package dev.rasne.intactpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rasne.intactpdf.model.PdfTextBlock
import dev.rasne.intactpdf.model.TextEditOperation

@Composable
fun PdfPageView(
    bitmap: Bitmap,
    textBlocks: List<PdfTextBlock>,
    pendingEdits: Map<String, TextEditOperation>,
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
            // Native rendered PDF page image
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF Sayfası",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            // Interactive Text Layer
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

                            // Find tapped block with touch tolerance
                            val toleranceX = 12f / widthPx
                            val toleranceY = 12f / heightPx

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
                    val edit = pendingEdits[block.id]
                    val isRemoved = edit?.isRemoved == true
                    val hasEdit = edit != null

                    val left = boxWidth * block.normalizedBounds.left
                    val top = boxHeight * block.normalizedBounds.top
                    val width = (boxWidth * block.normalizedBounds.width()).coerceAtLeast(12.dp)
                    val height = (boxHeight * block.normalizedBounds.height()).coerceAtLeast(12.dp)

                    Box(
                        modifier = Modifier
                            .offset(x = left, y = top)
                            .size(width = width, height = height)
                            .background(
                                when {
                                    isRemoved -> Color.White.copy(alpha = 0.9f)
                                    hasEdit -> Color.White.copy(alpha = 0.95f)
                                    else -> Color(0xFF2196F3).copy(alpha = 0.06f)
                                }
                            )
                            .border(
                                width = if (hasEdit) 1.5.dp else 0.8.dp,
                                color = when {
                                    isRemoved -> Color(0xFFE53935)
                                    hasEdit -> Color(0xFF2E7D32)
                                    else -> Color(0xFF1976D2).copy(alpha = 0.35f)
                                },
                                shape = RoundedCornerShape(2.dp)
                            )
                    ) {
                        if (hasEdit && !isRemoved && edit?.newText?.isNotBlank() == true) {
                            Text(
                                text = edit.newText,
                                color = Color.Black,
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        } else if (isRemoved) {
                            Text(
                                text = "(Silindi)",
                                color = Color(0xFFE53935),
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
