package com.pdfsigner.app.ui.viewmodel

import com.pdfsigner.app.domain.model.PlatformBitmap
import com.pdfsigner.app.domain.model.PlatformUri

data class DevicePdfFile(
    val uri: PlatformUri,
    val name: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val thumbnail: PlatformBitmap? = null
)

data class PdfPickerUiState(
    val files: List<DevicePdfFile> = emptyList(),
    val isLoading: Boolean = false,
    val thumbnailsLoading: Boolean = false,
    val query: String = ""
)

// ViewModel implementation lives in androidMain
