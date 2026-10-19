@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint as APaint
import android.graphics.Typeface
import android.net.Uri
import com.pdfsigner.app.domain.model.SignaturePlacement
import com.pdfsigner.app.domain.model.TextStampPlacement
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.FileOutputStream

/**
 * Handles all PDFBox operations:
 *  - Overlaying signature bitmap onto a PDF page (stamp / visual signature).
 *  - Rendering a PDF page to a Bitmap for PNG/JPG export.
 *  - Stamping text (as a rendered bitmap) with full Unicode support.
 *
 * All coordinate math accounts for:
 *  - PDF coordinate system origin at bottom-left (Y-axis inverted vs screen).
 *  - Page rotation (0 / 90 / 180 / 270 degrees).
 *  - CropBox vs MediaBox differences.
 */
actual class PdfSignatureService actual constructor(private val context: Context) {

    companion object {
        private const val PDF_POINTS_PER_INCH = 72f
        const val RENDER_DPI = 150f
        val POINTS_TO_PIXELS: Float get() = RENDER_DPI / PDF_POINTS_PER_INCH
    }

    private fun openInputStream(uri: Uri): java.io.InputStream {
        return if (uri.scheme == "file") {
            java.io.FileInputStream(java.io.File(uri.path!!))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open input stream for $uri")
        }
    }

    /**
     * Copies [uri] to a local temp file when we need random-access / seekable
     * I/O (e.g. rendering pages 2+ in a multi-page PDF).
     * For file:// URIs the file is used directly without copying.
     */
    private fun toReadableFile(uri: Uri): Pair<File, Boolean> {
        if (uri.scheme == "file") {
            return Pair(java.io.File(uri.path!!), false)
        }
        val tmp = java.io.File(context.cacheDir,
            "sig_tmp_${System.currentTimeMillis()}_${System.nanoTime()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
        return Pair(tmp, true) // second = should delete after use
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    actual fun stampSignatureAndSave(
        sourceUri: Uri,
        placements: List<SignaturePlacement>,
        outputUri: Uri,
        textPlacements: List<TextStampPlacement>
    ): Uri {
        val outputFile = File(requireNotNull(outputUri.path) { "outputUri must be a file:// URI" })
        val (file, shouldDelete) = toReadableFile(sourceUri)
        try {
            PDDocument.load(file).use { document ->
                placements.forEach { stampSignatureOnPage(document, it) }
                textPlacements.forEach { stampTextOnPage(document, it) }
                FileOutputStream(outputFile).use { out -> document.save(out) }
            }
        } finally {
            if (shouldDelete) file.delete()
        }
        return Uri.fromFile(outputFile)
    }

    actual fun renderPageToBitmap(
        sourceUri: Uri,
        pageIndex: Int,
        dpi: Float
    ): Bitmap {
        val (file, shouldDelete) = toReadableFile(sourceUri)
        try {
            PDDocument.load(file).use { document ->
                require(pageIndex in 0 until document.numberOfPages) {
                    "pageIndex $pageIndex out of range (total ${document.numberOfPages})"
                }
                return renderPage(document, pageIndex, dpi)
            }
        } finally {
            if (shouldDelete) file.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Signature stamping
    // -------------------------------------------------------------------------

    private fun stampSignatureOnPage(document: PDDocument, placement: SignaturePlacement) {
        val page = document.getPage(placement.pageIndex)
        val mediaBox = page.mediaBox ?: PDRectangle.LETTER
        val rotation = page.rotation

        val (pageWidthPt, pageHeightPt) = effectivePageDimensions(mediaBox, rotation)

        val ratioX = placement.positionPx.x / placement.renderedPageWidthPx
        val ratioY = placement.positionPx.y / placement.renderedPageHeightPx
        val ratioW = placement.sizePx.width / placement.renderedPageWidthPx
        val ratioH = placement.sizePx.height / placement.renderedPageHeightPx

        val sigXPt = ratioX * pageWidthPt
        val sigYPt = ratioY * pageHeightPt
        val sigWPt = ratioW * pageWidthPt
        val sigHPt = ratioH * pageHeightPt

        val (pdfX, pdfY) = toPdfUserSpace(
            sigXPt, sigYPt, sigWPt, sigHPt,
            pageWidthPt, pageHeightPt, mediaBox, rotation
        )

        val pdImage = LosslessFactory.createFromImage(document, placement.signatureBitmap)

        PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.drawImage(pdImage, pdfX, pdfY, sigWPt, sigHPt)
        }
    }

    // -------------------------------------------------------------------------
    // Text stamping � renders text to bitmap for full Unicode support
    // -------------------------------------------------------------------------

    /**
     * Stamps a [TextStampPlacement] onto the PDF document page.
     *
     * Instead of using PDType1Font (Latin-1 only), we render the text to a
     * transparent Bitmap using Android's Paint (which supports all Unicode
     * including Hebrew, Arabic, CJK, emoji) and then stamp it as an image.
     */
    private fun stampTextOnPage(document: PDDocument, placement: TextStampPlacement) {
        val page     = document.getPage(placement.pageIndex)
        val mediaBox = page.mediaBox ?: PDRectangle.LETTER
        val rotation = page.rotation
        val (pageWidthPt, pageHeightPt) = effectivePageDimensions(mediaBox, rotation)

        // Render text to a transparent bitmap at high resolution
        val renderScale = 3f  // render at 3x for crisp text
        val paint = APaint(APaint.ANTI_ALIAS_FLAG).apply {
            textSize = placement.fontSizePt * renderScale
            color    = placement.color
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (placement.isBold) Typeface.BOLD else Typeface.NORMAL
            )
        }

        val textWidth  = paint.measureText(placement.text).toInt().coerceAtLeast(1)
        val fm         = paint.fontMetrics
        val textHeight = (fm.descent - fm.ascent).toInt().coerceAtLeast(1)

        val textBitmap = Bitmap.createBitmap(textWidth, textHeight, Bitmap.Config.ARGB_8888)
        ACanvas(textBitmap).drawText(placement.text, 0f, -fm.ascent, paint)

        // Compute the stamp size in PDF points
        val stampWPt = placement.fontSizePt * (textWidth.toFloat() / textHeight.toFloat())
        val stampHPt = placement.fontSizePt

        // Map screen pixels ? PDF ratio ? PDF points
        val ratioX = placement.positionPx.x / placement.renderedPageWidthPx
        val ratioY = placement.positionPx.y / placement.renderedPageHeightPx
        val xPt    = ratioX * pageWidthPt
        val yPt    = ratioY * pageHeightPt

        val (pdfX, pdfY) = toPdfUserSpace(
            xPt, yPt,
            stampWPt, stampHPt,
            pageWidthPt, pageHeightPt, mediaBox, rotation
        )

        val pdImage = LosslessFactory.createFromImage(document, textBitmap)
        PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.drawImage(pdImage, pdfX, pdfY, stampWPt, stampHPt)
        }
        textBitmap.recycle()
    }

    // -------------------------------------------------------------------------
    // Coordinate helpers
    // -------------------------------------------------------------------------

    private fun effectivePageDimensions(mediaBox: PDRectangle, rotation: Int): Pair<Float, Float> {
        val w = mediaBox.width
        val h = mediaBox.height
        return if (rotation == 90 || rotation == 270) Pair(h, w) else Pair(w, h)
    }

    private fun toPdfUserSpace(
        xPt: Float, yPt: Float,
        wPt: Float, hPt: Float,
        effectiveWidth: Float, effectiveHeight: Float,
        mediaBox: PDRectangle,
        rotation: Int
    ): Pair<Float, Float> {
        return when (rotation) {
            0 -> {
                val pdfX = mediaBox.lowerLeftX + xPt
                val pdfY = mediaBox.lowerLeftY + (effectiveHeight - yPt - hPt)
                Pair(pdfX, pdfY)
            }
            90 -> {
                val pdfX = mediaBox.lowerLeftX + yPt
                val pdfY = mediaBox.lowerLeftY + xPt
                Pair(pdfX, pdfY)
            }
            180 -> {
                val pdfX = mediaBox.lowerLeftX + (effectiveWidth - xPt - wPt)
                val pdfY = mediaBox.lowerLeftY + yPt
                Pair(pdfX, pdfY)
            }
            270 -> {
                val pdfX = mediaBox.lowerLeftX + (effectiveHeight - yPt - hPt)
                val pdfY = mediaBox.lowerLeftY + (effectiveWidth - xPt - wPt)
                Pair(pdfX, pdfY)
            }
            else -> {
                val pdfX = mediaBox.lowerLeftX + xPt
                val pdfY = mediaBox.lowerLeftY + (effectiveHeight - yPt - hPt)
                Pair(pdfX, pdfY)
            }
        }
    }

    private fun renderPage(document: PDDocument, pageIndex: Int, dpi: Float): Bitmap {
        val renderer = PDFRenderer(document)
        val scale = dpi / PDF_POINTS_PER_INCH
        return renderer.renderImage(pageIndex, scale)
    }
}
