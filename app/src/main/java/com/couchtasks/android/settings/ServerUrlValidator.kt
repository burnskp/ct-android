package com.couchtasks.android.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

sealed interface ServerUrlValidationResult {
    data class Valid(val normalizedUrl: String) : ServerUrlValidationResult
    data object Missing : ServerUrlValidationResult
    data object NotHttps : ServerUrlValidationResult
    data object MissingHost : ServerUrlValidationResult
    data object Malformed : ServerUrlValidationResult
}

fun validateAndNormalizeServerUrl(input: String): ServerUrlValidationResult {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ServerUrlValidationResult.Missing

    val uri = runCatching { URI(trimmed) }.getOrElse { return ServerUrlValidationResult.Malformed }
    val scheme = uri.scheme ?: return ServerUrlValidationResult.Malformed
    if (!scheme.equals("https", ignoreCase = true)) return ServerUrlValidationResult.NotHttps
    if (uri.host.isNullOrBlank()) return ServerUrlValidationResult.MissingHost

    val url = trimmed.toHttpUrlOrNull() ?: return ServerUrlValidationResult.Malformed
    if (!url.isHttps) return ServerUrlValidationResult.NotHttps
    if (url.host.isBlank()) return ServerUrlValidationResult.MissingHost
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
        return ServerUrlValidationResult.Malformed
    }

    return ServerUrlValidationResult.Valid(url.toString().trimEnd('/'))
}
