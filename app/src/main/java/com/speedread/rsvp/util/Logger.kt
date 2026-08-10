package com.speedread.rsvp.util

import android.util.Log

/**
 * Centralized logging utility for the SpeedRead app
 */
object Logger {
    
    private const val DEFAULT_TAG = "SpeedRead"
    
    fun d(tag: String = DEFAULT_TAG, message: String) {
        Log.d(tag, message)
    }
    
    fun i(tag: String = DEFAULT_TAG, message: String) {
        Log.i(tag, message)
    }
    
    fun w(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }
    
    fun e(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
    
    fun wtf(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        Log.wtf(tag, message, throwable)
    }
}