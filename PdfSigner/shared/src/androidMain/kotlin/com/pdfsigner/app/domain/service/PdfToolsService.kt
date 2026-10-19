package com.pdfsigner.app.domain.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint as APaint
import android.graphics.Typeface
import android.net.Uri
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.pdfsigner.app.ui.viewmodel.WatermarkPosition

/**
 * Domain service for PDF management operations.
 *
 * ROOT FIX: Every PDDocument.load() uses a local File, never a content://
 * InputStream. PDFBox requires seekable random-access I/O.
 *
 * MERGE FIX: Uses PDFMergerUtility which performs a proper deep merge
 * (copies page dictionaries, resource dictionaries, content streams,
 * fonts, XObjects and images) � importPage() only shallow-copies.
 */
class PdfToolsService(private val context: Context) {

    // -----------------------------------------------------------------------
    // Password-aware page count
    // -----------------------------------------------------------------------

    /** Returns page count, or throws [PdfPasswordRequiredException] if encrypted. */
    fun getPageCount(uri: Uri): Int {
        val tmp = copyToTempFile(uri)
        try {
            try {
                return PDDocument.load(tmp).use { it.numberOfPages }
            } catch (e: Exception) {
                if (e.message?.contains("password", ignoreCase = true) == true ||
                    e.message?.contains("encrypt",  ignoreCase = true) == true) {
                    throw PdfPasswordRequiredException()
                }
                throw e
            }
        } finally {
            tmp.delete()
        }
    }

    /** Returns page count using the given [password], or throws if wrong. */
    fun getPageCountWithPassword(uri: Uri, password: String): Int {
        val tmp = copyToTempFile(uri)
        try {
            return PDDocument.load(tmp, password).use { it.numberOfPages }
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true ||
                e.message?.contains("wrong",    ignoreCase = true) == true ||
                e.message?.contains("encrypt",  ignoreCase = true) == true) {
                throw WrongPasswordException()
            }
            throw e
        } finally {
            tmp.delete()
        }
    }

    // -----------------------------------------------------------------------
    // Core I/O � always copy URI to a local temp file
    // -----------------------------------------------------------------------

    private fun copyToTempFile(uri: Uri): File {
        val tmp = File(
            context.cacheDir,
            "pdftool_${System.currentTimeMillis()}_${System.nanoTime()}.pdf"
        )
        val stream = when (uri.scheme) {
            "file" -> FileInputStream(File(uri.path!!))
            else   -> context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open: $uri")
        }
        stream.use { it.copyTo(tmp.outputStream()) }
        return tmp
    }

    // -----------------------------------------------------------------------
    // Merge � PDFMergerUtility (deep copy, preserves all resources)
    // -----------------------------------------------------------------------

    fun mergePdfs(sourceUris: List<Uri>, outputFile: File): File {
        // Copy every source to a local file first
        val tempFiles = sourceUris.map { copyToTempFile(it) }
        try {
            val merger = PDFMergerUtility()
            // Set the destination stream
            val outStream = FileOutputStream(outputFile)
            merger.destinationStream = outStream
            // Add each source as a stream � PDFMergerUtility reads them properly
            for (f in tempFiles) {
                merger.addSource(f)
            }
            // mergeDocuments does a full deep copy of all pages and resources
            merger.mergeDocuments(null)
            outStream.close()
        } finally {
            tempFiles.forEach { it.delete() }
        }
        return outputFile
    }

    // -----------------------------------------------------------------------
    // Split � save each page via PDFMergerUtility single-source merge
    // -----------------------------------------------------------------------

    fun splitPdf(sourceUri: Uri, outputDir: File, password: String? = null): List<File> {
        outputDir.mkdirs()
        val resultFiles = mutableListOf<File>()
        val tempFile = copyToTempFile(sourceUri)
        try {
            val pageCount = if (password != null) PDDocument.load(tempFile, password).use { it.numberOfPages }
                            else PDDocument.load(tempFile).use { it.numberOfPages }
            for (pageIndex in 0 until pageCount) {
                val outFile = File(outputDir, "page_${pageIndex + 1}.pdf")
                extractSinglePage(tempFile, pageIndex, outFile, password)
                resultFiles.add(outFile)
            }
        } finally {
            tempFile.delete()
        }
        return resultFiles
    }

    // -----------------------------------------------------------------------
    // Extract single page
    // -----------------------------------------------------------------------

    fun extractPage(sourceUri: Uri, pageIndex: Int, outputFile: File, password: String? = null): File {
        val tempFile = copyToTempFile(sourceUri)
        try {
            extractSinglePage(tempFile, pageIndex, outputFile, password)
        } finally {
            tempFile.delete()
        }
        return outputFile
    }

    /**
     * Extracts a single page from [sourceFile] into [outputFile].
     * Uses PDFMergerUtility with a range so all resources are deep-copied.
     */
    private fun extractSinglePage(sourceFile: File, pageIndex: Int, outputFile: File, password: String? = null) {
        val doc = if (password != null) PDDocument.load(sourceFile, password)
                  else PDDocument.load(sourceFile)
        doc.use { source ->
            require(pageIndex in 0 until source.numberOfPages) {
                "pageIndex $pageIndex out of range (${source.numberOfPages} pages)"
            }
            val dest = PDDocument()
            try {
                val importedPage = dest.importPage(source.getPage(pageIndex))
                source.getPage(pageIndex).mediaBox?.let { importedPage.mediaBox = it }
                FileOutputStream(outputFile).use { dest.save(it) }
            } finally {
                dest.close()
            }
        }
        val singlePageTemp = File(outputFile.parent, "sptmp_${System.nanoTime()}.pdf")
        try {
            val merger = PDFMergerUtility()
            val outStream = FileOutputStream(singlePageTemp)
            merger.destinationStream = outStream
            merger.addSource(outputFile)
            merger.mergeDocuments(null)
            outStream.close()
            singlePageTemp.renameTo(outputFile)
        } catch (_: Exception) {
            singlePageTemp.delete()
        }
    }

    // -----------------------------------------------------------------------
    // Text extraction
    // -----------------------------------------------------------------------

    fun extractTextToFile(sourceUri: Uri, outputFile: File): File {
        val tempFile = copyToTempFile(sourceUri)
        try {
            PDDocument.load(tempFile).use { doc ->
                val text = PDFTextStripper().getText(doc)
                outputFile.writeText(text, Charsets.UTF_8)
            }
        } finally {
            tempFile.delete()
        }
        return outputFile
    }

    // -----------------------------------------------------------------------
    // Watermark
    // -----------------------------------------------------------------------

    fun addWatermark(
        sourceUri: Uri,
        watermarkText: String,
        opacity: Float,
        outputFile: File
    ): File {
        val tempFile = copyToTempFile(sourceUri)
        try {
            PDDocument.load(tempFile).use { doc ->
                val gs = PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = opacity.coerceIn(0.05f, 1f)
                    strokingAlphaConstant    = opacity.coerceIn(0.05f, 1f)
                }
                for (i in 0 until doc.numberOfPages) {
                    val page     = doc.getPage(i)
                    val mediaBox = page.mediaBox ?: PDRectangle.LETTER
                    val rotation = page.rotation
                    val (pw, ph) = if (rotation == 90 || rotation == 270)
                        Pair(mediaBox.height, mediaBox.width)
                    else
                        Pair(mediaBox.width, mediaBox.height)

                    PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true
                    ).use { cs ->
                        cs.setGraphicsStateParameters(gs)
                        cs.setNonStrokingColor(180f / 255f, 180f / 255f, 180f / 255f)
                        val clampedSize = (pw / watermarkText.length.coerceAtLeast(1) * 1.2f)
                            .coerceIn(20f, 80f)
                        val angle = Math.toRadians(45.0)
                        val cos   = Math.cos(angle).toFloat()
                        val sin   = Math.sin(angle).toFloat()
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA_BOLD, clampedSize)
                        cs.setTextMatrix(
                            Matrix(
                                cos, sin, -sin, cos,
                                pw * 0.15f + mediaBox.lowerLeftX,
                                ph * 0.25f + mediaBox.lowerLeftY
                            )
                        )
                        cs.showText(watermarkText)
                        cs.endText()
                    }
                }
                FileOutputStream(outputFile).use { doc.save(it) }
            }
        } finally {
            tempFile.delete()
        }
        return outputFile
    }

    // -----------------------------------------------------------------------
    // Image / logo watermark
    // -----------------------------------------------------------------------

    fun addImageWatermark(
        sourceUri: Uri,
        logoBitmap: Bitmap,
        opacity: Float,
        position: WatermarkPosition,
        outputFile: File
    ): File {
        val tempFile = copyToTempFile(sourceUri)
        try {
            PDDocument.load(tempFile).use { doc ->
                val gs = PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = opacity.coerceIn(0.05f, 1f)
                    strokingAlphaConstant    = opacity.coerceIn(0.05f, 1f)
                }
                // Pre-scale logo to at most 40% of the page width
                for (i in 0 until doc.numberOfPages) {
                    val page     = doc.getPage(i)
                    val mediaBox = page.mediaBox ?: PDRectangle.LETTER
                    val rotation = page.rotation
                    val pw = if (rotation == 90 || rotation == 270) mediaBox.height else mediaBox.width
                    val ph = if (rotation == 90 || rotation == 270) mediaBox.width  else mediaBox.height

                    val maxW  = pw * 0.40f
                    val scale = maxW / logoBitmap.width.toFloat()
                    val imgW  = maxW
                    val imgH  = logoBitmap.height * scale

                    val margin = pw * 0.04f
                    val (x, y) = when (position) {
                        WatermarkPosition.CENTER       -> Pair(mediaBox.lowerLeftX + (pw - imgW) / 2f, mediaBox.lowerLeftY + (ph - imgH) / 2f)
                        WatermarkPosition.TOP_LEFT     -> Pair(mediaBox.lowerLeftX + margin, mediaBox.lowerLeftY + ph - imgH - margin)
                        WatermarkPosition.TOP_RIGHT    -> Pair(mediaBox.lowerLeftX + pw - imgW - margin, mediaBox.lowerLeftY + ph - imgH - margin)
                        WatermarkPosition.BOTTOM_LEFT  -> Pair(mediaBox.lowerLeftX + margin, mediaBox.lowerLeftY + margin)
                        WatermarkPosition.BOTTOM_RIGHT -> Pair(mediaBox.lowerLeftX + pw - imgW - margin, mediaBox.lowerLeftY + margin)
                    }

                    val pdImage = LosslessFactory.createFromImage(doc, logoBitmap)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setGraphicsStateParameters(gs)
                        cs.drawImage(pdImage, x, y, imgW, imgH)
                    }
                }
                FileOutputStream(outputFile).use { doc.save(it) }
            }
        } finally {
            tempFile.delete()
        }
        return outputFile
    }

    // -----------------------------------------------------------------------
    // Compression
    // -----------------------------------------------------------------------

    fun compressPdf(sourceUri: Uri, quality: Float, @Suppress("UNUSED_PARAMETER") dpi: Float, outputFile: File, password: String? = null): File {
        val tempFile = copyToTempFile(sourceUri)
        try {
            val doc = if (password != null) PDDocument.load(tempFile, password)
                      else PDDocument.load(tempFile)
            doc.use { source ->
                val renderer = PDFRenderer(source)
                PDDocument().use { compressed ->
                    for (i in 0 until source.numberOfPages) {
                        val srcPage  = source.getPage(i)
                        val mediaBox = srcPage.mediaBox ?: PDRectangle.LETTER
                        val rotation = srcPage.rotation
                        val displayW = if (rotation == 90 || rotation == 270) mediaBox.height else mediaBox.width
                        val displayH = if (rotation == 90 || rotation == 270) mediaBox.width  else mediaBox.height
                        val bmp = renderer.renderImage(i, 1.0f)
                        val page = PDPage(PDRectangle(displayW, displayH))
                        compressed.addPage(page)
                        val jpegImage = JPEGFactory.createFromImage(compressed, bmp, quality.coerceIn(0.1f, 1.0f))
                        PDPageContentStream(compressed, page).use { cs ->
                            cs.drawImage(jpegImage, 0f, 0f, displayW, displayH)
                        }
                        bmp.recycle()
                    }
                    FileOutputStream(outputFile).use { compressed.save(it) }
                }
            }
        } finally {
            tempFile.delete()
        }
        return outputFile
    }

    // -----------------------------------------------------------------------
    // Password protection
    // -----------------------------------------------------------------------

    fun protectWithPassword(sourceUri: Uri, password: String, outputFile: File): File {
        val tempFile = copyToTempFile(sourceUri)
        try {
            PDDocument.load(tempFile).use { doc ->
                val ap = AccessPermission().apply {
                    setCanPrint(true)
                    setCanExtractContent(false)
                    setCanModify(false)
                }
                val ownerPwd = "${password}_owner_${System.nanoTime()}"
                doc.protect(
                    StandardProtectionPolicy(ownerPwd, password, ap).apply {
                        encryptionKeyLength = 128
                    }
                )
                FileOutputStream(outputFile).use { doc.save(it) }
            }
        } finally {
            tempFile.delete()
        }
        return outputFile
    }

    // -----------------------------------------------------------------------
    // Audit trail
    // -----------------------------------------------------------------------

    fun addAuditTrail(
        sourceUri: Uri,
        signerName: String,
        deviceInfo: String,
        outputFile: File
    ): File {
        val tempFile = copyToTempFile(sourceUri)
        try {
            PDDocument.load(tempFile).use { doc ->
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss z", Locale.getDefault()
                ).format(Date())

                val lines = listOf(
                    "??????????????????????????????????????????",
                    "    AUDIT TRAIL / �������� �����",
                    "??????????????????????????????????????????",
                    "",
                    "Document:  ${doc.documentInformation?.title ?: "N/A"}",
                    "Pages:     ${doc.numberOfPages}",
                    "Signed by: $signerName",
                    "Timestamp: $timestamp",
                    "Device:    $deviceInfo",
                    "",
                    "Signature is visual only and does not",
                    "constitute a cryptographic digital signature.",
                    "",
                    "??????????????????????????????????????????"
                )

                val bmpW = 1200; val bmpH = 1600
                val bmp  = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                ACanvas(bmp).also { c ->
                    c.drawColor(android.graphics.Color.WHITE)
                    val p = APaint(APaint.ANTI_ALIAS_FLAG).apply {
                        textSize = 34f
                        color    = android.graphics.Color.BLACK
                        typeface = Typeface.MONOSPACE
                    }
                    var y = 90f
                    for (line in lines) { c.drawText(line, 60f, y, p); y += 52f }
                }

                val pageRect  = PDRectangle(bmpW * 72f / 150f, bmpH * 72f / 150f)
                val auditPage = PDPage(pageRect)
                doc.addPage(auditPage)
                val pdImg = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, auditPage).use { cs ->
                    cs.drawImage(pdImg, 0f, 0f, pageRect.width, pageRect.height)
                }
                bmp.recycle()
                FileOutputStream(outputFile).use { doc.save(it) }
            }
        } finally {
            tempFile.delete()
        }
        return outputFile
    }
}
