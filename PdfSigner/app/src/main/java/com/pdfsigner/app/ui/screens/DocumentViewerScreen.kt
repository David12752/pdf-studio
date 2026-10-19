package com.pdfsigner.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.pdfsigner.app.R
import com.pdfsigner.app.ui.viewmodel.DocType
import com.pdfsigner.app.ui.viewmodel.DocumentViewerViewModel
import com.pdfsigner.app.ui.viewmodel.PdfStats
import com.pdfsigner.app.ui.viewmodel.ReadingMode
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    viewModel: DocumentViewerViewModel,
    onBack: () -> Unit,
    onSignPdf: ((Uri) -> Unit)? = null,
    onHome: (() -> Unit)? = null,
    onRemoteSign: (() -> Unit)? = null
) {
    val uiState      by viewModel.uiState.collectAsState()
    val context      = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showControls    by remember { mutableStateOf(false) }
    var showReadingMenu by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showStatsSheet  by remember { mutableStateOf(false) }
    var showPageJump    by remember { mutableStateOf(false) }

    // Global zoom state — applies uniformly to all pages
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun resetZoom() { scale = 1f; offset = Offset.Zero }

    // LazyColumn state for page navigation
    val listState = rememberLazyListState()

    // Auto-hide controls after 4 s
    LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(4000)
            showControls = false
        }
    }
    LaunchedEffect(uiState.requiresPassword) {
        if (uiState.requiresPassword) showPasswordDialog = true
    }

    // Track current visible page from scroll
    val visiblePage by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    BackHandler { if (onHome != null) onHome() else onBack() }

    fun shareFile() {
        val rawUri = uiState.uri ?: return
        val shareUri = if (rawUri.scheme == "file") {
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(rawUri.path!!)) }.getOrElse { rawUri }
        } else rawUri
        val mime = uiState.mimeType.ifBlank { context.contentResolver.getType(shareUri) ?: "*/*" }
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, null))
        }.onFailure { Toast.makeText(context, context.getString(R.string.file_open_failed), Toast.LENGTH_SHORT).show() }
    }

    fun openExternal() {
        val editUri = uiState.cachedFilePath?.let { path ->
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path)) }.getOrElse { Uri.fromFile(File(path)) }
        } ?: uiState.uri ?: return
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(editUri, uiState.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }, context.getString(R.string.open_with)))
        }.onFailure { Toast.makeText(context, context.getString(R.string.file_open_failed), Toast.LENGTH_SHORT).show() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {

        when {
            uiState.isLoading && uiState.renderedPages.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                        Text(stringResource(R.string.processing), color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(64.dp))
                        Text(uiState.error!!, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        OutlinedButton(onClick = { viewModel.clearError(); onBack() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }

            // ?? PDF: CONTINUOUS SCROLL ????????????????????????????????????
            uiState.docType == DocType.PDF && uiState.renderedPages.isNotEmpty() -> {
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    scale  = (scale * zoomChange).coerceIn(0.5f, 6f)
                    offset += panChange
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) resetZoom() else scale = 2.5f }) { showControls = !showControls } }
                ) {
                    LazyColumn(
                        state            = listState,
                        contentPadding   = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier         = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX       = scale
                                scaleY       = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    ) {
                        itemsIndexed(uiState.renderedPages) { pageIndex, bitmap ->
                            PdfPageItem(
                                pageIndex = pageIndex,
                                bitmap    = bitmap,
                                isVisible = pageIndex == visiblePage
                            )
                        }
                    }
                }
            }

            // ?? IMAGE ?????????????????????????????????????????????????????
            uiState.docType == DocType.IMAGE && uiState.imageBitmap != null -> {
                var imgScale  by remember { mutableFloatStateOf(1f) }
                var imgOffset by remember { mutableStateOf(Offset.Zero) }
                Box(
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                imgScale  = (imgScale * zoom).coerceIn(0.5f, 6f)
                                imgOffset += pan
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (imgScale > 1f) { imgScale = 1f; imgOffset = Offset.Zero } else imgScale = 2.5f }) { showControls = !showControls } },
                    contentAlignment = Alignment.Center
                ) {
                    Image(bitmap = uiState.imageBitmap!!.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = imgScale; scaleY = imgScale; translationX = imgOffset.x; translationY = imgOffset.y },
                        contentScale = ContentScale.Fit)
                }
            }

            // ?? TEXT ??????????????????????????????????????????????????????
            uiState.textContent != null -> {
                val scrollState = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))
                    .pointerInput(Unit) { detectTapGestures { showControls = !showControls } }) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp).padding(top = 80.dp, bottom = 120.dp)) {
                        Text(text = uiState.textContent!!.ifBlank { "(Empty document)" }, color = Color.White.copy(0.9f),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 26.sp,
                                fontFamily = if (uiState.docType == DocType.TEXT) FontFamily.Monospace else FontFamily.Default))
                    }
                }
            }

            else -> {
                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { showControls = !showControls } }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(72.dp))
                        Text(stringResource(R.string.viewer_no_preview), color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        Button(onClick = { openExternal() }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.viewer_open_external))
                        }
                    }
                }
            }
        }

        // ?? TOP BAR ???????????????????????????????????????????????????????
        AnimatedVisibility(visible = showControls, enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(220)), modifier = Modifier.align(Alignment.TopCenter)) {
            Box(modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(0.85f), Color.Transparent)))
                .statusBarsPadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = if (onHome != null) onHome else onBack) {
                        Icon(if (onHome != null) Icons.Filled.Home else Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(uiState.fileName, color = Color.White, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (uiState.docType == DocType.PDF && uiState.pageCount > 0) {
                            Text(stringResource(R.string.page_x_of_y, visiblePage + 1, uiState.pageCount),
                                color = Color.White.copy(0.55f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (uiState.docType == DocType.PDF) {
                        // Page jump button
                        IconButton(onClick = { showPageJump = true }) {
                            Icon(Icons.Filled.FormatListNumbered, null, tint = Color.White)
                        }
                        IconButton(onClick = { showReadingMenu = true }) {
                            Icon(Icons.Filled.Palette, null, tint = Color.White)
                        }
                        IconButton(onClick = { showStatsSheet = true }) {
                            Icon(Icons.Filled.BarChart, null, tint = Color.White)
                        }
                    }
                    IconButton(onClick = { shareFile() }) {
                        Icon(Icons.Filled.Share, null, tint = Color.White)
                    }
                }
            }
        }

        // ?? BOTTOM BAR ????????????????????????????????????????????????????
        AnimatedVisibility(visible = showControls, enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.90f))))
                .navigationBarsPadding()) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {

                    // PDF page scrubber
                    if (uiState.docType == DocType.PDF && uiState.pageCount > 1) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Prev page
                            IconButton(onClick = {
                                val prev = (visiblePage - 1).coerceAtLeast(0)
                                coroutineScope.launch { listState.animateScrollToItem(prev) }
                            }, enabled = visiblePage > 0) {
                                Icon(Icons.AutoMirrored.Filled.NavigateBefore, null,
                                    tint = if (visiblePage > 0) Color.White else Color.White.copy(0.3f))
                            }
                            // Page slider
                            Slider(
                                value         = visiblePage.toFloat(),
                                onValueChange = { v ->
                                    coroutineScope.launch { listState.scrollToItem(v.toInt()) }
                                },
                                valueRange    = 0f..(uiState.pageCount - 1).toFloat().coerceAtLeast(0f),
                                steps         = (uiState.pageCount - 2).coerceAtLeast(0),
                                modifier      = Modifier.weight(1f),
                                colors        = SliderDefaults.colors(
                                    thumbColor        = Color.White,
                                    activeTrackColor  = Color.White.copy(0.8f),
                                    inactiveTrackColor = Color.White.copy(0.3f)
                                )
                            )
                            // Next page
                            IconButton(onClick = {
                                val next = (visiblePage + 1).coerceAtMost(uiState.pageCount - 1)
                                coroutineScope.launch { listState.animateScrollToItem(next) }
                            }, enabled = visiblePage < uiState.pageCount - 1) {
                                Icon(Icons.AutoMirrored.Filled.NavigateNext, null,
                                    tint = if (visiblePage < uiState.pageCount - 1) Color.White else Color.White.copy(0.3f))
                            }
                        }
                    }

                    // Action row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Fit / reset zoom
                        if (uiState.docType in listOf(DocType.PDF, DocType.IMAGE)) {
                            ViewerActionChip(icon = Icons.Filled.FitScreen, label = stringResource(R.string.viewer_fit), onClick = { resetZoom() })
                        }
                        if (uiState.docType == DocType.PDF) {
                            ViewerActionChip(
                                icon    = if (uiState.qrStampInProgress) Icons.Filled.HourglassTop else Icons.Filled.QrCode,
                                label   = stringResource(R.string.viewer_qr_stamp),
                                enabled = !uiState.qrStampInProgress,
                                onClick = { viewModel.stampQrCode() }
                            )
                            // Remote sign chip
                            if (onRemoteSign != null) {
                                ViewerActionChip(
                                    icon    = Icons.Filled.Send,
                                    label   = stringResource(R.string.remote_sign_button),
                                    onClick = onRemoteSign
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (uiState.docType == DocType.PDF && onSignPdf != null) {
                            Button(onClick = { uiState.uri?.let { onSignPdf(it) } },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                                Icon(Icons.Filled.Draw, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.nav_sign), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        } else if (uiState.docType == DocType.PDF && onRemoteSign != null) {
                            Button(onClick = { onRemoteSign() },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                                Icon(Icons.Filled.Draw, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.nav_sign_remote), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // ?? QR done snack ?????????????????????????????????????????????????
        if (uiState.qrStampDone) {
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(3000); viewModel.dismissQrDone() }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) {
                Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF1B5E20), shadowElevation = 6.dp) {
                    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.QrCode, null, tint = Color.White)
                        Text(stringResource(R.string.viewer_qr_done), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Dialogs
        if (showReadingMenu)
            ReadingModeMenu(current = uiState.readingMode, onSelect = { viewModel.setReadingMode(it); showReadingMenu = false }, onDismiss = { showReadingMenu = false })
        if (showStatsSheet && uiState.pdfStats != null)
            PdfStatsSheet(stats = uiState.pdfStats!!, onDismiss = { showStatsSheet = false })
        if (showPasswordDialog)
            PasswordDialog(hasError = uiState.passwordError, onConfirm = { pwd -> showPasswordDialog = false; viewModel.unlockWithPassword(pwd) }, onDismiss = { showPasswordDialog = false; onBack() })
        if (showPageJump && uiState.pageCount > 0)
            PageJumpDialog(current = visiblePage, pageCount = uiState.pageCount,
                onJump = { page -> showPageJump = false; coroutineScope.launch { listState.animateScrollToItem(page) } },
                onDismiss = { showPageJump = false })
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Single page item in continuous scroll
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun PdfPageItem(pageIndex: Int, bitmap: android.graphics.Bitmap?, isVisible: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap           = bitmap.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    modifier         = Modifier.fillMaxWidth(),
                    contentScale     = ContentScale.FillWidth
                )
            } else {
                // Loading placeholder — same aspect ratio as A4
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color(0xFFF5F5F5)),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp, color = Color(0xFFBBBBBB))
                }
            }
        }
        // Page number label
        Text(
            text  = "${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(0.45f)
        )
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Page jump dialog
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun PageJumpDialog(current: Int, pageCount: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("${current + 1}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Filled.FormatListNumbered, null) },
        title = { Text(stringResource(R.string.viewer_page_label)) },
        text  = {
            OutlinedTextField(
                value         = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                singleLine    = true,
                label         = { Text("1 – $pageCount") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier      = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                val page = (text.toIntOrNull() ?: 1).coerceIn(1, pageCount) - 1
                onJump(page)
            }) { Text(stringResource(R.string.use)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ?????????????????????????????????????????????????????????????????????????????
// Shared composables (unchanged)
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun ViewerActionChip(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50),
        color = Color.White.copy(if (enabled) 0.12f else 0.05f)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = if (enabled) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(16.dp))
            Text(label, color = if (enabled) Color.White else Color.White.copy(0.3f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ReadingModeMenu(current: ReadingMode, onSelect: (ReadingMode) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.viewer_reading_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                listOf(
                    ReadingMode.NORMAL to Pair(Icons.Filled.LightMode,   Color(0xFF607D8B)),
                    ReadingMode.SEPIA  to Pair(Icons.Filled.WbSunny,     Color(0xFF795548)),
                    ReadingMode.WARM   to Pair(Icons.Filled.Brightness5, Color(0xFFFF8F00)),
                    ReadingMode.COOL   to Pair(Icons.Filled.AcUnit,      Color(0xFF1565C0)),
                    ReadingMode.NIGHT  to Pair(Icons.Filled.NightsStay,  Color(0xFF212121))
                ).forEach { (mode, pair) ->
                    val (icon, color) = pair
                    val selected = current == mode
                    Surface(onClick = { onSelect(mode) }, shape = RoundedCornerShape(12.dp),
                        color = if (selected) color.copy(0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (selected) Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfStatsSheet(stats: PdfStats, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.viewer_stats_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp)) }
                }
                HorizontalDivider()
                StatRow(Icons.Filled.AutoStories, stringResource(R.string.viewer_stats_pages), stats.pageCount.toString())
                StatRow(Icons.Filled.TextFields, stringResource(R.string.viewer_stats_words), "%,d".format(stats.wordCount))
                StatRow(Icons.Filled.FormatSize, stringResource(R.string.viewer_stats_chars), "%,d".format(stats.charCount))
                StatRow(Icons.Filled.Timer, stringResource(R.string.viewer_stats_read_time), if (stats.readingTimeMin == 0) "< 1 min" else "${stats.readingTimeMin} min")
                StatRow(Icons.Filled.Storage, stringResource(R.string.viewer_stats_size), formatBytes(stats.fileSizeBytes))
            }
        }
    }
}

@Composable
private fun StatRow(icon: ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PasswordDialog(hasError: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Filled.Lock, null) },
        title = { Text(stringResource(R.string.viewer_password_title)) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.viewer_password_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = pwd, onValueChange = { pwd = it },
                    label = { Text(stringResource(R.string.protect_password_label)) },
                    isError = hasError,
                    supportingText = if (hasError) { { Text(stringResource(R.string.viewer_password_wrong), color = MaterialTheme.colorScheme.error) } } else null,
                    singleLine = true,
                    visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(pwd) }, enabled = pwd.isNotBlank()) { Text(stringResource(R.string.viewer_password_unlock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val g = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / 1024.0.pow(g.toDouble()), units[g])
}
