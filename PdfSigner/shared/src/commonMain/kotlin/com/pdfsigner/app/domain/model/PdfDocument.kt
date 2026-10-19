package com.pdfsigner.app.domain.model

/**
 * Represents the state of a loaded PDF document.
 */
data class PdfDocument(
    val uri: PlatformUri,
    val fileName: String,
    val pageCount: Int
)
