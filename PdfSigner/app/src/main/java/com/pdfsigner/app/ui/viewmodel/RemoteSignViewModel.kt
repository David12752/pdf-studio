package com.pdfsigner.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfsigner.app.domain.model.RemoteSession
import com.pdfsigner.app.domain.model.RemoteSessionStatus
import com.pdfsigner.app.domain.service.RemoteSigningService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// RemoteSignUiState is defined in shared/commonMain

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class RemoteSignViewModel(application: Application) : AndroidViewModel(application) {

    private val context  get() = getApplication<Application>()
    private val service  = RemoteSigningService(context)

    private val _uiState = MutableStateFlow(RemoteSignUiState())
    val uiState: StateFlow<RemoteSignUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        refreshHistory()
    }

    // -----------------------------------------------------------------------
    // Sender � form fields
    // -----------------------------------------------------------------------

    fun setSignerEmail(email: String) {
        _uiState.update { it.copy(signerEmail = email, emailError = false) }
    }

    fun setMessage(msg: String) {
        _uiState.update { it.copy(message = msg) }
    }

    // -----------------------------------------------------------------------
    // Sender � create session
    // -----------------------------------------------------------------------

    fun createSession(docUri: Uri, docName: String) {
        val email = _uiState.value.signerEmail.trim()
        if (!isValidEmail(email)) {
            _uiState.update { it.copy(emailError = true) }
            return
        }

        _uiState.update { it.copy(isCreating = true, error = null) }

        viewModelScope.launch {
            runCatching {
                service.createSession(
                    docUri      = docUri,
                    docName     = docName,
                    signerEmail = email,
                    message     = _uiState.value.message
                )
            }.onSuccess { session ->
                _uiState.update { it.copy(isCreating = false, session = session) }
                startPolling(session.sessionId)
                refreshHistory()
            }.onFailure { e ->
                _uiState.update { it.copy(isCreating = false, error = e.message ?: "Failed to create session") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Sender � cancel session
    // -----------------------------------------------------------------------

    fun cancelSession() {
        val id = _uiState.value.session?.sessionId ?: return
        stopPolling()
        viewModelScope.launch {
            runCatching { service.cancelSession(id) }
            _uiState.update { it.copy(session = null) }
            refreshHistory()
        }
    }

    // -----------------------------------------------------------------------
    // Sender � live status polling (every 10 s)
    // -----------------------------------------------------------------------

    private fun startPolling(sessionId: String) {
        stopPolling()
        _uiState.update { it.copy(isPolling = true) }
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000L)
                val status = runCatching { service.pollStatus(sessionId) }.getOrNull()
                    ?: continue
                _uiState.update { state ->
                    val updated = state.session?.copy(status = status)
                    state.copy(session = updated, isPolling = status == RemoteSessionStatus.PENDING || status == RemoteSessionStatus.VIEWED)
                }
                if (status == RemoteSessionStatus.SIGNED ||
                    status == RemoteSessionStatus.CANCELLED ||
                    status == RemoteSessionStatus.EXPIRED) {
                    stopPolling()
                    refreshHistory()
                    break
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _uiState.update { it.copy(isPolling = false) }
    }

    // -----------------------------------------------------------------------
    // Signer � load session from deep link
    // -----------------------------------------------------------------------

    fun loadSignerSession(sessionId: String) {
        _uiState.update { it.copy(isSignerView = true, error = null) }
        viewModelScope.launch {
            runCatching {
                service.markViewed(sessionId)
                service.loadSession(sessionId)
            }.onSuccess { session ->
                _uiState.update { it.copy(signerSession = session, error = null) }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Session not found") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Signer � submit signed document
    // -----------------------------------------------------------------------

    fun submitSignedDocument(sessionId: String, signedDocUri: Uri) {
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                service.submitSignedDocument(sessionId, signedDocUri)
            }.onSuccess { ok ->
                if (ok) {
                    val updated = _uiState.value.signerSession?.copy(
                        status = RemoteSessionStatus.SIGNED,
                        signedDocUri = signedDocUri
                    )
                    _uiState.update { it.copy(isSubmitting = false, signerSession = updated) }
                } else {
                    _uiState.update { it.copy(isSubmitting = false, error = "Upload failed") }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isSubmitting = false, error = e.message ?: "Upload error") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // History
    // -----------------------------------------------------------------------

    fun refreshHistory() {
        val all = runCatching { service.loadAllSessions() }.getOrDefault(emptyList())
        _uiState.update { it.copy(sessionHistory = all) }
    }

    // -----------------------------------------------------------------------
    // Reset / helpers
    // -----------------------------------------------------------------------

    fun reset() {
        stopPolling()
        _uiState.update {
            it.copy(
                signerEmail  = "",
                message      = "",
                emailError   = false,
                isCreating   = false,
                session      = null,
                isSignerView = false,
                signerSession = null,
                isSubmitting = false,
                error        = null
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun isValidEmail(email: String): Boolean {
        return email.contains("@") && email.contains(".") && email.length >= 5
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
