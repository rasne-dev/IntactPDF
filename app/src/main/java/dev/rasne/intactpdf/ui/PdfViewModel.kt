package dev.rasne.intactpdf.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rasne.intactpdf.core.PdfEngine
import dev.rasne.intactpdf.model.PdfLoadState
import dev.rasne.intactpdf.model.PdfTextBlock
import dev.rasne.intactpdf.model.TextEditOperation
import dev.rasne.intactpdf.model.ViewerMode
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

private data class UndoSnapshot(
    val file: File,
    val blocksMap: Map<Int, List<PdfTextBlock>>,
    /** Bu anlık görüntü alındığında belgenin revizyon numarası (geri alınca buna dönülür). */
    val revision: Long = 0L
)

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val MAX_UNDO_STEPS = 20
        /** Geri alma anlık görüntülerinin toplam disk bütçesi. Büyük PDF'lerde önbelleğin şişmesini önler. */
        const val MAX_UNDO_BYTES = 256L * 1024L * 1024L
    }

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
     * (Salt düzenleme sayısına bakmak "düzenle, kaydet, geri al, başka düzenle" gibi durumlarda yanılır.)
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
                openFile(sampleFile, "Örnek Belge", generation)
            } catch (e: Exception) {
                _loadState.value = PdfLoadState.Error("Örnek belge oluşturulamadı: ${e.message}")
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
                val target = File(context.cacheDir, "opened_doc_${System.currentTimeMillis()}.pdf")
                tempFile = target

                // Kopyalama ana iş parçacığında yapılırsa büyük dosyalarda / yavaş sağlayıcılarda (ör. bulut) ANR olur
                val copiedBytes = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: return@withContext -1L
                    input.use { i ->
                        FileOutputStream(target).use { o ->
                            i.copyTo(o)
                        }
                    }
                }

                if (generation != documentGeneration) {
                    // Kullanıcı bu arada başka bir belge açtı ya da belgeyi kapattı
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
                    _loadState.value = PdfLoadState.Error("Dosya boş. Lütfen geçerli bir PDF seçin.")
                    return@launch
                }

                val displayName = withContext(Dispatchers.IO) { resolveDisplayName(uri) }
                openFile(target, displayName, generation)
            } catch (e: Exception) {
                try { tempFile?.delete() } catch (_: Exception) {}
                if (generation == documentGeneration) {
                    _loadState.value = PdfLoadState.Error(friendlyOpenError(e))
                }
            }
        }
    }

    /** Belge adını sağlayıcıdan alır (content:// URI'lerinin son parçası çoğu zaman anlamsız bir sayıdır). */
    private fun resolveDisplayName(uri: Uri): String {
        try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) {
                                return name.substringBeforeLast('.', name)
                            }
                        }
                    }
                }
        } catch (_: Exception) {
            // Sağlayıcı sorguyu desteklemiyorsa aşağıdaki yedek yönteme düş
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Belge"
    }

    private fun friendlyOpenError(e: Exception): String = when (e) {
        is SecurityException ->
            "Bu PDF parola korumalı. Parola korumalı belgeler şu an desteklenmiyor."
        is java.io.FileNotFoundException ->
            "Dosya bulunamadı ya da erişim izni verilmedi."
        is java.io.IOException ->
            "PDF okunamadı. Dosya bozuk olabilir."
        else -> "Dosya açılamadı: ${e.message}"
    }

    private suspend fun openFile(sourceFile: File, title: String, generation: Long) {
        if (generation != documentGeneration) {
            withContext(Dispatchers.IO) { sourceFile.delete() }
            return
        }
        val context = getApplication<Application>()
        val workFile = File(context.cacheDir, "intact_work_${System.currentTimeMillis()}.pdf")
        withContext(Dispatchers.IO) {
            sourceFile.copyTo(workFile, overwrite = true)
            // Geçici kaynak kopya artık gereksiz; silinmezse her açılışta önbellekte iki kat yer kaplar
            sourceFile.delete()
        }
        if (generation != documentGeneration) {
            workFile.delete()
            return
        }
        val previousWorkFile = workingFile
        workingFile = workFile
        documentTitle = title

        pageBlocksCache.clear()
        clearUndoHistory()
        resetRevisions()
        _selectedBlock.value = null

        // Önceki belgenin çalışma kopyası artık gereksiz (Linux'ta açık dosya silinebilir; motor tutamacı yeniden açılırken bırakır)
        if (previousWorkFile != null && previousWorkFile != workFile) {
            withContext(Dispatchers.IO) { previousWorkFile.delete() }
        }

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
        _loadState.value = PdfLoadState.Success(count, title)
        loadPageData(0, ++pageLoadGeneration, generation)
    }

    private suspend fun loadPageData(pageIndex: Int, requestGeneration: Long, documentGeneration: Long) {
        _isPageLoading.value = true
        try {
            val bitmap = engine.renderPage(pageIndex)
            val blocks = pageBlocksCache.getOrPut(pageIndex) {
                engine.extractTextBlocks(pageIndex, workingFile).toMutableList()
            }

            // Rendering is asynchronous. Ignore a result once the user has moved on.
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
        if (pageIndex in 0 until _totalPages.value) {
            _currentPage.value = pageIndex
            _selectedBlock.value = null
            val requestGeneration = ++pageLoadGeneration
            val activeDocumentGeneration = documentGeneration
            viewModelScope.launch {
                loadPageData(pageIndex, requestGeneration, activeDocumentGeneration)
            }
        }
    }

    fun selectBlock(block: PdfTextBlock?) {
        _selectedBlock.value = block
    }

    fun applyEdit(block: PdfTextBlock, newText: String, isRemoved: Boolean) {
        if (_isEditing.value || _isSaving.value) return
        // Skip if nothing changed
        if (!isRemoved && newText == block.text) {
            _selectedBlock.value = null
            return
        }

        val activeWorkFile = workingFile ?: return
        val pageIndex = block.pageIndex
        val activeDocumentGeneration = documentGeneration
        _isEditing.value = true

        viewModelScope.launch {
            // Ensure the page blocks are loaded in cache before snapshotting
            if (!pageBlocksCache.containsKey(pageIndex)) {
                pageBlocksCache[pageIndex] = engine.extractTextBlocks(pageIndex, activeWorkFile).toMutableList()
            }

            // Push snapshot for undo
            val snapshot = pushUndoSnapshot(activeWorkFile)

            // Update blocks cache immediately so the bounding box disappears/updates instantly
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
                // Re-render ONLY the bitmap, keeping the updated blocks list
                val updatedBitmap = engine.renderPage(pageIndex)
                if (_currentPage.value == pageIndex) {
                    _pageBitmap.value = updatedBitmap
                }
                postStatus(if (isRemoved) "Metin silindi" else "Metin güncellendi", undoable = snapshot != null)
            }.onFailure { err ->
                if (activeDocumentGeneration != documentGeneration) return@onFailure
                // Yalnızca BU işlemin anlık görüntüsünü geri al (önceki işlemlerin anlık görüntülerine dokunma)
                discardSnapshot(snapshot)
                // Önbellek iyimser biçimde güncellenmişti; dosya değişmediği için önceki listeye dön.
                // (Dosyadan yeniden çıkarmak, önceki düzenlemelerle gizlenen orijinal metinleri de geri getirirdi.)
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

                // Shift any cached pages after the inserted index
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

                // Navigate to the newly inserted page
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

    fun undoLastEdit() {
        // Devam eden bir düzenleme/kaydetme varken geri alma dosyayı ortadan bozabilir
        if (_isEditing.value || _isSaving.value) return
        val activeWorkFile = workingFile ?: return
        if (undoHistory.isEmpty()) return
        val activeDocumentGeneration = documentGeneration
        _isEditing.value = true

        viewModelScope.launch {
            try {
                // Anlık görüntü yalnızca geri yükleme BAŞARILI olursa tüketilir
                val lastSnapshot = undoHistory.lastOrNull() ?: return@launch
                val restoredPages = engine.restoreFile(lastSnapshot.file, activeWorkFile)
                if (activeDocumentGeneration != documentGeneration) {
                    // Geri yükleme sürerken belge kapatıldı ya da değiştirildi: artık sahipsiz dosyaları temizle
                    withContext(Dispatchers.IO) {
                        if (workingFile != activeWorkFile) activeWorkFile.delete()
                        lastSnapshot.file.delete()
                    }
                    return@launch
                }

                undoHistory.removeAll { it === lastSnapshot }
                withContext(Dispatchers.IO) { lastSnapshot.file.delete() }
                _canUndo.value = undoHistory.isNotEmpty()
                _editCount.value = (_editCount.value - 1).coerceAtLeast(0)

                _totalPages.value = restoredPages
                if (_currentPage.value >= restoredPages) {
                    _currentPage.value = (restoredPages - 1).coerceAtLeast(0)
                }
                _revision.value = lastSnapshot.revision
                _selectedBlock.value = null

                // Restore blocks cache
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
                    // Anlık görüntü alındığında bu sayfanın metinleri henüz önbellekte değildi.
                    // Boş bırakmak düzenleme modunda kutuların kaybolmasına yol açardı; yeniden çıkar.
                    _textBlocks.value = emptyList()
                    loadPageData(page, ++pageLoadGeneration, activeDocumentGeneration)
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

    /**
     * Mevcut dosyanın anlık görüntüsünü alır (kopyalama IO iş parçacığında yapılır; büyük PDF'lerde
     * ana iş parçacığını dondurmaz). Başarısız olursa null döner.
     */
    private suspend fun pushUndoSnapshot(currentFile: File): UndoSnapshot? {
        val snapshotFile = File(getApplication<Application>().cacheDir, "undo_${System.nanoTime()}.pdf")
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
        val snapshot = UndoSnapshot(snapshotFile, blocksCopy, _revision.value)
        undoHistory.add(snapshot)
        _canUndo.value = true

        // Adım sayısını ve toplam disk kullanımını sınırla
        while (undoHistory.size > MAX_UNDO_STEPS ||
            (undoHistory.size > 1 && undoHistory.sumOf { it.file.length() } > MAX_UNDO_BYTES)
        ) {
            val oldest = undoHistory.removeAt(0)
            try { oldest.file.delete() } catch (_: Exception) {}
        }
        return snapshot
    }

    /** Başarısız bir işlemin anlık görüntüsünü (ve yalnızca onu) geçmişten çıkarıp siler. */
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

    /** Returns true if a document is currently open (for back press handling) */
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
        _totalPages.value = 0
        _currentPage.value = 0
        _editCount.value = 0
        _isPageLoading.value = false
        _isEditing.value = false
        _viewerMode.value = ViewerMode.VIEW
        _loadState.value = PdfLoadState.Idle
    }

    /** Kaydetme/paylaşma için önerilen dosya adı: "<belge adı>_duzenlendi.pdf". */
    fun suggestedFileName(): String {
        val base = documentTitle
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(80)
            .ifBlank { "Belge" }
        return "${base}_duzenlendi.pdf"
    }

    fun saveEditsToDestination(destination: Uri, onSuccess: () -> Unit) {
        val activeWorkFile = workingFile ?: return
        // Düzenleme sürerken dosya yazılıyor olabilir; yarım yazılmış bir PDF kaydedilmemeli
        if (_isEditing.value || _isSaving.value) {
            postStatus("Bir işlem sürüyor, lütfen bekleyin.")
            return
        }
        val revisionAtSave = _revision.value

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
                postStatus("PDF başarıyla kaydedildi!")
                onSuccess()
            }
        }
    }

    fun getWorkingFileForSharing(): File? {
        return workingFile
    }

    /**
     * Paylaşım için çalışma kopyasından anlamlı adlı bir kopya üretir (IO iş parçacığında) ve
     * hazır olunca ana iş parçacığında bildirir. Böylece alıcı uygulama "intact_work_1727...pdf"
     * yerine "<belge adı>_duzenlendi.pdf" görür.
     */
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
