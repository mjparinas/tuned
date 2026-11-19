package app.tuned.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import app.tuned.R
import app.tuned.ui.MainActivity

/** What the home screen widgets share: the card they sit on, the Tuned header, a thin progress bar and the empty state. */
internal object WidgetParts {
    /** Opens Tuned where it was, without the "start on the last channel" jump a launch from the app icon makes. */
    fun openApp(context: Context): Action = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(AppShortcuts.ACTION_OPEN)
            .putExtra(AppShortcuts.EXTRA_SCREEN, AppShortcuts.HOME),
    )
}

/** The widget's card: the theme's background with the launcher's round corners. */
@Composable
internal fun WidgetCard(
    modifier: GlanceModifier = GlanceModifier,
    padding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.background)
            .cornerRadius(24.dp)
            .padding(padding),
        content = content,
    )
}

/** The Tuned mark and the widget's name; a tap opens the app. */
@Composable
internal fun WidgetHeader(title: String) {
    val context = LocalContext.current
    Row(
        GlanceModifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
            .clickable(WidgetParts.openApp(context)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TunedMark(22.dp, context.getString(R.string.widget_open_tuned))
        Spacer(GlanceModifier.width(8.dp))
        Text(
            title,
            maxLines = 1,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
        )
    }
}

/** Tuned's icon, the channel stack on its dark square ([R.drawable.ic_brand_mark]). */
@Composable
internal fun TunedMark(
    size: Dp,
    description: String?,
) {
    Image(
        ImageProvider(R.drawable.ic_brand_mark),
        contentDescription = description,
        modifier = GlanceModifier.size(size),
    )
}

/** A channel's logo on a soft plate, or its initials when it has none. */
@Composable
internal fun LogoPlate(
    logo: Bitmap?,
    name: String,
    modifier: GlanceModifier,
    initialsSize: Int = 16,
) {
    Box(
        modifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(14.dp)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(ImageProvider(logo), contentDescription = null, contentScale = ContentScale.Fit, modifier = GlanceModifier.fillMaxSize())
        } else {
            Text(
                initials(name),
                style =
                    TextStyle(
                        fontSize = initialsSize.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    ),
            )
        }
    }
}

/** How far through a programme or film, as a thin bar in the accent colour. */
@Composable
internal fun ThinProgress(
    fraction: Float,
    modifier: GlanceModifier = GlanceModifier,
) {
    LinearProgressIndicator(
        progress = fraction.coerceIn(0f, 1f),
        modifier = modifier.fillMaxWidth().height(3.dp).cornerRadius(2.dp),
        color = GlanceTheme.colors.primary,
        backgroundColor = GlanceTheme.colors.surfaceVariant,
    )
}

/** Nothing to show yet: the Tuned mark, a line on how to fill the widget, and a button into the app. */
@Composable
internal fun WidgetMessage(
    title: String,
    body: String?,
    button: String,
    compact: Boolean,
) {
    val context = LocalContext.current
    WidgetCard {
        Column(
            GlanceModifier.fillMaxSize().semantics { contentDescription = listOfNotNull(title, body).joinToString(". ") },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) {
                TunedMark(36.dp, null)
                Spacer(GlanceModifier.height(8.dp))
            }
            Text(
                title,
                maxLines = 2,
                style =
                    TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onBackground,
                        textAlign = TextAlign.Center,
                    ),
            )
            if (body != null && !compact) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    body,
                    maxLines = 3,
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center),
                )
            }
            Spacer(GlanceModifier.height(10.dp))
            Button(
                button,
                onClick = WidgetParts.openApp(context),
                colors =
                    ButtonDefaults.buttonColors(
                        backgroundColor = GlanceTheme.colors.primary,
                        contentColor = GlanceTheme.colors.onPrimary,
                    ),
                maxLines = 1,
            )
        }
    }
}

/** "Maple News 24" → "MN": what stands in for a missing logo. */
internal fun initials(name: String): String = name
    .split(' ', '-', '|')
    .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifEmpty { name.take(2).uppercase() }
