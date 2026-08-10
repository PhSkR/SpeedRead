package com.speedread.rsvp

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedread.rsvp.R
import com.speedread.rsvp.data.TextProvider
import com.speedread.rsvp.data.TextSource
import com.speedread.rsvp.data.bookmark.BookmarkSource
import com.speedread.rsvp.data.bookmark.BookmarkWithProgress
import com.speedread.rsvp.data.document.DocumentRepository
import com.speedread.rsvp.data.document.SavedDocument
import com.speedread.rsvp.data.document.getPageBoundaries
import com.speedread.rsvp.data.parser.FileMetadata
import com.speedread.rsvp.data.parser.FileParseResult
import com.speedread.rsvp.data.settings.SettingsRepository
import com.speedread.rsvp.data.settings.TtsSettingsRepository
import com.speedread.rsvp.engine.EngineConstants
import com.speedread.rsvp.engine.RsvpSettings
import com.speedread.rsvp.engine.RsvpState
import com.speedread.rsvp.engine.RsvpWord
import com.speedread.rsvp.engine.TrailingBreak
import com.speedread.rsvp.tts.PlaybackCoordinator
import com.speedread.rsvp.tts.PlaybackMode
import com.speedread.rsvp.tts.TtsSettings
import com.speedread.rsvp.ui.UiState
import com.speedread.rsvp.util.DataValidator
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import com.speedread.rsvp.data.document.DocumentJson
import com.speedread.rsvp.data.document.toDocument
import com.speedread.rsvp.data.document.toImporter
import com.speedread.rsvp.data.parser.FigureRegion as ImporterFigureRegion
import com.speedread.rsvp.data.parser.PageBoundary as ImporterPageBoundary

data class PageInfo(
    val currentPage: Int,
    val totalPages: Int,
    val currentWord: Int,
    val totalWords: Int
)

data class PdfImportChoice(
    val uri: Uri,
    val originalFileName: String,
    val metadata: FileMetadata
)

sealed class DocumentLoadStatus {
    object Ok : DocumentLoadStatus()
    data class Truncated(val loadedWords: Int, val limit: Int) : DocumentLoadStatus()
}

@HiltViewModel
class ReadingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackCoordinator: PlaybackCoordinator,
    private val textProvider: TextProvider,
    private val fileTextProvider: com.speedread.rsvp.data.FileTextProvider,
    private val documentRepository: DocumentRepository,
    private val settingsRepository: SettingsRepository,
    private val ttsSettingsRepository: TtsSettingsRepository,
    private val tokenCacheStore: PageViewTokenCacheStore
) : ViewModel() {

    // Settings are owned by SettingsRepository (single writer). These flows simply
    // re-expose the repo's StateFlow so Fragments can bind to VM fields as before.
    val settings: StateFlow<RsvpSettings> = settingsRepository.settings
    val wpm: StateFlow<Int> = settingsRepository.settings
        .map { it.wpm }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.settings.value.wpm)

    private val _fileImportResult = MutableStateFlow<UiState<FileMetadata>?>(null)
    val fileImportResult = _fileImportResult.asStateFlow()

    private val _pdfImportChoice = MutableStateFlow<PdfImportChoice?>(null)
    val pdfImportChoice = _pdfImportChoice.asStateFlow()

    private val _currentDocumentTitle = MutableStateFlow<String?>(null)
    val currentDocumentTitle = _currentDocumentTitle.asStateFlow()

    // Current text and metadata for bookmarking and saving
    private var currentText: String = ""
    private var currentTextSizeBytes: Int = 0 // Store text size for UI display
    private var currentTextSource: BookmarkSource = BookmarkSource.MANUAL_TEXT
    private var currentSourceUri: String? = null
    private var currentTextTitle: String = ""
    private var currentTextAuthor: String? = null
    private var currentMimeType: String? = null
    private var currentFileName: String? = null
    private var currentDocumentId: Long? = null

    fun getCurrentDocumentId(): Long? = currentDocumentId
    private var totalWordCount: Int = 0 // Total words in current text
    private var currentPageBoundaries: List<ImporterPageBoundary>? = null // Page boundaries for PDFs
    private var currentPageCount: Int? = null // Total page count for PDFs
    private var currentFigureRegions: List<ImporterFigureRegion>? = null // Detected figures for PDFs

    // Page calculation constants
    companion object {
        const val WORDS_PER_PAGE = Constants.WORDS_PER_PAGE_ESTIMATION // Average words per page for estimation

        // Sentinel meaning "this ViewModel has not loaded anything into the coordinator yet".
        // Never matches a real coordinator generation (those start at 1).
        private const val NO_OWNED_LOAD_GENERATION = -1L
    }

    // Coordinator load generation captured after OUR most recent load. Position saves and
    // auto-bookmarks are gated on this still matching the coordinator's current generation:
    // PageViewActivity shares the same singleton coordinator and can load a DIFFERENT
    // document into it (Library -> Page View -> play), after which this ViewModel's
    // currentWord observations describe that other document — persisting them against
    // currentDocumentId would corrupt this document's saved position.
    private var ownedLoadGeneration: Long = NO_OWNED_LOAD_GENERATION

    private fun coordinatorHoldsOurDocument(): Boolean =
        ownedLoadGeneration != NO_OWNED_LOAD_GENERATION &&
            playbackCoordinator.getLoadGeneration() == ownedLoadGeneration

    // Bookmark-related state
    private val _bookmarkSaved = MutableStateFlow<String?>(null)
    val bookmarkSaved = _bookmarkSaved.asStateFlow()

    // Document-related state
    private val _documentSaved = MutableStateFlow<String?>(null)
    val documentSaved = _documentSaved.asStateFlow()

    // One-shot load-time diagnostics (e.g. document exceeded token cap and was truncated).
    private val _documentLoadStatus = MutableStateFlow<DocumentLoadStatus?>(null)
    val documentLoadStatus = _documentLoadStatus.asStateFlow()

    init {
        // Settings initialization + engine priming happens inside SettingsRepository's
        // constructor. Here we run one-shot startup cleanup for this user's library.
        viewModelScope.launch {
            try {
                documentRepository.cleanupAllDuplicateAutoBookmarks()
                documentRepository.cleanupOrphanedFiles()
                documentRepository.cleanupOldAutoBookmarks()
            } catch (e: Exception) {
                Logger.e("ReadingViewModel", "Startup cleanup failed", e)
            }
        }
    }

    val currentWord = playbackCoordinator.currentWord
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val progress = playbackCoordinator.progress
        .stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    val state = playbackCoordinator.state
        .stateIn(viewModelScope, SharingStarted.Lazily, RsvpState.Idle)

    /** Currently-active playback mode (RSVP vs TTS). UI observes to swap the speaker toggle icon. */
    val activeMode: StateFlow<PlaybackMode> = playbackCoordinator.activeMode

    /**
     * Live TTS settings — the Reading screen's inline ±speed selector binds the speech-rate
     * value (and the enable/disable state of the buttons at min/max) to this. Wraps the
     * existing repository flow rather than introducing a new source of truth, so changes
     * made here and in Options stay in sync without a second writer.
     */
    val ttsSettings: StateFlow<TtsSettings> = ttsSettingsRepository.settings

    /** Transient TTS notices (e.g. "Neural backend unavailable — dropped voice folder needed"). */
    val ttsNotices: SharedFlow<String> = playbackCoordinator.notices

    init {
        // Periodic word-position auto-save during playback. The existing pause()-driven save
        // covers manual pauses but loses the position when the user closes the app while
        // playback is still active — most reliably during TTS, which keeps running in the
        // foreground service when the app is backgrounded and can be silenced by a process
        // kill (swipe from recents) without ever hitting pause(). Sampling currentWord at
        // [Constants.AUTO_SAVE_POSITION_INTERVAL_MS] keeps DB writes bounded (≈1 every few
        // seconds, regardless of WPM/speech rate) while ensuring the saved position is at
        // most one interval stale on close. distinctUntilChanged guards against re-saving
        // the same word during quiet periods (paused engine still emits the last word).
        viewModelScope.launch {
            playbackCoordinator.currentWord
                .sample(Constants.AUTO_SAVE_POSITION_INTERVAL_MS)
                .distinctUntilChanged { a, b -> a?.absoluteStartIndex == b?.absoluteStartIndex }
                .collect { word ->
                    if (word != null && currentDocumentId != null && totalWordCount > 0 &&
                        coordinatorHoldsOurDocument()
                    ) {
                        try {
                            saveCurrentReadingPosition(word)
                        } catch (e: Exception) {
                            Logger.w("ReadingViewModel", "Auto-save position failed: ${e.message}")
                        }
                    }
                }
        }
    }

    val pageInfo = currentWord
        .map {
            if (it != null && totalWordCount > 0) {
                // Use actual PDF pages if available, otherwise fall back to word-count estimation
                val boundaries = currentPageBoundaries
                if (boundaries != null && currentPageCount != null) {
                    // Find which PDF page contains the current word
                    val currentPage = boundaries.find { boundary: ImporterPageBoundary ->
                        it.absoluteStartIndex >= boundary.startWordIndex && it.absoluteStartIndex <= boundary.endWordIndex
                    }?.pageNumber ?: 1

                    PageInfo(currentPage, currentPageCount!!, it.absoluteStartIndex + 1, playbackCoordinator.getAbsoluteWordCount())
                } else {
                    // Fall back to word-count estimation
                    val absWordCount = playbackCoordinator.getAbsoluteWordCount()
                    val currentPage = (it.absoluteStartIndex / WORDS_PER_PAGE) + 1
                    val totalPages = ((absWordCount - 1) / WORDS_PER_PAGE) + 1
                    PageInfo(currentPage, totalPages, it.absoluteStartIndex + 1, absWordCount)
                }
            } else {
                PageInfo(0, 0, 0, 0)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, PageInfo(0, 0, 0, 0))

    fun loadText(text: String, source: BookmarkSource = BookmarkSource.MANUAL_TEXT, sourceUri: String? = null) {
        viewModelScope.launch {
            // Validate and sanitize text
            val validationResult = DataValidator.validateTextForProcessing(text)
            val sanitizedText = when (validationResult) {
                is DataValidator.ValidationResult.Valid -> {
                    DataValidator.sanitizeText(validationResult.value)
                }
                is DataValidator.ValidationResult.Invalid -> {
                    Logger.e("ReadingViewModel", "Invalid text provided: ${validationResult.reason}")
                    return@launch
                }
            }

            // Clear document ID when loading new text (not from saved document)
            if (source != currentTextSource || sanitizedText != currentText) {
                currentDocumentId = null
            }

            // All document-identity fields must be assigned together BEFORE the first
            // suspension point: the import flow queues saveCurrentDocument right after
            // loadText, and a suspension between these assignments let the save observe
            // fresh currentText with a STALE currentTextSource from the previously loaded
            // document (an EPUB imported after a PDF was persisted as source=FILE_PDF).
            currentText = sanitizedText
            currentTextSource = source
            currentSourceUri = sourceUri
            // UTF-8 byte size on a multi-hundred-KB string is non-trivial; push to
            // Default so the import coroutine doesn't stall the main thread.
            currentTextSizeBytes = withContext(Dispatchers.Default) {
                sanitizedText.toByteArray(Charsets.UTF_8).size
            }

            // Update title based on source
            if (source == BookmarkSource.MANUAL_TEXT || source == BookmarkSource.CLIPBOARD) {
                updateDocumentTitle(null, null) // Clear title for manual/clipboard text
                currentPageBoundaries = null
                currentPageCount = null
                currentFigureRegions = null
            }

            // For large documents, use the progress-based loading
            if (sanitizedText.length > Constants.LARGE_DOCUMENT_THRESHOLD) { // Large document threshold
                playbackCoordinator.loadTextWithProgress(sanitizedText) { progress ->
                    // Update progress flow for large documents
                    _fileImportResult.value = UiState.Loading // Use UiState.Loading for processing
                }
                // Clear the processing result after loading
                _fileImportResult.value = null
            } else {
                playbackCoordinator.loadText(sanitizedText)
            }
            ownedLoadGeneration = playbackCoordinator.getLoadGeneration()

            // Engine has the authoritative count after tokenization.
            totalWordCount = playbackCoordinator.getTotalWords()
            _documentLoadStatus.value = if (playbackCoordinator.wasLastLoadTruncated()) {
                DocumentLoadStatus.Truncated(totalWordCount, EngineConstants.MAX_TOKENS)
            } else {
                DocumentLoadStatus.Ok
            }

            // Rehash any pre-1.13.18 MD5-keyed bookmarks to SHA-256 before the lookup,
            // otherwise legacy bookmarks for this document would look missing.
            documentRepository.migrateLegacyBookmarkHashes(sanitizedText)

            // Check if there's an existing bookmark for this text
            val existingBookmark = documentRepository.getBookmarkForText(sanitizedText)
            existingBookmark?.let { bookmark ->
                // Jump to bookmarked position if found
                playbackCoordinator.seekToAbsoluteWordPosition(bookmark.bookmark.wordPosition)
            }

            syncPageBoundariesToCoordinator()
        }
    }

    fun clearDocumentLoadStatus() {
        _documentLoadStatus.value = null
    }

    fun play() {
        if (currentText.isNotBlank() && totalWordCount > 0) {
            viewModelScope.launch {
                playbackCoordinator.play()
            }
        }
    }

    fun pause() {
        viewModelScope.launch {
            playbackCoordinator.pause()
            // After pause() returns, the engine has joined its playback job, so
            // currentWord is stable. Snapshot once so the auto-bookmark and the
            // position-save agree on the same word.
            val snapshot = currentWord.value
            createAutoBookmark(snapshot)
            saveCurrentReadingPosition(snapshot)
        }
    }

    /**
     * Flip between RSVP (visual word-flash) and TTS (read-aloud) playback. The coordinator
     * performs a seamless handoff: position is preserved, and if the previous mode was
     * playing, the new mode resumes automatically. Safe to invoke at any time.
     */
    fun togglePlaybackMode() {
        viewModelScope.launch {
            playbackCoordinator.toggleMode()
        }
    }

    fun restartParagraph() {
        viewModelScope.launch {
            try {
                val currentWordValue = currentWord.value
                val currentPosition = currentWordValue?.position ?: 0
                val paragraphStart = findParagraphStart(currentPosition)
                playbackCoordinator.seekToPosition(paragraphStart)
            } catch (e: Exception) {
                // If paragraph detection fails, just restart from current position
                val currentWordValue = currentWord.value
                playbackCoordinator.seekToPosition(currentWordValue?.position ?: 0)
            }
        }
    }

    private fun findParagraphStart(currentPosition: Int): Int {
        if (currentPosition <= 0) return 0

        try {
            val words = playbackCoordinator.getWords()
            if (words.isEmpty() || currentPosition >= words.size) return currentPosition

            var firstLineStart: Int? = null

            for (i in currentPosition - 1 downTo 0) {
                when (words[i].trailingBreak) {
                    TrailingBreak.PARAGRAPH -> return i + 1
                    TrailingBreak.LINE -> if (firstLineStart == null) firstLineStart = i + 1
                    null -> Unit
                }
            }

            return firstLineStart ?: 0
        } catch (e: Exception) {
            Logger.e("ReadingViewModel", "findParagraphStart failed (position=$currentPosition)", e)
            return currentPosition
        }
    }

    fun getSuggestedDocumentTitle(): String {
        return when {
            // Check if we have a filename (should now be the original filename)
            currentFileName?.isNotBlank() == true -> {
                // If it's already a clean filename (from our new method), use it as-is
                if (currentFileName == "Imported Document") {
                    "Saved Document"
                } else {
                    // Clean up the original filename for better readability
                    currentFileName!!
                        .substringBeforeLast('.') // Remove extension
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .replace(Regex("\\s+"), " ") // Replace multiple spaces with single space
                        .trim()                   // Remove leading/trailing whitespace
                        .let {
                            // Apply title case to make it look nice
                            it.split(" ")
                                .joinToString(" ") { word -> 
                                    if (word.isNotEmpty() && word.all { it.isLetter() || it.isDigit() }) {
                                        // Only capitalize words that are purely alphanumeric
                                        word.lowercase().replaceFirstChar { it.uppercase() }
                                    } else {
                                        word // Keep mixed case/special characters as-is
                                    }
                                }
                        }
                }
            }
            // Fall back to document title from metadata if meaningful
            currentTextTitle.isNotBlank() && currentTextTitle != "Imported Document" -> currentTextTitle
            // Final fallback
            else -> "Saved Document"
        }
    }

    fun getCurrentTextSizeBytes(): Int {
        return currentTextSizeBytes
    }

    private fun updateDocumentTitle(title: String?, fileName: String?) {
        currentTextTitle = title ?: ""
        // Update the StateFlow with a user-friendly display title
        val displayTitle = when {
            !title.isNullOrBlank() && title != "Imported Document" -> title
            !fileName.isNullOrBlank() -> {
                // Remove file extension and clean up filename
                fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
            }
            else -> null
        }
        _currentDocumentTitle.value = displayTitle
        // Push the same title into the coordinator so the background TTS service's notification /
        // lock-screen metadata can label the current document. Kept in sync here because this is
        // the single funnel through which every title source (file import, URL import, saved doc
        // load, manual text clear) writes the title.
        playbackCoordinator.setCurrentTitle(displayTitle)
    }

    fun seekToPosition(position: Int) {
        viewModelScope.launch {
            playbackCoordinator.seekToPosition(position)
        }
    }

    fun seekToAbsoluteWordPosition(absoluteWordIndex: Int) {
        viewModelScope.launch {
            playbackCoordinator.seekToAbsoluteWordPosition(absoluteWordIndex)
        }
    }

    fun seekToPercentage(percentage: Float) {
        try {
            if (currentText.isNotBlank() && totalWordCount > 0 && percentage.isFinite() && percentage >= 0f && percentage <= 100f) {
                val targetPosition = (totalWordCount * (percentage / 100f)).toInt().coerceIn(0, totalWordCount - 1)
                seekToPosition(targetPosition)
            }
        } catch (e: Exception) {
            Logger.e("ReadingViewModel", "seekToPercentage failed (percentage=$percentage)", e)
        }
    }

    fun seekToPage(page: Int) {
        try {
            if (totalWordCount > 0 && page > 0) {
                val boundaries = currentPageBoundaries
                if (boundaries != null && currentPageCount != null) {
                    // Use actual PDF page boundaries
                    val targetBoundary = boundaries.find { it.pageNumber == page }
                    if (targetBoundary != null) {
                        viewModelScope.launch {
                            playbackCoordinator.seekToAbsoluteWordPosition(targetBoundary.startWordIndex)
                        }
                    }
                } else {
                    // Fall back to word-count estimation
                    val absWordCount = playbackCoordinator.getAbsoluteWordCount()
                    val totalPages = ((absWordCount - 1) / WORDS_PER_PAGE) + 1
                    if (page <= totalPages) {
                        val targetPosition = ((page - 1) * WORDS_PER_PAGE).coerceIn(0, absWordCount - 1)
                        viewModelScope.launch {
                            playbackCoordinator.seekToAbsoluteWordPosition(targetPosition)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("ReadingViewModel", "seekToPage failed (page=$page)", e)
        }
    }

    fun importFromClipboard() {
        viewModelScope.launch {
            textProvider.getText(TextSource.Clipboard)
                .onSuccess { loadText(it, BookmarkSource.CLIPBOARD) }
                .onFailure { e ->
                    Logger.e("ReadingViewModel", "Clipboard import failed", e)
                }
        }
    }

    fun importFromFileWithOriginalName(uri: Uri, originalFileName: String) {
        viewModelScope.launch {
            _fileImportResult.value = UiState.Loading

            when (val result = fileTextProvider.parseFileWithMetadata(uri)) {
                is FileParseResult.Success -> {
                    // First, check if this is a PDF to potentially show choice dialog
                    if (result.metadata.mimeType == "application/pdf") {
                        // Show PDF import choice dialog
                        _pdfImportChoice.value = PdfImportChoice(uri, originalFileName, result.metadata)
                        return@launch
                    }

                    // Continue with normal processing for non-PDFs
                    // Check if the imported text is too large for database storage
                    val textSizeBytes = result.text.toByteArray(Charsets.UTF_8).size
                    val maxSizeBytes = Constants.MAX_DOCUMENT_SIZE_BYTES // Max document size in bytes

                    // Show file size and warn if close to or over the limit
                    if (textSizeBytes > maxSizeBytes) { 
                        val sizeMB = textSizeBytes / 1_048_576.0
                        _fileImportResult.value = UiState.Error(
                            "Document too large to save (${String.format(Locale.US, "%.1f", sizeMB)}MB). You can read it but cannot save to library. Maximum size: 100MB."
                        )
                        // Still load the text for reading with original filename
                        loadTextAfterImportWithOriginalName(result, uri, originalFileName)
                        return@launch
                    }

                    loadTextAfterImportWithOriginalName(result, uri, originalFileName)
                    _fileImportResult.value = UiState.Success(result.metadata)

                    // Automatically save the imported document to library
                    saveCurrentDocument(originalFileName)
                }
                is FileParseResult.Error -> {
                    _fileImportResult.value = UiState.Error(result.message)
                }
            }
        }
    }

    /**
     * Fetch an http(s) URL, extract readable article text (via Readability4J for HTML,
     * delegated to PdfFileParser for application/pdf responses, body-as-text for text/plain),
     * and feed it into the same pipeline as file imports. Auto-saves to the library using
     * the extracted page title (or the URL itself) as the document name.
     */
    fun importFromUrl(url: String) {
        viewModelScope.launch {
            val trimmed = url.trim()
            if (trimmed.isBlank()) {
                _fileImportResult.value = UiState.Error("Please enter a URL.")
                return@launch
            }
            // Prepend https:// if the user omitted a scheme entirely. Anything that looks
            // like scheme://... goes through as-is so the non-http(s) reject can fire.
            val normalized = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val scheme = Uri.parse(normalized).scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                _fileImportResult.value = UiState.Error(context.getString(R.string.url_invalid_scheme))
                return@launch
            }

            Logger.d("ReadingViewModel", "Starting URL import: $normalized")
            _fileImportResult.value = UiState.Loading

            try {
                when (val result = fileTextProvider.parseUrl(normalized)) {
                    is FileParseResult.Success -> {
                        val textSizeBytes = result.text.toByteArray(Charsets.UTF_8).size
                        val maxSizeBytes = Constants.MAX_DOCUMENT_SIZE_BYTES
                        val documentTitle = result.metadata.title?.takeIf { it.isNotBlank() } ?: normalized

                        if (textSizeBytes > maxSizeBytes) {
                            val sizeMB = textSizeBytes / 1_048_576.0
                            val errorMsg = "Document too large to save (${String.format(Locale.US, "%.1f", sizeMB)}MB). You can read it but cannot save to library. Maximum size: 100MB."
                            Logger.w("ReadingViewModel", errorMsg)
                            _fileImportResult.value = UiState.Error(errorMsg)
                            loadTextAfterImportWithOriginalName(result, Uri.parse(normalized), documentTitle)
                            return@launch
                        }

                        loadTextAfterImportWithOriginalName(result, Uri.parse(normalized), documentTitle)
                        _fileImportResult.value = UiState.Success(result.metadata)
                        saveCurrentDocument(documentTitle)
                    }
                    is FileParseResult.Error -> {
                        _fileImportResult.value = UiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Logger.e("ReadingViewModel", "Unexpected error during URL import", e)
                _fileImportResult.value = UiState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    fun importFromFile(uri: Uri) {
        viewModelScope.launch {
            Logger.d("ReadingViewModel", "Starting import from URI: ${uri.toString()}")
            _fileImportResult.value = UiState.Loading

            try {
                // Try to get the original filename from the URI
                val originalFileName = getOriginalFileNameFromUri(uri)

                when (val result = fileTextProvider.parseFileWithMetadata(uri)) {
                    is FileParseResult.Success -> {
                        Logger.d("ReadingViewModel", "Successfully parsed file with ${result.text.length} characters")

                        // Check if the imported text is too large for database storage
                        val textSizeBytes = result.text.toByteArray(Charsets.UTF_8).size
                        val maxSizeBytes = Constants.MAX_DOCUMENT_SIZE_BYTES // Max document size in bytes

                        // Show file size and warn if close to or over the limit
                        if (textSizeBytes > maxSizeBytes) { 
                            val sizeMB = textSizeBytes / 1_048_576.0
                            val errorMsg = "Document too large to save (${String.format(Locale.US, "%.1f", sizeMB)}MB). You can read it but cannot save to library. Maximum size: 100MB."
                            Logger.w("ReadingViewModel", errorMsg)
                            _fileImportResult.value = UiState.Error(errorMsg)

                            // Still load the text for reading with original filename if available
                            if (originalFileName != null) {
                                loadTextAfterImportWithOriginalName(result, uri, originalFileName)
                            } else {
                                loadTextAfterImport(result, uri)
                            }
                            return@launch
                        }

                        // Use the method with original filename if we have it
                        if (originalFileName != null) {
                            loadTextAfterImportWithOriginalName(result, uri, originalFileName)
                        } else {
                            loadTextAfterImport(result, uri)
                        }
                        _fileImportResult.value = UiState.Success(result.metadata)

                        // Automatically save the imported document to library
                        val documentTitle = when {
                            !originalFileName.isNullOrBlank() -> originalFileName
                            !result.metadata.title.isNullOrBlank() && result.metadata.title != "Imported Document" -> result.metadata.title!!
                            !uri.lastPathSegment.isNullOrBlank() -> uri.lastPathSegment!!
                            else -> "Imported Document"
                        }
                        saveCurrentDocument(documentTitle)
                    }
                    is FileParseResult.Error -> {
                        _fileImportResult.value = UiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Logger.e("ReadingViewModel", "Unexpected error during file import", e)
                _fileImportResult.value = UiState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    private fun loadTextAfterImportWithOriginalName(result: FileParseResult.Success, uri: Uri, originalFileName: String) {
        val isWebUri = uri.scheme?.lowercase() in setOf("http", "https")
        val source = when {
            result.metadata.mimeType == "application/pdf" -> BookmarkSource.FILE_PDF
            result.metadata.mimeType == "application/epub+zip" -> BookmarkSource.FILE_EPUB
            result.metadata.mimeType == "text/html" -> BookmarkSource.URL
            // Plain text fetched from an http(s) URL keeps URL provenance; plain text
            // loaded from a local file stays FILE_TXT.
            isWebUri && result.metadata.mimeType == "text/plain" -> BookmarkSource.URL
            result.metadata.mimeType == "text/plain" -> BookmarkSource.FILE_TXT
            else -> BookmarkSource.MANUAL_TEXT
        }

        // Store metadata for document saving - prioritize original filename
        val finalFileName = when {
            // Use the original filename from the file system first
            originalFileName.isNotBlank() -> originalFileName
            // Fall back to metadata title if meaningful
            !result.metadata.title.isNullOrBlank() && result.metadata.title != "Imported Document" -> result.metadata.title
            // Last resort
            else -> "Imported Document"
        }

        updateDocumentTitle(finalFileName, finalFileName)
        currentTextAuthor = result.metadata.author
        currentMimeType = result.metadata.mimeType
        currentFileName = finalFileName

        // Store page boundary data for later use
        currentPageBoundaries = result.metadata.pageBoundaries
        currentPageCount = result.metadata.pageCount
        currentFigureRegions = result.metadata.figureRegions

        loadText(result.text, source, uri.toString())
    }

    private fun loadTextAfterImport(result: FileParseResult.Success, uri: Uri) {
        val isWebUri = uri.scheme?.lowercase() in setOf("http", "https")
        val source = when {
            result.metadata.mimeType == "application/pdf" -> BookmarkSource.FILE_PDF
            result.metadata.mimeType == "application/epub+zip" -> BookmarkSource.FILE_EPUB
            result.metadata.mimeType == "text/html" -> BookmarkSource.URL
            isWebUri && result.metadata.mimeType == "text/plain" -> BookmarkSource.URL
            result.metadata.mimeType == "text/plain" -> BookmarkSource.FILE_TXT
            else -> BookmarkSource.MANUAL_TEXT
        }

        // Store metadata for document saving
        // Try to get a meaningful filename from multiple sources
        val originalFileName = when {
            // First try metadata title if it's meaningful
            !result.metadata.title.isNullOrBlank() && result.metadata.title != "Imported Document" -> result.metadata.title
            // Then try URI last path segment if it looks like a real filename (not encoded)
            !uri.lastPathSegment.isNullOrBlank() && isValidFilename(uri.lastPathSegment!!) -> uri.lastPathSegment
            // Fall back to generic name
            else -> "Imported Document"
        }

        updateDocumentTitle(originalFileName, originalFileName)
        currentTextAuthor = result.metadata.author
        currentMimeType = result.metadata.mimeType
        currentFileName = originalFileName

        // Store page boundary data for later use
        currentPageBoundaries = result.metadata.pageBoundaries
        currentPageCount = result.metadata.pageCount
        currentFigureRegions = result.metadata.figureRegions

        loadText(result.text, source, uri.toString())
    }

    fun clearFileImportResult() {
        _fileImportResult.value = null
    }

    fun createManualBookmark(title: String) {
        if (currentText.isBlank()) return

        viewModelScope.launch {
            val currentWordValue = currentWord.value
            val currentPosition = currentWordValue?.absoluteStartIndex ?: 0
            val currentPageInfo = pageInfo.value
            val currentWpm = wpm.value

            documentRepository.createBookmark(
                title = title.ifBlank { "Reading Session" },
                fullText = currentText,
                wordPosition = currentPosition,
                pageNumber = currentPageInfo.currentPage,
                wpm = currentWpm,
                source = currentTextSource,
                sourceUri = currentSourceUri,
                isAutoBookmark = false
            )

            _bookmarkSaved.value = "Bookmark saved: Page ${currentPageInfo.currentPage}"
        }
    }

    private suspend fun createAutoBookmark(snapshot: RsvpWord? = currentWord.value) {
        if (currentText.isBlank()) return
        // The snapshot word came from the shared coordinator; if another screen has since
        // loaded a different document into it, the position belongs to THAT document and
        // must not be recorded against this ViewModel's text hash.
        if (!coordinatorHoldsOurDocument()) return

        val currentPosition = snapshot?.absoluteStartIndex ?: 0
        val currentPageInfo = pageInfo.value
        val currentWpm = wpm.value

        // Only create auto-bookmark if we've read some content
        if (currentPosition > Constants.AUTO_BOOKMARK_MIN_POSITION) {
            documentRepository.createAutoBookmarkWithCleanup(
                title = "Auto-saved (${currentTextSource.name})",
                fullText = currentText,
                wordPosition = currentPosition,
                pageNumber = currentPageInfo.currentPage,
                wpm = currentWpm,
                source = currentTextSource,
                sourceUri = currentSourceUri
            )
        }
    }

    fun jumpToBookmark(bookmark: BookmarkWithProgress) {
        viewModelScope.launch {
            // Check if the current text matches the bookmarked text
            val currentTextHash = if (currentText.isNotEmpty()) {
                documentRepository.generateTextHash(currentText)
            } else ""

            if (currentText.isEmpty() || currentTextHash != bookmark.bookmark.textHash) {
                // Locate the owning document by hashing each candidate's FULL content —
                // the list query returns 200-char metadata projections whose hash can never
                // match a bookmark hash for any document longer than 200 chars.
                val matchingDocument = documentRepository.findDocumentByTextHash(bookmark.bookmark.textHash)

                if (matchingDocument != null) {
                    // Awaits playbackCoordinator.loadText internally, so when this returns the
                    // engine has words loaded and seekToPosition can fire immediately.
                    loadSavedDocumentInternal(matchingDocument)
                } else {
                    _bookmarkSaved.value = "Cannot find the document for this bookmark"
                    return@launch
                }
            }

            if (playbackCoordinator.getTotalWords() == 0) {
                _bookmarkSaved.value = "Cannot jump to bookmark: document failed to load"
                return@launch
            }

            // Jump to the exact word position from the bookmark
            playbackCoordinator.seekToAbsoluteWordPosition(bookmark.bookmark.wordPosition)

            // Update access time
            documentRepository.markBookmarkAsAccessed(bookmark.bookmark.id)

            _bookmarkSaved.value = "Jumped to bookmark: ${bookmark.bookmark.title}"
        }
    }

    fun deleteBookmark(bookmark: BookmarkWithProgress) {
        viewModelScope.launch {
            documentRepository.deleteBookmark(bookmark.bookmark)
        }
    }

    fun deleteBookmarks(bookmarks: List<BookmarkWithProgress>) {
        viewModelScope.launch {
            documentRepository.deleteBookmarks(bookmarks)
        }
    }
    
    suspend fun getCurrentDocumentBookmarks(): List<BookmarkWithProgress> {
        return if (currentText.isNotBlank()) {
            documentRepository.getBookmarksForText(currentText).first()
        } else {
            emptyList()
        }
    }

    fun clearBookmarkSavedMessage() {
        _bookmarkSaved.value = null
    }
    
    fun clearDocumentSavedMessage() {
        _documentSaved.value = null
    }

    fun saveCurrentDocument(title: String) {
        if (currentText.isBlank()) {
            Logger.w("ReadingViewModel", "Attempted to save blank document")
            return
        }

        viewModelScope.launch {
            try {
                val titleValidation = DataValidator.validateDocumentTitle(title)
                val finalTitle = when (titleValidation) {
                    is DataValidator.ValidationResult.Valid -> titleValidation.value
                    is DataValidator.ValidationResult.Invalid -> {
                        Logger.e("ReadingViewModel", "Invalid document title: ${titleValidation.reason}")
                        _documentSaved.value = "Invalid document title: ${titleValidation.reason}"
                        return@launch
                    }
                }

                Logger.d("ReadingViewModel", "Saving document: $finalTitle (${currentText.length} chars)")

                // With file-based storage, we now support much larger documents
                val textSizeBytes = currentText.toByteArray(Charsets.UTF_8).size
                val maxSizeBytes = Constants.MAX_DOCUMENT_SIZE_BYTES // Max document size in bytes

                if (textSizeBytes > maxSizeBytes) { 
                    val sizeMB = textSizeBytes / 1_048_576.0
                    _documentSaved.value = "Document too large to save (${String.format(Locale.US, "%.1f", sizeMB)}MB). You can read it but cannot save to library. Maximum size: 100MB."
                    return@launch
                }

                // saveNewDocument dedupes by content hash internally and returns the existing
                // row's id when the document is already in the library, so it is safe to call
                // unconditionally; isDocumentSaved only selects the user-facing message.
                val isAlreadySaved = documentRepository.isDocumentSaved(currentText)

                // Convert data-importers PageBoundary to SavedDocument PageBoundary
                val documentPageBoundaries = currentPageBoundaries?.map { boundary: ImporterPageBoundary ->
                    com.speedread.rsvp.data.document.PageBoundary(
                        pageNumber = boundary.pageNumber,
                        startWordIndex = boundary.startWordIndex,
                        endWordIndex = boundary.endWordIndex,
                        wordCount = boundary.wordCount
                    )
                }

                val savedDocumentId = documentRepository.saveNewDocument(
                    title = finalTitle,
                    content = currentText,
                    source = currentTextSource,
                    originalFileName = currentFileName,
                    originalUri = currentSourceUri,
                    mimeType = currentMimeType,
                    author = currentTextAuthor,
                    pageCount = currentPageCount,
                    pageBoundaries = documentPageBoundaries,
                    figureRegions = currentFigureRegions?.map { it.toDocument() }
                )
                // Adopt the returned id unconditionally: saveDocument dedupes by content
                // hash, so the returned row is by definition the row holding currentText.
                // The previous null-only guard kept a STALE id when the user deleted a
                // document and re-imported the same file (same text, same source — loadText's
                // clear-on-change check doesn't fire), sending Page View a deleted row id
                // ("Document not found").
                currentDocumentId = savedDocumentId
                if (isAlreadySaved) {
                    Logger.d("ReadingViewModel", "Document already exists in library")
                    _documentSaved.value = "Document already in library"
                } else {
                    Logger.d("ReadingViewModel", "Successfully saved document: $finalTitle")
                    _documentSaved.value = "Document saved to library"
                }
            } catch (e: Exception) {
                Logger.e("ReadingViewModel", "Error saving document: $title", e)
                _documentSaved.value = "Error saving document: ${e.message}"
            }
        }
    }

    fun loadSavedDocument(document: SavedDocument) {
        viewModelScope.launch { loadSavedDocumentInternal(document) }
    }

    private suspend fun loadSavedDocumentInternal(documentRef: SavedDocument) {
        // Callers frequently hand us metadata-projection rows (Library list, search results)
        // whose content is truncated to 200 chars and whose pageBoundariesJson is NULL — see
        // SavedDocumentDao.SELECT_METADATA. Loading such a row directly would tokenize a
        // 30-word stub, destroy the saved position via the auto-saver, and poison the token
        // cache under the full document's contentHash. Always re-fetch the full row first.
        val document = documentRepository.getDocumentById(documentRef.id) ?: documentRef
        Logger.d("ReadingViewModel", "Loading saved document: ${document.title} (ID: ${document.id})")
        try {
            // Store document ID for position tracking
            currentDocumentId = document.id
            updateDocumentTitle(document.title, document.originalFileName)
            currentFileName = document.originalFileName

            // Restore page boundary data if available
            currentPageBoundaries = document.getPageBoundaries()?.map { boundary: com.speedread.rsvp.data.document.PageBoundary ->
                ImporterPageBoundary(
                    pageNumber = boundary.pageNumber,
                    startWordIndex = boundary.startWordIndex,
                    endWordIndex = boundary.endWordIndex,
                    wordCount = boundary.wordCount
                )
            }
            currentPageCount = document.pageCount
            currentFigureRegions =
                DocumentJson.decodeFigureRegions(document.figureRegionsJson)?.map { it.toImporter() }

            // Load the text content (from database or external file)
            val content = try {
                if (document.isContentExternal) {
                    Logger.d("ReadingViewModel", "Loading external document content")
                    documentRepository.loadDocumentContent(document)
                } else {
                    Logger.d("ReadingViewModel", "Loading internal document content (${document.content.length} chars)")
                    document.content
                }
            } catch (e: Exception) {
                Logger.e("ReadingViewModel", "Error loading document content", e)
                _documentSaved.value = "Error loading document: ${e.message}"
                currentDocumentId = null
                return
            }

            currentText = content
            // SavedDocument.fileSize was computed on Dispatchers.IO at save time.
            // Fall back to a UTF-8 re-count only if the stored value is missing
            // (legacy rows) — and do that on Default so it doesn't stall main.
            currentTextSizeBytes = if (document.fileSize > 0) {
                document.fileSize.toInt()
            } else {
                withContext(Dispatchers.Default) { content.toByteArray(Charsets.UTF_8).size }
            }
            currentTextSource = document.source
            currentSourceUri = null

            val contentHash = document.contentHash
            val singleWordTokens = try {
                tokenCacheStore.load(contentHash)?.mapIndexed { i, ct ->
                    RsvpWord(
                        text = ct.t,
                        position = i,
                        trailingBreak = ct.b?.let { TrailingBreak.valueOf(it) }
                    )
                }
            } catch (_: IllegalArgumentException) {
                tokenCacheStore.invalidate(contentHash)
                null
            }
            if (singleWordTokens != null) {
                playbackCoordinator.loadPreTokenized(singleWordTokens)
            } else {
                playbackCoordinator.loadText(content)
                val freshTokens = playbackCoordinator.getSingleWordTokens()
                val cached = freshTokens.map { CachedToken(t = it.text, b = it.trailingBreak?.name) }
                tokenCacheStore.save(contentHash, cached)
            }
            ownedLoadGeneration = playbackCoordinator.getLoadGeneration()

            // Rehash any pre-1.13.18 MD5-keyed bookmarks to SHA-256 so downstream
            // lookups and the bookmarks-for-this-document Flow see them.
            documentRepository.migrateLegacyBookmarkHashes(content)

            // Engine is now the source of truth for word count and truncation state.
            totalWordCount = playbackCoordinator.getTotalWords()
            _documentLoadStatus.value = if (playbackCoordinator.wasLastLoadTruncated()) {
                DocumentLoadStatus.Truncated(totalWordCount, EngineConstants.MAX_TOKENS)
            } else {
                DocumentLoadStatus.Ok
            }
            Logger.d("ReadingViewModel", "Loaded document with ${totalWordCount} words")

            // Restore reading position if available and valid. No delay needed:
            // playbackCoordinator.loadText above already suspended until words were populated.
            val absWordCount = playbackCoordinator.getAbsoluteWordCount()
            if (document.lastReadPosition > 0 && document.lastReadPosition < absWordCount) {
                Logger.d("ReadingViewModel", "Restoring to position: ${document.lastReadPosition}")
                playbackCoordinator.seekToAbsoluteWordPosition(document.lastReadPosition)
            } else {
                // No usable DB position — fall back to the newest bookmark for this text
                // (covers sessions where the position was only captured as an auto-bookmark,
                // e.g. reading during the import session of a not-yet-tracked document).
                val fallbackBookmark = documentRepository.getBookmarkForText(content)
                val fallbackPosition = fallbackBookmark?.bookmark?.wordPosition ?: 0
                if (fallbackPosition > 0 && fallbackPosition < absWordCount) {
                    Logger.d("ReadingViewModel", "Restoring to bookmark position: $fallbackPosition")
                    playbackCoordinator.seekToAbsoluteWordPosition(fallbackPosition)
                }
            }

            // Mark as accessed
            documentRepository.markDocumentAsAccessed(document.id)
            Logger.d("ReadingViewModel", "Successfully loaded document: ${document.title}")

            syncPageBoundariesToCoordinator()

        } catch (e: Exception) {
            Logger.e("ReadingViewModel", "Error loading saved document: ${document.title}", e)
            currentDocumentId = null
            _documentSaved.value = when (e) {
                is com.speedread.rsvp.data.document.DocumentStorageException -> "File access error: ${e.message}"
                else -> "Error loading document: ${e.message}"
            }
        }
    }

    suspend fun loadMostRecentDocument(): Boolean {
        // Idempotent: if a document is already loaded or a load is in flight
        // (currentDocumentId is set synchronously at the top of loadSavedDocumentInternal,
        // before the suspend points that read content from disk), skip. This prevents the
        // ReadingFragment's onViewCreated autoload from overwriting a document the user
        // just selected in the Library tab — fragments are recreated on every bottom-nav
        // switch, so without this guard the autoload races with (and often beats) the
        // library's in-flight loadSavedDocument.
        if (currentDocumentId != null || currentText.isNotEmpty()) {
            return false
        }
        return try {
            val recentDocument = documentRepository.getMostRecentDocument()
            recentDocument?.let { document ->
                // Re-check after the suspend: a library load may have started while we
                // were waiting on the DB query.
                if (currentDocumentId != null || currentText.isNotEmpty()) return false
                // Validate document before loading. Externally-stored documents keep
                // content = "" in the row (text lives in a file) and are validated by
                // loadSavedDocumentInternal's content load instead.
                val hasLoadableContent = document.content.isNotBlank() || document.isContentExternal
                if (hasLoadableContent && document.content.length < Constants.MAX_AUTO_RESUME_CONTENT_CHARS) {
                    loadSavedDocumentInternal(document)
                    true
                } else false
            } ?: false
        } catch (e: Exception) {
            Logger.e("ReadingViewModel", "loadMostRecentDocument failed", e)
            false
        }
    }
    
    fun updateWpm(newWpm: Int) = settingsRepository.updateWpm(newWpm)

    fun updateChunkSize(chunkSize: Int) = settingsRepository.updateChunkSize(chunkSize)

    fun updateOrpSettings(enableOrp: Boolean, centerOrp: Boolean) =
        settingsRepository.updateOrpSettings(enableOrp, centerOrp)

    fun updatePunctuationTiming(settingIndex: Int, value: Int) =
        settingsRepository.updatePunctuationTiming(settingIndex, value)

    fun updateTextColors(textColor: Int, backgroundColor: Int) =
        settingsRepository.updateTextColors(textColor, backgroundColor)

    fun updatePunctuationPausing(enabled: Boolean) =
        settingsRepository.updatePunctuationPausing(enabled)

    /**
     * Adjust the TTS narration speed. Caller passes the desired absolute rate (not a delta)
     * so the increment math lives in the UI layer alongside [Constants.TTS_RATE_STEP] and the
     * disable-at-limits logic. The repository / manager already clamp to
     * `[TTS_MIN_RATE, TTS_MAX_RATE]`, so out-of-range values are corrected silently.
     */
    fun updateTtsSpeechRate(rate: Float) = ttsSettingsRepository.updateSpeechRate(rate)

    fun resetSettingsToDefaults() = settingsRepository.resetToDefaults()

    suspend fun saveCurrentReadingPosition(snapshot: RsvpWord? = currentWord.value) {
        val documentId = currentDocumentId ?: return
        val currentWordValue = snapshot ?: return
        if (!coordinatorHoldsOurDocument()) return

        if (totalWordCount > 0 && currentWordValue.position >= 0) {
            documentRepository.updateReadingPosition(
                documentId = documentId,
                position = currentWordValue.absoluteStartIndex,
                totalWords = playbackCoordinator.getAbsoluteWordCount()
            )
        }
    }

    /**
     * Check if a filename looks legitimate or if it's encoded/corrupted
     */
    private fun isValidFilename(filename: String): Boolean {
        return when {
            // Too short or too long
            filename.length < 3 || filename.length > 255 -> false
            // Contains suspicious encoded patterns
            filename.contains("=") && filename.contains(";") -> false
            filename.startsWith("Acc=") -> false
            filename.contains("encoded=") -> false
            // Contains mostly random characters (more than 60% non-alphanumeric except common symbols)
            filename.count { !it.isLetterOrDigit() && it !in ".-_ () " } > filename.length * 0.6 -> false
            // Contains valid file extension
            filename.matches(Regex(".*\\.(pdf|epub|txt|docx?|rtf)", RegexOption.IGNORE_CASE)) -> true
            // Reasonable mix of letters and other characters
            filename.count { it.isLetter() } > filename.length * 0.3 -> true
            // Otherwise probably encoded/corrupted
            else -> false
        }
    }

    /**
     * Try to get the original filename from a content URI using ContentResolver
     */
    private fun getOriginalFileNameFromUri(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "content" -> {
                    // Use ContentResolver to query the display name
                    val cursor: Cursor? = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (displayNameIndex != -1) {
                                val displayName = it.getString(displayNameIndex)
                                // Only return if it's a valid filename
                                if (!displayName.isNullOrBlank() && isValidFilename(displayName)) {
                                    displayName
                                } else null
                            } else null
                        } else null
                    }
                }
                "file" -> {
                    // For file URIs, extract the filename from the path
                    val path = uri.path
                    if (!path.isNullOrBlank()) {
                        val filename = path.substringAfterLast('/')
                        if (filename.isNotBlank() && isValidFilename(filename)) {
                            filename
                        } else null
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            // If anything goes wrong, just return null
            null
        }
    }

    fun getCurrentDocumentText(): String {
        return currentText
    }

    // PDF Import Choice Dialog Methods
    fun processPdfWithTextExtraction(choice: PdfImportChoice) {
        viewModelScope.launch {
            _pdfImportChoice.value = null // Clear the dialog
            _fileImportResult.value = UiState.Loading

            when (val result = fileTextProvider.parseFileWithMetadata(choice.uri)) {
                is FileParseResult.Success -> {
                    val textSizeBytes = result.text.toByteArray(Charsets.UTF_8).size
                    val maxSizeBytes = Constants.MAX_DOCUMENT_SIZE_BYTES // Max document size in bytes

                    if (textSizeBytes > maxSizeBytes) { 
                        val sizeMB = textSizeBytes / 1_048_576.0
                        _fileImportResult.value = UiState.Error(
                            "Document too large to save (${String.format(Locale.US, "%.1f", sizeMB)}MB). You can read it but cannot save to library. Maximum size: 100MB."
                        )
                        loadTextAfterImportWithOriginalName(result, choice.uri, choice.originalFileName)
                        return@launch
                    }

                    loadTextAfterImportWithOriginalName(result, choice.uri, choice.originalFileName)
                    _fileImportResult.value = UiState.Success(result.metadata)

                    // Automatically save the imported document to library
                    saveCurrentDocument(choice.originalFileName)
                }
                is FileParseResult.Error -> {
                    _fileImportResult.value = UiState.Error(result.message)
                }
            }
        }
    }

    fun processPdfWithNativeRendering(choice: PdfImportChoice) {
        viewModelScope.launch {
            _pdfImportChoice.value = null // Clear the dialog
            _fileImportResult.value = UiState.Loading

            when (val result = fileTextProvider.parseFileWithMetadata(choice.uri)) {
                is FileParseResult.Success -> {
                    // Load text for RSVP compatibility but mark as supporting PDF rendering
                    loadTextAfterImportWithOriginalName(result, choice.uri, choice.originalFileName)
                    _fileImportResult.value = UiState.Success(result.metadata)

                    // Save with PDF rendering support enabled
                    saveCurrentDocumentWithPdfSupport(choice.originalFileName, choice.uri.toString())
                }
                is FileParseResult.Error -> {
                    _fileImportResult.value = UiState.Error(result.message)
                }
            }
        }
    }

    fun cancelPdfImportChoice() {
        _pdfImportChoice.value = null
        _fileImportResult.value = null
    }

    private suspend fun saveCurrentDocumentWithPdfSupport(title: String, originalUri: String) {
        try {
            // Convert data-importers PageBoundary to SavedDocument PageBoundary
            val documentPageBoundaries = currentPageBoundaries?.map { boundary ->
                com.speedread.rsvp.data.document.PageBoundary(
                    pageNumber = boundary.pageNumber,
                    startWordIndex = boundary.startWordIndex,
                    endWordIndex = boundary.endWordIndex,
                    wordCount = boundary.wordCount
                )
            }

            // Use the existing saveDocument method with PDF support parameters
            val savedDocumentId = documentRepository.saveNewDocument(
                title = title,
                content = currentText,
                source = BookmarkSource.FILE_PDF,
                originalFileName = title,
                originalUri = originalUri, // Keep original URI for PDF rendering
                mimeType = "application/pdf",
                pageCount = currentPageCount,
                pageBoundaries = documentPageBoundaries,
                figureRegions = currentFigureRegions?.map { it.toDocument() }
            )
            // Same unconditional adoption as saveCurrentDocument: this save path previously
            // never set the id at all, so a PDF import left Page View pointing at whatever
            // document was loaded before (or a deleted row after delete + re-import).
            currentDocumentId = savedDocumentId

            Logger.d("ReadingViewModel", "Saved PDF document with rendering support: $title")
        } catch (e: Exception) {
            Logger.e("ReadingViewModel", "Failed to save PDF document: ${e.message}", e)
            _fileImportResult.value = UiState.Error("Failed to save document: ${e.message}")
        }
    }

    private fun syncPageBoundariesToCoordinator() {
        val boundaries = currentPageBoundaries?.map { boundary ->
            com.speedread.rsvp.data.document.PageBoundary(
                pageNumber = boundary.pageNumber,
                startWordIndex = boundary.startWordIndex,
                endWordIndex = boundary.endWordIndex,
                wordCount = boundary.wordCount
            )
        }
        playbackCoordinator.setPageBoundaries(boundaries, currentPageCount)
    }
}