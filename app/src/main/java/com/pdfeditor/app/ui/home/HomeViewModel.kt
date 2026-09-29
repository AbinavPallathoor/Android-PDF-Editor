package com.pdfeditor.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.domain.RecentsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val recentsRepository: RecentsRepository,
) : ViewModel() {
    val recents: StateFlow<List<RecentDocument>> =
        recentsRepository.observeRecents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun recordOpened(
        uri: String,
        displayName: String,
        fileSizeBytes: Long?,
    ): RecentDocument = recentsRepository.recordOpened(uri, displayName, fileSizeBytes)

    fun removeFromRecents(id: Long) {
        viewModelScope.launch { recentsRepository.remove(id) }
    }

    fun togglePinned(
        id: Long,
        currentlyPinned: Boolean,
    ) {
        viewModelScope.launch { recentsRepository.setPinned(id, !currentlyPinned) }
    }

    class Factory(
        private val recentsRepository: RecentsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(recentsRepository) as T
    }
}
