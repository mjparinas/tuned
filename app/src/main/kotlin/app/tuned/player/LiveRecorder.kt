package app.tuned.player

import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.tuned.R
import app.tuned.container
import app.tuned.core.TsAlign
import app.tuned.data.Recordings
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Recording (from the app audit, item 12): the Record button saves the live channel being watched to Downloads, from
 * the same stream the player is already receiving ([LiveBuffer]), so it never opens a second connection and can't
 * record anything but what's on screen. It stops on the button, on a channel change, when the player closes, or when
 * the device is nearly full. A small "REC" mark stays in the corner while it runs, even with the controls hidden.
 */
internal class LiveRecorder(
    private val activity: ComponentActivity,
    private val hooks: Hooks,
) {
    class Hooks(
        /** The live channel playing now, or null for films, episodes and catch-up. */
        val channel: () -> ItemEntity?,
        /** The uri the player is on, to find its stream. */
        val uri: () -> Uri?,
        val stream: (Uri?) -> LiveBuffer?,
        val casting: () -> Boolean,
        val say: (String) -> Unit,
    )

    private val recordings = activity.container.recordings
    private val density = activity.resources.displayMetrics.density

    @Volatile
    private var active: Sink? = null
    private var starting = false

    val button: ImageButton =
        ImageButton(activity).apply {
            val ripple = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { toggle() }
            visibility = View.GONE
        }

    private val mark =
        TextView(activity).apply {
            text = activity.getString(R.string.record_indicator)
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(MARK_RED)
            val h = (8 * density).toInt()
            val v = (3 * density).toInt()
            setPadding(h, v, h, v)
            visibility = View.GONE
        }

    init {
        showState()
    }

    fun attach(root: FrameLayout) {
        val margin = (16 * density).toInt()
        root.addView(
            mark,
            FrameLayout
                .LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(margin, margin, margin, margin) },
        )
    }

    /** With the controls: the button shows only on live channels that can be recorded, or while recording. */
    fun onControls(visibility: Int) {
        if (visibility != View.VISIBLE) return
        val can = hooks.channel() != null && hooks.stream(hooks.uri()) != null && !hooks.casting()
        button.visibility = if (can || active != null) View.VISIBLE else View.GONE
    }

    /** Picture-in-picture: nothing over the video. */
    fun hideMark(hidden: Boolean) {
        mark.visibility = if (active != null && !hidden) View.VISIBLE else View.GONE
    }

    fun toggle() {
        when {
            active != null -> stop()
            !starting -> start()
        }
    }

    /** The player is closing: what was recorded is kept. */
    fun release() {
        active?.buffer?.stopRecording()
    }

    private fun start() {
        val channel = hooks.channel()
        val stream = hooks.stream(hooks.uri())
        val problem =
            when {
                hooks.casting() -> R.string.record_not_casting
                channel == null || stream == null -> R.string.record_live_only
                recordings.freeBytes() < MIN_FREE_BYTES -> R.string.record_no_space
                else -> null
            }
        if (problem != null || channel == null || stream == null) {
            hooks.say(activity.getString(problem ?: R.string.record_live_only))
            return
        }
        starting = true
        activity.lifecycleScope.launchSafely(TAG, onError = { starting = false }) {
            val now = System.currentTimeMillis()
            val title =
                channel.tvgId?.let { id ->
                    activity.container.db
                        .programmes()
                        .nowAndNext(id, now)
                        .firstOrNull { it.start <= now }
                        ?.title
                }
            val details = Recordings.Recording(id = now, channelId = channel.id, channel = channel.name, title = title, logo = channel.logo)
            val file = withContext(Dispatchers.IO) { recordings.begin(details) }
            val sink = Sink(details, file, stream)
            active = sink
            starting = false
            stream.startRecording(sink)
            showState()
            hooks.say(activity.getString(R.string.record_started, channel.name))
        }
    }

    private fun stop() {
        val sink = active ?: return
        activity.lifecycleScope.launchSafely(TAG) { withContext(Dispatchers.IO) { sink.buffer.stopRecording() } }
    }

    private fun showState() {
        val on = active != null
        button.setImageResource(if (on) R.drawable.ic_player_record_stop else R.drawable.ic_player_record)
        button.contentDescription = activity.getString(if (on) R.string.record_stop else R.string.record_start)
        mark.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Ended from the loader thread, the button or the player closing: back to the main thread to say so. */
    private fun ended(message: Int) {
        activity.runOnUiThread {
            showState()
            Toast.makeText(activity.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Writes the channel's bytes to the file, starting at a packet boundary, while the device has room. */
    private inner class Sink(
        private val details: Recordings.Recording,
        private val file: File,
        val buffer: LiveBuffer,
    ) : LiveBuffer.Recorder {
        private var out: OutputStream? = null
        private var bytes = 0L
        private var sinceCheck = 0L
        private var problem: Int? = null

        override fun write(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (problem != null) return
            try {
                val stream = out ?: return startAt(data, offset, length)
                stream.write(data, offset, length)
                count(length)
            } catch (e: IOException) {
                AppLog.w(TAG, "Couldn't write the recording", e)
                fail(R.string.record_failed)
            }
        }

        /** The first chunk: from its first whole packet, or wait for the next chunk if none can be found. */
        private fun startAt(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val start = TsAlign.firstPacket(data, offset, length)
            if (start < 0) return
            val stream = BufferedOutputStream(FileOutputStream(file), BUFFER).also { out = it }
            stream.write(data, start, offset + length - start)
            count(offset + length - start)
        }

        private fun count(n: Int) {
            bytes += n
            sinceCheck += n
            if (sinceCheck < CHECK_EVERY) return
            sinceCheck = 0
            if (recordings.freeBytes() < MIN_FREE_BYTES) fail(R.string.record_space_ran_out)
        }

        /** Stops writing now; the buffer ends the recording once this chunk is done. */
        private fun fail(message: Int) {
            problem = message
            activity.lifecycleScope.launchSafely(TAG) { withContext(Dispatchers.IO) { buffer.stopRecording() } }
        }

        override fun end() {
            try {
                out?.close()
            } catch (e: IOException) {
                AppLog.w(TAG, "Couldn't finish the recording", e)
            }
            out = null
            if (active === this) active = null
            recordings.finish(details.copy(durationMs = System.currentTimeMillis() - details.id, bytes = bytes))
            ended(problem ?: R.string.record_saved)
        }
    }

    private companion object {
        const val TAG = "LiveRecorder"
        const val MARK_RED = 0xCCE53935.toInt()
        const val BUFFER = 256 * 1024

        /** Stop with this much left, so the rest of the phone keeps working. */
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        const val CHECK_EVERY = 16L * 1024 * 1024
    }
}
