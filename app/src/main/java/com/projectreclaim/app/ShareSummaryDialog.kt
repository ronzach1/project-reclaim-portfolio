package com.projectreclaim.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SharePreview(val summary: ShareSummary, val bitmap: Bitmap)

@Composable
internal fun ShareSummaryDialog(
    onDismiss: () -> Unit,
    readSummary: suspend (SharePeriod) -> ShareSummary,
) {
    var period by remember { mutableStateOf<SharePeriod?>(null) }
    var preview by remember { mutableStateOf<SharePreview?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(period) {
        val selected = period ?: return@LaunchedEffect
        val summary = readSummary(selected)
        preview = SharePreview(summary, withContext(Dispatchers.Default) { ShareCardImage.render(summary) })
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Share your usage", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SharePeriod.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            enabled = !sharing,
                            onClick = {
                                if (period != option) {
                                    preview = null
                                    error = null
                                    period = option
                                }
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                key(period) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        val current = preview
                        if (current == null) {
                            Text(if (period == null) "Choose a period to preview." else "Preparing preview…")
                        } else {
                            val summary = current.summary
                            Image(
                                bitmap = remember(current) { current.bitmap.asImageBitmap() },
                                contentDescription = buildList {
                                    add("Project Reclaim. ${summary.scope}. ${summary.dateLabel}.")
                                    add("${summary.totalLabel}. ${summary.status}.")
                                    summary.breakdown.forEach { (name, value) -> add("$name, $value.") }
                                    add(summary.measurementNote)
                                }.joinToString(" "),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Button(
                        enabled = preview != null && !sharing,
                        onClick = {
                            val current = preview ?: return@Button
                            error = null
                            sharing = true
                            scope.launch {
                                try {
                                    val intent = withContext(Dispatchers.IO) {
                                        ShareCardExport.createSendIntent(context, current.bitmap, current.summary.period)
                                    }
                                    context.startActivity(Intent.createChooser(intent, null))
                                } catch (_: ActivityNotFoundException) {
                                    error = "Sharing is unavailable on this device."
                                } catch (_: IOException) {
                                    error = "Couldn’t prepare the image. Try again."
                                } catch (_: SecurityException) {
                                    error = "Couldn’t open sharing. Try again."
                                } finally {
                                    sharing = false
                                }
                            }
                        },
                    ) { Text("Share PNG") }
                }
            }
        }
    }
}
