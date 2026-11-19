package app.tuned.diagnostics

import android.content.Context
import app.tuned.R
import app.tuned.core.ClockCheck
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The wrong-clock hint (Melvin, 2026-11-19, from the Horizon Maps review). Every answer through the shared HTTP client
 * notes how far the server's time is from the device's ([ClockCheck]); when the device's date is plainly wrong, a failed
 * sign-in or refresh says to check the date and time, the usual cause on TV boxes, instead of only "couldn't connect".
 */
object DeviceClock {
    private const val TAG = "Clock"

    /** Server minus device time at the last answer that had a `Date` header; null until one has. */
    @Volatile
    private var skewMs: Long? = null

    @Volatile
    private var warned = false

    /** Added to the shared OkHttp client. Reads one header; never changes the request or the answer. */
    val interceptor =
        Interceptor { chain ->
            val response: Response = chain.proceed(chain.request())
            ClockCheck.httpDate(response.header("Date"))?.let { server ->
                skewMs = server - response.receivedResponseAtMillis
            }
            response
        }

    fun looksWrong(): Boolean {
        val wrong = ClockCheck.looksWrong(System.currentTimeMillis(), skewMs)
        if (wrong && !warned) {
            warned = true
            AppLog.w(TAG, "The device's date looks wrong (server differs by ${skewMs?.div(MS_PER_MINUTE)} minutes)")
        }
        return wrong
    }

    /** [message] with the hint after it when the clock looks wrong, else [message] as it is. */
    fun explain(
        context: Context,
        message: String,
    ): String = if (looksWrong()) "$message\n\n${context.getString(R.string.clock_hint)}" else message

    private const val MS_PER_MINUTE = 60_000L
}
