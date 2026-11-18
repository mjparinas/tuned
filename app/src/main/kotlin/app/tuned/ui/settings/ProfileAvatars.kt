package app.tuned.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.core.Avatar
import app.tuned.core.Avatars
import app.tuned.data.Accent
import app.tuned.data.db.ProfileEntity
import app.tuned.ui.common.focusRing
import app.tuned.ui.theme.swatch

/** A profile's round badge: its avatar, or its colour and first letter. Also the profile button on Home. */
@Composable
fun ProfileBadge(
    profile: ProfileEntity,
    size: Dp,
    shape: Shape = CircleShape,
    animate: Boolean = false,
) {
    AvatarPicture(
        avatar = Avatars.find(profile.avatar),
        colour = profile.colour,
        letter = displayName(profile).take(1).uppercase(),
        size = size,
        shape = shape,
        animate = animate,
    )
}

/**
 * One avatar: the emoji on a soft gradient, or [letter] when there is none. Free avatars and the letter take the
 * profile's colour; Premium ones have their own gradient and a shimmering ring, which turns when [animate] is on (the
 * picker) and stays still elsewhere (Home's small badge), so nothing animates where it would distract.
 */
@Composable
fun AvatarPicture(
    avatar: Avatar?,
    colour: Int,
    letter: String,
    size: Dp,
    shape: Shape = CircleShape,
    animate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val (light, dark) = gradientFor(avatar, colour)
    // Sized in pixels rather than sp: the picture is a fixed size, so large text mustn't push the glyph out of it.
    val glyphSize = with(LocalDensity.current) { (size * if (avatar != null) GLYPH else LETTER).toSp() }
    val style = TextStyle(fontSize = glyphSize, lineHeight = glyphSize)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(light, dark)))
            .then(if (avatar?.premium == true) Modifier.premiumRing(shape, size * RING, premiumAngle(animate)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Text(avatar.glyph, style = style)
        } else {
            Text(letter, style = style, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The picture choices in the profile editor: the letter, the free avatars, then the Premium set. Without Premium the
 * Premium ones show a lock and [onLocked] opens the Premium screen, except the one the profile already wears.
 */
@Composable
fun AvatarChooser(
    selected: String?,
    current: String?,
    colour: Int,
    letter: String,
    premiumUnlocked: Boolean,
    onPick: (String?) -> Unit,
    onLocked: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.profile_picture), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        FlowRow {
            AvatarOption(null, stringResource(R.string.profile_picture_letter), selected == null, locked = false, colour, letter) {
                onPick(null)
            }
            Avatars.free.forEach { a ->
                AvatarOption(a, stringResource(avatarName(a.key)), selected == a.key, locked = false, colour, letter) { onPick(a.key) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = PREMIUM_GOLD, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.profile_picture_premium), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(4.dp))
        FlowRow {
            Avatars.premium.forEach { a ->
                val locked = !Avatars.canPick(a.key, current, premiumUnlocked)
                AvatarOption(a, stringResource(avatarName(a.key)), selected == a.key, locked, colour, letter) {
                    if (locked) onLocked() else onPick(a.key)
                }
            }
        }
    }
}

@Composable
private fun AvatarOption(
    avatar: Avatar?,
    name: String,
    selected: Boolean,
    locked: Boolean,
    colour: Int,
    letter: String,
    onClick: () -> Unit,
) {
    val lockedWords = stringResource(R.string.profile_picture_locked)
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .padding(2.dp)
            .size(OPTION)
            .focusRing(CircleShape, MaterialTheme.colorScheme.onSurface)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = name
                // The lock is drawn, so say it in words too.
                if (locked) stateDescription = lockedWords
            }.then(if (selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
            .padding(if (selected) 5.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        AvatarPicture(
            avatar,
            colour,
            letter,
            size = OPTION - if (selected) 10.dp else 4.dp,
            modifier = Modifier.alpha(if (locked) 0.45f else 1f),
        )
        if (locked) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(12.dp)) }
        }
    }
}

/** The ring's turn, in degrees: a slow loop when [animate], else still. */
@Composable
private fun premiumAngle(animate: Boolean): State<Float> {
    if (!animate) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "premiumRing").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RING_TURN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "premiumRingAngle",
    )
}

/**
 * A gold, rose and cyan ring along the inside edge of [shape]. [angle] is read only while drawing, so the turn
 * redraws the ring without recomposing anything.
 */
private fun Modifier.premiumRing(
    shape: Shape,
    width: Dp,
    angle: State<Float>,
): Modifier = drawWithCache {
    val w = width.toPx()
    val outer = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val inner =
        Path().apply {
            addOutline(shape.createOutline(Size(size.width - 2 * w, size.height - 2 * w), layoutDirection, this@drawWithCache))
            translate(Offset(w, w))
        }
    val ring = Path().apply { op(outer, inner, PathOperation.Difference) }
    val brush = Brush.sweepGradient(RING_COLOURS)
    onDrawWithContent {
        drawContent()
        clipPath(ring) { rotate(angle.value) { drawCircle(brush, radius = size.maxDimension) } }
    }
}

private fun gradientFor(
    avatar: Avatar?,
    colour: Int,
): Pair<Color, Color> {
    if (avatar?.premium == true) PREMIUM_GRADIENTS[avatar.key]?.let { (a, b) -> return Color(a) to Color(b) }
    val base = profileColour(colour)
    return lerp(base, Color.White, 0.22f) to lerp(base, Color.Black, 0.35f)
}

internal fun profileColour(index: Int): Color = Accent.entries[Math.floorMod(index, Accent.entries.size)].swatch()

@StringRes
private fun avatarName(key: String): Int = AVATAR_NAMES[key] ?: R.string.profile_picture

private val AVATAR_NAMES =
    mapOf(
        "fox" to R.string.avatar_fox,
        "panda" to R.string.avatar_panda,
        "tiger" to R.string.avatar_tiger,
        "lion" to R.string.avatar_lion,
        "frog" to R.string.avatar_frog,
        "monkey" to R.string.avatar_monkey,
        "penguin" to R.string.avatar_penguin,
        "dog" to R.string.avatar_dog,
        "cat" to R.string.avatar_cat,
        "koala" to R.string.avatar_koala,
        "bear" to R.string.avatar_bear,
        "rabbit" to R.string.avatar_rabbit,
        "dragon" to R.string.avatar_dragon,
        "unicorn" to R.string.avatar_unicorn,
        "trex" to R.string.avatar_trex,
        "owl" to R.string.avatar_owl,
        "shark" to R.string.avatar_shark,
        "octopus" to R.string.avatar_octopus,
        "rocket" to R.string.avatar_rocket,
        "alien" to R.string.avatar_alien,
        "robot" to R.string.avatar_robot,
        "wizard" to R.string.avatar_wizard,
        "crown" to R.string.avatar_crown,
        "diamond" to R.string.avatar_diamond,
    )

/** Each Premium avatar's own gradient, light to dark. */
private val PREMIUM_GRADIENTS: Map<String, Pair<Long, Long>> =
    mapOf(
        "dragon" to (0xFF34D399 to 0xFF065F46),
        "unicorn" to (0xFFF0ABFC to 0xFF7C3AED),
        "trex" to (0xFFA3E635 to 0xFF3F6212),
        "owl" to (0xFFFBBF24 to 0xFF92400E),
        "shark" to (0xFF38BDF8 to 0xFF1E3A8A),
        "octopus" to (0xFFFB7185 to 0xFF9F1239),
        "rocket" to (0xFF818CF8 to 0xFF1E1B4B),
        "alien" to (0xFF2DD4BF to 0xFF134E4A),
        "robot" to (0xFF94A3B8 to 0xFF1E293B),
        "wizard" to (0xFFC084FC to 0xFF3B0764),
        "crown" to (0xFFFDE68A to 0xFFB45309),
        "diamond" to (0xFF67E8F9 to 0xFF0E7490),
    )

internal val PREMIUM_GOLD = Color(0xFFFBBF24)
private val RING_COLOURS = listOf(PREMIUM_GOLD, Color(0xFFF472B6), Color(0xFF67E8F9), Color(0xFFA78BFA), PREMIUM_GOLD)
private val OPTION = 48.dp
private const val GLYPH = 0.56f
private const val LETTER = 0.42f
private const val RING = 0.045f
private const val RING_TURN_MS = 6_000
