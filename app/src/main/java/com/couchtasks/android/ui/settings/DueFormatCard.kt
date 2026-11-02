package com.couchtasks.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couchtasks.android.data.DueFormat

@Composable
fun DueFormatCard(selected: DueFormat, onSelect: (DueFormat) -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Due dates", fontWeight = FontWeight.SemiBold)
            val options = listOf(DueFormat.Absolute to "Date", DueFormat.Relative to "Time left")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (format, label) ->
                    SegmentedButton(
                        selected = selected == format,
                        onClick = { onSelect(format) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        modifier = Modifier.testTag("DueFormat-${format.name}"),
                    ) { Text(label) }
                }
            }
            Text(
                if (selected == DueFormat.Absolute) "Shown as 2026-11-15 09:00." else "Shown as time left, like 2d5h.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
