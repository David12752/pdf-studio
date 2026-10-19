@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pdfsigner.app.domain.model.ExportFormat
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Service to export files to the device's public storage (MediaStore).
 * Ensures that we return a content:// URI that can be shared with other apps.
 */
actual class MediaStoreExporter actual constructor(private val context: Context) {

    /** PlatformUri (= Uri) overload required by expect class. Converts to File internally. */
    actual fun savePdf(sourceUri: Uri, folderName: String, fileName: String): Uri {
        val sourceFile = if (sourceUri.scheme == "file") {
            File(sourceUri.path!!)
        } else {
            val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(sourceUri)?.use { it.copyTo(tmp.outputStream()) }
            tmp
        }
        return savePdf(sourceFile, folderName, fileName)
    }

    fun savePdf(sourceFile: File, subFolder: String = "PDF Studio", fileName: String): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subFolder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subFolder)
                dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, fileName).absolutePath)
            }
        }

        val resolver = context.contentResolver
        // Use MediaStore.Downloads for PDFs on Android Q+, or external storage for older
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val itemUri = resolver.insert(collection, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            return itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
    }

    actual fun saveBitmap(bitmap: Bitmap, fileName: String, format: ExportFormat): Uri {
        val extension = if (format == ExportFormat.PNG) "png" else "jpg"
        val mimeType = if (format == ExportFormat.PNG) "image/png" else "image/jpeg"
        val compressFormat = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (format == ExportFormat.PNG) 100 else 90

        val finalFileName = "$fileName.$extension"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                 val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                 val imageFile = File(imagesDir, finalFileName)
                 put(MediaStore.MediaColumns.DATA, imageFile.absolutePath)
            }
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        
        val itemUri = resolver.insert(collection, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                bitmap.compress(compressFormat, quality, output)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            return itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
    }

    fun saveTextFile(sourceFile: File, subFolder: String = "PDF Studio", fileName: String): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subFolder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subFolder)
                dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, fileName).absolutePath)
            }
        }
        val resolver   = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else
            MediaStore.Files.getContentUri("external")

        val itemUri = resolver.insert(collection, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                FileInputStream(sourceFile).use { it.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            return itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
    }
}
