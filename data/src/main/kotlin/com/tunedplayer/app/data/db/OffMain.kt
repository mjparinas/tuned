package com.tunedplayer.app.data.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs raw SQL off the screen thread. Room moves its own DAO calls off it, but SQL through `openHelper` runs on whatever
 * thread calls it, and a caller in a screen's scope is the screen thread (Melvin, 2026-11-20: 2.3.0 froze for 5 seconds
 * and more while the Shows table was rebuilt from Home). Every helper here that uses `openHelper` starts with this, so
 * where it is called from never matters (docs/decisions/speed.md › Nothing on the screen thread).
 */
suspend fun <T> offMain(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)
