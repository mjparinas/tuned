package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playlists written the way other providers write them, so countries, languages, adult categories and sections keep
 * working away from the provider Tuned was first built for. Every server, login and channel here is made up.
 */
class ProviderFormatsTest {
    private fun parse(text: String): List<PlaylistEntry> {
        val out = mutableListOf<PlaylistEntry>()
        M3uParser.parse(text.reader().buffered()) { out += it }
        return out
    }

    private fun region(group: String) = Regions.detect(group, null, "Some Channel")

    @Test
    fun `any symbol between the tag and the category separates them`() {
        for (group in listOf("US » News", "US ➤ News", "US ┃ News", "US ✦ News", "US ⋆ News", "US ▎News", "US ~ News", "US.News")) {
            assertEquals("US", region(group), group)
        }
    }

    @Test
    fun `flag emoji name the country`() {
        assertEquals("US", region("🇺🇸 News"))
        assertEquals("UK", region("🇬🇧 Sports"))
        assertEquals("FR", Regions.detect("Cinéma", null, "🇫🇷 TF1"))
    }

    @Test
    fun `country and language names written out are read as their tag`() {
        assertEquals("US", region("UNITED STATES | News"))
        assertEquals("UK", region("United Kingdom: Sports"))
        assertEquals("CA", region("Canada: News"))
        assertEquals("EN", region("ENGLISH | Movies"))
        assertEquals("AR", region("ARABIC | News"))
        assertEquals("LAT", region("LATINO | Deportes"))
        assertEquals("EX", region("EXYU| HRT"))
        assertEquals("CA", region("CA FR | Québec"))
    }

    @Test
    fun `a category that is only a country is that country`() {
        assertEquals("UK", region("UK"))
        assertEquals("US", region("USA"))
        assertEquals("DE", region("Germany"))
        assertNull(region("News"))
        assertNull(region("Kids"))
    }

    @Test
    fun `sports leagues, brands and networks are not countries`() {
        for (group in listOf(
            "PPV | UFC",
            "NBA | League Pass",
            "NFL | Sunday",
            "EPL | Matches",
            "SKY | Sports",
            "BT | Sport",
            "ITV | Hub",
            "CBC | Canada",
        )) {
            assertNull(region(group), group)
        }
        assertNull(Regions.detect("News", null, "CNN | Live"))
    }

    @Test
    fun `a country in brackets at the end of a name counts`() {
        assertEquals("US", Regions.detect("Sports", null, "ESPN (US)"))
        assertEquals("US", Regions.detect("News", null, "CNN [USA]"))
        assertNull(Regions.detect("Sports", null, "ESPN (HD)"))
    }

    @Test
    fun `tvg-country may be a name, and an unknown one falls back to the category`() {
        assertEquals("US", Regions.detect("News", "United States", "x"))
        assertEquals("UK", Regions.detect("News", "gb", "x"))
        assertEquals("FR", Regions.detect("FR| News", "INT", "x"))
        assertEquals("LAT", Regions.detect("Deportes", "AR", "x")) // ISO AR is Argentina, not Arabic
    }

    @Test
    fun `tvg-language fills in when nothing else names a country`() {
        assertEquals("EN", Regions.detect("News", null, "CNN", "English"))
        assertEquals("ES", Regions.detect("News", null, "x", "spa;eng"))
        assertEquals("FR", Regions.detect("News", null, "x", "fr"))
        assertEquals("US", Regions.detect("US| News", null, "x", "Spanish")) // the provider's tag still wins
        assertNull(Regions.detect("News", null, "x", "Klingon"))
    }

    @Test
    fun `adult categories in other languages and by studio name are caught`() {
        for (group in listOf(
            "+18",
            "18+ Only",
            "Adultos",
            "Adulte",
            "Adulti",
            "Für Erwachsene",
            "Erotic",
            "Playboy TV",
            "Brazzers",
            "Hustler HD",
            "Red Light",
        )) {
            assertTrue(Classifier.isAdult(group, "x"), group)
        }
    }

    @Test
    fun `adult swim and young adult are for everyone`() {
        assertFalse(Classifier.isAdult("US| Adult Swim", "Adult Swim"))
        assertFalse(Classifier.isAdult("Books", "Young Adults Hour"))
        assertTrue(Classifier.isAdult("XXX | Adult", "Adult Swim Parody")) // the category still says so
    }

    @Test
    fun `xtream live links stay live whatever the category says`() {
        assertEquals(ContentKind.LIVE, Classifier.kind("http://host:8080/user/pass/1201.ts", "UK| Sky Cinema"))
        assertEquals(ContentKind.LIVE, Classifier.kind("http://host:8080/user/pass/1202", "US| Kids Shows"))
        assertEquals(ContentKind.LIVE, Classifier.kind("http://host:8080/user/pass/1203.m3u8", "EN| Movies 24/7"))
        assertEquals(ContentKind.MOVIE, Classifier.kind("http://host:8080/movie/user/pass/1204.mkv", "EN| Sky Cinema"))
    }

    @Test
    fun `plain m3u sections read film files and channel categories`() {
        assertEquals(ContentKind.LIVE, Classifier.kind("https://cdn.example/a/master.m3u8", "Movie Channels"))
        assertEquals(ContentKind.LIVE, Classifier.kind("https://cdn.example/a/master.m3u8", "24/7 Shows"))
        assertEquals(ContentKind.SERIES, Classifier.kind("https://cdn.example/a/ep.mp4", "TV Shows"))
        assertEquals(ContentKind.MOVIE, Classifier.kind("https://cdn.example/a/film.mp4?token=1", "Movie Channels"))
        assertEquals(ContentKind.MOVIE, Classifier.kind("https://cdn.example/a/1.ts", "Películas"))
        assertEquals(ContentKind.SERIES, Classifier.kind("https://cdn.example/a/1.ts", "Séries"))
        assertEquals(ContentKind.SERIES, Classifier.kind("https://cdn.example/a/1.ts", "Serien"))
    }

    @Test
    fun `episode numbering in words and other languages`() {
        assertEquals(EpisodeInfo("Dark", 1, 2), Episodes.parse("Dark Season 1 Episode 2", "g"))
        assertEquals(EpisodeInfo("Lupin", 1, 2), Episodes.parse("Lupin - Saison 1 Épisode 2", "g"))
        assertEquals(EpisodeInfo("Élite", 3, 4), Episodes.parse("Élite Temporada 3 Episodio 4", "g"))
        assertEquals(EpisodeInfo("Élite", 3, 4), Episodes.parse("Élite T03E04", "g"))
        assertEquals(EpisodeInfo("Dark", 2, 5), Episodes.parse("Dark Staffel 2 Folge 5", "g"))
        assertEquals(EpisodeInfo("The Bear", 1, 2), Episodes.parse("The Bear S01 EP02", "g"))
        assertEquals(EpisodeInfo("Taskmaster", 2024, 5), Episodes.parse("Taskmaster S2024E05", "g"))
    }

    @Test
    fun `an iptv-org style playlist with single quotes, tvg-language and player options`() {
        val list =
            parse(
                """
                #EXTM3U x-tvg-url="https://guide.example/epg.xml.gz"
                #EXTINF:-1 tvg-id="ExampleNews.us" tvg-name="Example News" tvg-country="US" tvg-language="English" tvg-logo="https://logo.example/n.png" group-title="News",Example News (1080p)
                #EXTVLCOPT:http-user-agent=Mozilla/5.0
                #EXTVLCOPT:http-referrer=https://site.example/
                https://cdn.example/news/index.m3u8
                #EXTINF:-1 tvg-id='Deportes.mx' tvg-language='Spanish' group-title='Sports',Deportes Uno
                https://cdn.example/deportes/index.m3u8
                #EXTINF:-1 tvg-id="Kids.ca" group-title="Kids;Animation" tvg-country="",Kids Channel [CA]
                https://cdn.example/kids/index.m3u8
                """.trimIndent(),
            )
        assertEquals(3, list.size)
        val (news, sports, kids) = list
        assertEquals("US", news.region)
        assertEquals(ContentKind.LIVE, news.kind)
        assertEquals("https://cdn.example/news/index.m3u8", news.url)
        assertEquals("Deportes Uno", sports.name)
        assertEquals("Sports", sports.group)
        assertEquals("Deportes.mx", sports.tvgId)
        assertEquals("ES", sports.region)
        assertEquals("CA", kids.region)
    }
}
