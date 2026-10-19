package com.pdfsigner.app.domain.model

import androidx.compose.ui.geometry.Offset

/**
 * A text annotation to be stamped on a specific PDF page.
 *
 * @param text                 The string to render.
 * @param pageIndex            Zero-based page index.
 * @param positionPx           Top-left corner in rendered-bitmap pixels.
 * @param fontSizePt           Font size in PDF points.
 * @param color                Text color (ARGB).
 * @param isBold               Whether to use bold weight.
 * @param renderedPageWidthPx  Width of the rendered page bitmap (for coordinate mapping).
 * @param renderedPageHeightPx Height of the rendered page bitmap.
 */
data class TextStampPlacement(
    val text: String,
    val pageIndex: Int,
    val positionPx: Offset,
    val fontSizePt: Float,
    val color: Int,                 // ARGB int
    val isBold: Boolean,
    val renderedPageWidthPx: Int,
    val renderedPageHeightPx: Int
)
