package com.example.lamforgallery.ml

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.IntBuffer

/**
 * Handles loading the CLIP text encoder model and running inference.
 *
 * This class takes a list of token IDs (from ClipTokenizer) and returns
 * a 512-dimension embedding as a FloatArray.
 */
class TextEncoder(private val context: Context) { // <-- Made context a private val

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    private val inputName: String

    companion object {
        private const val MODEL_FILE = "clip-text-int8.ort"
        private const val TAG = "TextEncoder"
    }

    /**
     * Copies the ONNX model from assets to the app's cache directory.
     * This is crucial to avoid OutOfMemoryErrors when loading large models.
     *
     * @return The absolute path to the cached model file.
     */
    private fun getModelPath(): String {
        val modelFile = File(context.cacheDir, MODEL_FILE)

        // Only copy the model if it doesn't already exist in the cache
        if (!modelFile.exists()) {
            try {
                // Read from assets
                context.assets.open(MODEL_FILE).use { inputStream ->
                    // Write to cache
                    FileOutputStream(modelFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Model copied to cache: ${modelFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model to cache", e)
                throw RuntimeException("Failed to copy model to cache", e)
            }
        } else {
            Log.d(TAG, "Model already exists in cache.")
        }
        return modelFile.absolutePath
    }

    init {
        // --- START OOM FIX ---
        // Load the model from its file path instead of a byte array
        val modelPath = getModelPath()
        ortSession = ortEnv.createSession(modelPath)
        // --- END OOM FIX ---

        // Get the name of the model's input node (e.g., "input_ids")
        inputName = ortSession.inputNames.first()

        Log.d(TAG, "ONNX text encoder session created.")
        Log.d(TAG, "Input name: $inputName")
        Log.d(TAG, "Output names: ${ortSession.outputNames.joinToString()}")
    }

    /**
     * Encodes a list of 77 token IDs into a 512-dimension embedding.
     *
     * @param tokens A List<Int> of 77 token IDs from ClipTokenizer.
     * @return A FloatArray representing the text embedding.
     */
    fun encode(tokens: List<Int>): FloatArray {
        // 1. Convert the List<Int> to an IntArray
        val intArray = tokens.toIntArray()

        // 2. Create the input tensor
        // The model expects a 2D tensor of shape [batch_size, context_length]
        // For a single string, this is [1, 77]
        val shape = longArrayOf(1L, tokens.size.toLong())
        val buffer = IntBuffer.wrap(intArray)

        // We must wrap the tensor and session run in 'use' blocks
        // to ensure resources (like memory) are closed automatically.
        OnnxTensor.createTensor(ortEnv, buffer, shape).use { inputTensor ->
            // 3. Run the model
            ortSession.run(mapOf(inputName to inputTensor)).use { outputs ->

                // 4. Process the output
                // The first (and only) output is the embedding tensor
                (outputs.first().value as OnnxTensor).use { outputTensor ->

                    // The output shape is [1, 512] (batch_size, embedding_dim)
                    // We get its value, which is represented as an Array<FloatArray>
                    val embedding = (outputTensor.value as Array<FloatArray>)[0]

                    Log.d(TAG, "Embedding generated with size: ${embedding.size}")
                    return embedding
                }
            }
        }
    }
}