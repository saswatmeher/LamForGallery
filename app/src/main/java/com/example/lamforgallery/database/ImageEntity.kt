package com.example.lamforgallery.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey
    val uri: String,
    val displayName: String,
    val albumName: String,
    val relativePath: String,
    val isTrashed: Boolean = false,
    val trashedTimestamp: Long = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val dateTaken: Long = 0
)
