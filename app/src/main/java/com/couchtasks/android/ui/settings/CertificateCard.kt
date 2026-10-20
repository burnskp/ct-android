package com.couchtasks.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CertificateCard(
    title: String,
    emptyText: String,
    displayName: String,
    sizeBytes: Long?,
    sha256Fingerprint: String,
    onSelect: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                displayName.ifBlank { emptyText } + sizeBytes?.let { " · ${it} bytes" }.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sha256Fingerprint.isNotBlank()) {
                Text(
                    "SHA-256: $sha256Fingerprint",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text("Select") }
        }
    }
}
