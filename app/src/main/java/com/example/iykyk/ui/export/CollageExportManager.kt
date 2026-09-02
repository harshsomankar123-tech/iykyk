package com.example.iykyk.ui.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class CollageExportManager(private val context: Context) {

    /**
     * Saves the collage bitmap to device Public Gallery (Pictures/IYKYK) using MediaStore.
     */
    suspend fun saveToGallery(bitmap: Bitmap, filename: String = "IYKYK_Collage_${System.currentTimeMillis()}.png"): Uri? =
        withContext(Dispatchers.IO) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/IYKYK")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val imageUri = try {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (e: Exception) {
                null
            }

            imageUri?.let { uri ->
                try {
                    resolver.openOutputStream(uri)?.use { outputStream: OutputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                    uri
                } catch (e: Exception) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    null
                }
            }
        }

    /**
     * Prepares a shareable URI in cache directory and launches Android ShareSheet on Main thread.
     */
    suspend fun shareCollage(bitmap: Bitmap, title: String = "Share Story Collage") = withContext(Dispatchers.IO) {
        try {
            val cachePath = File(context.cacheDir, "shared_images")
            cachePath.mkdirs()
            val file = File(cachePath, "story_collage_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                clipData = android.content.ClipData.newRawUri("Story Collage", contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "IYKYK Story Collage")
                putExtra(Intent.EXTRA_TEXT, "✨ Created with IYKYK Unique Person Collage!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            withContext(Dispatchers.Main) {
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
