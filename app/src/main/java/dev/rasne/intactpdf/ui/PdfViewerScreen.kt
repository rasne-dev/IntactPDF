package dev.rasne.intactpdf.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.rasne.intactpdf.model.PdfLoadState
import dev.rasne.intactpdf.model.ViewerMode
import dev.rasne.intactpdf.ui.components.PdfPageView
import dev.rasne.intactpdf.ui.components.TextEditDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    viewModel: PdfViewModel
) {
    val context = LocalContext.current
    val loadState by viewModel.loadState.collectAsState()
    val viewerMode by viewModel.viewerMode.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val pageBitmap by viewModel.pageBitmap.collectAsState()
    val textBlocks by viewModel.textBlocks.collectAsState()
    val selectedBlock by viewModel.selectedBlock.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val editCount by viewModel.editCount.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var showTextListSheet by remember { mutableStateOf(false) }
    var showSaveOptionsDialog by remember { mutableStateOf(false) }

    // File Open Launcher
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.openFromUri(it) }
    }

    // Save to Device Launcher
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destinationUri ->
        destinationUri?.let { uri ->
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    viewModel.saveEditsToDestination(outputStream) {
                        Toast.makeText(context, "PDF başarıyla kaydedildi.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Kayıt hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (loadState is PdfLoadState.Success) {
                        IconButton(onClick = { viewModel.closeDocument() }) {
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
                                "IntactPDF"
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (loadState is PdfLoadState.Success) {
                            Text(
                                text = if (viewerMode == ViewerMode.EDIT) "Düzenleme Modu • Sayfa ${currentPage + 1} / $totalPages" else "Görüntüleme Modu • Sayfa ${currentPage + 1} / $totalPages",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (viewerMode == ViewerMode.EDIT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (loadState is PdfLoadState.Success) {
                        // Undo Action
                        if (canUndo) {
                            IconButton(onClick = { viewModel.undoLastEdit() }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Geri Al")
                            }
                        }

                        // Toggle View / Edit Mode
                        FilledTonalButton(
                            onClick = { viewModel.toggleViewerMode() },
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
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (viewerMode == ViewerMode.EDIT) "Görüntüle" else "Düzenle",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Text List (only in Edit mode or accessible)
                        if (viewerMode == ViewerMode.EDIT) {
                            IconButton(onClick = { showTextListSheet = true }) {
                                Icon(Icons.Default.List, contentDescription = "Sayfadaki Metinler")
                            }
                        }

                        // Save Button
                        IconButton(onClick = { showSaveOptionsDialog = true }) {
                            if (editCount > 0) {
                                BadgedBox(badge = { Badge { Text("$editCount") } }) {
                                    Icon(Icons.Default.Save, contentDescription = "Kaydet")
                                }
                            } else {
                                Icon(Icons.Default.Save, contentDescription = "Kaydet")
                            }
                        }
                    } else {
                        IconButton(onClick = { filePicker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "PDF Aç")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (loadState is PdfLoadState.Success && totalPages > 1) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.setPage(currentPage - 1) },
                            enabled = currentPage > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Önceki Sayfa")
                        }

                        Text(
                            text = "${currentPage + 1} / $totalPages",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        IconButton(
                            onClick = { viewModel.setPage(currentPage + 1) },
                            enabled = currentPage < totalPages - 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Sonraki Sayfa")
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
                .background(Color(0xFFF2F4F7)),
            contentAlignment = Alignment.Center
        ) {
            when (val state = loadState) {
                is PdfLoadState.Idle -> {
                    WelcomeHomeScreen(
                        onOpenFile = { filePicker.launch(arrayOf("application/pdf")) },
                        onLoadSample = { viewModel.loadSamplePdf() }
                    )
                }

                is PdfLoadState.Loading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("PDF İşleniyor...", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is PdfLoadState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
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
                            Button(onClick = { filePicker.launch(arrayOf("application/pdf")) }) {
                                Text("Farklı Dosya Aç")
                            }
                        }
                    }
                }

                is PdfLoadState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Edit Mode Hint Banner
                        if (viewerMode == ViewerMode.EDIT) {
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
                                        text = "Düzenlemek veya silmek istediğiniz metne dokunun.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            pageBitmap?.let { bmp ->
                                PdfPageView(
                                    bitmap = bmp,
                                    textBlocks = textBlocks,
                                    viewerMode = viewerMode,
                                    onBlockClick = { block ->
                                        viewModel.selectBlock(block)
                                    }
                                )
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
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.padding(24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Text("PDF kaydediliyor...")
                            }
                        }
                    }
                }
            }
        }
    }

    // Text Edit Modal Dialog
    selectedBlock?.let { block ->
        TextEditDialog(
            block = block,
            onDismiss = { viewModel.selectBlock(null) },
            onSaveEdit = { newText, isRemoved ->
                viewModel.applyEdit(block, newText, isRemoved)
            }
        )
    }

    // Modal Bottom Sheet for Listing Page Text Blocks
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(textBlocks) { block ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showTextListSheet = false
                                        viewModel.selectBlock(block)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = block.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
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

    // Save Options Dialog
    if (showSaveOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showSaveOptionsDialog = false },
            title = { Text("Kaydetme Seçenekleri") },
            text = { Text("PDF belgenizi nasıl kaydetmek veya paylaşmak istersiniz?") },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveOptionsDialog = false
                        val exportFileName = "Duzenlenen_${System.currentTimeMillis()}.pdf"
                        saveFileLauncher.launch(exportFileName)
                    }
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cihaza Kaydet")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showSaveOptionsDialog = false
                        val shareFile = viewModel.getWorkingFileForSharing()
                        if (shareFile != null && shareFile.exists()) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                shareFile
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "PDF Paylaş"))
                        } else {
                            Toast.makeText(context, "Paylaşılacak dosya bulunamadı.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paylaş")
                }
            }
        )
    }
}

@Composable
private fun WelcomeHomeScreen(
    onOpenFile: () -> Unit,
    onLoadSample: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(76.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "IntactPDF",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Sayfa düzenini bozmadan PDF metinlerini doğrudan düzenleyin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onOpenFile,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("PDF Dosyası Seç", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onLoadSample,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.AutoStories, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Örnek Belgeyi İncele", fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(36.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FeatureRow(
                    icon = Icons.Default.Visibility,
                    text = "Görüntüleme ve Düzenleme modları arasında kolayca geçiş yapın."
                )
                FeatureRow(
                    icon = Icons.Default.TouchApp,
                    text = "Düzenleme modunda metinlere dokunarak doğrudan düzenleyin veya silin."
                )
                FeatureRow(
                    icon = Icons.Default.SaveAlt,
                    text = "Değişiklikleri yeni PDF olarak kaydedin veya paylaşın."
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
