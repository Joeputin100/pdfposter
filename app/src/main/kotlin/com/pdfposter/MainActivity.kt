package com.pdfposter

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfposter.ui.components.GlassCard
import com.pdfposter.ui.components.ImagePickerHeader
import com.pdfposter.ui.components.PosterPreview
import com.pdfposter.ui.theme.PDFPosterTheme
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let {
            viewModel.lastGeneratedFile?.let { file ->
                context.contentResolver.openOutputStream(it)?.use { output ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Poster PDF Settings",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                // Units Selector
                ListItem(
                    headlineContent = { Text("Measurement Units") },
                    supportingContent = { Text(viewModel.units) },
                    leadingContent = { Icon(Icons.Default.Straighten, null) },
                    trailingContent = {
                        Switch(
                            checked = viewModel.units == "Metric",
                            onCheckedChange = { 
                                viewModel.units = if (it) "Metric" else "Inches"
                                viewModel.saveAllSettings()
                            }
                        )
                    }
                )

                // Default Paper Size
                ListItem(
                    headlineContent = { Text("Default Paper") },
                    supportingContent = { Text(viewModel.paperSize) },
                    leadingContent = { Icon(Icons.Default.Description, null) }
                )

                Divider(Modifier.padding(vertical = 8.dp))
                
                NavigationDrawerItem(
                    label = { Text("Reset to Defaults") },
                    selected = false,
                    onClick = { 
                        viewModel.resetToDefaults()
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Refresh, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { 
                        Text(
                            "Poster PDF", 
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Settings")
                        }
                    }
                )
            }
        ) { padding ->
            if (viewModel.isFirstRun) {
                FirstRunWizard(onDismiss = { viewModel.saveAllSettings() })
            }

            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 1. Image Selection
                ImagePickerHeader(
                    selectedImageUri = viewModel.selectedImageUri,
                    onImageSelected = { viewModel.updateImage(context, it) }
                )
                // 2. Image Info (Resolution & AR)
                viewModel.imageMetadata?.let { meta ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        InfoChip(label = "Resolution", value = meta.resolution)
                        InfoChip(label = "Aspect Ratio", value = String.format("%.2f:1", meta.aspectRatio))
                    }
                }

                AnimatedVisibility(
                    visible = viewModel.selectedImageUri != null,
                    enter = expandVertically(spring()),
                    exit = shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        
                        // 3. Poster Dimensions
                        GlassCard {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Poster Size", style = MaterialTheme.typography.labelLarge)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    ConfigInput(
                                        label = "Width (in)",
                                        value = viewModel.posterWidth,
                                        onValueChange = { 
                                            viewModel.updatePosterWidth(it)
                                            viewModel.saveAllSettings()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    IconButton(onClick = { 
                                        viewModel.isAspectRatioLocked = !viewModel.isAspectRatioLocked 
                                        viewModel.saveAllSettings()
                                    }) {
                                        Icon(
                                            if (viewModel.isAspectRatioLocked) Icons.Default.Link else Icons.Default.LinkOff,
                                            contentDescription = "Lock Ratio",
                                            tint = if (viewModel.isAspectRatioLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }

                                    ConfigInput(
                                        label = "Height (in)",
                                        value = viewModel.posterHeight,
                                        onValueChange = { 
                                            viewModel.updatePosterHeight(it)
                                            viewModel.saveAllSettings()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                
                                viewModel.getDpiWarning()?.let { warning ->
                                    Text(
                                        text = warning,
                                        color = Color(0xFFFFA000), // Material Orange 700
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                        }

                        // 4. Paper & Layout
                        GlassCard {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Paper & Layout", style = MaterialTheme.typography.labelLarge)
                                
                                PaperSizeSelector(viewModel)

                                if (viewModel.paperSize == "Custom") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        ConfigInput(
                                            label = "Paper Width (in)",
                                            value = viewModel.customPaperWidth,
                                            onValueChange = { 
                                                viewModel.customPaperWidth = it
                                                viewModel.saveAllSettings()
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        ConfigInput(
                                            label = "Paper Height (in)",
                                            value = viewModel.customPaperHeight,
                                            onValueChange = { 
                                                viewModel.customPaperHeight = it
                                                viewModel.saveAllSettings()
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    ConfigInput(
                                        label = "Margin (in)",
                                        value = viewModel.margin,
                                        onValueChange = { 
                                            viewModel.margin = it
                                            viewModel.saveAllSettings()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ConfigInput(
                                        label = "Overlap (in)",
                                        value = viewModel.overlap,
                                        onValueChange = { 
                                            viewModel.overlap = it
                                            viewModel.saveAllSettings()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                
                                viewModel.getPaneCount()?.let { (total, rows, cols) ->
                                    Text(
                                        "Will generate $total pages ($rows rows x $cols columns)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }

                        // 5. Advanced Options (Outlines & Labels)
                        AdvancedOptionsSection(viewModel)

                        // 6. Preview & Generate
                        PosterPreview(viewModel)

                        Button(
                            onClick = { viewModel.generatePoster(context) },
                            enabled = !viewModel.isGenerating,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            if (viewModel.isGenerating) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Default.PictureAsPdf, null)
                                Spacer(Modifier.width(12.dp))
                                Text("Generate Tiled PDF", fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        // Messages
                        viewModel.errorMessage?.let { MessageText(it, MaterialTheme.colorScheme.error) }
                        
                        if (viewModel.successMessage != null && viewModel.lastGeneratedFile != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                MessageText(viewModel.successMessage!!, MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val file = viewModel.lastGeneratedFile!!
                                    Button(
                                        onClick = { 
                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                context, 
                                                "${context.packageName}.provider", 
                                                file
                                            )
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "application/pdf")
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(intent, "Open PDF"))
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Icon(Icons.Default.Visibility, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("View")
                                    }
                                    
                                    Button(
                                        onClick = { 
                                            saveLauncher.launch("poster_${System.currentTimeMillis()}.pdf")
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                    ) {
                                        Icon(Icons.Default.Save, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Save As...")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoChip(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperSizeSelector(viewModel: MainViewModel) {
    val options = listOf("Letter (8.5x11)", "A4 (8.27x11.69)", "A3 (11.69x16.54)", "Custom")
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = viewModel.paperSize,
                onValueChange = {},
                readOnly = true,
                label = { Text("Paper Size") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = { Text(selectionOption) },
                        onClick = {
                            viewModel.paperSize = selectionOption
                            viewModel.saveAllSettings()
                            expanded = false
                        }
                    )
                }
            }
        }

        if (viewModel.paperSize == "Custom") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ConfigInput(
                    label = "Paper Width (in)",
                    value = viewModel.customPaperWidth,
                    onValueChange = { viewModel.customPaperWidth = it },
                    modifier = Modifier.weight(1f)
                )
                ConfigInput(
                    label = "Paper Height (in)",
                    value = viewModel.customPaperHeight,
                    onValueChange = { viewModel.customPaperHeight = it },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun AdvancedOptionsSection(viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    
    GlassCard {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Advanced Styling", style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewModel.showOutlines, 
                            onCheckedChange = { 
                                viewModel.showOutlines = it 
                                viewModel.saveAllSettings()
                            }
                        )
                        Text("Draw Outlines")
                    }
                    
                    if (viewModel.showOutlines) {
                        OutlineStyleSelector(
                            style = viewModel.outlineStyle,
                            onStyleChange = { 
                                viewModel.outlineStyle = it 
                                viewModel.saveAllSettings()
                            },
                            thickness = viewModel.outlineThickness,
                            onThicknessChange = { 
                                viewModel.outlineThickness = it 
                                viewModel.saveAllSettings()
                            }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewModel.labelPanes, 
                            onCheckedChange = { 
                                viewModel.labelPanes = it 
                                viewModel.saveAllSettings()
                            }
                        )
                        Text("Label Each Pane (A1, B2...)")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewModel.includeInstructions, 
                            onCheckedChange = { 
                                viewModel.includeInstructions = it 
                                viewModel.saveAllSettings()
                            }
                        )
                        Text("Include Assembly Instructions")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineStyleSelector(
    style: String, 
    onStyleChange: (String) -> Unit,
    thickness: String,
    onThicknessChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Line Style", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineStyleIcon(label = "Solid", isSelected = style == "Solid", onClick = { onStyleChange("Solid") }) {
                drawLine(Color.White, Offset(0f, 15f), Offset(40f, 15f), 2f)
            }
            LineStyleIcon(label = "Dashed", isSelected = style == "Dashed", onClick = { onStyleChange("Dashed") }) {
                drawLine(Color.White, Offset(0f, 15f), Offset(40f, 15f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
            }
            LineStyleIcon(label = "Dotted", isSelected = style == "Dotted", onClick = { onStyleChange("Dotted") }) {
                drawLine(Color.White, Offset(0f, 15f), Offset(40f, 15f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f), 0f))
            }
        }
        
        Text("Thickness", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Thin", "Medium", "Heavy").forEach { t ->
                FilterChip(
                    selected = thickness == t,
                    onClick = { onThicknessChange(t) },
                    label = { Text(t) }
                )
            }
        }
    }
}

@Composable
fun LineStyleIcon(label: String, isSelected: Boolean, onClick: () -> Unit, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(60.dp, 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick() }
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) { draw() }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MessageText(msg: String, color: Color) {
    Text(
        text = msg,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp),
        fontWeight = FontWeight.Medium
    )
}

@Composable
fun FirstRunWizard(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Welcome to Poster PDF!") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Let's get you set up. You can always change these settings later in the side menu.")
                Text("Select your preferred measurement units:", style = MaterialTheme.typography.labelLarge)
                // Note: Simplified for the wizard
                Text("Initial setup: Inches, Letter Paper")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Get Started") }
        }
    )
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
