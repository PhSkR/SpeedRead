package com.speedread.rsvp.util

import org.junit.Test
import org.junit.Assert.*

class LoggerTest {

    @Test
    fun `logger methods should not crash when called`() {
        // This test checks that the logger methods don't throw exceptions
        Logger.d("TestTag", "Debug message")
        Logger.i("TestTag", "Info message")
        Logger.w("TestTag", "Warning message")
        Logger.e("TestTag", "Error message")
        Logger.wtf("TestTag", "What a Terrible Failure message")
        
        // If we reach here, all methods executed without throwing exceptions
        assertTrue(true) // Simple assertion to satisfy JUnit
    }

    @Test
    fun `logger methods with throwable should not crash when called`() {
        val testException = Exception("Test exception")
        
        // This test checks that the logger methods with throwable don't throw exceptions
        Logger.e("TestTag", "Error message with exception", testException)
        Logger.wtf("TestTag", "WTF message with exception", testException)
        
        // If we reach here, all methods executed without throwing exceptions
        assertTrue(true) // Simple assertion to satisfy JUnit
    }
}