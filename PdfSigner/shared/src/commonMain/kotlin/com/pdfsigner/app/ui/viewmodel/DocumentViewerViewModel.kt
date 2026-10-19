package com.pdfsigner.app.ui.viewmodel

import com.pdfsigner.app.domain.model.PlatformBitmap
import com.pdfsigner.app.domain.model.PlatformUri

//  Enums 

enum class DocType {
    PDF, WORD, EXCEL, POWERPOINT, IMAGE, TEXT, UNKNOWN
}

enum class ReadingMode(val label: String) {
    NORMAL("Normal"),
    SEPIA("Sepia"),
    WARM("Warm"),
    COOL("Cool"),
    NIGHT("Night")
}

//  State 

data class PdfStats(
    val pageCount: Int = 0,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val readingTimeMin: Int = 0,
    val fileSizeBytes: Long = 0L
)

data class DocumentViewerUiState(
    val uri: PlatformUri? = null,
    val fileName: String = "",
    val mimeType: String = "",
    val docType: DocType = DocType.UNKNOWN,
    val isLoading: Boolean = false,
    val error: String? = null,

    // PDF pages
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val pageBitmap: PlatformBitmap? = null,
    val renderedPages: List<PlatformBitmap?> = emptyList(),

    // Reading mode
    val readingMode: ReadingMode = ReadingMode.NORMAL,

    // Stats
    val pdfStats: PdfStats? = null,
    val showStats: Boolean = false,

    // Password
    val requiresPassword: Boolean = false,
    val passwordError: Boolean = false,

    // QR stamp
    val qrStampInProgress: Boolean = false,
    val qrStampDone: Boolean = false,

    // Text / Image / Office
    val textContent: String? = null,
    val imageBitmap: PlatformBitmap? = null,
    val cachedFilePath: String? = null
)

//  ViewModel (Android implementation lives in androidMain) 
