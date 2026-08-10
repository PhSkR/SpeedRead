package com.speedread.rsvp.engine

import kotlinx.coroutines.flow.Flow

interface RsvpEngine {
    suspend fun loadText(text: String)
    suspend fun loadPreTokenized(singleWordTokens: List<RsvpWord>)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekToPosition(position: Int)
    suspend fun loadTextWithProgress(text: String, onProgress: (Float) -> Unit)
    
    fun observeCurrentWord(): Flow<RsvpWord?>
    fun observeProgress(): Flow<Float>
    fun observeState(): Flow<RsvpState>
    
    fun updateSettings(settings: RsvpSettings)
    fun getWords(): List<RsvpWord>
    fun getSingleWordTokens(): List<RsvpWord>
    fun getTotalWords(): Int
    fun getCurrentPosition(): Int
    fun wasLastLoadTruncated(): Boolean
}