package com.example.lamforgallery.ui

import android.app.Application
import android.content.ContentUris
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.database.ImageEmbedding
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ImageEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// A data class to hold all UI state in one object
data class EmbeddingUiState(
    val statusMessage: String = "Ready to index. Press 'Start' to begin.",
    val progress: Float = 0f,
    val isIndexing: Boolean = false,
    val totalImages: Int = 0,
    val indexedImages: Int = 0
)

/**
 * ViewModel for the EmbeddingScreen.
 * Handles the logic for scanning and indexing images.
 */
class EmbeddingViewModel(
    application: Application,
    private val dao: ImageEmbeddingDao,
    private val imageEncoder: ImageEncoder
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EmbeddingUiState())
    val uiState: StateFlow<EmbeddingUiState> = _uiState.asStateFlow()

    private val contentResolver = application.contentResolver

    companion object {
        private const val TAG = "EmbeddingViewModel"
    }

    init {
        // Check initial database state when ViewModel is created
        viewModelScope.launch(Dispatchers.IO) {
            val allImages = getAllImageUris()
            val indexedImages = dao.getAllEmbeddings().size
            _uiState.update {
                it.copy(
                    totalImages = allImages.size,
                    indexedImages = indexedImages,
                    statusMessage = "Ready. ${allImages.size} total images found, ${indexedImages} already indexed."
                )
            }
        }
    }

    /**
     * Starts the main indexing process.
     */
    fun startIndexing() {
        // Don't start a new job if one is already running
        if (_uiState.value.isIndexing) return

        // Run this in the IO dispatcher for heavy file/DB operations
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isIndexing = true, statusMessage = "Starting...", progress = 0f) }

            try {
                // 1. Get all image URIs from the MediaStore
                _uiState.update { it.copy(statusMessage = "Scanning device for images...") }
                val allImageUris = getAllImageUris()
                Log.d(TAG, "Found ${allImageUris.size} total images on device.")

                // 2. Get all URIs we have already indexed from our database
                _uiState.update { it.copy(statusMessage = "Checking database for existing images...") }
                val indexedUris = dao.getAllEmbeddings().map { it.uri }.toSet()
                Log.d(TAG, "Found ${indexedUris.size} images already in database.")

                // 3. Filter for *only* the new images
                val newImages = allImageUris.filter { !indexedUris.contains(it.toString()) }
                val totalNewImages = newImages.size
                Log.d(TAG, "Found $totalNewImages new images to index.")

                if (totalNewImages == 0) {
                    _uiState.update {
                        it.copy(
                            isIndexing = false,
                            statusMessage = "All ${allImageUris.size} images are already indexed!",
                            totalImages = allImageUris.size,
                            indexedImages = indexedUris.size
                        )
                    }
                    return@launch
                }

                // 4. Process each new image
                newImages.forEachIndexed { index, uri ->
                    val imageNumber = index + 1
                    _uiState.update {
                        it.copy(
                            statusMessage = "Indexing image $imageNumber of $totalNewImages...",
                            progress = imageNumber.toFloat() / totalNewImages.toFloat()
                        )
                    }

                    try {
                        // Load the bitmap from its URI
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
                        } else {
                            @Suppress("DEPRECATION")
                            MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        }

                        // Run the ML model
                        val embedding = imageEncoder.encode(bitmap)

                        // Save to the database
                        dao.insert(ImageEmbedding(uri.toString(), embedding))

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process image $uri: ${e.message}", e)
                    }
                }

                // 5. All done
                _uiState.update {
                    it.copy(
                        isIndexing = false,
                        statusMessage = "Indexing complete! Added $totalNewImages new images.",
                        progress = 1f,
                        totalImages = allImageUris.size,
                        indexedImages = allImageUris.size // All are now indexed
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Indexing failed: ${e.message}", e)
                _uiState.update { it.copy(isIndexing = false, statusMessage = "Error: ${e.message}") }
            }
        }
    }

    /**
     * Queries the MediaStore to get a list of all image URIs.
     */
    private fun getAllImageUris(): List<Uri> {
        val imageUris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                imageUris.add(contentUri)
            }
        }
        return imageUris
    }
}