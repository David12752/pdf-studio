package com.pdfsigner.app.ui.screens

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.RemoteSession
import com.pdfsigner.app.domain.model.RemoteSessionStatus
import com.pdfsigner.app.ui.theme.SuccessGreen
import com.pdfsigner.app.ui.theme.Brand700
import com.pdfsigner.app.ui.viewmodel.RemoteSignViewModel
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen shown to the person who RECEIVES the signing request.
 *
 * Opened via deep link: pdfsigner://sign/{sessionId}
 *
 * Flow:
 *  1. Load session metadata (doc name, hash, sender message)
 *  2. Show doc info + "Sign Document" button
 *  3. On sign ? navigate to signature canvas (reuses existing flow)
 *  4. After signing ? submit ? show success
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSignerScreen(
    sessionId: String,
    onBack: () -> Unit,
    onProceedToSign: (docName: String, sessionId: String) -> Unit,
    vm: RemoteSignViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()

    LaunchedEffect(sessionId) {
        vm.loadSignerSession(sessionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_signer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.signerSession == null && uiState.error == null -> {
                    // Loading
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.remote_signer_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error!!,
                        onBack = onBack,
                        onRetry = { vm.loadSignerSession(sessionId) }
                    )
                }

                else -> {
                    val session = uiState.signerSession!!
                    SignerContent(
                        session       = session,
                        isSubmitting  = uiState.isSubmitting,
                        onProceedSign = { onProceedToSign(session.docName, session.sessionId) }
                    )
                }
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun SignerContent(
    session: RemoteSession,
    isSubmitting: Boolean,
    onProceedSign: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Completed state
        if (session.status == RemoteSessionStatus.SIGNED) {
            SignedSuccessCard()
            return@Column
        }

        // Expired / cancelled
        if (session.status == RemoteSessionStatus.EXPIRED ||
            session.status == RemoteSessionStatus.CANCELLED) {
            SessionEndedCard(status = session.status)
            return@Column
        }

        // Document info
        Card(
            shape  = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .let { m ->
                                m.then(
                                    Modifier.then(
                                        Modifier.wrapContentSize()
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Brand700.copy(alpha = 0.1f),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PictureAsPdf, null,
                                    tint = Brand700,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.docName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.remote_signer_doc_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider()

                // Sender message
                if (session.message.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text  = stringResource(R.string.remote_signer_message_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = session.message,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                // Hash info
                val shortHash = session.docHash.take(16) + "�"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.Verified, null,
                        tint = SuccessGreen, modifier = Modifier.size(14.dp))
                    Text(
                        text  = stringResource(R.string.remote_signer_hash_label, shortHash),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Expiry
                val expiresStr = remember(session.expiresAtMs) {
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                        .format(Date(session.expiresAtMs))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.Schedule, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp))
                    Text(
                        text  = stringResource(R.string.remote_sign_expires, expiresStr),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Privacy notice
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SuccessGreen.copy(alpha = 0.07f)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Lock, null,
                    tint = SuccessGreen, modifier = Modifier.size(18.dp))
                Text(
                    text  = stringResource(R.string.remote_signer_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = SuccessGreen
                )
            }
        }

        // CTA
        Button(
            onClick  = onProceedSign,
            enabled  = !isSubmitting,
            shape    = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.Filled.Draw, null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.remote_signer_proceed_button),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SignedSuccessCard() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            shape  = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.CheckCircle, null,
                    tint = SuccessGreen, modifier = Modifier.size(64.dp))
                Text(
                    text = stringResource(R.string.remote_signer_signed_success_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SuccessGreen,
                    textAlign = TextAlign.Center
                )
                Text(
                    text  = stringResource(R.string.remote_signer_signed_success_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SessionEndedCard(status: RemoteSessionStatus) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            shape  = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Cancel, null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
                Text(
                    text = if (status == RemoteSessionStatus.EXPIRED)
                        stringResource(R.string.remote_signer_expired_title)
                    else
                        stringResource(R.string.remote_signer_cancelled_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Text(
                    text  = stringResource(R.string.remote_signer_ended_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Filled.ErrorOutline, null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Retry")
                }
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
