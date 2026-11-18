package app.tuned.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the cached Live TV list, without the profile (code interpreted at first, as before) and with it.
 * The two medians are the before and after numbers in the workflow's summary (docs/decisions/speed.md).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private var signedIn = false

    @Before
    fun provider() = startFakeProvider()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            // The first run on a fresh install adds the playlist, then the process is killed again so every
            // measured start is cold and reads the cache.
            if (!signedIn) {
                signInIfNeeded()
                signedIn = true
            }
            pressHome()
            killProcess()
        },
    ) {
        startActivityAndWait()
        waitForChannels()
    }

    private companion object {
        const val ITERATIONS = 10
    }
}
