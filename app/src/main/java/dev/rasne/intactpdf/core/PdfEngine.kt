package dev.rasne.intactpdf.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PdfEngine(private val context: Context) {

    private var currentFile: File? = null
    private var pfd: ParcelFileDescriptor? = null
    private var nativeRenderer: PdfRenderer? = null

    // Mutex to prevent concurrent PdfRenderer access (Android requires single-threaded usage)
    private val rendererMutex = Mutex()

    suspend fun openFile(file: File): Int = withContext(Dispatchers.IO) {
        rendererMutex.withLock {
            closeInternal()
            currentFile = file
            openRendererHandles(file)
            return@withContext nativeRenderer?.pageCount ?: 0
        }
    }

    private fun openRendererHandles(file: File) {
        closeRendererHandles()
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        nativeRenderer = PdfRenderer(pfd!!)
    }

    private fun closeRendererHandles() {
        try {
            nativeRenderer?.close()
        } catch (_: Exception) {}
        nativeRenderer = null

        try {
            pfd?.close()
        } catch (_: Exception) {}
        pfd = null
    }

    suspend fun renderPage(pageIndex: Int, densityMultiplier: Float = 2.0f): Bitmap? = withContext(Dispatchers.IO) {
        rendererMutex.withLock {
            val renderer = nativeRenderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            val page = renderer.openPage(pageIndex)
            try {
                val width = (page.width * densityMultiplier).toInt()
                val height = (page.height * densityMultiplier).toInt()

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return@withContext bitmap
            } finally {
                page.close()
            }
        }
    }

    suspend fun extractTextBlocks(pageIndex: Int, file: File? = currentFile): List<PdfTextBlock> = withContext(Dispatchers.IO) {
        val targetFile = file ?: return@withContext emptyList()
        if (!targetFile.exists()) return@withContext emptyList()

        try {
            PDDocument.load(targetFile).use { doc ->
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
                    return@withContext emptyList()
                }
                val locator = PdfTextLocator(pageIndex)
                return@withContext locator.locateTextBlocks(doc)
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    private fun resolveFont(doc: PDDocument): Pair<PDFont, Boolean> {
        val candidateFontPaths = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/RobotoStatic-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/NotoSansTurkish-Regular.ttf",
            "/system/fonts/DroidSans.ttf"
        )

        for (path in candidateFontPaths) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                try {
                    val font = PDType0Font.load(doc, f)
                    return Pair(font, true)
                } catch (_: Exception) {}
            }
        }

        return Pair(PDType1Font.HELVETICA, false)
    }

    private fun sanitizeForType1(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                'ı' -> sb.append('i')
                'İ' -> sb.append('I')
                'ş' -> sb.append('s')
                'Ş' -> sb.append('S')
                'ğ' -> sb.append('g')
                'Ğ' -> sb.append('G')
                'ç' -> sb.append('c')
                'Ç' -> sb.append('C')
                'ö' -> sb.append('o')
                'Ö' -> sb.append('O')
                'ü' -> sb.append('u')
                'Ü' -> sb.append('U')
                else -> {
                    // Only include characters in WinAnsiEncoding range
                    if (c.code in 32..255) {
                        sb.append(c)
                    } else {
                        sb.append('?')
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Applies a modification directly to the working PDF file,
     * re-opens renderer handles, allowing instant live re-rendering.
     */
    suspend fun applyEditLive(
        operation: TextEditOperation,
        targetFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        rendererMutex.withLock {
            try {
                closeRendererHandles()

                PDDocument.load(targetFile).use { doc ->
                    val (font, isUnicodeFont) = resolveFont(doc)
                    val pageIdx = operation.targetBlock.pageIndex

                    if (pageIdx in 0 until doc.numberOfPages) {
                        val page = doc.getPage(pageIdx)
                        val pageHeight = page.mediaBox.height

                        val contentStream = PDPageContentStream(
                            doc,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )

                        val bounds = operation.targetBlock.pdfBounds
                        val pdfY = pageHeight - bounds.bottom
                        val boxHeight = bounds.height()
                        val boxWidth = bounds.width()

                        // Generous padding around the bounding box to ensure zero text traces remain
                        val padX = maxOf(4f, operation.targetBlock.fontSize * 0.12f)
                        val padY = maxOf(3f, operation.targetBlock.fontSize * 0.10f)

                        contentStream.setNonStrokingColor(1f, 1f, 1f)
                        contentStream.addRect(
                            bounds.left - padX,
                            pdfY - padY,
                            boxWidth + (padX * 2f),
                            boxHeight + (padY * 2f)
                        )
                        contentStream.fill()

                        // If not removed, draw the replacement text
                        if (!operation.isRemoved && operation.newText.isNotBlank()) {
                            var textToWrite = if (isUnicodeFont) operation.newText else sanitizeForType1(operation.newText)
                            var targetFontSize = operation.targetBlock.fontSize
                            if (targetFontSize <= 0f) targetFontSize = 12f

                            var textWidth = try {
                                font.getStringWidth(textToWrite) / 1000f * targetFontSize
                            } catch (e: Exception) {
                                textToWrite = sanitizeForType1(textToWrite)
                                try {
                                    font.getStringWidth(textToWrite) / 1000f * targetFontSize
                                } catch (_: Exception) {
                                    textToWrite.length * targetFontSize * 0.5f
                                }
                            }

                            if (textWidth > boxWidth && boxWidth > 1f) {
                                val ratio = (boxWidth / textWidth).coerceIn(0.4f, 1f)
                                targetFontSize *= ratio
                            }

                            val targetY = if (operation.targetBlock.baselineY > 0f) {
                                pageHeight - operation.targetBlock.baselineY
                            } else {
                                pdfY + (boxHeight * 0.15f)
                            }

                            contentStream.beginText()
                            contentStream.setFont(font, targetFontSize)
                            contentStream.setNonStrokingColor(0f, 0f, 0f)
                            contentStream.newLineAtOffset(bounds.left, targetY)
                            try {
                                contentStream.showText(textToWrite)
                            } catch (e: Exception) {
                                // If showText fails, try sanitized version
                                contentStream.showText(sanitizeForType1(textToWrite))
                            }
                            contentStream.endText()
                        }

                        contentStream.close()
                    }

                    doc.save(targetFile)
                }

                currentFile = targetFile
                openRendererHandles(targetFile)
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                try {
                    currentFile?.let { openRendererHandles(it) }
                } catch (_: Exception) {}
                return@withContext Result.failure(e)
            }
        }
    }

    suspend fun addTextLive(
        targetFile: File,
        pageIndex: Int,
        text: String,
        normX: Float,
        normY: Float,
        fontSize: Float
    ): Result<PdfTextBlock> = withContext(Dispatchers.IO) {
        rendererMutex.withLock {
            try {
                closeRendererHandles()

                var newBlock: PdfTextBlock? = null
                PDDocument.load(targetFile).use { doc ->
                    val (font, isUnicodeFont) = resolveFont(doc)
                    if (pageIndex in 0 until doc.numberOfPages) {
                        val page = doc.getPage(pageIndex)
                        val pageWidth = page.mediaBox.width
                        val pageHeight = page.mediaBox.height

                        val contentStream = PDPageContentStream(
                            doc,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )

                        val textToWrite = if (isUnicodeFont) text else sanitizeForType1(text)
                        val targetFontSize = if (fontSize > 0f) fontSize else 14f

                        val textWidth = try {
                            font.getStringWidth(textToWrite) / 1000f * targetFontSize
                        } catch (e: Exception) {
                            val san = sanitizeForType1(textToWrite)
                            try {
                                font.getStringWidth(san) / 1000f * targetFontSize
                            } catch (_: Exception) {
                                san.length * targetFontSize * 0.5f
                            }
                        }

                        val x = normX * pageWidth
                        val y = pageHeight - (normY * pageHeight)

                        contentStream.beginText()
                        contentStream.setFont(font, targetFontSize)
                        contentStream.setNonStrokingColor(0f, 0f, 0f)
                        contentStream.newLineAtOffset(x, y)
                        try {
                            contentStream.showText(textToWrite)
                        } catch (e: Exception) {
                            contentStream.showText(sanitizeForType1(textToWrite))
                        }
                        contentStream.endText()
                        contentStream.close()

                        val topOfChar = normY - (targetFontSize * 1.15f / pageHeight)
                        val bottomOfChar = normY + (targetFontSize * 0.35f / pageHeight)
                        val normWidth = textWidth / pageWidth

                        val normRect = RectF(
                            normX.coerceIn(0f, 1f),
                            topOfChar.coerceIn(0f, 1f),
                            (normX + normWidth).coerceIn(0f, 1f),
                            bottomOfChar.coerceIn(0f, 1f)
                        )
                        val pdfRect = RectF(
                            x,
                            topOfChar * pageHeight,
                            x + textWidth,
                            bottomOfChar * pageHeight
                        )

                        newBlock = PdfTextBlock(
                            id = UUID.randomUUID().toString(),
                            text = textToWrite,
                            pageIndex = pageIndex,
                            normalizedBounds = normRect,
                            pdfBounds = pdfRect,
                            fontSize = targetFontSize,
                            baselineY = normY * pageHeight
                        )
                    }

                    doc.save(targetFile)
                }

                currentFile = targetFile
                openRendererHandles(targetFile)
                return@withContext if (newBlock != null) Result.success(newBlock!!) else Result.failure(IllegalStateException("Sayfa bulunamadı."))
            } catch (e: Exception) {
                try {
                    currentFile?.let { openRendererHandles(it) }
                } catch (_: Exception) {}
                return@withContext Result.failure(e)
            }
        }
    }

    suspend fun moveTextBlockLive(
        targetFile: File,
        block: PdfTextBlock,
        newNormX: Float,
        newNormY: Float
    ): Result<PdfTextBlock> = withContext(Dispatchers.IO) {
        rendererMutex.withLock {
            try {
                closeRendererHandles()

                var updatedBlock: PdfTextBlock? = null
                PDDocument.load(targetFile).use { doc ->
                    val (font, isUnicodeFont) = resolveFont(doc)
                    val pageIdx = block.pageIndex

                    if (pageIdx in 0 until doc.numberOfPages) {
                        val page = doc.getPage(pageIdx)
                        val pageWidth = page.mediaBox.width
                        val pageHeight = page.mediaBox.height

                        val contentStream = PDPageContentStream(
                            doc,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )

                        // 1. Cover original text with whiteout
                        val bounds = block.pdfBounds
                        val pdfY = pageHeight - bounds.bottom
                        val boxHeight = bounds.height()
                        val boxWidth = bounds.width()

                        val padX = maxOf(4f, block.fontSize * 0.12f)
                        val padY = maxOf(3f, block.fontSize * 0.10f)

                        contentStream.setNonStrokingColor(1f, 1f, 1f)
                        contentStream.addRect(
                            bounds.left - padX,
                            pdfY - padY,
                            boxWidth + (padX * 2f),
                            boxHeight + (padY * 2f)
                        )
                        contentStream.fill()

                        // 2. Draw text at new location
                        val textToWrite = if (isUnicodeFont) block.text else sanitizeForType1(block.text)
                        val targetFontSize = if (block.fontSize > 0f) block.fontSize else 12f

                        val textWidth = try {
                            font.getStringWidth(textToWrite) / 1000f * targetFontSize
                        } catch (e: Exception) {
                            val san = sanitizeForType1(textToWrite)
                            try {
                                font.getStringWidth(san) / 1000f * targetFontSize
                            } catch (_: Exception) {
                                san.length * targetFontSize * 0.5f
                            }
                        }

                        val newX = newNormX * pageWidth
                        val newY = pageHeight - (newNormY * pageHeight)

                        contentStream.beginText()
                        contentStream.setFont(font, targetFontSize)
                        contentStream.setNonStrokingColor(0f, 0f, 0f)
                        contentStream.newLineAtOffset(newX, newY)
                        try {
                            contentStream.showText(textToWrite)
                        } catch (e: Exception) {
                            contentStream.showText(sanitizeForType1(textToWrite))
                        }
                        contentStream.endText()
                        contentStream.close()

                        val topOfChar = newNormY - (targetFontSize * 1.15f / pageHeight)
                        val bottomOfChar = newNormY + (targetFontSize * 0.35f / pageHeight)
                        val normWidth = textWidth / pageWidth

                        val normRect = RectF(
                            newNormX.coerceIn(0f, 1f),
                            topOfChar.coerceIn(0f, 1f),
                            (newNormX + normWidth).coerceIn(0f, 1f),
                            bottomOfChar.coerceIn(0f, 1f)
                        )
                        val pdfRect = RectF(
                            newX,
                            topOfChar * pageHeight,
                            newX + textWidth,
                            bottomOfChar * pageHeight
                        )

                        updatedBlock = block.copy(
                            normalizedBounds = normRect,
                            pdfBounds = pdfRect,
                            baselineY = newNormY * pageHeight
                        )
                    }

                    doc.save(targetFile)
                }

                currentFile = targetFile
                openRendererHandles(targetFile)
                return@withContext if (updatedBlock != null) Result.success(updatedBlock!!) else Result.failure(IllegalStateException("Metin taşınamadı."))
            } catch (e: Exception) {
                try {
                    currentFile?.let { openRendererHandles(it) }
                } catch (_: Exception) {}
                return@withContext Result.failure(e)
            }
        }
    }

    suspend fun addNewPage(
        targetFile: File,
        insertAfterPageIndex: Int = -1
    ): Result<Int> = withContext(Dispatchers.IO) {
        rendererMutex.withLock {
            try {
                closeRendererHandles()

                var newCount = 0
                PDDocument.load(targetFile).use { doc ->
                    val pageSize = if (doc.numberOfPages > 0) {
                        val first = doc.getPage(0).mediaBox
                        PDRectangle(first.width, first.height)
                    } else {
                        PDRectangle.A4
                    }

                    val newPage = PDPage(pageSize)
                    val total = doc.numberOfPages

                    if (insertAfterPageIndex in 0 until (total - 1)) {
                        val next = doc.getPage(insertAfterPageIndex + 1)
                        doc.pages.insertBefore(newPage, next)
                    } else {
                        doc.addPage(newPage)
                    }

                    doc.save(targetFile)
                    newCount = doc.numberOfPages
                }

                currentFile = targetFile
                openRendererHandles(targetFile)
                return@withContext Result.success(newCount)
            } catch (e: Exception) {
                try {
                    currentFile?.let { openRendererHandles(it) }
                } catch (_: Exception) {}
                return@withContext Result.failure(e)
            }
        }
    }

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

        // Horizontal separator
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

        // Paragraph 3
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
        closeRendererHandles()
        currentFile = null
    }

    private fun closeInternal() {
        closeRendererHandles()
        currentFile = null
    }
}
