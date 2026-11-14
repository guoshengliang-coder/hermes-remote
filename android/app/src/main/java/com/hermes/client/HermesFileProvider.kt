package com.hermes.client

import androidx.core.content.FileProvider

/** Separate manifest identity from SDK providers; retain the host authority and path metadata. */
class HermesFileProvider : FileProvider()
