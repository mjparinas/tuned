package app.tuned.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.tuned.container
import app.tuned.core.ScreenLayout
import app.tuned.data.db.ItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The channels in the current category, down the right of the video, each with what's on now. Picking one switches to
 * it on the same connection (the player only ever holds one stream). On a tablet in landscape the guide sits beside
 * the video, which shrinks to make room ([ScreenLayout.guideBesideVideo]); elsewhere it slides over the video.
 */
@OptIn(UnstableApi::class)
class MiniGuide(
    private val activity: Activity,
    private val player: () -> ExoPlayer?,
    private val queue: () -> List<ItemEntity>,
    private val beside: List<View>,
) {
    private val density get() = activity.resources.displayMetrics.density
    private var channels: List<ItemEntity> = emptyList()
    private var onNow: Map<String, String> = emptyMap()
    private val adapter = Adapter()

    val view: ListView =
        ListView(activity).apply {
            setBackgroundColor(0xE6101014.toInt())
            divider = null
            visibility = View.GONE
            adapter = this@MiniGuide.adapter
            setOnItemClickListener { _, _, position, _ ->
                player()?.let { p ->
                    p.seekToDefaultPosition(position)
                    p.playWhenReady = true
                }
                close()
            }
        }

    val isOpen get() = view.isVisible

    fun open(scope: CoroutineScope) {
        channels = queue()
        onNow = emptyMap()
        adapter.notifyDataSetChanged()
        view.visibility = View.VISIBLE
        place()
        val current = player()?.currentMediaItemIndex ?: 0
        view.setSelection((current - 2).coerceAtLeast(0))
        view.requestFocus()
        scope.launch {
            val ids = channels.mapNotNull { it.tvgId }.distinct()
            val now = System.currentTimeMillis()
            val programmes = activity.container.db.programmes()
            // SQLite caps the number of parameters in one query; a big category is looked up in parts.
            onNow = ids.chunked(CHUNK).flatMap { programmes.onNow(it, now) }.associate { it.channelId to it.title }
            adapter.notifyDataSetChanged()
        }
    }

    fun close() {
        view.visibility = View.GONE
        place()
    }

    /** Sizes the guide for the screen, and makes room for it beside the video when there is room; again on rotation. */
    fun place() {
        val params = view.layoutParams ?: return
        val config = activity.resources.configuration
        val docked = isOpen && ScreenLayout.guideBesideVideo(config.screenWidthDp, config.screenHeightDp)
        val width = ((if (docked) ScreenLayout.GUIDE_PANE_DP else OVERLAY_DP) * density).toInt()
        if (params.width != width) {
            params.width = width
            view.layoutParams = params
        }
        for (v in beside) {
            val p = v.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val end = if (docked) width else 0
            if (p.marginEnd != end) {
                p.marginEnd = end
                v.layoutParams = p
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = channels.size

        override fun getItem(position: Int) = channels[position]

        override fun getItemId(position: Int) = channels[position].id

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val row = convertView as? LinearLayout ?: newRow()
            val name = row.getChildAt(0) as TextView
            val now = row.getChildAt(1) as TextView
            val channel = channels[position]
            val playing = player()?.currentMediaItemIndex == position
            name.text = channel.name
            name.setTextColor(if (playing) PLAYING_COLOUR else Color.WHITE)
            val title = channel.tvgId?.let(onNow::get)
            now.text = title.orEmpty()
            now.visibility = if (title == null) View.GONE else View.VISIBLE
            return row
        }

        private fun newRow() = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            addView(
                TextView(activity).apply {
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(
                TextView(activity).apply {
                    textSize = 13f
                    setTextColor(0xB3FFFFFF.toInt())
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
    }

    private companion object {
        const val CHUNK = 500
        const val OVERLAY_DP = 320

        /** A light violet that reads on the dark panel whatever the app's accent. */
        val PLAYING_COLOUR = 0xFFB9A8FF.toInt()
    }
}
