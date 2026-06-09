package com.psphere.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psphere.data.TokenDataStore
import com.psphere.network.AiSearchResultItem
import com.psphere.network.AiSearchRequest
import com.psphere.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SearchState(
    val query: String                    = "",
    val results: List<AiSearchResultItem> = emptyList(),
    val explanation: String              = "",
    val fallback: Boolean                = false,
    val loading: Boolean                 = false,
    val error: String                    = "",
    val hasSearched: Boolean             = false,
)

class SearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun search(context: Context) {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            val jwt = TokenDataStore(context).jwtToken.first() ?: run {
                _state.value = _state.value.copy(error = "Sign in to a Core server to use AI search.")
                return@launch
            }
            _state.value = _state.value.copy(loading = true, error = "", results = emptyList(), explanation = "")
            try {
                val res = ApiClient.api.aiSearch("Bearer $jwt", AiSearchRequest(query = q, limit = 30))
                _state.value = _state.value.copy(
                    results     = res.photos,
                    explanation = res.explanation,
                    fallback    = res.fallback,
                    loading     = false,
                    hasSearched = true,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Search failed", hasSearched = true)
            }
        }
    }

    fun clear() {
        _state.value = SearchState()
    }
}
