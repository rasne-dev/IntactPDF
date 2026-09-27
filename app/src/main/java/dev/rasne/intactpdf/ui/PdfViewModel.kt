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
import java.io.InputStream
import java.io.OutputStream

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

    // Notice: Application starts clean (Idle state). Does NOT open a file automatically!

    fun loadSamplePdf() {
        viewModelScope.launch {
            _loadState.value = PdfLoadState.Loading
            try {
                val sampleFile = File(getApplication<Application>().cacheDir, "sample_intact.pdf")
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
        _statusMessage.value = if (isRemoved) "Metin silindi" else "Metin güncellendi"
    }

    fun undoEdit(blockId: String) {
        _pendingEdits.value = _pendingEdits.value - blockId
        _statusMessage.value = "Değişiklik geri alındı"
    }

    fun closeDocument() {
        engine.close()
        activePdfFile = null
        _pageBitmap.value = null
        _textBlocks.value = emptyList()
        _pendingEdits.value = emptyMap()
        _selectedBlock.value = null
        _totalPages.value = 0
        _currentPage.value = 0
        _loadState.value = PdfLoadState.Idle
    }

    fun saveEditsToDestination(outputStream: OutputStream, onSuccess: () -> Unit) {
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
                try {
                    savedFile.inputStream().use { input ->
                        input.copyTo(outputStream)
                    }
                    _statusMessage.value = "PDF başarıyla kaydedildi!"
                    openFile(savedFile, savedFile.name)
                    onSuccess()
                } catch (e: Exception) {
                    _statusMessage.value = "Dosya yazma hatası: ${e.message}"
                }
            }.onFailure { err ->
                _statusMessage.value = "Kayıt hatası: ${err.message}"
            }
        }
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
                _statusMessage.value = "Değişiklikler kaydedildi!"
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
