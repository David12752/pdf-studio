package com.pdfsigner.desktop

/**
 * NOTE: The desktop application is implemented as an Electron app.
 * See PdfSigner/electronApp/ for the full desktop implementation.
 *
 * This Kotlin/Compose Desktop module is kept as a placeholder so the
 * Gradle multiplatform build (shared/) can resolve its "desktop" JVM target.
 * It is NOT intended to be shipped or used directly.
 */

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PDF Studio"
    ) {
        MaterialTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("The desktop app runs via Electron — see electronApp/")
            }
        }
    }
}
