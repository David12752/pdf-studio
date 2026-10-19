package com.pdfsigner.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.pdfsigner.app.domain.prefs.AppLanguage
import com.pdfsigner.app.domain.prefs.AppPreferences
import com.pdfsigner.app.domain.prefs.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// SettingsUiState is defined in shared/commonMain

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = AppPreferences(application)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            theme          = prefs.theme,
            language       = prefs.language,
            hapticsEnabled = prefs.hapticsEnabled,
            fontScale      = prefs.fontScale,
            highContrast   = prefs.highContrastEnabled
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setTheme(t: AppTheme) {
        prefs.theme = t
        _uiState.update { it.copy(theme = t) }
    }

    fun setLanguage(l: AppLanguage) {
        prefs.language = l
        _uiState.update { it.copy(language = l) }
    }

    fun setHaptics(v: Boolean) {
        prefs.hapticsEnabled = v
        _uiState.update { it.copy(hapticsEnabled = v) }
    }

    fun setFontScale(v: Int) {
        prefs.fontScale = v
        _uiState.update { it.copy(fontScale = v) }
    }

    fun setHighContrast(v: Boolean) {
        prefs.highContrastEnabled = v
        _uiState.update { it.copy(highContrast = v) }
    }
}
