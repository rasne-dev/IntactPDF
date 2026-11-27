package dev.rasne.intactfolio.model

enum class DocumentType(
    val displayName: String,
    val extension: String,
    val mimeType: String
) {
    PDF(
        displayName = "PDF",
        extension = "pdf",
        mimeType = "application/pdf"
    ),
    DOCX(
        displayName = "Word",
        extension = "docx",
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ),
    XLSX(
        displayName = "Excel",
        extension = "xlsx",
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    companion object {
        val supportedMimeTypes: Array<String> = arrayOf(
            PDF.mimeType,
            DOCX.mimeType,
            XLSX.mimeType
        )
    }
}

sealed interface WordElement {
    val id: String
    val bodyIndex: Int

    data class WordParagraph(
        override val id: String,
        override val bodyIndex: Int,
        val text: String,
        val styleId: String? = null,
        val isBold: Boolean = false,
        val isHeading: Boolean = false,
        val headingLevel: Int = 0,
        val alignment: String? = null,
        val startsNewPage: Boolean = false,
        val hasPageBreakAfter: Boolean = false
    ) : WordElement

    data class WordTable(
        override val id: String,
        override val bodyIndex: Int,
        val rows: List<WordTableRow>
    ) : WordElement
}

data class WordTableRow(
    val rowIndex: Int,
    val cells: List<WordTableCell>
)

data class WordTableCell(
    val rowIndex: Int,
    val colIndex: Int,
    val text: String
)

data class WordPageSetup(
    val widthPt: Float = 595.3f,
    val heightPt: Float = 841.9f,
    val marginTopPt: Float = 72f,
    val marginBottomPt: Float = 72f,
    val marginLeftPt: Float = 72f,
    val marginRightPt: Float = 72f
)

data class WordPage(
    val pageNumber: Int,
    val elements: List<WordElement>
)

data class WordDocumentData(
    val elements: List<WordElement>,
    val pageSetup: WordPageSetup = WordPageSetup(),
    val pages: List<WordPage> = emptyList()
)

data class ExcelCell(
    val row: Int,
    val col: Int,
    val ref: String,
    val value: String,
    val formula: String? = null,
    val isNumeric: Boolean = false
)

data class ExcelSheet(
    val index: Int,
    val name: String,
    val entryPath: String,
    val maxRow: Int,
    val maxCol: Int,
    val cells: Map<Pair<Int, Int>, ExcelCell>
)

data class ExcelWorkbookData(
    val sheets: List<ExcelSheet>
)
