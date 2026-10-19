package com.pdfsigner.app.domain.prefs

/** Application colour scheme preference. */
enum class AppTheme { SYSTEM, LIGHT, DARK }

/** Application language preference. */
enum class AppLanguage(val code: String, val displayName: String) {
    ENGLISH("en", "English"),
    HEBREW("he", "\u05E2\u05D1\u05E8\u05D9\u05EA")
}
