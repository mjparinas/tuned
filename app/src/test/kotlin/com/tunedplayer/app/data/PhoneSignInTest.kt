package com.tunedplayer.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneSignInTest {
    @Test
    fun `the phone page's logins are made as the app's forms make them`() {
        val sent =
            listOf(
                Credentials("tv.example.com:8080/", " me ", "pw", name = "Living room"),
                Credentials("", "", "", "http://tv.example.com/get.php?username=a&password=b&type=m3u", "http://guide.example.com/g.xml"),
                Credentials("", "", "", "example.com/list.m3u"),
                Credentials.stalker("http://portal.example.com/c/", "00:1A:79:00:00:01"),
            )
        assertEquals(
            listOf(
                Credentials("http://tv.example.com:8080", "me", "pw", name = "Living room"),
                Credentials("http://tv.example.com", "a", "b", guideUrl = "http://guide.example.com/g.xml"),
                Credentials("", "", "", "http://example.com/list.m3u"),
                Credentials.stalker("http://portal.example.com/c/", "00:1A:79:00:00:01"),
            ),
            PhoneSignIn.clean(sent),
        )
    }

    @Test
    fun `files, blanks and anything that isn't a playlist are dropped`() {
        val sent =
            listOf(
                Credentials("", "", "", "${Credentials.FILE_SCHEME}/data/x.m3u"),
                Credentials("", "", "pw"),
                Credentials("http://tv.example.com", "", "pw"),
                Credentials("", "", "", "not a link"),
            )
        assertEquals(emptyList(), PhoneSignIn.clean(sent))
    }
}
