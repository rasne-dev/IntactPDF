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
    private val undoHistory = mutableListOf<File>()

    fun setViewerMode(mode: ViewerMode) {
        _viewerMode.value = mode
    }

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
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val displayName = uri.lastPathSegment ?: "Belge.pdf"
                openFile(tempFile, displayName)
            } catch (e: Exception) {
                _loadState.value = PdfLoadState.Error("Dosya açılamadı: ${e.message}")
            }
        }
    }

    private suspend fun openFile(sourceFile: File, title: String) {
        val context = getApplication<Application>()
        // Create an isolated working copy for editing
        val workFile = File(context.cacheDir, "intact_work_${System.currentTimeMillis()}.pdf")
        sourceFile.copyTo(workFile, overwrite = true)
        workingFile = workFile

        // Clear undo history
        clearUndoHistory()

        val count = engine.openFile(workFile)
        _totalPages.value = count
        _currentPage.value = 0
        _editCount.value = 0
        _viewerMode.value = ViewerMode.VIEW // Always start in clean View mode!
        _loadState.value = PdfLoadState.Success(count, title)
        loadPageData(0)
    }

    private suspend fun loadPageData(pageIndex: Int) {
        val bitmap = engine.renderPage(pageIndex)
        _pageBitmap.value = bitmap
        val blocks = engine.extractTextBlocks(pageIndex, workingFile)
        _textBlocks.value = blocks
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
        val activeWorkFile = workingFile ?: return

        viewModelScope.launch {
            // Push snapshot to undo history
            pushUndoSnapshot(activeWorkFile)

            val op = TextEditOperation(
                targetBlock = block,
                newText = newText,
                isRemoved = isRemoved
            )

            val result = engine.applyEditLive(op, activeWorkFile)
            _selectedBlock.value = null

            result.onSuccess {
                _editCount.value = _editCount.value + 1
                _canUndo.value = undoHistory.isNotEmpty()
                loadPageData(_currentPage.value)
                _statusMessage.value = if (isRemoved) "Metin silindi" else "Metin güncellendi"
            }.onFailure { err ->
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
                lastSnapshot.copyTo(activeWorkFile, overwrite = true)
                lastSnapshot.delete()
                engine.openFile(activeWorkFile)
            }

            loadPageData(_currentPage.value)
            _statusMessage.value = "Son işlem geri alındı"
        }
    }

    private fun pushUndoSnapshot(currentFile: File) {
        try {
            val snapshot = File(getApplication<Application>().cacheDir, "undo_${System.currentTimeMillis()}.pdf")
            currentFile.copyTo(snapshot, overwrite = true)
            undoHistory.add(snapshot)
            _canUndo.value = true
        } catch (_: Exception) {}
    }

    private fun clearUndoHistory() {
        for (f in undoHistory) {
            try { f.delete() } catch (_: Exception) {}
        }
        undoHistory.clear()
        _canUndo.value = false
    }

    fun closeDocument() {
        engine.close()
        clearUndoHistory()
        workingFile?.let { try { it.delete() } catch (_: Exception) {} }
        workingFile = null
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
            withContext(Dispatchers.IO) {
                try {
                    activeWorkFile.inputStream().use { input ->
                        input.copyTo(outputStream)
                    }
                } catch (e: Exception) {
                    _statusMessage.value = "Dosya kaydetme hatası: ${e.message}"
                }
            }
            _isSaving.value = false
            _statusMessage.value = "PDF başarıyla kaydedildi!"
            onSuccess()
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
