package com.couchtasks.android.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.couchtasks.android.ui.HealthyGreen

@Composable
fun successAwareStatusColor(value: String, successValue: String): Color =
    if (value == successValue) HealthyGreen else MaterialTheme.colorScheme.onSurfaceVariant
