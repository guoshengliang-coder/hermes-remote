package com.hermes.client.domain

/** Strip trailing separators without turning a known root directory into an unknown path. */
fun normalizeProjectPath(path: String?): String? =
    path?.ifBlank { null }?.let { it.trimEnd('/', '\\').ifEmpty { it.take(1) } }
