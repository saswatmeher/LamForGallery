package com.example.lamforgallery.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import com.example.lamforgallery.ui.PermissionType
import com.example.lamforgallery.database.ImageDao
import com.example.lamforgallery.database.ImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import android.content.ContentResolver
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * This class contains the *real* Kotlin implementations for all
 * agent tools and gallery-reading functions.
 */
class GalleryTools(
    private val resolver: ContentResolver,
    private val imageDao: ImageDao
) {

    private val TAG = "GalleryTools"

    // --- DATABASE SYNC ---
    
    /**
     * Syncs MediaStore images to our database.
     * This should be called when the app starts or when permissions are granted.
     */
    suspend fun syncMediaStoreToDatabase() {
        Log.d(TAG, "Syncing MediaStore to database...")
        withContext(Dispatchers.IO) {
            try {
                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED
                )
                
                val imagesToInsert = mutableListOf<ImageEntity>()
                
                resolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val displayName = cursor.getString(nameColumn) ?: "unknown"
                        val albumName = cursor.getString(bucketColumn) ?: "Camera"
                        val relativePath = cursor.getString(pathColumn) ?: ""
                        val dateTaken = cursor.getLong(dateTakenColumn)
                        val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // Convert to millis
                        
                        val uri = ContentUris.withAppendedId(collection, id).toString()
                        
                        // Check if already exists in database
                        val existingUri = imageDao.getImageByUri(uri)
                        if (existingUri == null) {
                            imagesToInsert.add(
                                ImageEntity(
                                    uri = uri,
                                    displayName = displayName,
                                    albumName = albumName,
                                    relativePath = relativePath,
                                    dateTaken = dateTaken,
                                    dateAdded = dateAdded
                                )
                            )
                        }
                    }
                }
                
                if (imagesToInsert.isNotEmpty()) {
                    imageDao.insertImages(imagesToInsert)
                    Log.d(TAG, "Synced ${imagesToInsert.size} new images to database")
                } else {
                    Log.d(TAG, "Database is up to date")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync MediaStore to database", e)
            }
        }
    }

    // --- AGENT TOOL IMPLEMENTATIONS ---

    /**
     * Searches for photos by filename in the database (excludes trashed photos).
     */
    suspend fun searchPhotos(query: String): List<String> {
        Log.d(TAG, "Searching database for: $query")
        return withContext(Dispatchers.IO) {
            try {
                val images = imageDao.searchImages("%$query%")
                val uris = images.map { it.uri }
                Log.d(TAG, "Found ${uris.size} photos matching query (excluding trashed).")
                uris
            } catch (e: Exception) {
                Log.e(TAG, "Failed to search photos", e)
                emptyList()
            }
        }
    }

    /**
     * Creates an IntentSender for a delete request.
     * This PAUSES the agent loop and asks the user for permission.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createDeleteIntentSender(photoUris: List<String>): IntentSender? {
        Log.d(TAG, "AGENT REQUESTED DELETE for: $photoUris")
        val uris = photoUris.map { Uri.parse(it) }
        return MediaStore.createDeleteRequest(resolver, uris).intentSender
    }

    /**
     * Moves photos to trash by updating database flag.
     * No file system changes needed - just marks as trashed in database.
     */
    suspend fun moveToTrash(photoUris: List<String>): Boolean {
        Log.d(TAG, "UI REQUESTED MOVE TO TRASH for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                imageDao.updateTrashStatus(photoUris, isTrashed = true, timestamp = timestamp)
                Log.d(TAG, "Successfully moved ${photoUris.size} photos to trash in database")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move photos to trash", e)
                false
            }
        }
    }

    /**
     * Permanently deletes photos from database and MediaStore.
     */
    suspend fun deletePhotos(photoUris: List<String>): Boolean {
        Log.d(TAG, "UI REQUESTED PERMANENT DELETE for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                // Delete from MediaStore
                var deletedCount = 0
                for (uriString in photoUris) {
                    val uri = Uri.parse(uriString)
                    try {
                        val deleted = resolver.delete(uri, null, null)
                        if (deleted > 0) {
                            deletedCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete from MediaStore: $uriString", e)
                    }
                }
                
                // Delete from database
                imageDao.deleteImages(photoUris)
                
                Log.d(TAG, "Successfully deleted ${deletedCount} photos from MediaStore and database")
                deletedCount == photoUris.size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete photos", e)
                false
            }
        }
    }

    /**
     * Restores photos from trash by updating database flag.
     */
    suspend fun restoreFromTrash(photoUris: List<String>): Boolean {
        Log.d(TAG, "UI REQUESTED RESTORE FROM TRASH for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                imageDao.updateTrashStatus(photoUris, isTrashed = false, timestamp = 0)
                Log.d(TAG, "Successfully restored ${photoUris.size} photos from trash")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore photos from trash", e)
                false
            }
        }
    }

    /**
     * Moves photos to a different album by updating database.
     */
    suspend fun movePhotosToAlbum(photoUris: List<String>, albumName: String): Boolean {
        Log.d(TAG, "UI REQUESTED MOVE to album: $albumName for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                imageDao.updateAlbum(photoUris, albumName)
                Log.d(TAG, "Successfully moved ${photoUris.size} photos to album $albumName")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move photos to album", e)
                false
            }
        }
    }

    /**
     * Creates an IntentSender for a write (move) request.
     * This PAUSES the agent loop and asks the user for permission.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createWriteIntentSender(photoUris: List<String>): IntentSender? {
        Log.d(TAG, "AGENT REQUESTED WRITE for: $photoUris")
        val uris = photoUris.map { Uri.parse(it) }
        return MediaStore.createWriteRequest(resolver, uris).intentSender
    }

    /**
     * Performs the *actual* move operation *after* permission is granted.
     */
    suspend fun performMoveOperation(photoUris: List<String>, albumName: String): Boolean {
        Log.d(TAG, "Performing MOVE to '$albumName' for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                for (uriString in photoUris) {
                    val values = ContentValues().apply {
                        // Moving files is done by changing their relative path
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName")
                    }
                    resolver.update(Uri.parse(uriString), values, null, null)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move files", e)
                false
            }
        }
    }

    /**
     * Creates a new collage bitmap and saves it to MediaStore.
     */
    suspend fun createCollage(photoUris: List<String>, title: String): String? {
        Log.d(TAG, "AGENT REQUESTED COLLAGE '$title' for: $photoUris")
        if (photoUris.isEmpty()) {
            throw Exception("No photos provided for collage.")
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Load bitmaps
                val bitmaps = photoUris.mapNotNull { loadBitmapFromUri(it) }
                if (bitmaps.isEmpty()) throw Exception("Could not load any bitmaps.")

                // 2. Create new bitmap (simple vertical stitch)
                val totalHeight = bitmaps.sumOf { it.height }
                val maxWidth = bitmaps.maxOf { it.width }
                val collageBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(collageBitmap)

                var currentY = 0f
                for (bitmap in bitmaps) {
                    canvas.drawBitmap(bitmap, 0f, currentY, null)
                    currentY += bitmap.height
                    bitmap.recycle() // Clean up memory
                }

                // 3. Save to MediaStore
                val newUri = saveBitmapToMediaStore(collageBitmap, title, "Pictures/Collages")
                collageBitmap.recycle()

                Log.d(TAG, "Collage created successfully: $newUri")
                newUri.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create collage", e)
                null
            }
        }
    }

    /**
     * Applies a filter and saves new images.
     */
    suspend fun applyFilter(photoUris: List<String>, filterName: String): List<String> {
        Log.d(TAG, "AGENT REQUESTED FILTER '$filterName' for: $photoUris")
        if (photoUris.isEmpty()) {
            throw Exception("No photos provided to apply filter.")
        }

        val filter = when (filterName.lowercase()) {
            "grayscale", "black and white", "b&w" -> FilterType.GRAYSCALE
            "sepia" -> FilterType.SEPIA
            else -> throw Exception("Unknown filter: $filterName. Supported filters are 'grayscale' and 'sepia'.")
        }

        return withContext(Dispatchers.IO) {
            val newImageUris = mutableListOf<String>()

            for (uriString in photoUris) {
                val originalBitmap = loadBitmapFromUri(uriString)
                if (originalBitmap == null) {
                    Log.w(TAG, "Failed to load bitmap for filter: $uriString")
                    continue
                }

                // Apply the filter
                val filteredBitmap = applyColorFilter(originalBitmap, filter)

                // Get original filename to create a new one
                val originalName = getFileName(uriString) ?: "filtered_image"
                val newTitle = "${originalName}_${filterName}"

                // Save the new bitmap
                try {
                    val newUri = saveBitmapToMediaStore(filteredBitmap, newTitle, "Pictures/Filters")
                    newImageUris.add(newUri.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save filtered bitmap", e)
                }

                // Cleanup
                originalBitmap.recycle()
                filteredBitmap.recycle()
            }

            Log.d(TAG, "Filter applied. New URIs: $newImageUris")
            newImageUris
        }
    }

    // --- GALLERY/ALBUM TAB FUNCTIONS ---

    data class Album(
        val name: String,
        val coverUri: String,
        val photoCount: Int
    )

    /**
     * Fetches all unique albums (folders) from the database.
     */
    suspend fun getAlbums(): List<Album> {
        Log.d(TAG, "Fetching all albums from database")
        return withContext(Dispatchers.IO) {
            try {
                val albumsInfo = imageDao.getAlbumsWithCount()
                val albums = mutableListOf<Album>()
                
                for (albumInfo in albumsInfo) {
                    // Get one photo from this album as cover
                    val photos = imageDao.getImagesByAlbum(albumInfo.albumName, limit = 1, offset = 0)
                    val coverUri = photos.firstOrNull()?.uri ?: ""
                    
                    albums.add(
                        Album(
                            name = albumInfo.albumName,
                            coverUri = coverUri,
                            photoCount = albumInfo.photoCount
                        )
                    )
                }
                
                Log.d(TAG, "Found ${albums.size} albums from database.")
                albums
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get albums from database", e)
                emptyList()
            }
        }
    }

    /**
     * Fetches all photos, with pagination.
     * Queries database and filters out trashed photos.
     */
    suspend fun getPhotos(page: Int, pageSize: Int): List<String> {
        Log.d(TAG, "Fetching photos from database, page: $page, size: $pageSize")
        return withContext(Dispatchers.IO) {
            try {
                val offset = page * pageSize
                val images = imageDao.getAllImages(limit = pageSize, offset = offset)
                val uris = images.map { it.uri }
                Log.d(TAG, "Found ${uris.size} photos for page $page from database.")
                uris
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get photos from database", e)
                emptyList()
            }
        }
    }

    /**
     * Fetches all photos *for a specific album*, with pagination.
     * Queries database and filters out trashed photos.
     */
    suspend fun getPhotosForAlbum(albumName: String, page: Int, pageSize: Int): List<String> {
        Log.d(TAG, "Fetching photos for album from database: $albumName, page: $page")
        return withContext(Dispatchers.IO) {
            try {
                val offset = page * pageSize
                val images = imageDao.getImagesByAlbum(albumName, limit = pageSize, offset = offset)
                val uris = images.map { it.uri }
                Log.d(TAG, "Found ${uris.size} photos for $albumName from database")
                uris
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get photos for album from database", e)
                emptyList()
            }
        }
    }

    /**
     * Fetches only trashed photos from database.
     * Used for the Trash album view.
     */
    suspend fun getTrashPhotos(page: Int = 0, pageSize: Int = 100): List<String> {
        Log.d(TAG, "Fetching trash photos from database, page: $page")
        return withContext(Dispatchers.IO) {
            try {
                val offset = page * pageSize
                val images = imageDao.getTrashedImages(limit = pageSize, offset = offset)
                val uris = images.map { it.uri }
                Log.d(TAG, "Found ${uris.size} trashed photos from database")
                uris
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get trashed photos from database", e)
                emptyList()
            }
        }
    }

    // --- PRIVATE HELPER FUNCTIONS ---

    private enum class FilterType { GRAYSCALE, SEPIA }

    private fun applyColorFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        val paint = android.graphics.Paint()

        val matrix = android.graphics.ColorMatrix()
        when (filter) {
            FilterType.GRAYSCALE -> matrix.setSaturation(0f)
            FilterType.SEPIA -> {
                matrix.setSaturation(0f)
                val sepiaMatrix = android.graphics.ColorMatrix().apply {
                    setScale(1f, 0.95f, 0.82f, 1f)
                }
                matrix.postConcat(sepiaMatrix)
            }
        }

        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return newBitmap
    }

    private fun getFileName(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                        ?.substringBeforeLast(".") // Remove extension
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get filename for $uriString", e)
            null
        }
    }

    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // More efficient method for Android 10+
                resolver.loadThumbnail(uri, Size(1080, 1080), null)
            } else {
                // Legacy method
                MediaStore.Images.Media.getBitmap(resolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from $uriString", e)
            null
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, title: String, directory: String = "Pictures/Collages"): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val filename = "${title.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, directory)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        var newImageUri: Uri? = null
        var outputStream: OutputStream? = null

        try {
            newImageUri = resolver.insert(collection, contentValues)
                ?: throw Exception("MediaStore.insert failed")

            outputStream = resolver.openOutputStream(newImageUri)
                ?: throw Exception("resolver.openOutputStream failed")

            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                throw Exception("Bitmap.compress failed")
            }
        } catch (e: Exception) {
            newImageUri?.let { resolver.delete(it, null, null) }
            throw Exception("Failed to save bitmap: ${e.message}")
        } finally {
            outputStream?.close()
        }

        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(newImageUri, contentValues, null, null)

        return newImageUri
    }
}