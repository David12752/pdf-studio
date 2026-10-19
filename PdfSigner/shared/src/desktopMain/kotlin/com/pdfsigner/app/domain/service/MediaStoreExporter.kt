@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.ExportFormat
import com.pdfsigner.app.domain.model.PlatformBitmap
import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.PlatformUri
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

actual class MediaStoreExporter actual constructor(private val context: PlatformContext) {

    /**
     * Copies a PDF file to `~/Documents/PDF Studio/<folderName>/` and returns
     * a [PlatformUri] pointing to the saved file.
     */
    actual fun savePdf(sourceUri: PlatformUri, folderName: String, fileName: String): PlatformUri {
        val destDir = buildExportDir(folderName)
        val destFile = uniqueFile(destDir, fileName)
        sourceUri.file.copyTo(destFile, overwrite = true)
        return PlatformUri(destFile)
    }

    /**
     * Saves a [BufferedImage] to `~/Documents/PDF Studio/<folderName>/` and
     * returns a [PlatformUri] pointing to the saved image.
     */
    actual fun saveBitmap(bitmap: PlatformBitmap, fileName: String, format: ExportFormat): PlatformUri {
        val folderName = when (format) {
            ExportFormat.PDF -> "PDF Studio"
            ExportFormat.PNG -> "PDF Studio/Exports"
            ExportFormat.JPG -> "PDF Studio/Exports"
        }
        val ext    = if (format == ExportFormat.PNG) "png" else "jpg"
        val ioName = if (format == ExportFormat.PNG) "PNG"  else "JPEG"

        // JPEG doesn't support alpha — convert to TYPE_INT_RGB
        val toWrite: BufferedImage = if (format == ExportFormat.JPG && bitmap.colorModel.hasAlpha()) {
            val flat = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_RGB)
            val g = flat.createGraphics()
            g.fillRect(0, 0, bitmap.width, bitmap.height)
            g.drawImage(bitmap, 0, 0, null)
            g.dispose()
            flat
        } else bitmap

        val destDir  = buildExportDir(folderName)
        val baseName = fileName.substringBeforeLast(".", fileName)
        val destFile = uniqueFile(destDir, "$baseName.$ext")
        ImageIO.write(toWrite, ioName, destFile)
        return PlatformUri(destFile)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildExportDir(folderName: String): File {
        val docsDir = System.getProperty("user.home")?.let { File(it, "Documents") }
            ?: File(System.getProperty("user.home") ?: ".", "Documents")
        val dir = File(docsDir, folderName)
        dir.mkdirs()
        return dir
    }

    /** Returns a non-conflicting file in [dir]. */
    private fun uniqueFile(dir: File, fileName: String): File {
        val candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val baseName = fileName.substringBeforeLast(".")
        val ext      = fileName.substringAfterLast(".", "")
        var i = 1
        while (true) {
            val next = File(dir, if (ext.isEmpty()) "${baseName}_$i" else "${baseName}_$i.$ext")
            if (!next.exists()) return next
            i++
        }
    }
}
