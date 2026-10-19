package com.pdfsigner.app.ui.screens

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pdfsigner.app.R
import com.pdfsigner.app.ui.components.AppStep
import com.pdfsigner.app.ui.components.SignatureDrawingView
import com.pdfsigner.app.ui.components.StepProgressIndicator
import com.pdfsigner.app.ui.viewmodel.PdfSignerViewModel

/**
 * Step 2 – Signature drawing screen.
 * Optional biometric authentication before proceeding to placement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureScreen(
    viewModel: PdfSignerViewModel,
    onSignatureSaved: () -> Unit,
    onBack: () -> Unit,
    onHome: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Check if biometric is available
    val biometricAvailable = remember {
        val mgr = BiometricManager.from(context)
        mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    var biometricAuthenticated by remember { mutableStateOf(!biometricAvailable) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    // Trigger biometric prompt
    fun promptBiometric(onSuccess: () -> Unit) {
        if (!biometricAvailable || biometricAuthenticated) {
            onSuccess()
            return
        }
        val activity = context as? FragmentActivity ?: run {
            biometricAuthenticated = true
            onSuccess()
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                biometricAuthenticated = true
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    biometricError = errString.toString()
                }
            }
            override fun onAuthenticationFailed() {
                // Let user retry — don't show error
            }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.biometric_prompt_title))
            .setSubtitle(context.getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(context.getString(R.string.biometric_negative_button))
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text     = uiState.pdfDocument?.fileName
                            ?: stringResource(R.string.draw_signature_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (onHome != null) {
                        IconButton(onClick = onHome) {
                            Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.nav_home))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor             = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor          = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            StepProgressIndicator(currentStep = AppStep.SIGN)

            // Biometric badge
            if (biometricAvailable) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (biometricAuthenticated)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Fingerprint, null,
                            tint = if (biometricAuthenticated)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            if (biometricAuthenticated) "✓ Identity verified"
                            else stringResource(R.string.biometric_required),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (biometricAuthenticated)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            biometricError?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            SignatureDrawingView(
                onSignatureSaved = { bitmap ->
                    promptBiometric {
                        viewModel.setSignatureBitmap(bitmap, save = false)
                        onSignatureSaved()
                    }
                },
                onSaveRequested  = { bitmap ->
                    viewModel.setSignatureBitmap(bitmap, save = true)
                },
                savedSignatures  = uiState.savedSignatures,
                onDeleteSaved    = { id -> viewModel.deleteSavedSignature(id) },
                onDismiss        = onBack,
                modifier         = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}
