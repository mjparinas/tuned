package app.tuned.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import app.tuned.BuildConfig
import app.tuned.core.ReleaseTag
import app.tuned.data.Downloader
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.startActivitySafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
    /** Tests point these at a local server and an older installed copy. */
    private val api: String = "https://api.github.com",
    private val installedCode: Int = BuildConfig.VERSION_CODE,
    private val enabled: Boolean = !BuildConfig.DEBUG,
    private val downloader: Downloader = Downloader(http),
) {
    sealed interface State {
        data object Idle : State

        /** Asking GitHub, or downloading the new APK. */
        data object Checking : State

        data class Ready(
            val versionName: String,
            val apk: File,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val json = Json { ignoreUnknownKeys = true }

    /** What a check found, for Settings › Check for updates. */
    enum class Outcome { UP_TO_DATE, READY, FAILED }

    private val checking = Mutex()

    @Volatile private var lastCheck = 0L

    @Volatile private var lastFailed = false

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
        /** `sha256:<hex>`; GitHub lists it for assets uploaded since mid-2025. */
        val digest: String? = null,
    )

    /**
     * Checks GitHub and downloads a newer APK if there is one. On its own (app brought to the front, background
     * refresh) it asks at most once an hour, or a few minutes after a failed check; [force] (the Settings button)
     * always asks. Never throws, and always leaves [state] Idle or Ready, so the Settings button can't get stuck on
     * "Checking". A bad answer or a network error, whatever its type, must not crash Tuned, so any failure is
     * "try again later".
     */
    @Suppress("TooGenericExceptionCaught") // see above: every failure is FAILED, never a crash

    suspend fun check(force: Boolean = false): Outcome = checking.withLock {
        if (!enabled) return@withLock Outcome.UP_TO_DATE
        if (_state.value is State.Ready) return@withLock Outcome.READY
        val now = System.currentTimeMillis()
        if (!ReleaseTag.checkDue(lastCheck, now, force, lastFailed)) return@withLock Outcome.UP_TO_DATE
        lastCheck = now
        _state.value = State.Checking
        val outcome =
            try {
                fetch()
            } catch (e: CancellationException) {
                // Cut short, not answered: the next chance asks again straight away.
                lastCheck = 0L
                throw e
            } catch (_: Exception) {
                Outcome.FAILED
            } finally {
                if (_state.value is State.Checking) _state.value = State.Idle
            }
        lastFailed = outcome == Outcome.FAILED
        outcome
    }

    private suspend fun fetch(): Outcome {
        val release = withContext(Dispatchers.IO) { latest() } ?: return Outcome.FAILED
        val info = ReleaseTag.Release(release.tag, release.draft, release.prerelease, release.assets.map { it.name })
        val name = ReleaseTag.apkToInstall(info, installedCode) ?: return Outcome.UP_TO_DATE
        val asset = release.assets.first { it.name == name }
        val apk = download(asset, ReleaseTag.versionCode(release.tag) ?: 0)
        _state.value = State.Ready(ReleaseTag.versionName(release.tag) ?: release.tag, apk)
        return Outcome.READY
    }

    /** Copies installed from Play update through Play ([app.tuned.ui.AppUpdates]), never from here. */
    fun fromPlay(): Boolean = runCatching {
        val name = context.packageName
        val installer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(name).installingPackageName
            } else {
                @Suppress("DEPRECATION") // the replacement only exists from Android 11
                context.packageManager.getInstallerPackageName(name)
            }
        installer == PLAY_STORE
    }.getOrDefault(false)

    private fun latest(): Release? {
        val request =
            Request
                .Builder()
                .url("$api/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else json.decodeFromString<Release>(response.body.string())
        }
    }

    /**
     * Downloads the APK, resuming over dropped connections ([Downloader]). A copy already downloaded is reused, and
     * anything but the exact size (and checksum, when GitHub lists one) is thrown away, so a cut-short or damaged file
     * is never offered.
     */
    private suspend fun download(
        asset: Asset,
        code: Int,
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "tuned-$code.apk")
        if (apk.length() == asset.size && matches(apk, asset.digest)) return apk
        dir.listFiles()?.forEach { it.delete() }
        downloader.download(asset.url, apk)
        if (apk.length() != asset.size || !matches(apk, asset.digest)) {
            apk.delete()
            throw IOException("The update download was incomplete or damaged")
        }
        return apk
    }

    private fun matches(
        file: File,
        digest: String?,
    ): Boolean {
        val want = digest?.takeIf { it.startsWith(SHA256) }?.removePrefix(SHA256)?.lowercase() ?: return true
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        return sha.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } == want
    }

    /**
     * Hands the downloaded APK to Android's installer, which asks the person to confirm. The first time, Android
     * first asks them to allow Tuned to install apps; this opens that setting.
     */
    fun install(activity: Context) {
        val ready = _state.value as? State.Ready ?: return
        val packages = activity.packageManager
        if (!packages.canRequestPackageInstalls()) {
            activity.startActivitySafely(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                TAG,
            )
            return
        }
        val installer = packages.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        try {
            commit(activity, installer, installer.createSession(params), ready.apk)
        } catch (e: IOException) {
            // Out of space, or the downloaded file went missing: the next check downloads it again.
            AppLog.w(TAG, "Couldn't hand the update to the installer", e)
            _state.value = State.Idle
        } catch (e: SecurityException) {
            AppLog.w(TAG, "The installer refused the update", e)
        }
    }

    private fun commit(
        activity: Context,
        installer: PackageInstaller,
        id: Int,
        apk: File,
    ) {
        installer.openSession(id).use { session ->
            session.openWrite("tuned.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
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
        private const val TAG = "Updates"
        private const val SHA256 = "sha256:"
        private const val BUFFER = 64 * 1024
        private const val PLAY_STORE = "com.android.vending"
    }
}
