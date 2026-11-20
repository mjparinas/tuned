package com.tunedplayer.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The settings the website can change (`devices/lib/web-settings.json`, docs/decisions/accounts.md) must be the app's
 * own: a stored key that syncs, of the type the website writes, with the app's default and only values the app knows.
 */
class WebSettingsTest {
    private val groups = Json.parseToJsonElement(File("../devices/lib/web-settings.json").readText()).jsonArray
    private val settings = groups.flatMap { it.jsonObject["settings"]!!.jsonArray }.map { it.jsonObject }
    private val fields = SETTING_FIELDS.associateBy { it.key.name }
    private val defaults =
        mutablePreferencesOf()
            .also { p -> SETTING_FIELDS.forEach { it.write(p, AppSettings()) } }
            .asMap()
            .mapKeys { it.key.name }

    @Test
    fun `every website setting is a synced app setting with the app's default`() {
        assertTrue(settings.isNotEmpty())
        for (s in settings) {
            val key = s["key"]!!.jsonPrimitive.content
            val field = assertNotNull(fields[key], "$key isn't an app setting")
            assertTrue(!field.deviceOnly, "$key stays on each device, so the website can't change it")
            val stored = defaults[key]
            val default = s["default"] as JsonPrimitive
            when (s["type"]!!.jsonPrimitive.content) {
                "bool" -> assertEquals(stored, default.boolean, key)
                "int" -> assertEquals(stored, default.int, key)
                "choice" -> assertEquals(stored, default.content, key)
                else -> error("unknown type for $key")
            }
        }
    }

    @Test
    fun `choices are the app's own values and include the default`() {
        val enums =
            mapOf(
                "theme" to ThemeMode.entries,
                "accent" to Accent.entries,
                "start_tab" to StartTab.entries,
                "buffer" to BufferMode.entries,
                "video_fit" to VideoFit.entries,
            )
        for (s in settings.filter { it["type"]!!.jsonPrimitive.content == "choice" }) {
            val key = s["key"]!!.jsonPrimitive.content
            val values = (s["options"] as JsonArray).map { it.jsonArray[0].jsonPrimitive.content }
            val known = assertNotNull(enums[key], "add $key's enum to this test").map { it.name }
            assertTrue(known.containsAll(values), "$key offers a value the app doesn't know: ${values - known.toSet()}")
            assertTrue(s["default"]!!.jsonPrimitive.content in values, key)
        }
    }
}
