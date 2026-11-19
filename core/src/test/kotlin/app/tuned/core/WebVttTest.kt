package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WebVttTest {
    @Test
    fun subRipBecomesWebVtt() {
        val srt =
            "﻿1\r\n00:00:01,600 --> 00:00:04,200\r\nHello <i>there</i>.\r\n<font color=\"#fff\">Second line</font>\r\n\r\n" +
                "2\r\n00:01:02,5 --> 01:00:00,000 X1:10 X2:20\r\n{\\an8}Top\r\n"
        val expected =
            "WEBVTT\n\n" +
                "00:00:01.600 --> 00:00:04.200\nHello <i>there</i>.\nSecond line\n\n" +
                "00:01:02.500 --> 01:00:00.000\nTop\n\n"
        assertEquals(expected, WebVtt.fromSubRip(srt))
    }

    @Test
    fun theOffsetMovesEveryCueAndDropsThoseBeforeTheStart() {
        val srt =
            "1\n00:00:01,000 --> 00:00:02,000\nGone\n\n2\n00:00:02,500 --> 00:00:04,000\nCut\n\n" +
                "3\n00:00:10,000 --> 00:00:11,000\nLate\n"
        val vtt = WebVtt.fromSubRip(srt, offsetMs = -3_000)
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nCut\n\n00:00:07.000 --> 00:00:08.000\nLate\n\n", vtt)
        assertEquals(
            "WEBVTT\n\n00:00:01.500 --> 00:00:02.500\nGone\n\n",
            WebVtt.fromSubRip("1\n00:00:01,000 --> 00:00:02,000\nGone\n", offsetMs = 500),
        )
    }

    @Test
    fun blocksWithoutTimesOrTextAreSkipped() {
        assertEquals("WEBVTT\n\n", WebVtt.fromSubRip("1\nnot a time\nText\n\n2\n00:00:01,000 --> 00:00:02,000\n\n"))
        assertEquals("WEBVTT\n\n", WebVtt.fromSubRip(""))
    }
}
