package com.pdfsigner.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.service.PdfSignatureService
import com.pdfsigner.app.ui.viewmodel.PasswordDialogTarget
import com.pdfsigner.app.ui.viewmodel.PdfToolsViewModel
import com.pdfsigner.app.ui.viewmodel.WatermarkPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAB_MERGE     = "merge"
private const val TAB_SPLIT     = "split"
private const val TAB_EXTRACT   = "extract"
private const val TAB_CONVERT   = "convert"
private const val TAB_WATERMARK = "watermark"
private const val TAB_COMPRESS  = "compress"
private const val TAB_PROTECT   = "protect"
private const val TAB_AUDIT     = "audit"
private const val TAB_OPEN_FILE = "open_file"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(
    toolsViewModel: PdfToolsViewModel,
    onOpenPicker: (mode: String) -> Unit,
    onBack: () -> Unit,
    onViewFile: ((Uri) -> Unit)? = null
) {
    val viewModel    = toolsViewModel
    val uiState      by viewModel.uiState.collectAsState()
    val context      = LocalContext.current
    val expandedTool = uiState.expandedTool

    fun toggle(tab: String) = viewModel.setExpandedTool(if (expandedTool == tab) null else tab)

    fun Uri.displayName(): String {
        if (scheme == "file") return lastPathSegment ?: "file"
        return runCatching {
            context.contentResolver.query(this, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                c.moveToFirst(); if (idx >= 0) c.getString(idx) else null
            }
        }.getOrNull() ?: lastPathSegment ?: "file"
    }

    fun openDownloadsFolder() {
        listOf(
            Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary:Download"), "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ).forEach { runCatching { context.startActivity(it); return } }
        Toast.makeText(context, context.getString(R.string.file_open_failed), Toast.LENGTH_SHORT).show()
    }

    fun openFileWith(uri: Uri) {
        if (onViewFile != null) { onViewFile(uri); return }
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        runCatching {
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }, context.getString(R.string.open_with)))
        }.onFailure { Toast.makeText(context, context.getString(R.string.file_open_failed), Toast.LENGTH_SHORT).show() }
    }

    // ?? Pickers ?????????????????????????????????????????????????????????????
    val splitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.loadSplitUri(uri)
        }
    }
    val extractPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.loadExtractUri(uri)
        }
    }
    val convertPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.loadConvertUri(uri)
        }
    }
    val watermarkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.loadWatermarkUri(uri)
        }
    }
    val compressPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.loadCompressUri(uri)
        }
    }
    val protectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.loadProtectUri(uri)
        }
    }
    val auditPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.loadAuditUri(uri)
        }
    }
    val multiPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.addMergeUri(uri)
        }
        if (uris.isNotEmpty()) viewModel.setExpandedTool(TAB_MERGE)
    }
    val excelPicker   = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; openFileWith(uri) } }
    val wordPicker    = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; openFileWith(uri) } }
    val pptPicker     = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; openFileWith(uri) } }
    val imagePicker   = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; openFileWith(uri) } }
    val textPicker    = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; openFileWith(uri) } }
    val anyPicker     = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; openFileWith(uri) } }

    val watermarkImagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.setWatermarkImageUri(uri)
            viewModel.setWatermarkUseImage(true)
        }
    }

    // ?? Auto-open result ?????????????????????????????????????????????????????
    val resultUri = uiState.resultUri
    LaunchedEffect(resultUri) {
        if (resultUri != null) {
            viewModel.clearResultUri()
            val mime = context.contentResolver.getType(resultUri) ?: "application/pdf"
            if (mime == "application/pdf" && onViewFile != null) {
                onViewFile(resultUri)
            } else {
                runCatching {
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(resultUri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }, null))
                }
            }
        }
    }

    // ?? Split folder name dialog ?????????????????????????????????????????????
    if (uiState.splitFolderNameDialogShowing) {
        SplitFolderNameDialog(
            suggestedName = uiState.splitSourceName.ifBlank { "Split_${System.currentTimeMillis()}" },
            onConfirm     = { name -> viewModel.saveSplitWithFolderName(name) },
            onDismiss     = { viewModel.dismissSplitFolderDialog() }
        )
    }

    // ?? Password dialog ??????????????????????????????????????????????????????
    if (uiState.passwordDialogTarget != PasswordDialogTarget.NONE) {
        PdfPasswordDialog(
            isWrongPassword = uiState.passwordDialogError,
            onConfirm       = { pwd -> viewModel.submitPassword(pwd) },
            onDismiss       = { viewModel.dismissPasswordDialog() }
        )
    }

    // ?? Main scaffold ????????????????????????????????????????????????????????
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pdf_tools_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            uiState.resultMessage?.let {
                val msg = when (it) {
                    "convert_done" -> stringResource(R.string.convert_result_saved)
                    else           -> stringResource(R.string.result_saved)
                }
                SuccessBanner(message = msg, onOpenFolder = { openDownloadsFolder() })
                LaunchedEffect(it) { delay(5000); viewModel.clearResult() }
            }
            uiState.error?.let { err ->
                ErrorBanner(message = err)
                LaunchedEffect(err) { delay(4000); viewModel.clearResult() }
            }

            if (uiState.isLoading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.processing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                ToolSection(Icons.AutoMirrored.Filled.MergeType, stringResource(R.string.merge_pdfs), Color(0xFF1565C0), expandedTool == TAB_MERGE, { toggle(TAB_MERGE) }) {
                    MergePanel(uiState.mergeUris, { multiPickLauncher.launch(arrayOf("application/pdf")) }, viewModel::removeMergeUri, viewModel::mergePdfs) { it.displayName() }
                }
                ToolSection(Icons.AutoMirrored.Filled.CallSplit, stringResource(R.string.split_pdf), Color(0xFF00838F), expandedTool == TAB_SPLIT, { toggle(TAB_SPLIT) }) {
                    SplitPanel(
                        sourceUri      = uiState.splitUri,
                        pageCount      = uiState.splitPageCount,
                        selectedPages  = uiState.splitSelectedPages,
                        onPick         = { splitPicker.launch(arrayOf("application/pdf")) },
                        onTogglePage   = viewModel::toggleSplitPage,
                        onSelectAll    = viewModel::selectAllSplitPages,
                        onClear        = viewModel::clearSplitSelection,
                        onSplit        = viewModel::requestSplitFolder,
                        displayName    = { it.displayName() }
                    )
                }
                ToolSection(Icons.Filled.ContentCut, stringResource(R.string.extract_page), Color(0xFF6A1B9A), expandedTool == TAB_EXTRACT, { toggle(TAB_EXTRACT) }) {
                    ExtractPanel(uiState.extractUri, uiState.extractPageCount, uiState.extractPageIndex, { extractPicker.launch(arrayOf("application/pdf")) }, viewModel::setExtractPageIndex, viewModel::extractPage) { it.displayName() }
                }
                ToolSection(Icons.AutoMirrored.Filled.TextSnippet, stringResource(R.string.convert_to_word), Color(0xFF2E7D32), expandedTool == TAB_CONVERT, { toggle(TAB_CONVERT) }) {
                    ConvertPanel(uiState.convertUri, { convertPicker.launch(arrayOf("application/pdf")) }, viewModel::convertPdfToText) { it.displayName() }
                }
                ToolSection(Icons.Filled.WaterDrop, stringResource(R.string.watermark), Color(0xFF0277BD), expandedTool == TAB_WATERMARK, { toggle(TAB_WATERMARK) }) {
                    WatermarkPanel(
                        sourceUri        = uiState.watermarkUri,
                        text             = uiState.watermarkText,
                        opacity          = uiState.watermarkOpacity,
                        useImage         = uiState.watermarkUseImage,
                        imageUri         = uiState.watermarkImageUri,
                        position         = uiState.watermarkPosition,
                        onPick           = { watermarkPicker.launch(arrayOf("application/pdf")) },
                        onPickImage      = { watermarkImagePickerLauncher.launch(arrayOf("image/png","image/jpeg","image/gif","image/webp","image/bmp")) },
                        onTextChange     = viewModel::setWatermarkText,
                        onOpacityChange  = viewModel::setWatermarkOpacity,
                        onUseImageChange = viewModel::setWatermarkUseImage,
                        onPositionChange = viewModel::setWatermarkPosition,
                        onApply          = viewModel::addWatermark,
                        displayName      = { it.displayName() }
                    )
                }
                ToolSection(Icons.Filled.Compress, stringResource(R.string.compress_pdf), Color(0xFFE65100), expandedTool == TAB_COMPRESS, { toggle(TAB_COMPRESS) }) {
                    CompressPanel(uiState.compressUri, uiState.compressQuality, { compressPicker.launch(arrayOf("application/pdf")) }, viewModel::setCompressQuality, viewModel::compressPdf) { it.displayName() }
                }
                ToolSection(Icons.Filled.Lock, stringResource(R.string.protect_pdf), Color(0xFFC62828), expandedTool == TAB_PROTECT, { toggle(TAB_PROTECT) }) {
                    ProtectPanel(uiState.protectUri, uiState.protectPassword, { protectPicker.launch(arrayOf("application/pdf")) }, viewModel::setProtectPassword, viewModel::protectPdf) { it.displayName() }
                }
                ToolSection(Icons.Filled.VerifiedUser, stringResource(R.string.audit_trail), Color(0xFF37474F), expandedTool == TAB_AUDIT, { toggle(TAB_AUDIT) }) {
                    AuditPanel(uiState.auditUri, uiState.auditSignerName, { auditPicker.launch(arrayOf("application/pdf")) }, viewModel::setAuditSignerName, viewModel::addAuditTrail) { it.displayName() }
                }
                ToolSection(Icons.AutoMirrored.Filled.InsertDriveFile, stringResource(R.string.open_file), Color(0xFF7B1FA2), expandedTool == TAB_OPEN_FILE, { toggle(TAB_OPEN_FILE) }) {
                    OpenFilePanel(
                        { excelPicker.launch(arrayOf("application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.oasis.opendocument.spreadsheet","text/csv")) },
                        { wordPicker.launch(arrayOf("application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.oasis.opendocument.text","application/rtf")) },
                        { pptPicker.launch(arrayOf("application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.oasis.opendocument.presentation")) },
                        { imagePicker.launch(arrayOf("image/png","image/jpeg","image/gif","image/webp","image/bmp","image/svg+xml")) },
                        { textPicker.launch(arrayOf("text/plain","text/html","text/xml","application/json","text/markdown")) },
                        { anyPicker.launch(arrayOf("*/*")) }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Password dialog
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun PdfPasswordDialog(
    isWrongPassword: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var visible  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.viewer_password_title)) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.viewer_password_desc),
                    style = MaterialTheme.typography.bodyMedium)
                if (isWrongPassword) {
                    Text(stringResource(R.string.viewer_password_wrong),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        }
                    },
                    isError = isWrongPassword,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.viewer_password_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ?????????????????????????????????????????????????????????????????????????????
// Page thumbnail picker dialog (for Extract and Split preview)
// ?????????????????????????????????????????????????????????????????????????????

@Composable
fun PagePickerDialog(
    uri: Uri,
    pageCount: Int,
    password: String?,
    selectedPage: Int,
    title: String,
    confirmLabel: String,
    onPageSelected: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pdfService = remember { PdfSignatureService(context) }

    // Render page thumbnails lazily
    val thumbnails = remember(uri, pageCount) { mutableStateMapOf<Int, Bitmap>() }
    LaunchedEffect(uri, pageCount) {
        withContext(Dispatchers.IO) {
            // Copy to local file once — needed for password-protecteddocs
            val tmp = runCatching {
                val f = java.io.File(context.cacheDir, "pgpick_${System.nanoTime()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(f.outputStream()) }
                f
            }.getOrNull()

            for (i in 0 until pageCount) {
                runCatching {
                    val bmp = if (tmp != null && password != null) {
                        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(tmp, password)
                        val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                        val b = renderer.renderImage(i, 0.5f)
                        doc.close()
                        b
                    } else {
                        pdfService.renderPageToBitmap(uri, i, 72f)
                    }
                    thumbnails[i] = bmp
                }
            }
            tmp?.delete()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.select_page_to_extract, selectedPage + 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    itemsIndexed(List(pageCount) { it }) { _, pageIdx ->
                        val thumb = thumbnails[pageIdx]
                        val isSelected = pageIdx == selectedPage
                        Box(
                            modifier = Modifier
                                .aspectRatio(0.75f)
                                .clip(MaterialTheme.shapes.medium)
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                    shape = MaterialTheme.shapes.medium
                                )
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onPageSelected(pageIdx) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumb != null) {
                                Image(bitmap = thumb.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxSize())
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            // Page number badge
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = (if (isSelected) MaterialTheme.colorScheme.primary
                                         else Color.Black).copy(alpha = 0.7f),
                                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp)
                            ) {
                                Text("${pageIdx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text(confirmLabel) }
                }
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Tool section card
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun ToolSection(
    icon: ImageVector, label: String, color: Color,
    expanded: Boolean, onClick: () -> Unit, content: @Composable () -> Unit
) {
    Card(
        shape     = MaterialTheme.shapes.large,
        colors    = CardDefaults.cardColors(containerColor = if (expanded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 2.dp else 0.dp)
    ) {
        Column {
            Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.large) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(modifier = Modifier.size(40.dp).background(color.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
                    }
                    Text(label, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Sub-panels
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun MergePanel(uris: List<Uri>, onAdd: () -> Unit, onRemove: (Uri) -> Unit, onMerge: () -> Unit, displayName: (Uri) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.merge_pdfs_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (uris.isEmpty()) {
            Text(stringResource(R.string.no_files_selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            uris.forEach { uri -> FileChip(name = displayName(uri), onRemove = { onRemove(uri) }) }
            Text(stringResource(R.string.files_selected, uris.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.add_pdf))
            }
            Button(onClick = onMerge, enabled = uris.size >= 2, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.merge_button)) }
        }
    }
}

@Composable
private fun SplitFolderNameDialog(
    suggestedName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.split_folder_name_title)) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.split_folder_name_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    singleLine    = true,
                    placeholder   = { Text(stringResource(R.string.split_folder_name_hint)) },
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.split_save_button))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun SplitPanel(
    sourceUri: Uri?,
    pageCount: Int,
    selectedPages: Set<Int>,
    onPick: () -> Unit,
    onTogglePage: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSplit: () -> Unit,
    displayName: (Uri) -> String
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.split_pdf_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (sourceUri == null) {
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_pdf))
            }
        } else {
            SelectedFileChip(name = displayName(sourceUri))
            Text(stringResource(R.string.pages_count, pageCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Page selection controls
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onSelectAll, label = { Text(stringResource(R.string.split_select_all), style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Filled.SelectAll, null, modifier = Modifier.size(14.dp)) })
                AssistChip(onClick = onClear, label = { Text(stringResource(R.string.split_clear_selection), style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Filled.ClearAll, null, modifier = Modifier.size(14.dp)) })
            }

            if (selectedPages.isNotEmpty()) {
                Text(stringResource(R.string.split_pages_selected, selectedPages.size),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            // Page number grid with checkboxes
            if (pageCount > 0) {
                val columns = 5
                val rows = (pageCount + columns - 1) / columns
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until columns) {
                                val pageIdx = row * columns + col
                                if (pageIdx >= pageCount) {
                                    Spacer(modifier = Modifier.weight(1f))
                                } else {
                                    val isSelected = pageIdx in selectedPages
                                    Surface(
                                        onClick     = { onTogglePage(pageIdx) },
                                        shape       = RoundedCornerShape(8.dp),
                                        color       = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                      else MaterialTheme.colorScheme.surfaceVariant,
                                        border      = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier    = Modifier.weight(1f).aspectRatio(1f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                "${pageIdx + 1}",
                                                style     = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color     = if (isSelected) MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.open_pdf_button)) }
                Button(onClick = onSplit, modifier = Modifier.weight(1f)) {
                    Text(if (selectedPages.isEmpty()) stringResource(R.string.split_button)
                         else stringResource(R.string.split_pages_selected, selectedPages.size))
                }
            }
        }
    }
}

@Composable
private fun WatermarkPanel(
    sourceUri: Uri?,
    text: String,
    opacity: Float,
    useImage: Boolean,
    imageUri: Uri?,
    position: WatermarkPosition,
    onPick: () -> Unit,
    onPickImage: () -> Unit,
    onTextChange: (String) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onUseImageChange: (Boolean) -> Unit,
    onPositionChange: (WatermarkPosition) -> Unit,
    onApply: () -> Unit,
    displayName: (Uri) -> String
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.watermark_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sourceUri != null) SelectedFileChip(name = displayName(sourceUri))

        // Mode toggle: Text vs Image
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !useImage,
                onClick  = { onUseImageChange(false) },
                label    = { Text(stringResource(R.string.watermark_mode_text), style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Filled.TextFields, null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = useImage,
                onClick  = { onUseImageChange(true) },
                label    = { Text(stringResource(R.string.watermark_mode_image), style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Filled.Image, null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f)
            )
        }

        if (!useImage) {
            // ?? Text mode ??
            OutlinedTextField(value = text, onValueChange = onTextChange,
                label = { Text(stringResource(R.string.watermark_text_label)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Text(stringResource(R.string.watermark_presets), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(stringResource(R.string.watermark_draft), stringResource(R.string.watermark_confidential), stringResource(R.string.watermark_internal)).forEach { preset ->
                    AssistChip(onClick = { onTextChange(preset) }, label = { Text(preset, style = MaterialTheme.typography.labelSmall) })
                }
            }
        } else {
            // ?? Image / logo mode ??
            OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Image, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (imageUri == null) stringResource(R.string.watermark_pick_image) else stringResource(R.string.watermark_image_selected))
            }
            if (imageUri != null) {
                // Position selector
                Text(stringResource(R.string.watermark_position_label), style = MaterialTheme.typography.labelMedium)
                val positions = listOf(
                    WatermarkPosition.CENTER       to stringResource(R.string.watermark_pos_center),
                    WatermarkPosition.TOP_LEFT     to stringResource(R.string.watermark_pos_top_left),
                    WatermarkPosition.TOP_RIGHT    to stringResource(R.string.watermark_pos_top_right),
                    WatermarkPosition.BOTTOM_LEFT  to stringResource(R.string.watermark_pos_bottom_left),
                    WatermarkPosition.BOTTOM_RIGHT to stringResource(R.string.watermark_pos_bottom_right),
                )
                // 3ª2 grid of position chips
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    positions.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (pos, label) ->
                                FilterChip(
                                    selected = position == pos,
                                    onClick  = { onPositionChange(pos) },
                                    label    = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // Opacity — always shown
        Text(stringResource(R.string.watermark_opacity_label, (opacity * 100).toInt()), style = MaterialTheme.typography.labelMedium)
        Slider(value = opacity, onValueChange = onOpacityChange, valueRange = 0.05f..1f, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) { Text(if (sourceUri == null) stringResource(R.string.add_pdf) else stringResource(R.string.open_pdf_button)) }
            Button(
                onClick  = onApply,
                enabled  = sourceUri != null && (!useImage || imageUri != null) && (useImage || text.isNotBlank()),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.watermark_button)) }
        }
    }
}

@Composable
private fun CompressPanel(sourceUri: Uri?, quality: Float, onPick: () -> Unit, onQualityChange: (Float) -> Unit, onCompress: () -> Unit, displayName: (Uri) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.compress_pdf_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sourceUri == null) {
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_pdf))
            }
        } else {
            SelectedFileChip(name = displayName(sourceUri))
            Text(stringResource(R.string.compress_quality_label, (quality * 100).toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(value = quality, onValueChange = onQualityChange, valueRange = 0.1f..1f, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.open_pdf_button)) }
                Button(onClick = onCompress, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.compress_button)) }
            }
        }
    }
}

@Composable
private fun ProtectPanel(sourceUri: Uri?, password: String, onPick: () -> Unit, onPasswordChange: (String) -> Unit, onProtect: () -> Unit, displayName: (Uri) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.protect_pdf_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sourceUri != null) SelectedFileChip(name = displayName(sourceUri))
        OutlinedTextField(value = password, onValueChange = onPasswordChange, label = { Text(stringResource(R.string.protect_password_label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) { Text(if (sourceUri == null) stringResource(R.string.add_pdf) else stringResource(R.string.open_pdf_button)) }
            Button(onClick = onProtect, enabled = sourceUri != null && password.length >= 4, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.protect_button)) }
        }
    }
}

@Composable
private fun AuditPanel(sourceUri: Uri?, signerName: String, onPick: () -> Unit, onNameChange: (String) -> Unit, onAddAudit: () -> Unit, displayName: (Uri) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.audit_trail_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sourceUri != null) SelectedFileChip(name = displayName(sourceUri))
        OutlinedTextField(value = signerName, onValueChange = onNameChange, label = { Text(stringResource(R.string.audit_signer_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) { Text(if (sourceUri == null) stringResource(R.string.add_pdf) else stringResource(R.string.open_pdf_button)) }
            Button(onClick = onAddAudit, enabled = sourceUri != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.audit_button)) }
        }
    }
}

@Composable
private fun OpenFilePanel(onOpenExcel: () -> Unit, onOpenWord: () -> Unit, onOpenPowerPoint: () -> Unit, onOpenImage: () -> Unit, onOpenText: () -> Unit, onOpenAny: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.open_file_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.choose_file_type), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FileTypeButton(Icons.Filled.TableChart, stringResource(R.string.file_type_excel), Color(0xFF1B7A3D), onOpenExcel)
        FileTypeButton(Icons.Filled.Description, stringResource(R.string.file_type_word), Color(0xFF1565C0), onOpenWord)
        FileTypeButton(Icons.Filled.Slideshow, stringResource(R.string.file_type_powerpoint), Color(0xFFD84315), onOpenPowerPoint)
        FileTypeButton(Icons.Filled.Image, stringResource(R.string.file_type_image), Color(0xFF7B1FA2), onOpenImage)
        FileTypeButton(Icons.AutoMirrored.Filled.TextSnippet, stringResource(R.string.file_type_text), Color(0xFF455A64), onOpenText)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        FileTypeButton(Icons.Filled.FolderOpen, stringResource(R.string.file_type_any), MaterialTheme.colorScheme.primary, onOpenAny)
    }
}

@Composable
private fun FileTypeButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(36.dp).background(color.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(18.dp))
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Shared components
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun FileChip(name: String, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SelectedFileChip(name: String) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.PictureAsPdf, null, modifier = Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text = name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SuccessBanner(message: String, onOpenFolder: () -> Unit = {}) {
    Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            TextButton(onClick = onOpenFolder, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.open_folder), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
        Text(text = message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun ExtractPanel(
    sourceUri: Uri?, pageCount: Int, selectedPage: Int,
    onPick: () -> Unit, onPageSelected: (Int) -> Unit, onExtract: () -> Unit,
    displayName: (Uri) -> String
) {
    var showPicker by remember(sourceUri) { mutableStateOf(sourceUri != null) }

    if (showPicker && sourceUri != null && pageCount > 0) {
        PagePickerDialog(
            uri           = sourceUri,
            pageCount     = pageCount,
            password      = null,
            selectedPage  = selectedPage,
            title         = stringResource(R.string.extract_page),
            confirmLabel  = stringResource(R.string.extract_button),
            onPageSelected = onPageSelected,
            onConfirm     = { showPicker = false; onExtract() },
            onDismiss     = { showPicker = false }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.extract_page_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sourceUri == null) {
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_pdf))
            }
        } else {
            SelectedFileChip(name = displayName(sourceUri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.open_pdf_button)) }
                Button(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.extract_button)) }
            }
        }
    }
}

@Composable
private fun ConvertPanel(sourceUri: Uri?, onPick: () -> Unit, onConvert: () -> Unit, displayName: (Uri) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.convert_to_word_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sourceUri != null) SelectedFileChip(name = displayName(sourceUri))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) { Text(if (sourceUri == null) stringResource(R.string.add_pdf) else stringResource(R.string.open_pdf_button)) }
            Button(onClick = onConvert, enabled = sourceUri != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.convert_button)) }
        }
    }
}
