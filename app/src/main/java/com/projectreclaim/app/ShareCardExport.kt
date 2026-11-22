package com.projectreclaim.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object ShareCardExport {
    fun createSendIntent(context: Context, bitmap: Bitmap, period: SharePeriod): Intent {
        val directory = File(context.cacheDir, "share_cards")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Share cache unavailable")
        }
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }

        val file = File.createTempFile("reclaim-${period.label.lowercase()}-", ".png", directory)
        try {
            file.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("Could not encode share image")
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            return Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Project Reclaim summary", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (exception: Exception) {
            file.delete()
            throw exception
        }
    }
}
