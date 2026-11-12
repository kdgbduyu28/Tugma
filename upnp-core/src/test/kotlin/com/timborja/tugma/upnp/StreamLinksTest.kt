package com.timborja.tugma.upnp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamLinksTest {
    @Test
    fun `playlist format comes from the extension only`() {
        assertEquals(StreamLinks.Format.PLS, StreamLinks.playlistFormat("http://radio.example/listen.pls?sid=1"))
        assertEquals(StreamLinks.Format.M3U, StreamLinks.playlistFormat("https://radio.example/a/B.M3U"))
        assertEquals(StreamLinks.Format.ASX, StreamLinks.playlistFormat("http://radio.example/live.asx"))
        assertNull(StreamLinks.playlistFormat("https://radio.example/live.m3u8"), "HLS is a stream")
        assertNull(StreamLinks.playlistFormat("http://radio.example:8000/stream"))
        assertNull(StreamLinks.playlistFormat("http://radio.example.pls/"), "the host isn't the file")
    }

    @Test
    fun `parses each playlist format`() {
        val m3u = "﻿#EXTM3U\n#EXTINF:-1,Example FM\nhttp://stream.example/fm.mp3\r\nhttp://backup.example/fm.mp3\n"
        assertEquals(listOf("http://stream.example/fm.mp3", "http://backup.example/fm.mp3"), StreamLinks.entries(m3u, StreamLinks.Format.M3U))

        val pls = "[playlist]\nNumberOfEntries=2\nFile2=http://b.example/2\nTitle1=One\nFile1=http://a.example/1\nVersion=2\n"
        assertEquals(listOf("http://a.example/1", "http://b.example/2"), StreamLinks.entries(pls, StreamLinks.Format.PLS))

        val xspf = """<playlist><trackList><track><location> https://x.example/a?x=1&amp;y=2 </location></track></trackList></playlist>"""
        assertEquals(listOf("https://x.example/a?x=1&y=2"), StreamLinks.entries(xspf, StreamLinks.Format.XSPF))

        val asx = """<asx version="3.0"><entry><REF HREF="mms://old.example/x"/><ref href='http://new.example/x'/></entry></asx>"""
        assertEquals(listOf("http://new.example/x"), StreamLinks.entries(asx, StreamLinks.Format.ASX), "non-http entries are dropped")
    }

    @Test
    fun `relative entries resolve against the playlist`() {
        assertEquals(
            listOf("http://radio.example/streams/hi.mp3"),
            StreamLinks.entries("hi.mp3\n", StreamLinks.Format.M3U, base = "http://radio.example/streams/list.m3u"),
        )
    }

    @Test
    fun `resolver follows playlists and leaves streams alone`() = runTest {
        val requested = mutableListOf<String>()
        val bodies = mapOf(
            "http://radio.example/listen.pls" to "[playlist]\nFile1=http://radio.example/inner.m3u\n",
            "http://radio.example/inner.m3u" to "#EXTM3U\nhttp://cdn.example/live.aac\n",
        )
        val resolver = StreamLinkResolver({ r -> requested += r.url; bodies[r.url]?.let { HttpResponse(200, it) } ?: HttpResponse(404, "") })

        assertEquals("http://cdn.example/live.aac", resolver.resolve(" http://radio.example/listen.pls "))
        assertEquals(listOf("http://radio.example/listen.pls", "http://radio.example/inner.m3u"), requested)

        requested.clear()
        assertEquals("http://cdn.example/live.aac", resolver.resolve("http://cdn.example/live.aac"))
        assertTrue(requested.isEmpty(), "a plain stream is never fetched")

        assertTrue("HTTP 404" in assertFailsWith<IOException> { resolver.resolve("http://radio.example/missing.m3u") }.message!!)
    }

    @Test
    fun `empty playlist and endless nesting fail clearly`() = runTest {
        val empty = StreamLinkResolver({ HttpResponse(200, "#EXTM3U\n# nothing here\n") })
        assertTrue("no http" in assertFailsWith<IOException> { empty.resolve("http://r.example/a.m3u") }.message!!)

        val loop = StreamLinkResolver({ HttpResponse(200, "http://r.example/a.m3u\n") })
        assertTrue("nests" in assertFailsWith<IOException> { loop.resolve("http://r.example/a.m3u") }.message!!)
    }
}
