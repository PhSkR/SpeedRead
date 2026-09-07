package com.speedread.rsvp.data.parser

import com.speedread.rsvp.data.parser.PdfFileParser.Companion.countWords
import com.speedread.rsvp.data.parser.PdfFileParser.Companion.isWordSeparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFileParserTest {

    @Test
    fun `countWords counts simple words separated by spaces`() {
        assertEquals(3, countWords("one two three"))
    }

    @Test
    fun `countWords handles tabs and newlines`() {
        assertEquals(5, countWords("one\ttwo\nthree\rfour\r\nfive"))
    }

    @Test
    fun `countWords handles non-breaking spaces`() {
        // U+00A0 NO-BREAK SPACE and U+202F NARROW NO-BREAK SPACE
        assertEquals(3, countWords("one\u00A0two\u202Fthree"))
    }

    @Test
    fun `countWords handles zero-width space and BOM`() {
        // U+200B ZERO WIDTH SPACE and U+FEFF ZERO WIDTH NO-BREAK SPACE (BOM)
        assertEquals(3, countWords("one\u200Btwo\uFEFFthree"))
    }

    @Test
    fun `countWords handles NEL next line control character`() {
        // U+0085 NEXT LINE (NEL)
        assertEquals(2, countWords("one\u0085two"))
    }

    @Test
    fun `countWords handles C0 and C1 control characters`() {
        // U+0000 NUL, U+001F US, U+007F DEL, U+0090 DCS
        assertEquals(5, countWords("one\u0000two\u001Fthree\u007Ffour\u0090five"))
    }

    @Test
    fun `countWords collapses consecutive separators`() {
        assertEquals(2, countWords("   one   \t\t\n\n\u00A0\u00A0   two   "))
    }

    @Test
    fun `countWords returns zero for empty or all-separator text`() {
        assertEquals(0, countWords(""))
        assertEquals(0, countWords("   \t\n  \u00A0 \u200B  "))
    }

    @Test
    fun `isWordSeparator detects all expected separators`() {
        assertTrue(' '.isWordSeparator())
        assertTrue('\t'.isWordSeparator())
        assertTrue('\n'.isWordSeparator())
        assertTrue('\r'.isWordSeparator())
        assertTrue(Char(0x00A0).isWordSeparator())
        assertTrue(Char(0x202F).isWordSeparator())
        assertTrue(Char(0x200B).isWordSeparator())
        assertTrue(Char(0xFEFF).isWordSeparator())
        assertTrue(Char(0x0085).isWordSeparator())
        assertTrue(Char(0x0000).isWordSeparator())
        assertTrue(Char(0x007F).isWordSeparator())
        assertTrue(Char(0x0090).isWordSeparator())

        assertFalse('a'.isWordSeparator())
        assertFalse('Z'.isWordSeparator())
        assertFalse('0'.isWordSeparator())
        assertFalse('.'.isWordSeparator())
        assertFalse('-'.isWordSeparator())
    }
}
