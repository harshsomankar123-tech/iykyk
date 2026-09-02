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
 * Generates normalized L2 feature embedding vectors from cropped face regions.
 */
class FaceEmbeddingEngine(
    private val context: Context,
    private val modelAssetPath: String = "models/mobilefacenet.tflite"
) {
    private val TAG = "FaceEmbeddingEngine"
    private var interpreter: Interpreter
    var inputSize = 112 // MobileFaceNet standard input width/height
        private set
    var embeddingDim = 192 // MobileFaceNet output feature vector dimension
        private set

    init {
        interpreter = initInterpreter()
    }

    private fun initInterpreter(): Interpreter {
        val modelBuffer = loadModelFile(context, modelAssetPath)
            ?: throw IllegalStateException(
                "CRITICAL: Failed to load TFLite model from assets/$modelAssetPath. " +
                        "Ensure the genuine MobileFaceNet .tflite model binary exists in app/src/main/assets/$modelAssetPath."
            )

        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        val interp = Interpreter(modelBuffer, options)

        // Inspect and validate model tensor input/output shapes
        val inTensor = interp.getInputTensor(0)
        val outTensor = interp.getOutputTensor(0)

        val inShape = inTensor.shape()
        val outShape = outTensor.shape()

        if (inShape.size >= 3 && inShape[1] > 0) {
            inputSize = inShape[1]
        }
        if (outShape.isNotEmpty()) {
            embeddingDim = outShape[outShape.size - 1]
        }

        Log.i(TAG, "MobileFaceNet initialized: InputSize=${inputSize}x${inputSize}, EmbeddingDim=$embeddingDim")
        return interp
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
            Log.e(TAG, "Error reading model asset file: $path", e)
            null
        }
    }

    /**
     * Extracts an L2-normalized face embedding vector directly from a face crop bitmap.
     * Normalizes RGB pixels to [-1.0, 1.0] and executes on-device TFLite inference.
     */
    suspend fun getEmbedding(faceCropBitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val resized = if (faceCropBitmap.width != inputSize || faceCropBitmap.height != inputSize) {
            Bitmap.createScaledBitmap(faceCropBitmap, inputSize, inputSize, true)
        } else {
            faceCropBitmap
        }

        val embedding = runInference(resized)

        if (resized != faceCropBitmap) {
            resized.recycle()
        }

        l2Normalize(embedding)
    }

    private fun runInference(bitmap: Bitmap): FloatArray {
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

            // MobileFaceNet RGB normalization: [-1.0, 1.0]
            byteBuffer.putFloat((r - 127.5f) / 128.0f)
            byteBuffer.putFloat((g - 127.5f) / 128.0f)
            byteBuffer.putFloat((b - 127.5f) / 128.0f)
        }

        val output = Array(1) { FloatArray(embeddingDim) }
        interpreter.run(byteBuffer, output)
        return output[0]
    }

    /**
     * Crops the face region from a frame with a standard margin (e.g. 20%)
     * to preserve facial contours and ears before feeding to embedding extractor.
     */
    fun extractFaceCrop(
        frameBitmap: Bitmap,
        faceBox: Rect,
        marginRatio: Float = 0.20f
    ): Bitmap {
        val marginX = (faceBox.width() * marginRatio).toInt()
        val marginY = (faceBox.height() * marginRatio).toInt()

        val left = max(0, faceBox.left - marginX)
        val top = max(0, faceBox.top - marginY)
        val right = min(frameBitmap.width, faceBox.right + marginX)
        val bottom = min(frameBitmap.height, faceBox.bottom + marginY)

        val cropWidth = max(1, right - left)
        val cropHeight = max(1, bottom - top)

        return Bitmap.createBitmap(frameBitmap, left, top, cropWidth, cropHeight)
    }

    companion object {
        /**
         * Normalizes a float vector in-place to Euclidean L2 unit length.
         */
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

        /**
         * Computes Cosine Similarity between two L2-normalized vectors. Range: [-1.0, 1.0].
         */
        fun cosineSimilarity(u: FloatArray, v: FloatArray): Float {
            val len = min(u.size, v.size)
            var dot = 0f
            for (i in 0 until len) {
                dot += u[i] * v[i]
            }
            return dot.coerceIn(-1.0f, 1.0f)
        }

        /**
         * Computes Cosine Distance between two L2-normalized vectors: 1.0 - CosineSimilarity.
         * Range: [0.0, 2.0]. Lower distance = closer identity match.
         */
        fun cosineDistance(u: FloatArray, v: FloatArray): Float {
            return (1.0f - cosineSimilarity(u, v)).coerceAtLeast(0.0f)
        }
    }

    fun close() {
        try {
            interpreter.close()
        } catch (ignored: Exception) {}
    }
}
