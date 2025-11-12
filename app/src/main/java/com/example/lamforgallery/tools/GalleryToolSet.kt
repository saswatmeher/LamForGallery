package com.example.lamforgallery.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import android.content.ContentResolver
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.serialization.Serializable

/**
 * Koog-compatible ToolSet for gallery operations.
 * Wraps GalleryTools functionality with Koog's annotation-based tool system.
 */
@LLMDescription("Tools for searching, organizing, and editing photos in the device gallery")
class GalleryToolSet(
    private val galleryTools: GalleryTools,
    private val onSearchResults: ((List<String>) -> Unit)? = null
) : ToolSet {

    private val TAG = "GalleryToolSet"

    @Serializable
    data class SearchPhotosArgs(
        @property:LLMDescription("The search query - can be a filename or natural language description (e.g., 'sunset', 'people smiling', 'cat')")
        val query: String,
        @property:LLMDescription("Maximum number of results to return (default: 20)")
        val limit: Int = 20,
        @property:LLMDescription("Minimum similarity threshold for semantic search (0.0 to 1.0, default: 0.2)")
        val threshold: Float = 0.2f
    )

    @Serializable
    data class PhotoListResult(
        val photoUris: List<String>,
        val count: Int
    )

    @Tool
    @LLMDescription("Searches for photos using AI-powered semantic search. Can understand natural language queries like 'sunset', 'people smiling', 'cat photos'. Falls back to filename search if AI embeddings are not available.")
    suspend fun searchPhotos(args: SearchPhotosArgs): String {
        return try {
            val uris = galleryTools.searchPhotosBySemantic(args.query, args.limit, args.threshold)
            Log.d(TAG, "Search found ${uris.size} photos for query: ${args.query}")
            
            // Update UI with search results via callback
            onSearchResults?.invoke(uris)
            
            """{"count": ${uris.size}, "message": "Found ${uris.size} photos matching '${args.query}'"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error searching photos", e)
            """{"error": "Failed to search photos: ${e.message}"}"""
        }
    }

    @Serializable
    data class DeletePhotosArgs(
        @property:LLMDescription("List of photo URIs to delete")
        val photoUris: List<String>
    )

    @Tool
    @LLMDescription("Deletes the specified photos from the gallery. Requires user permission on Android 11+. Returns success status.")
    suspend fun deletePhotos(args: DeletePhotosArgs): String {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            """{"error": "Delete operation requires Android 11+"}"""
        } else {
            try {
                // This will trigger permission request flow
                Log.d(TAG, "Delete requested for ${args.photoUris.size} photos")
                """{"requiresPermission": true, "permissionType": "DELETE", "photoUris": ${args.photoUris.map { "\"$it\"" }}}"""
            } catch (e: Exception) {
                Log.e(TAG, "Error in delete photos", e)
                """{"error": "Failed to delete photos: ${e.message}"}"""
            }
        }
    }

    @Serializable
    data class MovePhotosArgs(
        @property:LLMDescription("List of photo URIs to move")
        val photoUris: List<String>,
        @property:LLMDescription("Name of the album/folder to move photos to")
        val albumName: String
    )

    @Tool
    @LLMDescription("Moves photos to a specified album/folder. Creates the album if it doesn't exist. Requires user permission on Android 11+.")
    suspend fun movePhotosToAlbum(args: MovePhotosArgs): String {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            """{"error": "Move operation requires Android 11+"}"""
        } else {
            try {
                Log.d(TAG, "Move requested for ${args.photoUris.size} photos to album: ${args.albumName}")
                """{"requiresPermission": true, "permissionType": "WRITE", "photoUris": ${args.photoUris.map { "\"$it\"" }}, "albumName": "${args.albumName}"}"""
            } catch (e: Exception) {
                Log.e(TAG, "Error in move photos", e)
                """{"error": "Failed to move photos: ${e.message}"}"""
            }
        }
    }

    @Serializable
    data class CreateCollageArgs(
        @property:LLMDescription("List of photo URIs to include in the collage")
        val photoUris: List<String>,
        @property:LLMDescription("Title/name for the collage image")
        val title: String
    )

    @Tool
    @LLMDescription("Creates a collage from multiple photos by stitching them together vertically. Saves the result as a new image.")
    suspend fun createCollage(args: CreateCollageArgs): String {
        return try {
            if (args.photoUris.isEmpty()) {
                return """{"error": "No photos provided for collage"}"""
            }
            val newUri = galleryTools.createCollage(args.photoUris, args.title)
            if (newUri != null) {
                Log.d(TAG, "Collage created: $newUri")
                """{"success": true, "newImageUri": "$newUri", "message": "Collage '${args.title}' created successfully"}"""
            } else {
                """{"error": "Failed to create collage"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating collage", e)
            """{"error": "Failed to create collage: ${e.message}"}"""
        }
    }

    @Serializable
    data class ApplyFilterArgs(
        @property:LLMDescription("List of photo URIs to apply filter to")
        val photoUris: List<String>,
        @property:LLMDescription("Name of the filter to apply. Supported: 'grayscale', 'black and white', 'b&w', 'sepia'")
        val filterName: String
    )

    @Tool
    @LLMDescription("Applies a visual filter to photos and saves them as new images. Supported filters: grayscale, sepia.")
    suspend fun applyFilter(args: ApplyFilterArgs): String {
        return try {
            if (args.photoUris.isEmpty()) {
                return """{"error": "No photos provided to apply filter"}"""
            }
            val newUris = galleryTools.applyFilter(args.photoUris, args.filterName)
            Log.d(TAG, "Filter '${args.filterName}' applied to ${newUris.size} photos")
            """{"success": true, "newImageUris": ${newUris.map { "\"$it\"" }}, "count": ${newUris.size}, "message": "Applied ${args.filterName} filter to ${newUris.size} photos"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error applying filter", e)
            """{"error": "Failed to apply filter: ${e.message}"}"""
        }
    }

    @Serializable
    data class GetAlbumsArgs(
        @property:LLMDescription("Optional parameter (not used, included for future expansion)")
        val dummy: String = ""
    )

    @Tool
    @LLMDescription("Lists all photo albums/folders in the gallery with their photo counts.")
    suspend fun getAlbums(args: GetAlbumsArgs = GetAlbumsArgs()): String {
        return try {
            val albums = galleryTools.getAlbums()
            val albumsJson = albums.joinToString(",") { album ->
                """{"name": "${album.name}", "photoCount": ${album.photoCount}}"""
            }
            Log.d(TAG, "Retrieved ${albums.size} albums")
            """{"albums": [$albumsJson], "count": ${albums.size}}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting albums", e)
            """{"error": "Failed to get albums: ${e.message}"}"""
        }
    }

    @Serializable
    data class GetPhotosArgs(
        @property:LLMDescription("Page number for pagination (starts at 0)")
        val page: Int = 0,
        @property:LLMDescription("Number of photos per page")
        val pageSize: Int = 50
    )

    @Tool
    @LLMDescription("Gets a paginated list of all photos in the gallery, sorted by date taken (newest first).")
    suspend fun getPhotos(args: GetPhotosArgs): String {
        return try {
            val photos = galleryTools.getPhotos(args.page, args.pageSize)
            Log.d(TAG, "Retrieved ${photos.size} photos for page ${args.page}")
            """{"photoUris": ${photos.map { "\"$it\"" }}, "count": ${photos.size}, "page": ${args.page}}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting photos", e)
            """{"error": "Failed to get photos: ${e.message}"}"""
        }
    }

    @Serializable
    data class GetPhotosForAlbumArgs(
        @property:LLMDescription("Name of the album to get photos from")
        val albumName: String,
        @property:LLMDescription("Page number for pagination (starts at 0)")
        val page: Int = 0,
        @property:LLMDescription("Number of photos per page")
        val pageSize: Int = 50
    )

    @Tool
    @LLMDescription("Gets photos from a specific album, with pagination support.")
    suspend fun getPhotosForAlbum(args: GetPhotosForAlbumArgs): String {
        return try {
            val photos = galleryTools.getPhotosForAlbum(args.albumName, args.page, args.pageSize)
            Log.d(TAG, "Retrieved ${photos.size} photos from album '${args.albumName}'")
            """{"photoUris": ${photos.map { "\"$it\"" }}, "count": ${photos.size}, "albumName": "${args.albumName}", "page": ${args.page}}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting photos for album", e)
            """{"error": "Failed to get photos for album: ${e.message}"}"""
        }
    }
}
