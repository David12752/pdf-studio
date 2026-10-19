package com.pdfsigner.app.ui.viewmodel

import com.pdfsigner.app.domain.prefs.AppLanguage
import com.pdfsigner.app.domain.prefs.AppTheme

data class SettingsUiState(
    val theme: AppTheme          = AppTheme.SYSTEM,
    val language: AppLanguage    = AppLanguage.ENGLISH,
    val hapticsEnabled: Boolean  = true,
    val fontScale: Int           = 0,
    val highContrast: Boolean    = false
)

// ViewModel implementation lives in app module
