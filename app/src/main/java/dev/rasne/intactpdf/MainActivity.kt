package dev.rasne.intactpdf

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import dev.rasne.intactpdf.ui.PdfViewModel
import dev.rasne.intactpdf.ui.PdfViewerScreen

class MainActivity : ComponentActivity() {

    private val viewModel: PdfViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Only handle intent on fresh launch, not on config change recreation
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colorScheme) {
                PdfViewerScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri -> viewModel.openFromUri(uri) }
            }
            Intent.ACTION_SEND -> {
                androidx.core.content.IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    android.net.Uri::class.java
                )?.let { uri ->
                    viewModel.openFromUri(uri)
                }
            }
        }
    }
}
