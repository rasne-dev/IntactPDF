package dev.rasne.intactfolio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rasne.intactfolio.core.XlsxEngine
import dev.rasne.intactfolio.model.ExcelCell
import dev.rasne.intactfolio.model.ExcelWorkbookData
import dev.rasne.intactfolio.model.ViewerMode

private data class SelectedCellCoord(
    val row: Int,
    val col: Int
)

@Composable
fun ExcelWorkbookView(
    workbook: ExcelWorkbookData,
    activeSheetIndex: Int,
    viewerMode: ViewerMode,
    isEditing: Boolean,
    onSelectSheet: (Int) -> Unit,
    onUpdateCell: (sheetIndex: Int, row: Int, col: Int, newValue: String) -> Unit,
    onAddRow: (sheetIndex: Int, afterRow: Int) -> Unit,
    onDeleteRow: (sheetIndex: Int, row: Int) -> Unit,
    onAddColumn: (sheetIndex: Int, afterCol: Int) -> Unit,
    onDeleteColumn: (sheetIndex: Int, col: Int) -> Unit
) {
    val sheets = workbook.sheets
    val safeSheetIndex = activeSheetIndex.coerceIn(0, (sheets.size - 1).coerceAtLeast(0))
    val activeSheet = sheets.getOrNull(safeSheetIndex) ?: return

    var selectedCoord by remember(safeSheetIndex) { mutableStateOf<SelectedCellCoord?>(null) }
    var editingCoord by remember(safeSheetIndex) { mutableStateOf<SelectedCellCoord?>(null) }

    val hScroll = rememberScrollState()
    val rowHeaderWidth = 48.dp
    val cellWidth = 118.dp
    val cellHeight = 40.dp

    Column(modifier = Modifier.fillMaxSize()) {
        if (sheets.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = safeSheetIndex,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                sheets.forEachIndexed { idx, sheet ->
                    Tab(
                        selected = idx == safeSheetIndex,
                        onClick = { onSelectSheet(idx) },
                        text = { Text(sheet.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }

        // Selected cell formula/value bar
        val selectedCell: ExcelCell? = selectedCoord?.let { activeSheet.cells[it.row to it.col] }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val refLabel = selectedCoord?.let { XlsxEngine.makeCellRef(it.row, it.col) } ?: "—"
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = refLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                val preview = when {
                    selectedCoord == null -> "Hücre seçmek için tabloya dokunun"
                    selectedCell?.formula != null -> "=${selectedCell.formula}  (${selectedCell.value})"
                    selectedCell != null -> selectedCell.value
                    else -> "(Boş hücre)"
                }
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selectedCoord == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f)
                )
                if (viewerMode == ViewerMode.EDIT && selectedCoord != null && !isEditing) {
                    IconButton(
                        onClick = { editingCoord = selectedCoord },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Seçili Hücreyi Düzenle",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        // Sticky Column Header Row (corner "#" stays pinned on left)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(
                modifier = Modifier
                    .width(rowHeaderWidth)
                    .height(34.dp)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("#", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll)
            ) {
                for (col in 0 until activeSheet.maxCol) {
                    Box(
                        modifier = Modifier
                            .width(cellWidth)
                            .height(34.dp)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = XlsxEngine.colIndexToLetter(col),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Scrollable Rows (row numbers pinned on left, cells horizontally synced with column header)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            items(activeSheet.maxRow, key = { it + 1 }) { idx ->
                val rowNum = idx + 1
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(rowHeaderWidth)
                            .height(cellHeight)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$rowNum",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(hScroll)
                    ) {
                        for (col in 0 until activeSheet.maxCol) {
                            val cell = activeSheet.cells[rowNum to col]
                            val isSelected = selectedCoord?.row == rowNum && selectedCoord?.col == col
                            val bgColor = when {
                                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                rowNum == 1 -> MaterialTheme.colorScheme.surfaceContainerLow
                                else -> MaterialTheme.colorScheme.surfaceContainerLowest
                            }
                            val borderColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }

                            Box(
                                modifier = Modifier
                                    .width(cellWidth)
                                    .height(cellHeight)
                                    .background(bgColor)
                                    .border(if (isSelected) 1.5.dp else 0.5.dp, borderColor)
                                    .clickable {
                                        val coord = SelectedCellCoord(rowNum, col)
                                        selectedCoord = coord
                                        if (viewerMode == ViewerMode.EDIT && !isEditing) {
                                            editingCoord = coord
                                        }
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = if (cell?.isNumeric == true) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Text(
                                    text = cell?.value ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (rowNum == 1) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = if (cell?.isNumeric == true) TextAlign.End else TextAlign.Start,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingCoord?.let { coord ->
        val existing = activeSheet.cells[coord.row to coord.col]
        val initialText = existing?.formula?.let { "=$it" } ?: existing?.value ?: ""
        var input by remember(coord, safeSheetIndex) { mutableStateOf(initialText) }
        val focusRequester = remember(coord, safeSheetIndex) { FocusRequester() }
        val ref = XlsxEngine.makeCellRef(coord.row, coord.col)

        LaunchedEffect(coord, safeSheetIndex) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }

        AlertDialog(
            onDismissRequest = { editingCoord = null },
            title = { Text("Hücreyi Düzenle ($ref)") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Değer veya formül (=SUM(...))") },
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )

                    Text(
                        text = "Satır ve Sütun İşlemleri",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                editingCoord = null
                                onAddRow(safeSheetIndex, coord.row)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Satır Ekle", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                editingCoord = null
                                onDeleteRow(safeSheetIndex, coord.row)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Satır Sil", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                editingCoord = null
                                onAddColumn(safeSheetIndex, coord.col)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sütun Ekle", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                editingCoord = null
                                onDeleteColumn(safeSheetIndex, coord.col)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sütun Sil", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingCoord = null
                        onUpdateCell(safeSheetIndex, coord.row, coord.col, input)
                    }
                ) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                Row {
                    if (existing != null) {
                        TextButton(
                            onClick = {
                                editingCoord = null
                                onUpdateCell(safeSheetIndex, coord.row, coord.col, "")
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Temizle")
                        }
                    }
                    TextButton(onClick = { editingCoord = null }) {
                        Text("Vazgeç")
                    }
                }
            }
        )
    }
}
