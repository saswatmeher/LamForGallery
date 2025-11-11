package com.example.lamforgallery.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

/**
 * Handles loading the CLIP image encoder model and running inference.
 *
 * This class takes a Bitmap, processes it into the required [1, 3, 224, 224] tensor,
 * and returns a 512-dimension embedding as a FloatArray.
 */
class ImageEncoder(private val context: Context) { // <-- Made context a private val

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    private val inputName: String

    companion object {
        private const val MODEL_FILE = "clip-image-int8.ort"
        private const val TAG = "ImageEncoder"

        // Model input dimensions
        private const val DIM_BATCH_SIZE = 1
        private const val DIM_CHANNELS = 3
        private const val DIM_IMG_HEIGHT = 224
        private const val DIM_IMG_WIDTH = 224
        private const val TENSOR_SIZE = DIM_BATCH_SIZE * DIM_CHANNELS * DIM_IMG_HEIGHT * DIM_IMG_WIDTH

        // Normalization constants for the CLIP model
        private val NORM_MEAN_R = (0.48145466 * 255).toFloat()
        private val NORM_MEAN_G = (0.4578275 * 255).toFloat()
        private val NORM_MEAN_B = (0.40821073 * 255).toFloat()

        private val NORM_STD_R = (0.26862954 * 255).toFloat()
        private val NORM_STD_G = (0.26130258 * 255).toFloat()
        private val NORM_STD_B = (0.27577711 * 255).toFloat()
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

        inputName = ortSession.inputNames.first()

        Log.d(TAG, "ONNX image encoder session created.")
        Log.d(TAG, "Input name: $inputName")
        Log.d(TAG, "Output names: ${ortSession.outputNames.joinToString()}")
    }

    /**
     * Pre-processes a Bitmap and converts it to a FloatBuffer.
     * The model requires a "planar" NCHW format: [1, 3, 224, 224].
     * This means we store all Red pixels first, then all Green, then all Blue.
     *
     * @param bitmap The input Bitmap, which will be scaled to 224x224.
     * @return A FloatBuffer containing the normalized pixel data.
     */
    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        // 1. Resize the bitmap to the model's required input size
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, DIM_IMG_WIDTH, DIM_IMG_HEIGHT, true)

        // 2. Get all pixels from the scaled bitmap
        val pixelCount = DIM_IMG_WIDTH * DIM_IMG_HEIGHT
        val pixels = IntArray(pixelCount)
        scaledBitmap.getPixels(pixels, 0, DIM_IMG_WIDTH, 0, 0, DIM_IMG_WIDTH, DIM_IMG_HEIGHT)

        // 3. Create the FloatBuffer
        val buffer = FloatBuffer.allocate(TENSOR_SIZE)
        buffer.rewind()

        // 4. Iterate through pixels and normalize, writing to the buffer in planar format
        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            // Extract ARGB components
            val r = (pixel shr 16 and 0xFF).toFloat()
            val g = (pixel shr 8 and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()

            // Normalize and write to buffer in NCHW format
            // R plane
            buffer.put(i, (r - NORM_MEAN_R) / NORM_STD_R)
            // G plane
            buffer.put(i + pixelCount, (g - NORM_MEAN_G) / NORM_STD_G)
            // B plane
            buffer.put(i + (2 * pixelCount), (b - NORM_MEAN_B) / NORM_STD_B)
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Encodes a Bitmap into a 512-dimension embedding.
     *
     * @param bitmap The input image to encode.
     * @return A FloatArray representing the image embedding.
     */
    fun encode(bitmap: Bitmap): FloatArray {
        // 1. Pre-process the bitmap into a normalized FloatBuffer
        val buffer = bitmapToFloatBuffer(bitmap)

        // 2. Define the tensor shape: [1, 3, 224, 224]
        val shape = longArrayOf(
            DIM_BATCH_SIZE.toLong(),
            DIM_CHANNELS.toLong(),
            DIM_IMG_HEIGHT.toLong(),
            DIM_IMG_WIDTH.toLong()
        )

        // 3. Create the input tensor
        OnnxTensor.createTensor(ortEnv, buffer, shape).use { inputTensor ->
            // 4. Run inference
            ortSession.run(mapOf(inputName to inputTensor)).use { outputs ->
                // 5. Process the output
                (outputs.first().value as OnnxTensor).use { outputTensor ->
                    // Output is [1, 512], so we get the first (and only) item
                    val embedding = (outputTensor.value as Array<FloatArray>)[0]
                    Log.d(TAG, "Image embedding generated with size: ${embedding.size}")
                    return embedding
                }
            }
        }
    }
}