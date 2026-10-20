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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ServerUrlCard(
    value: String,
    savedValue: String,
    error: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Server URL", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Server URL") },
                placeholder = { Text("https://tasks.example.com") },
                isError = error.isNotBlank(),
                supportingText = {
                    Text(error.ifBlank { "Required absolute HTTPS URL. It will be saved without trailing slashes." })
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ServerUrlField"),
            )
            Text(
                "Saved: ${savedValue.ifBlank { "not configured" }}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSave,
                enabled = value.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save server URL") }
        }
    }
}
