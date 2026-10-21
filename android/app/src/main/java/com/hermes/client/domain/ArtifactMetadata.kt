package com.hermes.client.domain

const val ARTIFACT_DOWNLOAD_LIMIT_BYTES = 100L * 1024L * 1024L

/** Original-file metadata; inspecting it never downloads the file body. */
data class ArtifactMetadata(
    val sizeBytes: Long,
    val maxBytes: Long = ARTIFACT_DOWNLOAD_LIMIT_BYTES,
)
