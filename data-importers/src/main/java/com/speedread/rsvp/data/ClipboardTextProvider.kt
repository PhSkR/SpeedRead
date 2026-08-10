package com.speedread.rsvp.data

import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardTextProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : TextProvider {
    
    override suspend fun getText(source: TextSource): Result<String> {
        return withContext(Dispatchers.Main) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip
                
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank()) {
                        Result.success(text)
                    } else {
                        Result.failure(Exception("Clipboard is empty"))
                    }
                } else {
                    Result.failure(Exception("No clipboard data available"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override fun getSupportedSources(): List<TextSource> {
        return listOf(TextSource.Clipboard)
    }
}