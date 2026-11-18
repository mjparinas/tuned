package app.tuned.premium

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.tuned.BuildConfig
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
 * each time the app comes to the front.
 */
class Premium(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val store: Store = storeFor(app) { refresh() }
    private val state = MutableStateFlow(ALWAYS)
    val unlocked: StateFlow<Boolean> = state.asStateFlow()

    init {
        if (!ALWAYS) scope.launchSafely(TAG) { state.value = withContext(Dispatchers.IO) { prefs.getBoolean(KEY, false) } }
    }

    /** Asks the store again in the background; an unreachable store leaves the remembered answer alone. */
    fun refresh() {
        if (ALWAYS) return
        scope.launchSafely(TAG) { store.owned()?.let { remember(it) } }
    }

    /** Asks the store now, for Restore purchase. */
    suspend fun restore(): Boolean? {
        if (ALWAYS) return true
        return store.owned()?.also { remember(it) }
    }

    private suspend fun remember(owned: Boolean) {
        state.value = owned
        withContext(Dispatchers.IO) { prefs.edit { putBoolean(KEY, owned) } }
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
        private const val TAG = "Premium"

        fun open(context: Context) {
            val intent = Intent(context, PremiumActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
