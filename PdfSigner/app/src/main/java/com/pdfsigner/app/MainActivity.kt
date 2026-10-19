package com.pdfsigner.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfsigner.app.domain.prefs.AppLanguage
import com.pdfsigner.app.domain.prefs.AppPreferences
import com.pdfsigner.app.ui.navigation.PdfSignerNavHost
import com.pdfsigner.app.ui.theme.PdfSignerTheme
import com.pdfsigner.app.ui.viewmodel.SettingsViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val p = AppPreferences(newBase)
        super.attachBaseContext(applyLocale(newBase, p.language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize PDFBox resource loader - required before any PDFBox operation
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()

        // Extract any incoming URI from VIEW/SEND intents
        val rawIncomingUri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }

        // If the URI is a pdfsigner:// deep link, extract the sessionId
        // e.g. pdfsigner://sign/SESSION_ID → sessionId = "SESSION_ID"
        val incomingSignerSessionId: String? =
            if (rawIncomingUri?.scheme == "pdfsigner")
                rawIncomingUri.pathSegments.firstOrNull()
            else null

        // Only treat it as a document URI if it is NOT a deep link
        val incomingUri: Uri? = if (incomingSignerSessionId != null) null else rawIncomingUri

        // Grant read permission if the URI came from another app
        if (incomingUri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    incomingUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        setContent {
            val settingsVm: SettingsViewModel = viewModel()
            val settings by settingsVm.uiState.collectAsState()

            PdfSignerTheme(
                appTheme     = settings.theme,
                fontScale    = settings.fontScale,
                highContrast = settings.highContrast
            ) {
                // Pass the single incomingUri as incomingDocUri regardless of type.
                // The DocumentViewerViewModel classifies the type itself.
                PdfSignerNavHost(
                    incomingPdfUri           = null,
                    incomingDocUri           = incomingUri,
                    incomingSignerSessionId  = incomingSignerSessionId,
                    settingsVm               = settingsVm
                )
            }
        }
    }

    companion object {
        fun applyLocale(context: Context, language: AppLanguage): Context {
            // Android < 24 uses "iw" for Hebrew, >= 24 uses "he"
            // Locale constructor on newer Android normalises "iw" ? "he" automatically
            val locale = when (language) {
                AppLanguage.HEBREW -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Locale("he")
                    else Locale("iw")
                }
                else -> Locale(language.code)
            }
            Locale.setDefault(locale)
            val config = context.resources.configuration.also {
                it.setLocale(locale)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    it.setLocales(android.os.LocaleList(locale))
                }
            }
            return context.createConfigurationContext(config)
        }
    }
}
