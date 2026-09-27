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

        val dummy = StringWriter()
        writeText(document, dummy)

        return assembleBlocks(collectedPositions)
    }

    override fun processTextPosition(text: TextPosition) {
        val unicode = text.unicode
        if (unicode != null && unicode.isNotEmpty()) {
            collectedPositions.add(text)
        }
    }

    private fun assembleBlocks(positions: List<TextPosition>): List<PdfTextBlock> {
        if (positions.isEmpty()) return emptyList()

        val blocks = mutableListOf<PdfTextBlock>()
        var currentText = StringBuilder()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        var currentFontSize = 12f
        var currentBaselineY = 0f

        var lastPos: TextPosition? = null

        fun flushBlock() {
            val textStr = currentText.toString().trim()
            if (textStr.isNotEmpty() && minX < maxX && minY < maxY) {
                val pdfRect = RectF(minX, minY, maxX, maxY)
                val normRect = RectF(
                    (minX / pageWidth).coerceIn(0f, 1f),
                    (minY / pageHeight).coerceIn(0f, 1f),
                    (maxX / pageWidth).coerceIn(0f, 1f),
                    (maxY / pageHeight).coerceIn(0f, 1f)
                )

                blocks.add(
                    PdfTextBlock(
                        id = UUID.randomUUID().toString(),
                        text = textStr,
                        pageIndex = targetPageIndex,
                        normalizedBounds = normRect,
                        pdfBounds = pdfRect,
                        fontSize = currentFontSize,
                        baselineY = currentBaselineY
                    )
                )
            }
            currentText.clear()
            minX = Float.MAX_VALUE
            minY = Float.MAX_VALUE
            maxX = Float.MIN_VALUE
            maxY = Float.MIN_VALUE
            lastPos = null
        }

        for (pos in positions) {
            val px = pos.xDirAdj
            val py = pos.yDirAdj
            val pw = pos.widthDirAdj

            if (lastPos != null) {
                val lineDeltaY = Math.abs(pos.yDirAdj - lastPos!!.yDirAdj)
                val isSameLine = lineDeltaY < (Math.max(pos.heightDir, lastPos!!.heightDir) * 0.6f)
                val gap = px - (lastPos!!.xDirAdj + lastPos!!.widthDirAdj)

                if (!isSameLine) {
                    // New line encountered
                    flushBlock()
                } else if (gap > (pos.widthDirAdj * 3.5f)) {
                    // Large horizontal gap (different column or tab stop)
                    flushBlock()
                } else if (gap > (pos.widthDirAdj * 0.25f) && !currentText.endsWith(" ")) {
                    // Normal space between words
                    currentText.append(" ")
                }
            }

            if (pos.unicode == " ") {
                if (currentText.isNotEmpty() && !currentText.endsWith(" ")) {
                    currentText.append(" ")
                }
            } else {
                currentText.append(pos.unicode)
                val fontPt = if (pos.fontSizeInPt > 0f) pos.fontSizeInPt else maxOf(pos.heightDir, 12f)
                currentFontSize = fontPt
                currentBaselineY = py

                // Full typographic coverage:
                // - Ascender and diacritics (İ, Ö, Â, Ä, etc.) reach up to 1.15 * fontPt above baseline (decreasing Y in top-down coordinates)
                // - Descenders (g, y, p, q, j) reach down to 0.35 * fontPt below baseline (increasing Y in top-down coordinates)
                val topOfChar = py - (fontPt * 1.15f)
                val bottomOfChar = py + (fontPt * 0.35f)

                minX = minOf(minX, px)
                minY = minOf(minY, topOfChar)
                maxX = maxOf(maxX, px + pw)
                maxY = maxOf(maxY, bottomOfChar)
            }

            lastPos = pos
        }

        flushBlock()
        return blocks
    }
}
