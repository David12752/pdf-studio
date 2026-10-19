package com.pdfsigner.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RecentFile(
    val uriString: String,
    val fileName: String,
    val pageCount: Int,
    val timestampMs: Long
)
