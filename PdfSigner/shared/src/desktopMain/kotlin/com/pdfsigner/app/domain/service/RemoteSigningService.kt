package com.pdfsigner.app.domain.service

import com.pdfsigner.app.domain.model.PlatformContext
import com.pdfsigner.app.domain.model.PlatformUri
import com.pdfsigner.app.domain.model.RemoteSession
import com.pdfsigner.app.domain.model.RemoteSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.prefs.Preferences

/**
 * Desktop implementation of RemoteSigningService.
 * Persists sessions via [java.util.prefs.Preferences].
 */
class RemoteSigningService(private val context: PlatformContext) {

    companion object {
        private const val DEEP_LINK_SCHEME = "pdfsigner://sign/"
        private const val SESSION_TTL_MS   = 24 * 60 * 60 * 1000L
        private const val KEY_SESSIONS     = "sessions_json"
    }

    private val prefs: Preferences =
        Preferences.userRoot().node("com/pdfsigner/remote_sessions")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // -------------------------------------------------------------------------
    // SHA-256 of PDF bytes
    // -------------------------------------------------------------------------

    fun computeDocHash(uri: PlatformUri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        uri.file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // Create session
    // -------------------------------------------------------------------------

    suspend fun createSession(
        docUri: PlatformUri,
        docName: String,
        signerEmail: String,
        message: String
    ): RemoteSession = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        val docHash   = computeDocHash(docUri)
        val now       = System.currentTimeMillis()

        val session = RemoteSession(
            sessionId   = sessionId,
            docName     = docName,
            docHash     = docHash,
            signerEmail = signerEmail,
            message     = message,
            status      = RemoteSessionStatus.PENDING,
            deepLink    = "$DEEP_LINK_SCHEME$sessionId",
            createdAtMs = now,
            expiresAtMs = now + SESSION_TTL_MS
        )
        persistSession(session)
        session
    }

    // -------------------------------------------------------------------------
    // Poll status
    // -------------------------------------------------------------------------

    suspend fun pollStatus(sessionId: String): RemoteSessionStatus =
        withContext(Dispatchers.IO) {
            val stored = loadSession(sessionId) ?: return@withContext RemoteSessionStatus.EXPIRED
            if (System.currentTimeMillis() > stored.expiresAtMs) {
                updateSessionStatus(sessionId, RemoteSessionStatus.EXPIRED)
                RemoteSessionStatus.EXPIRED
            } else stored.status
        }

    suspend fun markViewed(sessionId: String) = withContext(Dispatchers.IO) {
        updateSessionStatus(sessionId, RemoteSessionStatus.VIEWED)
    }

    suspend fun submitSignedDocument(sessionId: String, signedDocUri: PlatformUri): Boolean =
        withContext(Dispatchers.IO) {
            val session = loadSession(sessionId) ?: return@withContext false
            persistSession(session.copy(status = RemoteSessionStatus.SIGNED, signedDocUri = signedDocUri))
            true
        }

    suspend fun cancelSession(sessionId: String) = withContext(Dispatchers.IO) {
        updateSessionStatus(sessionId, RemoteSessionStatus.CANCELLED)
    }

    // -------------------------------------------------------------------------
    // Load sessions
    // -------------------------------------------------------------------------

    fun loadAllSessions(): List<RemoteSession> {
        val raw = prefs.get(KEY_SESSIONS, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredSession>>(raw).map { it.toRemoteSession() }
        }.getOrDefault(emptyList())
    }

    fun loadSession(sessionId: String): RemoteSession? =
        loadAllSessions().firstOrNull { it.sessionId == sessionId }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun persistSession(session: RemoteSession) {
        val all = loadAllSessions().toMutableList()
        val idx = all.indexOfFirst { it.sessionId == session.sessionId }
        if (idx >= 0) all[idx] = session else all.add(0, session)
        prefs.put(KEY_SESSIONS, json.encodeToString(all.map { StoredSession.from(it) }))
    }

    private fun updateSessionStatus(sessionId: String, status: RemoteSessionStatus) {
        loadSession(sessionId)?.let { persistSession(it.copy(status = status)) }
    }

    @Serializable
    private data class StoredSession(
        val sessionId: String,
        val docName: String,
        val docHash: String,
        val signerEmail: String,
        val message: String,
        val status: String,
        val deepLink: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val signedDocUriString: String? = null
    ) {
        fun toRemoteSession(): RemoteSession {
            val uri: PlatformUri? = signedDocUriString?.let { path ->
                com.pdfsigner.app.domain.model.PlatformUri(File(path))
            }
            return RemoteSession(
                sessionId    = sessionId,
                docName      = docName,
                docHash      = docHash,
                signerEmail  = signerEmail,
                message      = message,
                status       = runCatching { RemoteSessionStatus.valueOf(status) }
                                   .getOrDefault(RemoteSessionStatus.EXPIRED),
                deepLink     = deepLink,
                createdAtMs  = createdAtMs,
                expiresAtMs  = expiresAtMs,
                signedDocUri = uri
            )
        }

        companion object {
            fun from(s: RemoteSession) = StoredSession(
                sessionId          = s.sessionId,
                docName            = s.docName,
                docHash            = s.docHash,
                signerEmail        = s.signerEmail,
                message            = s.message,
                status             = s.status.name,
                deepLink           = s.deepLink,
                createdAtMs        = s.createdAtMs,
                expiresAtMs        = s.expiresAtMs,
                signedDocUriString = s.signedDocUri?.file?.absolutePath
            )
        }
    }
}
