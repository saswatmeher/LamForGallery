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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val ALBUM_PAGE_SIZE = 60

// --- State ---
data class AlbumDetailState(
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val canLoadMore: Boolean = true,
    val page: Int = 0,
    val albumName: String = ""
)

// --- ViewModel ---
class AlbumDetailViewModel(
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailState())
    val uiState: StateFlow<AlbumDetailState> = _uiState.asStateFlow()

    private val TAG = "AlbumDetailViewModel"

    fun loadAlbum(name: String) {
        // Decode the album name (in case it has spaces like "My Cats")
        val decodedName = try {
            URLDecoder.decode(name, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode album name", e)
            name
        }

        _uiState.value = AlbumDetailState(albumName = decodedName)
        loadNextPage()
    }

    fun loadNextPage() {
        val currentState = _uiState.value
        if (currentState.isLoading || !currentState.canLoadMore || currentState.albumName.isEmpty()) {
            return
        }

        Log.d(TAG, "Loading next page for ${currentState.albumName}")

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val newPhotos = try {
                galleryTools.getPhotosForAlbum(
                    albumName = currentState.albumName,
                    page = currentState.page,
                    pageSize = ALBUM_PAGE_SIZE
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load photos for album", e)
                emptyList<String>()
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    photos = it.photos + newPhotos,
                    page = it.page + 1,
                    canLoadMore = newPhotos.isNotEmpty()
                )
            }
        }
    }
}

// --- Composable UI ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumName: String,
    viewModel: AlbumDetailViewModel,
    onNavigateBack: () -> Unit
) {
    // Load the album when the screen first appears
    LaunchedEffect(albumName) {
        viewModel.loadAlbum(albumName)
    }

    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    // Infinite Scroll Logic
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .distinctUntilChanged()
            .collect { layoutInfo ->
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@collect
                val totalItems = layoutInfo.totalItemsCount

                if (lastVisibleItem.index >= totalItems - (ALBUM_PAGE_SIZE / 2) &&
                    !uiState.isLoading &&
                    uiState.canLoadMore
                ) {
                    viewModel.loadNextPage()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.albumName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
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
                Text("No photos found in this album.")
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.photos, key = { it }) { photoUri ->
                        AsyncImage(
                            model = photoUri,
                            contentDescription = "Album Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .fillMaxWidth()
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
    }
}