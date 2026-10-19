package com.pdfsigner.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfsigner.app.R
import com.pdfsigner.app.ui.theme.Brand700
import com.pdfsigner.app.ui.viewmodel.DevicePdfFile
import com.pdfsigner.app.ui.viewmodel.PdfPickerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Full-screen in-app PDF picker.
 * Shows all PDFs found on the device in a 2-column grid with thumbnail previews.
 * Also offers a fallback "Browse files" button that opens the system file picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPickerScreen(
    title: String,
    onPicked: (Uri) -> Unit,
    onBack: () -> Unit
) {
    val vm: PdfPickerViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current

    // Collect filtered list reactively from the StateFlow
    val files by vm.filteredFiles.collectAsState()

    // System picker fallback
    val systemPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistable permission for reliable access
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onPicked(uri)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = { vm.loadFiles() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    // System picker fallback
                    IconButton(onClick = { systemPickerLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.browse_files))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor             = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor          = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor     = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ?? Search bar ????????????????????????????????????????????????????
            OutlinedTextField(
                value         = uiState.query,
                onValueChange = { vm.setQuery(it) },
                placeholder   = { Text(stringResource(R.string.search_pdfs)) },
                leadingIcon   = { Icon(Icons.Filled.Search, null) },
                trailingIcon  = {
                    AnimatedVisibility(uiState.query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, null)
                        }
                    }
                },
                singleLine    = true,
                shape         = MaterialTheme.shapes.large,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )

            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.scanning_device),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                files.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(Icons.Filled.SearchOff, null,
                                tint     = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(64.dp))
                            Text(
                                if (uiState.query.isNotEmpty())
                                    stringResource(R.string.no_pdf_found_query, uiState.query)
                                else
                                    stringResource(R.string.no_pdfs_on_device),
                                style     = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { systemPickerLauncher.launch(arrayOf("application/pdf")) },
                                shape   = MaterialTheme.shapes.large
                            ) {
                                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.browse_files))
                            }
                        }
                    }
                }

                else -> {
                    // File count header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.pdf_count, files.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.thumbnailsLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier  = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp
                                )
                                Text(stringResource(R.string.loading_previews),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    LazyVerticalGrid(
                        columns               = GridCells.Fixed(2),
                        contentPadding        = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement   = Arrangement.spacedBy(10.dp),
                        modifier              = Modifier.fillMaxSize()
                    ) {
                        items(files, key = { it.uri.toString() }) { file ->
                            PdfFileCard(
                                file    = file,
                                onClick = { onPicked(file.uri) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Single PDF card with thumbnail
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun PdfFileCard(
    file: DevicePdfFile,
    onClick: () -> Unit
) {
    val dateStr = remember(file.dateModifiedSec) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            .format(Date(file.dateModifiedSec * 1000L))
    }
    val sizeStr = remember(file.sizeBytes) { formatFileSize(file.sizeBytes) }

    Card(
        onClick   = onClick,
        shape     = MaterialTheme.shapes.large,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column {
            // Thumbnail area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)   // portrait PDF proportions
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clip(
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val thumb = file.thumbnail
                if (thumb != null) {
                    Image(
                        bitmap             = thumb.asImageBitmap(),
                        contentDescription = file.name,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize()
                    )
                    // Subtle overlay gradient at bottom
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))
                                )
                            )
                    )
                } else {
                    // Placeholder while thumbnail loads
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, null,
                            tint = Brand700.copy(alpha = 0.4f),
                            modifier = Modifier.size(40.dp))
                        CircularProgressIndicator(
                            modifier    = Modifier.size(16.dp),
                            strokeWidth = 1.5.dp,
                            color       = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }

                // "PDF" badge top-right
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Brand700.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        "PDF",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.White,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // File info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text     = file.name,
                    style    = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(sizeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Helpers
// ?????????????????????????????????????????????????????????????????????????????

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups])
}
