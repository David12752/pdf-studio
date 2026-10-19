@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.PlatformBitmap
import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.PlatformUri
import com.pdfsigner.app.domain.model.SignaturePlacement
import com.pdfsigner.app.domain.model.TextStampPlacement

expect class PdfSignatureService(context: PlatformContext) {
    fun stampSignatureAndSave(
        sourceUri: PlatformUri,
        placements: List<SignaturePlacement>,
        outputUri: PlatformUri,
        textPlacements: List<TextStampPlacement>
    ): PlatformUri

    fun renderPageToBitmap(
        sourceUri: PlatformUri,
        pageIndex: Int,
        dpi: Float
    ): PlatformBitmap
}