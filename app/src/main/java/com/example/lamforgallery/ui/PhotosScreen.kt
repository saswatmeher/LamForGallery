package com.example.lamforgallery.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.tools.GalleryTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

// --- Constants ---
private const val PAGE_SIZE = 60 // Load 60 photos at a time

// --- State ---
data class PhotosScreenState(
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val canLoadMore: Boolean = true, // Becomes false when we've loaded all photos
    val page: Int = 0,
    val selectedPhotos: Set<String> = emptySet(), // Selection state
    val isSelectionMode: Boolean = false, // Selection mode flag
    val showDeleteDialog: Boolean = false // Delete confirmation dialog
)

// --- ViewModel ---
class PhotosViewModel(
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotosScreenState())
    val uiState: StateFlow<PhotosScreenState> = _uiState.asStateFlow()

    private val TAG = "PhotosViewModel"

    init {
        // We will load photos from MainActivity *after*
        // permission is confirmed.
        // loadPhotos() // <-- DELETE OR COMMENT OUT THIS LINE
    }

    /**
     * Resets and loads the first page of photos.
     */
    fun loadPhotos() {
        // Reset state for a fresh load
        _uiState.value = PhotosScreenState()
        loadNextPage()
    }

    /**
     * Refreshes the photos list (alias for loadPhotos).
     */
    fun refreshPhotos() {
        loadPhotos()
    }

    /**
     * Loads the next page of photos.
     */
    fun loadNextPage() {
        if (_uiState.value.isLoading || !_uiState.value.canLoadMore) {
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                val loadedPaths = galleryTools.getPhotos(page = _uiState.value.page, pageSize = PAGE_SIZE)
                _uiState.update { current ->
                    current.copy(
                        photos = current.photos + loadedPaths,
                        isLoading = false,
                        page = current.page + 1,
                        canLoadMore = loadedPaths.size == PAGE_SIZE
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
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
                // Move to trash instead of permanent delete
                galleryTools.moveToTrash(photosToDelete)
                _uiState.update { current ->
                    current.copy(
                        photos = current.photos.filterNot { it in photosToDelete },
                        selectedPhotos = emptySet(),
                        isSelectionMode = false,
                        showDeleteDialog = false
                    )
                }
                // Call the completion callback after successful deletion
                onComplete()
            } catch (e: Exception) {
                // Handle error
                _uiState.update { it.copy(showDeleteDialog = false) }
            }
        }
    }
}

// --- Composable UI ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onPhotosDeleted: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    // --- This is the "Infinite Scroll" logic ---
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .distinctUntilChanged()
            .collect { layoutInfo ->
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@collect
                val totalItems = layoutInfo.totalItemsCount

                if (lastVisibleItem.index >= totalItems - (PAGE_SIZE / 2) &&
                    !uiState.isLoading &&
                    uiState.canLoadMore
                ) {
                    viewModel.loadNextPage()
                }
            }
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = uiState.selectedPhotos.size,
                    onClearSelection = { viewModel.clearSelection() }
                )
            }
        },
        floatingActionButton = {
            if (uiState.isSelectionMode && uiState.selectedPhotos.isNotEmpty()) {
                SelectionActionButtons(
                    onDelete = { viewModel.showDeleteConfirmation() }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.photos.isEmpty() && uiState.isLoading) {
                CircularProgressIndicator()
            } else if (uiState.photos.isEmpty() && !uiState.isLoading) {
                Text("No photos found.")
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.photos, key = { it }) { photoUri ->
                        SelectablePhotoItem(
                            photoPath = photoUri,
                            isSelected = uiState.selectedPhotos.contains(photoUri),
                            isSelectionMode = uiState.isSelectionMode,
                            onPhotoClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleSelection(photoUri)
                                }
                            },
                            onPhotoLongClick = {
                                viewModel.enterSelectionMode(photoUri)
                            }
                        )
                    }

                    if (uiState.isLoading && uiState.photos.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectablePhotoItem(
    photoPath: String,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onPhotoClick: () -> Unit,
    onPhotoLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(
                onClick = onPhotoClick,
                onLongClick = onPhotoLongClick
            )
    ) {
        AsyncImage(
            model = photoPath,
            contentDescription = "Gallery Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Selection overlay
        if (isSelectionMode) {
            // Dimmed overlay when in selection mode
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                )
            }

            // Selection indicator circle at bottom-right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.7f)
                    )
                    .border(
                        width = 2.dp,
                        color = if (isSelected) Color.White else Color.Gray,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear selection"
                )
            }
        }
    )
}

@Composable
fun SelectionActionButtons(
    onDelete: () -> Unit
) {
    FloatingActionButton(
        onClick = onDelete,
        containerColor = MaterialTheme.colorScheme.error
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = "Delete selected"
        )
    }
}