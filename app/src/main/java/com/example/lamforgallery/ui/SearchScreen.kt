package com.example.lamforgallery.ui

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.example.lamforgallery.tools.GalleryTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// --- ViewModel ---

data class SearchScreenState(
    val searchQuery: String = "",
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val selectedPhotos: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val showDeleteDialog: Boolean = false
)

class SearchViewModel(
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchScreenState())
    val uiState: StateFlow<SearchScreenState> = _uiState.asStateFlow()

    private val TAG = "SearchViewModel"

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun performSearch() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) return

        _uiState.update { it.copy(isLoading = true, hasSearched = true) }

        viewModelScope.launch {
            try {
                val results = galleryTools.searchPhotos(query)
                _uiState.update { current ->
                    current.copy(
                        photos = results,
                        isLoading = false
                    )
                }
                Log.d(TAG, "Search found ${results.size} photos")
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                _uiState.update { it.copy(isLoading = false, photos = emptyList()) }
            }
        }
    }

    fun clearSearch() {
        _uiState.update { 
            SearchScreenState() // Reset to initial state
        }
    }

    fun toggleSelection(photoPath: String) {
        _uiState.update { current ->
            val newSelection = if (current.selectedPhotos.contains(photoPath)) {
                current.selectedPhotos - photoPath
            } else {
                current.selectedPhotos + photoPath
            }
            current.copy(
                selectedPhotos = newSelection,
                isSelectionMode = newSelection.isNotEmpty()
            )
        }
    }

    fun enterSelectionMode(photoPath: String) {
        _uiState.update {
            it.copy(
                selectedPhotos = setOf(photoPath),
                isSelectionMode = true
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedPhotos = emptySet(),
                isSelectionMode = false
            )
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun deleteSelectedPhotos(onComplete: () -> Unit = {}) {
        val photosToDelete = _uiState.value.selectedPhotos.toList()
        if (photosToDelete.isEmpty()) return

        viewModelScope.launch {
            try {
                galleryTools.moveToTrash(photosToDelete)
                _uiState.update { current ->
                    current.copy(
                        photos = current.photos.filterNot { it in photosToDelete },
                        selectedPhotos = emptySet(),
                        isSelectionMode = false,
                        showDeleteDialog = false
                    )
                }
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete photos", e)
                _uiState.update { it.copy(showDeleteDialog = false) }
            }
        }
    }
}

// --- Composable UI ---

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onPhotosDeleted: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = uiState.selectedPhotos.size,
                    onClearSelection = { viewModel.clearSelection() }
                )
            } else {
                SearchTopBar(
                    searchQuery = uiState.searchQuery,
                    onQueryChange = { viewModel.updateSearchQuery(it) },
                    onSearch = { viewModel.performSearch() },
                    onClear = { viewModel.clearSearch() }
                )
            }
        },
        floatingActionButton = {
            if (uiState.isSelectionMode && uiState.selectedPhotos.isNotEmpty()) {
                SearchSelectionActionButtons(
                    onDelete = { viewModel.showDeleteConfirmation() }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                !uiState.hasSearched -> {
                    Text(
                        text = "Enter a search term to find photos",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uiState.photos.isEmpty() -> {
                    Text(
                        text = "No photos found",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(uiState.photos, key = { it }) { photoPath ->
                            SelectablePhotoItem(
                                photoPath = photoPath,
                                isSelected = uiState.selectedPhotos.contains(photoPath),
                                isSelectionMode = uiState.isSelectionMode,
                                onPhotoClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleSelection(photoPath)
                                    }
                                },
                                onPhotoLongClick = {
                                    if (!uiState.isSelectionMode) {
                                        viewModel.enterSelectionMode(photoPath)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Show delete confirmation dialog
        if (uiState.showDeleteDialog) {
            DeleteConfirmationDialog(
                itemCount = uiState.selectedPhotos.size,
                onConfirm = {
                    viewModel.deleteSelectedPhotos(onComplete = onPhotosDeleted)
                },
                onDismiss = { viewModel.dismissDeleteDialog() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    TopAppBar(
        title = {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("Search photos by name...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }
    )
}

@Composable
fun SearchSelectionActionButtons(onDelete: () -> Unit) {
    FloatingActionButton(
        onClick = onDelete,
        containerColor = MaterialTheme.colorScheme.error
    ) {
        Icon(Icons.Default.Delete, contentDescription = "Delete")
    }
}
