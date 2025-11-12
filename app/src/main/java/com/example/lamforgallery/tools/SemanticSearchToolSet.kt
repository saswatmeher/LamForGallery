package com.example.lamforgallery.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import android.util.Log
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.TextEncoder
import com.example.lamforgallery.utils.SimilarityUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Koog-compatible ToolSet for semantic photo search using CLIP embeddings.
 */
@LLMDescription("Tools for semantic photo search using AI-powered image understanding")
class SemanticSearchToolSet(
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val clipTokenizer: ClipTokenizer,
    private val textEncoder: TextEncoder
) : ToolSet {

    private val TAG = "SemanticSearchToolSet"

    @Serializable
    data class SemanticSearchArgs(
        @property:LLMDescription("Natural language description of photos to search for (e.g., 'sunset at the beach', 'people smiling')")
        val query: String,
        @property:LLMDescription("Maximum number of results to return (default: 20)")
        val limit: Int = 20,
        @property:LLMDescription("Minimum similarity threshold (0.0 to 1.0, default: 0.2)")
        val threshold: Float = 0.2f
    )

    @Tool
    @LLMDescription("Searches photos using natural language descriptions. Uses AI to understand image content and find semantically matching photos.")
    suspend fun searchPhotosBySemantic(args: SemanticSearchArgs): String {
        return try {
            Log.d(TAG, "Semantic search for: ${args.query}")
            
            // Tokenize the query
            val tokenIds = clipTokenizer.tokenize(args.query)
            
            // Generate text embedding
            val queryEmbedding = withContext(Dispatchers.Default) {
                textEncoder.encode(tokenIds)
            }
            
            // Get all embeddings from database
            val allEmbeddings = withContext(Dispatchers.IO) {
                imageEmbeddingDao.getAllEmbeddings()
            }
            
            if (allEmbeddings.isEmpty()) {
                return """{"photoUris": [], "count": 0, "message": "No photos have been indexed yet. Please wait for embeddings to be generated."}"""
            }
            
            // Calculate similarities
            val results = allEmbeddings
                .map { embedding ->
                    val similarity = SimilarityUtil.cosineSimilarity(
                        queryEmbedding,
                        embedding.embedding
                    )
                    Pair(embedding.uri, similarity)
                }
                .filter { it.second >= args.threshold }
                .sortedByDescending { it.second }
                .take(args.limit)
            
            val photoUris = results.map { it.first }
            val similarities = results.map { it.second }
            
            Log.d(TAG, "Semantic search found ${results.size} matching photos")
            
            val urisJson = photoUris.joinToString(",") { "\"$it\"" }
            val simsJson = similarities.joinToString(",")
            
            """{"photoUris": [$urisJson], "similarities": [$simsJson], "count": ${results.size}, "message": "Found ${results.size} photos matching '${args.query}'"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error in semantic search", e)
            """{"error": "Failed to perform semantic search: ${e.message}"}"""
        }
    }

    @Serializable
    data class GetEmbeddingStatsArgs(
        @property:LLMDescription("Optional parameter (not used)")
        val dummy: String = ""
    )

    @Tool
    @LLMDescription("Gets statistics about indexed photos (how many photos have AI embeddings for semantic search).")
    suspend fun getEmbeddingStats(args: GetEmbeddingStatsArgs = GetEmbeddingStatsArgs()): String {
        return try {
            val count = withContext(Dispatchers.IO) {
                imageEmbeddingDao.getAllEmbeddings().size
            }
            Log.d(TAG, "Embedding stats: $count photos indexed")
            """{"indexedPhotos": $count, "message": "$count photos are indexed for semantic search"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting embedding stats", e)
            """{"error": "Failed to get embedding stats: ${e.message}"}"""
        }
    }
}
