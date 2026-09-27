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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = PdfEngine(application)

    private val _loadState = MutableStateFlow<PdfLoadState>(PdfLoadState.Idle)
    val loadState: StateFlow<PdfLoadState> = _loadState.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _pageBitmap = MutableStateFlow<Bitmap?>(null)
    val pageBitmap: StateFlow<Bitmap?> = _pageBitmap.asStateFlow()

    private val _textBlocks = MutableStateFlow<List<PdfTextBlock>>(emptyList())
    val textBlocks: StateFlow<List<PdfTextBlock>> = _textBlocks.asStateFlow()

    private val _pendingEdits = MutableStateFlow<Map<String, TextEditOperation>>(emptyMap())
    val pendingEdits: StateFlow<Map<String, TextEditOperation>> = _pendingEdits.asStateFlow()

    private val _selectedBlock = MutableStateFlow<PdfTextBlock?>(null)
    val selectedBlock: StateFlow<PdfTextBlock?> = _selectedBlock.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var activePdfFile: File? = null

    init {
        // Load default sample PDF so user can test immediately
        loadSamplePdf()
    }

    fun loadSamplePdf() {
        viewModelScope.launch {
            _loadState.value = PdfLoadState.Loading
            try {
                val sampleFile = File(getApplication<Application>().cacheDir, "sample_intact.pdf")
                engine.createSamplePdf(sampleFile)
                openFile(sampleFile, "Sample Document")
            } catch (e: Exception) {
                _loadState.value = PdfLoadState.Error("Sample document could not be created: ${e.message}")
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
                openFile(tempFile, uri.lastPathSegment ?: "Document.pdf")
            } catch (e: Exception) {
                _loadState.value = PdfLoadState.Error("Failed to open file: ${e.message}")
            }
        }
    }

    private suspend fun openFile(file: File, title: String) {
        activePdfFile = file
        val count = engine.openFile(file)
        _totalPages.value = count
        _currentPage.value = 0
        _pendingEdits.value = emptyMap()
        _loadState.value = PdfLoadState.Success(count, title)
        loadPageData(0)
    }

    private suspend fun loadPageData(pageIndex: Int) {
        val bitmap = engine.renderPage(pageIndex)
        _pageBitmap.value = bitmap
        val blocks = engine.extractTextBlocks(pageIndex)
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
        val op = TextEditOperation(
            targetBlock = block,
            newText = newText,
            isRemoved = isRemoved
        )
        _pendingEdits.value = _pendingEdits.value + (block.id to op)
        _selectedBlock.value = null
        _statusMessage.value = if (isRemoved) "Metin kaldırıldı (Düzen korundu)" else "Metin güncellendi (Düzen korundu)"
    }

    fun saveEdits(onSaved: (File) -> Unit) {
        if (activePdfFile == null) return
        if (_pendingEdits.value.isEmpty()) {
            _statusMessage.value = "Henüz bir değişiklik yapılmadı."
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            val outFile = File(getApplication<Application>().cacheDir, "IntactPDF_Export_${System.currentTimeMillis()}.pdf")
            val result = engine.applyEdits(_pendingEdits.value.values.toList(), outFile)
            _isSaving.value = false

            result.onSuccess { savedFile ->
                _statusMessage.value = "Değişiklikler kaydedildi! Sayfa yapısı korundu."
                // Reopen the saved file to show updated state
                openFile(savedFile, savedFile.name)
                onSaved(savedFile)
            }.onFailure { err ->
                _statusMessage.value = "Kayıt hatası: ${err.message}"
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
