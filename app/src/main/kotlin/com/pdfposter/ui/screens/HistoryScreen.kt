package com.pdfposter.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.pdfposter.MainViewModel
import com.pdfposter.data.backend.HistoryItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
                        Icon(Icons.Default.ArrowBack, "Back")
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
                    CircularProgressIndicator()
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
                            onOpen = { openLocalPdf(context, item) },
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
private fun HistoryCard(item: HistoryItem, onOpen: () -> Unit) {
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

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
