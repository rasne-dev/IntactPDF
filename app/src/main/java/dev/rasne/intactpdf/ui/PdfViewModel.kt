package dev.rasne.intactpdf.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rasne.intactpdf.core.PdfEngine
import dev.rasne.intactpdf.model.PdfLoadState
import dev.rasne.intactpdf.model.PdfTextBlock
import dev.rasne.intactpdf.model.TextEditOperation
import dev.rasne.intactpdf.model.ViewerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private data class UndoSnapshot(
    val file: File,
    val blocksMap: Map<Int, List<PdfTextBlock>>
)

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = PdfEngine(application)

    private val _loadState = MutableStateFlow<PdfLoadState>(PdfLoadState.Idle)
    val loadState: StateFlow<PdfLoadState> = _loadState.asStateFlow()

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

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _editCount = MutableStateFlow(0)
    val editCount: StateFlow<Int> = _editCount.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var workingFile: File? = null
    private var documentTitle: String = ""
    private val undoHistory = mutableListOf<UndoSnapshot>()
    private val pageBlocksCache = mutableMapOf<Int, MutableList<PdfTextBlock>>()

    fun toggleViewerMode() {
        _viewerMode.value = if (_viewerMode.value == ViewerMode.VIEW) ViewerMode.EDIT else ViewerMode.VIEW
    }

    fun loadSamplePdf() {
        viewModelScope.launch {
            _loadState.value = PdfLoadState.Loading
            try {
                val sampleFile = File(getApplication<Application>().cacheDir, "sample_source_${System.currentTimeMillis()}.pdf")
                engine.createSamplePdf(sampleFile)
                openFile(sampleFile, "Örnek Belge")
            } catch (e: Exception) {
                _loadState.value = PdfLoadState.Error("Örnek belge oluşturulamadı: ${e.message}")
            }
        }
    }

    fun openFromUri(uri: Uri) {
        viewModelScope.launch {
            _loadState.value = PdfLoadState.Loading
            try {
                val context = getApplication<Application>()
                val tempFile = File(context.cacheDir, "opened_doc_${System.currentTimeMillis()}.pdf")

                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _loadState.value = PdfLoadState.Error("Dosya okunamadı. Lütfen farklı bir dosya deneyin.")
                    return@launch
                }

                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (tempFile.length() == 0L) {
                    tempFile.delete()
                    _loadState.value = PdfLoadState.Error("Dosya boş. Lütfen geçerli bir PDF seçin.")
                    return@launch
                }

                val displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Belge"
                openFile(tempFile, displayName)
            } catch (e: Exception) {
                _loadState.value = PdfLoadState.Error("Dosya açılamadı: ${e.message}")
            }
        }
    }

    private suspend fun openFile(sourceFile: File, title: String) {
        val context = getApplication<Application>()
        val workFile = File(context.cacheDir, "intact_work_${System.currentTimeMillis()}.pdf")
        sourceFile.copyTo(workFile, overwrite = true)
        workingFile = workFile
        documentTitle = title

        pageBlocksCache.clear()
        clearUndoHistory()

        val count = engine.openFile(workFile)
        if (count == 0) {
            _loadState.value = PdfLoadState.Error("PDF dosyası okunamadı veya sayfa bulunamadı.")
            return
        }

        _totalPages.value = count
        _currentPage.value = 0
        _editCount.value = 0
        _viewerMode.value = ViewerMode.VIEW
        _loadState.value = PdfLoadState.Success(count, title)
        loadPageData(0)
    }

    private suspend fun loadPageData(pageIndex: Int) {
        val bitmap = engine.renderPage(pageIndex)
        _pageBitmap.value = bitmap

        val blocks = pageBlocksCache.getOrPut(pageIndex) {
            engine.extractTextBlocks(pageIndex, workingFile).toMutableList()
        }
        _textBlocks.value = blocks.toList()
    }

    fun setPage(pageIndex: Int) {
        if (pageIndex in 0 until _totalPages.value) {
            _currentPage.value = pageIndex
            viewModelScope.launch {
                loadPageData(pageIndex)
            }
        }
    }

    fun selectBlock(block: PdfTextBlock?) {
        _selectedBlock.value = block
    }

    fun applyEdit(block: PdfTextBlock, newText: String, isRemoved: Boolean) {
        // Skip if nothing changed
        if (!isRemoved && newText == block.text) {
            _selectedBlock.value = null
            return
        }

        val activeWorkFile = workingFile ?: return
        val pageIndex = block.pageIndex

        viewModelScope.launch {
            // Ensure the page blocks are loaded in cache before snapshotting
            if (!pageBlocksCache.containsKey(pageIndex)) {
                pageBlocksCache[pageIndex] = engine.extractTextBlocks(pageIndex, activeWorkFile).toMutableList()
            }

            // Push snapshot for undo
            pushUndoSnapshot(activeWorkFile)

            // Update blocks cache immediately so the bounding box disappears/updates instantly
            val currentList = pageBlocksCache[pageIndex] ?: mutableListOf()
            if (isRemoved) {
                currentList.removeAll { it.id == block.id }
            } else {
                val idx = currentList.indexOfFirst { it.id == block.id }
                if (idx != -1) {
                    currentList[idx] = block.copy(text = newText)
                }
            }
            pageBlocksCache[pageIndex] = currentList
            _textBlocks.value = currentList.toList()
            _selectedBlock.value = null

            val op = TextEditOperation(
                targetBlock = block,
                newText = newText,
                isRemoved = isRemoved
            )

            val result = engine.applyEditLive(op, activeWorkFile)

            result.onSuccess {
                _editCount.value = _editCount.value + 1
                _canUndo.value = undoHistory.isNotEmpty()
                // Re-render ONLY the bitmap, keeping the updated blocks list
                val updatedBitmap = engine.renderPage(pageIndex)
                _pageBitmap.value = updatedBitmap
                _statusMessage.value = if (isRemoved) "Metin silindi" else "Metin güncellendi"
            }.onFailure { err ->
                // Roll back snapshot on failure
                if (undoHistory.isNotEmpty()) {
                    val failedSnapshot = undoHistory.removeAt(undoHistory.lastIndex)
                    try { failedSnapshot.file.delete() } catch (_: Exception) {}
                    _canUndo.value = undoHistory.isNotEmpty()
                    pageBlocksCache.remove(pageIndex)
                    loadPageData(pageIndex)
                }
                _statusMessage.value = "Düzenleme hatası: ${err.message}"
            }
        }
    }

    fun undoLastEdit() {
        val activeWorkFile = workingFile ?: return
        if (undoHistory.isEmpty()) return

        viewModelScope.launch {
            val lastSnapshot = undoHistory.removeAt(undoHistory.lastIndex)
            _canUndo.value = undoHistory.isNotEmpty()
            _editCount.value = (_editCount.value - 1).coerceAtLeast(0)

            withContext(Dispatchers.IO) {
                lastSnapshot.file.copyTo(activeWorkFile, overwrite = true)
                lastSnapshot.file.delete()
                engine.openFile(activeWorkFile)
            }

            // Restore blocks cache
            pageBlocksCache.clear()
            lastSnapshot.blocksMap.forEach { (page, list) ->
                pageBlocksCache[page] = list.map { it.copy() }.toMutableList()
            }

            _textBlocks.value = pageBlocksCache[_currentPage.value]?.toList() ?: emptyList()
            _pageBitmap.value = engine.renderPage(_currentPage.value)
            _statusMessage.value = "Geri alındı"
        }
    }

    private fun pushUndoSnapshot(currentFile: File) {
        try {
            val snapshot = File(getApplication<Application>().cacheDir, "undo_${System.currentTimeMillis()}.pdf")
            currentFile.copyTo(snapshot, overwrite = true)

            val blocksCopy = pageBlocksCache.mapValues { (_, list) ->
                list.map { it.copy() }
            }
            undoHistory.add(UndoSnapshot(snapshot, blocksCopy))
            _canUndo.value = true

            // Limit undo history to 20 steps to prevent excessive disk usage
            while (undoHistory.size > 20) {
                val oldest = undoHistory.removeAt(0)
                try { oldest.file.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun clearUndoHistory() {
        for (s in undoHistory) {
            try { s.file.delete() } catch (_: Exception) {}
        }
        undoHistory.clear()
        _canUndo.value = false
    }

    /** Returns true if a document is currently open (for back press handling) */
    fun isDocumentOpen(): Boolean = _loadState.value is PdfLoadState.Success

    fun closeDocument() {
        engine.close()
        clearUndoHistory()
        pageBlocksCache.clear()
        workingFile?.let { try { it.delete() } catch (_: Exception) {} }
        workingFile = null
        documentTitle = ""
        _pageBitmap.value = null
        _textBlocks.value = emptyList()
        _selectedBlock.value = null
        _totalPages.value = 0
        _currentPage.value = 0
        _editCount.value = 0
        _viewerMode.value = ViewerMode.VIEW
        _loadState.value = PdfLoadState.Idle
    }

    fun saveEditsToDestination(outputStream: OutputStream, onSuccess: () -> Unit) {
        val activeWorkFile = workingFile ?: return

        viewModelScope.launch {
            _isSaving.value = true
            var success = false
            withContext(Dispatchers.IO) {
                try {
                    activeWorkFile.inputStream().use { input ->
                        input.copyTo(outputStream)
                    }
                    success = true
                } catch (e: Exception) {
                    _statusMessage.value = "Dosya kaydetme hatası: ${e.message}"
                }
            }
            _isSaving.value = false
            if (success) {
                _statusMessage.value = "PDF başarıyla kaydedildi!"
                onSuccess()
            }
        }
    }

    fun getWorkingFileForSharing(): File? {
        return workingFile
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        closeDocument()
    }
}
