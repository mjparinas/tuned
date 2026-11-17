package app.tuned.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.core.net.toUri
import app.tuned.BuildConfig
import app.tuned.core.ReleaseTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Updates from GitHub Releases (Melvin, 2026-11-17: "create github releases for now"). release.yml publishes a
 * signed APK on every promotion; this asks GitHub for the latest release, downloads a newer APK in the background,
 * and offers it with one tap. Android itself checks the new APK is signed with the same key as the installed app.
 *
 * Copies installed from Play use Play's own updates instead ([app.tuned.ui.AppUpdates]).
 */
class GitHubUpdates(
    private val context: Context,
    private val http: OkHttpClient,
) {
    sealed interface State {
        data object Idle : State

        data class Ready(
            val versionName: String,
            val apk: File,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        val size: Long,
        @SerialName("browser_download_url") val url: String,
    )

    /** Checks GitHub and downloads a newer APK if there is one. Returns true when an update is ready to install. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) return@withContext false
        try {
            val release = latest() ?: return@withContext false
            if (release.draft || release.prerelease || !ReleaseTag.isNewer(release.tag, BuildConfig.VERSION_CODE)) {
                return@withContext false
            }
            val asset = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext false
            val apk = download(asset, ReleaseTag.versionCode(release.tag) ?: 0)
            _state.value = State.Ready(ReleaseTag.versionName(release.tag) ?: release.tag, apk)
            true
        } catch (_: IOException) {
            // Offline or GitHub unreachable: try again next time.
            false
        }
    }

    private fun latest(): Release? {
        val request =
            Request
                .Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else json.decodeFromString<Release>(response.body.string())
        }
    }

    private fun download(
        asset: Asset,
        code: Int,
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "tuned-$code.apk")
        if (apk.length() == asset.size) return apk
        dir.listFiles()?.forEach { it.delete() }
        val partial = File(dir, "download.tmp")
        val ok =
            http.newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
                response.isSuccessful && partial.outputStream().use { out -> response.body.byteStream().copyTo(out) } == asset.size
            }
        if (!ok || !partial.renameTo(apk)) throw IOException("The update download failed or was incomplete")
        return apk
    }

    /**
     * Hands the downloaded APK to Android's installer, which asks the person to confirm. The first time, Android
     * first asks them to allow Tuned to install apps; this opens that setting.
     */
    fun install(activity: Context) {
        val ready = _state.value as? State.Ready ?: return
        val packages = activity.packageManager
        if (!packages.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val installer = packages.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("tuned.apk", 0, ready.apk.length()).use { out ->
                ready.apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result =
                PendingIntent.getBroadcast(
                    activity,
                    id,
                    Intent(activity, InstallResultReceiver::class.java).setPackage(activity.packageName),
                    // Mutable: the installer adds the status and, when it needs the person, a confirm intent.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            session.commit(result.intentSender)
        }
    }

    companion object {
        const val REPO = "mjparinas/tuned"
    }
}
