package app.tuned.premium

import android.app.Activity
import android.content.Context
import app.tuned.core.AccountSeats
import app.tuned.diagnostics.AppLog
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** The `play` build sells Premium through Google Play Billing. */
internal fun storeFor(
    context: Context,
    onPurchased: () -> Unit,
): Store = PlayStore(context, onPurchased)

private class PlayStore(
    context: Context,
    onPurchased: () -> Unit,
) : Store {
    private val listener =
        PurchasesUpdatedListener { result, _ ->
            // A finished purchase is checked and acknowledged by asking again, the same way as at start.
            if (result.responseCode == BillingClient.BillingResponseCode.OK) onPurchased()
        }
    private val client =
        BillingClient
            .newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    private val connecting = Mutex()
    private var details: Map<String, ProductDetails> = emptyMap()

    @Volatile private var found: SeatPurchase? = null

    @Volatile private var all: List<SeatPurchase> = emptyList()

    override fun purchase(): SeatPurchase? = found

    override fun purchases(): List<SeatPurchase> = all

    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }
    }

    override suspend fun owned(): Boolean? {
        if (!ready()) return null
        var owned = false
        var seen: SeatPurchase? = null
        val every = mutableListOf<SeatPurchase>()
        for (type in listOf(ProductType.INAPP, ProductType.SUBS)) {
            val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLog.w(TAG, "Couldn't read purchases: ${result.billingResult.debugMessage}")
                return null
            }
            result.purchasesList
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { id -> id in SOLD } }
                .forEach { purchase ->
                    val product = purchase.products.first { it in SOLD }
                    every += SeatPurchase(product, purchase.purchaseToken)
                    if (product in PRODUCTS) {
                        owned = true
                        seen = seen ?: SeatPurchase(product, purchase.purchaseToken)
                    }
                    // Play refunds a purchase that isn't acknowledged within three days.
                    if (!purchase.isAcknowledged) {
                        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                        client.acknowledgePurchase(params)
                    }
                }
        }
        found = seen
        all = every
        return owned
    }

    override suspend fun seatOffer(productId: String): Offer? {
        if (!ready()) return null
        val type = if (productId == AccountSeats.SEAT_YEARLY) ProductType.SUBS else ProductType.INAPP
        val product = lookUp(productId, type) ?: return null
        details = details + (productId to product)
        return offer(product)
    }

    override suspend fun consume(token: String) {
        if (!ready()) return
        val result = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(token).build())
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            AppLog.w(TAG, "Couldn't consume a seat purchase: ${result.billingResult.debugMessage}")
        }
    }

    private suspend fun lookUp(
        id: String,
        type: String,
    ): ProductDetails? {
        val product =
            QueryProductDetailsParams.Product
                .newBuilder()
                .setProductId(id)
                .setProductType(type)
                .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        return client.queryProductDetails(params).productDetailsList?.firstOrNull()
    }

    override suspend fun offers(): List<Offer> {
        if (!ready()) return emptyList()
        val found =
            listOf(Premium.YEARLY to ProductType.SUBS, Premium.LIFETIME to ProductType.INAPP).mapNotNull { (id, type) ->
                lookUp(id, type)
            }
        details = details + found.associateBy { it.productId }
        return found.mapNotNull(::offer)
    }

    override suspend fun buy(
        activity: Activity,
        productId: String,
    ): Boolean {
        if (!ready()) return false
        val product = details[productId] ?: return false
        val params =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(product)
                .apply { bestOffer(product)?.offerToken?.let(this::setOfferToken) }
                .build()
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()
        return client.launchBillingFlow(activity, flow).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun offer(product: ProductDetails): Offer? {
        product.oneTimePurchaseOfferDetails?.let { return Offer(product.productId, it.formattedPrice, 0, yearly = false) }
        val offer = bestOffer(product) ?: return null
        val phases = offer.pricingPhases.pricingPhaseList
        val paid = phases.lastOrNull() ?: return null
        val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
        return Offer(product.productId, paid.formattedPrice, trial?.let { days(it.billingPeriod) } ?: 0, yearly = true)
    }

    /** The subscription offer with a free trial when the account can still have one, else the base plan. */
    private fun bestOffer(product: ProductDetails): ProductDetails.SubscriptionOfferDetails? =
        product.subscriptionOfferDetails?.maxByOrNull { it.pricingPhases.pricingPhaseList.size }

    private companion object {
        const val TAG = "Premium"
        val PRODUCTS = setOf(Premium.YEARLY, Premium.LIFETIME)

        /** Premium and the account's extra seats (docs/decisions/accounts.md). */
        val SOLD = PRODUCTS + setOf(AccountSeats.SEAT_ONCE, AccountSeats.SEAT_YEARLY)

        /** An ISO 8601 period such as P7D or P1W, in days. */
        fun days(period: String): Int {
            val n = period.filter(Char::isDigit).toIntOrNull() ?: return 0
            return when {
                period.endsWith("W") -> n * DAYS_IN_WEEK
                period.endsWith("M") -> n * DAYS_IN_MONTH
                else -> n
            }
        }

        const val DAYS_IN_WEEK = 7
        const val DAYS_IN_MONTH = 30
    }
}
