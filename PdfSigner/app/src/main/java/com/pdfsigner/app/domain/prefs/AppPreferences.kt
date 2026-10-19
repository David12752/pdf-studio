package com.pdfsigner.app.domain.prefs

import android.content.Context
import androidx.core.content.edit

// AppTheme and AppLanguage enums are defined in :shared commonMain (AppSettings.kt)
// Importing them here is not needed as they are in the same package and accessible automatically.

/**
 * Lightweight SharedPreferences-backed preferences.
 * Using SharedPrefs instead of DataStore to keep the dependency count minimal.
 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME       = "app_prefs"
        private const val KEY_THEME        = "theme"
        private const val KEY_LANGUAGE     = "language"
        private const val KEY_HAPTICS      = "haptics"
        private const val KEY_FONT_SCALE   = "font_scale"
        private const val KEY_HIGH_CONTRAST= "high_contrast"
    }

    var theme: AppTheme
        get() = AppTheme.valueOf(prefs.getString(KEY_THEME, AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name)
        set(v) = prefs.edit { putString(KEY_THEME, v.name) }

    var language: AppLanguage
        get() {
            val stored = prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code)
            return when (stored) {
                "he", "iw" -> AppLanguage.HEBREW
                else -> AppLanguage.entries.find { it.code == stored } ?: AppLanguage.ENGLISH
            }
        }
        set(v) = prefs.edit { putString(KEY_LANGUAGE, v.code) }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(v) = prefs.edit { putBoolean(KEY_HAPTICS, v) }

    /** 0=Default, 1=Large, 2=Extra Large */
    var fontScale: Int
        get() = prefs.getInt(KEY_FONT_SCALE, 0)
        set(v) = prefs.edit { putInt(KEY_FONT_SCALE, v.coerceIn(0, 2)) }

    var highContrastEnabled: Boolean
        get() = prefs.getBoolean(KEY_HIGH_CONTRAST, false)
        set(v) = prefs.edit { putBoolean(KEY_HIGH_CONTRAST, v) }
}
