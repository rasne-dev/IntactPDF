package dev.rasne.intactfolio.core

import dev.rasne.intactfolio.model.WordElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DocxEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val engine = DocxEngine()

    @Test
    fun createSampleAndParse_extractsHeadingsParagraphsAndTable() {
        val file = tempFolder.newFile("sample.docx")
        engine.createSampleDocx(file)

        val doc = engine.parse(file)
        val paragraphs = doc.elements.filterIsInstance<WordElement.WordParagraph>()
        val tables = doc.elements.filterIsInstance<WordElement.WordTable>()

        assertTrue(paragraphs.isNotEmpty())
        assertTrue(paragraphs.first().isHeading)
        assertEquals(1, paragraphs.first().headingLevel)
        assertTrue(paragraphs.first().text.contains("IntactFolio"))
        assertEquals(1, tables.size)
        assertEquals(3, tables.first().rows.size)
        assertEquals("Gizlilik", tables.first().rows[1].cells[0].text)
    }

    @Test
    fun updateParagraph_preservesStyleAndBoldAndExtraZipEntries() {
        val file = tempFolder.newFile("styled.docx")
        createDocxWithExtraEntry(file)

        val initial = engine.parse(file)
        val heading = initial.elements.filterIsInstance<WordElement.WordParagraph>().first()
        assertTrue(heading.isHeading)
        assertTrue(heading.isBold)

        val updated = engine.updateParagraph(file, heading.bodyIndex, "Yeni Başlık\nAlt Satır").getOrThrow()
        val updatedHeading = updated.elements.filterIsInstance<WordElement.WordParagraph>().first()
        assertTrue(updatedHeading.isHeading)
        assertEquals("Heading1", updatedHeading.styleId)
        assertTrue(updatedHeading.isBold)
        assertEquals("Yeni Başlık\nAlt Satır", updatedHeading.text)

        // Verify extra ZIP entry is preserved byte-for-byte
        ZipFile(file).use { zip ->
            val extra = zip.getEntry("word/styles.xml")
            assertNotNull(extra)
            val content = zip.getInputStream(extra).bufferedReader().readText()
            assertTrue(content.contains("CustomMarker"))
        }
    }

    @Test
    fun addAndDeleteParagraph_updatesBodyElements() {
        val file = tempFolder.newFile("add_delete.docx")
        engine.createSampleDocx(file)

        val initialCount = engine.parse(file).elements.size
        val afterAdd = engine.addParagraph(file, "Eklenen paragraf").getOrThrow()
        assertEquals(initialCount + 1, afterAdd.elements.size)

        val addedPara = afterAdd.elements.filterIsInstance<WordElement.WordParagraph>().last()
        assertEquals("Eklenen paragraf", addedPara.text)

        val afterDelete = engine.deleteElement(file, addedPara.bodyIndex).getOrThrow()
        assertEquals(initialCount, afterDelete.elements.size)
    }

    @Test
    fun updateTableCell_updatesTargetCellOnly() {
        val file = tempFolder.newFile("table.docx")
        engine.createSampleDocx(file)

        val initial = engine.parse(file)
        val table = initial.elements.filterIsInstance<WordElement.WordTable>().first()

        val updated = engine.updateTableCell(file, table.bodyIndex, rowIndex = 1, colIndex = 2, newText = "Doğrulandı").getOrThrow()
        val updatedTable = updated.elements.filterIsInstance<WordElement.WordTable>().first()

        assertEquals("Doğrulandı", updatedTable.rows[1].cells[2].text)
        assertEquals("Gizlilik", updatedTable.rows[1].cells[0].text)
    }

    @Test
    fun sampleDocx_parsesA4PageSetupAndExplicitTwoPages() {
        val file = tempFolder.newFile("sample_pages.docx")
        engine.createSampleDocx(file)

        val doc = engine.parse(file)
        assertEquals(595.3f, doc.pageSetup.widthPt, 0.2f)
        assertEquals(841.9f, doc.pageSetup.heightPt, 0.2f)
        assertEquals(72f, doc.pageSetup.marginTopPt, 0.1f)
        assertEquals(2, doc.pages.size)
        assertEquals(1, doc.pages[0].pageNumber)
        assertEquals(2, doc.pages[1].pageNumber)
        assertTrue(doc.pages[1].elements.any {
            it is WordElement.WordParagraph && it.text.contains("2. Sayfa")
        })
    }

    @Test
    fun pagination_splitsOnPageBreakBeforeAndLongContentOverflow() {
        val file = tempFolder.newFile("paginated.docx")
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

        val manyParagraphs = buildString {
            append("""<w:p><w:r><w:t>Birinci sayfa paragrafı.</w:t></w:r></w:p>""")
            append("""<w:p><w:pPr><w:pageBreakBefore/><w:jc w:val="center"/></w:pPr><w:r><w:t>İkinci sayfa başlangıcı.</w:t></w:r></w:p>""")
            repeat(55) { idx ->
                append("""<w:p><w:r><w:t>Uzun metin satırı numara $idx burada yer alır ve sayfa taşması testini tetiklemek için yeterince uzun yazılmıştır.</w:t></w:r></w:p>""")
            }
        }
        val docXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    $manyParagraphs
                    <w:sectPr>
                        <w:pgSz w:w="12240" w:h="15840"/>
                        <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
                    </w:sectPr>
                </w:body>
            </w:document>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(file)).use { zos ->
            fun put(name: String, text: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("word/document.xml", docXml)
        }

        val doc = engine.parse(file)
        assertEquals(612f, doc.pageSetup.widthPt, 0.1f)
        assertEquals(792f, doc.pageSetup.heightPt, 0.1f)
        // Page 1 has only the first paragraph due to pageBreakBefore on paragraph 2;
        // remaining 56 paragraphs overflow across multiple additional pages.
        assertEquals(1, doc.pages[0].elements.size)
        val secondPageFirst = doc.pages[1].elements.first() as WordElement.WordParagraph
        assertEquals("center", secondPageFirst.alignment)
        assertTrue(doc.pages.size >= 3)
    }

    @Test
    fun addPageBreak_appendsNewPageAndPersistsAcrossReparse() {
        val file = tempFolder.newFile("add_page.docx")
        engine.createSampleDocx(file)

        val initialPages = engine.parse(file).pages.size
        val afterBreak = engine.addPageBreak(file).getOrThrow()
        assertEquals(initialPages + 1, afterBreak.pages.size)

        val reparsed = engine.parse(file)
        assertEquals(initialPages + 1, reparsed.pages.size)
    }

    private fun createDocxWithExtraEntry(target: File) {
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
        val docXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                        <w:r><w:rPr><w:b/></w:rPr><w:t>Eski Başlık</w:t></w:r>
                    </w:p>
                    <w:sectPr/>
                </w:body>
            </w:document>
        """.trimIndent()
        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><!-- CustomMarker --></w:styles>"""

        ZipOutputStream(FileOutputStream(target)).use { zos ->
            fun put(name: String, text: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("word/document.xml", docXml)
            put("word/styles.xml", stylesXml)
        }
    }
}
