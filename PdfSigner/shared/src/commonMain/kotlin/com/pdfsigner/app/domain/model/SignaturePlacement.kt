package com.pdfsigner.app.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Represents a placed signature on a specific PDF page.
 *
 * @param signatureBitmap      The rendered signature as a transparent bitmap.
 * @param pageIndex            Zero-based page index on which the signature is placed.
 * @param positionPx           Top-left corner in rendered-bitmap pixels.
 * @param sizePx               Width and height in rendered-bitmap pixels.
 * @param renderedPageWidthPx  Width of the rendered page bitmap (used for coordinate mapping).
 * @param renderedPageHeightPx Height of the rendered page bitmap (used for coordinate mapping).
 */
data class SignaturePlacement(
    val signatureBitmap: PlatformBitmap,
    val pageIndex: Int,
    val positionPx: Offset,
    val sizePx: Size,
    val renderedPageWidthPx: Int,
    val renderedPageHeightPx: Int
)
