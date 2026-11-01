package com.hermes.client.ui.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** The cache's collision-safe filename is private; viewers receive the user's original name. */
internal fun attachmentFileUri(context: Context, local: File, displayName: String): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local, displayName)
