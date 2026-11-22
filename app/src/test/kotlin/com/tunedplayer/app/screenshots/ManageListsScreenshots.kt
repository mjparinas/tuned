package com.tunedplayer.app.screenshots

import com.tunedplayer.app.container
import com.tunedplayer.app.ui.ManageListsRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings' list manager, separate so other Settings screenshot work can land beside it. */
@RunWith(RobolectricTestRunner::class)
class ManageListsScreenshots : Screenshots() {
    /** Every custom list can be renamed or deleted from Settings, including with a TV remote. */
    @Test
    fun manageLists() {
        runBlocking(Dispatchers.IO) {
            app.container.lists.create("Weekend", null)
            app.container.lists.create("Sport", null)
            app.container.lists.create("Family films", null)
        }
        shot("settings-manage-lists", ManageListsRoute, wait = "Weekend")
    }
}
