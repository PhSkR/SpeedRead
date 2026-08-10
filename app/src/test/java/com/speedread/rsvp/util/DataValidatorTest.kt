package com.speedread.rsvp.util

import org.junit.Test
import org.junit.Assert.*

class DataValidatorTest {

    @Test
    fun `validateTextForProcessing should return Valid for valid text`() {
        val result = DataValidator.validateTextForProcessing("This is a valid text.")
        
        assertTrue(result is DataValidator.ValidationResult.Valid)
        assertEquals("This is a valid text.", (result as DataValidator.ValidationResult.Valid).value)
    }

    @Test
    fun `validateTextForProcessing should return Invalid for blank text`() {
        val result = DataValidator.validateTextForProcessing("")
        
        assertTrue(result is DataValidator.ValidationResult.Invalid)
        assertTrue((result as DataValidator.ValidationResult.Invalid).reason.contains("blank"))
    }

    @Test
    fun `validateTextForProcessing should return Invalid for very large text`() {
        val largeText = "x".repeat(11_000_000) // More than 10 million characters
        val result = DataValidator.validateTextForProcessing(largeText)
        
        assertTrue(result is DataValidator.ValidationResult.Invalid)
        assertTrue((result as DataValidator.ValidationResult.Invalid).reason.contains("large"))
    }

    @Test
    fun `sanitizeText should remove null characters`() {
        val input = "Text\u0000with\u0000null\u0000characters"
        val result = DataValidator.sanitizeText(input)
        
        assertEquals("Textwithnullcharacters", result)
        assertFalse(result.contains("\u0000"))
    }

    @Test
    fun `sanitizeText should remove BOM characters`() {
        val input = "Text\ufffewith\ufffebom\ufffecharacters"
        val result = DataValidator.sanitizeText(input)
        
        assertEquals("Textwithbomcharacters", result)
        assertFalse(result.contains("\ufffe"))
    }

    @Test
    fun `validateDocumentTitle should return Valid for valid title`() {
        val result = DataValidator.validateDocumentTitle("Valid Title")
        
        assertTrue(result is DataValidator.ValidationResult.Valid)
        assertEquals("Valid Title", (result as DataValidator.ValidationResult.Valid).value)
    }

    @Test
    fun `validateDocumentTitle should return Invalid for blank title`() {
        val result = DataValidator.validateDocumentTitle("")
        
        assertTrue(result is DataValidator.ValidationResult.Invalid)
        assertTrue((result as DataValidator.ValidationResult.Invalid).reason.contains("blank"))
    }

    @Test
    fun `validateDocumentTitle should return Invalid for too long title`() {
        val longTitle = "x".repeat(300) // More than 255 characters
        val result = DataValidator.validateDocumentTitle(longTitle)
        
        assertTrue(result is DataValidator.ValidationResult.Invalid)
        assertTrue((result as DataValidator.ValidationResult.Invalid).reason.contains("long"))
    }

    @Test
    fun `validateDocumentTitle should return Invalid for title with invalid characters`() {
        val invalidTitle = "Title<with>invalid:chars"
        val result = DataValidator.validateDocumentTitle(invalidTitle)
        
        assertTrue(result is DataValidator.ValidationResult.Invalid)
        assertTrue((result as DataValidator.ValidationResult.Invalid).reason.contains("invalid"))
    }
}