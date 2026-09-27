package dev.rasne.intactpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.rasne.intactpdf.model.PdfTextBlock
import dev.rasne.intactpdf.model.ViewerMode

@Composable
fun PdfPageView(
    bitmap: Bitmap,
    textBlocks: List<PdfTextBlock>,
    viewerMode: ViewerMode,
    onBlockClick: (PdfTextBlock) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
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
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

        Box(
            modifier = Modifier
                .aspectRatio(aspectRatio)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
        ) {
            // Live-rendered native PDF page
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF Sayfası",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            // Interactive Text Layer: ONLY active and visible in EDIT mode!
            if (viewerMode == ViewerMode.EDIT) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(textBlocks) {
                            detectTapGestures { tapOffset ->
                                val widthPx = size.width.toFloat()
                                val heightPx = size.height.toFloat()
                                if (widthPx <= 0f || heightPx <= 0f) return@detectTapGestures

                                val normX = tapOffset.x / widthPx
                                val normY = tapOffset.y / heightPx

                                val toleranceX = 14f / widthPx
                                val toleranceY = 14f / heightPx

                                val clickedBlock = textBlocks.firstOrNull { block ->
                                    val bounds = block.normalizedBounds
                                    normX >= (bounds.left - toleranceX) &&
                                    normX <= (bounds.right + toleranceX) &&
                                    normY >= (bounds.top - toleranceY) &&
                                    normY <= (bounds.bottom + toleranceY)
                                }

                                clickedBlock?.let { onBlockClick(it) }
                            }
                        }
                ) {
                    val boxWidth = maxWidth
                    val boxHeight = maxHeight

                    for (block in textBlocks) {
                        val left = boxWidth * block.normalizedBounds.left
                        val top = boxHeight * block.normalizedBounds.top
                        val width = (boxWidth * block.normalizedBounds.width()).coerceAtLeast(14.dp)
                        val height = (boxHeight * block.normalizedBounds.height()).coerceAtLeast(14.dp)

                        Box(
                            modifier = Modifier
                                .offset(x = left, y = top)
                                .size(width = width, height = height)
                                .background(Color(0xFF1976D2).copy(alpha = 0.08f))
                                .border(
                                    width = 1.dp,
                                    color = Color(0xFF1976D2).copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}
