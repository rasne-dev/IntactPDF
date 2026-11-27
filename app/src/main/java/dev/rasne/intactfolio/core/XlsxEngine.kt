package dev.rasne.intactfolio.core

import dev.rasne.intactfolio.model.ExcelCell
import dev.rasne.intactfolio.model.ExcelSheet
import dev.rasne.intactfolio.model.ExcelWorkbookData
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
import kotlin.math.max

class XlsxEngine {

    companion object {
        const val S_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private const val WORKBOOK_ENTRY = "xl/workbook.xml"
        private const val WORKBOOK_RELS_ENTRY = "xl/_rels/workbook.xml.rels"
        private const val SHARED_STRINGS_ENTRY = "xl/sharedStrings.xml"
        private const val CALC_CHAIN_ENTRY = "xl/calcChain.xml"
        private const val MIN_DISPLAY_ROWS = 12
        private const val MIN_DISPLAY_COLS = 6

        fun colIndexToLetter(col: Int): String {
            var n = col
            val sb = StringBuilder()
            while (n >= 0) {
                val rem = n % 26
                sb.append(('A'.code + rem).toChar())
                n = (n / 26) - 1
            }
            return sb.reverse().toString()
        }

        fun letterToColIndex(letters: String): Int {
            var result = 0
            for (ch in letters.uppercase()) {
                if (ch !in 'A'..'Z') return -1
                result = result * 26 + (ch - 'A' + 1)
            }
            return result - 1
        }

        /** Returns Pair(row1Based, col0Based) or null if invalid. */
        fun parseCellRef(ref: String): Pair<Int, Int>? {
            val clean = ref.trim().replace("$", "")
            val match = Regex("^([A-Za-z]+)(\\d+)$").matchEntire(clean) ?: return null
            val col = letterToColIndex(match.groupValues[1])
            val row = match.groupValues[2].toIntOrNull() ?: return null
            if (col < 0 || row < 1) return null
            return row to col
        }

        fun makeCellRef(row: Int, col: Int): String = "${colIndexToLetter(col)}$row"
    }

    fun parse(file: File): ExcelWorkbookData {
        ZipFile(file).use { zip ->
            if (zip.getEntry(WORKBOOK_ENTRY) == null) {
                throw IOException("Excel dosyası geçersiz: xl/workbook.xml bulunamadı.")
            }
            val sharedStrings = parseSharedStrings(zip)
            val relsMap = parseWorkbookRels(zip)
            val sheetMetaList = parseWorkbookSheets(zip, relsMap)

            val sheets = sheetMetaList.mapIndexed { idx, (name, entryPath) ->
                parseSheet(zip, idx, name, entryPath, sharedStrings)
            }
            if (sheets.isEmpty()) {
                throw IOException("Excel dosyasında çalışma sayfası bulunamadı.")
            }
            return ExcelWorkbookData(sheets)
        }
    }

    fun updateCell(
        file: File,
        sheetIndex: Int,
        row: Int,
        col: Int,
        newValue: String
    ): Result<ExcelWorkbookData> = runCatching {
        require(row >= 1 && col >= 0) { "Geçersiz hücre konumu." }
        val workbook = parse(file)
        val sheet = workbook.sheets.getOrNull(sheetIndex)
            ?: throw IllegalArgumentException("Çalışma sayfası bulunamadı ($sheetIndex).")

        val isFormula = newValue.startsWith("=") && newValue.length > 1
        rewriteSheetXml(file, sheet.entryPath, stripCalcChain = isFormula) { doc, sheetData, root ->
            val prefix = sheetData.prefix ?: root.prefix
            val rowEl = findOrCreateRow(doc, sheetData, prefix, row)
            val cellRef = makeCellRef(row, col)
            val cellEl = findOrCreateCell(doc, rowEl, prefix, cellRef, col)

            // Remove old <f>, <v>, <is> children while keeping style attribute "s"
            val toRemove = directElementChildren(cellEl).filter {
                val ln = localNameOf(it)
                ln == "f" || ln == "v" || ln == "is"
            }
            toRemove.forEach { cellEl.removeChild(it) }

            when {
                newValue.isEmpty() -> {
                    cellEl.removeAttribute("t")
                }
                isFormula -> {
                    cellEl.removeAttribute("t")
                    val fEl = createElement(doc, prefix, "f")
                    fEl.textContent = newValue.substring(1)
                    cellEl.appendChild(fEl)
                }
                isPureNumber(newValue) -> {
                    cellEl.removeAttribute("t")
                    val vEl = createElement(doc, prefix, "v")
                    vEl.textContent = newValue
                    cellEl.appendChild(vEl)
                }
                else -> {
                    cellEl.setAttribute("t", "inlineStr")
                    val isEl = createElement(doc, prefix, "is")
                    val tEl = createElement(doc, prefix, "t")
                    tEl.setAttributeNS(XML_NS, "xml:space", "preserve")
                    tEl.textContent = newValue
                    isEl.appendChild(tEl)
                    cellEl.appendChild(isEl)
                }
            }
            updateDimensionIfPresent(root, sheetData)
        }
        parse(file)
    }

    fun addRow(file: File, sheetIndex: Int, afterRow: Int): Result<ExcelWorkbookData> = runCatching {
        require(afterRow >= 0) { "Geçersiz satır indeksi." }
        val workbook = parse(file)
        val sheet = workbook.sheets.getOrNull(sheetIndex)
            ?: throw IllegalArgumentException("Çalışma sayfası bulunamadı.")

        rewriteSheetXml(file, sheet.entryPath, stripCalcChain = true) { doc, sheetData, root ->
            val prefix = sheetData.prefix ?: root.prefix
            val rows = directElementChildren(sheetData).filter { localNameOf(it) == "row" }
            for (rowEl in rows.reversed()) {
                val r = rowEl.getAttribute("r").toIntOrNull() ?: continue
                if (r > afterRow) {
                    val newR = r + 1
                    rowEl.setAttribute("r", newR.toString())
                    shiftRowCellsRow(rowEl, newR)
                }
            }
            findOrCreateRow(doc, sheetData, prefix, afterRow + 1)
            updateDimensionIfPresent(root, sheetData)
        }
        parse(file)
    }

    fun deleteRow(file: File, sheetIndex: Int, row: Int): Result<ExcelWorkbookData> = runCatching {
        require(row >= 1) { "Geçersiz satır indeksi." }
        val workbook = parse(file)
        val sheet = workbook.sheets.getOrNull(sheetIndex)
            ?: throw IllegalArgumentException("Çalışma sayfası bulunamadı.")

        rewriteSheetXml(file, sheet.entryPath, stripCalcChain = true) { _, sheetData, root ->
            val rows = directElementChildren(sheetData).filter { localNameOf(it) == "row" }
            for (rowEl in rows) {
                val r = rowEl.getAttribute("r").toIntOrNull() ?: continue
                when {
                    r == row -> sheetData.removeChild(rowEl)
                    r > row -> {
                        val newR = r - 1
                        rowEl.setAttribute("r", newR.toString())
                        shiftRowCellsRow(rowEl, newR)
                    }
                }
            }
            updateDimensionIfPresent(root, sheetData)
        }
        parse(file)
    }

    fun addColumn(file: File, sheetIndex: Int, afterCol: Int): Result<ExcelWorkbookData> = runCatching {
        require(afterCol >= -1) { "Geçersiz sütun indeksi." }
        val workbook = parse(file)
        val sheet = workbook.sheets.getOrNull(sheetIndex)
            ?: throw IllegalArgumentException("Çalışma sayfası bulunamadı.")

        rewriteSheetXml(file, sheet.entryPath, stripCalcChain = true) { _, sheetData, root ->
            val rows = directElementChildren(sheetData).filter { localNameOf(it) == "row" }
            for (rowEl in rows) {
                val cells = directElementChildren(rowEl).filter { localNameOf(it) == "c" }
                for (cellEl in cells) {
                    val parsed = parseCellRef(cellEl.getAttribute("r")) ?: continue
                    val (r, c) = parsed
                    if (c > afterCol) {
                        cellEl.setAttribute("r", makeCellRef(r, c + 1))
                    }
                }
            }
            updateDimensionIfPresent(root, sheetData)
        }
        parse(file)
    }

    fun deleteColumn(file: File, sheetIndex: Int, col: Int): Result<ExcelWorkbookData> = runCatching {
        require(col >= 0) { "Geçersiz sütun indeksi." }
        val workbook = parse(file)
        val sheet = workbook.sheets.getOrNull(sheetIndex)
            ?: throw IllegalArgumentException("Çalışma sayfası bulunamadı.")

        rewriteSheetXml(file, sheet.entryPath, stripCalcChain = true) { _, sheetData, root ->
            val rows = directElementChildren(sheetData).filter { localNameOf(it) == "row" }
            for (rowEl in rows) {
                val cells = directElementChildren(rowEl).filter { localNameOf(it) == "c" }
                for (cellEl in cells) {
                    val parsed = parseCellRef(cellEl.getAttribute("r")) ?: continue
                    val (r, c) = parsed
                    when {
                        c == col -> rowEl.removeChild(cellEl)
                        c > col -> cellEl.setAttribute("r", makeCellRef(r, c - 1))
                    }
                }
            }
            updateDimensionIfPresent(root, sheetData)
        }
        parse(file)
    }

    fun createSampleXlsx(targetFile: File) {
        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>
        """.trimIndent()

        val rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>
                    <sheet name="Bütçe" sheetId="1" r:id="rId1"/>
                    <sheet name="Notlar" sheetId="2" r:id="rId2"/>
                </sheets>
            </workbook>
        """.trimIndent()

        val workbookRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
            </Relationships>
        """.trimIndent()

        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <dimension ref="A1:C5"/>
                <sheetData>
                    <row r="1">
                        <c r="A1" t="inlineStr"><is><t>Kalem</t></is></c>
                        <c r="B1" t="inlineStr"><is><t>Tutar (TL)</t></is></c>
                        <c r="C1" t="inlineStr"><is><t>Kategori</t></is></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="inlineStr"><is><t>Market</t></is></c>
                        <c r="B2"><v>1250</v></c>
                        <c r="C2" t="inlineStr"><is><t>Gıda</t></is></c>
                    </row>
                    <row r="3">
                        <c r="A3" t="inlineStr"><is><t>Ulaşım</t></is></c>
                        <c r="B3"><v>480</v></c>
                        <c r="C3" t="inlineStr"><is><t>Sabit</t></is></c>
                    </row>
                    <row r="4">
                        <c r="A4" t="inlineStr"><is><t>Faturalar</t></is></c>
                        <c r="B4"><v>920</v></c>
                        <c r="C4" t="inlineStr"><is><t>Zorunlu</t></is></c>
                    </row>
                    <row r="5">
                        <c r="A5" t="inlineStr"><is><t>Toplam</t></is></c>
                        <c r="B5"><f>SUM(B2:B4)</f><v>2650</v></c>
                        <c r="C5" t="inlineStr"><is><t>Aylık</t></is></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val sheet2Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <dimension ref="A1:B2"/>
                <sheetData>
                    <row r="1">
                        <c r="A1" t="inlineStr"><is><t>Bilgi</t></is></c>
                        <c r="B1" t="inlineStr"><is><t>Değer</t></is></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="inlineStr"><is><t>Çevrimdışı Düzenleme</t></is></c>
                        <c r="B2" t="inlineStr"><is><t>Herhangi bir hücreye dokunun</t></is></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(targetFile)).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", contentTypes.toByteArray(Charsets.UTF_8))
            writeZipEntry(zos, "_rels/.rels", rootRels.toByteArray(Charsets.UTF_8))
            writeZipEntry(zos, WORKBOOK_ENTRY, workbookXml.toByteArray(Charsets.UTF_8))
            writeZipEntry(zos, WORKBOOK_RELS_ENTRY, workbookRels.toByteArray(Charsets.UTF_8))
            writeZipEntry(zos, "xl/worksheets/sheet1.xml", sheet1Xml.toByteArray(Charsets.UTF_8))
            writeZipEntry(zos, "xl/worksheets/sheet2.xml", sheet2Xml.toByteArray(Charsets.UTF_8))
        }
    }

    private fun parseSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry(SHARED_STRINGS_ENTRY) ?: return emptyList()
        val doc = zip.getInputStream(entry).use { createSecureDocumentBuilder().parse(it) }
        val root = doc.documentElement ?: return emptyList()
        return directElementChildren(root)
            .filter { localNameOf(it) == "si" }
            .map { si ->
                val sb = StringBuilder()
                collectAllTextElements(si, sb)
                sb.toString()
            }
    }

    private fun collectAllTextElements(node: Node, sb: StringBuilder) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            when (localNameOf(child)) {
                "rPh" -> continue // Ignore phonetic guide text
                "t" -> sb.append(child.textContent ?: "")
                else -> collectAllTextElements(child, sb)
            }
        }
    }

    private fun parseWorkbookRels(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry(WORKBOOK_RELS_ENTRY) ?: return emptyMap()
        val doc = zip.getInputStream(entry).use { createSecureDocumentBuilder().parse(it) }
        val root = doc.documentElement ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        for (rel in directElementChildren(root)) {
            if (localNameOf(rel) != "Relationship") continue
            val id = rel.getAttribute("Id")
            val target = rel.getAttribute("Target")
            if (id.isNotBlank() && target.isNotBlank()) {
                map[id] = normalizeXlPath(target)
            }
        }
        return map
    }

    private fun normalizeXlPath(target: String): String {
        val trimmed = target.trimStart('/')
        return if (trimmed.startsWith("xl/")) trimmed else "xl/$trimmed"
    }

    private fun parseWorkbookSheets(zip: ZipFile, relsMap: Map<String, String>): List<Pair<String, String>> {
        val entry = zip.getEntry(WORKBOOK_ENTRY)
            ?: throw IOException("xl/workbook.xml bulunamadı.")
        val doc = zip.getInputStream(entry).use { createSecureDocumentBuilder().parse(it) }
        val root = doc.documentElement ?: return emptyList()
        val sheetsEl = directElementChildren(root).firstOrNull { localNameOf(it) == "sheets" } ?: return emptyList()
        val sheetEls = directElementChildren(sheetsEl).filter { localNameOf(it) == "sheet" }
        return sheetEls.mapIndexed { idx, sheetEl ->
            val name = sheetEl.getAttribute("name").ifBlank { "Sayfa ${idx + 1}" }
            val rId = attrByLocalName(sheetEl, "id")
            val path = rId?.let { relsMap[it] } ?: "xl/worksheets/sheet${idx + 1}.xml"
            name to path
        }
    }

    private fun parseSheet(
        zip: ZipFile,
        sheetIndex: Int,
        name: String,
        entryPath: String,
        sharedStrings: List<String>
    ): ExcelSheet {
        val entry = zip.getEntry(entryPath)
            ?: throw IOException("Çalışma sayfası XML dosyası bulunamadı: $entryPath")
        val doc = zip.getInputStream(entry).use { createSecureDocumentBuilder().parse(it) }
        val root = doc.documentElement ?: throw IOException("Geçersiz sayfa XML: $entryPath")
        val sheetData = directElementChildren(root).firstOrNull { localNameOf(it) == "sheetData" }

        val cells = mutableMapOf<Pair<Int, Int>, ExcelCell>()
        var actualMaxRow = 0
        var actualMaxCol = -1

        if (sheetData != null) {
            var fallbackRow = 0
            for (rowEl in directElementChildren(sheetData)) {
                if (localNameOf(rowEl) != "row") continue
                val rowNum = rowEl.getAttribute("r").toIntOrNull() ?: (fallbackRow + 1)
                fallbackRow = rowNum
                var fallbackCol = 0

                for (cellEl in directElementChildren(rowEl)) {
                    if (localNameOf(cellEl) != "c") continue
                    val refAttr = cellEl.getAttribute("r")
                    val parsed = if (refAttr.isNotBlank()) parseCellRef(refAttr) else null
                    val r = parsed?.first ?: rowNum
                    val c = parsed?.second ?: fallbackCol
                    fallbackCol = c + 1
                    val resolvedRef = if (refAttr.isNotBlank()) refAttr else makeCellRef(r, c)

                    val type = cellEl.getAttribute("t")
                    val fEl = directElementChildren(cellEl).firstOrNull { localNameOf(it) == "f" }
                    val vEl = directElementChildren(cellEl).firstOrNull { localNameOf(it) == "v" }
                    val isEl = directElementChildren(cellEl).firstOrNull { localNameOf(it) == "is" }

                    val formula = fEl?.textContent?.takeIf { it.isNotEmpty() }
                    val rawV = vEl?.textContent ?: ""

                    var isNumeric = false
                    val value = when (type) {
                        "s" -> {
                            val ssIdx = rawV.toIntOrNull()
                            if (ssIdx != null && ssIdx in sharedStrings.indices) sharedStrings[ssIdx] else rawV
                        }
                        "inlineStr" -> {
                            if (isEl != null) {
                                val sb = StringBuilder()
                                collectAllTextElements(isEl, sb)
                                sb.toString()
                            } else {
                                rawV
                            }
                        }
                        "b" -> if (rawV == "1") "TRUE" else if (rawV == "0") "FALSE" else rawV
                        "str", "e" -> rawV
                        else -> {
                            if (rawV.isNotEmpty()) {
                                isNumeric = rawV.toDoubleOrNull() != null
                                rawV
                            } else if (formula != null) {
                                "=$formula"
                            } else {
                                ""
                            }
                        }
                    }

                    if (value.isNotEmpty() || formula != null) {
                        cells[r to c] = ExcelCell(
                            row = r,
                            col = c,
                            ref = resolvedRef,
                            value = value,
                            formula = formula,
                            isNumeric = isNumeric
                        )
                        actualMaxRow = max(actualMaxRow, r)
                        actualMaxCol = max(actualMaxCol, c)
                    }
                }
            }
        }

        return ExcelSheet(
            index = sheetIndex,
            name = name,
            entryPath = entryPath,
            maxRow = max(actualMaxRow, MIN_DISPLAY_ROWS),
            maxCol = max(actualMaxCol + 1, MIN_DISPLAY_COLS),
            cells = cells
        )
    }

    private fun rewriteSheetXml(
        file: File,
        sheetEntryPath: String,
        stripCalcChain: Boolean,
        mutator: (Document, Element, Element) -> Unit
    ) {
        val tempFile = File(file.parentFile ?: file.absoluteFile.parentFile, "xlsx_tmp_${System.nanoTime()}.xlsx")
        var copiedSuccessfully = false
        try {
            ZipFile(file).use { zip ->
                val sheetEntry = zip.getEntry(sheetEntryPath)
                    ?: throw IOException("Çalışma sayfası bulunamadı: $sheetEntryPath")
                val doc = zip.getInputStream(sheetEntry).use { createSecureDocumentBuilder().parse(it) }
                val root = doc.documentElement
                    ?: throw IOException("Geçersiz worksheet XML.")
                var sheetData = directElementChildren(root).firstOrNull { localNameOf(it) == "sheetData" }
                if (sheetData == null) {
                    sheetData = createElement(doc, root.prefix, "sheetData")
                    root.appendChild(sheetData)
                }

                mutator(doc, sheetData, root)
                val updatedBytes = serializeXml(doc)

                ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (stripCalcChain && entry.name == CALC_CHAIN_ENTRY) {
                            continue
                        }
                        if (entry.name == sheetEntryPath) {
                            writeZipEntry(zos, sheetEntryPath, updatedBytes)
                        } else {
                            zos.putNextEntry(ZipEntry(entry.name))
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

    private fun findOrCreateRow(doc: Document, sheetData: Element, prefix: String?, targetRow: Int): Element {
        val rows = directElementChildren(sheetData).filter { localNameOf(it) == "row" }
        var insertBefore: Element? = null
        for (rowEl in rows) {
            val r = rowEl.getAttribute("r").toIntOrNull() ?: continue
            if (r == targetRow) return rowEl
            if (r > targetRow && insertBefore == null) {
                insertBefore = rowEl
            }
        }
        val newRow = createElement(doc, prefix, "row")
        newRow.setAttribute("r", targetRow.toString())
        if (insertBefore != null) {
            sheetData.insertBefore(newRow, insertBefore)
        } else {
            sheetData.appendChild(newRow)
        }
        return newRow
    }

    private fun findOrCreateCell(
        doc: Document,
        rowEl: Element,
        prefix: String?,
        cellRef: String,
        targetCol: Int
    ): Element {
        val cells = directElementChildren(rowEl).filter { localNameOf(it) == "c" }
        var insertBefore: Element? = null
        for (cellEl in cells) {
            val parsed = parseCellRef(cellEl.getAttribute("r")) ?: continue
            val col = parsed.second
            if (col == targetCol) return cellEl
            if (col > targetCol && insertBefore == null) {
                insertBefore = cellEl
            }
        }
        val newCell = createElement(doc, prefix, "c")
        newCell.setAttribute("r", cellRef)
        if (insertBefore != null) {
            rowEl.insertBefore(newCell, insertBefore)
        } else {
            rowEl.appendChild(newCell)
        }
        return newCell
    }

    private fun shiftRowCellsRow(rowEl: Element, newRowNum: Int) {
        val cells = directElementChildren(rowEl).filter { localNameOf(it) == "c" }
        for (cellEl in cells) {
            val parsed = parseCellRef(cellEl.getAttribute("r")) ?: continue
            cellEl.setAttribute("r", makeCellRef(newRowNum, parsed.second))
        }
    }

    private fun updateDimensionIfPresent(root: Element, sheetData: Element) {
        val dimEl = directElementChildren(root).firstOrNull { localNameOf(it) == "dimension" } ?: return
        var maxRow = 1
        var maxCol = 0
        for (rowEl in directElementChildren(sheetData)) {
            if (localNameOf(rowEl) != "row") continue
            val r = rowEl.getAttribute("r").toIntOrNull()
            if (r != null) maxRow = max(maxRow, r)
            for (cellEl in directElementChildren(rowEl)) {
                if (localNameOf(cellEl) != "c") continue
                val parsed = parseCellRef(cellEl.getAttribute("r")) ?: continue
                maxRow = max(maxRow, parsed.first)
                maxCol = max(maxCol, parsed.second)
            }
        }
        dimEl.setAttribute("ref", "A1:${makeCellRef(maxRow, maxCol)}")
    }

    private fun isPureNumber(text: String): Boolean {
        val num = text.toDoubleOrNull() ?: return false
        if (num.isNaN() || num.isInfinite()) return false
        val stripped = text.removePrefix("-").removePrefix("+")
        // Preserve leading-zero identifiers like "0123" as strings
        if (stripped.length > 1 && stripped.startsWith("0") && !stripped.startsWith("0.")) {
            return false
        }
        return true
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
        return doc.createElementNS(S_NS, qName)
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
