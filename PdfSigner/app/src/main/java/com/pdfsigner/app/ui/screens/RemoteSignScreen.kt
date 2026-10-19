package com.pdfsigner.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.RemoteSession
import com.pdfsigner.app.domain.model.RemoteSessionStatus
import com.pdfsigner.app.ui.theme.Brand700
import com.pdfsigner.app.ui.theme.SuccessGreen
import com.pdfsigner.app.ui.viewmodel.RemoteSignViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full remote-signing sender screen.
 *
 * Flow:
 *  1. User enters signer e-mail + optional message ? tap "Send"
 *  2. Session card shows deep-link + QR placeholder + live status badge
 *  3. When status = SIGNED the "Download Signed PDF" button appears
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSignScreen(
    docUri: Uri,
    docName: String,
    onBack: () -> Unit,
    onSignedDocReady: (Uri) -> Unit,
    vm: RemoteSignViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current

    // Reset when this screen is first opened for a new document
    LaunchedEffect(docUri) { vm.reset() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_sign_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Document info chip
            DocInfoChip(docName = docName)

            // -- Form or session card depending on state --
            if (uiState.session == null) {
                SendForm(
                    email         = uiState.signerEmail,
                    message       = uiState.message,
                    emailError    = uiState.emailError,
                    isCreating    = uiState.isCreating,
                    onEmailChange = { vm.setSignerEmail(it) },
                    onMessageChange = { vm.setMessage(it) },
                    onSend = { vm.createSession(docUri, docName) }
                )
            } else {
                SessionCard(
                    session   = uiState.session!!,
                    isPolling = uiState.isPolling,
                    context   = context,
                    onCancel  = { vm.cancelSession() },
                    onOpenSigned = { uri -> onSignedDocReady(uri) }
                )
            }

            // Error snack
            uiState.error?.let { err ->
                ErrorBanner(message = err, onDismiss = { vm.clearError() })
            }

            // Session history
            if (uiState.sessionHistory.isNotEmpty()) {
                SessionHistorySection(
                    sessions    = uiState.sessionHistory,
                    currentId   = uiState.session?.sessionId,
                    onOpenSigned = onSignedDocReady
                )
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun DocInfoChip(docName: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Brand700.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.PictureAsPdf, null,
                tint = Brand700, modifier = Modifier.size(20.dp))
            Text(
                text = docName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun SendForm(
    email: String,
    message: String,
    emailError: Boolean,
    isCreating: Boolean,
    onEmailChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Card(
        shape  = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.remote_sign_invite_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text  = stringResource(R.string.remote_sign_invite_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value         = email,
                onValueChange = onEmailChange,
                label         = { Text(stringResource(R.string.remote_sign_email_label)) },
                leadingIcon   = { Icon(Icons.Filled.Email, null) },
                isError       = emailError,
                supportingText = if (emailError) {
                    { Text(stringResource(R.string.remote_sign_email_error)) }
                } else null,
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape         = MaterialTheme.shapes.medium,
                modifier      = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value         = message,
                onValueChange = onMessageChange,
                label         = { Text(stringResource(R.string.remote_sign_message_label)) },
                leadingIcon   = { Icon(Icons.Filled.Message, null) },
                minLines      = 2,
                maxLines      = 4,
                shape         = MaterialTheme.shapes.medium,
                modifier      = Modifier.fillMaxWidth()
            )

            // Privacy notice
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SuccessGreen.copy(alpha = 0.08f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Security, null,
                        tint = SuccessGreen, modifier = Modifier.size(18.dp))
                    Text(
                        text  = stringResource(R.string.remote_sign_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen
                    )
                }
            }

            Button(
                onClick  = onSend,
                enabled  = !isCreating && email.isNotBlank(),
                shape    = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_sign_send_button))
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun SessionCard(
    session: RemoteSession,
    isPolling: Boolean,
    context: Context,
    onCancel: () -> Unit,
    onOpenSigned: (Uri) -> Unit
) {
    Card(
        shape  = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: status badge + title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusBadge(status = session.status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remote_sign_session_id, session.sessionId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = session.signerEmail,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isPolling) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            HorizontalDivider()

            // Deep link
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text  = stringResource(R.string.remote_sign_link_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text     = session.deepLink,
                            style    = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick  = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("signing_link", session.deepLink))
                                Toast.makeText(context, context.getString(R.string.remote_sign_link_copied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            RemoteQrCode(link = session.deepLink)

            // Share button
            OutlinedButton(
                onClick = {
                    val shareText = context.getString(
                        R.string.remote_sign_share_text,
                        session.docName,
                        session.deepLink
                    )
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }, null
                    ))
                },
                shape    = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_sign_share_button))
            }

            // Expiry info
            val expiresStr = remember(session.expiresAtMs) {
                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    .format(Date(session.expiresAtMs))
            }
            Text(
                text  = stringResource(R.string.remote_sign_expires, expiresStr),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // Signed doc available
            AnimatedVisibility(session.status == RemoteSessionStatus.SIGNED && session.signedDocUri != null) {
                Button(
                    onClick  = { session.signedDocUri?.let { onOpenSigned(it) } },
                    shape    = MaterialTheme.shapes.large,
                    colors   = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_sign_open_signed))
                }
            }

            // Cancel
            if (session.status == RemoteSessionStatus.PENDING || session.status == RemoteSessionStatus.VIEWED) {
                TextButton(
                    onClick  = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text  = stringResource(R.string.remote_sign_cancel_session),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteQrCode(link: String) {
    val qrBitmap = remember(link) { generateQrBitmap(link, 512) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.remote_sign_qr_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.remote_sign_qr_content_desc),
                modifier = Modifier
                    .size(196.dp)
                    .padding(10.dp)
            )
        }
    }
}

private fun generateQrBitmap(text: String, sizePx: Int): Bitmap {
    val hints: Map<EncodeHintType, Any> = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bitmap
}

// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun StatusBadge(status: RemoteSessionStatus) {
    val (label, color) = when (status) {
        RemoteSessionStatus.PENDING   -> stringResource(R.string.remote_status_pending)   to Color(0xFFF59E0B)
        RemoteSessionStatus.VIEWED    -> stringResource(R.string.remote_status_viewed)    to Color(0xFF3B82F6)
        RemoteSessionStatus.SIGNED    -> stringResource(R.string.remote_status_signed)    to SuccessGreen
        RemoteSessionStatus.CANCELLED -> stringResource(R.string.remote_status_cancelled) to MaterialTheme.colorScheme.error
        RemoteSessionStatus.EXPIRED   -> stringResource(R.string.remote_status_expired)   to MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color    = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun SessionHistorySection(
    sessions: List<RemoteSession>,
    currentId: String?,
    onOpenSigned: (Uri) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.remote_sign_history_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        sessions
            .filter { it.sessionId != currentId }
            .take(5)
            .forEach { session ->
                SessionHistoryRow(session = session, onOpenSigned = onOpenSigned)
            }
    }
}

@Composable
private fun SessionHistoryRow(session: RemoteSession, onOpenSigned: (Uri) -> Unit) {
    val dateStr = remember(session.createdAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(session.createdAtMs))
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.docName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text  = "${session.signerEmail} � $dateStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusBadge(status = session.status)
            if (session.status == RemoteSessionStatus.SIGNED && session.signedDocUri != null) {
                IconButton(
                    onClick  = { session.signedDocUri?.let { onOpenSigned(it) } },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Download, null,
                        tint = SuccessGreen, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape    = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Error, null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, null, modifier = Modifier.size(14.dp))
            }
        }
    }
}
