@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pdfsigner.app.domain.model.SavedSignature
import java.io.File
import java.io.FileOutputStream

/**
 * Persists and retrieves saved signature bitmaps on the device's internal storage.
 * Files are stored as PNG in [context.filesDir]/signatures/.
 * Keeps a maximum of [MAX_SAVED] entries, by modification time (newest first).
 */
actual class SavedSignaturesManager actual constructor(private val context: Context) {

    companion object {
        private const val DIR_NAME  = "signatures"
        private const val MAX_SAVED = 5
    }

    private val sigDir: File
        get() = File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /** Returns all saved signature bitmaps, newest first. */
    actual fun getAll(): List<SavedSignature> {
        return sigDir.listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                val bmp = runCatching {
                    BitmapFactory.decodeFile(file.absolutePath)
                }.getOrNull() ?: return@mapNotNull null
                SavedSignature(id = file.nameWithoutExtension, bitmap = bmp)
            } ?: emptyList()
    }

    /** Saves [bitmap] and trims to [MAX_SAVED]. Returns the id. */
    actual fun save(bitmap: Bitmap): String {
        val id   = System.currentTimeMillis().toString()
        val file = File(sigDir, "$id.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        // Trim oldest if over limit
        val all = sigDir.listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (all.size > MAX_SAVED) {
            all.drop(MAX_SAVED).forEach { it.delete() }
        }
        return id
    }

    /** Deletes the signature with the given [id]. */
    actual fun delete(id: String) {
        File(sigDir, "$id.png").delete()
    }
}
