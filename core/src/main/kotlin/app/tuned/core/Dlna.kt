package app.tuned.core

import org.xmlpull.v1.XmlPullParser
import java.net.URI
import java.util.Locale

/**
 * The plain-text side of DLNA (UPnP AV) casting: finding renderers with SSDP, reading a renderer's description, and the
 * SOAP calls that tell it what to play. The app does the networking; everything here is testable on the JVM.
 */
object Dlna {
    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Any version of AVTransport. */
    private const val AV_TRANSPORT_PREFIX = "urn:schemas-upnp-org:service:AVTransport:"

    /** A TV or speaker that can be told to play a link. */
    data class Renderer(
        val name: String,
        /** Where AVTransport commands are posted. */
        val controlUrl: String,
        /** The device's own unique name, stable across restarts. */
        val udn: String,
    )

    /** The multicast search for media renderers; answers arrive within [waitSeconds]. */
    fun search(waitSeconds: Int = 2): String = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $waitSeconds\r\n" +
        "ST: $RENDERER\r\n\r\n"

    /** The description link from a search answer, or null if the answer isn't from a renderer. */
    fun location(response: String): String? {
        val lines = response.split("\r\n", "\n")
        if (lines.firstOrNull()?.startsWith("HTTP/1.1 200", ignoreCase = true) != true) return null
        val headers =
            lines
                .drop(1)
                .mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim() else null
                }.toMap()
        if (headers["st"]?.contains("MediaRenderer", ignoreCase = true) != true) return null
        return headers["location"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** Reads a device description fetched from [location]; null when the device can't be told what to play. */
    fun renderer(
        parser: XmlPullParser,
        location: String,
    ): Renderer? {
        val d = Description()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                d.start(parser)
            } else if (event == XmlPullParser.END_TAG) {
                d.end(parser.name)
            }
            event = parser.next()
        }
        val path = d.avControl?.takeIf { it.isNotEmpty() } ?: return null
        val url = runCatching { URI(d.base?.takeIf { it.isNotEmpty() } ?: location).resolve(path).toString() }.getOrNull() ?: return null
        return Renderer(d.name?.takeIf { it.isNotEmpty() } ?: "DLNA", url, d.udn ?: location)
    }

    /** What a description has said so far: the first device's name and id, and the AVTransport control link. */
    private class Description {
        var name: String? = null
        var udn: String? = null
        var base: String? = null
        var avControl: String? = null
        private var serviceType: String? = null
        private var control: String? = null

        fun start(parser: XmlPullParser) {
            when (parser.name) {
                "friendlyName" -> {
                    if (name == null) name = parser.nextText().trim()
                }

                "UDN" -> {
                    if (udn == null) udn = parser.nextText().trim()
                }

                "URLBase" -> {
                    base = parser.nextText().trim()
                }

                "service" -> {
                    serviceType = null
                    control = null
                }

                "serviceType" -> {
                    serviceType = parser.nextText().trim()
                }

                "controlURL" -> {
                    control = parser.nextText().trim()
                }
            }
        }

        fun end(tag: String?) {
            if (tag == "service" && avControl == null && serviceType?.startsWith(AV_TRANSPORT_PREFIX) == true) avControl = control
        }
    }

    /** The SOAPAction header for [action]. */
    fun soapAction(action: String) = "\"$AV_TRANSPORT#$action\""

    /** Tells the renderer what to play next; [play] then starts it. */
    fun setUri(
        url: String,
        title: String,
        mimeType: String,
    ): String {
        val didl =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mimeType:*\">${escape(url)}</res></item></DIDL-Lite>"
        return envelope(
            "SetAVTransportURI",
            "<CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>${escape(didl)}</CurrentURIMetaData>",
        )
    }

    fun play() = envelope("Play", "<Speed>1</Speed>")

    fun pause() = envelope("Pause", "")

    fun stop() = envelope("Stop", "")

    /** Jumps to [positionMs]; renderers that can't seek in a stream simply refuse. */
    fun seek(positionMs: Long): String {
        val total = positionMs.coerceAtLeast(0) / 1_000
        val time = String.format(Locale.ROOT, "%d:%02d:%02d", total / 3_600, total / 60 % 60, total % 60)
        return envelope("Seek", "<Unit>REL_TIME</Unit><Target>$time</Target>")
    }

    /** DLNA TVs play MPEG-TS and MP4 directly, so links go as they are; only the type is needed. */
    fun mimeFor(url: String): String = CastMedia.mimeFor(url.substringBefore('?')).let { if (it == "video/*") "video/mp2t" else it }

    private fun envelope(
        action: String,
        args: String,
    ) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
