package com.pdfsigner.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfsigner.app.domain.service.MediaStoreExporter
import com.pdfsigner.app.domain.service.PdfPasswordRequiredException
import com.pdfsigner.app.domain.service.PdfToolsService
import com.pdfsigner.app.domain.service.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class PdfToolsViewModel(application: Application) : AndroidViewModel(application) {

    private val context  get() = getApplication<Application>()
    private val service      by lazy { PdfToolsService(context) }
    private val exporter     by lazy { MediaStoreExporter(context) }

    private val _uiState = MutableStateFlow(PdfToolsUiState())
    val uiState: StateFlow<PdfToolsUiState> = _uiState.asStateFlow()

    // -----------------------------------------------------------------------
    // Tool panel expansion
    // -----------------------------------------------------------------------

    fun setExpandedTool(tool: String?) { _uiState.update { it.copy(expandedTool = tool) } }

    // -----------------------------------------------------------------------
    // Merge
    // -----------------------------------------------------------------------

    fun addMergeUri(uri: Uri)    { _uiState.update { it.copy(mergeUris = it.mergeUris + uri) } }
    fun removeMergeUri(uri: Uri) { _uiState.update { it.copy(mergeUris = it.mergeUris - uri) } }
    fun clearMergeUris()         { _uiState.update { it.copy(mergeUris = emptyList()) } }

    fun mergePdfs(folderName: String) {
        val uris = _uiState.value.mergeUris
        if (uris.size < 2) {
            _uiState.update { it.copy(error = "Select at least 2 PDFs to merge.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, resultUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tempOut = File(context.cacheDir, "merged_${System.currentTimeMillis()}.pdf")
                service.mergePdfs(uris, tempOut)
                val saved = exporter.savePdf(tempOut, folderName, "merged_${System.currentTimeMillis()}.pdf")
                tempOut.delete()
                saved
            }.onSuccess { uri ->
                _uiState.update { it.copy(isLoading = false, resultUri = uri, resultMessage = "PDFs merged successfully.", mergeUris = emptyList()) }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Merge failed.") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Split
    // -----------------------------------------------------------------------

    fun setSplitUri(uri: Uri?) {
        _uiState.update { it.copy(splitUri = uri, splitPageCount = 0, splitSelectedPages = emptySet(), splitPassword = null) }
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { service.getPageCount(uri) }
                .onSuccess { pc -> _uiState.update { it.copy(splitPageCount = pc) } }
                .onFailure { e ->
                    if (e is PdfPasswordRequiredException) {
                        _uiState.update { it.copy(passwordDialogTarget = PasswordDialogTarget.SPLIT, passwordDialogUri = uri) }
                    } else {
                        _uiState.update { it.copy(error = e.message ?: "Failed to open PDF.") }
                    }
                }
        }
    }

    fun toggleSplitPage(page: Int) {
        _uiState.update { state ->
            val updated = state.splitSelectedPages.toMutableSet()
            if (page in updated) updated.remove(page) else updated.add(page)
            state.copy(splitSelectedPages = updated)
        }
    }

    fun selectAllSplitPages() {
        val count = _uiState.value.splitPageCount
        _uiState.update { it.copy(splitSelectedPages = (0 until count).toSet()) }
    }

    fun showSplitFolderDialog() { _uiState.update { it.copy(splitFolderNameDialogShowing = true) } }
    fun dismissSplitFolderDialog() { _uiState.update { it.copy(splitFolderNameDialogShowing = false) } }

    fun splitPdf(folderName: String) {
        val uri = _uiState.value.splitUri ?: return
        val password = _uiState.value.splitPassword
        _uiState.update { it.copy(isLoading = true, splitFolderNameDialogShowing = false, resultUri = null, splitPendingUris = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val outputDir = File(context.cacheDir, "split_${System.currentTimeMillis()}")
                val selected = _uiState.value.splitSelectedPages
                if (selected.isEmpty()) {
                    // Split all pages
                    service.splitPdf(uri, outputDir, password)
                } else {
                    // Extract only selected pages
                    outputDir.mkdirs()
                    selected.sorted().map { pageIndex ->
                        val out = File(outputDir, "page_${pageIndex + 1}.pdf")
                        service.extractPage(uri, pageIndex, out, password)
                    }
                }
            }.onSuccess { files ->
                val savedUris = files.map { f ->
                    val saved = runCatching { exporter.savePdf(f, folderName, f.name) }.getOrNull()
                        ?: Uri.fromFile(f)
                    f.delete()
                    saved
                }
                _uiState.update { it.copy(isLoading = false, splitPendingUris = savedUris, resultMessage = "Split into ${savedUris.size} file(s).") }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Split failed.") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Extract (single page → text)
    // -----------------------------------------------------------------------

    fun setExtractUri(uri: Uri?) {
        _uiState.update { it.copy(extractUri = uri, extractPageCount = 0, extractPageIndex = 0, extractPassword = null) }
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { service.getPageCount(uri) }
                .onSuccess { pc -> _uiState.update { it.copy(extractPageCount = pc) } }
                .onFailure { e ->
                    if (e is PdfPasswordRequiredException) {
                        _uiState.update { it.copy(passwordDialogTarget = PasswordDialogTarget.EXTRACT, passwordDialogUri = uri) }
                    } else {
                        _uiState.update { it.copy(error = e.message) }
                    }
                }
        }
    }

    fun setExtractPageIndex(index: Int) { _uiState.update { it.copy(extractPageIndex = index) } }

    fun extractPage(folderName: String) {
        val uri = _uiState.value.extractUri ?: return
        val pageIndex = _uiState.value.extractPageIndex
        val password = _uiState.value.extractPassword
        _uiState.update { it.copy(isLoading = true, resultUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val outputFile = File(context.cacheDir, "page_${pageIndex + 1}_${System.currentTimeMillis()}.pdf")
                service.extractPage(uri, pageIndex, outputFile, password)
                val saved = exporter.savePdf(outputFile, folderName, outputFile.name)
                outputFile.delete()
                saved
            }.onSuccess { saved ->
                _uiState.update { it.copy(isLoading = false, resultUri = saved, resultMessage = "Page extracted successfully.") }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Extract failed.") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Convert (text extraction)
    // -----------------------------------------------------------------------

    fun setConvertUri(uri: Uri?) { _uiState.update { it.copy(convertUri = uri) } }

    fun convertToText(folderName: String) {
        val uri = _uiState.value.convertUri ?: return
        _uiState.update { it.copy(isLoading = true, resultUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val outputFile = File(context.cacheDir, "extracted_${System.currentTimeMillis()}.txt")
                service.extractTextToFile(uri, outputFile)
                val saved = exporter.savePdf(outputFile, folderName, outputFile.name)
                outputFile.delete()
                saved
            }.onSuccess { saved ->
                _uiState.update { it.copy(isLoading = false, resultUri = saved, resultMessage = "Text extracted successfully.") }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Conversion failed.") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Watermark
    // -----------------------------------------------------------------------

    fun setWatermarkUri(uri: Uri?)  { _uiState.update { it.copy(watermarkUri = uri) } }
    fun setWatermarkText(s: String) { _uiState.update { it.copy(watermarkText = s) } }
    fun setWatermarkOpacity(v: Float) { _uiState.update { it.copy(watermarkOpacity = v) } }
    fun setWatermarkImageUri(uri: Uri?) { _uiState.update { it.copy(watermarkImageUri = uri, watermarkUseImage = uri != null) } }
    fun setWatermarkPosition(pos: WatermarkPosition) { _uiState.update { it.copy(watermarkPosition = pos) } }

    fun applyWatermark(folderName: String) {
        val uri = _uiState.value.watermarkUri ?: return
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, resultUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val outputFile = File(context.cacheDir, "watermarked_${System.currentTimeMillis()}.pdf")
                if (state.watermarkUseImage && state.watermarkImageUri != null) {
                    val bmpStream = context.contentResolver.openInputStream(state.watermarkImageUri)
                    val logoBmp = bmpStream?.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: throw IllegalStateException("Cannot open watermark image.")
                    service.addImageWatermark(uri, logoBmp, state.watermarkOpacity, state.watermarkPosition, outputFile)
                    logoBmp.recycle()
                } else {
                    service.addWatermark(uri, state.watermarkText, state.watermarkOpacity, outputFile)
                }
                val saved = exporter.savePdf(outputFile, folderName, outputFile.name)
                outputFile.delete()
                saved
            }.onSuccess { saved ->
                _uiState.update { it.copy(isLoading = false, resultUri = saved, resultMessage = "Watermark applied.") }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Watermark failed.") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Compress
    // -----------------------------------------------------------------------

    fun setCompressUri(uri: Uri?) {
        _uiState.update { it.copy(compressUri = uri, compressPassword = null) }
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { service.getPageCount(uri) }.onFailure { e ->
                if (e is PdfPasswordRequiredException) {
                    _uiState.update { it.copy(passwordDialogTarget = PasswordDialogTarget.COMPRESS, passwordDialogUri = uri) }
                }
            }
        }
    }

    fun setCompressQuality(v: Float) { _uiState.update { it.copy(compressQuality = v) } }

    fun compressPdf(folderName: String) {
        val uri = _uiState.value.compressUri ?: return
        val quality = _uiState.value.compressQuality
        val password = _uiState.value.compressPassword
        _uiState.update { it.copy(isLoading = true, resultUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val outputFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.pdf")
                service.compressPdf(uri, quality, 150f, outputFile, password)
                val saved = exporter.savePdf(outputFile, folderName, outputFile.name)
                outputFile.delete()
                saved
            }.onSuccess { saved ->
                _uiState.update { it.copy(isLoading = false, resultUri = saved, resultMessage = "PDF compressed successfully.") }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Compression failed.") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Protect
    // -----------------------------------------------------------------------

    fun setProtectUri(uri: Uri?)       { _uiState.update { it.copy(protectUri = uri) } }
    fun setProtectPassword(pw: String) { _uiState.update { it.copy(protectPassword = pw) } }

    fun protectPdf(folderName: String) {
        val uri = _uiState.value.protectUri ?: return
        val password = _uiState.value.protectPassword
        if (password.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a password.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, resultUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val outputFile = File(context.cacheDir, "protected_${System.currentTimeMillis()}.pdf")
                service.protectWithPassword(uri, password, outputFile)
                val saved = exporter.savePdf(outputFile, folderName, outputFile.name)
                outputFile.delete()
                saved
            }.onSuccess { saved ->
                _uiState.update { it.copy(isLoading = false, resultUri = saved, resultMessage = "PDF protected.") }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Protection failed.") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Audit trail
    // -----------------------------------------------------------------------

    fun setAuditUri(uri: Uri?)          { _uiState.update { it.copy(auditUri = uri) } }
    fun setAuditSignerName(name: String){ _uiState.update { it.copy(auditSignerName = name) } }

    fun addAuditTrail(folderName: String) {
        val uri = _uiState.value.auditUri ?: return
        val signerName = _uiState.value.auditSignerName
        if (signerName.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a signer name.") }
            return
        }
        _uiState.update { it.copy(isLoading = true, resultUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val deviceInfo = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
            runCatching {
                val outputFile = File(context.cacheDir, "audit_${System.currentTimeMillis()}.pdf")
                service.addAuditTrail(uri, signerName, deviceInfo, outputFile)
                val saved = exporter.savePdf(outputFile, folderName, outputFile.name)
                outputFile.delete()
                saved
            }.onSuccess { saved ->
                _uiState.update { it.copy(isLoading = false, resultUri = saved, resultMessage = "Audit trail added.") }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Audit trail failed.") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Password dialog
    // -----------------------------------------------------------------------

    fun showPasswordDialog(target: PasswordDialogTarget, uri: Uri) {
        _uiState.update { it.copy(passwordDialogTarget = target, passwordDialogUri = uri, passwordDialogError = false) }
    }

    fun dismissPasswordDialog() {
        _uiState.update { it.copy(passwordDialogTarget = PasswordDialogTarget.NONE, passwordDialogUri = null, passwordDialogError = false) }
    }

    fun submitPassword(password: String) {
        val target = _uiState.value.passwordDialogTarget
        val uri    = _uiState.value.passwordDialogUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { service.getPageCountWithPassword(uri, password) }
                .onSuccess { pc ->
                    _uiState.update {
                        val updated = when (target) {
                            PasswordDialogTarget.SPLIT    -> it.copy(splitPassword = password,    splitPageCount = pc)
                            PasswordDialogTarget.EXTRACT  -> it.copy(extractPassword = password,  extractPageCount = pc)
                            PasswordDialogTarget.COMPRESS -> it.copy(compressPassword = password)
                            else -> it
                        }
                        updated.copy(passwordDialogTarget = PasswordDialogTarget.NONE, passwordDialogUri = null, passwordDialogError = false)
                    }
                }
                .onFailure { e ->
                    if (e is WrongPasswordException) {
                        _uiState.update { it.copy(passwordDialogError = true) }
                    } else {
                        _uiState.update { it.copy(passwordDialogError = true, error = e.message) }
                    }
                }
        }
    }

    // -----------------------------------------------------------------------
    // Alias / no-arg convenience methods for screen composables
    // -----------------------------------------------------------------------

    fun mergePdfs()           = mergePdfs("PDF Studio/Merged")
    fun extractPage()         = extractPage("PDF Studio/Extracted")
    fun convertPdfToText()    = convertToText("PDF Studio/Text")
    fun addWatermark()        = applyWatermark("PDF Studio/Watermarked")
    fun compressPdf()         = compressPdf("PDF Studio/Compressed")
    fun protectPdf()          = protectPdf("PDF Studio/Protected")
    fun addAuditTrail()       = addAuditTrail("PDF Studio/Audit Trail")
    fun clearResultUri()      = clearResult()
    fun saveSplitWithFolderName(name: String) = splitPdf(name)
    fun requestSplitFolder()  = showSplitFolderDialog()
    fun clearSplitSelection() { _uiState.update { it.copy(splitSelectedPages = emptySet()) } }
    fun setWatermarkUseImage(use: Boolean) { _uiState.update { it.copy(watermarkUseImage = use) } }

    // Load URI aliases (screen calls loadXxxUri, ViewModel has setXxxUri)
    fun loadSplitUri(uri: Uri)     = setSplitUri(uri)
    fun loadExtractUri(uri: Uri)   = setExtractUri(uri)
    fun loadConvertUri(uri: Uri)   = setConvertUri(uri)
    fun loadWatermarkUri(uri: Uri) = setWatermarkUri(uri)
    fun loadCompressUri(uri: Uri)  = setCompressUri(uri)
    fun loadProtectUri(uri: Uri)   = setProtectUri(uri)
    fun loadAuditUri(uri: Uri)     = setAuditUri(uri)

    // Page-count-aware overloads (NavHost pre-computes page count with Android PdfRenderer)
    fun setSplitUri(uri: Uri, pageCount: Int) {
        _uiState.update { it.copy(splitUri = uri, splitPageCount = pageCount, splitSelectedPages = emptySet(), splitPassword = null) }
    }
    fun setExtractUri(uri: Uri, pageCount: Int) {
        _uiState.update { it.copy(extractUri = uri, extractPageCount = pageCount, extractPageIndex = 0, extractPassword = null) }
    }

    // -----------------------------------------------------------------------
    // Misc
    // -----------------------------------------------------------------------

    fun clearResult() { _uiState.update { it.copy(resultUri = null, resultMessage = null) } }
    fun clearError()  { _uiState.update { it.copy(error = null) } }
}
