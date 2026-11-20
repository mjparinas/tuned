package com.tunedplayer.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/** Tuned follows the device's language, and Android's per-app language list names the same ones (docs/decisions/languages.md). */
@RunWith(RobolectricTestRunner::class)
class LanguagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the locale list names every translated language`() {
        val res = File("src/main/res")
        val folders =
            res
                .listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }
                .orEmpty()
                .map { it.name.removePrefix("values-").replace("-r", "-") }
                .toSet()
        val listed =
            Regex("""android:name="([^"]+)"""")
                .findAll(File(res, "xml/locales_config.xml").readText())
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(folders + "en", listed)
    }

    @Test
    @Config(qualifiers = "ar")
    fun `an Arabic device gets Arabic`() = assertEquals("الإعدادات", context.getString(R.string.settings))

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `a Chinese device gets Simplified Chinese`() = assertEquals("设置", context.getString(R.string.settings))

    @Test
    @Config(qualifiers = "ja")
    fun `a language Tuned isn't translated into falls back to English`() = assertEquals("Settings", context.getString(R.string.settings))

    @Test
    @Config(qualifiers = "es")
    fun `the name stays Tuned`() = assertEquals("Tuned", context.getString(R.string.app_name))
}
