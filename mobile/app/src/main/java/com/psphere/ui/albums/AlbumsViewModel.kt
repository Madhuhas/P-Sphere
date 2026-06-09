package com.psphere.ui.albums

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psphere.data.TokenDataStore
import com.psphere.network.AlbumDetailResponse
import com.psphere.network.AlbumDto
import com.psphere.network.ApiClient
import com.psphere.network.PhotoDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AlbumsState(
    val albums: List<AlbumDto>     = emptyList(),
    val loading: Boolean           = false,
    val error: String              = "",
    val generatingSmartAlbums: Boolean = false,
    // Detail view
    val openAlbum: AlbumDto?       = null,
    val albumPhotos: List<PhotoDto> = emptyList(),
    val albumPhotosLoading: Boolean = false,
)

class AlbumsViewModel : ViewModel() {

    private val _state = MutableStateFlow(AlbumsState())
    val state: StateFlow<AlbumsState> = _state

    fun load(context: Context) {
        viewModelScope.launch {
            val jwt = TokenDataStore(context).jwtToken.first() ?: run {
                _state.value = _state.value.copy(error = "Sign in to a Core server to see albums.")
                return@launch
            }
            _state.value = _state.value.copy(loading = true, error = "")
            try {
                val res = ApiClient.api.getAlbums("Bearer $jwt")
                _state.value = _state.value.copy(albums = res.albums, loading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load albums")
            }
        }
    }

    fun refresh(context: Context) {
        _state.value = _state.value.copy(albums = emptyList())
        load(context)
    }

    fun openAlbum(context: Context, album: AlbumDto) {
        _state.value = _state.value.copy(openAlbum = album, albumPhotos = emptyList(), albumPhotosLoading = true)
        viewModelScope.launch {
            val jwt = TokenDataStore(context).jwtToken.first() ?: return@launch
            try {
                val res = ApiClient.api.getAlbum("Bearer $jwt", album.id)
                _state.value = _state.value.copy(albumPhotos = res.photos, albumPhotosLoading = false)
            } catch (_: Exception) {
                _state.value = _state.value.copy(albumPhotosLoading = false)
            }
        }
    }

    fun closeAlbum() {
        _state.value = _state.value.copy(openAlbum = null, albumPhotos = emptyList())
    }

    fun generateSmartAlbums(context: Context) {
        viewModelScope.launch {
            val jwt = TokenDataStore(context).jwtToken.first() ?: return@launch
            _state.value = _state.value.copy(generatingSmartAlbums = true)
            try {
                ApiClient.api.generateSmartAlbums("Bearer $jwt")
                load(context)
            } catch (_: Exception) {
                _state.value = _state.value.copy(generatingSmartAlbums = false)
            }
        }
    }

    fun deleteAlbum(context: Context, albumId: String) {
        viewModelScope.launch {
            val jwt = TokenDataStore(context).jwtToken.first() ?: return@launch
            try {
                ApiClient.api.deleteAlbum("Bearer $jwt", albumId)
                _state.value = _state.value.copy(albums = _state.value.albums.filter { it.id != albumId })
            } catch (_: Exception) { /* keep list intact on failure */ }
        }
    }
}
