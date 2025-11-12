package com.example.lamforgallery.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.lamforgallery.tools.GalleryTools
import com.example.lamforgallery.database.AppDatabase
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.ImageEncoder
import com.example.lamforgallery.ml.TextEncoder

import com.google.gson.Gson

/**
 * A single, unified ViewModel Factory for the entire application.
 * This factory provides all necessary dependencies (like GalleryTools)
 * as singletons to any ViewModel that requests them.
 */
class ViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    // --- Create Singleton Dependencies ---
    // These are created once and shared by all ViewModels

    private val gson: Gson by lazy {
        Gson()
    }

    private val appDatabase: AppDatabase by lazy {
        AppDatabase.getDatabase(application)
    }

    private val imageEmbeddingDao: ImageEmbeddingDao by lazy {
        appDatabase.imageEmbeddingDao()
    }

    private val imageDao by lazy {
        appDatabase.imageDao()
    }

    private val clipTokenizer: ClipTokenizer by lazy {
        ClipTokenizer(application)
    }

    private val textEncoder: TextEncoder by lazy {
        TextEncoder(application)
    }

    private val galleryTools: GalleryTools by lazy {
        GalleryTools(
            resolver = application.contentResolver,
            imageDao = imageDao,
            imageEmbeddingDao = imageEmbeddingDao,
            clipTokenizer = clipTokenizer,
            textEncoder = textEncoder
        )
    }

    // Expose for MainActivity
    fun provideGalleryTools(): GalleryTools = galleryTools
    fun provideImageDao() = imageDao

    private val imageEncoder: ImageEncoder by lazy {
        ImageEncoder(application)
    }

    // --- End Dependencies ---

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            // When AgentViewModel is requested...
            modelClass.isAssignableFrom(AgentViewModel::class.java) -> {
                AgentViewModel(application, galleryTools) as T
            }

            // When PhotosViewModel is requested...
            modelClass.isAssignableFrom(PhotosViewModel::class.java) -> {
                PhotosViewModel(galleryTools) as T
            }

            // When AlbumsViewModel is requested...
            modelClass.isAssignableFrom(AlbumsViewModel::class.java) -> {
                AlbumsViewModel(galleryTools) as T
            }

            // When SearchViewModel is requested...
            modelClass.isAssignableFrom(SearchViewModel::class.java) -> {
                SearchViewModel(galleryTools) as T
            }

            // --- ADD THIS NEW CASE ---
            modelClass.isAssignableFrom(AlbumDetailViewModel::class.java) -> {
                AlbumDetailViewModel(galleryTools) as T
            }
            // --- END NEW CASE ---
            modelClass.isAssignableFrom(EmbeddingViewModel::class.java) -> {
                EmbeddingViewModel(application, imageEmbeddingDao, imageEncoder) as T
            }

            else -> {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}