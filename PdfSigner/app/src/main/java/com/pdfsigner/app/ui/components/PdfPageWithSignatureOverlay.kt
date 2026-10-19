package com.pdfsigner.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdfsigner.app.R
import com.pdfsigner.app.domain.model.SignaturePlacement
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private const val MIN_SIG_SIZE_PX = 40f
private const val HANDLE_HIT_PX  = 56f   // increased hit area for finger comfort

@OptIn(FlowPreview::class)
@Composable
fun PdfPageWithSignatureOverlay(
    pageBitmap: Bitmap,
    signatureBitmap: Bitmap,
    pageIndex: Int,
    onPlacementChanged: (SignaturePlacement) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewSize    by remember { mutableStateOf(IntSize.Zero) }
    var sigPosition by remember { mutableStateOf(Offset(40f, 40f)) }
    var sigWidth    by remember { mutableStateOf(200f) }

    val aspectRatio  = signatureBitmap.height.toFloat() /
                       signatureBitmap.width.toFloat().coerceAtLeast(1f)
    val sigHeight by remember { derivedStateOf { sigWidth * aspectRatio } }

    val pageImageBitmap = remember(pageBitmap)      { pageBitmap.asImageBitmap() }
    val sigImageBitmap  = remember(signatureBitmap) { signatureBitmap.asImageBitmap() }

    LaunchedEffect(pageBitmap, signatureBitmap, pageIndex) {
        snapshotFlow {
            Triple(viewSize, sigPosition, sigWidth)
        }
            .filter { (currentViewSize, _, _) -> currentViewSize != IntSize.Zero }
            .distinctUntilChanged()
            .debounce(120)
            .collect { (currentViewSize, currentPosition, currentWidth) ->
                val scaleX = pageBitmap.width.toFloat() / currentViewSize.width
                val scaleY = pageBitmap.height.toFloat() / currentViewSize.height
                val currentHeight = currentWidth * aspectRatio
                onPlacementChanged(
                    SignaturePlacement(
                        signatureBitmap = signatureBitmap,
                        pageIndex = pageIndex,
                        positionPx = Offset(currentPosition.x * scaleX, currentPosition.y * scaleY),
                        sizePx = Size(currentWidth * scaleX, currentHeight * scaleY),
                        renderedPageWidthPx = pageBitmap.width,
                        renderedPageHeightPx = pageBitmap.height
                    )
                )
            }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.drag_signature_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = stringResource(R.string.resize_signature_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(pageBitmap.width.toFloat() / pageBitmap.height.toFloat())
                .onGloballyPositioned { viewSize = it.size }
        ) {
            Image(
                bitmap = pageImageBitmap,
                contentDescription = stringResource(R.string.pdf_preview_desc),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Single unified pointer layer � fixes the drag bug where the
            // stacked resize Canvas was consuming all touch events.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(aspectRatio) {
                        // Capture sigWidth / sigPosition via closure references;
                        // they are Compose State so reads here are always current.
                        detectDragGestures(
                            onDragStart = { /* no-op */ },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val h = sigWidth * aspectRatio
                                val handleLeft = sigPosition.x + sigWidth - HANDLE_HIT_PX
                                val handleTop  = sigPosition.y + h - HANDLE_HIT_PX
                                val inHandle   =
                                    change.position.x >= handleLeft &&
                                    change.position.y >= handleTop

                                if (inHandle) {
                                    // Resize: drag changes width, height follows aspect ratio
                                    sigWidth = (sigWidth + dragAmount.x)
                                        .coerceAtLeast(MIN_SIG_SIZE_PX)
                                        .coerceAtMost(viewSize.width.toFloat() - sigPosition.x)
                                } else {
                                    // Move
                                    val newH = sigWidth * aspectRatio
                                    sigPosition = Offset(
                                        x = (sigPosition.x + dragAmount.x)
                                            .coerceIn(0f, viewSize.width  - sigWidth),
                                        y = (sigPosition.y + dragAmount.y)
                                            .coerceIn(0f, viewSize.height - newH)
                                    )
                                }
                            }
                        )
                    }
            ) {
                val h = sigWidth * aspectRatio

                drawImage(
                    image     = sigImageBitmap,
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        sigPosition.x.toInt(), sigPosition.y.toInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        sigWidth.toInt(), h.toInt()
                    )
                )

                // Selection border
                drawRect(
                    color    = Color(0xFF1565C0),
                    topLeft  = sigPosition,
                    size     = Size(sigWidth, h),
                    style    = Stroke(width = 2.dp.toPx())
                )

                // Resize handle � filled square bottom-right corner
                val hs = 20.dp.toPx()
                drawRect(
                    color   = Color(0xFF1565C0),
                    topLeft = Offset(sigPosition.x + sigWidth - hs, sigPosition.y + h - hs),
                    size    = Size(hs, hs)
                )
            }
        }
    }
}
