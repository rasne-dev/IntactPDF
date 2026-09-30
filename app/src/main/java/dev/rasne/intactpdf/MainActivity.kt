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
import androidx.compose.ui.graphics.Color
import dev.rasne.intactpdf.ui.PdfViewModel
import dev.rasne.intactpdf.ui.PdfViewerScreen

/**
 * Marka renkleri (uygulama simgesi, mağaza görselleri ve web sitesindeki mavi). Daha önce Material'ın
 * varsayılan mor şeması kullanılıyordu; bu da marka ile arayüz arasında tutarsızlık yaratıyordu.
 * Açık temada birincil renk, beyaz yazıyla WCAG AA kontrastını sağlayacak şekilde bir ton koyu seçildi.
 */
private val IntactLightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF006874),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF97F0FF),
    onTertiaryContainer = Color(0xFF001F24),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9)
)

private val IntactDarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF00468C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3D4758),
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFF4FD8EB),
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF004F58),
    onTertiaryContainer = Color(0xFF97F0FF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A)
)

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
            val colorScheme = if (isSystemInDarkTheme()) IntactDarkColors else IntactLightColors

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
