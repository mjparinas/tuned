package com.tunedplayer.app.premium

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.core.AccountSeats
import com.tunedplayer.app.core.DeviceSeats
import com.tunedplayer.app.core.SeatAnswer
import com.tunedplayer.app.core.SeatDevice
import com.tunedplayer.app.data.AccountPlan
import com.tunedplayer.app.data.TunedAccount
import com.tunedplayer.app.diagnostics.launchSafely
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/** One thing a store sells: its product id, its price as the store writes it, and its free trial in days (0 for none). */
data class Offer(
    val productId: String,
    val price: String,
    val trialDays: Int,
    val yearly: Boolean,
)

/**
 * The store behind the Premium unlock: Play Billing in the `play` build, nothing in the `github` build, and in the
 * `amazon` build (Fire TV) no store at all, only the Tuned account.
 */
interface Store {
    /** False when nothing is sold in the app (the `amazon` build): Premium comes only from the Tuned account. */
    val sells: Boolean get() = true

    /** True when this account owns Premium, false when it doesn't, null when the store couldn't be asked. */
    suspend fun owned(): Boolean?

    /** The Premium purchase [owned] last found, for the device list; null when none, or nothing is sold. */
    fun purchase(): SeatPurchase? = null

    /** Every purchase [owned] last found, Premium and seats, which a signed-in device reports to its account. */
    fun purchases(): List<SeatPurchase> = emptyList()

    /** The extra-seat product [productId] as the store sells it; null when it can't be asked or doesn't sell it. */
    suspend fun seatOffer(productId: String): Offer? = null

    /** Lets a one-time seat purchase be bought again, once the account has recorded it. */
    suspend fun consume(token: String) = Unit

    /** What can be bought, cheapest first; empty when the store couldn't be asked. */
    suspend fun offers(): List<Offer>

    /** Opens the store's purchase sheet; false when it couldn't. The result comes back through `onPurchased`. */
    suspend fun buy(
        activity: Activity,
        productId: String,
    ): Boolean
}

/**
 * Whether Premium is unlocked (docs/decisions/premium.md). The `github` build is always unlocked. The `play` build
 * remembers the last answer on the device, so Premium works offline and nothing waits on Play, and asks Play again
 * each time the app comes to the front. With a device list ([seats]), a purchase covers at most three devices; only
 * a clear "full" answer from the list locks this one ([DeviceSeats]).
 */
class Premium(
    context: Context,
    private val scope: CoroutineScope,
    private val seats: DeviceSeatsClient? = null,
    /** The Tuned account, which holds the seats once this device signs in to it (docs/decisions/accounts.md). */
    private val tunedAccount: (() -> TunedAccount)? = null,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val store: Store = storeFor(app) { refresh() }
    private val owned = MutableStateFlow(ALWAYS)

    /** True when this account owns Premium, whether or not this device holds a seat. */
    val purchased: StateFlow<Boolean> = owned.asStateFlow()
    private val seat = MutableStateFlow<Boolean?>(null)
    private val state = MutableStateFlow(ALWAYS)
    val unlocked: StateFlow<Boolean> = state.asStateFlow()
    private val _devices = MutableStateFlow<List<SeatDevice>>(emptyList())

    /** The devices using this purchase, as the list last said; empty before it has answered. */
    val devices: StateFlow<List<SeatDevice>> = _devices.asStateFlow()

    /** True when this build limits Premium to a number of devices. */
    val limited: Boolean get() = seats != null
    private val _plan = MutableStateFlow<AccountPlan?>(null)

    /** What the account last said about Premium and its seats; null when signed out or not asked yet. */
    val plan: StateFlow<AccountPlan?> = _plan.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    /** Whether Premium is unlocked, once the answer remembered on the device has been read (for background work). */
    suspend fun unlockedNow(): Boolean {
        if (!ALWAYS) withTimeoutOrNull(LOAD_WAIT_MS) { loaded.await() }
        return state.value
    }

    /** True when the Tuned account can unlock Premium here: a build that limits devices, or one that sells nothing. */
    private val usesAccount: Boolean get() = limited || !store.sells

    /** The signed-in account, in a build that uses it; null otherwise. */
    private fun account(): TunedAccount? = tunedAccount?.invoke()?.takeIf { usesAccount && it.signedIn.value }

    init {
        if (!ALWAYS) {
            scope.launchSafely(TAG) {
                withContext(Dispatchers.IO) {
                    owned.value = prefs.getBoolean(KEY, false)
                    seat.value = if (prefs.contains(KEY_SEAT)) prefs.getBoolean(KEY_SEAT, true) else null
                }
                update()
                loaded.complete(Unit)
            }
            // Signing in to the account or out of it moves the seat there or back: ask again each time.
            val make = tunedAccount
            if (usesAccount && make != null) {
                scope.launchSafely(TAG) {
                    withContext(Dispatchers.IO) { make() }.signedIn.drop(1).collect { check() }
                }
            }
        }
    }

    /** Asks the store again in the background; an unreachable store leaves the remembered answer alone. */
    fun refresh() {
        if (ALWAYS) return
        scope.launchSafely(TAG) { check() }
    }

    /** Asks the store now, for Restore purchase. */
    suspend fun restore(): Boolean? {
        if (ALWAYS) return true
        return check()
    }

    private suspend fun check(): Boolean? {
        val answer = store.owned()
        val account = account()
        if (account == null) _plan.value = null
        if (account != null && !released()) {
            val plan = account.seat(use = true, purchases = store.purchases())
            if (plan != null) return heardAccount(plan, answer ?: if (store.sells) null else false)
        }
        // Nothing sold here and no account signed in: nothing can unlock Premium. Signed in but unreachable keeps
        // what this device last heard, as offline does.
        if (!store.sells && account == null) {
            remember(false)
            return false
        }
        answer ?: return null
        remember(answer)
        val list = seats
        val purchase = store.purchase()
        if (answer && list != null && purchase != null && account == null && !released()) heard(list.activate(purchase))
        return answer
    }

    /**
     * The account's answer: Premium bought anywhere (Play, or the website) counts, and Play's own answer still does
     * when the account couldn't confirm it. One-time seats the account has recorded are consumed in Play, so more
     * can be bought later.
     */
    private suspend fun heardAccount(
        plan: AccountPlan,
        play: Boolean?,
    ): Boolean {
        _plan.value = plan
        _devices.value = plan.devices
        val owned = plan.premium != null || play == true
        // Neither the account nor Play could say: keep what this device last heard, as offline does.
        if (plan.premium != null || play != null) remember(owned)
        heard(AccountSeats.answer(plan.seat, plan.devices))
        store
            .purchases()
            .filter { it.productId == AccountSeats.SEAT_ONCE && sha256(it.token) in plan.recorded }
            .forEach { store.consume(it.token) }
        return owned
    }

    /**
     * Frees [target]'s seat. Freeing this device's own seat also keeps it from taking one again on its own until
     * [useHere]. False when the list couldn't be reached.
     */
    suspend fun deactivate(target: SeatDevice): Boolean {
        account()?.let { return deactivate(it, target) }
        val list = seats ?: return false
        val purchase = store.purchase() ?: return false
        val answer = list.deactivate(purchase, target.id)
        if (answer == SeatAnswer.Unreachable) return false
        if (target.current) withContext(Dispatchers.IO) { prefs.edit { putBoolean(KEY_RELEASED, true) } }
        heard(answer)
        return true
    }

    /** Frees a seat in the account: this device's own gives its seat back, another device is removed (signed out). */
    private suspend fun deactivate(
        account: TunedAccount,
        target: SeatDevice,
    ): Boolean {
        val plan =
            if (target.current) account.seat(use = false, purchases = emptyList()) else account.removeDevice(target.id)
        if (plan == null) return false
        _plan.value = plan
        if (target.current) {
            withContext(Dispatchers.IO) { prefs.edit { putBoolean(KEY_RELEASED, true) } }
            heard(SeatAnswer.Full(plan.devices))
        } else {
            _devices.value = plan.devices
        }
        return true
    }

    /** Takes a seat for this device again after it gave its own up, or after another device freed one. */
    suspend fun useHere(): Boolean? {
        withContext(Dispatchers.IO) { prefs.edit { remove(KEY_RELEASED) } }
        return check()
    }

    private suspend fun released(): Boolean = withContext(Dispatchers.IO) { prefs.getBoolean(KEY_RELEASED, false) }

    private suspend fun heard(answer: SeatAnswer) {
        when (answer) {
            is SeatAnswer.Active -> _devices.value = answer.devices
            is SeatAnswer.Full -> _devices.value = answer.devices
            SeatAnswer.Unreachable -> Unit
        }
        val next = DeviceSeats.remember(answer, seat.value)
        seat.value = next
        update()
        withContext(Dispatchers.IO) { prefs.edit { if (next == null) remove(KEY_SEAT) else putBoolean(KEY_SEAT, next) } }
    }

    private suspend fun remember(answer: Boolean) {
        owned.value = answer
        update()
        withContext(Dispatchers.IO) { prefs.edit { putBoolean(KEY, answer) } }
    }

    private fun update() {
        state.value = ALWAYS || DeviceSeats.unlocked(owned.value, if (seats == null) null else seat.value)
    }

    /** True when Premium is unlocked. Otherwise opens the Premium screen and returns false. */
    fun allow(context: Context): Boolean {
        if (state.value) return true
        open(context)
        return false
    }

    companion object {
        /** The `github` build: everything unlocked, nothing sold. */
        val ALWAYS: Boolean = BuildConfig.PREMIUM_ALWAYS

        /** False in the `amazon` build (Fire TV): nothing is sold in the app, and the Tuned account brings Premium. */
        val SOLD_IN_APP: Boolean = BuildConfig.STORE != STORE_AMAZON

        const val STORE_AMAZON = "amazon"

        /** Downloads a free copy can keep on the device at once. */
        const val FREE_DOWNLOADS = 3

        const val YEARLY = "premium_yearly"
        const val LIFETIME = "premium_lifetime"

        private const val PREFS = "premium"
        private const val KEY = "unlocked"
        private const val KEY_SEAT = "seat"
        private const val KEY_RELEASED = "seat_released"
        private const val TAG = "Premium"
        private const val LOAD_WAIT_MS = 5_000L

        /** A purchase token's hash, as the account names the purchases it has recorded. */
        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun open(context: Context) {
            val intent = Intent(context, PremiumActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
