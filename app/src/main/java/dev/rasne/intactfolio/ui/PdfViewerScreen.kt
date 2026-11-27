package dev.rasne.intactfolio.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.rasne.intactfolio.model.DocumentType
import dev.rasne.intactfolio.model.PdfLoadState
import dev.rasne.intactfolio.model.ViewerMode
import dev.rasne.intactfolio.ui.components.AddTextDialog
import dev.rasne.intactfolio.ui.components.ExcelWorkbookView
import dev.rasne.intactfolio.ui.components.PdfPageView
import dev.rasne.intactfolio.ui.components.TextEditDialog
import dev.rasne.intactfolio.ui.components.WordDocumentView
import kotlinx.coroutines.launch

private class CreateDocumentWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (mimeType, fileName) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, fileName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    viewModel: PdfViewModel
) {
    val context = LocalContext.current
    val loadState by viewModel.loadState.collectAsState()
    val documentType by viewModel.documentType.collectAsState()
    val viewerMode by viewModel.viewerMode.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val pageBitmap by viewModel.pageBitmap.collectAsState()
    val textBlocks by viewModel.textBlocks.collectAsState()
    val selectedBlock by viewModel.selectedBlock.collectAsState()
    val wordDocument by viewModel.wordDocument.collectAsState()
    val wordScrollRequest by viewModel.wordScrollRequest.collectAsState()
    val excelWorkbook by viewModel.excelWorkbook.collectAsState()
    val activeSheetIndex by viewModel.activeSheetIndex.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val editCount by viewModel.editCount.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isPageLoading by viewModel.isPageLoading.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val statusUndoable by viewModel.statusUndoable.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()

    var showTextListSheet by remember { mutableStateOf(false) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var showAddWordParagraphDialog by remember { mutableStateOf(false) }
    var showGoToPageDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    var pendingLeaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterSaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val requestLeave: (() -> Unit) -> Unit = { action ->
        if (loadState is PdfLoadState.Success && hasUnsavedChanges) {
            pendingLeaveAction = action
        } else {
            action()
        }
    }

    BackHandler(enabled = loadState !is PdfLoadState.Idle) {
        requestLeave { viewModel.closeDocument() }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.openFromUri(it) }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = CreateDocumentWithMime()
    ) { destinationUri ->
        if (destinationUri != null) {
            viewModel.saveEditsToDestination(destinationUri) {
                val next = afterSaveAction
                afterSaveAction = null
                next?.invoke()
            }
        } else {
            afterSaveAction = null
        }
    }

    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        val offerUndo = statusUndoable && canUndo
        viewModel.clearStatusMessage()
        uiScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (offerUndo) "Geri Al" else null,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoLastEdit()
            }
        }
    }

    val bgColor = MaterialTheme.colorScheme.surfaceContainerLow

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (loadState is PdfLoadState.Success) {
                        IconButton(onClick = { requestLeave { viewModel.closeDocument() } }) {
                            Icon(Icons.Default.Close, contentDescription = "Belgeyi Kapat")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = if (loadState is PdfLoadState.Success) {
                                (loadState as PdfLoadState.Success).title
                            } else {
                                "IntactFolio"
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (loadState is PdfLoadState.Success) {
                            val subtitle = when {
                                viewerMode == ViewerMode.EDIT -> "Düzenleme Modu • ${documentType.displayName}"
                                documentType == DocumentType.PDF -> "Sayfa ${currentPage + 1} / $totalPages"
                                documentType == DocumentType.DOCX -> "Word • Sayfa ${currentPage + 1} / $totalPages"
                                documentType == DocumentType.XLSX -> {
                                    val sheetName = excelWorkbook?.sheets?.getOrNull(activeSheetIndex)?.name
                                        ?: "Sayfa ${activeSheetIndex + 1}"
                                    "Excel • $sheetName"
                                }
                                else -> ""
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (viewerMode == ViewerMode.EDIT) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                actions = {
                    if (loadState is PdfLoadState.Success) {
                        AnimatedVisibility(visible = canUndo) {
                            IconButton(
                                onClick = { viewModel.undoLastEdit() },
                                enabled = !isEditing && !isPageLoading
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Geri Al")
                            }
                        }

                        FilledTonalButton(
                            onClick = { viewModel.toggleViewerMode() },
                            enabled = !isEditing,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = if (viewerMode == ViewerMode.EDIT) {
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            }
                        ) {
                            Icon(
                                imageVector = if (viewerMode == ViewerMode.EDIT) Icons.Default.Visibility else Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (viewerMode == ViewerMode.EDIT) "Oku" else "Düzenle",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                if (editCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$editCount") } }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Daha Fazla")
                                    }
                                } else {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Daha Fazla")
                                }
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Başka Belge Aç") },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        requestLeave { filePicker.launch(DocumentType.supportedMimeTypes) }
                                    },
                                    enabled = !isEditing && !isSaving
                                )
                                HorizontalDivider()
                                if (viewerMode == ViewerMode.EDIT) {
                                    when (documentType) {
                                        DocumentType.PDF -> {
                                            DropdownMenuItem(
                                                text = { Text("Yeni Metin Ekle") },
                                                leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    showAddTextDialog = true
                                                },
                                                enabled = !isEditing && !isSaving
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Yeni Sayfa Ekle") },
                                                leadingIcon = { Icon(Icons.Default.PostAdd, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.addNewPage()
                                                },
                                                enabled = !isEditing && !isSaving && !isPageLoading
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Sayfadaki Metinler") },
                                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    showTextListSheet = true
                                                }
                                            )
                                        }
                                        DocumentType.DOCX -> {
                                            DropdownMenuItem(
                                                text = { Text("Yeni Paragraf Ekle") },
                                                leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    showAddWordParagraphDialog = true
                                                },
                                                enabled = !isEditing && !isSaving
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Yeni Sayfa Ekle") },
                                                leadingIcon = { Icon(Icons.Default.PostAdd, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.addWordPageBreak()
                                                },
                                                enabled = !isEditing && !isSaving
                                            )
                                        }
                                        DocumentType.XLSX -> {
                                            val activeSheet = excelWorkbook?.sheets?.getOrNull(activeSheetIndex)
                                            val lastDataRow = activeSheet?.cells?.keys?.maxOfOrNull { it.first } ?: 1
                                            val lastDataCol = activeSheet?.cells?.keys?.maxOfOrNull { it.second } ?: 0
                                            DropdownMenuItem(
                                                text = { Text("Sona Satır Ekle") },
                                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.addExcelRow(activeSheetIndex, lastDataRow)
                                                },
                                                enabled = !isEditing && !isSaving
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Sona Sütun Ekle") },
                                                leadingIcon = { Icon(Icons.Default.GridOn, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.addExcelColumn(activeSheetIndex, lastDataCol)
                                                },
                                                enabled = !isEditing && !isSaving
                                            )
                                        }
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text("Cihaza Kaydet") },
                                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        saveFileLauncher.launch(documentType.mimeType to viewModel.suggestedFileName())
                                    },
                                    enabled = !isEditing && !isSaving
                                )
                                DropdownMenuItem(
                                    text = { Text("Paylaş") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.prepareShareFile { shareFile ->
                                            if (shareFile != null && shareFile.exists()) {
                                                try {
                                                    val uri = FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        shareFile
                                                    )
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = documentType.mimeType
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "Belge Paylaş"))
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "Paylaşım başlatılamadı.", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Paylaşılacak dosya bulunamadı.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    enabled = !isEditing && !isSaving
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Gizlilik Politikası") },
                                    leadingIcon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        try {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://rasne-dev.github.io/IntactPDF/privacy-policy")
                                            )
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Tarayıcı açılamadı.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { filePicker.launch(DocumentType.supportedMimeTypes) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Belge Aç")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (loadState is PdfLoadState.Success && viewerMode == ViewerMode.EDIT) {
                when (documentType) {
                    DocumentType.PDF -> {
                        ExtendedFloatingActionButton(
                            onClick = { showAddTextDialog = true },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text("Metin Ekle") },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    DocumentType.DOCX -> {
                        ExtendedFloatingActionButton(
                            onClick = { showAddWordParagraphDialog = true },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text("Paragraf Ekle") },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    DocumentType.XLSX -> Unit
                }
            }
        },
        bottomBar = {
            if (loadState is PdfLoadState.Success) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    when (documentType) {
                        DocumentType.PDF -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.setPage(currentPage - 1) },
                                        enabled = currentPage > 0 && !isPageLoading && !isEditing
                                    ) {
                                        Icon(Icons.Default.ChevronLeft, contentDescription = "Önceki Sayfa")
                                    }

                                    TextButton(
                                        onClick = { showGoToPageDialog = true },
                                        enabled = totalPages > 1 && !isPageLoading && !isEditing,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "${currentPage + 1} / $totalPages",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.setPage(currentPage + 1) },
                                        enabled = currentPage < totalPages - 1 && !isPageLoading && !isEditing
                                    ) {
                                        Icon(Icons.Default.ChevronRight, contentDescription = "Sonraki Sayfa")
                                    }
                                }

                                if (viewerMode == ViewerMode.EDIT) {
                                    FilledTonalButton(
                                        onClick = { viewModel.addNewPage() },
                                        enabled = !isEditing && !isPageLoading,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.PostAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Sayfa Ekle", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        DocumentType.DOCX -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.setPage(currentPage - 1) },
                                        enabled = currentPage > 0 && !isEditing
                                    ) {
                                        Icon(Icons.Default.ChevronLeft, contentDescription = "Önceki Sayfa")
                                    }

                                    TextButton(
                                        onClick = { showGoToPageDialog = true },
                                        enabled = totalPages > 1 && !isEditing,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "${currentPage + 1} / $totalPages",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.setPage(currentPage + 1) },
                                        enabled = currentPage < totalPages - 1 && !isEditing
                                    ) {
                                        Icon(Icons.Default.ChevronRight, contentDescription = "Sonraki Sayfa")
                                    }
                                }

                                if (viewerMode == ViewerMode.EDIT) {
                                    FilledTonalButton(
                                        onClick = { viewModel.addWordPageBreak() },
                                        enabled = !isEditing,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.PostAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Sayfa Ekle", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        DocumentType.XLSX -> {
                            val sheetsCount = excelWorkbook?.sheets?.size ?: 1
                            val activeSheet = excelWorkbook?.sheets?.getOrNull(activeSheetIndex)
                            val lastDataRow = activeSheet?.cells?.keys?.maxOfOrNull { it.first } ?: 1
                            val lastDataCol = activeSheet?.cells?.keys?.maxOfOrNull { it.second } ?: 0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Sayfa ${activeSheetIndex + 1} / $sheetsCount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (viewerMode == ViewerMode.EDIT) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(
                                            onClick = { viewModel.addExcelRow(activeSheetIndex, lastDataRow) },
                                            enabled = !isEditing,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("+ Satır", fontSize = 12.sp)
                                        }
                                        FilledTonalButton(
                                            onClick = { viewModel.addExcelColumn(activeSheetIndex, lastDataCol) },
                                            enabled = !isEditing,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("+ Sütun", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            when (val state = loadState) {
                is PdfLoadState.Idle -> {
                    WelcomeHomeScreen(
                        onOpenFile = { filePicker.launch(DocumentType.supportedMimeTypes) },
                        onLoadSamplePdf = { viewModel.loadSamplePdf() },
                        onLoadSampleDocx = { viewModel.loadSampleDocx() },
                        onLoadSampleXlsx = { viewModel.loadSampleXlsx() },
                        onOpenPrivacyPolicy = {
                            try {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://rasne-dev.github.io/IntactPDF/privacy-policy")
                                )
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Tarayıcı açılamadı.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                is PdfLoadState.Loading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Belge İşleniyor...", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is PdfLoadState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.closeDocument() }) {
                                Text("Ana Sayfaya Dön")
                            }
                            Button(onClick = { filePicker.launch(DocumentType.supportedMimeTypes) }) {
                                Text("Farklı Dosya Aç")
                            }
                        }
                    }
                }

                is PdfLoadState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(visible = viewerMode == ViewerMode.EDIT) {
                            val hintText = when (documentType) {
                                DocumentType.PDF -> "Düzenlemek için dokunun, taşımak için sürükleyin"
                                DocumentType.DOCX -> "Düzenlemek veya silmek için paragrafa ya da tablo hücresine dokunun"
                                DocumentType.XLSX -> "Düzenlemek, formül yazmak veya satır/sütun eklemek için hücreye dokunun"
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.TouchApp,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = hintText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = isEditing && !isPageLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        AnimatedVisibility(visible = isPageLoading) {
                            Column {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = "Sayfa hazırlanıyor…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            when (documentType) {
                                DocumentType.PDF -> {
                                    pageBitmap?.let { bmp ->
                                        PdfPageView(
                                            bitmap = bmp,
                                            textBlocks = textBlocks,
                                            viewerMode = viewerMode,
                                            onBlockClick = { block ->
                                                if (!isEditing) viewModel.selectBlock(block)
                                            },
                                            onBlockMoved = { block, newNormX, newNormY ->
                                                viewModel.moveTextBlock(block, newNormX, newNormY)
                                            }
                                        )
                                    }
                                }
                                DocumentType.DOCX -> {
                                    wordDocument?.let { doc ->
                                        WordDocumentView(
                                            document = doc,
                                            viewerMode = viewerMode,
                                            isEditing = isEditing,
                                            scrollToPageRequest = wordScrollRequest,
                                            onVisiblePageChanged = { viewModel.onWordVisiblePageChanged(it) },
                                            onUpdateParagraph = { bodyIndex, newText ->
                                                viewModel.updateWordParagraph(bodyIndex, newText)
                                            },
                                            onDeleteElement = { bodyIndex ->
                                                viewModel.deleteWordElement(bodyIndex)
                                            },
                                            onUpdateTableCell = { bodyIndex, rowIndex, colIndex, newText ->
                                                viewModel.updateWordTableCell(bodyIndex, rowIndex, colIndex, newText)
                                            }
                                        )
                                    }
                                }
                                DocumentType.XLSX -> {
                                    excelWorkbook?.let { workbook ->
                                        ExcelWorkbookView(
                                            workbook = workbook,
                                            activeSheetIndex = activeSheetIndex,
                                            viewerMode = viewerMode,
                                            isEditing = isEditing,
                                            onSelectSheet = { viewModel.selectExcelSheet(it) },
                                            onUpdateCell = { sheetIdx, row, col, newValue ->
                                                viewModel.updateExcelCell(sheetIdx, row, col, newValue)
                                            },
                                            onAddRow = { sheetIdx, afterRow ->
                                                viewModel.addExcelRow(sheetIdx, afterRow)
                                            },
                                            onDeleteRow = { sheetIdx, row ->
                                                viewModel.deleteExcelRow(sheetIdx, row)
                                            },
                                            onAddColumn = { sheetIdx, afterCol ->
                                                viewModel.addExcelColumn(sheetIdx, afterCol)
                                            },
                                            onDeleteColumn = { sheetIdx, col ->
                                                viewModel.deleteExcelColumn(sheetIdx, col)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isSaving) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.padding(24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Text("${documentType.displayName} kaydediliyor...")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingLeaveAction?.let { leaveAction ->
        AlertDialog(
            onDismissRequest = { pendingLeaveAction = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Kaydedilmemiş değişiklikler") },
            text = { Text("Bu belgede kaydedilmemiş düzenlemeler var. Kaydetmeden devam ederseniz kaybolacaklar.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLeaveAction = null
                        afterSaveAction = leaveAction
                        saveFileLauncher.launch(documentType.mimeType to viewModel.suggestedFileName())
                    }
                ) { Text("Kaydet") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingLeaveAction = null }) { Text("Vazgeç") }
                    TextButton(
                        onClick = {
                            pendingLeaveAction = null
                            leaveAction()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Kaydetmeden Devam Et") }
                }
            }
        )
    }

    if (showGoToPageDialog) {
        var pageInput by remember { mutableStateOf((currentPage + 1).toString()) }
        val targetPage = pageInput.toIntOrNull()
        val isValidPage = targetPage != null && targetPage in 1..totalPages
        val focusRequester = remember { FocusRequester() }
        val goToPage = {
            if (isValidPage && targetPage != null) {
                showGoToPageDialog = false
                viewModel.setPage(targetPage - 1)
            }
        }
        LaunchedEffect(Unit) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
        AlertDialog(
            onDismissRequest = { showGoToPageDialog = false },
            title = { Text("Sayfaya Git") },
            text = {
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { pageInput = it.filter(Char::isDigit).take(6) },
                    label = { Text("Sayfa numarası (1–$totalPages)") },
                    singleLine = true,
                    isError = pageInput.isNotEmpty() && !isValidPage,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { goToPage() }),
                    modifier = Modifier.focusRequester(focusRequester)
                )
            },
            confirmButton = {
                TextButton(onClick = { goToPage() }, enabled = isValidPage) { Text("Git") }
            },
            dismissButton = {
                TextButton(onClick = { showGoToPageDialog = false }) { Text("Vazgeç") }
            }
        )
    }

    selectedBlock?.let { block ->
        TextEditDialog(
            block = block,
            onDismiss = { viewModel.selectBlock(null) },
            onSaveEdit = { newText, isRemoved ->
                viewModel.applyEdit(block, newText, isRemoved)
            }
        )
    }

    if (showAddTextDialog) {
        AddTextDialog(
            onDismiss = { showAddTextDialog = false },
            onAddText = { text, fontSize ->
                showAddTextDialog = false
                viewModel.addNewText(text, fontSize)
            }
        )
    }

    if (showAddWordParagraphDialog) {
        var paragraphInput by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
        AlertDialog(
            onDismissRequest = { showAddWordParagraphDialog = false },
            title = { Text("Yeni Paragraf Ekle") },
            text = {
                OutlinedTextField(
                    value = paragraphInput,
                    onValueChange = { paragraphInput = it },
                    label = { Text("Paragraf metni") },
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
                        showAddWordParagraphDialog = false
                        viewModel.addWordParagraph(paragraphInput)
                    },
                    enabled = paragraphInput.isNotBlank()
                ) { Text("Ekle") }
            },
            dismissButton = {
                TextButton(onClick = { showAddWordParagraphDialog = false }) { Text("Vazgeç") }
            }
        )
    }

    if (showTextListSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTextListSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Sayfadaki Metinler (${textBlocks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (textBlocks.isEmpty()) {
                    Text(
                        text = "Bu sayfada algılanan metin bulunamadı.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(textBlocks) { block ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showTextListSheet = false
                                        viewModel.selectBlock(block)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = block.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Düzenle",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun WelcomeHomeScreen(
    onOpenFile: () -> Unit,
    onLoadSamplePdf: () -> Unit,
    onLoadSampleDocx: () -> Unit,
    onLoadSampleXlsx: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "IntactFolio",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "PDF, Word (.docx) ve Excel (.xlsx) belgelerini sayfa düzenini bozmadan görüntüleyin ve düzenleyin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onOpenFile,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Belge Seç (PDF, Word, Excel)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Örnek belgeleri incele",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onLoadSamplePdf,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("PDF", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onLoadSampleDocx,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Word", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onLoadSampleXlsx,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Excel", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FeatureRow(
                    icon = Icons.Default.Visibility,
                    text = "PDF, Word (.docx) ve Excel (.xlsx) dosyalarını çevrimdışı açın"
                )
                FeatureRow(
                    icon = Icons.Default.TouchApp,
                    text = "Düzenleme modunda metinlere, paragraflara veya hücrelere dokunun"
                )
                FeatureRow(
                    icon = Icons.Default.SaveAlt,
                    text = "Değişiklikleri orijinal formatında kaydedin veya paylaşın"
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        TextButton(
            onClick = onOpenPrivacyPolicy
        ) {
            Icon(
                imageVector = Icons.Default.PrivacyTip,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Gizlilik Politikası",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
