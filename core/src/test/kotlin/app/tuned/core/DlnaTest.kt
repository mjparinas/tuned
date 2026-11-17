package app.tuned.core

import org.kxml2.io.KXmlParser
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaTest {
    private fun parse(
        xml: String,
        location: String,
    ) = Dlna.renderer(KXmlParser().apply { setInput(StringReader(xml)) }, location)

    @Test
    fun readsTheLocationFromARendererAnswer() {
        val answer =
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:9197/dmr\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.20:9197/dmr", Dlna.location(answer))
    }

    @Test
    fun ignoresAnswersFromOtherDevices() {
        val answer =
            "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1/igd.xml\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
        assertNull(Dlna.location(answer))
        assertNull(Dlna.location("NOTIFY * HTTP/1.1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"))
    }

    @Test
    fun findsTheAvTransportControlUrlRelativeToTheDescription() {
        val xml =
            """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>Living Room TV</friendlyName>
                <UDN>uuid:1234</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/rc/control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>upnp/control/AVTransport1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
            """.trimIndent()
        assertEquals(
            Dlna.Renderer("Living Room TV", "http://192.168.1.20:9197/upnp/control/AVTransport1", "uuid:1234"),
            parse(xml, "http://192.168.1.20:9197/dmr"),
        )
    }

    @Test
    fun aDeviceWithoutAvTransportCantPlay() {
        val xml = "<root><device><friendlyName>Speaker</friendlyName></device></root>"
        assertNull(parse(xml, "http://192.168.1.30/desc.xml"))
    }

    @Test
    fun theLinkAndTitleAreEscapedInTheSoapBody() {
        val body = Dlna.setUri("http://tv.example/live/u/p/1.ts?a=1&b=2", "News & <Sport>", "video/mp2t")
        assertTrue("<CurrentURI>http://tv.example/live/u/p/1.ts?a=1&amp;b=2</CurrentURI>" in body)
        // The metadata is itself XML inside XML, so the title is escaped twice.
        assertTrue("News &amp;amp; &amp;lt;Sport&amp;gt;" in body)
        assertEquals("video/mp2t", Dlna.mimeFor("http://tv.example/u/p/1"))
        assertEquals("video/mp4", Dlna.mimeFor("http://tv.example/movie/u/p/5.mp4"))
        assertTrue("<Target>1:02:05</Target>" in Dlna.seek(3_725_000))
    }
}
