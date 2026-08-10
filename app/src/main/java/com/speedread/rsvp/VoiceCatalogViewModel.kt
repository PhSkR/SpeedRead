package com.speedread.rsvp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedread.rsvp.tts.TtsModelRegistry
import com.speedread.rsvp.tts.catalog.CatalogState
import com.speedread.rsvp.tts.catalog.VoiceCatalogEntry
import com.speedread.rsvp.tts.catalog.VoiceCatalogRepository
import com.speedread.rsvp.tts.catalog.VoiceCatalogRow
import com.speedread.rsvp.tts.catalog.VoiceInstallStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-state binder for the in-app Piper voice catalog. Joins:
 *  - [VoiceCatalogRepository.catalogState] (the network result)
 *  - [TtsModelRegistry.voices]            (what's installed on disk)
 *  - [VoiceCatalogRepository.transientStatus] (in-flight downloads)
 *  - [searchQuery]                        (the user's current text filter)
 *
 * into one [rows] StateFlow that drives the RecyclerView. Triggers an initial catalog fetch
 * lazily on first ViewModel creation; subsequent refreshes are user-driven via [refresh].
 */
@HiltViewModel
class VoiceCatalogViewModel @Inject constructor(
    private val repository: VoiceCatalogRepository,
    registry: TtsModelRegistry
) : ViewModel() {

    val catalogState: StateFlow<CatalogState> = repository.catalogState

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Joined stream that drives the RecyclerView. The transform runs on [Dispatchers.IO]
     * because [VoiceCatalogRepository.computeStatus] reads each installed voice's
     * `.voice_metadata.json` from disk to detect "update available" — doing that on the
     * collector's default Main dispatcher would jank the UI on every flow re-emission.
     */
    val rows: StateFlow<List<VoiceCatalogRow>> = combine(
        repository.catalogState,
        registry.voices,
        repository.transientStatus,
        _searchQuery
    ) { state, installed, transient, query ->
        val entries = (state as? CatalogState.Loaded)?.entries.orEmpty()
        val filtered = if (query.isBlank()) entries else entries.filter { matchesQuery(it, query) }
        filtered.map { entry ->
            VoiceCatalogRow(
                entry = entry,
                status = repository.computeStatus(entry, installed, transient)
            )
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Cold-fetch on first creation. Subsequent observers reuse the StateFlow without
        // triggering another HTTP call.
        viewModelScope.launch {
            repository.refreshCatalog(forceRefresh = false)
        }
    }

    fun refresh(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            repository.refreshCatalog(forceRefresh = forceRefresh)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun install(entry: VoiceCatalogEntry) {
        viewModelScope.launch { repository.install(entry) }
    }

    fun cancel(entry: VoiceCatalogEntry) {
        viewModelScope.launch { repository.cancelInstall(entry.voiceId) }
    }

    fun uninstall(entry: VoiceCatalogEntry) {
        viewModelScope.launch { repository.uninstall(entry.voiceId) }
    }

    private fun matchesQuery(entry: VoiceCatalogEntry, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return entry.assetName.lowercase().contains(q) ||
            entry.voiceName.lowercase().contains(q) ||
            entry.languageCode.lowercase().contains(q) ||
            entry.quality.lowercase().contains(q)
    }

    companion object {
        /** Convenience for the UI: returns true when the row's button should be enabled. */
        fun isActionEnabled(status: VoiceInstallStatus): Boolean = when (status) {
            is VoiceInstallStatus.Downloading -> true
            VoiceInstallStatus.Extracting -> false
            VoiceInstallStatus.Installed -> true
            VoiceInstallStatus.NotInstalled -> true
            VoiceInstallStatus.UpdateAvailable -> true
            is VoiceInstallStatus.Failed -> true
        }
    }
}
