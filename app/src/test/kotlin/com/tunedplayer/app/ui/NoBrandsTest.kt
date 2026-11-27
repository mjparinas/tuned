package com.tunedplayer.app.ui

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/** Store builds never name a provider, channel or sports brand, in any language (AGENTS.md › Modules). */
class NoBrandsTest {
    private val brands =
        listOf(
            "ESPN",
            "Sky Sports",
            "Sky Cinema",
            "BBC",
            "DAZN",
            "NFL",
            "UFC",
            "NBA",
            "NHL",
            "MLB",
            "HBO",
            "CNN",
            "Netflix",
            "beIN",
            "TSN",
            "Sportsnet",
            "Premier League",
            "Champions League",
        )

    @Test
    fun `no string in any language names a channel or sports brand`() {
        val pattern = Regex("""\b(${brands.joinToString("|") { Regex.escape(it) }})\b""")
        val found =
            File("src/main/res")
                .walkTopDown()
                .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
                .flatMap { file -> pattern.findAll(file.readText()).map { "${file.parentFile.name}/${file.name}: ${it.value}" } }
                .toList()
        assertEquals(emptyList(), found)
    }
}
