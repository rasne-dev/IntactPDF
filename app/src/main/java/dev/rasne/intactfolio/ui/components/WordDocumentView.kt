package dev.rasne.intactfolio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rasne.intactfolio.model.ViewerMode
import dev.rasne.intactfolio.model.WordDocumentData
import dev.rasne.intactfolio.model.WordElement
import dev.rasne.intactfolio.model.WordPage
import dev.rasne.intactfolio.model.WordPageSetup
import kotlinx.coroutines.flow.distinctUntilChanged

private data class EditingTableCellTarget(
    val bodyIndex: Int,
    val rowIndex: Int,
    val colIndex: Int,
    val currentText: String
)

private val PaperBackground = Color(0xFFFFFFFF)
private val PaperBorder = Color(0xFFD0D4DC)
private val PaperInk = Color(0xFF1A1C1E)
private val PaperMutedInk = Color(0xFF6B7280)
private val PaperTableHeaderBg = Color(0xFFF3F4F6)
private val PaperTableBorder = Color(0xFFD1D5DB)
private val EditHighlightBorder = Color(0xFF1565C0)

@Composable
fun WordDocumentView(
    document: WordDocumentData,
    viewerMode: ViewerMode,
    isEditing: Boolean,
    scrollToPageRequest: Pair<Int, Long>?,
    onVisiblePageChanged: (Int) -> Unit,
    onUpdateParagraph: (bodyIndex: Int, newText: String) -> Unit,
    onDeleteElement: (bodyIndex: Int) -> Unit,
    onUpdateTableCell: (bodyIndex: Int, rowIndex: Int, colIndex: Int, newText: String) -> Unit
) {
    var editingParagraph by remember { mutableStateOf<WordElement.WordParagraph?>(null) }
    var editingTableCell by remember { mutableStateOf<EditingTableCellTarget?>(null) }

    val pages = remember(document) {
        document.pages.ifEmpty { listOf(WordPage(1, document.elements)) }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(scrollToPageRequest) {
        val (targetPage, _) = scrollToPageRequest ?: return@LaunchedEffect
        if (targetPage in pages.indices) {
            listState.animateScrollToItem(targetPage)
        }
    }

    LaunchedEffect(listState, pages.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index in pages.indices) {
                    onVisiblePageChanged(index)
                }
            }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.TopCenter
    ) {
        val setup = document.pageSetup
        val paperWidth = (maxWidth - 24.dp).coerceIn(260.dp, 680.dp)
        val aspectRatio = (setup.heightPt / setup.widthPt).coerceIn(0.6f, 2.0f)
        val paperMinHeight = paperWidth * aspectRatio
        val scale = paperWidth.value / setup.widthPt.coerceAtLeast(200f)

        val padLeft = (setup.marginLeftPt * scale).dp.coerceIn(18.dp, 40.dp)
        val padRight = (setup.marginRightPt * scale).dp.coerceIn(18.dp, 40.dp)
        val padTop = (setup.marginTopPt * scale).dp.coerceIn(22.dp, 44.dp)
        val padBottom = (setup.marginBottomPt * scale).dp.coerceIn(22.dp, 44.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 14.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            itemsIndexed(
                items = pages,
                key = { _, page -> "word_page_${page.pageNumber}" }
            ) { _, page ->
                WordPageSheet(
                    page = page,
                    totalPages = pages.size,
                    paperWidth = paperWidth,
                    paperMinHeight = paperMinHeight,
                    padLeft = padLeft,
                    padRight = padRight,
                    padTop = padTop,
                    padBottom = padBottom,
                    isEditMode = viewerMode == ViewerMode.EDIT,
                    onParagraphClick = { para ->
                        if (viewerMode == ViewerMode.EDIT && !isEditing) {
                            editingParagraph = para
                        }
                    },
                    onTableCellClick = { tableBodyIndex, rowIdx, colIdx, text ->
                        if (viewerMode == ViewerMode.EDIT && !isEditing) {
                            editingTableCell = EditingTableCellTarget(
                                bodyIndex = tableBodyIndex,
                                rowIndex = rowIdx,
                                colIndex = colIdx,
                                currentText = text
                            )
                        }
                    }
                )
            }
        }
    }

    editingParagraph?.let { para ->
        var textInput by remember(para.id) { mutableStateOf(para.text) }
        val focusRequester = remember(para.id) { FocusRequester() }
        LaunchedEffect(para.id) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
        AlertDialog(
            onDismissRequest = { editingParagraph = null },
            title = { Text(if (para.isHeading) "Başlığı Düzenle" else "Paragrafı Düzenle") },
            text = {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    label = { Text("Metin") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingParagraph = null
                        if (textInput != para.text) {
                            onUpdateParagraph(para.bodyIndex, textInput)
                        }
                    }
                ) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            editingParagraph = null
                            onDeleteElement(para.bodyIndex)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sil")
                    }
                    TextButton(onClick = { editingParagraph = null }) {
                        Text("Vazgeç")
                    }
                }
            }
        )
    }

    editingTableCell?.let { target ->
        var cellInput by remember(target) { mutableStateOf(target.currentText) }
        val focusRequester = remember(target) { FocusRequester() }
        LaunchedEffect(target) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
        AlertDialog(
            onDismissRequest = { editingTableCell = null },
            title = { Text("Tablo Hücresini Düzenle (${target.rowIndex + 1}. satır, ${target.colIndex + 1}. sütun)") },
            text = {
                OutlinedTextField(
                    value = cellInput,
                    onValueChange = { cellInput = it },
                    label = { Text("Hücre içeriği") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingTableCell = null
                        if (cellInput != target.currentText) {
                            onUpdateTableCell(target.bodyIndex, target.rowIndex, target.colIndex, cellInput)
                        }
                    }
                ) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTableCell = null }) {
                    Text("Vazgeç")
                }
            }
        )
    }
}

@Composable
private fun WordPageSheet(
    page: WordPage,
    totalPages: Int,
    paperWidth: Dp,
    paperMinHeight: Dp,
    padLeft: Dp,
    padRight: Dp,
    padTop: Dp,
    padBottom: Dp,
    isEditMode: Boolean,
    onParagraphClick: (WordElement.WordParagraph) -> Unit,
    onTableCellClick: (bodyIndex: Int, rowIndex: Int, colIndex: Int, text: String) -> Unit
) {
    Surface(
        color = PaperBackground,
        shape = RoundedCornerShape(3.dp),
        shadowElevation = 4.dp,
        border = BorderStroke(0.75.dp, PaperBorder),
        modifier = Modifier
            .width(paperWidth)
            .heightIn(min = paperMinHeight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = paperMinHeight)
                .padding(start = padLeft, end = padRight, top = padTop, bottom = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = padBottom),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (page.elements.isEmpty()) {
                    Text(
                        text = if (isEditMode) {
                            "(Boş sayfa — alttan yeni paragraf ekleyebilirsiniz)"
                        } else {
                            "(Boş sayfa)"
                        },
                        fontSize = 13.sp,
                        color = PaperMutedInk
                    )
                } else {
                    page.elements.forEach { element ->
                        when (element) {
                            is WordElement.WordParagraph -> {
                                WordPageParagraphItem(
                                    paragraph = element,
                                    isEditMode = isEditMode,
                                    onClick = { onParagraphClick(element) }
                                )
                            }
                            is WordElement.WordTable -> {
                                WordPageTableItem(
                                    table = element,
                                    isEditMode = isEditMode,
                                    onCellClick = { rowIdx, colIdx, text ->
                                        onTableCellClick(element.bodyIndex, rowIdx, colIdx, text)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "Sayfa ${page.pageNumber} / $totalPages",
                fontSize = 11.sp,
                color = PaperMutedInk,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun WordPageParagraphItem(
    paragraph: WordElement.WordParagraph,
    isEditMode: Boolean,
    onClick: () -> Unit
) {
    val isBlankLine = paragraph.text.isBlank()
    if (isBlankLine && !isEditMode) {
        Spacer(modifier = Modifier.height(6.dp))
        return
    }

    val fontSize = when {
        paragraph.headingLevel == 1 -> 20.sp
        paragraph.headingLevel == 2 -> 16.sp
        paragraph.headingLevel >= 3 -> 14.sp
        else -> 13.sp
    }

    val textAlign = when (paragraph.alignment?.lowercase()) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        "both", "justify" -> TextAlign.Justify
        else -> TextAlign.Start
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isEditMode) {
                    Modifier
                        .border(
                            width = 1.dp,
                            color = EditHighlightBorder.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable(onClick = onClick)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
            )
    ) {
        Text(
            text = if (isBlankLine) "(Boş paragraf — düzenlemek için dokunun)" else paragraph.text,
            fontSize = fontSize,
            fontWeight = if (paragraph.isBold || paragraph.isHeading) FontWeight.Bold else FontWeight.Normal,
            lineHeight = (fontSize.value * 1.45f).sp,
            textAlign = textAlign,
            color = if (isBlankLine) PaperMutedInk else PaperInk,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun WordPageTableItem(
    table: WordElement.WordTable,
    isEditMode: Boolean,
    onCellClick: (rowIndex: Int, colIndex: Int, text: String) -> Unit
) {
    val scrollState = rememberScrollState()
    val outerBorderColor = if (isEditMode) {
        EditHighlightBorder.copy(alpha = 0.45f)
    } else {
        PaperTableBorder
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, outerBorderColor, RoundedCornerShape(2.dp))
            .horizontalScroll(scrollState)
    ) {
        table.rows.forEachIndexed { rowIdx, row ->
            Row {
                row.cells.forEachIndexed { colIdx, cell ->
                    Box(
                        modifier = Modifier
                            .width(136.dp)
                            .border(0.5.dp, PaperTableBorder)
                            .background(if (rowIdx == 0) PaperTableHeaderBg else PaperBackground)
                            .then(
                                if (isEditMode) {
                                    Modifier.clickable { onCellClick(rowIdx, colIdx, cell.text) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cell.text.ifEmpty { if (isEditMode) "…" else "" },
                            fontSize = 12.sp,
                            fontWeight = if (rowIdx == 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (cell.text.isEmpty()) PaperMutedInk else PaperInk
                        )
                    }
                }
            }
        }
    }
}
