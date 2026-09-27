package dev.rasne.intactpdf.model

import android.graphics.RectF

data class PdfTextBlock(
    val id: String,
    val text: String,
    val pageIndex: Int,
    /**
     * Normalized coordinates (0.0f .. 1.0f) relative to the PDF page width and height
     */
    val normalizedBounds: RectF,
    /**
     * Exact points in PDF space
     */
    val pdfBounds: RectF,
    val fontSize: Float,
    val baselineY: Float = 0f
)

data class TextEditOperation(
    val targetBlock: PdfTextBlock,
    val newText: String, // Empty string means "Remove / Redact"
    val isRemoved: Boolean = false
)

sealed class PdfLoadState {
    data object Idle : PdfLoadState()
    data object Loading : PdfLoadState()
    data class Success(val pageCount: Int, val title: String) : PdfLoadState()
    data class Error(val message: String) : PdfLoadState()
}

enum class ViewerMode {
    VIEW,
    EDIT
}

data class AlignmentGuide(
    val isVertical: Boolean,
    val position: Float,
    val label: String? = null
)

