package com.psphere.ui.photos

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psphere.data.LocalPhotoRepository
import com.psphere.data.TokenDataStore
import com.psphere.data.toPhotoDto
import com.psphere.network.ApiClient
import com.psphere.network.PhotoDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PhotosState(
    val photos: List<PhotoDto> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val isLocalSource: Boolean = false,  // true when reading from MediaStore rather than Core API
)

class PhotoViewModel : ViewModel() {
    private val _state = MutableStateFlow(PhotosState())
    val state: StateFlow<PhotosState> = _state

    fun load(context: Context, page: Int = 1) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = "")

            val store   = TokenDataStore(context)
            val jwt     = store.jwtToken.first()
            val p2pMode = store.p2pMode.first()

            if (p2pMode || jwt == null) {
                // P2P / offline mode — read directly from the device's MediaStore
                val local = LocalPhotoRepository.getPhotos(context)
                _state.value = _state.value.copy(
                    photos      = local.map { it.toPhotoDto() },
                    loading     = false,
                    currentPage = 1,
                    totalPages  = 1,
                    isLocalSource = true,
                )
            } else {
                // Core mode — fetch from the PSphere backend
                try {
                    val res    = ApiClient.api.getTimeline("Bearer $jwt", page, 80)
                    val photos = if (page == 1) res.photos else _state.value.photos + res.photos
                    _state.value = _state.value.copy(
                        photos      = photos,
                        loading     = false,
                        currentPage = page,
                        totalPages  = res.pagination.pages,
                        isLocalSource = false,
                    )
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error   = e.message ?: "Failed to load photos"
                    )
                }
            }
        }
    }

    fun refresh(context: Context) = load(context, 1)

    fun loadNextPage(context: Context) {
        val s = _state.value
        // Pagination only applies to Core mode; local MediaStore loads everything at once
        if (!s.isLocalSource && !s.loading && s.currentPage < s.totalPages) {
            load(context, s.currentPage + 1)
        }
    }
}
