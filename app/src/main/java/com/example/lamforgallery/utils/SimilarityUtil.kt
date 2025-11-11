package com.example.lamforgallery.utils

import kotlin.math.sqrt

/**
 * A utility object for calculating vector similarity.
 */
object SimilarityUtil {

    /**
     * Calculates the cosine similarity between two non-zero vectors.
     *
     * @param v1 The first vector.
     * @param v2 The second vector.
     * @return The cosine similarity, a value between -1.0 and 1.0.
     * (Though for CLIP, it's typically between 0 and 1).
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) {
            throw IllegalArgumentException("Vectors must be of the same length (v1: ${v1.size}, v2: ${v2.size})")
        }

        var dotProduct = 0.0f
        var normV1 = 0.0f
        var normV2 = 0.0f

        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normV1 += v1[i] * v1[i]
            normV2 += v2[i] * v2[i]
        }

        val normProduct = sqrt(normV1) * sqrt(normV2)
        if (normProduct == 0.0f) {
            // Handle zero vectors to avoid division by zero
            return 0.0f
        }

        return dotProduct / normProduct
    }
}