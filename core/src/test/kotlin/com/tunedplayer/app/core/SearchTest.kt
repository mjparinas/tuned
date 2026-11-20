package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTest {
    @Test
    fun everyWordMatchesByItsStart() {
        assertEquals("top* gu*", Search.match("Top  Gu"))
        assertEquals("tele* quebec*", Search.match("Télé-Québec"))
    }

    @Test
    fun punctuationCantBreakTheQuery() {
        assertEquals("ufc* 310*", Search.match("\"UFC\" 310 -*"))
        assertEquals("", Search.match("  ()*  "))
    }
}
