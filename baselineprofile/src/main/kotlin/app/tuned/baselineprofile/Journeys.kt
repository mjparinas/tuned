package app.tuned.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream

/** The release app's id; the benchmark build type keeps it. */
const val TARGET_PACKAGE = "app.tuned"

private const val SIGN_IN_TIMEOUT_MS = 120_000L
private const val SCREEN_TIMEOUT_MS = 15_000L
private const val PLAY_MS = 3_000L

/** Starts the fake provider with the clip from this test app's assets. */
fun startFakeProvider() {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    FakeProvider.start(assets.open("clip.mpegts").use { it.readBytes() })
}

/**
 * Adds the fake provider's playlist link the first time the app opens on this install, then waits for the cached
 * channels. Later launches skip straight to waiting, which is the start people see every day.
 */
fun MacrobenchmarkScope.signInIfNeeded() {
    startActivityAndWait()
    val link = device.wait(Until.findObject(By.text("Playlist link")), SCREEN_TIMEOUT_MS)
    if (link != null) {
        link.click()
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), SCREEN_TIMEOUT_MS).text = FakeProvider.PLAYLIST_URL
        device.findObject(By.text("Add playlist")).click()
    }
    waitForChannels(SIGN_IN_TIMEOUT_MS)
}

fun MacrobenchmarkScope.waitForChannels(timeoutMs: Long = SCREEN_TIMEOUT_MS) {
    checkNotNull(device.wait(Until.findObject(By.text(FakeProvider.FIRST_CHANNEL)), timeoutMs)) {
        "Live TV never listed the channels. On screen: ${onScreen()}"
    }
}

/** The text on screen, for a failure message, so a failed run says where it stopped. */
private fun MacrobenchmarkScope.onScreen(): String {
    val dump = ByteArrayOutputStream()
    device.dumpWindowHierarchy(dump)
    return Regex("""(?:text|content-desc)="([^"]+)"""").findAll(dump.toString()).map { it.groupValues[1] }.joinToString(" | ")
}

/** Live TV, Movies and Shows scrolled, then a channel played and closed: what people do in the first minute. */
fun MacrobenchmarkScope.browseAndPlay() {
    scrollList()
    openTab("Movies", "Film ")
    openTab("Shows", "Show ")
    openTab("Live TV", "Channel ")
    device.findObject(By.text(FakeProvider.FIRST_CHANNEL)).click()
    device.waitForIdle()
    Thread.sleep(PLAY_MS)
    device.pressBack()
    waitForChannels()
}

private fun MacrobenchmarkScope.openTab(
    tab: String,
    itemPrefix: String,
) {
    device.findObject(By.text(tab))?.click()
    device.wait(Until.hasObject(By.textStartsWith(itemPrefix)), SCREEN_TIMEOUT_MS)
    scrollList()
}

private fun MacrobenchmarkScope.scrollList() {
    val list = device.findObject(By.scrollable(true)) ?: return
    list.setGestureMargin(device.displayWidth / 5)
    repeat(3) { list.fling(Direction.DOWN) }
    list.fling(Direction.UP)
    device.waitForIdle()
}
