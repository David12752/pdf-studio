package com.pdfsigner.app.domain.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.pdfsigner.app.domain.model.RemoteSession
import com.pdfsigner.app.domain.model.RemoteSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages remote signing sessions — Android implementation.
 *
 * Architecture: privacy-first — the PDF itself is never uploaded to any server.
 * Only the document hash + session metadata travel over the network.
 *
 * Current implementation: local simulation (Phase 2 scaffold).
 * Phase 1 (backend) will replace [createSession], [pollStatus], and
 * [submitSignedDocument] with real Retrofit/Ktor client calls.
 */
class RemoteSigningService(private val context: Context) {

    companion object {
        private const val PREFS_NAME       = "remote_sessions"
        private const val KEY_SESSIONS     = "sessions_json"
        private const val DEEP_LINK_SCHEME = "pdfsigner://sign/"
        private const val SESSION_TTL_MS   = 24 * 60 * 60 * 1000L  // 24 hours
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // -------------------------------------------------------------------------
    // SHA-256 of PDF bytes
    // -------------------------------------------------------------------------

    fun computeDocHash(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = if (uri.scheme == "file") {
            java.io.FileInputStream(java.io.File(uri.path!!))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open $uri")
        }
        stream.use {
            val buf = ByteArray(8192)
            var n: Int
            while (it.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // Create session (Phase 1: POST /sessions)
    // -------------------------------------------------------------------------

    suspend fun createSession(
        docUri: Uri,
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
    // Poll session status (Phase 1: GET /sessions/{id})
    // -------------------------------------------------------------------------

    suspend fun pollStatus(sessionId: String): RemoteSessionStatus =
        withContext(Dispatchers.IO) {
            val stored = loadSession(sessionId) ?: return@withContext RemoteSessionStatus.EXPIRED
            if (System.currentTimeMillis() > stored.expiresAtMs) {
                updateSessionStatus(sessionId, RemoteSessionStatus.EXPIRED)
                RemoteSessionStatus.EXPIRED
            } else {
                stored.status
            }
        }

    // -------------------------------------------------------------------------
    // Signer: mark as viewed
    // -------------------------------------------------------------------------

    suspend fun markViewed(sessionId: String) = withContext(Dispatchers.IO) {
        updateSessionStatus(sessionId, RemoteSessionStatus.VIEWED)
    }

    // -------------------------------------------------------------------------
    // Signer: submit signed document (Phase 1: POST /sessions/{id}/sign)
    // -------------------------------------------------------------------------

    suspend fun submitSignedDocument(
        sessionId: String,
        signedDocUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        val session = loadSession(sessionId) ?: return@withContext false
        persistSession(session.copy(status = RemoteSessionStatus.SIGNED, signedDocUri = signedDocUri))
        true
    }

    // -------------------------------------------------------------------------
    // Cancel session (Phase 1: DELETE /sessions/{id})
    // -------------------------------------------------------------------------

    suspend fun cancelSession(sessionId: String) = withContext(Dispatchers.IO) {
        updateSessionStatus(sessionId, RemoteSessionStatus.CANCELLED)
    }

    // -------------------------------------------------------------------------
    // Load sessions
    // -------------------------------------------------------------------------

    fun loadAllSessions(): List<RemoteSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredSession>>(raw).map { it.toRemoteSession() }
        }.getOrDefault(emptyList())
    }

    fun loadSession(sessionId: String): RemoteSession? =
        loadAllSessions().firstOrNull { it.sessionId == sessionId }

    // -------------------------------------------------------------------------
    // Private persistence helpers
    // -------------------------------------------------------------------------

    private fun persistSession(session: RemoteSession) {
        val all = loadAllSessions().toMutableList()
        val idx = all.indexOfFirst { it.sessionId == session.sessionId }
        if (idx >= 0) all[idx] = session else all.add(0, session)
        prefs.edit().putString(KEY_SESSIONS, json.encodeToString(all.map { StoredSession.from(it) })).apply()
    }

    private fun updateSessionStatus(sessionId: String, status: RemoteSessionStatus) {
        loadSession(sessionId)?.let { persistSession(it.copy(status = status)) }
    }

    // -------------------------------------------------------------------------
    // Serialisable DTO (avoids android.net.Uri serialization issues)
    // -------------------------------------------------------------------------

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
        fun toRemoteSession() = RemoteSession(
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
            signedDocUri = signedDocUriString?.let { Uri.parse(it) }
        )

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
                signedDocUriString = s.signedDocUri?.toString()
            )
        }
    }
}
