@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import android.content.Context
import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.PlatformUri
import android.net.Uri
import androidx.core.content.edit
import com.pdfsigner.app.domain.model.RecentFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * Persists and retrieves the list of recently opened PDFs using SharedPreferences.
 * Keeps a maximum of [MAX_RECENTS] entries, newest first.
 */
actual class RecentFilesManager actual constructor(context: PlatformContext) {

    companion object {
        private const val PREFS_NAME   = "recent_files"
        private const val KEY_RECENTS  = "recents_json"
        private const val MAX_RECENTS  = 8
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json  = Json { ignoreUnknownKeys = true }

    actual fun getRecents(): List<RecentFile> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<RecentFile>>(raw) }.getOrElse { emptyList() }
    }

    actual fun addRecent(uri: PlatformUri, fileName: String, pageCount: Int) {
        val existing = getRecents().toMutableList()
        // Remove any existing entry for the same URI
        existing.removeAll { it.uriString == uri.toString() }
        existing.add(0, RecentFile(
            uriString   = uri.toString(),
            fileName    = fileName,
            pageCount   = pageCount,
            timestampMs = System.currentTimeMillis()
        ))
        val trimmed = existing.take(MAX_RECENTS)
        prefs.edit { putString(KEY_RECENTS, json.encodeToString(trimmed)) }
    }

    actual fun removeRecent(uriString: String) {
        val existing = getRecents().toMutableList()
        existing.removeAll { it.uriString == uriString }
        prefs.edit { putString(KEY_RECENTS, json.encodeToString(existing)) }
    }
}
