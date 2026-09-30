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
import com.tom_roush.pdfbox.util.Matrix
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

    private companion object {
        /** Tek bir sayfa bitmap'i için piksel bütçesi (~48 MB ARGB_8888). Büyük sayfalarda OOM'u önler. */
        const val MAX_RENDER_PIXELS = 12_000_000L
    }

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

    suspend fun renderPage(pageIndex: Int, densityMultiplier: Float = 3.0f): Bitmap? = withContext(Dispatchers.IO) {
        rendererMutex.withLock {
            val renderer = nativeRenderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            val page = renderer.openPage(pageIndex)
            try {
                // Poster/plan gibi çok büyük sayfalarda bellek taşmasını önlemek için ölçeği sınırla
                var scale = PageGeometry.cappedRenderScale(page.width, page.height, densityMultiplier, MAX_RENDER_PIXELS)
                var attempt = 0
                while (attempt < 2) {
                    try {
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)

                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return@withContext bitmap
                    } catch (_: OutOfMemoryError) {
                        // Bellek yetmedi: yarı çözünürlükle bir kez daha dene
                        scale /= 2f
                        attempt++
                    }
                }
                return@withContext null
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

    /** Görüntülenen (CropBox + /Rotate uygulanmış) sayfa yüksekliği. PDFTextStripper koordinatlarıyla aynı uzay. */
    private fun displayHeightOf(page: PDPage): Float {
        val crop = page.cropBox
        return PageGeometry.displaySize(crop.width, crop.height, page.rotation).second
    }

    /** Görüntülenen (CropBox + /Rotate uygulanmış) sayfa genişliği. */
    private fun displayWidthOf(page: PDPage): Float {
        val crop = page.cropBox
        return PageGeometry.displaySize(crop.width, crop.height, page.rotation).first
    }

    /**
     * İçerik akışını "görüntü uzayına" alır: sol-alt orijin, y yukarı, döndürülmüş ve kırpılmış sayfa.
     * Böylece aşağıdaki çizim kodu, sayfa döndürülmüş veya MediaBox orijini (0,0) değilse de doğru yere yazar.
     * Standart (döndürülmemiş, orijini 0,0 olan) sayfalarda dönüşüm kimliktir ve hiçbir şey eklenmez.
     */
    private fun enterDisplaySpace(contentStream: PDPageContentStream, page: PDPage) {
        val crop = page.cropBox
        val m = PageGeometry.displayToUserMatrix(
            crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, page.rotation
        )
        if (!PageGeometry.isIdentity(m)) {
            contentStream.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        }
    }

    /**
     * Satır sonu, sekme ve diğer kontrol karakterlerini tek boşluğa çevirir.
     * Aksi halde fontlarda glifi olmadığı için metne '?' olarak yazılırdı.
     */
    private fun normalizeControlChars(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasSpace = false
        for (c in text) {
            val isControl = c.code < 32 || c.code == 0x7F || c == '\u2028' || c == '\u2029'
            if (isControl) {
                if (!lastWasSpace) sb.append(' ')
                lastWasSpace = true
            } else {
                sb.append(c)
                lastWasSpace = (c == ' ')
            }
        }
        return sb.toString().trim()
    }

    /**
     * Önce geçici dosyaya yazıp sonra yerine taşır. Kayıt sırasında (disk dolu, süreç ölümü vb.)
     * hata olursa çalışma dosyası bozulmaz ve önceki hali korunur.
     */
    private fun saveAtomically(doc: PDDocument, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            doc.save(tmp)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
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
                        val pageHeight = displayHeightOf(page)

                        val contentStream = PDPageContentStream(
                            doc,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )
                        enterDisplaySpace(contentStream, page)

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
                            val cleanedNewText = normalizeControlChars(operation.newText)
                            var textToWrite = if (isUnicodeFont) cleanedNewText else sanitizeForType1(cleanedNewText)
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

                    saveAtomically(doc, targetFile)
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
                        val pageWidth = displayWidthOf(page)
                        val pageHeight = displayHeightOf(page)

                        val contentStream = PDPageContentStream(
                            doc,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )
                        enterDisplaySpace(contentStream, page)

                        val cleanedText = normalizeControlChars(text)
                        val textToWrite = if (isUnicodeFont) cleanedText else sanitizeForType1(cleanedText)
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

                    saveAtomically(doc, targetFile)
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
                        val pageWidth = displayWidthOf(page)
                        val pageHeight = displayHeightOf(page)

                        val contentStream = PDPageContentStream(
                            doc,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )
                        enterDisplaySpace(contentStream, page)

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
                        val cleanedBlockText = normalizeControlChars(block.text)
                        val textToWrite = if (isUnicodeFont) cleanedBlockText else sanitizeForType1(cleanedBlockText)
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

                    saveAtomically(doc, targetFile)
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

    /**
     * Geri alma için: çalışma dosyasını anlık görüntüyle değiştirir.
     * Render tutamaçları mutex altında kapatılıp yeniden açıldığından, sürmekte olan bir render
     * ile çakışıp çökme riski yoktur. Kopyalama önce geçici dosyaya yapılır; başarısız olursa
     * mevcut çalışma dosyası bozulmaz.
     */
    suspend fun restoreFile(snapshot: File, target: File): Int = withContext(Dispatchers.IO) {
        rendererMutex.withLock {
            closeRendererHandles()
            val tmp = File(target.parentFile, target.name + ".restore")
            try {
                snapshot.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                }
            } catch (e: Exception) {
                try { openRendererHandles(target) } catch (_: Exception) {}
                throw e
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            currentFile = target
            openRendererHandles(target)
            return@withContext nativeRenderer?.pageCount ?: 0
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
                    val referencePage = if (doc.numberOfPages > 0) {
                        doc.getPage(
                            if (insertAfterPageIndex in 0 until doc.numberOfPages) insertAfterPageIndex else 0
                        )
                    } else {
                        null
                    }
                    val pageSize = if (referencePage != null) {
                        val ref = referencePage.mediaBox
                        PDRectangle(ref.width, ref.height)
                    } else {
                        PDRectangle.A4
                    }

                    val newPage = PDPage(pageSize)
                    // Yeni sayfa, yanına eklendiği sayfayla aynı yönde görünsün
                    if (referencePage != null) newPage.rotation = referencePage.rotation
                    val total = doc.numberOfPages

                    if (insertAfterPageIndex in 0 until (total - 1)) {
                        val next = doc.getPage(insertAfterPageIndex + 1)
                        doc.pages.insertBefore(newPage, next)
                    } else {
                        doc.addPage(newPage)
                    }

                    saveAtomically(doc, targetFile)
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
