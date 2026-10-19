@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.PlatformUri
import com.pdfsigner.app.domain.model.RecentFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences

actual class RecentFilesManager actual constructor(private val context: PlatformContext) {

    private val prefs: Preferences = Preferences.userRoot().node("com/pdfsigner/recents")
    private val json = Json { ignoreUnknownKeys = true }
    private val MAX_RECENTS = 20

    actual fun getRecents(): List<RecentFile> {
        val raw = prefs.get(KEY_RECENTS, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<RecentFile>>(raw)
        }.getOrDefault(emptyList())
    }

    actual fun addRecent(uri: PlatformUri, fileName: String, pageCount: Int) {
        val current = getRecents().toMutableList()
        val uriString = uri.file.absolutePath
        // Remove existing entry for this URI (avoid duplicates)
        current.removeAll { it.uriString == uriString }
        // Add at front
        current.add(0, RecentFile(
            uriString  = uriString,
            fileName   = fileName,
            pageCount  = pageCount,
            timestampMs = System.currentTimeMillis()
        ))
        // Trim to max
        val trimmed = current.take(MAX_RECENTS)
        prefs.put(KEY_RECENTS, json.encodeToString(trimmed))
    }

    actual fun removeRecent(uriString: String) {
        val updated = getRecents().filter { it.uriString != uriString }
        prefs.put(KEY_RECENTS, json.encodeToString(updated))
    }

    private companion object {
        const val KEY_RECENTS = "recents_list"
    }
}
