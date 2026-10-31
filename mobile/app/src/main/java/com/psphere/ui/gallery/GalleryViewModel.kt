package com.psphere.ui.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psphere.data.LocalPhotoRepository
import com.psphere.data.TokenDataStore
import com.psphere.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Unified photo model used by GalleryScreen regardless of source (local or server). */
data class GalleryPhoto(
    val id: String,
    val thumbUri: String,    // content:// for local, https://... for server
    val dateTaken: Long,     // epoch millis; 0 if unknown
    val isVideo: Boolean = false,
)

sealed class GridItem {
    data class Header(val label: String) : GridItem()
    data class Photo(val photo: GalleryPhoto, val globalIndex: Int) : GridItem()
}

data class GalleryState(
    val items: List<GridItem>        = emptyList(),
    val allPhotos: List<GalleryPhoto> = emptyList(),
    val loading: Boolean             = false,
    val loadingMore: Boolean         = false,
    val hasMore: Boolean             = true,
    val totalCount: Int              = 0,
    val error: String                = "",
    val isServerSource: Boolean      = false,
)

private val MONTH_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy").withZone(ZoneId.systemDefault())

private const val PAGE_SIZE = 150

class GalleryViewModel : ViewModel() {

    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state

    fun load(context: Context) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = "")
            val store = TokenDataStore(context)
            val jwt = store.jwtToken.first()

            if (jwt != null) {
                loadFromServer(jwt)
            } else {
                loadFromLocal(context)
            }
        }
    }

    fun loadMore(context: Context) {
        val s = _state.value
        if (!s.hasMore || s.loading || s.loadingMore) return

        viewModelScope.launch {
            val store = TokenDataStore(context)
            val jwt = store.jwtToken.first()

            if (jwt != null && s.isServerSource) {
                loadMoreFromServer(jwt, s)
            } else if (!s.isServerSource) {
                loadMoreFromLocal(context, s)
            }
        }
    }

    fun refresh(context: Context) {
        _state.value = GalleryState()
        load(context)
    }

    // ── Server source ─────────────────────────────────────────────────────────

    private suspend fun loadFromServer(jwt: String) {
        try {
            val res    = ApiClient.api.getTimeline("Bearer $jwt", page = 1, limit = PAGE_SIZE)
            val photos = res.photos.map { dto ->
                GalleryPhoto(
                    id       = dto.id,
                    thumbUri = "${ApiClient.currentBaseUrl()}${dto.thumb_url}",
                    dateTaken = dto.taken_at?.let {
                        runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
                    } ?: 0L,
                )
            }
            _state.value = GalleryState(
                items          = buildGridItems(photos),
                allPhotos      = photos,
                loading        = false,
                hasMore        = res.photos.size >= PAGE_SIZE,
                totalCount     = res.pagination.total,
                isServerSource = true,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load photos")
        }
    }

    private suspend fun loadMoreFromServer(jwt: String, current: GalleryState) {
        _state.value = current.copy(loadingMore = true)
        try {
            val nextPage = (current.allPhotos.size / PAGE_SIZE) + 1
            val res = ApiClient.api.getTimeline("Bearer $jwt", page = nextPage, limit = PAGE_SIZE)
            val newPhotos = res.photos.map { dto ->
                GalleryPhoto(
                    id        = dto.id,
                    thumbUri  = "${ApiClient.currentBaseUrl()}${dto.thumb_url}",
                    dateTaken = dto.taken_at?.let {
                        runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
                    } ?: 0L,
                )
            }
            val all = current.allPhotos + newPhotos
            _state.value = current.copy(
                items       = buildGridItems(all),
                allPhotos   = all,
                loadingMore = false,
                hasMore     = newPhotos.size >= PAGE_SIZE,
            )
        } catch (_: Exception) {
            _state.value = current.copy(loadingMore = false)
        }
    }

    // ── Local source ──────────────────────────────────────────────────────────

    private var activeFolderPath: String? = null

    private suspend fun loadFromLocal(context: Context) {
        try {
            val folderPath = TokenDataStore(context).sourceFolderPath.first()
            activeFolderPath = folderPath
            val locals = if (!folderPath.isNullOrBlank()) {
                LocalPhotoRepository.getPageFromFolder(context, folderPath, PAGE_SIZE, 0)
            } else {
                LocalPhotoRepository.getPage(context, PAGE_SIZE, 0)
            }
            val total = if (!folderPath.isNullOrBlank()) locals.size else LocalPhotoRepository.getCount(context)
            val photos = locals.map { p ->
                GalleryPhoto(id = p.id.toString(), thumbUri = p.uri.toString(), dateTaken = p.dateTaken, isVideo = p.isVideo)
            }
            _state.value = GalleryState(
                items      = buildGridItems(photos),
                allPhotos  = photos,
                loading    = false,
                hasMore    = locals.size >= PAGE_SIZE,
                totalCount = total,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load photos")
        }
    }

    private suspend fun loadMoreFromLocal(context: Context, current: GalleryState) {
        _state.value = current.copy(loadingMore = true)
        try {
            val folderPath = activeFolderPath
            val next = if (!folderPath.isNullOrBlank()) {
                LocalPhotoRepository.getPageFromFolder(context, folderPath, PAGE_SIZE, current.allPhotos.size)
            } else {
                LocalPhotoRepository.getPage(context, PAGE_SIZE, current.allPhotos.size)
            }
            val newPhotos = next.map { p ->
                GalleryPhoto(id = p.id.toString(), thumbUri = p.uri.toString(), dateTaken = p.dateTaken, isVideo = p.isVideo)
            }
            val all = current.allPhotos + newPhotos
            _state.value = current.copy(
                items       = buildGridItems(all),
                allPhotos   = all,
                loadingMore = false,
                hasMore     = newPhotos.size >= PAGE_SIZE,
            )
        } catch (_: Exception) {
            _state.value = current.copy(loadingMore = false)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildGridItems(photos: List<GalleryPhoto>): List<GridItem> {
        val grouped = photos.groupBy { p ->
            if (p.dateTaken > 0)
                MONTH_FMT.format(Instant.ofEpochMilli(p.dateTaken))
            else
                "Unknown date"
        }
        var index = 0
        return buildList {
            for ((label, group) in grouped) {
                add(GridItem.Header(label))
                for (photo in group) add(GridItem.Photo(photo, index++))
            }
        }
    }
}
