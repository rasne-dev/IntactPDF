package dev.rasne.intactpdf.core

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import dev.rasne.intactpdf.model.PdfTextBlock
import java.io.StringWriter
import java.util.UUID

class PdfTextLocator(private val targetPageIndex: Int) : PDFTextStripper() {

    private val collectedPositions = mutableListOf<TextPosition>()
    private var pageWidth: Float = 1f
    private var pageHeight: Float = 1f

    init {
        sortByPosition = true
        startPage = targetPageIndex + 1
        endPage = targetPageIndex + 1
    }

    fun locateTextBlocks(document: PDDocument): List<PdfTextBlock> {
        collectedPositions.clear()
        val page = document.getPage(targetPageIndex)
        val mediaBox = page.mediaBox
        pageWidth = mediaBox.width
        pageHeight = mediaBox.height

        // Process text and collect positions
        val dummy = StringWriter()
        writeText(document, dummy)

        return assembleLines(collectedPositions)
    }

    override fun processTextPosition(text: TextPosition) {
        val unicode = text.unicode
        if (unicode != null && unicode.isNotBlank()) {
            collectedPositions.add(text)
        }
    }

    private fun assembleLines(positions: List<TextPosition>): List<PdfTextBlock> {
        if (positions.isEmpty()) return emptyList()

        val blocks = mutableListOf<PdfTextBlock>()
        var currentWord = StringBuilder()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        var currentFontSize = 12f

        var lastPos: TextPosition? = null

        fun flushWord() {
            if (currentWord.isNotEmpty() && minX < maxX && minY < maxY) {
                val pdfRect = RectF(minX, minY, maxX, maxY)
                // Normalize to [0..1] range based on page dimensions
                val normRect = RectF(
                    (minX / pageWidth).coerceIn(0f, 1f),
                    (minY / pageHeight).coerceIn(0f, 1f),
                    (maxX / pageWidth).coerceIn(0f, 1f),
                    (maxY / pageHeight).coerceIn(0f, 1f)
                )

                blocks.add(
                    PdfTextBlock(
                        id = UUID.randomUUID().toString(),
                        text = currentWord.toString(),
                        pageIndex = targetPageIndex,
                        normalizedBounds = normRect,
                        pdfBounds = pdfRect,
                        fontSize = currentFontSize
                    )
                )
                currentWord.clear()
                minX = Float.MAX_VALUE
                minY = Float.MAX_VALUE
                maxX = Float.MIN_VALUE
                maxY = Float.MIN_VALUE
            }
        }

        for (pos in positions) {
            val px = pos.xDirAdj
            val py = pos.yDirAdj
            val pw = pos.widthDirAdj
            val ph = pos.heightDir

            val isSameLine = lastPos == null || Math.abs(pos.yDirAdj - lastPos.yDirAdj) < (pos.heightDir * 0.7f)
            val isContinuous = lastPos == null || (px - (lastPos.xDirAdj + lastPos.widthDirAdj)) < (pos.widthDirAdj * 1.5f)

            if (!isSameLine || !isContinuous) {
                flushWord()
            }

            currentWord.append(pos.unicode)
            currentFontSize = pos.fontSizeInPt
            minX = minOf(minX, px)
            minY = minOf(minY, py - ph)
            maxX = maxOf(maxX, px + pw)
            maxY = maxOf(maxY, py + (ph * 0.2f))

            lastPos = pos
        }

        flushWord()
        return blocks
    }
}
