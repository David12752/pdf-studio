@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.PlatformBitmap
import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.PlatformUri
import com.pdfsigner.app.domain.model.SignaturePlacement
import com.pdfsigner.app.domain.model.TextStampPlacement
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream

actual class PdfSignatureService actual constructor(private val context: PlatformContext) {

    /**
     * Stamps all [placements] and [textPlacements] onto the source PDF and
     * writes the result to [outputUri]. Returns [outputUri].
     */
    actual fun stampSignatureAndSave(
        sourceUri: PlatformUri,
        placements: List<SignaturePlacement>,
        outputUri: PlatformUri,
        textPlacements: List<TextStampPlacement>
    ): PlatformUri {
        Loader.loadPDF(sourceUri.file).use { doc ->
            // 1. Stamp images
            for (place in placements) {
                val page = doc.getPage(place.pageIndex)
                stampSignatureOnPage(doc, page, place)
            }
            // 2. Stamp text annotations
            for (stamp in textPlacements) {
                val page = doc.getPage(stamp.pageIndex)
                stampTextOnPage(doc, page, stamp)
            }
            // 3. Save
            outputUri.file.parentFile?.mkdirs()
            FileOutputStream(outputUri.file).use { doc.save(it) }
        }
        return outputUri
    }

    /**
     * Renders one PDF page at the given [dpi] and returns a [BufferedImage].
     */
    actual fun renderPageToBitmap(
        sourceUri: PlatformUri,
        pageIndex: Int,
        dpi: Float
    ): PlatformBitmap {
        Loader.loadPDF(sourceUri.file).use { doc ->
            val renderer = PDFRenderer(doc)
            return renderer.renderImageWithDPI(pageIndex, dpi)
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun stampSignatureOnPage(
        doc: PDDocument,
        page: PDPage,
        place: SignaturePlacement
    ) {
        val mediaBox = page.mediaBox ?: PDRectangle.LETTER
        val pageW    = mediaBox.width
        val pageH    = mediaBox.height

        val rendW    = place.renderedPageWidthPx.toFloat()
        val rendH    = place.renderedPageHeightPx.toFloat()

        // Map bitmap-pixel coordinates → PDF-point coordinates
        val scaleX   = pageW / rendW
        val scaleY   = pageH / rendH

        val pdfX     = mediaBox.lowerLeftX + place.positionPx.x * scaleX
        // PDF y-axis is bottom-up; bitmap y-axis is top-down
        val pdfH     = place.sizePx.height * scaleY
        val pdfY     = mediaBox.lowerLeftY + pageH - (place.positionPx.y * scaleY) - pdfH
        val pdfW     = place.sizePx.width  * scaleX

        val sigBmp   = place.signatureBitmap
        val pdImage  = LosslessFactory.createFromImage(doc, sigBmp)

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.drawImage(pdImage, pdfX, pdfY, pdfW, pdfH)
        }
    }

    private fun stampTextOnPage(
        doc: PDDocument,
        page: PDPage,
        stamp: TextStampPlacement
    ) {
        val mediaBox = page.mediaBox ?: PDRectangle.LETTER
        val pageW    = mediaBox.width
        val pageH    = mediaBox.height

        val rendW    = stamp.renderedPageWidthPx.toFloat()
        val rendH    = stamp.renderedPageHeightPx.toFloat()

        val scaleX   = pageW / rendW
        val scaleY   = pageH / rendH

        val pdfX     = mediaBox.lowerLeftX + stamp.positionPx.x * scaleX
        val pdfY     = mediaBox.lowerLeftY + pageH - (stamp.positionPx.y * scaleY)

        // ARGB → PDFBox color
        val argb     = stamp.color
        val r        = ((argb shr 16) and 0xFF) / 255f
        val g        = ((argb shr 8)  and 0xFF) / 255f
        val b        = ((argb)         and 0xFF) / 255f

        val font = if (stamp.isBold)
            PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        else
            PDType1Font(Standard14Fonts.FontName.HELVETICA)

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.beginText()
            cs.setFont(font, stamp.fontSizePt)
            cs.setNonStrokingColor(r, g, b)
            cs.newLineAtOffset(pdfX, pdfY)
            cs.showText(stamp.text)
            cs.endText()
        }
    }
}
