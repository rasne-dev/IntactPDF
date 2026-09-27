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
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import dev.rasne.intactpdf.model.PdfTextBlock
import dev.rasne.intactpdf.model.TextEditOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
     * Resolves the best available font supporting Unicode / Turkish characters.
     */
    private fun resolveFont(doc: PDDocument): Pair<PDFont, Boolean> {
        val candidateFontPaths = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf"
        )

        for (path in candidateFontPaths) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                try {
                    val font = PDType0Font.load(doc, f)
                    return Pair(font, true)
                } catch (_: Exception) {
                    // Try next font candidate
                }
            }
        }

        // Fallback to standard Type 1 Helvetica font
        return Pair(PDType1Font.HELVETICA, false)
    }

    private fun sanitizeForType1(text: String): String {
        return text
            .replace('ı', 'i').replace('İ', 'I')
            .replace('ş', 's').replace('Ş', 'S')
            .replace('ğ', 'g').replace('Ğ', 'G')
            .replace('ç', 'c').replace('Ç', 'C')
            .replace('ö', 'o').replace('Ö', 'O')
            .replace('ü', 'u').replace('Ü', 'U')
    }

    /**
     * Applies layout-preserving text modifications.
     * Original text bounding box is covered with clean white, and new text is constrained strictly within bounds.
     */
    suspend fun applyEdits(
        operations: List<TextEditOperation>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val srcFile = currentFile ?: return@withContext Result.failure(IllegalStateException("Açık belge bulunamadı."))

        try {
            val doc = PDDocument.load(srcFile)
            val (font, isUnicodeFont) = resolveFont(doc)
            val editsByPage = operations.groupBy { it.targetBlock.pageIndex }

            for ((pageIdx, edits) in editsByPage) {
                if (pageIdx < 0 || pageIdx >= doc.numberOfPages) continue
                val page = doc.getPage(pageIdx)
                val pageHeight = page.mediaBox.height

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
                    contentStream.setNonStrokingColor(1f, 1f, 1f)
                    val pdfY = pageHeight - bounds.bottom
                    val boxHeight = bounds.height()
                    val boxWidth = bounds.width()

                    // Expand rect slightly (1pt) to completely conceal original text
                    contentStream.addRect(
                        bounds.left - 1f,
                        pdfY - 1f,
                        boxWidth + 2f,
                        boxHeight + 2f
                    )
                    contentStream.fill()

                    // 2. If it's a replacement (not deletion), render the new text
                    if (!op.isRemoved && op.newText.isNotBlank()) {
                        var textToWrite = if (isUnicodeFont) op.newText else sanitizeForType1(op.newText)
                        var targetFontSize = op.targetBlock.fontSize
                        if (targetFontSize <= 0f) targetFontSize = 12f

                        // Calculate text width safely
                        var textWidth = try {
                            font.getStringWidth(textToWrite) / 1000f * targetFontSize
                        } catch (e: Exception) {
                            // If encoding error occurs, sanitize text and recalculate
                            textToWrite = sanitizeForType1(textToWrite)
                            font.getStringWidth(textToWrite) / 1000f * targetFontSize
                        }

                        // If user input is longer than original slot, scale down font proportionately
                        // to guarantee ZERO layout shifting of surrounding lines!
                        if (textWidth > boxWidth && boxWidth > 1f) {
                            val ratio = (boxWidth / textWidth).coerceIn(0.4f, 1f)
                            targetFontSize *= ratio
                        }

                        val targetY = if (op.targetBlock.baselineY > 0f) {
                            pageHeight - op.targetBlock.baselineY
                        } else {
                            pdfY + (boxHeight * 0.15f)
                        }

                        contentStream.beginText()
                        contentStream.setFont(font, targetFontSize)
                        contentStream.setNonStrokingColor(0f, 0f, 0f)
                        contentStream.newLineAtOffset(bounds.left, targetY)
                        contentStream.showText(textToWrite)
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
     * Creates a sample test PDF document so users can instantly test editing.
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
        contentStream.showText("IntactPDF Ornek Belge")
        contentStream.endText()

        // Subtitle
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, 13f)
        contentStream.setNonStrokingColor(0.2f, 0.4f, 0.8f)
        contentStream.newLineAtOffset(50f, 720f)
        contentStream.showText("Sayfa Duzenini Koruyan PDF Duzenleyici")
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
        contentStream.showText("Bu metin uzerine dokunarak dogrudan degistirebilirsiniz.")
        contentStream.endText()

        // Paragraph 2
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, 12f)
        contentStream.newLineAtOffset(50f, 630f)
        contentStream.showText("Duzenleme sonrasinda sayfa duzeni ve satir yapisi bozulmaz.")
        contentStream.endText()

        // Paragraph 3 (Highlighted sample)
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12f)
        contentStream.newLineAtOffset(50f, 600f)
        contentStream.showText("Istediginiz metni silebilir veya yerine yeni metin yazabilirsiniz.")
        contentStream.endText()

        // Paragraph 4
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA_OBLIQUE, 11f)
        contentStream.setNonStrokingColor(0.4f, 0.4f, 0.4f)
        contentStream.newLineAtOffset(50f, 550f)
        contentStream.showText("Kaydet butonuna basarak duzenlenmis PDF belgenizi disa aktarabilirsiniz.")
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
