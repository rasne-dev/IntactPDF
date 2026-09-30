package dev.rasne.intactpdf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.rasne.intactpdf.model.PdfTextBlock

@Composable
fun TextEditDialog(
    block: PdfTextBlock,
    onDismiss: () -> Unit,
    onSaveEdit: (newText: String, isRemoved: Boolean) -> Unit
) {
    // İmleç metnin sonunda başlar; düzeltme yapmak için doğrudan yazmaya başlanabilir
    var fieldValue by remember(block.id) {
        mutableStateOf(TextFieldValue(block.text, TextRange(block.text.length)))
    }
    val editedText = fieldValue.text
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Pencere açılır açılmaz klavye gelsin (bir kez daha alana dokunma zorunluluğunu kaldırır)
    LaunchedEffect(block.id) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Metni Düzenle",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Text(
                    text = "Mevcut Metin:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { newValue ->
                        // PDF'deki her blok tek satırdır; satır sonları '?' olarak yazılmasın diye boşluğa çevrilir
                        val cleaned = newValue.text.replace('\n', ' ').replace('\r', ' ')
                        fieldValue = if (cleaned == newValue.text) newValue else newValue.copy(text = cleaned)
                    },
                    label = { Text("Yeni Metin") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = false,
                    maxLines = 4
                )

                Text(
                    text = "Düzenleme doğrudan PDF sayfasına uygulanır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showDeleteConfirmation = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Metni Sil")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("İptal")
                        }
                        Button(
                            // Metin değişmediyse "Uygula" anlamsız; boş bırakılırsa silme onayına yönlendirilir
                            enabled = editedText != block.text,
                            onClick = {
                                if (editedText.isBlank()) {
                                    showDeleteConfirmation = true
                                } else {
                                    onSaveEdit(editedText, false)
                                }
                            }
                        ) {
                            Text("Uygula")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Metin silinsin mi?") },
            text = {
                Text(
                    "Metin sayfada beyaz bir kutuyla kapatılır; bu işlem geri alınabilir.\n\n" +
                        "Not: Metin PDF dosyasının içinden tamamen silinmez, yalnızca görünmez hale getirilir. " +
                        "Gizli bilgi içeren bir belgeyi paylaşmadan önce bunu göz önünde bulundurun."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onSaveEdit("", true)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Sil") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Vazgeç") }
            }
        )
    }
}
