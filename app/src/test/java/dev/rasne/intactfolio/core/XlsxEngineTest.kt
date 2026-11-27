package dev.rasne.intactfolio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class XlsxEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val engine = XlsxEngine()

    @Test
    fun columnLetterAndRefHelpers_workForSingleAndMultiLetter() {
        assertEquals("A", XlsxEngine.colIndexToLetter(0))
        assertEquals("Z", XlsxEngine.colIndexToLetter(25))
        assertEquals("AA", XlsxEngine.colIndexToLetter(26))
        assertEquals(26, XlsxEngine.letterToColIndex("AA"))
        assertEquals(12 to 27, XlsxEngine.parseCellRef("AB12"))
    }

    @Test
    fun createSampleAndParse_loadsMultipleSheetsAndFormulas() {
        val file = tempFolder.newFile("sample.xlsx")
        engine.createSampleXlsx(file)

        val wb = engine.parse(file)
        assertEquals(2, wb.sheets.size)
        assertEquals("Bütçe", wb.sheets[0].name)
        assertEquals("Notlar", wb.sheets[1].name)

        val b5 = wb.sheets[0].cells[5 to 1]
        assertNotNull(b5)
        assertEquals("SUM(B2:B4)", b5?.formula)
        assertEquals("2650", b5?.value)
        assertTrue(b5?.isNumeric == true)
    }

    @Test
    fun updateCell_handlesSharedStringsReplacementTextNumberFormulaAndClear() {
        val file = tempFolder.newFile("shared.xlsx")
        createXlsxWithSharedStringsAndExtra(file)

        val initial = engine.parse(file)
        assertEquals("Merhaba", initial.sheets[0].cells[1 to 0]?.value)

        // Update with text
        val afterText = engine.updateCell(file, 0, row = 1, col = 0, newValue = "Güncel").getOrThrow()
        assertEquals("Güncel", afterText.sheets[0].cells[1 to 0]?.value)
        assertFalse(afterText.sheets[0].cells[1 to 0]?.isNumeric == true)

        // Update with number
        val afterNum = engine.updateCell(file, 0, row = 2, col = 1, newValue = "42.5").getOrThrow()
        assertEquals("42.5", afterNum.sheets[0].cells[2 to 1]?.value)
        assertTrue(afterNum.sheets[0].cells[2 to 1]?.isNumeric == true)

        // Update with formula
        val afterFormula = engine.updateCell(file, 0, row = 3, col = 1, newValue = "=B2*2").getOrThrow()
        assertEquals("B2*2", afterFormula.sheets[0].cells[3 to 1]?.formula)

        // Clear cell
        val afterClear = engine.updateCell(file, 0, row = 1, col = 0, newValue = "").getOrThrow()
        assertNull(afterClear.sheets[0].cells[1 to 0])

        // Extra ZIP entry preserved
        ZipFile(file).use { zip ->
            assertNotNull(zip.getEntry("xl/styles.xml"))
        }
    }

    @Test
    fun addAndDeleteRowAndColumn_shiftCellReferencesProperly() {
        val file = tempFolder.newFile("shift.xlsx")
        engine.createSampleXlsx(file)

        // Insert row after row 1 -> row 2 ("Market") moves to row 3
        val afterAddRow = engine.addRow(file, sheetIndex = 0, afterRow = 1).getOrThrow()
        assertNull(afterAddRow.sheets[0].cells[2 to 0])
        assertEquals("Market", afterAddRow.sheets[0].cells[3 to 0]?.value)

        // Delete row 2 -> "Market" moves back to row 2
        val afterDeleteRow = engine.deleteRow(file, sheetIndex = 0, row = 2).getOrThrow()
        assertEquals("Market", afterDeleteRow.sheets[0].cells[2 to 0]?.value)

        // Insert column after col 0 (A) -> col 1 (B: "Tutar (TL)") moves to col 2 (C)
        val afterAddCol = engine.addColumn(file, sheetIndex = 0, afterCol = 0).getOrThrow()
        assertNull(afterAddCol.sheets[0].cells[1 to 1])
        assertEquals("Tutar (TL)", afterAddCol.sheets[0].cells[1 to 2]?.value)

        // Delete col 1 -> moves back to col 1
        val afterDeleteCol = engine.deleteColumn(file, sheetIndex = 0, col = 1).getOrThrow()
        assertEquals("Tutar (TL)", afterDeleteCol.sheets[0].cells[1 to 1]?.value)
    }

    private fun createXlsxWithSharedStringsAndExtra(target: File) {
        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
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
                    <sheet name="Sayfa1" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()
        val workbookRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent()
        val sharedStrings = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="1" uniqueCount="1">
                <si><t>Merhaba</t></si>
            </sst>
        """.trimIndent()
        val sheet1 = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <dimension ref="A1:A1"/>
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val styles = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>"""

        ZipOutputStream(FileOutputStream(target)).use { zos ->
            fun put(name: String, text: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rootRels)
            put("xl/workbook.xml", workbookXml)
            put("xl/_rels/workbook.xml.rels", workbookRels)
            put("xl/sharedStrings.xml", sharedStrings)
            put("xl/worksheets/sheet1.xml", sheet1)
            put("xl/styles.xml", styles)
        }
    }
}
