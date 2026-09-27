package dev.rasne.intactpdf

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class IntactPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize PDFBox Android resource loader for offline font & CMAP management
        PDFBoxResourceLoader.init(applicationContext)
    }
}
