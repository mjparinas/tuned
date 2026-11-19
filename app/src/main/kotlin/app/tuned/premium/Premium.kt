package app.tuned.premium

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.tuned.BuildConfig
import app.tuned.core.DeviceSeats
import app.tuned.core.SeatAnswer
import app.tuned.core.SeatDevice
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One thing a store sells: its product id, its price as the store writes it, and its free trial in days (0 for none). */
data class Offer(
    val productId: String,
    val price: String,
    val trialDays: Int,
    val yearly: Boolean,
)

/** The store behind the Premium unlock: Play Billing in the `play` build, nothing in the `github` build. */
interface Store {
    /** True when this account owns Premium, false when it doesn't, null when the store couldn't be asked. */
    suspend fun owned(): Boolean?

    /** The Premium purchase [owned] last found, for the device list; null when none, or nothing is sold. */
    fun purchase(): SeatPurchase? = null

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

    /** True when this build limits Premium to [DeviceSeats.LIMIT] devices. */
    val limited: Boolean get() = seats != null

    init {
        if (!ALWAYS) {
            scope.launchSafely(TAG) {
                withContext(Dispatchers.IO) {
                    owned.value = prefs.getBoolean(KEY, false)
                    seat.value = if (prefs.contains(KEY_SEAT)) prefs.getBoolean(KEY_SEAT, true) else null
                }
                update()
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
        val answer = store.owned() ?: return null
        remember(answer)
        val list = seats
        val purchase = store.purchase()
        if (answer && list != null && purchase != null && !released()) heard(list.activate(purchase))
        return answer
    }

    /**
     * Frees [target]'s seat. Freeing this device's own seat also keeps it from taking one again on its own until
     * [useHere]. False when the list couldn't be reached.
     */
    suspend fun deactivate(target: SeatDevice): Boolean {
        val list = seats ?: return false
        val purchase = store.purchase() ?: return false
        val answer = list.deactivate(purchase, target.id)
        if (answer == SeatAnswer.Unreachable) return false
        if (target.current) withContext(Dispatchers.IO) { prefs.edit { putBoolean(KEY_RELEASED, true) } }
        heard(answer)
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

        /** Downloads a free copy can keep on the device at once. */
        const val FREE_DOWNLOADS = 3

        const val YEARLY = "premium_yearly"
        const val LIFETIME = "premium_lifetime"

        private const val PREFS = "premium"
        private const val KEY = "unlocked"
        private const val KEY_SEAT = "seat"
        private const val KEY_RELEASED = "seat_released"
        private const val TAG = "Premium"

        fun open(context: Context) {
            val intent = Intent(context, PremiumActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
