package com.tunedplayer.app.updates

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.data.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The whole GitHub path, from asking for the latest release to an APK ready to install, against a local server that
 * answers the way GitHub does. The answers start from what GitHub really returned for this repository on 2026-11-17
 * (`updates/latest-v1.2.0.json`); a test changes only the tag and where the APK lives.
 *
 * Melvin (2026-11-17): "I've updated multiple times and the new update is available functionality never works".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubUpdatesTest {
    private val server = MockWebServer()
    private val paths = CopyOnWriteArrayList<String>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val apk = ByteArray(200_000) { (it % 241).toByte() }

    /** 1.2.0 as published: version code 1002000, read from the APK on the release. */
    private val installed120 = 1_002_000

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun updates(installedCode: Int) = GitHubUpdates(
        context,
        OkHttpClient(),
        api = server.url("/").toString().trimEnd('/'),
        installedCode = installedCode,
        enabled = true,
        downloader = Downloader(OkHttpClient(), backoffMs = { 0 }),
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    /** GitHub's real answer for v1.2.0, re-tagged as [tag], with its APK served from here. */
    private fun release(
        tag: String,
        size: Long = apk.size.toLong(),
        digest: String = "sha256:${sha256(apk)}",
    ): String {
        val real =
            checkNotNull(javaClass.classLoader?.getResource("updates/latest-v1.2.0.json")).readText()
        return real
            .replace("\"v1.2.0\"", "\"$tag\"")
            .replace("https://github.com/mjparinas/tuned/releases/download/v1.2.0/tuned.apk", server.url("/download/tuned.apk").toString())
            .replace("\"size\": 5734691", "\"size\": $size")
            .replace(Regex("\"digest\": \"sha256:[0-9a-f]+\""), "\"digest\": \"$digest\"")
    }

    private fun serve(
        latest: () -> MockResponse,
        dropApkTimes: Int = 0,
    ) {
        var drops = dropApkTimes
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    paths += path
                    return when (path) {
                        "/repos/mjparinas/tuned/releases/latest" -> {
                            latest()
                        }

                        // GitHub redirects the download to its file host; OkHttp has to follow it.
                        "/download/tuned.apk" -> {
                            MockResponse
                                .Builder()
                                .code(302)
                                .addHeader("Location", server.url("/files/tuned.apk").toString())
                                .build()
                        }

                        "/files/tuned.apk" -> {
                            MockResponse
                                .Builder()
                                .body(okio.Buffer().write(apk))
                                .apply { if (drops-- > 0) onResponseBody(SocketEffect.CloseSocket()) }
                                .build()
                        }

                        else -> {
                            MockResponse.Builder().code(404).build()
                        }
                    }
                }
            }
    }

    private fun json(body: String) = MockResponse
        .Builder()
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body)
        .build()

    @Test
    fun anOlderCopyFindsDownloadsAndOffersTheNewRelease() = runBlocking<Unit> {
        serve({ json(release("v1.2.1")) })
        val updates = updates(installed120)

        assertEquals(GitHubUpdates.Outcome.READY, updates.check())

        val ready = assertIs<GitHubUpdates.State.Ready>(updates.state.value)
        assertEquals("1.2.1", ready.versionName)
        assertContentEquals(apk, ready.apk.readBytes())
        assertEquals(listOf("/repos/mjparinas/tuned/releases/latest", "/download/tuned.apk", "/files/tuned.apk"), paths)
    }

    @Test
    fun theRealLatestReleaseIsNotOfferedToTheSameVersion() = runBlocking<Unit> {
        serve({ json(release("v1.2.0")) })
        val updates = updates(installed120)

        assertEquals(GitHubUpdates.Outcome.UP_TO_DATE, updates.check())
        assertIs<GitHubUpdates.State.Idle>(updates.state.value)
        assertEquals(listOf("/repos/mjparinas/tuned/releases/latest"), paths)
    }

    @Test
    fun aCopyFromTheOldBuildTagsIsOfferedThePlainRelease() = runBlocking<Unit> {
        serve({ json(release("v1.2.0")) })
        assertEquals(GitHubUpdates.Outcome.READY, updates(installedCode = 4).check())
    }

    @Test
    fun aDroppedDownloadPicksUpAgain() = runBlocking<Unit> {
        serve({ json(release("v1.3.0")) }, dropApkTimes = 2)
        val updates = updates(installed120)

        assertEquals(GitHubUpdates.Outcome.READY, updates.check())
        assertContentEquals(apk, assertIs<GitHubUpdates.State.Ready>(updates.state.value).apk.readBytes())
    }

    @Test
    fun aDamagedDownloadIsNeverOffered() = runBlocking<Unit> {
        serve({ json(release("v1.2.1", digest = "sha256:${"0".repeat(64)}")) })
        val updates = updates(installed120)

        assertEquals(GitHubUpdates.Outcome.FAILED, updates.check())
        assertIs<GitHubUpdates.State.Idle>(updates.state.value)
    }

    @Test
    fun githubTroubleSaysCouldNotCheckAndLeavesTheButtonWorking() = runBlocking<Unit> {
        val answers = ArrayDeque(listOf(MockResponse.Builder().code(403).build(), json("{\"message\":")))
        serve({ answers.removeFirstOrNull() ?: json(release("v1.2.1")) })
        val updates = updates(installed120)

        assertEquals(GitHubUpdates.Outcome.FAILED, updates.check(force = true)) // rate limited
        assertIs<GitHubUpdates.State.Idle>(updates.state.value)
        assertEquals(GitHubUpdates.Outcome.FAILED, updates.check(force = true)) // cut-off JSON
        assertIs<GitHubUpdates.State.Idle>(updates.state.value)
        assertEquals(GitHubUpdates.Outcome.READY, updates.check(force = true))
    }

    @Test
    fun aCheckCutShortDoesNotLeaveSettingsStuckOnChecking() = runBlocking<Unit> {
        serve({ json(release("v1.2.1")).newBuilder().headersDelay(2, TimeUnit.SECONDS).build() })
        val updates = updates(installed120)

        val job = launch(Dispatchers.Default) { updates.check() }
        // Cancel while GitHub is answering, as when the screen closes mid-check.
        while (paths.isEmpty()) Thread.sleep(5)
        assertIs<GitHubUpdates.State.Checking>(updates.state.value)
        job.cancelAndJoin()

        // Before: the screen that started it closed, the check stopped, and "Checking" stayed for good, so the
        // Settings button did nothing.
        assertIs<GitHubUpdates.State.Idle>(updates.state.value)
        // And the next chance asks again at once, rather than in an hour.
        assertEquals(GitHubUpdates.Outcome.READY, updates.check())
        assertEquals(2, paths.count { it.endsWith("/releases/latest") })
    }

    private fun allowInstalls() = shadowOf(context.packageManager).setCanRequestPackageInstalls(true)

    private suspend fun GitHubUpdates.await(done: (GitHubUpdates.State) -> Boolean): GitHubUpdates.State =
        withTimeout(WAIT_MS) { state.first(done) }

    private suspend fun GitHubUpdates.handedOver(): GitHubUpdates.State = await {
        (it is GitHubUpdates.State.Installing && it.step == GitHubUpdates.InstallStep.OPEN_INSTALLER) ||
            it is GitHubUpdates.State.InstallFailed
    }

    /**
     * Melvin (2026-11-21): "When I click on install, there should be some type of spinner or an indication that it's
     * downloading, processing, and then it installs". Before, Install copied the APK into Android's installer on the
     * screen thread and the banner didn't change until the installer appeared seconds later.
     */
    @Test
    fun installSaysItIsWorkingAtOnceAndHandsTheApkToAndroid() = runBlocking<Unit> {
        serve({ json(release("v1.2.1")) })
        val updates = updates(installed120)
        assertEquals(GitHubUpdates.Outcome.READY, updates.check())
        allowInstalls()

        updates.install(context)

        // On the tap itself, before any of the work.
        assertIs<GitHubUpdates.State.Installing>(updates.state.value)
        val handed = assertIs<GitHubUpdates.State.Installing>(updates.handedOver())
        assertEquals(GitHubUpdates.InstallStep.OPEN_INSTALLER, handed.step)
        assertEquals("1.2.1", handed.ready.versionName)
        // A second tap while it works starts nothing new.
        updates.install(context)
        assertEquals(handed, updates.state.value)

        // Android's confirm window opens, and the person cancels it: back to Install, no error.
        updates.onInstallerAnswer(PackageInstaller.STATUS_PENDING_USER_ACTION, null)
        assertEquals(GitHubUpdates.InstallStep.CONFIRM, assertIs<GitHubUpdates.State.Installing>(updates.state.value).step)
        updates.onInstallerAnswer(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals(handed.ready, updates.state.value)
    }

    @Test
    fun leavingTheConfirmWindowOpenShowsInstallAgainOnReturn() = runBlocking<Unit> {
        serve({ json(release("v1.2.1")) })
        val updates = updates(installed120)
        updates.check()
        allowInstalls()
        updates.install(context)
        val handed = assertIs<GitHubUpdates.State.Installing>(updates.handedOver())
        updates.onInstallerAnswer(PackageInstaller.STATUS_PENDING_USER_ACTION, null)

        updates.onReturned(context)

        assertEquals(handed.ready, updates.state.value)
    }

    @Test
    fun anUpdateTheCacheLostIsDownloadedAgainOnInstall() = runBlocking<Unit> {
        serve({ json(release("v1.2.1")) })
        val updates = updates(installed120)
        updates.check()
        val ready = assertIs<GitHubUpdates.State.Ready>(updates.state.value)
        ready.apk.delete() // Android cleared Tuned's cache
        paths.clear()
        allowInstalls()

        updates.install(context)

        assertIs<GitHubUpdates.State.Installing>(updates.handedOver())
        assertEquals(listOf("/download/tuned.apk", "/files/tuned.apk"), paths)
        assertContentEquals(apk, ready.apk.readBytes())
    }

    @Test
    fun aFailedDownloadSaysSoAndTryAgainWorks() = runBlocking<Unit> {
        serve({ json(release("v1.2.1")) })
        val updates = updates(installed120)
        updates.check()
        assertIs<GitHubUpdates.State.Ready>(updates.state.value).apk.delete()
        val working = server.dispatcher
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(404).build()
            }
        allowInstalls()

        updates.install(context)

        val failed = assertIs<GitHubUpdates.State.InstallFailed>(updates.handedOver())
        assertEquals(GitHubUpdates.InstallProblem.DOWNLOAD, failed.problem)
        // A check doesn't throw the waiting update away.
        assertEquals(GitHubUpdates.Outcome.READY, updates.check(force = true))

        server.dispatcher = working
        updates.install(context)
        assertIs<GitHubUpdates.State.Installing>(updates.handedOver())
    }

    @Test
    fun theInstallerRunningOutOfSpaceSaysSo() = runBlocking<Unit> {
        serve({ json(release("v1.2.1")) })
        val updates = updates(installed120)
        updates.check()
        allowInstalls()
        updates.install(context)
        updates.handedOver()

        updates.onInstallerAnswer(PackageInstaller.STATUS_FAILURE_STORAGE, "not enough space")

        assertEquals(GitHubUpdates.InstallProblem.SPACE, assertIs<GitHubUpdates.State.InstallFailed>(updates.state.value).problem)
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
