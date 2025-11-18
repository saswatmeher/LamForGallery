package com.example.lamforgallery.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImage(image: ImageEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<ImageEntity>)
    
    @Update
    suspend fun updateImage(image: ImageEntity)
    
    @Query("SELECT * FROM images WHERE isTrashed = 0 ORDER BY dateTaken DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllImages(limit: Int, offset: Int): List<ImageEntity>
    
    @Query("SELECT * FROM images WHERE albumName = :albumName AND isTrashed = 0 ORDER BY dateTaken DESC LIMIT :limit OFFSET :offset")
    suspend fun getImagesByAlbum(albumName: String, limit: Int, offset: Int): List<ImageEntity>
    
    @Query("SELECT * FROM images WHERE isTrashed = 1 ORDER BY trashedTimestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getTrashedImages(limit: Int, offset: Int): List<ImageEntity>
    
    @Query("SELECT COUNT(*) FROM images WHERE isTrashed = 1")
    suspend fun getTrashedCount(): Int
    
    @Query("UPDATE images SET isTrashed = :isTrashed, trashedTimestamp = :timestamp WHERE uri IN (:uris)")
    suspend fun updateTrashStatus(uris: List<String>, isTrashed: Boolean, timestamp: Long)
    
    @Query("UPDATE images SET isTrashed = 0, trashedTimestamp = 0 WHERE isTrashed = 1")
    suspend fun restoreAllFromTrash()
    
    @Query("UPDATE images SET albumName = :albumName WHERE uri IN (:uris)")
    suspend fun updateAlbum(uris: List<String>, albumName: String)
    
    @Query("SELECT DISTINCT albumName, COUNT(*) as photoCount FROM images WHERE isTrashed = 0 GROUP BY albumName")
    suspend fun getAlbumsWithCount(): List<AlbumInfo>
    
    @Query("SELECT uri FROM images WHERE uri = :uri")
    suspend fun getImageByUri(uri: String): String?
    
    @Query("DELETE FROM images WHERE uri IN (:uris)")
    suspend fun deleteImages(uris: List<String>)
    
    @Query("SELECT COUNT(*) FROM images")
    suspend fun getImageCount(): Int
    
    @Query("DELETE FROM images")
    suspend fun clearAllImages()
    
    @Query("SELECT * FROM images WHERE displayName LIKE :query AND isTrashed = 0 ORDER BY dateTaken DESC")
    suspend fun searchImages(query: String): List<ImageEntity>
}

data class AlbumInfo(
    val albumName: String,
    val photoCount: Int
)
