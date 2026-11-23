package com.tunedplayer.app.ui.settings

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.Avatars
import com.tunedplayer.app.core.ProfileLock
import com.tunedplayer.app.core.ProfileRules
import com.tunedplayer.app.data.Accent
import com.tunedplayer.app.data.ProfilesSeen
import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.LocalTvMargin
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.bleedIntoTvMargin
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.common.startFocus
import com.tunedplayer.app.ui.theme.swatch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ProfilesViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    private val repo = container.profiles

    // The list "Who's watching?" last showed comes first, with the settings, so the picker draws at once on a cold start;
    // the database's list replaces it as soon as it answers (docs/decisions/speed.md › Quiet start).
    val profiles: StateFlow<List<ProfileEntity>?> =
        flow {
            saved().first.takeIf { it.isNotEmpty() }?.let { emit(it) }
            emitAll(repo.profiles)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val current: StateFlow<ProfileEntity?> =
        flow {
            val (list, id) = saved()
            list.firstOrNull { it.id == id }?.let { emit(it) }
            emitAll(repo.current)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val premiumUnlocked: StateFlow<Boolean> = container.premium.unlocked
    val hasParentalPin: StateFlow<Boolean> = container.settings.hasAdultPin.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val askAtStart: StateFlow<Boolean?> =
        container.settings.settings
            .map { it.askProfileAtStart }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun saved(): Pair<List<ProfileEntity>, Long> {
        val s = container.settings.settings.first()
        return ProfilesSeen.decode(s.profilesSeen) to s.profileId
    }

    suspend fun lockFor(profile: ProfileEntity): ProfileLock = repo.lockFor(profile)

    suspend fun switchTo(id: Long) = repo.switchTo(id)

    fun setAskAtStart(on: Boolean) {
        viewModelScope.launchSafely("Profiles") { container.settings.update { it.copy(askProfileAtStart = on) } }
    }

    /** Creates or saves [profile]; [pin] is a new PIN, [clearPin] removes the old one. */
    fun save(
        profile: ProfileEntity,
        pin: String?,
        clearPin: Boolean,
    ) {
        viewModelScope.launchSafely("Profiles") {
            val id =
                if (profile.id == 0L) {
                    repo.create(profile.name, profile.colour, profile.kids, profile.avatar)
                } else {
                    repo.save(profile)
                    profile.id
                }
            when {
                pin != null -> repo.setPin(id, pin)
                clearPin -> repo.setPin(id, null)
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launchSafely("Profiles") { repo.delete(id) }
    }

    /** The PIN that guards [profile]: its own, or the parental PIN when leaving a kids profile. */
    fun pinFor(
        profile: ProfileEntity,
        lock: ProfileLock,
    ): PinActions = object : PinActions {
        override suspend fun setPin(pin: String) = Unit

        override suspend fun unlock(pin: String): PinResult = if (lock == ProfileLock.PARENTAL) {
            repo.checkParentalPin(pin).toPinResult()
        } else {
            repo.checkPin(profile.id, pin).toPinResult()
        }

        override fun matchesLogin(secret: String): Boolean = container.credentials.matches(secret)
    }

    /** Sets the parental PIN without unlocking adult categories, for a first kids profile. */
    val parental: PinActions =
        object : PinActions {
            override suspend fun setPin(pin: String) {
                container.settings.setAdultPin(pin)
                container.settings.lockAdult()
            }

            override suspend fun unlock(pin: String): PinResult = repo.checkParentalPin(pin).toPinResult()

            override fun matchesLogin(secret: String): Boolean = container.credentials.matches(secret)
        }
}

/** What happens once a PIN for a profile is right. */
private enum class AfterPin { Switch, Edit }

private data class PinAsk(
    val profile: ProfileEntity,
    val lock: ProfileLock,
    val then: AfterPin,
)

/**
 * "Who's watching?" (docs/decisions/profiles.md): pick a profile, or manage them. At start ([atStart]) it's the first
 * screen, while there's more than one profile; from Home or Settings it opens with a back arrow.
 */
@Composable
fun ProfilesScreen(
    atStart: Boolean,
    onPicked: () -> Unit,
    onBack: () -> Unit,
    vm: ProfilesViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val askAtStart by vm.askAtStart.collectAsStateWithLifecycle()
    val list = profiles ?: return
    val kidsNow = current?.kids == true
    val context = LocalContext.current
    var managing by remember { mutableStateOf(!atStart && list.size == 1) }
    var ask by remember { mutableStateOf<PinAsk?>(null) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    val scope = rememberCoroutineScope()

    val pick: (ProfileEntity) -> Unit = { p ->
        scope.launchSafely("Profiles") {
            if (managing) {
                val needsPin = p.pinHash != null && p.id != current?.id
                if (needsPin) ask = PinAsk(p, ProfileLock.PROFILE, AfterPin.Edit) else editing = p
            } else {
                when (val lock = vm.lockFor(p)) {
                    ProfileLock.NONE -> {
                        vm.switchTo(p.id)
                        onPicked()
                    }

                    else -> {
                        ask = PinAsk(p, lock, AfterPin.Switch)
                    }
                }
            }
        }
    }

    val manageToggle: () -> Unit = { managing = !managing }
    WelcomeScaffold(
        topBar = {
            if (atStart) {
                BrandBar()
            } else {
                ProfilesTopBar(managing, onBack)
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (atStart) {
                    Text(
                        stringResource(if (managing) R.string.profiles_manage else R.string.profiles_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(40.dp))
                }
                ProfileRow(list, current?.id, managing, onPick = pick) {
                    // Hidden at the cap, so the picker never crowds (docs/decisions/profiles.md).
                    if (!kidsNow && managing && ProfileRules.canAdd(list.size)) {
                        AddTile(onClick = {
                            // A second profile is Premium (docs/decisions/premium.md).
                            if (list.isEmpty() || context.container.premium.allow(context)) editing = newProfile(list.size)
                        })
                    }
                }
                // A kids profile can pick, but never add, change or remove profiles.
                if (!kidsNow) {
                    Spacer(Modifier.height(40.dp))
                    if (managing) {
                        Button(onClick = manageToggle) { Text(stringResource(R.string.profiles_done)) }
                    } else {
                        OutlinedButton(onClick = manageToggle) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.profiles_manage))
                        }
                    }
                }
                if (managing && list.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.widthIn(max = 480.dp)) {
                        SwitchRow(
                            stringResource(R.string.profiles_ask_at_start),
                            askAtStart ?: true,
                            stringResource(R.string.profiles_ask_at_start_summary),
                        ) { on -> vm.setAskAtStart(on) }
                    }
                }
            }
        }
    }

    ask?.let { a ->
        PinDialog(
            start = PinMode.Unlock,
            vm = vm.pinFor(a.profile, a.lock),
            unlockTitle =
                if (a.lock == ProfileLock.PARENTAL) {
                    stringResource(R.string.profile_parental_title)
                } else {
                    stringResource(R.string.profile_pin_title, displayName(a.profile))
                },
            forgotResets = false,
            onDone = { ask = null },
            onSuccess = {
                when (a.then) {
                    AfterPin.Edit -> {
                        editing = a.profile
                    }

                    AfterPin.Switch -> {
                        scope.launchSafely("Profiles") {
                            vm.switchTo(a.profile.id)
                            onPicked()
                        }
                    }
                }
            },
        )
    }
    editing?.let { p ->
        ProfileEditor(
            profile = p,
            canDelete = p.id != 0L && p.id != current?.id && list.size > 1,
            vm = vm,
            onClose = { editing = null },
        )
    }
}

/**
 * The profiles, in a row that wraps, with [trailing] (the add tile) after them. On a TV the remote starts on the first
 * profile, so OK alone picks it (Melvin, 2026-11-20: "it should auto highlight the first entry so we only need to click ok
 * on the remote"). The first, not the one in use, so the same press always picks the same profile.
 */
@Composable
internal fun ProfileRow(
    list: List<ProfileEntity>,
    currentId: Long?,
    managing: Boolean,
    onPick: (ProfileEntity) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    // Remembered once the profiles have loaded, so the moment it may take focus starts when the tiles appear.
    val startFocus = rememberStartFocus()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        list.forEachIndexed { i, p ->
            key(p.id) {
                ProfileTile(
                    p,
                    selected = p.id == currentId,
                    managing = managing,
                    onClick = { onPick(p) },
                    modifier = if (i == 0) Modifier.startFocus(startFocus) else Modifier,
                )
            }
        }
        trailing()
    }
}

/**
 * The picker's backdrop: the theme's background with a soft glow of the accent behind the profiles, so the first
 * screen feels like a welcome, not a form. The container is see-through for the glow, so the text colour is set to
 * onBackground here; left to itself it falls back to black, which is unreadable on the dark theme.
 */
@Composable
internal fun WelcomeScaffold(
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tvMargin = LocalTvMargin.current
    Box(Modifier.fillMaxSize()) {
        // The backdrop is its own layer so that on a TV it fills the overscan margin too, edge to edge, while the
        // profiles stay inside it; drawn on the Scaffold, the plain margin showed as a white frame (Melvin, 2026-11-20).
        Box(
            Modifier
                .matchParentSize()
                .bleedIntoTvMargin(tvMargin)
                .background(MaterialTheme.colorScheme.background)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(primary.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(size.width / 2, size.height * 0.38f),
                            radius = size.maxDimension * 0.62f,
                        ),
                    )
                },
        )
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = topBar,
            content = content,
        )
    }
}

/** The title bar of "Who's watching?" opened from Home: see-through, so the glow shows behind the title. */
@Composable
internal fun ProfilesTopBar(
    managing: Boolean,
    onBack: () -> Unit,
) {
    BackTopBar(stringResource(if (managing) R.string.profiles_manage else R.string.profiles_title), onBack, transparent = true)
}

/** The app's mark and name in the upper left of "Who's watching?" at start, so the first screen says whose it is. */
@Composable
private fun BrandBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * One profile on the picker. Its name and lock are coloured onBackground here rather than left to the surrounding
 * content colour, which falls back to black wherever nothing sets it (the names were once near-black on the dark theme).
 *
 * Focus and "in use" share one accent ring on the picture (the same 3 dp the phone uses for the current profile). A
 * tile-wide [focusRing] used to frame the name too and stack on the selected ring, which read as a heavy double box
 * from the sofa (Melvin, 2026-11-22). The press/focus bounce still answers the remote.
 */
@Composable
internal fun ProfileTile(
    profile: ProfileEntity,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val avatarShape = RoundedCornerShape(TILE_AVATAR * 0.28f)
    val avatarRingShape = RoundedCornerShape((TILE_AVATAR + 12.dp) * 0.28f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    // A press sinks the picture a little and a remote's focus lifts it, so a tap and a D-pad both feel answered.
    val scale by animateFloatAsState(
        when {
            pressed -> 0.94f
            focused -> 1.06f
            else -> 1f
        },
        label = "profileScale",
    )
    // The ring and the pencil are drawn, so a screen reader is told in words which profile is in use and what a tap does.
    val current = stringResource(R.string.a11y_current_profile)
    val edit = stringResource(R.string.a11y_edit)
    val ring = MaterialTheme.colorScheme.primary
    // One ring for focus or for the profile in use — never both — so a focused current profile stays as light as the phone.
    val showAvatarRing = focused || (selected && !managing)
    Column(
        modifier
            .width(TILE_WIDTH)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = if (managing) edit else null,
                onClick = onClick,
            ).semantics(mergeDescendants = true) { if (selected && !managing) stateDescription = current }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.size(TILE_AVATAR + 12.dp)
                .then(if (showAvatarRing) Modifier.border(AVATAR_RING, ring, avatarRingShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.shadow(10.dp, avatarShape, clip = false)) {
                ProfileBadge(profile, size = TILE_AVATAR, shape = avatarShape, animate = true)
            }
            if (managing) {
                Box(
                    Modifier.size(TILE_AVATAR).clip(avatarShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (profile.pinHash != null) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.profile_locked),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                displayName(profile),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (profile.kids) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.profile_kids_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val avatarShape = RoundedCornerShape(TILE_AVATAR * 0.28f)
    val avatarRingShape = RoundedCornerShape((TILE_AVATAR + 12.dp) * 0.28f)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .width(TILE_WIDTH)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(TILE_AVATAR + 12.dp)
                .then(if (focused) Modifier.border(AVATAR_RING, ring, avatarRingShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(TILE_AVATAR)
                    .clip(avatarShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, avatarShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.profile_add),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun displayName(profile: ProfileEntity): String = profile.name.ifBlank { stringResource(R.string.profile_default_name) }

private fun newProfile(count: Int) = ProfileEntity(name = "", colour = count, kids = false, pinHash = null, createdAt = 0)

/** Name, colour, kids, PIN lock and delete for one profile; a new one has id 0. */
@Composable
private fun ProfileEditor(
    profile: ProfileEntity,
    canDelete: Boolean,
    vm: ProfilesViewModel,
    onClose: () -> Unit,
) {
    val hasParentalPin by vm.hasParentalPin.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(profile.name) }
    var colour by remember { mutableStateOf(profile.colour) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    val premiumUnlocked by vm.premiumUnlocked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var kids by remember { mutableStateOf(profile.kids) }
    var locked by remember { mutableStateOf(profile.pinHash != null) }
    var newPin by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf(false) }
    var askParental by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val newPinCollector =
        remember {
            object : PinActions {
                override suspend fun setPin(pin: String) {
                    newPin = pin
                }

                override suspend fun unlock(pin: String) = PinResult.Wrong

                override fun matchesLogin(secret: String) = false
            }
        }
    val save = {
        val clearPin = !locked && profile.pinHash != null
        vm.save(profile.copy(name = name, colour = colour, kids = kids, avatar = avatar), pin = newPin, clearPin = clearPin)
        onClose()
    }

    TunedDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (profile.id == 0L) R.string.profile_new_title else R.string.profile_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val letter = name.ifBlank { stringResource(R.string.profile_default_name) }.take(1).uppercase()
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AvatarPicture(Avatars.find(avatar), colour, letter, size = 88.dp, animate = true)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                AvatarChooser(
                    selected = avatar,
                    current = profile.avatar,
                    colour = colour,
                    letter = letter,
                    premiumUnlocked = premiumUnlocked,
                    onPick = { avatar = it },
                    // Opens the Premium screen; the picture stays as it was.
                    onLocked = { context.container.premium.allow(context) },
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.profile_colour), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                // Wraps onto a second line when the dialog is narrow; each swatch brings its own room around it.
                FlowRow {
                    Accent.entries.forEachIndexed { i, accent ->
                        val swatchName = stringResource(accentLabel(accent))
                        Box(
                            Modifier
                                // A 28dp swatch with room around it to take a finger (48dp), named for screen readers.
                                .minimumInteractiveComponentSize()
                                .focusRing(CircleShape, MaterialTheme.colorScheme.onSurface)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accent.swatch())
                                .selectable(selected = i == colour, role = Role.RadioButton, onClick = { colour = i })
                                .semantics { contentDescription = swatchName },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == colour) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(stringResource(R.string.profile_kids), kids, stringResource(R.string.profile_kids_summary)) { kids = it }
                SwitchRow(stringResource(R.string.profile_lock), locked, stringResource(R.string.profile_lock_summary)) { on ->
                    if (on) {
                        askPin = true
                    } else {
                        locked = false
                        newPin = null
                    }
                }
                if (canDelete) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Leaving a kids profile asks for the parental PIN, so a kids profile can't exist without one.
                if (kids && !hasParentalPin) askParental = true else save()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )

    if (askPin) {
        PinDialog(PinMode.Create, newPinCollector, onDone = { askPin = false }, onSuccess = { locked = true })
    }
    if (askParental) {
        PinDialog(PinMode.Create, vm.parental, onDone = { askParental = false }, onSuccess = save)
    }
    if (confirmDelete) {
        TunedDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.profile_delete_confirm, displayName(profile))) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(profile.id)
                    confirmDelete = false
                    onClose()
                }) { Text(stringResource(R.string.profile_delete_button), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val MAX_NAME = 24
private val TILE_AVATAR = 104.dp
private val TILE_WIDTH = 136.dp

/** Matches the phone's selected ring so a TV remote highlight stays obvious without a heavy frame. */
private val AVATAR_RING = 3.dp
