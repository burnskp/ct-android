package com.couchtasks.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ReviewIntervalCard(
    value: String,
    savedDays: Int,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review interval", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value,
                onValueChange = { text -> onValueChange(text.filter(Char::isDigit)) },
                label = { Text("Days") },
                supportingText = { Text("Pending tasks not reviewed in this many days appear in Review.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ReviewIntervalField"),
            )
            Button(
                onClick = onSave,
                enabled = (value.toIntOrNull() ?: 0) >= 1 && value.toIntOrNull() != savedDays,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save review interval") }
        }
    }
}
