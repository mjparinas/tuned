package app.tuned.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.tuned.R
import coil3.dispose
import coil3.load
import coil3.size.Precision
import app.tuned.container
import app.tuned.core.ScreenLayout
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope

/**
 * The channels in the current category, down the right of the video, each with its logo on the left (Melvin,
 * 2026-11-18) and what's on now. Picking one switches to
 * it on the same connection (the player only ever holds one stream). On a tablet in landscape the guide sits beside
 * the video, which shrinks to make room ([ScreenLayout.guideBesideVideo]); elsewhere it slides over the video.
 */
@OptIn(UnstableApi::class)
class MiniGuide(
    private val activity: Activity,
    private val player: () -> ExoPlayer?,
    private val queue: () -> List<ItemEntity>,
    private val beside: List<View>,
    private val onClosed: () -> Unit,
) {
    private val density get() = activity.resources.displayMetrics.density
    private var channels: List<ItemEntity> = emptyList()
    private var onNow: Map<String, String> = emptyMap()
    private val adapter = Adapter()

    private val list: ListView =
        ListView(activity).apply {
            divider = null
            adapter = this@MiniGuide.adapter
            setOnItemClickListener { _, _, position, _ ->
                player()?.let { p ->
                    p.seekToDefaultPosition(position)
                    p.playWhenReady = true
                }
                close()
            }
        }

    /** "Channels" with a close button, so it's plain how to put the guide away (back and a tap beside it work too). */
    private val header: LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad * 2, pad / 2, pad / 2, pad / 2)
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.player_guide)
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_player_close)
                    contentDescription = activity.getString(R.string.player_guide_close)
                    val ripple = TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener { close() }
                },
            )
        }

    val view: LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6101014.toInt())
            visibility = View.GONE
            addView(header)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

    /** Covers the video beside the guide while it slides over it: a tap there puts the guide away. */
    val scrim: View =
        View(activity).apply {
            visibility = View.GONE
            setOnClickListener { close() }
        }

    val isOpen get() = view.isVisible

    fun open(scope: CoroutineScope) {
        channels = queue()
        onNow = emptyMap()
        adapter.notifyDataSetChanged()
        view.visibility = View.VISIBLE
        place()
        val current = player()?.currentMediaItemIndex ?: 0
        list.setSelection((current - 2).coerceAtLeast(0))
        list.requestFocus()
        scope.launchSafely("MiniGuide") {
            val ids = channels.mapNotNull { it.tvgId }.distinct()
            val now = System.currentTimeMillis()
            val programmes = activity.container.db.programmes()
            // SQLite caps the number of parameters in one query; a big category is looked up in parts.
            onNow = ids.chunked(CHUNK).flatMap { programmes.onNow(it, now) }.associate { it.channelId to it.title }
            adapter.notifyDataSetChanged()
        }
    }

    fun close() {
        if (!isOpen) return
        view.visibility = View.GONE
        place()
        onClosed()
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
        scrim.visibility = if (isOpen && !docked) View.VISIBLE else View.GONE
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
            val logo = row.getChildAt(0) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            val name = texts.getChildAt(0) as TextView
            val now = texts.getChildAt(1) as TextView
            val channel = channels[position]
            val playing = player()?.currentMediaItemIndex == position
            showLogo(logo, channel.logo)
            name.text = channel.name
            name.setTextColor(if (playing) PLAYING_COLOUR else Color.WHITE)
            val title = channel.tvgId?.let(onNow::get)
            now.text = title.orEmpty()
            now.visibility = if (title == null) View.GONE else View.VISIBLE
            return row
        }

        /**
         * The logo through the app's image cache, decoded at the size it's drawn so a long category stays light. A
         * channel without one keeps the space empty, so the names still line up.
         */
        private fun showLogo(
            view: ImageView,
            url: String?,
        ) {
            view.dispose()
            view.setImageDrawable(null)
            if (url.isNullOrBlank()) return
            val px = (LOGO_DP * density).toInt()
            view.load(url) {
                size(px, px)
                precision(Precision.INEXACT)
            }
        }

        private fun newRow() = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            val logoSize = (LOGO_DP * density).toInt()
            addView(
                ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(logoSize, logoSize).apply { marginEnd = pad },
            )
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
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
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    private companion object {
        const val CHUNK = 500
        const val OVERLAY_DP = 320
        const val LOGO_DP = 40

        /** A light violet that reads on the dark panel whatever the app's accent. */
        val PLAYING_COLOUR = 0xFFB9A8FF.toInt()
    }
}
