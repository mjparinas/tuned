package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ListPagingTest {
    @Test
    fun pageAndChannelKeysPage() {
        assertEquals(1, ListPaging.direction(RemoteKeys.PAGE_DOWN))
        assertEquals(1, ListPaging.direction(RemoteKeys.CHANNEL_DOWN))
        assertEquals(-1, ListPaging.direction(RemoteKeys.PAGE_UP))
        assertEquals(-1, ListPaging.direction(RemoteKeys.CHANNEL_UP))
        assertEquals(0, ListPaging.direction(RemoteKeys.DPAD_DOWN))
    }

    @Test
    fun aPageMovesByAScreenful() {
        assertEquals(18, ListPaging.target(current = 9, direction = 1, page = 9, count = 2_000))
        assertEquals(1_991, ListPaging.target(current = 2_000, direction = -1, page = 9, count = 2_001))
    }

    @Test
    fun staysWithinTheList() {
        assertEquals(1_999, ListPaging.target(current = 1_995, direction = 1, page = 9, count = 2_000))
        assertEquals(0, ListPaging.target(current = 4, direction = -1, page = 9, count = 2_000))
        // Rows above the posters (Continue watching, Watch later) aren't landed on.
        assertEquals(2, ListPaging.target(current = 5, direction = -1, page = 12, count = 40, first = 2))
    }

    @Test
    fun anEmptyPageStillMoves() {
        assertEquals(6, ListPaging.target(current = 5, direction = 1, page = 0, count = 10))
        assertEquals(3, ListPaging.target(current = 3, direction = 1, page = 9, count = 0))
    }
}
