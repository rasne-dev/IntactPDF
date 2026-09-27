package dev.rasne.intactpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                    scale = (scale * zoom).coerceIn(1f, 4f)
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
        contentAlignment = androidx.compose.ui.Alignment.Center
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
                contentDescription = "PDF Page",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            // Overlays for detected text blocks
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val boxWidth = maxWidth
                val boxHeight = maxHeight

                for (block in textBlocks) {
                    val edit = pendingEdits[block.id]
                    val isRemoved = edit?.isRemoved == true
                    val hasEdit = edit != null

                    val left = boxWidth * block.normalizedBounds.left
                    val top = boxHeight * block.normalizedBounds.top
                    val width = boxWidth * block.normalizedBounds.width()
                    val height = boxHeight * block.normalizedBounds.height()

                    Box(
                        modifier = Modifier
                            .offset(x = left, y = top)
                            .size(width = width, height = height)
                            .background(
                                when {
                                    isRemoved -> Color.White
                                    hasEdit -> Color.White
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                width = if (hasEdit) 1.dp else 0.5.dp,
                                color = when {
                                    isRemoved -> Color(0xFFFF5252).copy(alpha = 0.6f)
                                    hasEdit -> Color(0xFF4CAF50)
                                    else -> Color(0xFF1E88E5).copy(alpha = 0.35f)
                                },
                                shape = RoundedCornerShape(2.dp)
                            )
                            .clickable { onBlockClick(block) }
                    ) {
                        if (hasEdit && !isRemoved && edit?.newText?.isNotBlank() == true) {
                            Text(
                                text = edit.newText,
                                color = Color.Black,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
