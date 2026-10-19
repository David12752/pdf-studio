package com.pdfsigner.app.ui.viewmodel

import com.pdfsigner.app.domain.model.PlatformUri

enum class PasswordDialogTarget { SPLIT, EXTRACT, COMPRESS, PROTECT, WATERMARK, AUDIT, NONE }
enum class WatermarkPosition    { CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

data class PdfToolsUiState(
    val isLoading: Boolean = false,
    val resultMessage: String? = null,
    val resultUri: PlatformUri? = null,
    val error: String? = null,
    val expandedTool: String? = null,

    // Merge
    val mergeUris: List<PlatformUri> = emptyList(),

    // Split
    val splitUri: PlatformUri? = null,
    val splitPageCount: Int = 0,
    val splitPassword: String? = null,
    val splitSelectedPages: Set<Int> = emptySet(),
    val splitFolderNameDialogShowing: Boolean = false,
    val splitPendingUris: List<PlatformUri> = emptyList(),
    val splitSourceName: String = "",

    // Extract
    val extractUri: PlatformUri? = null,
    val extractPageCount: Int = 0,
    val extractPageIndex: Int = 0,
    val extractPassword: String? = null,

    // Convert
    val convertUri: PlatformUri? = null,

    // Watermark
    val watermarkUri: PlatformUri? = null,
    val watermarkText: String = "DRAFT",
    val watermarkOpacity: Float = 0.3f,
    val watermarkImageUri: PlatformUri? = null,
    val watermarkUseImage: Boolean = false,
    val watermarkPosition: WatermarkPosition = WatermarkPosition.CENTER,

    // Compress
    val compressUri: PlatformUri? = null,
    val compressQuality: Float = 0.6f,
    val compressPassword: String? = null,

    // Protect
    val protectUri: PlatformUri? = null,
    val protectPassword: String = "",

    // Audit
    val auditUri: PlatformUri? = null,
    val auditSignerName: String = "",

    // Password dialog
    val passwordDialogTarget: PasswordDialogTarget = PasswordDialogTarget.NONE,
    val passwordDialogUri: PlatformUri? = null,
    val passwordDialogError: Boolean = false,
)

// ViewModel implementation lives in androidMain
