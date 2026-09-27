package dev.rasne.intactpdf.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import dev.rasne.intactpdf.model.PdfTextBlock
import dev.rasne.intactpdf.model.TextEditOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfEngine(private val context: Context) {

    private var currentFile: File? = null
    private var pfd: ParcelFileDescriptor? = null
    private var nativeRenderer: PdfRenderer? = null
    private var pdDocument: PDDocument? = null

    suspend fun openFile(file: File): Int = withContext(Dispatchers.IO) {
        close()
        currentFile = file
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        nativeRenderer = PdfRenderer(pfd!!)
        pdDocument = PDDocument.load(file)
        return@withContext nativeRenderer?.pageCount ?: 0
    }

    suspend fun renderPage(pageIndex: Int, densityMultiplier: Float = 2.0f): Bitmap? = withContext(Dispatchers.IO) {
        val renderer = nativeRenderer ?: return@withContext null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

        val page = renderer.openPage(pageIndex)
        val width = (page.width * densityMultiplier).toInt()
        val height = (page.height * densityMultiplier).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return@withContext bitmap
    }

    suspend fun extractTextBlocks(pageIndex: Int): List<PdfTextBlock> = withContext(Dispatchers.IO) {
        val doc = pdDocument ?: return@withContext emptyList()
        if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext emptyList()

        val locator = PdfTextLocator(pageIndex)
        return@withContext locator.locateTextBlocks(doc)
    }

    /**
     * Applies layout-preserving text modifications.
     * Original text bounding box is covered, and new text is constrained strictly within bounds.
     */
    suspend fun applyEdits(
        operations: List<TextEditOperation>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val srcFile = currentFile ?: return@withContext Result.failure(IllegalStateException("No document loaded"))

        try {
            val doc = PDDocument.load(srcFile)
            val editsByPage = operations.groupBy { it.targetBlock.pageIndex }

            for ((pageIdx, edits) in editsByPage) {
                if (pageIdx < 0 || pageIdx >= doc.numberOfPages) continue
                val page = doc.getPage(pageIdx)
                val pageHeight = page.mediaBox.height

                // Append mode to overlay on top of existing elements
                val contentStream = PDPageContentStream(
                    doc,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                )

                for (op in edits) {
                    val bounds = op.targetBlock.pdfBounds

                    // 1. Redact / White-out the exact bounding box so layout remains completely intact
                    contentStream.setNonStrokingColor(1f, 1f, 1f) // Clean white
                    // In PDF coordinate space, Y=0 is bottom-left
                    val pdfY = pageHeight - bounds.bottom
                    val boxHeight = bounds.height()
                    val boxWidth = bounds.width()

                    // Expand rect slightly (0.5pt) to avoid any edge bleeding of original text
                    contentStream.addRect(
                        bounds.left - 0.5f,
                        pdfY - 0.5f,
                        boxWidth + 1f,
                        boxHeight + 1f
                    )
                    contentStream.fill()

                    // 2. If it's a replacement (not just deletion/removal), write the new text
                    if (!op.isRemoved && op.newText.isNotBlank()) {
                        val font = PDType1Font.HELVETICA
                        var targetFontSize = op.targetBlock.fontSize
                        if (targetFontSize <= 0f) targetFontSize = 12f

                        // Calculate text width with standard font
                        var textWidth = font.getStringWidth(op.newText) / 1000f * targetFontSize

                        // If user input is longer than original slot, scale down font proportionately
                        // to guarantee ZERO layout shifting of surrounding lines!
                        if (textWidth > boxWidth && boxWidth > 1f) {
                            val ratio = boxWidth / textWidth
                            targetFontSize *= ratio
                        }

                        contentStream.beginText()
                        contentStream.setFont(font, targetFontSize)
                        contentStream.setNonStrokingColor(0f, 0f, 0f) // Black text
                        // Position at baseline
                        contentStream.newLineAtOffset(bounds.left, pdfY + (boxHeight * 0.15f))
                        contentStream.showText(op.newText)
                        contentStream.endText()
                    }
                }

                contentStream.close()
            }

            doc.save(outputFile)
            doc.close()

            return@withContext Result.success(outputFile)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /**
     * Creates a sample test PDF document so users can instantly test editing
     * without needing to find or transfer a PDF to their device.
     */
    suspend fun createSamplePdf(targetFile: File): File = withContext(Dispatchers.IO) {
        val document = PDDocument()
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)

        val contentStream = PDPageContentStream(document, page)

        // Title
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 22f)
        contentStream.newLineAtOffset(50f, 750f)
        contentStream.showText("IntactPDF Sample Document")
        contentStream.endText()

        // Subtitle
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, 13f)
        contentStream.setNonStrokingColor(0.2f, 0.4f, 0.8f)
        contentStream.newLineAtOffset(50f, 720f)
        contentStream.showText("100% Offline, Ad-Free & Layout-Preserving Editor")
        contentStream.endText()

        // Horizontal separator line
        contentStream.setStrokingColor(0.8f, 0.8f, 0.8f)
        contentStream.setLineWidth(1f)
        contentStream.moveTo(50f, 700f)
        contentStream.lineTo(545f, 700f)
        contentStream.stroke()

        // Paragraph 1
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, 12f)
        contentStream.setNonStrokingColor(0f, 0f, 0f)
        contentStream.newLineAtOffset(50f, 660f)
        contentStream.showText("This is a test paragraph designed to test in-place editing.")
        contentStream.endText()

        // Paragraph 2
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, 12f)
        contentStream.newLineAtOffset(50f, 630f)
        contentStream.showText("You can tap any word or line to edit or remove it cleanly.")
        contentStream.endText()

        // Paragraph 3 (Highlighted sample)
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12f)
        contentStream.newLineAtOffset(50f, 600f)
        contentStream.showText("Notice how surrounding sentences stay perfectly aligned.")
        contentStream.endText()

        // Paragraph 4
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA_OBLIQUE, 11f)
        contentStream.setNonStrokingColor(0.4f, 0.4f, 0.4f)
        contentStream.newLineAtOffset(50f, 550f)
        contentStream.showText("IntactPDF does not have internet permission. Your data is 100% private.")
        contentStream.endText()

        contentStream.close()
        document.save(targetFile)
        document.close()

        return@withContext targetFile
    }

    fun close() {
        try {
            pdDocument?.close()
        } catch (_: Exception) {}
        pdDocument = null

        try {
            nativeRenderer?.close()
        } catch (_: Exception) {}
        nativeRenderer = null

        try {
            pfd?.close()
        } catch (_: Exception) {}
        pfd = null
    }
}
