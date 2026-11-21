package com.tunedplayer.app.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.core.ReleaseTag
import com.tunedplayer.app.data.Downloader
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.diagnostics.startActivitySafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Updates from GitHub Releases (Melvin, 2026-11-17: "create github releases for now"). release.yml publishes a
 * signed APK on every promotion; this asks GitHub for the latest release, downloads a newer APK in the background,
 * and offers it with one tap. Android itself checks the new APK is signed with the same key as the installed app.
 *
 * Copies installed from Play use Play's own updates instead ([com.tunedplayer.app.ui.AppUpdates]).
 */
class GitHubUpdates(
    private val context: Context,
    private val http: OkHttpClient,
    /** Tests point these at a local server and an older installed copy. */
    private val api: String = "https://api.github.com",
    private val installedCode: Int = BuildConfig.VERSION_CODE,
    private val enabled: Boolean = !BuildConfig.DEBUG && BuildConfig.GITHUB_UPDATES,
    private val downloader: Downloader = Downloader(http),
    /** Where an install runs: the app's scope, so leaving the screen part way doesn't stop it. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** How long Android's installer has to answer once the APK is handed over, before Install says it didn't open. */
    private val installerTimeoutMs: Long = INSTALLER_TIMEOUT_MS,
) {
    sealed interface State {
        data object Idle : State

        /**
         * Asking GitHub, or downloading the new APK in the background: [versionName] once there is one to download, and
         * [done] of [total] bytes (-1 when GitHub doesn't say), for Settings › Check for updates.
         */
        data class Checking(
            val versionName: String? = null,
            val done: Long = 0,
            val total: Long = -1,
        ) : State

        data class Ready(
            val versionName: String,
            val apk: File,
            /** The APK's size on GitHub, its `sha256:` digest, and where to download it again if the copy here goes missing. */
            val size: Long = -1,
            val digest: String? = null,
            val url: String = "",
        ) : State

        /**
         * Install was tapped, and this is the step it is on (Melvin, 2026-11-21: "there needs to be some user feedback so
         * they know that the app didn't just crash or freeze"): [done] of [total] bytes, or -1 when the step has nothing
         * to count.
         */
        data class Installing(
            val ready: Ready,
            val step: InstallStep,
            val done: Long = 0,
            val total: Long = -1,
        ) : State {
            /** 0 to 1 while there is something to count, otherwise null (a moving bar). */
            val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        }

        /** The install stopped, and why; Try again starts it over. */
        data class InstallFailed(
            val ready: Ready,
            val problem: InstallProblem,
        ) : State
    }

    /**
     * What happens after Install, numbered 1 to [INSTALL_STEPS]. Step 1 is downloading again only when the copy here went
     * missing or was damaged; otherwise it is checking that copy. Then the APK is copied into Android's installer, which
     * opens its confirm window and waits there for the person.
     */
    enum class InstallStep(
        val number: Int,
    ) {
        DOWNLOAD(1),
        CHECK(1),
        PREPARE(2),
        OPEN_INSTALLER(3),
        CONFIRM(3),
    }

    /**
     * Why an install stopped. [PERMISSION]: Tuned isn't allowed to install apps and Android's setting for it couldn't be
     * opened, or the person came back without turning it on. [INSTALLER]: the APK was handed over and Android's installer
     * never answered. Both happened silently on an Nvidia Shield (Melvin, 2026-11-21: "when I click install, nothing
     * happens. And when I check the logs, there are nothing in the logs").
     */
    enum class InstallProblem { DOWNLOAD, SPACE, PERMISSION, INSTALLER, REFUSED, OTHER }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val json = Json { ignoreUnknownKeys = true }

    /** What a check found, for Settings › Check for updates. */
    enum class Outcome { UP_TO_DATE, READY, FAILED }

    private val checking = Mutex()

    @Volatile private var lastCheck = 0L

    @Volatile private var lastFailed = false

    /** Install opened Android's "allow Tuned to install apps" setting; coming back with it on carries on. */
    @Volatile private var awaitingPermission = false

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
        if (_state.value.pending != null) return@withLock Outcome.READY
        val now = System.currentTimeMillis()
        if (!ReleaseTag.checkDue(lastCheck, now, force, lastFailed)) return@withLock Outcome.UP_TO_DATE
        lastCheck = now
        _state.value = State.Checking()
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
        val versionName = ReleaseTag.versionName(release.tag) ?: release.tag
        _state.value = State.Checking(versionName, 0, asset.size)
        val apk =
            download(asset, ReleaseTag.versionCode(release.tag) ?: 0) { bytes ->
                _state.value = State.Checking(versionName, bytes, asset.size)
            }
        _state.value = State.Ready(versionName, apk, asset.size, asset.digest, asset.url)
        return Outcome.READY
    }

    /** Copies installed from Play update through Play ([com.tunedplayer.app.ui.AppUpdates]), never from here. */
    fun fromPlay(): Boolean = !BuildConfig.GITHUB_UPDATES || installedFromPlay()

    private fun installedFromPlay(): Boolean = runCatching {
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

    /**
     * The release notes of [versionName]'s GitHub release, for What's new in Settings, or null when GitHub can't be
     * reached or has no such release (a debug build's version, say). Off the main thread; logs a failure.
     */
    suspend fun releaseNotes(versionName: String = BuildConfig.VERSION_NAME): String? = withContext(Dispatchers.IO) {
        attempt(TAG, "release notes") {
            val request =
                Request
                    .Builder()
                    .url("$api/repos/$REPO/releases/tags/${ReleaseTag.of(versionName)}")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else json.decodeFromString<Notes>(response.body.string()).body
            }
        }.getOrNull()
    }

    @Serializable
    private data class Notes(
        val body: String? = null,
    )

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
        progress: (Long) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "tuned-$code.apk")
        if (apk.length() == asset.size && matches(apk, asset.digest)) return apk
        dir.listFiles()?.forEach { it.delete() }
        downloader.download(asset.url, apk, progress = { bytes, _ -> progress(bytes) })
        if (apk.length() != asset.size || !matches(apk, asset.digest)) {
            apk.delete()
            throw IOException("The update download was incomplete or damaged")
        }
        return apk
    }

    private fun matches(
        file: File,
        digest: String?,
        progress: (Long) -> Unit = {},
    ): Boolean {
        val want = digest?.takeIf { it.startsWith(SHA256) }?.removePrefix(SHA256)?.lowercase() ?: return true
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            var read = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
                read += n
                progress(read)
            }
        }
        return sha.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } == want
    }

    /**
     * Install was tapped: checks the downloaded APK (downloading it again if it went missing), copies it into Android's
     * installer and lets the installer ask the person to confirm, saying which step it is on all the way through
     * ([State.Installing]). The work runs off the screen thread: copying a 40 MB APK on it was the pause before anything
     * happened. Any failure is logged and shown with Try again ([State.InstallFailed]), never a crash. The first time,
     * Android first asks the person to allow Tuned to install apps; this opens that setting, and coming back with it on
     * carries on by itself ([onReturned]).
     */
    fun install(activity: Context) {
        val now = _state.value
        val ready = now.pending
        if (ready == null) {
            AppLog.w(TAG, "Install tapped with no update waiting (${now::class.simpleName})")
            return
        }
        if (now is State.Installing) {
            AppLog.i(TAG, "Install tapped again while on ${now.step.name.lowercase()}")
            return
        }
        AppLog.i(TAG, "Install tapped: Tuned ${ready.versionName}, Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
        val app = activity.applicationContext
        val allowed = attempt(TAG, "install permission") { app.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
        if (!allowed) {
            askPermission(activity, ready)
            return
        }
        awaitingPermission = false
        _state.value = State.Installing(ready, InstallStep.CHECK, 0, ready.size)
        scope.launchSafely(TAG, onError = { _state.value = State.InstallFailed(ready, InstallProblem.OTHER) }) {
            installSafely(app, ready)
        }
    }

    /**
     * Opens Android's "allow Tuned to install apps" setting. A TV's settings may not have the page for one app (or any
     * page for it), so this falls back to the list of apps and then to security settings; if none opens, Install says
     * what to turn on rather than doing nothing.
     */
    private fun askPermission(
        activity: Context,
        ready: State.Ready,
    ) {
        AppLog.i(TAG, "Tuned isn't allowed to install apps yet; opening Android's setting")
        val pages =
            listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
            )
        val opened = pages.firstOrNull { activity.startActivitySafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), TAG) }
        if (opened == null) {
            AppLog.w(TAG, "No settings page to allow installs could be opened")
            awaitingPermission = false
            _state.value = State.InstallFailed(ready, InstallProblem.PERMISSION)
        } else {
            AppLog.i(TAG, "Opened ${opened.action}${if (opened.data != null) " for Tuned" else ""}")
            awaitingPermission = true
        }
    }

    @Suppress("TooGenericExceptionCaught") // logged and shown with Try again, never a crash
    private suspend fun installSafely(
        context: Context,
        ready: State.Ready,
    ) {
        try {
            installNow(context, ready)
        } catch (e: CancellationException) {
            if (_state.value is State.Installing) _state.value = ready
            throw e
        } catch (e: Exception) {
            val problem = problemOf(e)
            AppLog.w(TAG, "Couldn't install the update (${problem.name.lowercase()})", e)
            _state.value = State.InstallFailed(ready, problem)
        }
    }

    private suspend fun installNow(
        context: Context,
        ready: State.Ready,
    ) = withContext(Dispatchers.IO) {
        val apk = ready.apk
        val size = if (ready.size > 0) ready.size else apk.length()
        val step = { s: InstallStep, done: Long -> _state.value = State.Installing(ready, s, done, size) }
        val good = apk.exists() && apk.length() == size && matches(apk, ready.digest) { step(InstallStep.CHECK, it) }
        if (!good) {
            // Android cleared the cache, or the copy was damaged: download it again rather than give up.
            if (ready.url.isBlank()) throw DownloadFailed(IOException("The downloaded update is missing"))
            apk.delete()
            step(InstallStep.DOWNLOAD, 0)
            try {
                downloader.download(ready.url, apk, progress = { bytes, _ -> step(InstallStep.DOWNLOAD, bytes) })
            } catch (e: IOException) {
                throw DownloadFailed(e)
            }
            step(InstallStep.CHECK, 0)
            if (apk.length() != size || !matches(apk, ready.digest) { step(InstallStep.CHECK, it) }) {
                apk.delete()
                throw DownloadFailed(IOException("The update download was incomplete or damaged"))
            }
        }
        step(InstallStep.PREPARE, 0)
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        AppLog.i(TAG, "Copying the update into Android's installer (session $id, $size bytes)")
        try {
            commit(context, installer, id, apk) { step(InstallStep.PREPARE, it) }
        } catch (e: CancellationException) {
            attempt(TAG, "abandon install session") { installer.abandonSession(id) }
            throw e
        } catch (e: IOException) {
            attempt(TAG, "abandon install session") { installer.abandonSession(id) }
            throw e
        }
        // Handed over: the installer answers through InstallResultReceiver, usually with its confirm window. It can
        // answer before this line runs, so only a step still preparing moves on.
        val handed = State.Installing(ready, InstallStep.OPEN_INSTALLER)
        _state.compareAndSet(State.Installing(ready, InstallStep.PREPARE, size, size), handed)
        AppLog.i(TAG, "Handed the update to Android's installer; waiting for its answer")
        // Some devices never answer at all: rather than "Opening Android's installer" for ever, say it didn't open.
        delay(installerTimeoutMs)
        if (_state.compareAndSet(handed, State.InstallFailed(ready, InstallProblem.INSTALLER))) {
            AppLog.w(TAG, "Android's installer didn't answer within ${installerTimeoutMs / MS_PER_SECOND} s")
        }
    }

    private fun commit(
        context: Context,
        installer: PackageInstaller,
        id: Int,
        apk: File,
        progress: (Long) -> Unit,
    ) {
        installer.openSession(id).use { session ->
            session.openWrite("tuned.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> copy(input, out, progress) }
                session.fsync(out)
            }
            val result =
                PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                    // Mutable: the installer adds the status and, when it needs the person, a confirm intent.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            session.commit(result.intentSender)
        }
    }

    /** Copies [input] to [out], saying how many bytes so far after each piece. */
    private fun copy(
        input: InputStream,
        out: OutputStream,
        progress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER)
        var written = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            written += n
            progress(written)
        }
    }

    /**
     * Android's installer answered ([InstallResultReceiver]): its confirm window is opening, the person cancelled it
     * (back to Install, no error), or it failed (shown with Try again). Success needs nothing: Android replaces Tuned.
     */
    fun onInstallerAnswer(
        status: Int,
        message: String?,
    ) {
        AppLog.i(TAG, "Android's installer answered: status $status${message?.let { ", $it" }.orEmpty()}")
        val now = _state.value as? State.Installing ?: return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _state.value = State.Installing(now.ready, InstallStep.CONFIRM)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Unit
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                _state.value = now.ready
            }

            else -> {
                AppLog.w(TAG, "The installer refused the update: status $status, ${message.orEmpty()}")
                val problem = if (status == PackageInstaller.STATUS_FAILURE_STORAGE) InstallProblem.SPACE else InstallProblem.REFUSED
                _state.value = State.InstallFailed(now.ready, problem)
            }
        }
    }

    /**
     * Tuned is back in front. Left on the installer's confirm window (Home, say), Install shows again rather than
     * "waiting" for ever; back from allowing Tuned to install apps, the install carries on by itself.
     */
    fun onReturned(activity: Context) {
        val now = _state.value
        if (now is State.Installing && now.step == InstallStep.CONFIRM) _state.value = now.ready
        val ready = now.pending
        if (!awaitingPermission || ready == null) return
        awaitingPermission = false
        if (attempt(TAG, "install permission") { activity.packageManager.canRequestPackageInstalls() }.getOrDefault(false)) {
            AppLog.i(TAG, "Allowed to install apps now; carrying on")
            install(activity)
        } else {
            AppLog.w(TAG, "Came back without allowing Tuned to install apps")
            _state.value = State.InstallFailed(ready, InstallProblem.PERMISSION)
        }
    }

    /** A download that failed, so the message says to check the connection. */
    private class DownloadFailed(
        cause: IOException,
    ) : IOException(cause.message, cause)

    private fun problemOf(e: Exception): InstallProblem = when {
        e is DownloadFailed -> InstallProblem.DOWNLOAD
        e is IOException && outOfSpace(e) -> InstallProblem.SPACE
        e is SecurityException -> InstallProblem.REFUSED
        else -> InstallProblem.OTHER
    }

    private fun outOfSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { cause -> cause.message.orEmpty().let { "ENOSPC" in it || "No space" in it } }

    companion object {
        const val REPO = "mjparinas/tuned"

        /** How many steps [InstallStep] numbers. */
        const val INSTALL_STEPS = 3
        private const val TAG = "Updates"
        private const val SHA256 = "sha256:"
        private const val BUFFER = 64 * 1024
        private const val PLAY_STORE = "com.android.vending"
        private const val INSTALLER_TIMEOUT_MS = 30_000L
        private const val MS_PER_SECOND = 1000
    }
}

/** The release waiting to install, whether it is ready, installing or stopped with a problem; null when there is none. */
val GitHubUpdates.State.pending: GitHubUpdates.State.Ready?
    get() =
        when (this) {
            is GitHubUpdates.State.Ready -> this
            is GitHubUpdates.State.Installing -> ready
            is GitHubUpdates.State.InstallFailed -> ready
            else -> null
        }
