package app.tuned.ui.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.ProfileLock
import app.tuned.data.Accent
import app.tuned.data.db.ProfileEntity
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.common.focusRing
import app.tuned.ui.theme.swatch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    private val repo = container.profiles

    val profiles: StateFlow<List<ProfileEntity>?> = repo.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val current: StateFlow<ProfileEntity?> = repo.current.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasParentalPin: StateFlow<Boolean> = container.settings.hasAdultPin.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val askAtStart: StateFlow<Boolean?> =
        container.settings.settings
            .map { it.askProfileAtStart }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    suspend fun lockFor(profile: ProfileEntity): ProfileLock = repo.lockFor(profile)

    suspend fun switchTo(id: Long) = repo.switchTo(id)

    fun setAskAtStart(on: Boolean) {
        viewModelScope.launch { container.settings.update { it.copy(askProfileAtStart = on) } }
    }

    /** Creates or saves [profile]; [pin] is a new PIN, [clearPin] removes the old one. */
    fun save(
        profile: ProfileEntity,
        pin: String?,
        clearPin: Boolean,
    ) {
        viewModelScope.launch {
            val id =
                if (profile.id == 0L) {
                    repo.create(profile.name, profile.colour, profile.kids)
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
        viewModelScope.launch { repo.delete(id) }
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
    var managing by remember { mutableStateOf(!atStart && list.size == 1) }
    var ask by remember { mutableStateOf<PinAsk?>(null) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    val scope = rememberCoroutineScope()

    val pick: (ProfileEntity) -> Unit = { p ->
        scope.launch {
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

    Scaffold(
        topBar = {
            val actions: @Composable RowScope.() -> Unit = {
                // A kids profile can pick, but never add, change or remove profiles.
                if (!kidsNow) {
                    IconButton(onClick = { managing = !managing }) {
                        if (managing) {
                            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.profiles_done))
                        } else {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profiles_manage))
                        }
                    }
                }
            }
            if (atStart) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    actions()
                }
            } else {
                BackTopBar(stringResource(if (managing) R.string.profiles_manage else R.string.profiles_title), onBack, actions)
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(136.dp),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (atStart) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(if (managing) R.string.profiles_manage else R.string.profiles_title),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
            items(list, key = { it.id }) { p ->
                ProfileTile(p, selected = p.id == current?.id, managing = managing, onClick = { pick(p) })
            }
            if (!kidsNow && managing) {
                item(key = "add") {
                    AddTile(onClick = { editing = newProfile(list.size) })
                }
            }
            if (managing && list.size > 1) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SwitchRow(
                        stringResource(R.string.profiles_ask_at_start),
                        askAtStart ?: true,
                        stringResource(R.string.profiles_ask_at_start_summary),
                    ) { on -> vm.setAskAtStart(on) }
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
                        scope.launch {
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

@Composable
private fun ProfileTile(
    profile: ProfileEntity,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .focusRing(shape, MaterialTheme.colorScheme.primary)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProfileBadge(profile, size = 88.dp)
            if (managing) {
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White) }
            } else if (selected) {
                Box(Modifier.size(96.dp).border(3.dp, MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (profile.pinHash != null) {
                Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.profile_locked), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(displayName(profile), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (profile.kids) {
            Text(
                stringResource(R.string.profile_kids_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .focusRing(shape, MaterialTheme.colorScheme.primary)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.profile_add), style = MaterialTheme.typography.titleSmall)
    }
}

/** A profile's round badge: its colour and first letter. Also the profile button on Home. */
@Composable
fun ProfileBadge(
    profile: ProfileEntity,
    size: Dp,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(profileColour(profile.colour)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            displayName(profile).take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
fun displayName(profile: ProfileEntity): String = profile.name.ifBlank { stringResource(R.string.profile_default_name) }

private fun newProfile(count: Int) = ProfileEntity(name = "", colour = count, kids = false, pinHash = null, createdAt = 0)

private fun profileColour(index: Int): Color = Accent.entries[Math.floorMod(index, Accent.entries.size)].swatch()

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
        vm.save(profile.copy(name = name, colour = colour, kids = kids), pin = newPin, clearPin = clearPin)
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (profile.id == 0L) R.string.profile_new_title else R.string.profile_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.profile_colour), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Accent.entries.forEachIndexed { i, accent ->
                        Box(
                            Modifier
                                .focusRing(CircleShape, MaterialTheme.colorScheme.onSurface)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accent.swatch())
                                .clickable { colour = i },
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
        AlertDialog(
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
