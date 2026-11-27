package com.tunedplayer.app.premium

import android.app.Activity
import android.content.Context

/**
 * The `amazon` build (Fire TV, docs/decisions/tv.md#fire-tv) sells nothing in the app: Fire TV has no Google Play, and
 * Premium comes from the Tuned account the device signs in to ([Premium]), bought anywhere.
 */
@Suppress("UnusedParameter") // the same signature as the `play` build's storeFor, which uses both
internal fun storeFor(
    context: Context,
    onPurchased: () -> Unit,
): Store = AccountOnly

private object AccountOnly : Store {
    override val sells: Boolean = false

    /** No store to ask: the account answers instead. */
    override suspend fun owned(): Boolean? = null

    override suspend fun offers(): List<Offer> = emptyList()

    override suspend fun buy(
        activity: Activity,
        productId: String,
    ): Boolean = false
}
