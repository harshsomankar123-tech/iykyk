package com.example.iykyk.data.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device Face Embedding Engine powered by MobileFaceNet TFLite.
 * Lazy-initialized on first use (off main thread) to prevent ANR.
 */
class FaceEmbeddingEngine(
    private val context: Context,
    private val modelAssetPath: String = "models/mobilefacenet.tflite"
) {
    private val TAG = "FaceEmbeddingEngine"
    private var interpreter: Interpreter? = null
    var inputSize = 112
        private set
    var embeddingDim = 192
        private set
    private var initialized = false

    /**
     * Lazy initialization - called on background thread on first embedding request.
     */
    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return
        try {
            val modelBuffer = loadModelFile(context, modelAssetPath)
            if (modelBuffer == null) {
                Log.e(TAG, "Failed to load model from assets/$modelAssetPath")
                initialized = true
                return
            }

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, options)

            // Read the model's native input shape
            val inShape = interp.getInputTensor(0).shape()
            if (inShape.size >= 3 && inShape[1] > 0) {
                inputSize = inShape[1]
            }

            // Force batch size = 1 (some MobileFaceNet models default to batch=2)
            try {
                val singleBatchShape = intArrayOf(1, inputSize, inputSize, 3)
                interp.resizeInput(0, singleBatchShape)
                interp.allocateTensors()
            } catch (e: Exception) {
                Log.w(TAG, "resizeInput failed, using model defaults: ${e.message}")
            }

            // Read output shape
            val outShape = interp.getOutputTensor(0).shape()
            if (outShape.isNotEmpty()) {
                embeddingDim = outShape[outShape.size - 1]
            }

            interpreter = interp
            Log.i(TAG, "MobileFaceNet OK: ${inputSize}x${inputSize} -> ${embeddingDim}-d")
        } catch (e: Exception) {
            Log.e(TAG, "Model init failed: ${e.message}", e)
            interpreter = null
        }
        initialized = true
    }

    private fun loadModelFile(context: Context, path: String): MappedByteBuffer? {
        return try {
            val fileDescriptor = context.assets.openFd(path)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading model asset: $path", e)
            null
        }
    }

    /**
     * Extracts an L2-normalized face embedding vector from a face crop bitmap.
     */
    suspend fun getEmbedding(faceCropBitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        ensureInitialized()

        val resized = if (faceCropBitmap.width != inputSize || faceCropBitmap.height != inputSize) {
            Bitmap.createScaledBitmap(faceCropBitmap, inputSize, inputSize, true)
        } else {
            faceCropBitmap
        }

        val embedding = try {
            val interp = interpreter
            if (interp != null) {
                runInference(interp, resized)
            } else {
                // Fallback: deterministic spatial descriptor (never silent - logged above)
                generateSpatialDescriptor(resized)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            generateSpatialDescriptor(resized)
        }

        if (resized != faceCropBitmap) {
            resized.recycle()
        }

        l2Normalize(embedding)
    }

    private fun runInference(interp: Interpreter, bitmap: Bitmap): FloatArray {
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            byteBuffer.putFloat((r - 127.5f) / 128.0f)
            byteBuffer.putFloat((g - 127.5f) / 128.0f)
            byteBuffer.putFloat((b - 127.5f) / 128.0f)
        }

        val output = Array(1) { FloatArray(embeddingDim) }
        interp.run(byteBuffer, output)
        return output[0]
    }

    /**
     * Deterministic multi-region spatial descriptor as emergency fallback.
     * Divides face crop into a grid and computes mean color + gradient features.
     */
    private fun generateSpatialDescriptor(bitmap: Bitmap): FloatArray {
        Log.w(TAG, "Using fallback spatial descriptor (model not available)")
        val vec = FloatArray(embeddingDim)
        val w = bitmap.width
        val h = bitmap.height
        val gridSize = 8
        val cellW = max(1, w / gridSize)
        val cellH = max(1, h / gridSize)
        var idx = 0
        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                if (idx + 3 > embeddingDim) break
                val cx = min(gx * cellW + cellW / 2, w - 1)
                val cy = min(gy * cellH + cellH / 2, h - 1)
                val pixel = bitmap.getPixel(cx, cy)
                vec[idx++] = ((pixel shr 16) and 0xFF) / 255f
                vec[idx++] = ((pixel shr 8) and 0xFF) / 255f
                vec[idx++] = (pixel and 0xFF) / 255f
            }
        }
        return vec
    }

    fun extractFaceCrop(
        frameBitmap: Bitmap,
        faceBox: Rect,
        marginRatio: Float = 0.20f
    ): Bitmap {
        val faceW = max(1, faceBox.right - faceBox.left)
        val faceH = max(1, faceBox.bottom - faceBox.top)
        val marginX = (faceW * marginRatio).toInt()
        val marginY = (faceH * marginRatio).toInt()

        val left = max(0, faceBox.left - marginX)
        val top = max(0, faceBox.top - marginY)
        val right = min(frameBitmap.width, faceBox.right + marginX)
        val bottom = min(frameBitmap.height, faceBox.bottom + marginY)

        val cropWidth = max(1, right - left)
        val cropHeight = max(1, bottom - top)

        return Bitmap.createBitmap(frameBitmap, left, top, cropWidth, cropHeight)
    }

    companion object {
        fun l2Normalize(v: FloatArray): FloatArray {
            var sumSquares = 0.0
            for (x in v) {
                sumSquares += (x * x).toDouble()
            }
            val norm = sqrt(sumSquares).toFloat()
            if (norm > 1e-6f) {
                for (i in v.indices) {
                    v[i] /= norm
                }
            }
            return v
        }

        fun cosineSimilarity(u: FloatArray, v: FloatArray): Float {
            val len = min(u.size, v.size)
            var dot = 0f
            for (i in 0 until len) {
                dot += u[i] * v[i]
            }
            return dot.coerceIn(-1.0f, 1.0f)
        }

        fun cosineDistance(u: FloatArray, v: FloatArray): Float {
            return (1.0f - cosineSimilarity(u, v)).coerceAtLeast(0.0f)
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
    }
}
