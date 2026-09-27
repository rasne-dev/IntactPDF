package dev.rasne.intactpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rasne.intactpdf.model.AlignmentGuide
import dev.rasne.intactpdf.model.PdfTextBlock
import dev.rasne.intactpdf.model.ViewerMode

@Composable
fun PdfPageView(
    bitmap: Bitmap,
    textBlocks: List<PdfTextBlock>,
    viewerMode: ViewerMode,
    onBlockClick: (PdfTextBlock) -> Unit,
    onBlockMoved: ((block: PdfTextBlock, newNormX: Float, newNormY: Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Reset zoom/offset when bitmap changes (page change or re-render)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Reset zoom when bitmap identity changes (page navigation)
    LaunchedEffect(bitmap) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    var activeDraggedBlock by remember { mutableStateOf<PdfTextBlock?>(null) }
    var draggedBlockNormPos by remember { mutableStateOf<Offset?>(null) }
    var activeGuides by remember { mutableStateOf<List<AlignmentGuide>>(emptyList()) }

    val viewConfiguration = LocalViewConfiguration.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // Only pan/zoom when not actively dragging a block
                    if (activeDraggedBlock == null) {
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                )
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

        Box(
            modifier = Modifier
                .aspectRatio(aspectRatio)
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
        ) {
            // Live-rendered native PDF page
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF Sayfası",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            // Interactive Text Layer: ONLY active and visible in EDIT mode
            if (viewerMode == ViewerMode.EDIT) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(textBlocks) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val widthPx = size.width.toFloat()
                                val heightPx = size.height.toFloat()
                                if (widthPx <= 0f || heightPx <= 0f) return@awaitEachGesture

                                val normX = down.position.x / widthPx
                                val normY = down.position.y / heightPx

                                val toleranceX = 14f / widthPx
                                val toleranceY = 14f / heightPx

                                val touchedBlock = textBlocks.firstOrNull { block ->
                                    val bounds = block.normalizedBounds
                                    normX >= (bounds.left - toleranceX) &&
                                    normX <= (bounds.right + toleranceX) &&
                                    normY >= (bounds.top - toleranceY) &&
                                    normY <= (bounds.bottom + toleranceY)
                                }

                                if (touchedBlock == null) {
                                    return@awaitEachGesture
                                }

                                var isDragging = false
                                var currentNormX = touchedBlock.normalizedBounds.left
                                var currentNormY = touchedBlock.normalizedBounds.top
                                val blockWidthNorm = touchedBlock.normalizedBounds.width()
                                val blockHeightNorm = touchedBlock.normalizedBounds.height()

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                    if (!change.pressed) {
                                        // Pointer up!
                                        if (!isDragging) {
                                            onBlockClick(touchedBlock)
                                        } else {
                                            onBlockMoved?.invoke(touchedBlock, currentNormX, currentNormY)
                                            activeDraggedBlock = null
                                            draggedBlockNormPos = null
                                            activeGuides = emptyList()
                                        }
                                        change.consume()
                                        break
                                    }

                                    val distance = (change.position - down.position).getDistance()
                                    if (!isDragging && distance > viewConfiguration.touchSlop) {
                                        isDragging = true
                                        activeDraggedBlock = touchedBlock
                                    }

                                    if (isDragging) {
                                        change.consume()
                                        val deltaNormX = (change.position.x - down.position.x) / widthPx
                                        val deltaNormY = (change.position.y - down.position.y) / heightPx

                                        var candidateNormX = (touchedBlock.normalizedBounds.left + deltaNormX).coerceIn(0f, 1f - blockWidthNorm)
                                        var candidateNormY = (touchedBlock.normalizedBounds.top + deltaNormY).coerceIn(0f, 1f - blockHeightNorm)

                                        val guides = mutableListOf<AlignmentGuide>()
                                        val snapThresholdX = 16f / widthPx
                                        val snapThresholdY = 16f / heightPx

                                        // 1. Vertical Center alignment (Page center X = 0.5f)
                                        val blockCenterX = candidateNormX + (blockWidthNorm / 2f)
                                        if (Math.abs(blockCenterX - 0.5f) < snapThresholdX) {
                                            candidateNormX = 0.5f - (blockWidthNorm / 2f)
                                            guides.add(AlignmentGuide(isVertical = true, position = 0.5f, label = "Sayfa Ortası"))
                                        } else {
                                            // Check alignment with other blocks
                                            for (other in textBlocks) {
                                                if (other.id == touchedBlock.id) continue
                                                if (Math.abs(candidateNormX - other.normalizedBounds.left) < snapThresholdX) {
                                                    candidateNormX = other.normalizedBounds.left
                                                    guides.add(AlignmentGuide(isVertical = true, position = other.normalizedBounds.left, label = "Sola Hizalı"))
                                                    break
                                                }
                                                val otherCenterX = other.normalizedBounds.left + (other.normalizedBounds.width() / 2f)
                                                if (Math.abs(blockCenterX - otherCenterX) < snapThresholdX) {
                                                    candidateNormX = otherCenterX - (blockWidthNorm / 2f)
                                                    guides.add(AlignmentGuide(isVertical = true, position = otherCenterX, label = "Metinle Ortalı"))
                                                    break
                                                }
                                            }
                                        }

                                        // 2. Horizontal Center alignment (Page center Y = 0.5f)
                                        val blockCenterY = candidateNormY + (blockHeightNorm / 2f)
                                        if (Math.abs(blockCenterY - 0.5f) < snapThresholdY) {
                                            candidateNormY = 0.5f - (blockHeightNorm / 2f)
                                            guides.add(AlignmentGuide(isVertical = false, position = 0.5f, label = "Yatay Orta"))
                                        }

                                        currentNormX = candidateNormX
                                        currentNormY = candidateNormY
                                        draggedBlockNormPos = Offset(candidateNormX, candidateNormY)
                                        activeGuides = guides
                                    }
                                }
                            }
                        }
                ) {
                    val boxWidth = maxWidth
                    val boxHeight = maxHeight

                    // 1. Draw text blocks
                    for (block in textBlocks) {
                        val isDragged = activeDraggedBlock?.id == block.id
                        val normX = if (isDragged && draggedBlockNormPos != null) draggedBlockNormPos!!.x else block.normalizedBounds.left
                        val normY = if (isDragged && draggedBlockNormPos != null) draggedBlockNormPos!!.y else block.normalizedBounds.top

                        val left = boxWidth * normX
                        val top = boxHeight * normY
                        val width = (boxWidth * block.normalizedBounds.width()).coerceAtLeast(14.dp)
                        val height = (boxHeight * block.normalizedBounds.height()).coerceAtLeast(14.dp)

                        Box(
                            modifier = Modifier
                                .offset(x = left, y = top)
                                .size(width = width, height = height)
                                .background(
                                    if (isDragged) Color(0xFF1976D2).copy(alpha = 0.25f)
                                    else Color(0xFF1976D2).copy(alpha = 0.08f)
                                )
                                .border(
                                    width = if (isDragged) 2.dp else 1.dp,
                                    color = if (isDragged) Color(0xFF1976D2) else Color(0xFF1976D2).copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }

                    // 2. Draw real-time dynamic alignment guidelines
                    for (guide in activeGuides) {
                        if (guide.isVertical) {
                            val lineX = boxWidth * guide.position
                            Box(
                                modifier = Modifier
                                    .offset(x = lineX - 1.dp, y = 0.dp)
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(Color(0xFFE91E63))
                            )
                            guide.label?.let { text ->
                                Surface(
                                    color = Color(0xFFE91E63),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier
                                        .offset(x = (lineX + 4.dp).coerceAtMost(boxWidth - 90.dp), y = 8.dp)
                                ) {
                                    Text(
                                        text = text,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        } else {
                            val lineY = boxHeight * guide.position
                            Box(
                                modifier = Modifier
                                    .offset(x = 0.dp, y = lineY - 1.dp)
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(Color(0xFFE91E63))
                            )
                            guide.label?.let { text ->
                                Surface(
                                    color = Color(0xFFE91E63),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier
                                        .offset(x = 8.dp, y = (lineY + 4.dp).coerceAtMost(boxHeight - 24.dp))
                                ) {
                                    Text(
                                        text = text,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
