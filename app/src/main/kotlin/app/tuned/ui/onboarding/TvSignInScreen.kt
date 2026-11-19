package app.tuned.ui.onboarding

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.TvHandoff
import app.tuned.data.CloudSyncRepository
import app.tuned.data.SyncCopyRepository
import app.tuned.data.TvRelay
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.BackTopBar
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A QR code's dark modules, row by row. */
class QrModules(
    val size: Int,
    private val dark: BooleanArray,
) {
    operator fun get(
        x: Int,
        y: Int,
    ) = dark[y * size + x]

    companion object {
        fun of(text: String): QrModules {
            val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val size = matrix.width
            return QrModules(size, BooleanArray(size * size) { matrix[it % size, it / size] })
        }
    }
}

/**
 * The TV's side of signing in with a phone (docs/decisions/onboarding.md): a one-time key, a code from the relay, a
 * QR code with the key in it, then waiting for the phone. A code that runs out is replaced on its own. What arrives
 * is shown first and only taken on "Use it".
 */
class TvSignInViewModel(
    app: Application,
) : AndroidViewModel(app) {
    sealed interface State {
        data object Loading : State

        data class Showing(
            val code: String,
            val site: String,
            val check: String,
            val qr: QrModules,
        ) : State

        class Arrived(
            val summary: SyncCopyRepository.Summary,
            val plain: ByteArray,
        ) : State

        data object Saving : State

        data object Offline : State

        /** The website signed this TV in to a Tuned account and its backup came back: open the app. */
        data object Restored : State

        /** Signed in to the account, but its backup has no playlist yet. */
        data object NoBackup : State
    }

    private val container = app.container
    val state = MutableStateFlow<State>(State.Loading)

    /** Bumped to start over (Try again, Not mine); the screen runs [run] again for each value. */
    val round = MutableStateFlow(0)

    /** Shows codes and waits for the phone, until something arrives or the relay can't be reached. */
    suspend fun run() {
        state.value = State.Loading
        while (true) {
            val keys = withContext(Dispatchers.Default) { TvHandoff.newKeys() }
            val code = container.tvRelay.code(keys.publicKey)
            if (code == null) {
                state.value = State.Offline
                return
            }
            val link = TvHandoff.link(container.tvRelay.base, code.code, keys.publicKey)
            val qr = withContext(Dispatchers.Default) { QrModules.of(link) }
            val site = container.tvRelay.base.substringAfter("://") + "/tv"
            state.value = State.Showing(TvHandoff.displayCode(code.code), site, TvHandoff.checkDigits(keys.publicKey), qr)
            val arrived = wait(code, keys) ?: continue
            state.value = arrived
            return
        }
    }

    /** Polls until the box comes (opened and read), or null once the code runs out and a new one is needed. */
    private suspend fun wait(
        code: TvRelay.Code,
        keys: TvHandoff.Keys,
    ): State? {
        val until = System.currentTimeMillis() + (code.seconds - MARGIN_SECONDS) * 1000L
        var misses = 0
        while (System.currentTimeMillis() < until) {
            delay(POLL_MS)
            when (val taken = container.tvRelay.take(code)) {
                TvRelay.Taken.Waiting -> misses = 0
                TvRelay.Taken.Gone -> return null
                TvRelay.Taken.Unreachable -> if (++misses >= MAX_MISSES) return State.Offline
                is TvRelay.Taken.Box -> return opened(taken.box, code.code, keys)
                is TvRelay.Taken.Linked -> return signedInToAccount(taken.link)
            }
        }
        return null
    }

    /** The website signed this TV in to a Tuned account (docs/decisions/accounts.md): restore its backup. */
    private suspend fun signedInToAccount(link: String): State {
        state.value = State.Saving
        if (!container.cloudSync.account.signIn(link)) return State.Offline
        return when (container.cloudSync.restore()) {
            CloudSyncRepository.Restore.RESTORED -> State.Restored
            CloudSyncRepository.Restore.OFFLINE -> State.Offline
            else -> State.NoBackup
        }
    }

    private suspend fun opened(
        box: ByteArray,
        code: String,
        keys: TvHandoff.Keys,
    ): State? = withContext(Dispatchers.Default) {
        attempt(TAG, "Couldn't open what the phone sent") {
            val plain = TvHandoff.unpack(TvHandoff.open(box, code, keys))
            State.Arrived(container.syncCopy.summary(plain), plain)
        }.getOrNull()
    }

    fun startOver() {
        round.update { it + 1 }
    }

    /** Takes what arrived and opens the lists. */
    fun use(onDone: () -> Unit) {
        val arrived = state.value as? State.Arrived ?: return
        state.value = State.Saving
        viewModelScope.launchSafely(TAG) {
            if (container.phoneSignIn.take(arrived.plain)) {
                onDone()
            } else {
                AppLog.w(TAG, "The phone sent no playlist")
                startOver()
            }
        }
    }

    private companion object {
        const val TAG = "TvSignIn"
        const val POLL_MS = 2_000L
        const val MARGIN_SECONDS = 5
        const val MAX_MISSES = 5
    }
}

@Composable
fun TvSignInScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    vm: TvSignInViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val round by vm.round.collectAsStateWithLifecycle()
    LaunchedEffect(round) { vm.run() }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.tv_signin_title), onBack) }) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (val s = state) {
                TvSignInViewModel.State.Loading -> {
                    Busy(stringResource(R.string.tv_signin_waiting))
                }

                TvSignInViewModel.State.Saving -> {
                    Busy(stringResource(R.string.tv_signin_saving))
                }

                TvSignInViewModel.State.Offline -> {
                    Offline(onRetry = vm::startOver, onType = onBack)
                }

                is TvSignInViewModel.State.Showing -> {
                    Showing(s, onType = onBack)
                }

                is TvSignInViewModel.State.Arrived -> {
                    Arrived(s.summary, onUse = { vm.use(onDone) }, onNotMine = vm::startOver)
                }

                TvSignInViewModel.State.Restored -> {
                    LaunchedEffect(Unit) { onDone() }
                    Busy(stringResource(R.string.tv_signin_saving))
                }

                TvSignInViewModel.State.NoBackup -> {
                    NoBackup(onType = onBack)
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(48.dp))
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Showing(
    s: TvSignInViewModel.State.Showing,
    onType: () -> Unit,
) {
    Row(
        Modifier.widthIn(max = 900.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tv_signin_scan), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.tv_signin_or_go, s.site),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(s.code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.tv_signin_check, s.check),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onType) { Text(stringResource(R.string.tv_signin_type_instead)) }
        }
        Qr(s.qr)
    }
}

/** Black on white with a quiet zone, whatever the theme, so every phone camera reads it. */
@Composable
internal fun Qr(modules: QrModules) {
    val label = stringResource(R.string.tv_signin_qr)
    Box(
        Modifier
            .size(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(16.dp)
            .semantics { contentDescription = label },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / modules.size
            for (y in 0 until modules.size) {
                for (x in 0 until modules.size) {
                    // A hair over one cell, so no seams show between modules.
                    if (modules[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

@Composable
private fun NoBackup(onType: () -> Unit) {
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.tv_signin_no_backup), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onType) { Text(stringResource(R.string.tv_signin_type_instead)) }
    }
}

@Composable
private fun Offline(
    onRetry: () -> Unit,
    onType: () -> Unit,
) {
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.tv_signin_offline), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text(stringResource(R.string.tv_signin_retry)) }
            OutlinedButton(onClick = onType) { Text(stringResource(R.string.tv_signin_type_instead)) }
        }
    }
}

@Composable
private fun Arrived(
    summary: SyncCopyRepository.Summary,
    onUse: () -> Unit,
    onNotMine: () -> Unit,
) {
    val context = LocalContext.current
    val premium by context.container.premium.unlocked
        .collectAsStateWithLifecycle()
    val signedIn = context.container.credentials.load() != null
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tv_signin_arrived), style = MaterialTheme.typography.headlineSmall)
        // Names and where they come from; never a password.
        summary.playlists.forEach { login ->
            Column {
                Text(login.label, style = MaterialTheme.typography.titleMedium)
                if (login.name.isNotBlank()) {
                    Text(login.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val extras =
            buildList {
                if (summary.settings) add(stringResource(R.string.tv_signin_settings))
                if (summary.favourites >
                    0
                ) {
                    add(pluralStringResource(R.plurals.tv_signin_favourites, summary.favourites, summary.favourites))
                }
                if (summary.lists > 0) add(pluralStringResource(R.plurals.tv_signin_lists, summary.lists, summary.lists))
                if (summary.watchLater >
                    0
                ) {
                    add(pluralStringResource(R.plurals.tv_signin_watch_later, summary.watchLater, summary.watchLater))
                }
            }
        if (extras.isNotEmpty()) {
            Text(
                stringResource(R.string.tv_signin_also, extras.joinToString(", ")),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val note =
            when {
                signedIn && summary.everything -> R.string.tv_signin_only_playlists
                !premium && summary.playlists.size > 1 -> R.string.tv_signin_only_first
                else -> null
            }
        note?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onUse) { Text(stringResource(R.string.tv_signin_use)) }
            OutlinedButton(onClick = onNotMine) { Text(stringResource(R.string.tv_signin_not_mine)) }
        }
    }
}
