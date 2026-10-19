@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.SavedSignature
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

actual class SavedSignaturesManager actual constructor(private val context: PlatformContext) {

    companion object {
        private const val DIR_NAME  = "signatures"
        private const val MAX_SAVED = 5
    }

    private val sigDir: File
        get() {
            val dir = File(
                System.getProperty("user.home") ?: ".",
                "Documents${File.separator}PDF Studio${File.separator}$DIR_NAME"
            )
            dir.mkdirs()
            return dir
        }

    actual fun getAll(): List<SavedSignature> {
        return sigDir.listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                val img = runCatching { ImageIO.read(file) }.getOrNull() ?: return@mapNotNull null
                SavedSignature(id = file.nameWithoutExtension, bitmap = img)
            } ?: emptyList()
    }

    actual fun save(bitmap: BufferedImage): String {
        val id   = System.currentTimeMillis().toString()
        val file = File(sigDir, "$id.png")
        ImageIO.write(bitmap, "PNG", file)
        val all = sigDir.listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (all.size > MAX_SAVED) {
            all.drop(MAX_SAVED).forEach { it.delete() }
        }
        return id
    }

    actual fun delete(id: String) {
        File(sigDir, "$id.png").delete()
    }
}
