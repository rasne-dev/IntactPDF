package dev.rasne.intactfolio.core

import dev.rasne.intactfolio.model.WordDocumentData
import dev.rasne.intactfolio.model.WordElement
import dev.rasne.intactfolio.model.WordPage
import dev.rasne.intactfolio.model.WordPageSetup
import dev.rasne.intactfolio.model.WordTableCell
import dev.rasne.intactfolio.model.WordTableRow
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.ceil
import kotlin.math.max

class DocxEngine {

    private companion object {
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        const val DOCUMENT_ENTRY = "word/document.xml"
        const val TWIPS_PER_POINT = 20f
    }

    fun parse(file: File): WordDocumentData {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(DOCUMENT_ENTRY)
                ?: throw IOException("Word belgesi geçersiz: word/document.xml bulunamadı.")
            val doc = zip.getInputStream(entry).use { input ->
                createSecureDocumentBuilder().parse(input)
            }
            val body = findBodyElement(doc)
                ?: throw IOException("Word belgesi geçersiz: <w:body> bulunamadı.")
            return parseBody(body)
        }
    }

    fun updateParagraph(file: File, bodyIndex: Int, newText: String): Result<WordDocumentData> = runCatching {
        rewriteDocumentXml(file) { doc, body ->
            val children = directElementChildren(body)
            val paragraph = children.getOrNull(bodyIndex)
                ?: throw IllegalArgumentException("Paragraf bulunamadı (indeks: $bodyIndex).")
            if (localNameOf(paragraph) != "p") {
                throw IllegalArgumentException("Seçilen öğe bir paragraf değil.")
            }
            replaceParagraphText(doc, paragraph, newText)
        }
        parse(file)
    }

    fun deleteElement(file: File, bodyIndex: Int): Result<WordDocumentData> = runCatching {
        rewriteDocumentXml(file) { _, body ->
            val children = directElementChildren(body)
            val target = children.getOrNull(bodyIndex)
                ?: throw IllegalArgumentException("Silinecek öğe bulunamadı (indeks: $bodyIndex).")
            if (localNameOf(target) == "sectPr") {
                throw IllegalArgumentException("Belge bölüm ayarları silinemez.")
            }
            body.removeChild(target)
        }
        parse(file)
    }

    fun addParagraph(
        file: File,
        text: String,
        insertAfterBodyIndex: Int? = null
    ): Result<WordDocumentData> = runCatching {
        rewriteDocumentXml(file) { doc, body ->
            val prefix = body.prefix
            val newP = createElement(doc, prefix, "p")
            appendTextRuns(doc, newP, prefix, text, clonedRPr = null)

            val children = directElementChildren(body)
            val targetAfter = insertAfterBodyIndex?.let { children.getOrNull(it) }
            val nextSibling = targetAfter?.nextSibling
            if (nextSibling != null) {
                body.insertBefore(newP, nextSibling)
            } else if (targetAfter != null) {
                body.appendChild(newP)
            } else {
                val sectPr = children.lastOrNull { localNameOf(it) == "sectPr" }
                if (sectPr != null) {
                    body.insertBefore(newP, sectPr)
                } else {
                    body.appendChild(newP)
                }
            }
        }
        parse(file)
    }

    fun addPageBreak(file: File): Result<WordDocumentData> = runCatching {
        rewriteDocumentXml(file) { doc, body ->
            val prefix = body.prefix

            val breakP = createElement(doc, prefix, "p")
            val breakRun = createElement(doc, prefix, "r")
            val br = createElement(doc, prefix, "br")
            br.setAttributeNS(W_NS, if (!prefix.isNullOrBlank()) "$prefix:type" else "type", "page")
            breakRun.appendChild(br)
            breakP.appendChild(breakRun)

            val newPageP = createElement(doc, prefix, "p")
            appendTextRuns(doc, newPageP, prefix, "", clonedRPr = null)

            val children = directElementChildren(body)
            val sectPr = children.lastOrNull { localNameOf(it) == "sectPr" }
            if (sectPr != null) {
                body.insertBefore(breakP, sectPr)
                body.insertBefore(newPageP, sectPr)
            } else {
                body.appendChild(breakP)
                body.appendChild(newPageP)
            }
        }
        parse(file)
    }

    fun updateTableCell(
        file: File,
        bodyIndex: Int,
        rowIndex: Int,
        colIndex: Int,
        newText: String
    ): Result<WordDocumentData> = runCatching {
        rewriteDocumentXml(file) { doc, body ->
            val children = directElementChildren(body)
            val table = children.getOrNull(bodyIndex)
                ?: throw IllegalArgumentException("Tablo bulunamadı (indeks: $bodyIndex).")
            if (localNameOf(table) != "tbl") {
                throw IllegalArgumentException("Seçilen öğe bir tablo değil.")
            }
            val rows = directElementChildren(table).filter { localNameOf(it) == "tr" }
            val row = rows.getOrNull(rowIndex)
                ?: throw IllegalArgumentException("Tablo satırı bulunamadı ($rowIndex).")
            val cells = directElementChildren(row).filter { localNameOf(it) == "tc" }
            val cell = cells.getOrNull(colIndex)
                ?: throw IllegalArgumentException("Tablo hücresi bulunamadı ($colIndex).")

            val cellParagraphs = directElementChildren(cell).filter { localNameOf(it) == "p" }
            if (cellParagraphs.isNotEmpty()) {
                val firstP = cellParagraphs.first()
                replaceParagraphText(doc, firstP, newText)
                for (i in 1 until cellParagraphs.size) {
                    cell.removeChild(cellParagraphs[i])
                }
            } else {
                val prefix = cell.prefix ?: body.prefix
                val newP = createElement(doc, prefix, "p")
                appendTextRuns(doc, newP, prefix, newText, clonedRPr = null)
                cell.appendChild(newP)
            }
        }
        parse(file)
    }

    fun createSampleDocx(targetFile: File) {
        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent()

        val rels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent()

        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                        <w:r><w:rPr><w:b/></w:rPr><w:t>IntactFolio — Örnek Word Belgesi</w:t></w:r>
                    </w:p>
                    <w:p>
                        <w:r><w:t>Bu belge tamamen çevrimdışı olarak cihazınızda açılır ve sayfa düzenini koruyarak bilgisayardaki gibi sayfa sayfa görüntülenir.</w:t></w:r>
                    </w:p>
                    <w:p>
                        <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                        <w:r><w:rPr><w:b/></w:rPr><w:t>Örnek Proje Özeti</w:t></w:r>
                    </w:p>
                    <w:tbl>
                        <w:tr>
                            <w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bölüm</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Açıklama</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Durum</w:t></w:r></w:p></w:tc>
                        </w:tr>
                        <w:tr>
                            <w:tc><w:p><w:r><w:t>Gizlilik</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>İnternet izni yok</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>Aktif</w:t></w:r></w:p></w:tc>
                        </w:tr>
                        <w:tr>
                            <w:tc><w:p><w:r><w:t>Biçim</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>Orijinal .docx yapısı</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>Korunur</w:t></w:r></w:p></w:tc>
                        </w:tr>
                    </w:tbl>
                    <w:p>
                        <w:r><w:br w:type="page"/></w:r>
                    </w:p>
                    <w:p>
                        <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                        <w:r><w:rPr><w:b/></w:rPr><w:t>2. Sayfa — Düzenleme İpuçları</w:t></w:r>
                    </w:p>
                    <w:p>
                        <w:r><w:t>Düzenleme moduna geçerek herhangi bir paragrafa veya tablo hücresine dokunabilir, yeni paragraf ya da yeni sayfa ekleyebilirsiniz.</w:t></w:r>
                    </w:p>
                    <w:sectPr>
                        <w:pgSz w:w="11906" w:h="16838"/>
                        <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
                    </w:sectPr>
                </w:body>
            </w:document>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(targetFile)).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", contentTypes.toByteArray(Charsets.UTF_8))
            writeZipEntry(zos, "_rels/.rels", rels.toByteArray(Charsets.UTF_8))
            writeZipEntry(zos, DOCUMENT_ENTRY, documentXml.toByteArray(Charsets.UTF_8))
        }
    }

    private fun parseBody(body: Element): WordDocumentData {
        val elements = mutableListOf<WordElement>()
        val children = directElementChildren(body)
        val pageSetup = parsePageSetup(children)

        children.forEachIndexed { index, el ->
            when (localNameOf(el)) {
                "p" -> {
                    val styleId = extractParagraphStyleId(el)
                    val headingLevel = resolveHeadingLevel(styleId)
                    val isHeading = headingLevel > 0
                    val isBold = isHeading || hasBoldRun(el)
                    val alignment = extractParagraphAlignment(el)
                    val content = extractParagraphContent(el)
                    val hasInlineSectPr = hasParagraphSectPr(el)
                    val pageBreakBefore = hasPageBreakBefore(el)

                    val startsNewPage = pageBreakBefore || content.breakBeforeText
                    val hasPageBreakAfter = content.breakAfterText ||
                        (content.text.isEmpty() && content.sawPageBreak) ||
                        hasInlineSectPr

                    elements.add(
                        WordElement.WordParagraph(
                            id = "p_$index",
                            bodyIndex = index,
                            text = content.text,
                            styleId = styleId,
                            isBold = isBold,
                            isHeading = isHeading,
                            headingLevel = headingLevel,
                            alignment = alignment,
                            startsNewPage = startsNewPage,
                            hasPageBreakAfter = hasPageBreakAfter
                        )
                    )
                }
                "tbl" -> {
                    val rows = parseTableRows(el)
                    if (rows.isNotEmpty()) {
                        elements.add(
                            WordElement.WordTable(
                                id = "tbl_$index",
                                bodyIndex = index,
                                rows = rows
                            )
                        )
                    }
                }
            }
        }
        val pages = paginateElements(elements, pageSetup)
        return WordDocumentData(elements = elements, pageSetup = pageSetup, pages = pages)
    }

    private fun parsePageSetup(children: List<Element>): WordPageSetup {
        val bodySectPr = children.lastOrNull { localNameOf(it) == "sectPr" }
        val sectPr = bodySectPr ?: children.asReversed()
            .filter { localNameOf(it) == "p" }
            .firstNotNullOfOrNull { p ->
                val pPr = directElementChildren(p).firstOrNull { localNameOf(it) == "pPr" }
                pPr?.let { directElementChildren(it).firstOrNull { c -> localNameOf(c) == "sectPr" } }
            } ?: return WordPageSetup()

        val pgSz = directElementChildren(sectPr).firstOrNull { localNameOf(it) == "pgSz" }
        val pgMar = directElementChildren(sectPr).firstOrNull { localNameOf(it) == "pgMar" }

        val wPt = pgSz?.let { attrByLocalName(it, "w")?.toFloatOrNull()?.div(TWIPS_PER_POINT) }
            ?.takeIf { it > 100f } ?: 595.3f
        val hPt = pgSz?.let { attrByLocalName(it, "h")?.toFloatOrNull()?.div(TWIPS_PER_POINT) }
            ?.takeIf { it > 100f } ?: 841.9f

        val topPt = pgMar?.let { attrByLocalName(it, "top")?.toFloatOrNull()?.div(TWIPS_PER_POINT) }
            ?.takeIf { it >= 0f } ?: 72f
        val bottomPt = pgMar?.let { attrByLocalName(it, "bottom")?.toFloatOrNull()?.div(TWIPS_PER_POINT) }
            ?.takeIf { it >= 0f } ?: 72f
        val leftPt = pgMar?.let { attrByLocalName(it, "left")?.toFloatOrNull()?.div(TWIPS_PER_POINT) }
            ?.takeIf { it >= 0f } ?: 72f
        val rightPt = pgMar?.let { attrByLocalName(it, "right")?.toFloatOrNull()?.div(TWIPS_PER_POINT) }
            ?.takeIf { it >= 0f } ?: 72f

        return WordPageSetup(
            widthPt = wPt,
            heightPt = hPt,
            marginTopPt = topPt,
            marginBottomPt = bottomPt,
            marginLeftPt = leftPt,
            marginRightPt = rightPt
        )
    }

    internal fun paginateElements(
        elements: List<WordElement>,
        pageSetup: WordPageSetup
    ): List<WordPage> {
        val printableHeight = max(200f, pageSetup.heightPt - pageSetup.marginTopPt - pageSetup.marginBottomPt)
        val printableWidth = max(180f, pageSetup.widthPt - pageSetup.marginLeftPt - pageSetup.marginRightPt)

        val pages = mutableListOf<WordPage>()
        val currentElements = mutableListOf<WordElement>()
        var currentHeight = 0f

        fun flushPage(allowEmpty: Boolean = false) {
            if (currentElements.isNotEmpty() || allowEmpty) {
                pages.add(WordPage(pageNumber = pages.size + 1, elements = currentElements.toList()))
                currentElements.clear()
                currentHeight = 0f
            }
        }

        for (el in elements) {
            when (el) {
                is WordElement.WordParagraph -> {
                    val isPureBreakOnly = el.text.isEmpty() && (el.startsNewPage || el.hasPageBreakAfter)
                    if (isPureBreakOnly) {
                        if (currentElements.isNotEmpty()) {
                            flushPage()
                        } else {
                            flushPage(allowEmpty = true)
                        }
                        continue
                    }

                    if (el.startsNewPage && currentElements.isNotEmpty()) {
                        flushPage()
                    }

                    val estHeight = estimateParagraphHeightPt(el, printableWidth)
                    if (currentElements.isNotEmpty() && currentHeight + estHeight > printableHeight) {
                        flushPage()
                    }
                    currentElements.add(el)
                    currentHeight += estHeight

                    if (el.hasPageBreakAfter) {
                        flushPage()
                    }
                }
                is WordElement.WordTable -> {
                    val estHeight = estimateTableHeightPt(el, printableWidth)
                    if (currentElements.isNotEmpty() && currentHeight + estHeight > printableHeight) {
                        flushPage()
                    }
                    currentElements.add(el)
                    currentHeight += estHeight
                }
            }
        }

        if (currentElements.isNotEmpty()) {
            flushPage()
        }
        if (pages.isEmpty()) {
            pages.add(WordPage(pageNumber = 1, elements = emptyList()))
        }
        return pages
    }

    private fun estimateParagraphHeightPt(p: WordElement.WordParagraph, printableWidth: Float): Float {
        if (p.text.isBlank()) return 14f
        val fontSize = when {
            p.headingLevel == 1 -> 20f
            p.headingLevel == 2 -> 16f
            p.headingLevel >= 3 -> 14f
            else -> 11.5f
        }
        val avgCharWidth = fontSize * 0.52f
        val charsPerLine = max(12, (printableWidth / avgCharWidth).toInt())
        var totalLines = 0
        for (segment in p.text.split('\n')) {
            totalLines += max(1, ceil(segment.length.toFloat() / charsPerLine).toInt())
        }
        val lineHeight = fontSize * 1.45f
        val spacingAfter = if (p.isHeading) 10f else 8f
        return totalLines * lineHeight + spacingAfter
    }

    private fun estimateTableHeightPt(table: WordElement.WordTable, printableWidth: Float): Float {
        var total = 12f
        for (row in table.rows) {
            val colCount = max(1, row.cells.size)
            val colWidth = max(60f, printableWidth / colCount)
            val charsPerLine = max(8, (colWidth / 6.5f).toInt())
            var maxLines = 1
            for (cell in row.cells) {
                var cellLines = 0
                for (seg in cell.text.split('\n')) {
                    cellLines += max(1, ceil(seg.length.toFloat() / charsPerLine).toInt())
                }
                if (cellLines > maxLines) maxLines = cellLines
            }
            total += max(24f, maxLines * 15f + 10f)
        }
        return total
    }

    private fun parseTableRows(table: Element): List<WordTableRow> {
        val trNodes = directElementChildren(table).filter { localNameOf(it) == "tr" }
        return trNodes.mapIndexed { rowIdx, tr ->
            val tcNodes = directElementChildren(tr).filter { localNameOf(it) == "tc" }
            val cells = tcNodes.mapIndexed { colIdx, tc ->
                val cellText = directElementChildren(tc)
                    .filter { localNameOf(it) == "p" }
                    .joinToString("\n") { extractParagraphContent(it).text }
                WordTableCell(rowIndex = rowIdx, colIndex = colIdx, text = cellText)
            }
            WordTableRow(rowIndex = rowIdx, cells = cells)
        }
    }

    private fun extractParagraphStyleId(p: Element): String? {
        val pPr = directElementChildren(p).firstOrNull { localNameOf(it) == "pPr" } ?: return null
        val pStyle = directElementChildren(pPr).firstOrNull { localNameOf(it) == "pStyle" } ?: return null
        return attrByLocalName(pStyle, "val")
    }

    private fun extractParagraphAlignment(p: Element): String? {
        val pPr = directElementChildren(p).firstOrNull { localNameOf(it) == "pPr" } ?: return null
        val jc = directElementChildren(pPr).firstOrNull { localNameOf(it) == "jc" } ?: return null
        return attrByLocalName(jc, "val")
    }

    private fun hasPageBreakBefore(p: Element): Boolean {
        val pPr = directElementChildren(p).firstOrNull { localNameOf(it) == "pPr" } ?: return false
        val pbb = directElementChildren(pPr).firstOrNull { localNameOf(it) == "pageBreakBefore" } ?: return false
        val value = attrByLocalName(pbb, "val")?.lowercase()
        return value != "0" && value != "false"
    }

    private fun hasParagraphSectPr(p: Element): Boolean {
        val pPr = directElementChildren(p).firstOrNull { localNameOf(it) == "pPr" } ?: return false
        return directElementChildren(pPr).any { localNameOf(it) == "sectPr" }
    }

    private fun resolveHeadingLevel(styleId: String?): Int {
        if (styleId.isNullOrBlank()) return 0
        val normalized = styleId.trim().lowercase()
        if (normalized == "title") return 1
        if (normalized == "subtitle") return 2
        val match = Regex("^(?:heading|baslik|başlık)(\\d+)$").find(normalized)
        if (match != null) {
            return match.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 1
        }
        return 0
    }

    private fun hasBoldRun(p: Element): Boolean {
        val pPr = directElementChildren(p).firstOrNull { localNameOf(it) == "pPr" }
        val pRPr = pPr?.let { directElementChildren(it).firstOrNull { c -> localNameOf(c) == "rPr" } }
        if (pRPr != null && isBoldRPr(pRPr)) return true

        val runs = directElementChildren(p).filter { localNameOf(it) == "r" }
        if (runs.isEmpty()) return false
        return runs.any { run ->
            val rPr = directElementChildren(run).firstOrNull { localNameOf(it) == "rPr" }
            rPr != null && isBoldRPr(rPr)
        }
    }

    private fun isBoldRPr(rPr: Element): Boolean {
        val b = directElementChildren(rPr).firstOrNull { localNameOf(it) == "b" } ?: return false
        val value = attrByLocalName(b, "val")?.lowercase()
        return value != "0" && value != "false"
    }

    private class ParagraphContentScan {
        val sb = StringBuilder()
        var sawPageBreak = false
        var breakBeforeText = false
        var breakAfterText = false
    }

    private data class ParagraphContent(
        val text: String,
        val sawPageBreak: Boolean,
        val breakBeforeText: Boolean,
        val breakAfterText: Boolean
    )

    private fun extractParagraphContent(p: Element): ParagraphContent {
        val scan = ParagraphContentScan()
        collectParagraphContent(p, scan)
        return ParagraphContent(
            text = scan.sb.toString(),
            sawPageBreak = scan.sawPageBreak,
            breakBeforeText = scan.breakBeforeText,
            breakAfterText = scan.breakAfterText
        )
    }

    private fun collectParagraphContent(node: Node, scan: ParagraphContentScan) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            when (localNameOf(child)) {
                "pPr", "rPr", "del", "delText", "instrText" -> continue
                "t" -> scan.sb.append(child.textContent ?: "")
                "tab" -> scan.sb.append('\t')
                "br" -> {
                    val brType = attrByLocalName(child as Element, "type")?.lowercase()
                    if (brType == "page") {
                        scan.sawPageBreak = true
                        if (scan.sb.isEmpty()) {
                            scan.breakBeforeText = true
                        } else {
                            scan.breakAfterText = true
                        }
                    } else {
                        scan.sb.append('\n')
                    }
                }
                "cr" -> scan.sb.append('\n')
                "lastRenderedPageBreak" -> {
                    scan.sawPageBreak = true
                    if (scan.sb.isEmpty()) {
                        scan.breakBeforeText = true
                    } else {
                        scan.breakAfterText = true
                    }
                }
                else -> collectParagraphContent(child, scan)
            }
        }
    }

    private fun replaceParagraphText(doc: Document, paragraph: Element, newText: String) {
        val prefix = paragraph.prefix
        var pPr: Element? = null
        var firstRPr: Element? = null

        val children = directElementChildren(paragraph)
        for (child in children) {
            when (localNameOf(child)) {
                "pPr" -> if (pPr == null) pPr = child
                "r" -> {
                    if (firstRPr == null) {
                        val rPr = directElementChildren(child).firstOrNull { localNameOf(it) == "rPr" }
                        if (rPr != null) {
                            firstRPr = rPr.cloneNode(true) as Element
                        }
                    }
                }
            }
        }

        val toRemove = mutableListOf<Node>()
        val childList = paragraph.childNodes
        for (i in 0 until childList.length) {
            val child = childList.item(i)
            if (child !== pPr) {
                toRemove.add(child)
            }
        }
        toRemove.forEach { paragraph.removeChild(it) }

        appendTextRuns(doc, paragraph, prefix, newText, firstRPr)
    }

    private fun appendTextRuns(
        doc: Document,
        paragraph: Element,
        prefix: String?,
        text: String,
        clonedRPr: Element?
    ) {
        val run = createElement(doc, prefix, "r")
        if (clonedRPr != null) {
            run.appendChild(clonedRPr)
        }
        val lines = text.split("\n")
        lines.forEachIndexed { idx, line ->
            if (idx > 0) {
                run.appendChild(createElement(doc, prefix, "br"))
            }
            val t = createElement(doc, prefix, "t")
            t.setAttributeNS(XML_NS, "xml:space", "preserve")
            t.textContent = line
            run.appendChild(t)
        }
        paragraph.appendChild(run)
    }

    private fun rewriteDocumentXml(file: File, mutator: (Document, Element) -> Unit) {
        val tempFile = File(file.parentFile ?: file.absoluteFile.parentFile, "docx_tmp_${System.nanoTime()}.docx")
        var copiedSuccessfully = false
        try {
            ZipFile(file).use { zip ->
                val docEntry = zip.getEntry(DOCUMENT_ENTRY)
                    ?: throw IOException("word/document.xml bulunamadı.")
                val doc = zip.getInputStream(docEntry).use { input ->
                    createSecureDocumentBuilder().parse(input)
                }
                val body = findBodyElement(doc)
                    ?: throw IOException("<w:body> bulunamadı.")

                mutator(doc, body)
                val updatedBytes = serializeXml(doc)

                ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == DOCUMENT_ENTRY) {
                            writeZipEntry(zos, DOCUMENT_ENTRY, updatedBytes)
                        } else {
                            val newEntry = ZipEntry(entry.name)
                            zos.putNextEntry(newEntry)
                            zip.getInputStream(entry).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            copiedSuccessfully = true
            replaceFile(tempFile, file)
        } finally {
            if (!copiedSuccessfully && tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun findBodyElement(doc: Document): Element? {
        val root = doc.documentElement ?: return null
        return directElementChildren(root).firstOrNull { localNameOf(it) == "body" }
    }

    private fun directElementChildren(parent: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                result.add(node as Element)
            }
        }
        return result
    }

    private fun localNameOf(node: Node): String {
        return node.localName ?: node.nodeName.substringAfter(':')
    }

    private fun attrByLocalName(el: Element, localName: String): String? {
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            if (localNameOf(attr) == localName) {
                return attr.nodeValue
            }
        }
        return null
    }

    private fun createElement(doc: Document, prefix: String?, localName: String): Element {
        val qName = if (!prefix.isNullOrBlank()) "$prefix:$localName" else localName
        return doc.createElementNS(W_NS, qName)
    }

    private fun serializeXml(doc: Document): ByteArray {
        val out = ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.METHOD, "xml")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        transformer.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    private fun writeZipEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(data)
        zos.closeEntry()
    }

    private fun replaceFile(source: File, target: File) {
        if (target.exists() && !target.delete()) {
            source.copyTo(target, overwrite = true)
            source.delete()
            return
        }
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun createSecureDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            isXIncludeAware = false
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
            }
            try {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            } catch (_: Exception) {
            }
            try {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (_: Exception) {
            }
        }
        return factory.newDocumentBuilder()
    }
}
