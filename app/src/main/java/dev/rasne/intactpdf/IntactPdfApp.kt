package dev.rasne.intactpdf

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

class IntactPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize PDFBox Android resource loader for offline font & CMAP management
        PDFBoxResourceLoader.init(applicationContext)
        cleanStaleCacheFiles()
    }

    /**
     * Uygulama işlemi ölünce (arka planda sistem tarafından sonlandırma, çökme vb.) çalışma kopyaları,
     * geri alma anlık görüntüleri ve paylaşım kopyaları önbellekte kalıyordu. Açılışta henüz hiçbir
     * belge açık olmadığı için bunların hepsi güvenle silinebilir. Yalnızca uygulamanın kendi
     * ürettiği geçici dosyalara dokunulur; kullanıcının belgelerine asla.
     */
    private fun cleanStaleCacheFiles() {
        Thread {
            try {
                // Yeni açılan bir belgenin dosyasını yanlışlıkla silmemek için yalnızca 1 dakikadan eski
                // dosyalar silinir (önceki oturumdan kalanlar her zaman bundan eskidir).
                val cutoff = System.currentTimeMillis() - 60_000L
                val prefixes = listOf("opened_doc_", "sample_source_", "intact_work_", "undo_")
                cacheDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff && prefixes.any { f.name.startsWith(it) }) {
                        f.delete()
                    }
                }
                File(cacheDir, "share").listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) f.delete()
                }
            } catch (_: Exception) {
                // Temizlik en iyi çaba esaslıdır; başarısız olması uygulamayı etkilememeli
            }
        }.start()
    }
}
