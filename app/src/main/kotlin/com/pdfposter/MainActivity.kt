package com.pdfposter

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfposter.ui.components.GlassCard
import com.pdfposter.ui.components.ImagePickerHeader
import com.pdfposter.ui.theme.PDFPosterTheme
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PDFPosterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var posterWidth by remember { mutableStateOf("24") }
    var posterHeight by remember { mutableStateOf("36") }
    var paperSize by remember { mutableStateOf("8.5x11") }
    var margin by remember { mutableStateOf("0.5") }
    var overlap by remember { mutableStateOf("0.25") }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        "PDF Poster", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    ) 
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = PaddingValues(16.dp).let { 
                object : Arrangement.Vertical {
                    override fun Density.arrange(
                        totalSize: Int,
                        sizes: IntArray,
                        outPositions: IntArray
                    ) {
                        var current = 0
                        sizes.forEachIndexed { index, s ->
                            outPositions[index] = current
                            current += s + 24.dp.roundToPx()
                        }
                    }
                }
            }
        ) {
            ImagePickerHeader(
                selectedImageUri = selectedImageUri,
                onImageSelected = { selectedImageUri = it }
            )

            AnimatedVisibility(
                visible = selectedImageUri != null,
                enter = expandVertically(spring()),
                exit = shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(
                        text = "Configure Poster",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                ConfigInput(
                                    label = "Width (in)",
                                    value = posterWidth,
                                    onValueChange = { posterWidth = it },
                                    modifier = Modifier.weight(1f)
                                )
                                ConfigInput(
                                    label = "Height (in)",
                                    value = posterHeight,
                                    onValueChange = { posterHeight = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            ConfigInput(
                                label = "Paper Size",
                                value = paperSize,
                                onValueChange = { paperSize = it },
                                placeholder = "e.g., 8.5x11"
                            )
                        }
                    }

                    GlassCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ConfigInput(
                                label = "Margin (in)",
                                value = margin,
                                onValueChange = { margin = it },
                                modifier = Modifier.weight(1f)
                            )
                            ConfigInput(
                                label = "Overlap (in)",
                                value = overlap,
                                onValueChange = { overlap = it },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (viewModel.errorMessage != null) {
                        Text(
                            text = viewModel.errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    if (viewModel.successMessage != null) {
                        Text(
                            text = viewModel.successMessage!!,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    Button(
                        onClick = { 
                            selectedImageUri?.let { uri ->
                                viewModel.generatePoster(
                                    context = context,
                                    imageUri = uri,
                                    posterWidth = posterWidth.toDoubleOrNull() ?: 24.0,
                                    posterHeight = posterHeight.toDoubleOrNull() ?: 36.0,
                                    paperSize = paperSize,
                                    margin = margin.toDoubleOrNull() ?: 0.5,
                                    overlap = overlap.toDoubleOrNull() ?: 0.25
                                )
                            }
                        },
                        enabled = !viewModel.isGenerating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp)
                    ) {
                        if (viewModel.isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Generate Tiled PDF",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun ConfigInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
}
