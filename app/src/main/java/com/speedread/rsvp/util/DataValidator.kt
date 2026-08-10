package com.speedread.rsvp.util

/**
 * Data validation and sanitization utility
 */
object DataValidator {
    
    /**
     * Validates that a text string is appropriate for processing
     */
    fun validateTextForProcessing(text: String): ValidationResult {
        if (text.isBlank()) {
            return ValidationResult.Invalid("Text is blank or empty")
        }
        
        if (text.length > 10_000_000) { // 10MB limit
            return ValidationResult.Invalid("Text is too large (${text.length} characters). Maximum allowed: 10,000,000 characters.")
        }
        
        // Check for potentially harmful content (basic check)
        if (containsPotentialSecurityIssues(text)) {
            return ValidationResult.Invalid("Text contains potentially unsafe content")
        }
        
        return ValidationResult.Valid(text)
    }
    
    /**
     * Sanitizes text by removing potentially unsafe content
     */
    fun sanitizeText(text: String): String {
        return text
            .replace("\u0000", "") // Remove null characters
            .replace("\ufffe", "") // Remove potential problematic Unicode
            .replace("\ufeff", "") // Remove BOM characters
    }
    
    /**
     * Validates document title
     */
    fun validateDocumentTitle(title: String): ValidationResult {
        if (title.isBlank()) {
            return ValidationResult.Invalid("Title cannot be blank")
        }
        
        if (title.length > 255) {
            return ValidationResult.Invalid("Title is too long (max 255 characters)")
        }
        
        // Check for invalid characters
        val invalidChars = Regex("[<>:\"/\\\\|?*\\x00-\\x1f]")
        if (invalidChars.containsMatchIn(title)) {
            return ValidationResult.Invalid("Title contains invalid characters")
        }
        
        return ValidationResult.Valid(title.trim())
    }
    
    /**
     * Validates file name
     */
    fun validateFileName(fileName: String): ValidationResult {
        if (fileName.isBlank()) {
            return ValidationResult.Invalid("File name cannot be blank")
        }
        
        if (fileName.length > 255) {
            return ValidationResult.Invalid("File name is too long (max 255 characters)")
        }
        
        // Check for invalid characters in file names
        val invalidChars = Regex("[<>:\"/\\\\|?*\\x00-\\x1f]")
        if (invalidChars.containsMatchIn(fileName)) {
            return ValidationResult.Invalid("File name contains invalid characters")
        }
        
        return ValidationResult.Valid(fileName.trim())
    }
    
    /**
     * Checks if text contains potential security issues
     */
    private fun containsPotentialSecurityIssues(text: String): Boolean {
        // Check for potential script tags or other security issues
        val securityPatterns = listOf(
            Regex("<script", RegexOption.IGNORE_CASE),
            Regex("javascript:", RegexOption.IGNORE_CASE),
            Regex("vbscript:", RegexOption.IGNORE_CASE),
            Regex("onload=", RegexOption.IGNORE_CASE),
            Regex("onerror=", RegexOption.IGNORE_CASE)
        )
        
        return securityPatterns.any { it.containsMatchIn(text) }
    }
    
    sealed class ValidationResult {
        data class Valid(val value: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }
}