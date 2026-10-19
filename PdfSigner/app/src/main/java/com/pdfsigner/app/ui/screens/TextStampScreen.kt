package com.pdfsigner.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.TextStampPlacement
import com.pdfsigner.app.ui.viewmodel.PdfSignerViewModel

// ?????????????????????????????????????????????????????????????????????????????
// Constants
// ?????????????????????????????????????????????????????????????????????????????

private val TEXT_COLORS = listOf(
    Color.Black,
    Color(0xFF1565C0),
    Color(0xFFC62828),
    Color(0xFF2E7D32),
    Color(0xFF6A1B9A),
    Color(0xFFE65100),
)

private const val HANDLE_PX = 52f      // touch target for resize/delete handles
private const val FONT_DPI  = 150f     // screen DPI used for pt?px conversion

/** A label that has been committed to the canvas but not yet exported. */
private data class DraftLabel(
    val id: Int,
    val text: String,
    val fontSizePt: Float,
    val isBold: Boolean,
    val color: Color,
    var position: Offset,   // top-left of the bounding box in view-px
    var width: Float        // current display width in view-px (height follows font)
)

// ?????????????????????????????????????????????????????????????????????????????
// Screen
// ?????????????????????????????????????????????????????????????????????????????

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextStampScreen(
    viewModel: PdfSignerViewModel,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onHome: (() -> Unit)? = null
) {
    val uiState    by viewModel.uiState.collectAsState()
    val doc        = uiState.pdfDocument  ?: return
    val pageBitmap = uiState.renderedPageBitmap ?: return

    // ?? Formatting state ???????????????????????????????????????????????
    var stampText     by remember { mutableStateOf("") }
    var fontSize      by remember { mutableStateOf(18f) }
    var isBold        by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableStateOf(TEXT_COLORS[0]) }

    // ?? Canvas pan/zoom ????????????????????????????????????????????????
    var canvasScale  by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }

    // ?? Draft labels (placed on this page, not yet committed) ??????????
    var drafts       by remember { mutableStateOf(listOf<DraftLabel>()) }
    var nextId       by remember { mutableIntStateOf(0) }
    var selectedId   by remember { mutableStateOf<Int?>(null) }

    // ?? Input state ????????????????????????????????????????????????????
    var editingId    by remember { mutableStateOf<Int?>(null) }   // which draft we're editing
    var showInput    by remember { mutableStateOf(false) }
    var viewSize     by remember { mutableStateOf(IntSize.Zero) }

    val focusRequester = remember { FocusRequester() }
    val focusManager   = LocalFocusManager.current
    val pageImageBitmap = remember(pageBitmap) { pageBitmap.asImageBitmap() }

    // ?? Helpers ????????????????????????????????????????????????????????

    /** Convert a view-pixel font size to actual drawn pixel size at the current font scale. */
    fun fontPx(pt: Float) = (pt / 72f) * FONT_DPI

    /** Paint for measuring/drawing a draft label. */
    fun makePaint(label: DraftLabel) = android.graphics.Paint().apply {
        textSize    = fontPx(label.fontSizePt)
        typeface    = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            if (label.isBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        isAntiAlias = true
        color       = label.color.toArgb()
    }

    /** Natural width of a label based on its text + font. */
    fun naturalWidth(label: DraftLabel) = makePaint(label).measureText(label.text).coerceAtLeast(40f)

    /** Place a new draft at a tap position in VIEW coordinates. */
    fun placeDraft(viewPos: Offset) {
        if (stampText.isBlank()) return
        val id = nextId++
        val lbl = DraftLabel(
            id        = id,
            text      = stampText,
            fontSizePt = fontSize,
            isBold    = isBold,
            color     = selectedColor,
            position  = Offset(viewPos.x, viewPos.y - fontPx(fontSize)),
            width     = 0f
        )
        val w = naturalWidth(lbl)
        drafts = drafts + lbl.copy(width = w)
        selectedId = id
        stampText  = ""
        showInput  = false
        focusManager.clearFocus()
    }

    /** Commit all drafts into the ViewModel. */
    fun commitAll() {
        if (viewSize == IntSize.Zero) return
        val scaleX = pageBitmap.width.toFloat()  / viewSize.width
        val scaleY = pageBitmap.height.toFloat() / viewSize.height
        drafts.forEach { lbl ->
            val paint    = makePaint(lbl)
            val baseline = lbl.position.y + fontPx(lbl.fontSizePt)
            viewModel.addTextPlacement(
                TextStampPlacement(
                    text                 = lbl.text,
                    pageIndex            = uiState.currentPageIndex,
                    positionPx           = Offset(lbl.position.x * scaleX, baseline * scaleY),
                    fontSizePt           = lbl.fontSizePt,
                    color                = lbl.color.toArgb(),
                    isBold               = lbl.isBold,
                    renderedPageWidthPx  = pageBitmap.width,
                    renderedPageHeightPx = pageBitmap.height
                )
            )
        }
        drafts = emptyList()
        selectedId = null
        onConfirm()
    }

    // ?????????????????????????????????????????????????????????????????????
    // Layout
    // ?????????????????????????????????????????????????????????????????????

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.text_stamp_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (drafts.isNotEmpty()) {
                        // Undo last draft
                        IconButton(onClick = {
                            drafts = drafts.dropLast(1)
                            if (selectedId == drafts.lastOrNull()?.id) selectedId = null
                        }) {
                            Icon(Icons.Filled.Undo, contentDescription = stringResource(R.string.undo))
                        }
                    }
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
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ?? Text input + format bar ??????????????????????????
                    if (showInput) {
                        OutlinedTextField(
                            value         = stampText,
                            onValueChange = { stampText = it },
                            placeholder   = { Text(stringResource(R.string.text_stamp_hint)) },
                            singleLine    = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (stampText.isNotBlank()) showInput = false
                            }),
                            trailingIcon = {
                                if (stampText.isNotBlank()) {
                                    IconButton(onClick = { showInput = false }) {
                                        Icon(Icons.Filled.Check, null,
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            shape    = MaterialTheme.shapes.large
                        )
                        LaunchedEffect(showInput) {
                            if (showInput) runCatching { focusRequester.requestFocus() }
                        }
                    }

                    // ?? Format row ???????????????????????????????????????
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        // Size chips
                        listOf(10f to "XS", 14f to "S", 18f to "M", 24f to "L", 36f to "XL", 48f to "2X").forEach { (sz, lbl) ->
                            val active = fontSize == sz
                            Surface(
                                onClick  = { fontSize = sz },
                                shape    = RoundedCornerShape(50),
                                color    = if (active) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                    Text(lbl,
                                        style      = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color      = if (active) Color.White
                                                     else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(Modifier.width(4.dp))
                        VerticalDivider(modifier = Modifier.height(28.dp))
                        Spacer(Modifier.width(4.dp))

                        // Bold
                        Surface(
                            onClick  = { isBold = !isBold },
                            shape    = RoundedCornerShape(50),
                            color    = if (isBold) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("B", style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isBold) Color.White
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Spacer(Modifier.width(4.dp))
                        VerticalDivider(modifier = Modifier.height(28.dp))
                        Spacer(Modifier.width(4.dp))

                        // Color dots
                        TEXT_COLORS.forEach { c ->
                            val sel = c == selectedColor
                            Box(
                                modifier = Modifier
                                    .size(if (sel) 32.dp else 28.dp)
                                    .background(c, CircleShape)
                                    .then(
                                        if (sel) Modifier
                                            .border(2.dp, Color.White, CircleShape)
                                            .border(3.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        else Modifier
                                    )
                                    .clickable { selectedColor = c }
                            )
                        }
                    }

                    // ?? Action buttons ???????????????????????????????????
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Add text button (opens text input or places if text ready)
                        OutlinedButton(
                            onClick  = { showInput = true },
                            modifier = Modifier.weight(1f),
                            shape    = MaterialTheme.shapes.large
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_text_button),
                                style = MaterialTheme.typography.labelLarge)
                        }

                        // Done ? commit & export
                        Button(
                            onClick  = { commitAll() },
                            modifier = Modifier.weight(1f),
                            shape    = MaterialTheme.shapes.large
                        ) {
                            Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.confirm_placement),
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    // Draft count + hint
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (drafts.isEmpty()) Icons.Filled.TouchApp else Icons.Filled.TextFields,
                            null,
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            if (drafts.isEmpty())
                                stringResource(R.string.text_stamp_tap_hint)
                            else
                                stringResource(R.string.text_stamps_count, drafts.size),
                            style  = MaterialTheme.typography.labelSmall,
                            color  = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        // Zoom reset
                        if (canvasScale != 1f) {
                            TextButton(
                                onClick = { canvasScale = 1f; canvasOffset = Offset.Zero },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Filled.FitScreen, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.viewer_fit),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        // ?? Interactive PDF canvas ??????????????????????????????????????
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onGloballyPositioned { viewSize = it.size }
                // Pinch-to-zoom + pan
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        canvasScale  = (canvasScale * zoom).coerceIn(0.5f, 5f)
                        canvasOffset += pan
                    }
                }
                // Tap to place text at that position
                .pointerInput(stampText, showInput) {
                    detectTapGestures { tapInBox ->
                        // Convert tap from Box coords to canvas coords (accounting for zoom/pan)
                        val canvasX = (tapInBox.x - canvasOffset.x) / canvasScale
                        val canvasY = (tapInBox.y - canvasOffset.y) / canvasScale
                        val tapped  = Offset(canvasX, canvasY)

                        // Check if tap hit an existing draft
                        val hit = drafts.firstOrNull { lbl ->
                            val paint = makePaint(lbl)
                            val h = fontPx(lbl.fontSizePt) * 1.4f
                            tapped.x in lbl.position.x..(lbl.position.x + lbl.width) &&
                            tapped.y in lbl.position.y..(lbl.position.y + h)
                        }
                        if (hit != null) {
                            selectedId = if (selectedId == hit.id) null else hit.id
                        } else {
                            selectedId = null
                            if (stampText.isNotBlank()) {
                                placeDraft(tapped)
                            } else {
                                showInput = true
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // PDF page + labels rendered together with zoom transform
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(pageBitmap.width.toFloat() / pageBitmap.height.toFloat())
                    .graphicsLayer {
                        scaleX       = canvasScale
                        scaleY       = canvasScale
                        translationX = canvasOffset.x
                        translationY = canvasOffset.y
                    }
            ) {
                // Page image
                Image(
                    bitmap             = pageImageBitmap,
                    contentDescription = stringResource(R.string.pdf_preview_desc),
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Fit
                )

                // ?? All draft labels + committed labels from ViewModel ??
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(drafts, selectedId) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val id = selectedId ?: return@detectDragGestures
                                val tapped = (change.position - canvasOffset) / canvasScale

                                val lbl = drafts.firstOrNull { it.id == id } ?: return@detectDragGestures
                                val h    = fontPx(lbl.fontSizePt) * 1.4f

                                // Resize handle = bottom-right corner
                                val resizeX = lbl.position.x + lbl.width
                                val resizeY = lbl.position.y + h
                                val inResize = change.previousPosition.let {
                                    val px = (it.x - canvasOffset.x) / canvasScale
                                    val py = (it.y - canvasOffset.y) / canvasScale
                                    px >= resizeX - HANDLE_PX / canvasScale &&
                                    py >= resizeY - HANDLE_PX / canvasScale
                                }

                                drafts = if (inResize) {
                                    // Resize by dragging corner
                                    drafts.map { d ->
                                        if (d.id == id)
                                            d.copy(width = (d.width + drag.x / canvasScale)
                                                .coerceAtLeast(40f))
                                        else d
                                    }
                                } else {
                                    // Move
                                    drafts.map { d ->
                                        if (d.id == id)
                                            d.copy(position = d.position + drag / canvasScale)
                                        else d
                                    }
                                }
                            }
                        }
                ) {
                    val scaleX = size.width  / pageBitmap.width.toFloat()
                    val scaleY = size.height / pageBitmap.height.toFloat()

                    // Draw already-committed labels from ViewModel (previous pages etc.)
                    uiState.textPlacements
                        .filter { it.pageIndex == uiState.currentPageIndex }
                        .forEach { stamp ->
                            val px    = fontPx(stamp.fontSizePt)
                            val paint = android.graphics.Paint().apply {
                                textSize    = px
                                color       = stamp.color
                                typeface    = android.graphics.Typeface.create(
                                    android.graphics.Typeface.DEFAULT,
                                    if (stamp.isBold) android.graphics.Typeface.BOLD
                                    else android.graphics.Typeface.NORMAL
                                )
                                isAntiAlias = true
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                stamp.text,
                                stamp.positionPx.x * scaleX,
                                stamp.positionPx.y * scaleY,
                                paint
                            )
                        }

                    // Draw draft labels
                    drafts.forEach { lbl ->
                        val paint   = makePaint(lbl)
                        val fontH   = fontPx(lbl.fontSizePt)
                        val lineH   = fontH * 1.4f
                        val isSelec = lbl.id == selectedId

                        // Selection / bounding box
                        if (isSelec) {
                            drawRect(
                                color   = lbl.color.copy(alpha = 0.10f),
                                topLeft = lbl.position,
                                size    = Size(lbl.width, lineH)
                            )
                            drawRect(
                                color   = lbl.color.copy(alpha = 0.80f),
                                topLeft = lbl.position,
                                size    = Size(lbl.width, lineH),
                                style   = Stroke(width = 1.5.dp.toPx())
                            )

                            // Resize handle — bottom-right
                            val hs = 14.dp.toPx()
                            drawRect(
                                color   = primaryColor,
                                topLeft = Offset(lbl.position.x + lbl.width - hs,
                                                 lbl.position.y + lineH - hs),
                                size    = Size(hs, hs)
                            )

                            // Delete handle — top-right circle
                            drawCircle(
                                color  = Color(0xFFCC0000),
                                radius = 10.dp.toPx(),
                                center = Offset(lbl.position.x + lbl.width,
                                                lbl.position.y)
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                "?",
                                lbl.position.x + lbl.width - 8.dp.toPx(),
                                lbl.position.y + 8.dp.toPx(),
                                android.graphics.Paint().apply {
                                    textSize    = 16.dp.toPx()
                                    color       = android.graphics.Color.WHITE
                                    isAntiAlias = true
                                }
                            )
                        } else {
                            // Subtle underline when not selected
                            drawRect(
                                color   = lbl.color.copy(alpha = 0.20f),
                                topLeft = Offset(lbl.position.x, lbl.position.y + lineH - 2.dp.toPx()),
                                size    = Size(lbl.width, 2.dp.toPx())
                            )
                        }

                        // Draw text baseline
                        drawContext.canvas.nativeCanvas.drawText(
                            lbl.text,
                            lbl.position.x,
                            lbl.position.y + fontH,
                            paint
                        )
                    }
                }

                // ?? Delete hit area (invisible, on top) ????????????????
                // Intercept taps on the red ? circle of the selected draft
                if (selectedId != null) {
                    val sel = drafts.firstOrNull { it.id == selectedId }
                    if (sel != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(selectedId) {
                                    detectTapGestures { tap ->
                                        val cx = (tap.x - canvasOffset.x) / canvasScale
                                        val cy = (tap.y - canvasOffset.y) / canvasScale
                                        val lineH  = fontPx(sel.fontSizePt) * 1.4f
                                        val delCx  = sel.position.x + sel.width
                                        val delCy  = sel.position.y
                                        val inDel  = (cx - delCx) * (cx - delCx) +
                                                     (cy - delCy) * (cy - delCy) <
                                                     (HANDLE_PX / canvasScale / 2f).let { it * it }
                                        if (inDel) {
                                            drafts     = drafts.filter { it.id != selectedId }
                                            selectedId = null
                                        }
                                    }
                                }
                        )
                    }
                }
            }

            // Zoom level badge
            if (canvasScale != 1f) {
                Surface(
                    shape  = RoundedCornerShape(8.dp),
                    color  = Color.Black.copy(0.55f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        "${(canvasScale * 100).toInt()}%",
                        color    = Color.White,
                        style    = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
