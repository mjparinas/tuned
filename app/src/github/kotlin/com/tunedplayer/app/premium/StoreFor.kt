package com.tunedplayer.app.premium

import android.app.Activity
import android.content.Context

/** The `github` build sells nothing: everything is unlocked ([Premium.ALWAYS]). */
@Suppress("UnusedParameter") // the same signature as the `play` build's storeFor, which uses both
internal fun storeFor(
    context: Context,
    onPurchased: () -> Unit,
): Store = NoStore

private object NoStore : Store {
    override suspend fun owned(): Boolean = true

    override suspend fun offers(): List<Offer> = emptyList()

    override suspend fun buy(
        activity: Activity,
        productId: String,
    ): Boolean = false
}
