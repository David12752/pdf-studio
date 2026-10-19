package com.pdfsigner.app.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.TextStampPlacement
import com.pdfsigner.app.ui.components.AppStep
import com.pdfsigner.app.ui.components.PdfPageWithSignatureOverlay
import com.pdfsigner.app.ui.components.StepProgressIndicator
import com.pdfsigner.app.ui.viewmodel.PdfSignerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacementScreen(
    viewModel: PdfSignerViewModel,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onAddText: () -> Unit,
    onHome: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val doc = uiState.pdfDocument ?: return
    val pageBitmap = uiState.renderedPageBitmap ?: return
    val signatureBitmap = uiState.signatureBitmap ?: return

    val view = LocalView.current
    var showStampMenu by remember { mutableStateOf(false) }
    var dateAddedSnack by remember { mutableStateOf(false) }
    var stampAddedSnack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.place_signature_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
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
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = {
            if (dateAddedSnack) {
                Snackbar(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.auto_date_added))
                }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    dateAddedSnack = false
                }
            }
            if (stampAddedSnack) {
                Snackbar(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.stamp_added))
                }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    stampAddedSnack = false
                }
            }
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (doc.pageCount > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledIconButton(
                                onClick = { viewModel.navigateToPage(uiState.currentPageIndex - 1) },
                                enabled = uiState.currentPageIndex > 0,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = stringResource(R.string.previous_page))
                            }

                            Text(
                                text = stringResource(R.string.page_x_of_y, uiState.currentPageIndex + 1, doc.pageCount),
                                style = MaterialTheme.typography.titleSmall
                            )

                            FilledIconButton(
                                onClick = { viewModel.navigateToPage(uiState.currentPageIndex + 1) },
                                enabled = uiState.currentPageIndex < doc.pageCount - 1,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = stringResource(R.string.next_page))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Quick action chips row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Add Text button
                        AssistChip(
                            onClick = onAddText,
                            label = { Text(stringResource(R.string.add_text_button), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Filled.TextFields, null, Modifier.size(16.dp)) }
                        )

                        // Auto-Date button
                        AssistChip(
                            onClick = {
                                val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                                viewModel.addTextPlacement(
                                    TextStampPlacement(
                                        text = dateStr,
                                        pageIndex = uiState.currentPageIndex,
                                        positionPx = Offset(
                                            pageBitmap.width * 0.65f,
                                            pageBitmap.height * 0.85f
                                        ),
                                        fontSizePt = 14f,
                                        color = Color.Black.toArgb(),
                                        isBold = false,
                                        renderedPageWidthPx = pageBitmap.width,
                                        renderedPageHeightPx = pageBitmap.height
                                    )
                                )
                                dateAddedSnack = true
                            },
                            label = { Text(stringResource(R.string.auto_date_button), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Filled.CalendarToday, null, Modifier.size(16.dp)) }
                        )

                        // Visual Stamp button
                        AssistChip(
                            onClick = { showStampMenu = true },
                            label = { Text(stringResource(R.string.visual_stamp_title), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Confirm
                    Button(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onConfirm()
                        },
                        enabled  = uiState.placement != null,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = MaterialTheme.shapes.large
                    ) {
                        Text(stringResource(R.string.confirm_placement),
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                StepProgressIndicator(currentStep = AppStep.PLACE)
                Spacer(Modifier.height(4.dp))
                PdfPageWithSignatureOverlay(
                    pageBitmap = pageBitmap,
                    signatureBitmap = signatureBitmap,
                    pageIndex = uiState.currentPageIndex,
                    onPlacementChanged = { placement -> viewModel.setSignaturePlacement(placement) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }

    // Visual Stamps dropdown menu
    if (showStampMenu) {
        val stamps = listOf(
            Pair(stringResource(R.string.stamp_approved), Color(0xFF2E7D32)),
            Pair(stringResource(R.string.stamp_confidential), Color(0xFFC62828)),
            Pair(stringResource(R.string.stamp_draft), Color(0xFF1565C0)),
            Pair(stringResource(R.string.stamp_final), Color(0xFF2E7D32)),
            Pair(stringResource(R.string.stamp_copy), Color(0xFF6A1B9A)),
            Pair(stringResource(R.string.stamp_void), Color(0xFFC62828)),
        )
        AlertDialog(
            onDismissRequest = { showStampMenu = false },
            title = { Text(stringResource(R.string.visual_stamp_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stamps.forEach { pair ->
                        val stampLabel = pair.first
                        val stampColor = pair.second
                        OutlinedButton(
                            onClick = {
                                viewModel.addTextPlacement(
                                    TextStampPlacement(
                                        text = stampLabel,
                                        pageIndex = uiState.currentPageIndex,
                                        positionPx = Offset(
                                            pageBitmap.width * 0.3f,
                                            pageBitmap.height * 0.4f
                                        ),
                                        fontSizePt = 36f,
                                        color = stampColor.toArgb(),
                                        isBold = true,
                                        renderedPageWidthPx = pageBitmap.width,
                                        renderedPageHeightPx = pageBitmap.height
                                    )
                                )
                                showStampMenu = false
                                stampAddedSnack = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stampLabel, color = stampColor)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStampMenu = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

