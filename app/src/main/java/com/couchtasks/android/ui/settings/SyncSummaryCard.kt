package com.couchtasks.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couchtasks.android.settings.SettingsState
import com.couchtasks.android.ui.common.successAwareStatusColor

@Composable
fun SyncSummaryCard(settings: SettingsState) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sync Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Health: ${settings.lastHealthResult}",
                color = successAwareStatusColor(settings.lastHealthResult, "Healthy"),
            )
            Text(
                "Last sync result: ${settings.lastSyncResult}",
                color = successAwareStatusColor(settings.lastSyncResult, "Sync successful"),
            )
            Text(
                "Last sync timestamp: ${settings.lastSyncTimestamp.ifBlank { "never" }}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
