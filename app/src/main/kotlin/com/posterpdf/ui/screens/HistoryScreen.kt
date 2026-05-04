package com.posterpdf.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.posterpdf.MainViewModel
import com.posterpdf.data.backend.HistoryItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshHistory() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        when {
            viewModel.isHistoryLoading && viewModel.historyItems.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
            viewModel.historyItems.isEmpty() -> {
                EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    items(viewModel.historyItems, key = { it.id }) { item ->
                        HistoryCard(
                            item = item,
                            onView = { openLocalPdf(context, item) },
                            onShare = { sharePdf(context, item) },
                            onDownload = {
                                // TODO(H-P3): pull cloud copy to local Downloads.
                                // Stubbed for v1; History view shows the button
                                // but the actual download flow needs a callable
                                // function or signed-URL fetch.
                            },
                            onDeleteFromCloud = {
                                // TODO(H-P3.4): wire to viewModel.deleteFromCloud(item.id)
                                // which calls the deleteCloudCopy callable.
                                viewModel.logEvent(
                                    context,
                                    "delete_cloud_copy_requested",
                                    "id=${item.id}",
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.HistoryEdu,
            null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("No history yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Generate a poster — it'll show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryCard(
    item: HistoryItem,
    onView: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onDeleteFromCloud: () -> Unit,
) {
    val icon = when (item.type) {
        "upscale_local", "upscale_remote" -> Icons.Default.AutoAwesome
        else -> Icons.Default.PictureAsPdf
    }
    val fileName = item.localUri.substringAfterLast('/').ifEmpty { item.id }
    val createdLabel = item.createdAtMillis?.let {
        SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(it))
    } ?: "—"
    val dimensions = run {
        val rows = item.metadata["rows"] as? Number
        val cols = item.metadata["cols"] as? Number
        val pages = item.metadata["pages"] as? Number
        if (rows != null && cols != null && pages != null) {
            "${pages.toInt()} pages · ${rows.toInt()}×${cols.toInt()}"
        } else null
    }
    val poster = run {
        val pw = item.metadata["posterWidth"]?.toString()
        val ph = item.metadata["posterHeight"]?.toString()
        val units = item.metadata["units"]?.toString()
        if (!pw.isNullOrEmpty() && !ph.isNullOrEmpty()) "$pw × $ph ${units ?: ""}" else null
    }

    val hasCloud = item.cloudStorageUri.isNotEmpty()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // RC14: render the PDF's first page as a thumbnail when
                    // the file is on disk; fall back to the type-based icon
                    // when the file is gone (cloud-only) or hasn't loaded yet.
                    PdfThumbnail(
                        localUri = item.localUri,
                        fallbackIcon = icon,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        createdLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (dimensions != null || poster != null) {
                        Text(
                            listOfNotNull(poster, dimensions).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onView, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Visibility, contentDescription = "View")
                }
                IconButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
                IconButton(
                    onClick = onDownload,
                    enabled = hasCloud,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download from cloud")
                }
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = hasCloud,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = "Delete cloud copy",
                        tint = if (hasCloud) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete cloud copy?") },
            text = {
                Text(
                    "This removes the poster from cloud storage. Your local copy and this " +
                        "history entry are unaffected — you'll just lose the ability to " +
                        "re-download from cloud later. Frees up storage on your account.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFromCloud()
                    showDeleteConfirm = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private fun openLocalPdf(context: android.content.Context, item: HistoryItem) {
    val path = item.localUri
    if (path.isEmpty()) return
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open PDF"))
}

private fun sharePdf(context: android.content.Context, item: HistoryItem) {
    val path = item.localUri
    if (path.isEmpty()) return
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share PDF"))
}

/**
 * RC14 — first-page thumbnail of a saved PDF, rendered via PdfRenderer
 * on a background coroutine. Shows the fallbackIcon while loading or
 * when the file is gone (cloud-only items, or the user cleared cache).
 *
 * Cache strategy: composable-scoped via remember(localUri); the LazyColumn
 * recycles cards as the user scrolls, so each card re-renders its thumb
 * on first compose. PdfRenderer is fast enough (~10-30 ms per first
 * page on mid-tier hardware) that this is acceptable; a global LruCache
 * could be added if scrolling lag becomes noticeable.
 */
@Composable
private fun PdfThumbnail(
    localUri: String,
    fallbackIcon: ImageVector,
) {
    var bmp by remember(localUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(localUri) {
        bmp = if (localUri.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                renderPdfFirstPage(File(localUri))
            }
        } else null
    }
    val current = bmp
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Icon(
            fallbackIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun renderPdfFirstPage(file: File): android.graphics.Bitmap? {
    if (!file.exists()) return null
    val pfd = android.os.ParcelFileDescriptor.open(
        file, android.os.ParcelFileDescriptor.MODE_READ_ONLY,
    )
    return try {
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        try {
            if (renderer.pageCount == 0) null else {
                val page = renderer.openPage(0)
                // Render at fixed 144px height to keep memory bounded;
                // width follows page aspect.
                val targetH = 144
                val ratio = page.width.toFloat() / page.height.coerceAtLeast(1)
                val targetW = (targetH * ratio).toInt().coerceAtLeast(1)
                val out = android.graphics.Bitmap.createBitmap(
                    targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888,
                )
                out.eraseColor(android.graphics.Color.WHITE)
                page.render(
                    out, null, null,
                    android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                page.close()
                out
            }
        } finally {
            renderer.close()
        }
    } catch (t: Throwable) {
        null
    } finally {
        pfd.close()
    }
}
