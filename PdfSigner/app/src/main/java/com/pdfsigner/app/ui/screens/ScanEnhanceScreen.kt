package com.pdfsigner.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint as APaint
import android.graphics.Canvas as ACanvas
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.service.PdfSignatureService
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private enum class ScanMode { COLOR, GRAYSCALE, BW }

/** What the user wants to do with the scanned PDF. */
enum class ScanAction {
    VIEW, SIGN, SHARE, MERGE, COMPRESS, CONVERT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanEnhanceScreen(
    scannedPdfUri: Uri,
    onConfirm: (Uri, ScanAction) -> Unit,
    onBack: () -> Unit
) {
    val context    = LocalContext.current
    val pdfService = remember { PdfSignatureService(context) }
    val scope      = rememberCoroutineScope()

    var rawBitmap        by remember { mutableStateOf<Bitmap?>(null) }
    var previewBitmap    by remember { mutableStateOf<Bitmap?>(null) }
    var loadError        by remember { mutableStateOf<String?>(null) }
    var isSaving         by remember { mutableStateOf(false) }
    var showActionDialog by remember { mutableStateOf(false) }
    var savedUri         by remember { mutableStateOf<Uri?>(null) }

    // Mirror / flip state — user can toggle if scan appears mirrored
    var flipHorizontal by remember { mutableStateOf(false) }
    var flipVertical   by remember { mutableStateOf(false) }

    var scanMode   by remember { mutableStateOf(ScanMode.COLOR) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast   by remember { mutableFloatStateOf(1f) }
    var sharpness  by remember { mutableFloatStateOf(0f) }

    // ?? Load and auto-correct orientation ????????????????????????????????
    LaunchedEffect(scannedPdfUri) {
        runCatching {
            withContext(Dispatchers.IO) {
                // Step 1: copy the URI to a local file so PDDocument can read it
                val tmp = File(context.cacheDir, "scan_load_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(scannedPdfUri)
                    ?.use { it.copyTo(tmp.outputStream()) }

                // Step 2: read the page's declared rotation from the PDF dictionary.
                // ML Kit embeds scans with rotation values (0 / 90 / 180 / 270).
                // PDFRenderer.renderImage() applies this rotation automatically,
                // so the rendered bitmap is already upright — no extra rotation needed.
                // However, ML Kit on some Android versions sets rotation = 0 but draws
                // the image content bottom-up (PDF y-origin = bottom-left), which makes
                // PDFRenderer produce a vertically-flipped result.
                // We detect this: if the page has rotation=0 AND the rendered bitmap
                // is taller than wide (portrait) but the mediaBox is wider than tall
                // (landscape storage), the content is upside-down ? flip 180°.
                var needsFlip180 = false
                runCatching {
                    PDDocument.load(tmp).use { doc ->
                        val page = doc.getPage(0)
                        val rot  = page.rotation          // declared rotation in PDF
                        val mw   = page.mediaBox?.width  ?: 0f
                        val mh   = page.mediaBox?.height ?: 0f
                        // ML Kit quirk: rotation=0 but image stored landscape (mw>mh).
                        // PDFRenderer renders it as landscape ? user sees it sideways.
                        // The correct fix: if rotation=0 and mediaBox is landscape,
                        // the scan was captured portrait but stored landscape ? rotate 90° CW.
                        // More specifically: PDFRenderer already handles declared rotation,
                        // so what comes out is already "correct" in terms of declared rotation.
                        // The remaining issue is ML Kit sometimes writes rotation=0 with
                        // portrait content stored as a landscape page, meaning the image
                        // appears rotated 90° CCW in the viewer ? we need to rotate 90° CW.
                        needsFlip180 = (rot == 0 && mw > mh * 1.2f)
                    }
                }
                tmp.delete()

                // Step 3: render — PDFRenderer handles declared page rotation
                val bmp = pdfService.renderPageToBitmap(scannedPdfUri, pageIndex = 0, dpi = 200f)

                // Step 4: apply correction if needed
                if (needsFlip180) rotateBitmap(bmp, 90f) else bmp
            }
        }.onSuccess { bmp ->
            rawBitmap     = bmp
            previewBitmap = bmp
        }.onFailure { e ->
            loadError = e.message ?: "Failed to load scan"
        }
    }

    // ?? Re-apply enhancements + flip when any parameter changes ??????????
    LaunchedEffect(rawBitmap, scanMode, brightness, contrast, sharpness, flipHorizontal, flipVertical) {
        val src = rawBitmap ?: return@LaunchedEffect
        val enhanced = withContext(Dispatchers.Default) {
            var result = applyEnhancements(src, scanMode, brightness, contrast, sharpness)
            if (flipHorizontal || flipVertical) {
                result = flipBitmap(result, flipHorizontal, flipVertical)
            }
            result
        }
        previewBitmap = enhanced
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_enhance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    // Flip horizontal button — fixes mirror issue
                    IconButton(onClick = { flipHorizontal = !flipHorizontal }) {
                        Icon(
                            Icons.Filled.Flip,
                            contentDescription = "Flip horizontal",
                            tint = if (flipHorizontal) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    // Flip vertical button
                    IconButton(onClick = { flipVertical = !flipVertical }) {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = "Flip vertical",
                            tint = if (flipVertical) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onPrimaryContainer
                        )
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loadError != null ->
                        Text(loadError ?: "", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    isSaving -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.processing),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    previewBitmap != null ->
                        Image(
                            bitmap       = previewBitmap!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.pdf_preview_desc),
                            modifier     = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    else -> CircularProgressIndicator()
                }
            }

            // Flip indicator chips
            if (flipHorizontal || flipVertical) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (flipHorizontal) {
                        SuggestionChip(
                            onClick = { flipHorizontal = false },
                            label   = { Text("? Flipped H  ?", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    if (flipVertical) {
                        SuggestionChip(
                            onClick = { flipVertical = false },
                            label   = { Text("? Flipped V  ?", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Mode selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = scanMode == ScanMode.COLOR,
                    onClick  = { scanMode = ScanMode.COLOR },
                    shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                ) { Text(stringResource(R.string.scan_mode_color)) }
                SegmentedButton(
                    selected = scanMode == ScanMode.GRAYSCALE,
                    onClick  = { scanMode = ScanMode.GRAYSCALE },
                    shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                ) { Text(stringResource(R.string.scan_mode_grayscale)) }
                SegmentedButton(
                    selected = scanMode == ScanMode.BW,
                    onClick  = { scanMode = ScanMode.BW },
                    shape    = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                ) { Text(stringResource(R.string.scan_mode_bw)) }
            }

            LabeledSlider(
                label         = stringResource(R.string.scan_brightness_label),
                value         = brightness,
                onValueChange = { brightness = it },
                valueRange    = -100f..100f
            )
            LabeledSlider(
                label         = stringResource(R.string.scan_contrast_label),
                value         = contrast,
                onValueChange = { contrast = it },
                valueRange    = 0.5f..2.0f
            )
            if (scanMode != ScanMode.BW) {
                LabeledSlider(
                    label         = stringResource(R.string.scan_sharpness_label),
                    value         = sharpness,
                    onValueChange = { sharpness = it },
                    valueRange    = 0f..1f
                )
            }

            Button(
                onClick  = {
                    val enhanced = previewBitmap ?: return@Button
                    isSaving = true
                    scope.launch {
                        val resultUri = withContext(Dispatchers.IO) {
                            saveEnhancedBitmapAsPdf(enhanced, context.cacheDir)
                        }
                        isSaving  = false
                        savedUri  = resultUri
                        showActionDialog = true
                    }
                },
                enabled  = loadError == null && !isSaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = MaterialTheme.shapes.large
            ) {
                Text(stringResource(R.string.apply_and_continue))
            }
        }
    }

    if (showActionDialog) {
        val uri = savedUri ?: return
        ScanActionDialog(
            onSelect  = { action -> showActionDialog = false; onConfirm(uri, action) },
            onDismiss = { showActionDialog = false }
        )
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Post-scan action dialog
// ?????????????????????????????????????????????????????????????????????????????

@Composable
private fun ScanActionDialog(
    onSelect: (ScanAction) -> Unit,
    onDismiss: () -> Unit
) {
    data class ScanOption(val action: ScanAction, val icon: ImageVector, val labelRes: Int)

    val options = listOf(
        ScanOption(ScanAction.VIEW,     Icons.Filled.Visibility,  R.string.open_file),
        ScanOption(ScanAction.SIGN,     Icons.Filled.Draw,        R.string.nav_sign),
        ScanOption(ScanAction.SHARE,    Icons.Filled.Share,       R.string.share),
        ScanOption(ScanAction.MERGE,    Icons.Filled.MergeType,   R.string.merge_pdfs),
        ScanOption(ScanAction.COMPRESS, Icons.Filled.Compress,    R.string.compress_pdf),
        ScanOption(ScanAction.CONVERT,  Icons.Filled.TextSnippet, R.string.convert_to_word)
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.scan_action_title),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.scan_action_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                options.forEach { opt ->
                    Surface(
                        onClick  = { onSelect(opt.action) },
                        shape    = MaterialTheme.shapes.large,
                        color    = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(opt.icon, null,
                                tint     = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp))
                            Text(stringResource(opt.labelRes),
                                style      = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

// ?????????????????????????????????????????????????????????????????????????????
// Helpers
// ?????????????????????????????????????????????????????????????????????????????

private fun flipBitmap(src: Bitmap, horizontal: Boolean, vertical: Boolean): Bitmap {
    val sx = if (horizontal) -1f else 1f
    val sy = if (vertical)   -1f else 1f
    val tx = if (horizontal) src.width.toFloat()  else 0f
    val ty = if (vertical)   src.height.toFloat() else 0f
    val matrix = Matrix().apply { setScale(sx, sy); postTranslate(tx, ty) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

private fun saveEnhancedBitmapAsPdf(bitmap: Bitmap, cacheDir: File): Uri {
    val outFile = File(cacheDir, "enhanced_scan_${System.currentTimeMillis()}.pdf")
    PDDocument().use { doc ->
        // Use 96 dpi reference so the page size matches the rendered bitmap size
        val widthPt  = bitmap.width  * 72f / 200f
        val heightPt = bitmap.height * 72f / 200f
        val page = PDPage(PDRectangle(widthPt, heightPt))
        doc.addPage(page)
        val pdImage = LosslessFactory.createFromImage(doc, bitmap)
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(pdImage, 0f, 0f, widthPt, heightPt)
        }
        FileOutputStream(outFile).use { out -> doc.save(out) }
    }
    return Uri.fromFile(outFile)
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column {
        Text(label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = valueRange,
            modifier      = Modifier.fillMaxWidth()
        )
    }
}

private fun applyEnhancements(
    src: Bitmap, mode: ScanMode,
    brightness: Float, contrast: Float, sharpness: Float
): Bitmap {
    val cm = ColorMatrix()
    val s = contrast
    val t = (-0.5f * s + 0.5f) * 255f
    cm.postConcat(ColorMatrix(floatArrayOf(
        s,0f,0f,0f,t, 0f,s,0f,0f,t, 0f,0f,s,0f,t, 0f,0f,0f,1f,0f)))
    val b = brightness
    cm.postConcat(ColorMatrix(floatArrayOf(
        1f,0f,0f,0f,b, 0f,1f,0f,0f,b, 0f,0f,1f,0f,b, 0f,0f,0f,1f,0f)))
    if (mode == ScanMode.GRAYSCALE || mode == ScanMode.BW) {
        val gm = ColorMatrix(); gm.setSaturation(0f); cm.postConcat(gm)
    }
    val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    ACanvas(result).drawBitmap(src, 0f, 0f,
        APaint(APaint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })

    if (mode == ScanMode.BW) {
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        for (i in pixels.indices) {
            val r  = (pixels[i] shr 16) and 0xFF
            val g  = (pixels[i] shr 8)  and 0xFF
            val bv =  pixels[i]         and 0xFF
            val luma = (0.299 * r + 0.587 * g + 0.114 * bv).toInt()
            pixels[i] = if (luma > 128) android.graphics.Color.WHITE
                        else            android.graphics.Color.BLACK
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }
    return if (sharpness > 0f) sharpenBitmap(result, sharpness) else result
}

private fun sharpenBitmap(src: Bitmap, amount: Float): Bitmap {
    val w = src.width; val h = src.height
    val pixels = IntArray(w * h); src.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = pixels.copyOf()
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        fun ch(p: Int, s: Int) = (p shr s) and 0xFF
        val c  = pixels[y * w + x]
        val n  = pixels[(y - 1) * w + x]
        val s2 = pixels[(y + 1) * w + x]
        val e  = pixels[y * w + x + 1]
        val ww = pixels[y * w + x - 1]
        fun blend(shift: Int): Int {
            val sharp = (5*ch(c,shift) - ch(n,shift) - ch(s2,shift)
                         - ch(e,shift) - ch(ww,shift)).coerceIn(0, 255)
            return (ch(c, shift) + amount * (sharp - ch(c, shift))).toInt().coerceIn(0, 255)
        }
        out[y * w + x] = (0xFF shl 24) or (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(out, 0, w, 0, 0, w, h) }
}
