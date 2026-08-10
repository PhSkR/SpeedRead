package com.speedread.rsvp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedread.rsvp.data.document.DocumentRepository
import com.speedread.rsvp.data.document.SavedDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val tokenCache: PageViewTokenCache,
    private val pageViewPositionManager: PageViewPositionManager
) : ViewModel() {

    // Search query for documents
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // All saved documents with search filtering
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allSavedDocuments = _searchQuery.flatMapLatest { query ->
        documentRepository.getAllDocuments().map { documents ->
            if (query.isBlank()) {
                documents
            } else {
                documents.filter { document ->
                    document.title.contains(query, ignoreCase = true)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query.trim()
    }

    suspend fun getAllSavedDocuments(): List<SavedDocument> {
        return documentRepository.getAllDocuments().first()
    }

    fun deleteSavedDocument(document: SavedDocument) {
        viewModelScope.launch {
            tokenCache.store.invalidate(document.contentHash)
            // Drop the Page View position pref too — after a destructive DB reset, id
            // sequences restart and a new document could otherwise inherit this orphaned
            // position and open at a bogus offset.
            pageViewPositionManager.clearPosition(document.id)
            documentRepository.deleteDocument(document)
        }
    }

    fun deleteDocuments(documents: List<SavedDocument>) {
        viewModelScope.launch {
            documents.forEach { document ->
                tokenCache.store.invalidate(document.contentHash)
                pageViewPositionManager.clearPosition(document.id)
                documentRepository.deleteDocument(document)
            }
        }
    }

    fun toggleDocumentFavorite(document: SavedDocument) {
        viewModelScope.launch {
            documentRepository.updateFavoriteStatus(document.id, !document.isFavorite)
        }
    }

    fun invalidateDocumentCache(document: SavedDocument) {
        viewModelScope.launch {
            tokenCache.invalidateInMemory()
            tokenCache.store.invalidate(document.contentHash)
        }
    }
}