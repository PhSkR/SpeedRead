package com.speedread.rsvp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    /**
     * Get the app's documents folder 
     * Uses app-specific directory to avoid permission issues
     */
    fun getDocumentsFolder(): File {
        return try {
            // Use app-specific external directory (no permissions needed on Android 4.4+)
            val appExternalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val documentsDir = File(appExternalDir, Constants.DOCUMENTS_FOLDER_NAME)
            
            // Ensure directory exists
            if (!documentsDir.exists()) {
                documentsDir.mkdirs()
            }
            
            documentsDir

        } catch (e: Exception) {
            Logger.w("DocumentsManager", "External documents dir unavailable; falling back to internal storage", e)
            val fallbackDir = File(context.filesDir, Constants.DOCUMENTS_FOLDER_NAME)
            fallbackDir.mkdirs()
            fallbackDir
        }
    }
    
    /**
     * Scan the documents folder for supported files
     * This method is designed to never throw exceptions
     */
    suspend fun scanDocumentsFolder(): List<FolderFile> = withContext(Dispatchers.IO) {
        try {
            val documentsFolder = getDocumentsFolder()
            
            // Double-check folder exists and is readable
            if (!documentsFolder.exists()) {
                try {
                    documentsFolder.mkdirs()
                } catch (e: Exception) {
                    Logger.e("DocumentsManager", "Failed to create documents folder ${documentsFolder.absolutePath}", e)
                    return@withContext emptyList()
                }
            }

            if (!documentsFolder.canRead()) {
                return@withContext emptyList()
            }

            val files = try {
                documentsFolder.listFiles()
            } catch (e: Exception) {
                Logger.e("DocumentsManager", "Failed to list documents folder", e)
                return@withContext emptyList()
            }

            files
                ?.filter { file ->
                    try {
                        file.isFile &&
                        file.canRead() &&
                        file.extension.lowercase() in Constants.SUPPORTED_EXTENSIONS
                    } catch (e: Exception) {
                        Logger.w("DocumentsManager", "Filter failed for ${file?.name}", e)
                        false
                    }
                }
                ?.mapNotNull { file ->
                    try {
                        FolderFile(
                            file = file,
                            name = file.name,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            extension = file.extension
                        )
                    } catch (e: Exception) {
                        Logger.w("DocumentsManager", "Skipping unreadable file ${file?.name}", e)
                        null
                    }
                }
                ?.sortedByDescending { it.lastModified } // Sort by newest first
                ?: emptyList()
        } catch (e: Exception) {
            Logger.e("DocumentsManager", "scanDocumentsFolder failed", e)
            emptyList()
        }
    }
    
    /**
     * Open the documents folder in the system file manager
     * Uses modern Android approaches that work with scoped storage
     */
    fun openDocumentsFolder(): Intent? {
        return try {
            // Try to open file picker pointing to Documents directory
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                // Try to hint at Documents directory
                putExtra("android.content.extra.SHOW_ADVANCED", true)
            }
            
            // Try to create a chooser with helpful text
            Intent.createChooser(intent, "Select files from SpeedReadDocuments folder")
            
        } catch (e: Exception) {
            Logger.w("DocumentsManager", "ACTION_GET_CONTENT chooser failed; trying ACTION_OPEN_DOCUMENT", e)
            try {
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                }
            } catch (e2: Exception) {
                Logger.e("DocumentsManager", "ACTION_OPEN_DOCUMENT also failed; using basic picker", e2)
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            }
        }
    }
    
    /**
     * Get URI for a file to import it
     */
    fun getFileUri(file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Logger.e("DocumentsManager", "getUriForFile failed for ${file.name}", e)
            null
        }
    }
    
    /**
     * Check if external storage is available for reading
     */
    fun isExternalStorageReadable(): Boolean {
        return Environment.getExternalStorageState() in setOf(
            Environment.MEDIA_MOUNTED,
            Environment.MEDIA_MOUNTED_READ_ONLY
        )
    }
    
    /**
     * Create a guide file in the documents folder to help users
     */
    suspend fun createGuideFile() = withContext(Dispatchers.IO) {
        try {
            val documentsFolder = getDocumentsFolder()
            val guideFile = File(documentsFolder, "README.txt")
            
            if (!guideFile.exists()) {
                val guideContent = """
                    SpeedRead Documents Folder
                    ========================
                    
                    This is your SpeedRead documents folder (app-specific storage).
                    
                    How to add files:
                    1. Use SpeedRead's "Import from File" button in the Reading tab
                    2. Use the "Import Files" button in the Library tab  
                    3. Share files to SpeedRead from other apps
                    4. Copy files to this folder using a file manager (advanced users)
                    
                    Supported formats:
                    • PDF files (.pdf)
                    • EPUB books (.epub) 
                    • Text files (.txt)
                    
                    Files in this folder will appear in the SpeedRead app's Library tab.
                    
                    Location: ${documentsFolder.absolutePath}
                    
                    Note: This folder is specific to SpeedRead and may not be visible 
                    in all file managers. Use the Import buttons for easiest access.
                    
                    Happy speed reading!
                """.trimIndent()
                
                guideFile.writeText(guideContent)
            }
        } catch (e: Exception) {
            Logger.w("DocumentsManager", "Failed to write documents guide file", e)
        }
    }
}