package com.pdfsigner.app.ui.viewmodel

import com.pdfsigner.app.domain.model.RemoteSession
import com.pdfsigner.app.domain.model.RemoteSessionStatus

data class RemoteSignUiState(
    val signerEmail: String   = "",
    val message: String       = "",
    val emailError: Boolean   = false,
    val isCreating: Boolean   = false,
    val session: RemoteSession? = null,
    val isPolling: Boolean    = false,
    val isSignerView: Boolean = false,
    val signerSession: RemoteSession? = null,
    val isSubmitting: Boolean = false,
    val sessionHistory: List<RemoteSession> = emptyList(),
    val error: String? = null
)

// ViewModel implementation lives in app module
