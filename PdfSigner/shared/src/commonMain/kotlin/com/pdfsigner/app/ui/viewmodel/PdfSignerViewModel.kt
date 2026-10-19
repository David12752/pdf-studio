package com.pdfsigner.app.ui.viewmodel

import com.pdfsigner.app.domain.model.ExportFormat
import com.pdfsigner.app.domain.model.PdfDocument
import com.pdfsigner.app.domain.model.PlatformBitmap
import com.pdfsigner.app.domain.model.PlatformUri
import com.pdfsigner.app.domain.model.RecentFile
import com.pdfsigner.app.domain.model.SavedSignature
import com.pdfsigner.app.domain.model.SignaturePlacement
import com.pdfsigner.app.domain.model.TextStampPlacement

data class PdfSignerUiState(
    val pdfDocument: PdfDocument? = null,
    val currentPageIndex: Int = 0,
    val renderedPageBitmap: PlatformBitmap? = null,
    val signatureBitmap: PlatformBitmap? = null,
    val placement: SignaturePlacement? = null,
    val textPlacements: List<TextStampPlacement> = emptyList(),
    val recentFiles: List<RecentFile> = emptyList(),
    val savedSignatures: List<SavedSignature> = emptyList(),
    val isLoading: Boolean = false,
    val exportResult: ExportResult? = null,
    val error: String? = null
)

sealed class ExportResult {
    data class Success(val uri: PlatformUri, val format: ExportFormat) : ExportResult()
    data class Error(val message: String) : ExportResult()
}

// ViewModel implementation lives in app module
