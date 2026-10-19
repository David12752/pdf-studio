package com.pdfsigner.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.ExportFormat
import com.pdfsigner.app.ui.components.AppStep
import com.pdfsigner.app.ui.components.StepProgressIndicator
import com.pdfsigner.app.ui.viewmodel.ExportResult
import com.pdfsigner.app.ui.viewmodel.PdfSignerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: PdfSignerViewModel,
    onStartOver: () -> Unit,
    onShare: (Uri) -> Unit,
    onHome: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    fun openFolder(isDownloads: Boolean) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Intent.ACTION_VIEW).apply {
                data = if (isDownloads)
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                else
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse(if (isDownloads)
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toURI().toString()
                    else
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toURI().toString()
                    ), "resource/folder"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching { context.startActivity(intent) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                actions = {
                    if (onHome != null) {
                        IconButton(onClick = onHome) {
                            Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.nav_home))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
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
            StepProgressIndicator(currentStep = AppStep.EXPORT)

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.exporting_wait),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Result feedback
                    when (val result = uiState.exportResult) {
                        is ExportResult.Success -> ExportSuccessCard(
                            format        = result.format,
                            onShare       = { onShare(result.uri) },
                            onOpenFolder  = { openFolder(result.format == ExportFormat.PDF) }
                        )
                        is ExportResult.Error -> ExportErrorCard(message = result.message)
                        null -> {
                            Text(
                                text      = stringResource(R.string.choose_export_format),
                                style     = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier  = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                text  = stringResource(R.string.export_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Format cards
                    ExportFormatCard(
                        icon        = Icons.Filled.PictureAsPdf,
                        label       = stringResource(R.string.save_as_pdf),
                        description = stringResource(R.string.save_as_pdf_desc),
                        tint        = MaterialTheme.colorScheme.error,
                        onClick     = { viewModel.clearExportResult(); viewModel.exportAs(ExportFormat.PDF) }
                    )
                    ExportFormatCard(
                        icon        = Icons.Filled.Image,
                        label       = stringResource(R.string.save_as_png),
                        description = stringResource(R.string.save_as_png_desc),
                        tint        = MaterialTheme.colorScheme.primary,
                        onClick     = { viewModel.clearExportResult(); viewModel.exportAs(ExportFormat.PNG) }
                    )
                    ExportFormatCard(
                        icon        = Icons.Filled.Photo,
                        label       = stringResource(R.string.save_as_jpg),
                        description = stringResource(R.string.save_as_jpg_desc),
                        tint        = MaterialTheme.colorScheme.secondary,
                        onClick     = { viewModel.clearExportResult(); viewModel.exportAs(ExportFormat.JPG) }
                    )

                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick  = onStartOver,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Filled.Home, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.start_over))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ExportFormatCard(
    icon: ImageVector,
    label: String,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        onClick   = onClick,
        shape     = MaterialTheme.shapes.large,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tint.copy(alpha = 0.1f),
                        androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(label,
                    style     = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ExportSuccessCard(format: ExportFormat, onShare: () -> Unit, onOpenFolder: () -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape    = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.CheckCircle, null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp))
                Column {
                    Text(stringResource(R.string.saved_success),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        text  = when (format) {
                            ExportFormat.PDF -> stringResource(R.string.saved_to_downloads)
                            ExportFormat.PNG -> stringResource(R.string.saved_to_pictures_png)
                            ExportFormat.JPG -> stringResource(R.string.saved_to_pictures_jpg)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick  = onOpenFolder,
                    modifier = Modifier.weight(1f),
                    shape    = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.open_folder),
                        style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick  = onShare,
                    modifier = Modifier.weight(1f),
                    shape    = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ExportErrorCard(message: String) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape    = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(20.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.Error, null,
                tint     = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp))
            Column {
                Text(stringResource(R.string.export_failed),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onErrorContainer)
                Text(message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

