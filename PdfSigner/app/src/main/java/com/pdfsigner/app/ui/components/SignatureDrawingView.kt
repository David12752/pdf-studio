package com.pdfsigner.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.SavedSignature

private data class DrawingStroke(val points: List<Offset>, val color: Color, val widthPx: Float)

private val INK_COLORS = listOf(
    Color.Black,
    Color(0xFF0F3460),   // navy
    Color(0xFFC62828),   // red
    Color(0xFF2E7D32),   // green
)

@Composable
fun SignatureDrawingView(
    onSignatureSaved: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
    savedSignatures: List<SavedSignature> = emptyList(),
    onSaveRequested: (Bitmap) -> Unit = {},
    onDeleteSaved: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.draw_tab), stringResource(R.string.saved_tab))

    Column(modifier = modifier.fillMaxWidth()) {
        // Tab row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor   = MaterialTheme.colorScheme.surfaceVariant,
            contentColor     = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick  = { selectedTab = index },
                    text     = { Text(title, style = MaterialTheme.typography.labelLarge) }
                )
            }
        }

        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                if (targetState > initialState)
                    slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
                else
                    slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
            },
            label = "tab_transition"
        ) { tab ->
            when (tab) {
                0 -> DrawTab(
                    onSignatureSaved = onSignatureSaved,
                    onSaveRequested  = onSaveRequested,
                    onDismiss        = onDismiss
                )
                1 -> SavedTab(
                    signatures       = savedSignatures,
                    onUse            = { onSignatureSaved(it.bitmap) },
                    onDelete         = onDeleteSaved
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Draw Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DrawTab(
    onSignatureSaved: (Bitmap) -> Unit,
    onSaveRequested: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    val view = LocalView.current

    val strokes = remember { mutableStateListOf<DrawingStroke>() }
    var currentPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var selectedColor by remember { mutableStateOf(INK_COLORS[0]) }
    var penThickness  by remember { mutableFloatStateOf(3f) }   // 1..8 dp

    var canvasW by remember { mutableIntStateOf(0) }
    var canvasH by remember { mutableIntStateOf(0) }

    var showSaveConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Toolbar row (colors + thickness) ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Color swatches
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                INK_COLORS.forEach { color ->
                    val selected = color == selectedColor
                    Box(
                        modifier = Modifier
                            .size(if (selected) 30.dp else 24.dp)
                            .background(color, CircleShape)
                            .then(
                                if (selected) Modifier.border(
                                    2.5.dp, MaterialTheme.colorScheme.primary, CircleShape
                                ) else Modifier
                            )
                            .clickable { selectedColor = color }
                    )
                }
            }

            // Undo + Clear
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick  = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick  = { strokes.clear(); currentPoints = emptyList() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Refresh, null,
                        modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── Pen thickness ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.pen_thickness_label, penThickness.toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp)
            )
            Slider(
                value         = penThickness,
                onValueChange = { penThickness = it },
                valueRange    = 1f..8f,
                steps         = 6,
                modifier      = Modifier.weight(1f)
            )
            // Live preview dot
            Box(
                modifier = Modifier
                    .size(penThickness.dp * 3 + 8.dp)
                    .background(selectedColor, CircleShape)
            )
        }

        // ── Drawing canvas ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(MaterialTheme.shapes.medium)
                .border(
                    2.dp,
                    MaterialTheme.colorScheme.outline,
                    MaterialTheme.shapes.medium
                )
                .background(Color.White)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(penThickness, selectedColor) {
                        canvasW = size.width
                        canvasH = size.height
                        detectDragGestures(
                            onDragStart = { offset ->
                                currentPoints = listOf(offset)
                                view.performHapticFeedback(
                                    HapticFeedbackConstants.CLOCK_TICK)
                            },
                            onDrag = { change, _ ->
                                currentPoints = currentPoints + change.position
                            },
                            onDragEnd = {
                                if (currentPoints.size > 1) {
                                    strokes.add(DrawingStroke(
                                        currentPoints, selectedColor,
                                        penThickness * 4f
                                    ))
                                }
                                currentPoints = emptyList()
                            },
                            onDragCancel = { currentPoints = emptyList() }
                        )
                    }
            ) {
                canvasW = size.width.toInt()
                canvasH = size.height.toInt()

                // Baseline
                drawLine(
                    color = Color.LightGray,
                    start = Offset(24f, size.height - 28f),
                    end   = Offset(size.width - 24f, size.height - 28f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect  = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )

                strokes.forEach { stroke ->
                    drawStrokePath(stroke.points, stroke.color, stroke.widthPx)
                }
                if (currentPoints.size > 1) {
                    drawStrokePath(currentPoints, selectedColor, penThickness * 4f)
                }
            }

            // Placeholder
            if (strokes.isEmpty() && currentPoints.isEmpty()) {
                Text(
                    text     = stringResource(R.string.sign_here),
                    style    = MaterialTheme.typography.bodyLarge,
                    color    = Color.LightGray,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // ── Action buttons ─────────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Save to library
            OutlinedButton(
                onClick  = {
                    if (strokes.isNotEmpty() && canvasW > 0 && canvasH > 0) {
                        val bmp = renderSignatureToBitmap(
                            strokes.map { Triple(it.points, it.color, it.widthPx) },
                            canvasW, canvasH
                        )
                        onSaveRequested(bmp)
                        showSaveConfirm = true
                    }
                },
                enabled  = strokes.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape    = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Filled.Bookmark, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.save_signature),
                    style = MaterialTheme.typography.labelMedium)
            }

            // Use
            Button(
                onClick = {
                    if (strokes.isNotEmpty() && canvasW > 0 && canvasH > 0) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        val bmp = renderSignatureToBitmap(
                            strokes.map { Triple(it.points, it.color, it.widthPx) },
                            canvasW, canvasH
                        )
                        onSignatureSaved(bmp)
                    }
                },
                enabled  = strokes.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape    = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.use),
                    style = MaterialTheme.typography.labelLarge)
            }
        }

        TextButton(
            onClick  = onDismiss,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.cancel))
        }
    }

    // Save confirmation snackbar
    if (showSaveConfirm) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            showSaveConfirm = false
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape  = MaterialTheme.shapes.large,
                color  = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.CheckCircle, null,
                        tint     = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.signature_saved_toast),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Saved Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SavedTab(
    signatures: List<SavedSignature>,
    onUse: (SavedSignature) -> Unit,
    onDelete: (String) -> Unit
) {
    if (signatures.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Edit, null,
                    tint     = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(48.dp))
                Text(
                    stringResource(R.string.saved_signatures_empty),
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns          = GridCells.Fixed(2),
        modifier         = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(8.dp)
    ) {
        items(signatures, key = { it.id }) { sig ->
            SavedSignatureCard(
                signature = sig,
                onUse     = { onUse(sig) },
                onDelete  = { onDelete(sig.id) }
            )
        }
    }
}

@Composable
private fun SavedSignatureCard(
    signature: SavedSignature,
    onUse: () -> Unit,
    onDelete: () -> Unit
) {
    val view = LocalView.current
    Card(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onUse()
        },
        shape  = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                bitmap             = signature.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.use_saved_signature),
                modifier           = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color.White)
                    .padding(8.dp)
            )
            // Delete button overlay
            IconButton(
                onClick  = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .padding(4.dp)
            ) {
                Icon(Icons.Filled.Close, null,
                    modifier = Modifier.size(14.dp),
                    tint     = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Drawing helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawStrokePath(
    points: List<Offset>, color: Color, strokeWidthPx: Float
) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size - 1) {
            val midX = (points[i].x + points[i + 1].x) / 2f
            val midY = (points[i].y + points[i + 1].y) / 2f
            quadraticTo(points[i].x, points[i].y, midX, midY)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(path, color,
        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun renderSignatureToBitmap(
    strokes: List<Triple<List<Offset>, Color, Float>>,
    widthPx: Int, heightPx: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = ACanvas(bitmap)
    val paint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style      = android.graphics.Paint.Style.STROKE
        strokeCap  = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        val points = stroke.first
        val color  = stroke.second
        val width  = stroke.third
        if (points.size < 2) return@forEach
        paint.color       = color.toArgb()
        paint.strokeWidth = width
        val path = android.graphics.Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size - 1) {
                val mx = (points[i].x + points[i + 1].x) / 2f
                val my = (points[i].y + points[i + 1].y) / 2f
                quadTo(points[i].x, points[i].y, mx, my)
            }
            lineTo(points.last().x, points.last().y)
        }
        canvas.drawPath(path, paint)
    }
    return bitmap
}

