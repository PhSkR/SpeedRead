package com.speedread.rsvp.data.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubFileParserTest {

    @Test
    fun `isTextMarkupEntry accepts text and markup extensions`() {
        assertTrue(EpubFileParser.isTextMarkupEntry("content.opf", false))
        assertTrue(EpubFileParser.isTextMarkupEntry("META-INF/container.xml", false))
        assertTrue(EpubFileParser.isTextMarkupEntry("OEBPS/chapter1.html", false))
        assertTrue(EpubFileParser.isTextMarkupEntry("OEBPS/chapter2.xhtml", false))
        assertTrue(EpubFileParser.isTextMarkupEntry("OEBPS/intro.htm", false))
    }

    @Test
    fun `isTextMarkupEntry is case insensitive`() {
        assertTrue(EpubFileParser.isTextMarkupEntry("PACKAGE.OPF", false))
        assertTrue(EpubFileParser.isTextMarkupEntry("INDEX.XHTML", false))
        assertTrue(EpubFileParser.isTextMarkupEntry("PAGE.HTML", false))
        assertTrue(EpubFileParser.isTextMarkupEntry("METADATA.XML", false))
    }

    @Test
    fun `isTextMarkupEntry rejects directories`() {
        assertFalse(EpubFileParser.isTextMarkupEntry("OEBPS/", true))
        assertFalse(EpubFileParser.isTextMarkupEntry("OEBPS/html.html/", true))
    }

    @Test
    fun `isTextMarkupEntry rejects images, fonts, and media`() {
        assertFalse(EpubFileParser.isTextMarkupEntry("images/cover.jpg", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("images/illustration.png", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("images/figure.webp", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("images/diagram.gif", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("fonts/serif.ttf", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("fonts/sans.otf", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("fonts/custom.woff", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("fonts/custom.woff2", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("audio/narration.mp3", false))
        assertFalse(EpubFileParser.isTextMarkupEntry("video/clip.mp4", false))
    }
}
