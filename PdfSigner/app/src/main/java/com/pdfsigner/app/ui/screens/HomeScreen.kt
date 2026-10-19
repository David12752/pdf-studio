package com.pdfsigner.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.RecentFile
import com.pdfsigner.app.ui.theme.Brand700
import com.pdfsigner.app.ui.theme.Brand900
import com.pdfsigner.app.ui.theme.Accent500
import com.pdfsigner.app.ui.viewmodel.PdfSignerViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: PdfSignerViewModel,
    onOpenPicker: () -> Unit,
    onScanComplete: (Uri) -> Unit,
    onPdfTools: () -> Unit,
    onOpenFile: ((Uri) -> Unit)? = null,
    onSignPdf: ((Uri) -> Unit)? = null
) {
    val uiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current

    // ML Kit scanner
    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setScannerMode(SCANNER_MODE_FULL)
            .setResultFormats(RESULT_FORMAT_PDF)
            .setPageLimit(10)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(scannerOptions) }
    var pendingScanAfterPermission by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pdf?.uri?.let { onScanComplete(it) }
        }
    }

    fun startDocumentScan() {
        val activity = context.findActivity()
        if (activity == null) {
            viewModel.setError(context.getString(R.string.scanner_unavailable, "Activity not available"))
            return
        }

        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                scanLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                viewModel.setError(context.getString(R.string.scanner_unavailable, e.message ?: ""))
            }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingScanAfterPermission) {
            pendingScanAfterPermission = false
            startDocumentScan()
        } else {
            pendingScanAfterPermission = false
            viewModel.setError(context.getString(R.string.scanner_permission_denied))
        }
    }

    // File opener for all document types
    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (onOpenFile != null) {
                onOpenFile(uri)
            } else {
                val mimeType = context.contentResolver.getType(uri) ?: "*/*"
                if (mimeType == "application/pdf") {
                    // Open PDF in app for signing
                    viewModel.loadPdf(uri)
                } else {
                    // Open other file types with external app
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(
                            Intent.createChooser(intent, context.getString(R.string.open_with))
                        )
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.file_open_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── Hero Header ──────────────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.0f to Brand900,
                                    0.6f to Brand700,
                                    1.0f to Color(0xFF1A4A8A)
                                )
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 28.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // App logo
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_app_logo),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(stringResource(R.string.home_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                letterSpacing = (-0.5).sp)
                            Text(stringResource(R.string.home_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f))
                        }
                    }
                }
            }

            // ── Loading ──────────────────────────────────────────────────────
            if (uiState.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.processing),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // ── Quick Actions ─────────────────────────────────────────────
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                    Text(
                        text = stringResource(R.string.quick_actions),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        QuickActionCard(
                            icon        = Icons.Filled.FolderOpen,
                            label       = stringResource(R.string.open_pdf_button),
                            color       = Brand700,
                            modifier    = Modifier.weight(1f),
                            onClick     = onOpenPicker
                        )
                        QuickActionCard(
                            icon        = Icons.Filled.CameraAlt,
                            label       = stringResource(R.string.scan_document_button),
                            color       = Accent500,
                            modifier    = Modifier.weight(1f),
                            onClick     = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    startDocumentScan()
                                } else {
                                    pendingScanAfterPermission = true
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                        QuickActionCard(
                            icon        = Icons.Filled.Build,
                            label       = stringResource(R.string.pdf_tools_button),
                            color       = Color(0xFF2E7D32),
                            modifier    = Modifier.weight(1f),
                            onClick     = onPdfTools
                        )
                        QuickActionCard(
                            icon        = Icons.AutoMirrored.Filled.InsertDriveFile,
                            label       = stringResource(R.string.open_file_button),
                            color       = Color(0xFF7B1FA2),
                            modifier    = Modifier.weight(1f),
                            onClick     = {
                                openFileLauncher.launch(arrayOf(
                                    "application/pdf",
                                    "application/vnd.ms-excel",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.oasis.opendocument.spreadsheet",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.oasis.opendocument.text",
                                    "application/vnd.ms-powerpoint",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                    "application/vnd.oasis.opendocument.presentation",
                                    "image/*",
                                    "text/*"
                                ))
                            }
                        )
                    }
                }
            }

            // ── Recent Files ─────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.recent_files_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (uiState.recentFiles.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.History, null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(40.dp))
                            Text(stringResource(R.string.no_recent_files),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                items(uiState.recentFiles) { recentFile ->
                    RecentFileRow(
                        file     = recentFile,
                        // Tap on recent file → open in viewer (onSignPdf callback is used for this)
                        onClick  = {
                            val uri = Uri.parse(recentFile.uriString)
                            if (onSignPdf != null) onSignPdf(uri)
                            else viewModel.loadPdf(uri)
                        },
                        onRemove = { viewModel.removeRecentFile(recentFile.uriString) }
                    )
                }
            }

            // ── Error Banner ─────────────────────────────────────────────────
            uiState.error?.let { error ->
                item {
                    Card(
                        colors   = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape    = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Warning, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp))
                            Text(error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                        }
                    }
                    LaunchedEffect(error) { delay(4000); viewModel.clearError() }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick   = onClick,
        modifier  = modifier,
        shape     = MaterialTheme.shapes.large,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Text(
                text      = label,
                style     = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines  = 2,
                overflow  = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecentFileRow(
    file: RecentFile,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val dateStr = remember(file.timestampMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            .format(Date(file.timestampMs))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // PDF icon badge
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Brand700.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PictureAsPdf, null,
                tint = Brand700, modifier = Modifier.size(24.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = file.fileName,
                style    = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text  = "${stringResource(R.string.pages_count, file.pageCount)} · $dateStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
