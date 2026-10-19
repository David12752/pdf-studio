package com.pdfsigner.app.domain.model

/**
 * Represents a remote signing session.
 *
 * The actual PDF bytes are NEVER sent to the server.
 * Only the SHA-256 hash of the document is shared so the signer
 * can verify document integrity after downloading the file peer-to-peer
 * or via a short-lived pre-signed URL the sender generates locally.
 */
data class RemoteSession(
    val sessionId: String,
    val docName: String,
    val docHash: String,           // SHA-256 of the original PDF bytes
    val signerEmail: String,
    val message: String,
    val status: RemoteSessionStatus,
    val deepLink: String,          // pdfsigner://sign/{sessionId}
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val signedDocUri: PlatformUri? = null
)

enum class RemoteSessionStatus {
    /** Waiting for the signer to open the link */
    PENDING,
    /** Signer has opened the link and is reviewing */
    VIEWED,
    /** Signer has completed and uploaded the signed document */
    SIGNED,
    /** Sender or system cancelled the session */
    CANCELLED,
    /** Session TTL elapsed without a signature */
    EXPIRED
}
