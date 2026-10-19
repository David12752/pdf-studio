package com.pdfsigner.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.ExportFormat
import com.pdfsigner.app.domain.model.PdfDocument
import com.pdfsigner.app.domain.model.SignaturePlacement
import com.pdfsigner.app.domain.model.TextStampPlacement
import com.pdfsigner.app.domain.model.RecentFile
import com.pdfsigner.app.domain.model.SavedSignature
import com.pdfsigner.app.domain.service.MediaStoreExporter
import com.pdfsigner.app.domain.service.PdfSignatureService
import com.pdfsigner.app.domain.service.RecentFilesManager
import com.pdfsigner.app.domain.service.SavedSignaturesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.io.FileInputStream

// ---------------------------------------------------------------------------
// UI State (PdfSignerUiState + ExportResult defined in shared/commonMain)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * Central ViewModel for the PDF signing flow.
 *
 * Responsibilities:
 *  1. Load a PDF URI and expose page bitmaps for the viewer.
 *  2. Hold the drawn signature bitmap from the signature canvas.
 *  3. Accept a signature placement from the placement screen.
 *  4. Coordinate the export pipeline (PDF / PNG / JPG) via the service layer.
 */
class PdfSignerViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val pdfSignatureService    = PdfSignatureService(context)
    private val mediaStoreExporter     = MediaStoreExporter(context)
    private val recentFilesManager     = RecentFilesManager(context)
    private val savedSignaturesManager = SavedSignaturesManager(context)

    private val _uiState = MutableStateFlow(PdfSignerUiState(
        recentFiles      = recentFilesManager.getRecents(),
        savedSignatures  = savedSignaturesManager.getAll()
    ))
    val uiState: StateFlow<PdfSignerUiState> = _uiState.asStateFlow()

    private var disposed = false

    // -----------------------------------------------------------------------
    // PDF Loading
    // -----------------------------------------------------------------------

    /**
     * Opens and validates the PDF at [uri], then renders page 0 for the viewer.
     */
    fun loadPdf(uri: Uri) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val fileName  = resolveFileName(uri)
                val pageCount = resolvePageCount(uri)
                val document  = PdfDocument(uri = uri, fileName = fileName, pageCount = pageCount)
                val bitmap    = pdfSignatureService.renderPageToBitmap(uri, pageIndex = 0, dpi = 200f)
                recentFilesManager.addRecent(uri, fileName, pageCount)
                Triple(document, bitmap, recentFilesManager.getRecents())
            }.onSuccess { (document, bitmap, recents) ->
                val previousBitmap = _uiState.value.renderedPageBitmap
                _uiState.update {
                    it.copy(
                        pdfDocument        = document,
                        currentPageIndex   = 0,
                        renderedPageBitmap = bitmap,
                        recentFiles        = recents,
                        isLoading          = false
                    )
                }
                recycleBitmap(previousBitmap, keep = bitmap)
            }.onFailure { throwable ->
                val message = when {
                    throwable.message?.contains("password", ignoreCase = true) == true ->
                        context.getString(R.string.error_pdf_password)
                    throwable.message?.contains("encrypt", ignoreCase = true) == true ->
                        context.getString(R.string.error_pdf_encrypted)
                    else -> context.getString(R.string.error_pdf_load, throwable.message ?: "Unknown")
                }
                _uiState.update {
                    it.copy(isLoading = false, error = message)
                }
            }
        }
    }

    /**
     * Navigates to [pageIndex] and re-renders that page.
     */
    fun navigateToPage(pageIndex: Int) {
        val doc = _uiState.value.pdfDocument ?: return
        if (pageIndex !in 0 until doc.pageCount) return

        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                pdfSignatureService.renderPageToBitmap(doc.uri, pageIndex, dpi = 200f)
            }.onSuccess { bitmap ->
                val previousBitmap = _uiState.value.renderedPageBitmap
                _uiState.update {
                    it.copy(currentPageIndex = pageIndex, renderedPageBitmap = bitmap, isLoading = false)
                }
                recycleBitmap(previousBitmap, keep = bitmap)
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to render page: ${throwable.message}")
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Signature
    // -----------------------------------------------------------------------

    /** Stores the drawn signature bitmap and persists it to the saved library. */
    fun setSignatureBitmap(bitmap: Bitmap, save: Boolean = false) {
        if (save) {
            viewModelScope.launch(Dispatchers.IO) {
                savedSignaturesManager.save(bitmap)
                val updated = savedSignaturesManager.getAll()
                val previousSignature = _uiState.value.signatureBitmap
                val previousSaved = _uiState.value.savedSignatures.map { it.bitmap }
                _uiState.update { it.copy(signatureBitmap = bitmap, placement = null, savedSignatures = updated) }
                recycleBitmap(previousSignature, keep = bitmap)
                recycleBitmaps(previousSaved, keep = updated.map { it.bitmap })
            }
        } else {
            val previousSignature = _uiState.value.signatureBitmap
            _uiState.update { it.copy(signatureBitmap = bitmap, placement = null) }
            recycleBitmap(previousSignature, keep = bitmap)
        }
    }

    /** Records where the user placed the signature on the PDF viewer. */
    fun setSignaturePlacement(placement: SignaturePlacement) {
        _uiState.update { it.copy(placement = placement) }
    }

    fun clearSignature() {
        val previousSignature = _uiState.value.signatureBitmap
        _uiState.update { it.copy(signatureBitmap = null, placement = null) }
        recycleBitmap(previousSignature)
    }

    // -----------------------------------------------------------------------
    // Text stamps
    // -----------------------------------------------------------------------

    fun addTextPlacement(placement: TextStampPlacement) {
        _uiState.update { it.copy(textPlacements = it.textPlacements + placement) }
    }

    fun removeTextPlacement(index: Int) {
        _uiState.update {
            it.copy(textPlacements = it.textPlacements.toMutableList()
                .also { list -> if (index in list.indices) list.removeAt(index) })
        }
    }

    fun clearAllAnnotations() {
        val previousSignature = _uiState.value.signatureBitmap
        _uiState.update { it.copy(signatureBitmap = null, placement = null, textPlacements = emptyList()) }
        recycleBitmap(previousSignature)
    }

    fun clearPdfDocument() {
        val previousBitmap = _uiState.value.renderedPageBitmap
        _uiState.update { it.copy(pdfDocument = null, renderedPageBitmap = null, currentPageIndex = 0) }
        recycleBitmap(previousBitmap)
    }

    // -----------------------------------------------------------------------
    // Export
    // -----------------------------------------------------------------------

    /**
     * Exports the signed PDF/Image to the device's public storage (MediaStore).
     * This is used when the user clicks explicitly on "PDF", "PNG", or "JPG" buttons.
     */
    fun exportAs(format: ExportFormat) {
        val state = _uiState.value
        val doc   = state.pdfDocument ?: return
        val placement = state.placement ?: return

        if (format != ExportFormat.PDF && doc.pageCount > 1) {
            _uiState.update {
                it.copy(
                    error = "PNG/JPG export is only supported for single-page PDFs. Export the signed PDF to keep all pages."
                )
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, exportResult = null, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            runCatching {
                // 1. Create temp signed PDF
                tempFile = File(context.cacheDir, "temp_signed_export_${System.currentTimeMillis()}.pdf")
                // Ensure logic for PDF stamping is reused
                pdfSignatureService.stampSignatureAndSave(
                    sourceUri      = doc.uri,
                    placements     = listOf(placement),
                    outputUri      = Uri.fromFile(tempFile),
                    textPlacements = state.textPlacements
                )

                // 2. Export based on format
                when (format) {
                    ExportFormat.PDF -> {
                        val fileName = sanitizeFileName(doc.fileName, "pdf")
                        mediaStoreExporter.savePdf(requireNotNull(tempFile), "PDF Studio/Signed", fileName)
                    }
                    ExportFormat.PNG, ExportFormat.JPG -> {
                        // We need to render the *signed* page to a bitmap
                        // We can read from tempFile
                        val signedUri = Uri.fromFile(requireNotNull(tempFile))
                        
                        // We assume single page export for now (the one with the signature)
                        // Or we export the current page. Let's export the page where the signature was placed.
                        val bitmap = pdfSignatureService.renderPageToBitmap(
                            sourceUri = signedUri,
                            pageIndex = placement.pageIndex,
                            dpi = 144f // Higher DPI for export quality
                        )
                        
                        val baseName = sanitizeFileName(doc.fileName, "")
                        try {
                            mediaStoreExporter.saveBitmap(bitmap, "${baseName}_signed", format)
                        } finally {
                            recycleBitmap(bitmap)
                        }
                    }
                }
            }.onSuccess { uri ->
                _uiState.update { it.copy(isLoading = false, exportResult = ExportResult.Success(uri, format)) }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isLoading = false, exportResult = ExportResult.Error(throwable.message ?: "Export failed")) }
            }.also {
                tempFile?.delete()
            }
        }
    }

    /**
     * Exports the signed PDF in the requested [format] to a specific target [uri] (e.g. from Storage Access Framework).
     */
    fun exportToUri(targetUri: Uri, format: ExportFormat) {
        val state = _uiState.value
        val doc   = state.pdfDocument ?: run { _uiState.update { it.copy(error = "No PDF loaded") }; return }
        val placement = state.placement ?: run { _uiState.update { it.copy(error = "No signature placed") }; return }

        if (format != ExportFormat.PDF && doc.pageCount > 1) {
            _uiState.update {
                it.copy(
                    error = "PNG/JPG export is only supported for single-page PDFs. Export the signed PDF to keep all pages."
                )
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, exportResult = null, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            runCatching {
                // Step 1: stamp the signature onto a temporary PDF file
                tempFile = File(context.cacheDir, "signed_temp_${System.currentTimeMillis()}.pdf")
                pdfSignatureService.stampSignatureAndSave(
                    sourceUri      = doc.uri,
                    placements     = listOf(placement),
                    outputUri      = Uri.fromFile(tempFile),
                    textPlacements = state.textPlacements
                )

                when (format) {
                    ExportFormat.PDF -> {
                        // Write tempFile to targetUri
                        context.contentResolver.openOutputStream(targetUri)?.use { output ->
                            FileInputStream(tempFile).use { input ->
                                input.copyTo(output)
                            }
                        }
                    }
                    ExportFormat.PNG, ExportFormat.JPG -> {
                        // Step 2: render the stamped page to a bitmap
                        val signedUri    = Uri.fromFile(requireNotNull(tempFile))
                        val bitmap       = pdfSignatureService.renderPageToBitmap(signedUri, placement.pageIndex, 200f)
                        val compressFmt  = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        val quality      = if (format == ExportFormat.PNG) 100 else 90

                        try {
                            context.contentResolver.openOutputStream(targetUri)?.use { output ->
                                bitmap.compress(compressFmt, quality, output)
                            }
                        } finally {
                            recycleBitmap(bitmap)
                        }
                    }
                }
                targetUri
            }.onSuccess { uri ->
                _uiState.update { it.copy(isLoading = false, exportResult = ExportResult.Success(uri, format)) }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isLoading = false, exportResult = ExportResult.Error(throwable.message ?: "Export failed")) }
            }.also {
                tempFile?.delete()
            }
        }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun removeRecentFile(uriString: String) {
        recentFilesManager.removeRecent(uriString)
        _uiState.update { it.copy(recentFiles = recentFilesManager.getRecents()) }
    }

    fun deleteSavedSignature(id: String) {
        val previousSaved = _uiState.value.savedSignatures
        savedSignaturesManager.delete(id)
        val updated = savedSignaturesManager.getAll()
        _uiState.update { it.copy(savedSignatures = updated) }
        recycleBitmaps(previousSaved.map { it.bitmap }, keep = updated.map { it.bitmap })
    }

    fun refreshSavedSignatures() {
        val previousSaved = _uiState.value.savedSignatures
        val updated = savedSignaturesManager.getAll()
        _uiState.update { it.copy(savedSignatures = updated) }
        recycleBitmaps(previousSaved.map { it.bitmap }, keep = updated.map { it.bitmap })
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private fun resolveFileName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex < 0 || !cursor.moveToFirst()) return@use null
                cursor.getString(nameIndex)
            }
        }.getOrNull() ?: "document.pdf"
    }

    private fun resolvePageCount(uri: Uri): Int {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    return@runCatching renderer.pageCount
                }
            }
        }.getOrNull() ?: 1
    }

    private fun sanitizeFileName(name: String, extension: String): String {
        val base = name.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return if (extension.isBlank()) base else "$base.$extension"
    }

    private fun recycleBitmap(bitmap: Bitmap?, keep: Bitmap? = null) {
        if (bitmap == null || bitmap === keep || bitmap.isRecycled) return
        runCatching { bitmap.recycle() }
    }

    private fun recycleBitmaps(bitmaps: Collection<Bitmap?>, keep: Collection<Bitmap?> = emptyList()) {
        val keepSet = keep.filterNotNull().toSet()
        bitmaps.forEach { bitmap ->
            if (bitmap != null && bitmap !in keepSet) recycleBitmap(bitmap)
        }
    }

    override fun onCleared() {
        disposed = true
        val state = _uiState.value
        recycleBitmap(state.renderedPageBitmap)
        recycleBitmap(state.signatureBitmap)
        recycleBitmaps(state.savedSignatures.map { it.bitmap })
        super.onCleared()
    }
}
