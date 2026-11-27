package dev.rasne.intactfolio.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rasne.intactfolio.core.DocxEngine
import dev.rasne.intactfolio.core.PdfEngine
import dev.rasne.intactfolio.core.XlsxEngine
import dev.rasne.intactfolio.model.DocumentType
import dev.rasne.intactfolio.model.ExcelWorkbookData
import dev.rasne.intactfolio.model.PdfLoadState
import dev.rasne.intactfolio.model.PdfTextBlock
import dev.rasne.intactfolio.model.TextEditOperation
import dev.rasne.intactfolio.model.ViewerMode
import dev.rasne.intactfolio.model.WordDocumentData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

private data class UndoSnapshot(
    val file: File,
    val blocksMap: Map<Int, List<PdfTextBlock>>,
    /** Bu anlık görüntü alındığında belgenin revizyon numarası (geri alınca buna dönülür). */
    val revision: Long = 0L,
    val activeSheetIndex: Int = 0
)

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val MAX_UNDO_STEPS = 20
        /** Geri alma anlık görüntülerinin toplam disk bütçesi. Büyük belgelerde önbelleğin şişmesini önler. */
        const val MAX_UNDO_BYTES = 256L * 1024L * 1024L
    }

    private val engine = PdfEngine(application)
    private val docxEngine = DocxEngine()
    private val xlsxEngine = XlsxEngine()

    private val _loadState = MutableStateFlow<PdfLoadState>(PdfLoadState.Idle)
    val loadState: StateFlow<PdfLoadState> = _loadState.asStateFlow()

    private val _documentType = MutableStateFlow(DocumentType.PDF)
    val documentType: StateFlow<DocumentType> = _documentType.asStateFlow()

    private val _viewerMode = MutableStateFlow(ViewerMode.VIEW)
    val viewerMode: StateFlow<ViewerMode> = _viewerMode.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _pageBitmap = MutableStateFlow<Bitmap?>(null)
    val pageBitmap: StateFlow<Bitmap?> = _pageBitmap.asStateFlow()

    private val _textBlocks = MutableStateFlow<List<PdfTextBlock>>(emptyList())
    val textBlocks: StateFlow<List<PdfTextBlock>> = _textBlocks.asStateFlow()

    private val _selectedBlock = MutableStateFlow<PdfTextBlock?>(null)
    val selectedBlock: StateFlow<PdfTextBlock?> = _selectedBlock.asStateFlow()

    private val _wordDocument = MutableStateFlow<WordDocumentData?>(null)
    val wordDocument: StateFlow<WordDocumentData?> = _wordDocument.asStateFlow()

    private val _wordScrollRequest = MutableStateFlow<Pair<Int, Long>?>(null)
    val wordScrollRequest: StateFlow<Pair<Int, Long>?> = _wordScrollRequest.asStateFlow()

    private val _excelWorkbook = MutableStateFlow<ExcelWorkbookData?>(null)
    val excelWorkbook: StateFlow<ExcelWorkbookData?> = _excelWorkbook.asStateFlow()

    private val _activeSheetIndex = MutableStateFlow(0)
    val activeSheetIndex: StateFlow<Int> = _activeSheetIndex.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _editCount = MutableStateFlow(0)
    val editCount: StateFlow<Int> = _editCount.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isPageLoading = MutableStateFlow(false)
    val isPageLoading: StateFlow<Boolean> = _isPageLoading.asStateFlow()

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Mevcut durum mesajı bir düzenlemenin sonucuysa true (arayüz "Geri Al" eylemi sunabilir). */
    private val _statusUndoable = MutableStateFlow(false)
    val statusUndoable: StateFlow<Boolean> = _statusUndoable.asStateFlow()

    /**
     * Revizyon takibi: her başarılı değişiklik yeni bir numara üretir, geri alma önceki numaraya döner.
     * Kaydedilen revizyon ile mevcut revizyon farklıysa kaydedilmemiş değişiklik vardır.
     */
    private var revisionCounter = 0L
    private val _revision = MutableStateFlow(0L)
    private val _savedRevision = MutableStateFlow(0L)
    val hasUnsavedChanges: StateFlow<Boolean> =
        combine(_revision, _savedRevision) { current, saved -> current != saved }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var workingFile: File? = null
    private var documentTitle: String = ""
    /** Invalidates work started for a previously opened or closed document. */
    private var documentGeneration = 0L
    private var pageLoadGeneration = 0L
    private val undoHistory = mutableListOf<UndoSnapshot>()
    private val pageBlocksCache = mutableMapOf<Int, MutableList<PdfTextBlock>>()

    private fun postStatus(message: String, undoable: Boolean = false) {
        _statusUndoable.value = undoable
        _statusMessage.value = message
    }

    private fun resetRevisions() {
        revisionCounter = 0L
        _revision.value = 0L
        _savedRevision.value = 0L
    }

    /** Başarılı her değişiklikten sonra ortak durum güncellemesi. */
    private fun markModified() {
        _revision.value = ++revisionCounter
        _editCount.value = _editCount.value + 1
        _canUndo.value = undoHistory.isNotEmpty()
    }

    fun toggleViewerMode() {
        _viewerMode.value = if (_viewerMode.value == ViewerMode.VIEW) ViewerMode.EDIT else ViewerMode.VIEW
    }

    fun loadSamplePdf() {
        val generation = ++documentGeneration
        viewModelScope.launch {
            _loadState.value = PdfLoadState.Loading
            try {
                val sampleFile = File(getApplication<Application>().cacheDir, "sample_source_${System.currentTimeMillis()}.pdf")
                engine.createSamplePdf(sampleFile)
                openFile(sampleFile, "Örnek PDF Belgesi", DocumentType.PDF, generation)
            } catch (e: Exception) {
                if (generation == documentGeneration) {
                    _loadState.value = PdfLoadState.Error("Örnek PDF oluşturulamadı: ${e.message}")
                }
            }
        }
    }

    fun loadSampleDocx() {
        val generation = ++documentGeneration
        viewModelScope.launch {
            _loadState.value = PdfLoadState.Loading
            try {
                val sampleFile = File(getApplication<Application>().cacheDir, "sample_source_${System.currentTimeMillis()}.docx")
                withContext(Dispatchers.IO) { docxEngine.createSampleDocx(sampleFile) }
                openFile(sampleFile, "Örnek Word Belgesi", DocumentType.DOCX, generation)
            } catch (e: Exception) {
                if (generation == documentGeneration) {
                    _loadState.value = PdfLoadState.Error("Örnek Word belgesi oluşturulamadı: ${e.message}")
                }
            }
        }
    }

    fun loadSampleXlsx() {
        val generation = ++documentGeneration
        viewModelScope.launch {
            _loadState.value = PdfLoadState.Loading
            try {
                val sampleFile = File(getApplication<Application>().cacheDir, "sample_source_${System.currentTimeMillis()}.xlsx")
                withContext(Dispatchers.IO) { xlsxEngine.createSampleXlsx(sampleFile) }
                openFile(sampleFile, "Örnek Excel Tablosu", DocumentType.XLSX, generation)
            } catch (e: Exception) {
                if (generation == documentGeneration) {
                    _loadState.value = PdfLoadState.Error("Örnek Excel tablosu oluşturulamadı: ${e.message}")
                }
            }
        }
    }

    fun openFromUri(uri: Uri) {
        val generation = ++documentGeneration
        viewModelScope.launch {
            _loadState.value = PdfLoadState.Loading
            var tempFile: File? = null
            try {
                val context = getApplication<Application>()
                val target = File(context.cacheDir, "opened_doc_${System.currentTimeMillis()}.tmp")
                tempFile = target

                // Kopyalama ana iş parçacığında yapılırsa büyük dosyalarda / yavaş sağlayıcılarda ANR olur
                val copiedBytes = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: return@withContext -1L
                    input.use { i ->
                        FileOutputStream(target).use { o ->
                            i.copyTo(o)
                        }
                    }
                }

                if (generation != documentGeneration) {
                    target.delete()
                    return@launch
                }
                if (copiedBytes < 0L) {
                    target.delete()
                    _loadState.value = PdfLoadState.Error("Dosya okunamadı. Lütfen farklı bir dosya deneyin.")
                    return@launch
                }
                if (copiedBytes == 0L) {
                    target.delete()
                    _loadState.value = PdfLoadState.Error("Dosya boş. Lütfen geçerli bir belge seçin.")
                    return@launch
                }

                val rawFileName = withContext(Dispatchers.IO) { resolveFileNameWithExtension(uri) }
                val detectedType = withContext(Dispatchers.IO) { detectDocumentType(uri, target, rawFileName) }
                if (generation != documentGeneration) {
                    target.delete()
                    return@launch
                }
                if (detectedType == null) {
                    target.delete()
                    _loadState.value = PdfLoadState.Error(
                        "Desteklenmeyen dosya formatı. Yalnızca PDF, Word (.docx) ve Excel (.xlsx) dosyaları desteklenir."
                    )
                    return@launch
                }

                val displayTitle = rawFileName.substringBeforeLast('.', rawFileName).ifBlank { "Belge" }
                openFile(target, displayTitle, detectedType, generation)
            } catch (e: Exception) {
                try { tempFile?.delete() } catch (_: Exception) {}
                if (generation == documentGeneration) {
                    _loadState.value = PdfLoadState.Error(friendlyOpenError(e))
                }
            }
        }
    }

    private fun resolveFileNameWithExtension(uri: Uri): String {
        try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Belge"
    }

    /**
     * 3 katmanlı belge türü algılama:
     * 1) ContentResolver MIME türü
     * 2) Dosya uzantısı (.pdf, .docx, .xlsx)
     * 3) Dosya imzası (magic bytes: %PDF veya ZIP içindeki word/document.xml / xl/workbook.xml)
     */
    private fun detectDocumentType(uri: Uri, file: File, fileName: String): DocumentType? {
        val mime = try {
            getApplication<Application>().contentResolver.getType(uri)?.lowercase()
        } catch (_: Exception) {
            null
        }
        when (mime) {
            DocumentType.PDF.mimeType -> return DocumentType.PDF
            DocumentType.DOCX.mimeType -> return DocumentType.DOCX
            DocumentType.XLSX.mimeType -> return DocumentType.XLSX
        }

        val ext = fileName.substringAfterLast('.', "").lowercase()
        when (ext) {
            "pdf" -> return DocumentType.PDF
            "docx" -> return DocumentType.DOCX
            "xlsx" -> return DocumentType.XLSX
        }

        return try {
            val header = ByteArray(5)
            val read = file.inputStream().use { it.read(header) }
            if (read >= 4 && header[0] == '%'.code.toByte() && header[1] == 'P'.code.toByte() &&
                header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()
            ) {
                DocumentType.PDF
            } else if (read >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
                ZipFile(file).use { zip ->
                    when {
                        zip.getEntry("word/document.xml") != null -> DocumentType.DOCX
                        zip.getEntry("xl/workbook.xml") != null -> DocumentType.XLSX
                        else -> null
                    }
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun friendlyOpenError(e: Exception): String = when (e) {
        is SecurityException ->
            "Bu belge parola korumalı. Parola korumalı belgeler şu an desteklenmiyor."
        is java.io.FileNotFoundException ->
            "Dosya bulunamadı ya da erişim izni verilmedi."
        is java.io.IOException ->
            "Belge okunamadı. Dosya bozuk veya desteklenmeyen bir yapıda olabilir."
        else -> "Dosya açılamadı: ${e.message}"
    }

    private suspend fun openFile(
        sourceFile: File,
        title: String,
        docType: DocumentType,
        generation: Long
    ) {
        if (generation != documentGeneration) {
            withContext(Dispatchers.IO) { sourceFile.delete() }
            return
        }
        val context = getApplication<Application>()
        val workFile = File(context.cacheDir, "intact_work_${System.currentTimeMillis()}.${docType.extension}")
        withContext(Dispatchers.IO) {
            sourceFile.copyTo(workFile, overwrite = true)
            sourceFile.delete()
        }
        if (generation != documentGeneration) {
            workFile.delete()
            return
        }
        val previousWorkFile = workingFile
        workingFile = workFile
        documentTitle = title

        engine.close()
        pageBlocksCache.clear()
        clearUndoHistory()
        resetRevisions()
        _selectedBlock.value = null
        _pageBitmap.value = null
        _textBlocks.value = emptyList()
        _wordDocument.value = null
        _excelWorkbook.value = null
        _activeSheetIndex.value = 0
        _documentType.value = docType

        if (previousWorkFile != null && previousWorkFile != workFile) {
            withContext(Dispatchers.IO) { previousWorkFile.delete() }
        }

        when (docType) {
            DocumentType.PDF -> {
                val count = engine.openFile(workFile)
                if (generation != documentGeneration) return
                if (count == 0) {
                    _loadState.value = PdfLoadState.Error("PDF dosyası okunamadı veya sayfa bulunamadı.")
                    return
                }
                _totalPages.value = count
                _currentPage.value = 0
                _editCount.value = 0
                _viewerMode.value = ViewerMode.VIEW
                _loadState.value = PdfLoadState.Success(count, title, DocumentType.PDF)
                loadPageData(0, ++pageLoadGeneration, generation)
            }
            DocumentType.DOCX -> {
                val docData = withContext(Dispatchers.IO) { docxEngine.parse(workFile) }
                if (generation != documentGeneration) return
                val pageCount = docData.pages.size.coerceAtLeast(1)
                _wordDocument.value = docData
                _wordScrollRequest.value = null
                _totalPages.value = pageCount
                _currentPage.value = 0
                _editCount.value = 0
                _viewerMode.value = ViewerMode.VIEW
                _loadState.value = PdfLoadState.Success(pageCount, title, DocumentType.DOCX)
            }
            DocumentType.XLSX -> {
                val workbookData = withContext(Dispatchers.IO) { xlsxEngine.parse(workFile) }
                if (generation != documentGeneration) return
                _excelWorkbook.value = workbookData
                _activeSheetIndex.value = 0
                _totalPages.value = workbookData.sheets.size
                _currentPage.value = 0
                _editCount.value = 0
                _viewerMode.value = ViewerMode.VIEW
                _loadState.value = PdfLoadState.Success(workbookData.sheets.size, title, DocumentType.XLSX)
            }
        }
    }

    private suspend fun loadPageData(pageIndex: Int, requestGeneration: Long, documentGeneration: Long) {
        _isPageLoading.value = true
        try {
            val bitmap = engine.renderPage(pageIndex)
            val blocks = pageBlocksCache.getOrPut(pageIndex) {
                engine.extractTextBlocks(pageIndex, workingFile).toMutableList()
            }

            if (requestGeneration != pageLoadGeneration || documentGeneration != this.documentGeneration || pageIndex != _currentPage.value) {
                return
            }
            _pageBitmap.value = bitmap
            _textBlocks.value = blocks.toList()
            _isPageLoading.value = false
            if (bitmap == null) {
                postStatus("Sayfa görüntülenemedi (bellek yetersiz olabilir).")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestGeneration == pageLoadGeneration && documentGeneration == this.documentGeneration) {
                _isPageLoading.value = false
                postStatus("Sayfa yüklenemedi: ${e.message}")
            }
        }
    }

    fun setPage(pageIndex: Int) {
        if (pageIndex !in 0 until _totalPages.value) return
        when (_documentType.value) {
            DocumentType.PDF -> {
                _currentPage.value = pageIndex
                _selectedBlock.value = null
                val requestGeneration = ++pageLoadGeneration
                val activeDocumentGeneration = documentGeneration
                viewModelScope.launch {
                    loadPageData(pageIndex, requestGeneration, activeDocumentGeneration)
                }
            }
            DocumentType.DOCX -> {
                _currentPage.value = pageIndex
                _wordScrollRequest.value = pageIndex to System.nanoTime()
            }
            DocumentType.XLSX -> Unit
        }
    }

    fun onWordVisiblePageChanged(pageIndex: Int) {
        if (_documentType.value != DocumentType.DOCX) return
        if (pageIndex in 0 until _totalPages.value) {
            _currentPage.value = pageIndex
        }
    }

    fun selectBlock(block: PdfTextBlock?) {
        _selectedBlock.value = block
    }

    fun applyEdit(block: PdfTextBlock, newText: String, isRemoved: Boolean) {
        if (_isEditing.value || _isSaving.value) return
        if (!isRemoved && newText == block.text) {
            _selectedBlock.value = null
            return
        }

        val activeWorkFile = workingFile ?: return
        val pageIndex = block.pageIndex
        val activeDocumentGeneration = documentGeneration
        _isEditing.value = true

        viewModelScope.launch {
            if (!pageBlocksCache.containsKey(pageIndex)) {
                pageBlocksCache[pageIndex] = engine.extractTextBlocks(pageIndex, activeWorkFile).toMutableList()
            }

            val snapshot = pushUndoSnapshot(activeWorkFile)

            val currentList = pageBlocksCache[pageIndex] ?: mutableListOf()
            val listBeforeEdit = currentList.toList()
            if (isRemoved) {
                currentList.removeAll { it.id == block.id }
            } else {
                val idx = currentList.indexOfFirst { it.id == block.id }
                if (idx != -1) {
                    currentList[idx] = block.copy(text = newText)
                }
            }
            pageBlocksCache[pageIndex] = currentList
            if (_currentPage.value == pageIndex) {
                _textBlocks.value = currentList.toList()
            }
            _selectedBlock.value = null

            val op = TextEditOperation(
                targetBlock = block,
                newText = newText,
                isRemoved = isRemoved
            )

            val result = engine.applyEditLive(op, activeWorkFile)

            result.onSuccess {
                if (activeDocumentGeneration != documentGeneration) return@onSuccess
                markModified()
                val updatedBitmap = engine.renderPage(pageIndex)
                if (_currentPage.value == pageIndex) {
                    _pageBitmap.value = updatedBitmap
                }
                postStatus(if (isRemoved) "Metin silindi" else "Metin güncellendi", undoable = snapshot != null)
            }.onFailure { err ->
                if (activeDocumentGeneration != documentGeneration) return@onFailure
                discardSnapshot(snapshot)
                pageBlocksCache[pageIndex] = listBeforeEdit.toMutableList()
                if (_currentPage.value == pageIndex) {
                    _textBlocks.value = listBeforeEdit
                }
                postStatus("Düzenleme hatası: ${err.message}")
            }
            _isEditing.value = false
        }
    }

    fun addNewText(text: String, fontSize: Float = 14f, normX: Float = 0.25f, normY: Float = 0.5f) {
        if (_isEditing.value || _isSaving.value || text.isBlank()) return
        val activeWorkFile = workingFile ?: return
        val pageIndex = _currentPage.value
        val activeDocumentGeneration = documentGeneration
        _isEditing.value = true

        viewModelScope.launch {
            if (!pageBlocksCache.containsKey(pageIndex)) {
                pageBlocksCache[pageIndex] = engine.extractTextBlocks(pageIndex, activeWorkFile).toMutableList()
            }

            val snapshot = pushUndoSnapshot(activeWorkFile)

            val result = engine.addTextLive(
                targetFile = activeWorkFile,
                pageIndex = pageIndex,
                text = text.trim(),
                normX = normX,
                normY = normY,
                fontSize = fontSize
            )

            result.onSuccess { newBlock ->
                if (activeDocumentGeneration != documentGeneration) return@onSuccess
                val currentList = pageBlocksCache[pageIndex] ?: mutableListOf()
                currentList.add(newBlock)
                pageBlocksCache[pageIndex] = currentList
                _textBlocks.value = currentList.toList()
                markModified()

                val updatedBitmap = engine.renderPage(pageIndex)
                if (_currentPage.value == pageIndex) {
                    _pageBitmap.value = updatedBitmap
                }
                postStatus("Yeni metin eklendi", undoable = snapshot != null)
            }.onFailure { err ->
                if (activeDocumentGeneration != documentGeneration) return@onFailure
                discardSnapshot(snapshot)
                postStatus("Metin ekleme hatası: ${err.message}")
            }
            _isEditing.value = false
        }
    }

    fun moveTextBlock(block: PdfTextBlock, newNormX: Float, newNormY: Float) {
        if (_isEditing.value || _isSaving.value) return
        val activeWorkFile = workingFile ?: return
        val pageIndex = block.pageIndex
        val activeDocumentGeneration = documentGeneration
        _isEditing.value = true

        viewModelScope.launch {
            if (!pageBlocksCache.containsKey(pageIndex)) {
                pageBlocksCache[pageIndex] = engine.extractTextBlocks(pageIndex, activeWorkFile).toMutableList()
            }

            val snapshot = pushUndoSnapshot(activeWorkFile)

            val result = engine.moveTextBlockLive(
                targetFile = activeWorkFile,
                block = block,
                newNormX = newNormX,
                newNormY = newNormY
            )

            result.onSuccess { updatedBlock ->
                if (activeDocumentGeneration != documentGeneration) return@onSuccess
                val currentList = pageBlocksCache[pageIndex] ?: mutableListOf()
                val idx = currentList.indexOfFirst { it.id == block.id }
                if (idx != -1) {
                    currentList[idx] = updatedBlock
                }
                pageBlocksCache[pageIndex] = currentList
                if (_currentPage.value == pageIndex) {
                    _textBlocks.value = currentList.toList()
                }
                markModified()

                val updatedBitmap = engine.renderPage(pageIndex)
                if (_currentPage.value == pageIndex) {
                    _pageBitmap.value = updatedBitmap
                }
                postStatus("Metin taşındı", undoable = snapshot != null)
            }.onFailure { err ->
                if (activeDocumentGeneration != documentGeneration) return@onFailure
                discardSnapshot(snapshot)
                postStatus("Metin taşıma hatası: ${err.message}")
            }
            _isEditing.value = false
        }
    }

    fun addNewPage() {
        if (_isEditing.value || _isSaving.value || _isPageLoading.value) return
        val activeWorkFile = workingFile ?: return
        val activeDocumentGeneration = documentGeneration
        val insertAfter = _currentPage.value
        _isEditing.value = true

        viewModelScope.launch {
            val snapshot = pushUndoSnapshot(activeWorkFile)

            val result = engine.addNewPage(activeWorkFile, insertAfter)

            result.onSuccess { newTotalPages ->
                if (activeDocumentGeneration != documentGeneration) return@onSuccess
                _totalPages.value = newTotalPages
                markModified()

                val newCache = mutableMapOf<Int, MutableList<PdfTextBlock>>()
                pageBlocksCache.forEach { (p, list) ->
                    if (p <= insertAfter) {
                        newCache[p] = list
                    } else {
                        newCache[p + 1] = list
                    }
                }
                pageBlocksCache.clear()
                pageBlocksCache.putAll(newCache)

                val targetPage = (insertAfter + 1).coerceAtMost(newTotalPages - 1)
                _currentPage.value = targetPage
                _selectedBlock.value = null
                postStatus("Yeni sayfa eklendi", undoable = snapshot != null)
                loadPageData(targetPage, ++pageLoadGeneration, activeDocumentGeneration)
            }.onFailure { err ->
                if (activeDocumentGeneration != documentGeneration) return@onFailure
                discardSnapshot(snapshot)
                postStatus("Sayfa ekleme hatası: ${err.message}")
            }
            _isEditing.value = false
        }
    }

    // --- Word (.docx) Operations ---

    fun updateWordParagraph(bodyIndex: Int, newText: String) {
        runWordEdit("Paragraf güncellendi") { file ->
            docxEngine.updateParagraph(file, bodyIndex, newText)
        }
    }

    fun deleteWordElement(bodyIndex: Int) {
        runWordEdit("Öğe silindi") { file ->
            docxEngine.deleteElement(file, bodyIndex)
        }
    }

    fun addWordParagraph(text: String, insertAfterBodyIndex: Int? = null) {
        if (text.isBlank()) return
        runWordEdit("Yeni paragraf eklendi") { file ->
            docxEngine.addParagraph(file, text.trim(), insertAfterBodyIndex)
        }
    }

    fun addWordPageBreak() {
        runWordEdit("Yeni sayfa eklendi", scrollToLastPage = true) { file ->
            docxEngine.addPageBreak(file)
        }
    }

    fun updateWordTableCell(bodyIndex: Int, rowIndex: Int, colIndex: Int, newText: String) {
        runWordEdit("Tablo hücresi güncellendi") { file ->
            docxEngine.updateTableCell(file, bodyIndex, rowIndex, colIndex, newText)
        }
    }

    private fun runWordEdit(
        successMessage: String,
        scrollToLastPage: Boolean = false,
        action: (File) -> Result<WordDocumentData>
    ) {
        if (_isEditing.value || _isSaving.value) return
        val activeWorkFile = workingFile ?: return
        val activeGen = documentGeneration
        _isEditing.value = true

        viewModelScope.launch {
            val snapshot = pushUndoSnapshot(activeWorkFile)
            val result = withContext(Dispatchers.IO) { action(activeWorkFile) }
            result.onSuccess { updated ->
                if (activeGen != documentGeneration) return@onSuccess
                val pageCount = updated.pages.size.coerceAtLeast(1)
                _wordDocument.value = updated
                _totalPages.value = pageCount
                if (scrollToLastPage) {
                    val lastIdx = pageCount - 1
                    _currentPage.value = lastIdx
                    _wordScrollRequest.value = lastIdx to System.nanoTime()
                } else if (_currentPage.value >= pageCount) {
                    _currentPage.value = pageCount - 1
                }
                markModified()
                postStatus(successMessage, undoable = snapshot != null)
            }.onFailure { err ->
                if (activeGen != documentGeneration) return@onFailure
                discardSnapshot(snapshot)
                postStatus("Word düzenleme hatası: ${err.message}")
            }
            _isEditing.value = false
        }
    }

    // --- Excel (.xlsx) Operations ---

    fun selectExcelSheet(sheetIndex: Int) {
        val sheets = _excelWorkbook.value?.sheets ?: return
        if (sheetIndex in sheets.indices) {
            _activeSheetIndex.value = sheetIndex
            _currentPage.value = sheetIndex
        }
    }

    fun updateExcelCell(sheetIndex: Int, row: Int, col: Int, newValue: String) {
        runExcelEdit("Hücre güncellendi") { file ->
            xlsxEngine.updateCell(file, sheetIndex, row, col, newValue)
        }
    }

    fun addExcelRow(sheetIndex: Int, afterRow: Int) {
        runExcelEdit("Satır eklendi") { file ->
            xlsxEngine.addRow(file, sheetIndex, afterRow)
        }
    }

    fun deleteExcelRow(sheetIndex: Int, row: Int) {
        runExcelEdit("Satır silindi") { file ->
            xlsxEngine.deleteRow(file, sheetIndex, row)
        }
    }

    fun addExcelColumn(sheetIndex: Int, afterCol: Int) {
        runExcelEdit("Sütun eklendi") { file ->
            xlsxEngine.addColumn(file, sheetIndex, afterCol)
        }
    }

    fun deleteExcelColumn(sheetIndex: Int, col: Int) {
        runExcelEdit("Sütun silindi") { file ->
            xlsxEngine.deleteColumn(file, sheetIndex, col)
        }
    }

    private fun runExcelEdit(successMessage: String, action: (File) -> Result<ExcelWorkbookData>) {
        if (_isEditing.value || _isSaving.value) return
        val activeWorkFile = workingFile ?: return
        val activeGen = documentGeneration
        _isEditing.value = true

        viewModelScope.launch {
            val snapshot = pushUndoSnapshot(activeWorkFile)
            val result = withContext(Dispatchers.IO) { action(activeWorkFile) }
            result.onSuccess { updated ->
                if (activeGen != documentGeneration) return@onSuccess
                _excelWorkbook.value = updated
                _totalPages.value = updated.sheets.size
                if (_activeSheetIndex.value >= updated.sheets.size) {
                    _activeSheetIndex.value = (updated.sheets.size - 1).coerceAtLeast(0)
                }
                _currentPage.value = _activeSheetIndex.value
                markModified()
                postStatus(successMessage, undoable = snapshot != null)
            }.onFailure { err ->
                if (activeGen != documentGeneration) return@onFailure
                discardSnapshot(snapshot)
                postStatus("Excel düzenleme hatası: ${err.message}")
            }
            _isEditing.value = false
        }
    }

    // --- Undo / History ---

    fun undoLastEdit() {
        if (_isEditing.value || _isSaving.value) return
        val activeWorkFile = workingFile ?: return
        if (undoHistory.isEmpty()) return
        val activeDocumentGeneration = documentGeneration
        val docType = _documentType.value
        _isEditing.value = true

        viewModelScope.launch {
            try {
                val lastSnapshot = undoHistory.lastOrNull() ?: return@launch

                when (docType) {
                    DocumentType.PDF -> {
                        val restoredPages = engine.restoreFile(lastSnapshot.file, activeWorkFile)
                        if (activeDocumentGeneration != documentGeneration) {
                            cleanupOrphanedSnapshot(lastSnapshot, activeWorkFile)
                            return@launch
                        }
                        consumeSnapshot(lastSnapshot)
                        _totalPages.value = restoredPages
                        if (_currentPage.value >= restoredPages) {
                            _currentPage.value = (restoredPages - 1).coerceAtLeast(0)
                        }
                        _selectedBlock.value = null
                        pageBlocksCache.clear()
                        lastSnapshot.blocksMap.forEach { (page, list) ->
                            pageBlocksCache[page] = list.map { it.copy() }.toMutableList()
                        }
                        val page = _currentPage.value
                        val cached = pageBlocksCache[page]
                        if (cached != null) {
                            _textBlocks.value = cached.toList()
                            _pageBitmap.value = engine.renderPage(page)
                        } else {
                            _textBlocks.value = emptyList()
                            loadPageData(page, ++pageLoadGeneration, activeDocumentGeneration)
                        }
                    }
                    DocumentType.DOCX -> {
                        val restoredDoc = withContext(Dispatchers.IO) {
                            lastSnapshot.file.copyTo(activeWorkFile, overwrite = true)
                            docxEngine.parse(activeWorkFile)
                        }
                        if (activeDocumentGeneration != documentGeneration) {
                            cleanupOrphanedSnapshot(lastSnapshot, activeWorkFile)
                            return@launch
                        }
                        consumeSnapshot(lastSnapshot)
                        val pageCount = restoredDoc.pages.size.coerceAtLeast(1)
                        _wordDocument.value = restoredDoc
                        _totalPages.value = pageCount
                        if (_currentPage.value >= pageCount) {
                            _currentPage.value = pageCount - 1
                        }
                    }
                    DocumentType.XLSX -> {
                        val restoredWb = withContext(Dispatchers.IO) {
                            lastSnapshot.file.copyTo(activeWorkFile, overwrite = true)
                            xlsxEngine.parse(activeWorkFile)
                        }
                        if (activeDocumentGeneration != documentGeneration) {
                            cleanupOrphanedSnapshot(lastSnapshot, activeWorkFile)
                            return@launch
                        }
                        consumeSnapshot(lastSnapshot)
                        _excelWorkbook.value = restoredWb
                        _totalPages.value = restoredWb.sheets.size
                        val restoredSheet = lastSnapshot.activeSheetIndex.coerceIn(0, (restoredWb.sheets.size - 1).coerceAtLeast(0))
                        _activeSheetIndex.value = restoredSheet
                        _currentPage.value = restoredSheet
                    }
                }
                postStatus("Geri alındı")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                postStatus("Geri alma hatası: ${e.message}")
            } finally {
                _isEditing.value = false
            }
        }
    }

    private suspend fun cleanupOrphanedSnapshot(snapshot: UndoSnapshot, workFile: File) {
        withContext(Dispatchers.IO) {
            if (workingFile != workFile) workFile.delete()
            snapshot.file.delete()
        }
    }

    private suspend fun consumeSnapshot(snapshot: UndoSnapshot) {
        undoHistory.removeAll { it === snapshot }
        withContext(Dispatchers.IO) { snapshot.file.delete() }
        _canUndo.value = undoHistory.isNotEmpty()
        _editCount.value = (_editCount.value - 1).coerceAtLeast(0)
        _revision.value = snapshot.revision
    }

    private suspend fun pushUndoSnapshot(currentFile: File): UndoSnapshot? {
        val ext = _documentType.value.extension
        val snapshotFile = File(getApplication<Application>().cacheDir, "undo_${System.nanoTime()}.$ext")
        val copied = withContext(Dispatchers.IO) {
            try {
                currentFile.copyTo(snapshotFile, overwrite = true)
                true
            } catch (_: Exception) {
                try { snapshotFile.delete() } catch (_: Exception) {}
                false
            }
        }
        if (!copied) return null

        val blocksCopy = pageBlocksCache.mapValues { (_, list) ->
            list.map { it.copy() }
        }
        val snapshot = UndoSnapshot(
            file = snapshotFile,
            blocksMap = blocksCopy,
            revision = _revision.value,
            activeSheetIndex = _activeSheetIndex.value
        )
        undoHistory.add(snapshot)
        _canUndo.value = true

        while (undoHistory.size > MAX_UNDO_STEPS ||
            (undoHistory.size > 1 && undoHistory.sumOf { it.file.length() } > MAX_UNDO_BYTES)
        ) {
            val oldest = undoHistory.removeAt(0)
            try { oldest.file.delete() } catch (_: Exception) {}
        }
        return snapshot
    }

    private fun discardSnapshot(snapshot: UndoSnapshot?) {
        if (snapshot == null) return
        if (undoHistory.removeAll { it === snapshot }) {
            try { snapshot.file.delete() } catch (_: Exception) {}
        }
        _canUndo.value = undoHistory.isNotEmpty()
    }

    private fun clearUndoHistory() {
        for (s in undoHistory) {
            try { s.file.delete() } catch (_: Exception) {}
        }
        undoHistory.clear()
        _canUndo.value = false
    }

    fun isDocumentOpen(): Boolean = _loadState.value is PdfLoadState.Success

    fun closeDocument() {
        documentGeneration++
        pageLoadGeneration++
        engine.close()
        clearUndoHistory()
        pageBlocksCache.clear()
        workingFile?.let { try { it.delete() } catch (_: Exception) {} }
        workingFile = null
        documentTitle = ""
        resetRevisions()
        _pageBitmap.value = null
        _textBlocks.value = emptyList()
        _selectedBlock.value = null
        _wordDocument.value = null
        _wordScrollRequest.value = null
        _excelWorkbook.value = null
        _activeSheetIndex.value = 0
        _documentType.value = DocumentType.PDF
        _totalPages.value = 0
        _currentPage.value = 0
        _editCount.value = 0
        _isPageLoading.value = false
        _isEditing.value = false
        _viewerMode.value = ViewerMode.VIEW
        _loadState.value = PdfLoadState.Idle
    }

    /** Kaydetme/paylaşma için önerilen dosya adı: "<belge adı>_duzenlendi.<uzantı>". */
    fun suggestedFileName(): String {
        val base = documentTitle
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(80)
            .ifBlank { "Belge" }
        return "${base}_duzenlendi.${_documentType.value.extension}"
    }

    fun saveEditsToDestination(destination: Uri, onSuccess: () -> Unit) {
        val activeWorkFile = workingFile ?: return
        if (_isEditing.value || _isSaving.value) {
            postStatus("Bir işlem sürüyor, lütfen bekleyin.")
            return
        }
        val revisionAtSave = _revision.value
        val docDisplayName = _documentType.value.displayName

        viewModelScope.launch {
            _isSaving.value = true
            var success = false
            withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openOutputStream(destination)?.use { output ->
                        activeWorkFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Kaydedilecek konum açılamadı.")
                    success = true
                } catch (e: Exception) {
                    postStatus("Dosya kaydetme hatası: ${e.message}")
                }
            }
            _isSaving.value = false
            if (success) {
                _savedRevision.value = revisionAtSave
                postStatus("$docDisplayName dosyası başarıyla kaydedildi!")
                onSuccess()
            }
        }
    }

    fun getWorkingFileForSharing(): File? = workingFile

    fun prepareShareFile(onReady: (File?) -> Unit) {
        val source = workingFile
        if (source == null) {
            onReady(null)
            return
        }
        if (_isEditing.value || _isSaving.value) {
            postStatus("Bir işlem sürüyor, lütfen bekleyin.")
            return
        }
        viewModelScope.launch {
            val shared = withContext(Dispatchers.IO) {
                try {
                    val dir = File(getApplication<Application>().cacheDir, "share")
                    dir.mkdirs()
                    dir.listFiles()?.forEach { it.delete() }
                    val target = File(dir, suggestedFileName())
                    source.copyTo(target, overwrite = true)
                    target
                } catch (_: Exception) {
                    null
                }
            }
            onReady(shared)
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        closeDocument()
    }
}
