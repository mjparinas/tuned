package com.tunedplayer.app.diagnostics

import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceDetailsTest {
    @After
    fun reset() = AppLog.setSecrets()

    @Test
    fun `lays the details out in sections`() {
        val text =
            DeviceDetails.render(
                listOf(
                    DeviceDetails.Section("Device", listOf("Model" to "SHIELD Android TV", "Android" to "11 (API 30)")),
                    DeviceDetails.Section("Playlists", listOf("Saved" to "2")),
                ),
                secrets = emptyList(),
            )
        assertEquals("[Device]\nModel: SHIELD Android TV\nAndroid: 11 (API 30)\n\n[Playlists]\nSaved: 2", text)
    }

    @Test
    fun `masks every saved login, even one the log doesn't know about`() {
        val text =
            DeviceDetails.render(
                listOf(
                    DeviceDetails.Section(
                        "Odd",
                        listOf(
                            "Name" to "alice's s3cretpw box",
                            "Link" to "http://tv.example/get.php?username=alice&password=s3cretpw",
                            "Guide" to "http://epg.example/guide.xml?key=abc123key",
                        ),
                    ),
                ),
                secrets = listOf("s3cretpw", "alice", "http://epg.example/guide.xml?key=abc123key", "", "ab"),
            )
        assertFalse("s3cretpw" in text || "alice" in text || "abc123key" in text, text)
        assertTrue("•••" in text)
    }

    @Test
    fun `masks the login in use the way the log does`() {
        AppLog.setSecrets("hunter22", "bob")
        val text = DeviceDetails.render(listOf(DeviceDetails.Section("X", listOf("Error" to "Failed for bob with hunter22"))), emptyList())
        assertFalse("hunter22" in text, text)
    }
}
