package com.tunedplayer.app.core

import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmltvParserTest {
    private val guide =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="x">
          <channel id="cnn.us"><display-name>CNN</display-name></channel>
          <programme start="20261117050000 +0000" stop="20261117060000 +0000" channel="cnn.us">
            <title lang="en">Morning News</title><desc lang="en">Headlines &amp; weather</desc>
            <category lang="en">News</category>
          </programme>
          <programme start="20261117060000 -0500" stop="20261117070000 -0500" channel="cnn.us">
            <title>Later</title>
          </programme>
          <programme start="20261117050000 +0000" stop="20261117060000 +0000" channel="other">
            <title>Skipped</title>
          </programme>
        </tv>
        """.trimIndent()

    private fun parse(bytes: ByteArray): List<Programme> {
        val parser = KXmlParser()
        parser.setInput(Streams.maybeGunzip(ByteArrayInputStream(bytes)), "UTF-8")
        val out = mutableListOf<Programme>()
        XmltvParser.parse(parser, keep = { id, _, _ -> id == "cnn.us" }) { out += it }
        return out
    }

    @Test
    fun `keeps wanted channels with titles, descriptions and offsets applied`() {
        val list = parse(guide.toByteArray())
        assertEquals(2, list.size)
        assertEquals("Morning News", list[0].title)
        assertEquals("Headlines & weather", list[0].description)
        assertEquals(XmltvParser.parseTime("20261117050000 +0000")!! + 3_600_000, list[0].stop)
        assertNull(list[1].description)
        assertEquals(listOf("News"), list[0].categories)
        assertEquals(emptyList(), list[1].categories)
        // 06:00 at -0500 is 11:00 UTC.
        assertEquals(XmltvParser.parseTime("20261117110000"), list[1].start)
    }

    @Test
    fun `lists the guide's channels with their names`() {
        val parser = KXmlParser()
        parser.setInput(ByteArrayInputStream(guide.toByteArray()), "UTF-8")
        val channels = mutableListOf<Pair<String, String>>()
        val count = XmltvParser.programmes(parser, keep = { id, _, _ -> id == "cnn.us" }) { id, name -> channels += id to name }.count()
        assertEquals(listOf("cnn.us" to "CNN"), channels)
        assertEquals(2, count)
    }

    @Test
    fun `reads gzipped guides`() {
        val zipped = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(guide.toByteArray()) } }.toByteArray()
        assertEquals(2, parse(zipped).size)
    }

    @Test
    fun `an offset without a space before it still counts`() {
        // 06:00 at +0100 is 05:00 UTC; read as UTC it was an hour late.
        assertEquals(XmltvParser.parseTime("20261117050000 +0000"), XmltvParser.parseTime("20261117060000+0100"))
        assertEquals(XmltvParser.parseTime("20261117110000"), XmltvParser.parseTime("202611170600-0500"))
    }

    @Test
    fun `bad times are rejected`() {
        assertNull(XmltvParser.parseTime("tomorrow"))
        assertEquals(0L, XmltvParser.parseTime("197001010000"))
    }
}
