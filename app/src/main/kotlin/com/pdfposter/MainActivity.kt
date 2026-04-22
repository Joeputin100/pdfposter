package com.pdfposter

import androidx.activity.compose.BackHandler
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
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
import androidx.compose.ui.viewinterop.AndroidView
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
                    var showSplash by remember { mutableStateOf(true) }
                    if (showSplash) {
                        SplashScreen { showSplash = false }
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onComplete() }
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val uri = Uri.parse("android.resource://${ctx.packageName}/raw/splash")
                    setVideoURI(uri)
                    setOnCompletionListener { onComplete() }
                    setVolume(0f, 0f)
                    start()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = "Tap to skip",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val storagePermissions = arrayOf(
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            // Start debug logging if enabled
        }
    }
    var permissionsRequested by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        val hasPermissions = storagePermissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions && !permissionsRequested) {
            permissionLauncher.launch(storagePermissions)
            permissionsRequested = true
        }
    }

    // Info Dialog states
    var infoDialogContent by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Handle back button to close drawer
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let {
            viewModel.lastGeneratedFile?.let { file ->
                context.contentResolver.openOutputStream(it)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }

    infoDialogContent?.let { (title, msg) ->
        AlertDialog(
            onDismissRequest = { infoDialogContent = null },
            title = { Text(title) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { infoDialogContent = null }) { Text("OK") } }
        )
    }

    if (viewModel.showNagwareModal) {
        AlertDialog(
            onDismissRequest = { if (viewModel.nagwareCountdown <= 0) viewModel.dismissNagwareModal() },
            title = { Text("Support the Developer") },
            text = { 
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("You've made ${viewModel.postersMadeCount} posters! Please consider registering the app on the Play Store for just \$2 to support the very poor software developer.")
                    Text("Link: https://play.google.com/store/apps/details?id=com.pdfposter")
                    Text("Time remaining: ${viewModel.nagwareCountdown} seconds", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (viewModel.nagwareCountdown <= 0) viewModel.dismissNagwareModal() },
                    enabled = viewModel.nagwareCountdown <= 0
                ) { Text("Continue") }
            }
        )
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
                
                 ListItem(
                     headlineContent = { Text("Units") },
                     supportingContent = { Text(viewModel.units) },
                     leadingContent = { Icon(Icons.Default.Straighten, null) },
                     trailingContent = {
                         Switch(
                             checked = viewModel.units == "Metric",
                             onCheckedChange = { 
                                 viewModel.logEvent(context, "Units switch toggled", "checked=$it")
                                 viewModel.toggleUnits(it) 
                             }
                         )
                     }
                 )

                 ListItem(
                     headlineContent = { Text("Debug Logging") },
                     supportingContent = { Text("Write logs to Downloads folder") },
                     leadingContent = { Icon(Icons.Default.BugReport, null) },
                     trailingContent = {
                         Switch(
                             checked = viewModel.debugLoggingEnabled,
                             onCheckedChange = { 
                                 viewModel.debugLoggingEnabled = it
                                 viewModel.saveAllSettings()
                                 viewModel.logEvent(context, "Debug logging toggled", "enabled=$it")
                             }
                         )
                     }
                 )

                 Text(
                     "Default Paper Size",
                     modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                     style = MaterialTheme.typography.labelMedium
                 )
                Box(Modifier.padding(horizontal = 16.dp)) {
                    PaperSizeSelector(viewModel)
                }

                Divider(Modifier.padding(vertical = 16.dp))
                
                Text(
                    "Supported File Types:",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "• PNG\n• JPG / JPEG\n• SVG\n• WEBP\n• BMP",
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Divider(Modifier.padding(vertical = 16.dp))
                
                 NavigationDrawerItem(
                     label = { Text("Reset to Defaults") },
                     selected = false,
                     onClick = { 
                         viewModel.logEvent(context, "Reset to defaults triggered")
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
                         IconButton(onClick = { 
                             viewModel.logEvent(context, "Drawer opened")
                             scope.launch { drawerState.open() } 
                         }) {
                             Icon(Icons.Default.Menu, "Settings")
                         }
                    }
                )
            }
        ) { padding ->
            if (viewModel.isFirstRun) {
                FirstRunWizard(viewModel = viewModel, onDismiss = { viewModel.saveAllSettings() })
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
                    onImageSelected = { 
                        viewModel.logEvent(context, "Image selected", "uri=$it")
                        viewModel.updateImage(context, it) 
                    }
                )

                if (viewModel.selectedImageUri == null) {
                    OnboardingView()
                } else {
                    // 2. Image Info
                    viewModel.imageMetadata?.let { meta ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            InfoChip(label = "Resolution", value = meta.resolution)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InfoChip(label = "Aspect Ratio", value = meta.aspectRatioString)
                                IconButton(onClick = { 
                                    infoDialogContent = "Aspect Ratio" to "This is the ratio of width to height. Locked scaling ensures your poster matches the image proportions perfectly."
                                }) {
                                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = true,
                        enter = expandVertically(spring()),
                        exit = shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            val unitLabel = if (viewModel.units == "Metric") "cm" else "in"

                            // 3. Poster Dimensions
                            GlassCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Poster Size", style = MaterialTheme.typography.labelLarge)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        ConfigInput(
                                            label = "Width ($unitLabel)",
                                            value = viewModel.posterWidth,
                                            onValueChange = { 
                                                viewModel.updatePosterWidth(it)
                                                viewModel.saveAllSettings()
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        
                                         IconButton(onClick = { 
                                             viewModel.isAspectRatioLocked = !viewModel.isAspectRatioLocked 
                                             viewModel.logEvent(context, "Aspect ratio lock toggled", "locked=${viewModel.isAspectRatioLocked}")
                                             viewModel.saveAllSettings()
                                         }) {
                                            Icon(
                                                if (viewModel.isAspectRatioLocked) Icons.Default.Link else Icons.Default.LinkOff,
                                                null,
                                                tint = if (viewModel.isAspectRatioLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                            )
                                        }

                                         ConfigInput(
                                             label = if (viewModel.isAspectRatioLocked) "Height (auto) ($unitLabel)" else "Height ($unitLabel)",
                                             value = viewModel.posterHeight,
                                             onValueChange = { 
                                                 viewModel.updatePosterHeight(it)
                                                 viewModel.saveAllSettings()
                                             },
                                             modifier = Modifier.weight(1f),
                                             readOnly = viewModel.isAspectRatioLocked
                                         )
                                    }
                                    
                                    viewModel.getDpiWarning()?.let { warning ->
                                        Text(
                                            text = warning,
                                            color = Color(0xFFFFA000),
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

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Orientation", style = MaterialTheme.typography.bodyMedium)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("Best Fit", "Portrait", "Landscape").forEach { o ->
                                             FilterChip(
                                                 selected = viewModel.orientation == o,
                                                 onClick = { 
                                                     viewModel.orientation = o
                                                     viewModel.logEvent(context, "Orientation changed", "value=$o")
                                                     viewModel.saveAllSettings()
                                                 },
                                                 label = { Text(o) }
                                             )
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                             ConfigInput(
                                                 label = "Margin ($unitLabel)",
                                                 value = viewModel.margin,
                                                 onValueChange = { 
                                                     viewModel.margin = it
                                                     viewModel.logEvent(context, "Margin changed", "value=$it")
                                                     viewModel.saveAllSettings() 
                                                 },
                                                 modifier = Modifier.weight(1f)
                                             )
                                            IconButton(onClick = { 
                                                infoDialogContent = "Margin" to "The unprinted space around the edges of each page. Most home printers require at least 0.25in (0.64cm)."
                                            }) { Icon(Icons.Default.Info, null, Modifier.size(18.dp)) }
                                        }
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                             ConfigInput(
                                                 label = "Overlap ($unitLabel)",
                                                 value = viewModel.overlap,
                                                 onValueChange = { 
                                                     viewModel.overlap = it
                                                     viewModel.logEvent(context, "Overlap changed", "value=$it")
                                                     viewModel.saveAllSettings() 
                                                 },
                                                 modifier = Modifier.weight(1f)
                                             )
                                            IconButton(onClick = { 
                                                infoDialogContent = "Overlap" to "The repeated area between tiles to help you align and glue them together. 0.25in to 0.5in is recommended."
                                            }) { Icon(Icons.Default.Info, null, Modifier.size(18.dp)) }
                                        }
                                    }
                                    
                                    viewModel.getPaneCount()?.let { (total, rows, cols) ->
                                        Text(
                                            "Project scope: $total pages ($rows rows x $cols columns)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            AdvancedOptionsSection(viewModel)
                            PosterPreview(viewModel)

                            // 7. Unified Actions
                            if (viewModel.isGenerating) {
                                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { 
                                            viewModel.triggerNagwareModal {
                                                viewModel.generatePoster(context) {
                                                    val file = viewModel.lastGeneratedFile ?: return@generatePoster
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                                        context, "${context.packageName}.provider", file
                                                    )
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, "application/pdf")
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(android.content.Intent.createChooser(intent, "Open PDF"))
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(64.dp),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Default.Visibility, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("View")
                                    }
                                    
                                    Button(
                                        onClick = { 
                                            viewModel.triggerNagwareModal {
                                                viewModel.generatePoster(context) {
                                                    saveLauncher.launch("poster_${System.currentTimeMillis()}.pdf")
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(64.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                    ) {
                                        Icon(Icons.Default.Save, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Save")
                                    }

                                    Button(
                                        onClick = { 
                                            viewModel.triggerNagwareModal {
                                                viewModel.generatePoster(context) {
                                                    val file = viewModel.lastGeneratedFile ?: return@generatePoster
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                                        context, "${context.packageName}.provider", file
                                                    )
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                        type = "application/pdf"
                                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(android.content.Intent.createChooser(intent, "Share PDF"))
                                                }
                                            }
                                        },
                                        modifier = Modifier.width(64.dp).height(64.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                                    ) {
                                        Icon(Icons.Default.Share, null)
                                    }
                                }
                            }
                            
                            viewModel.errorMessage?.let { MessageText(it, MaterialTheme.colorScheme.error) }
                            viewModel.successMessage?.let { MessageText(it, MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingView() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("How to get started:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        
        OnboardingStep(1, "Pick a high-resolution image above.")
        OnboardingStep(2, "Set your final poster dimensions.")
        OnboardingStep(3, "Select your paper size and orientation.")
        OnboardingStep(4, "View or Save your print-ready PDF!")
    }
}

@Composable
fun OnboardingStep(num: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(num.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
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
    val options = listOf("Letter (8.5x11)", "Legal (8.5x14)", "Tabloid (11x17)", "A4 (8.27x11.69)", "A3 (11.69x16.54)", "Custom")
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
                        text = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(selectionOption) 
                            }
                        },
                         onClick = {
                             viewModel.paperSize = selectionOption
                             viewModel.logEvent(context, "Paper size selected", "size=$selectionOption")
                             viewModel.saveAllSettings()
                             expanded = false
                         }
                    )
                }
            }
        }

        if (viewModel.paperSize == "Custom") {
            val unitLabel = if (viewModel.units == "Metric") "cm" else "in"
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                 ConfigInput(
                     label = "Width ($unitLabel)",
                     value = viewModel.customPaperWidth,
                     onValueChange = { 
                         viewModel.customPaperWidth = it
                         viewModel.logEvent(context, "Custom paper width changed", "value=$it")
                         viewModel.saveAllSettings() 
                     },
                     modifier = Modifier.weight(1f)
                 )
                 ConfigInput(
                     label = "Height ($unitLabel)",
                     value = viewModel.customPaperHeight,
                     onValueChange = { 
                         viewModel.customPaperHeight = it
                         viewModel.logEvent(context, "Custom paper height changed", "value=$it")
                         viewModel.saveAllSettings() 
                     },
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
                             Checkbox(checked = viewModel.showOutlines, onCheckedChange = { 
                                 viewModel.showOutlines = it
                                 viewModel.logEvent(context, "Show outlines toggled", "enabled=$it")
                                 viewModel.saveAllSettings() 
                             })
                             Text("Draw Outlines")
                         }
                    
                    if (viewModel.showOutlines) {
                         OutlineStyleSelector(
                             style = viewModel.outlineStyle,
                             onStyleChange = { 
                                 viewModel.outlineStyle = it
                                 viewModel.logEvent(context, "Outline style changed", "style=$it")
                                 viewModel.saveAllSettings() 
                             },
                             thickness = viewModel.outlineThickness,
                             onThicknessChange = { 
                                 viewModel.outlineThickness = it
                                 viewModel.logEvent(context, "Outline thickness changed", "thickness=$it")
                                 viewModel.saveAllSettings() 
                             }
                         )
                    }

                     Row(verticalAlignment = Alignment.CenterVertically) {
                         Checkbox(checked = viewModel.labelPanes, onCheckedChange = { 
                             viewModel.labelPanes = it
                             viewModel.logEvent(context, "Label panes toggled", "enabled=$it")
                             viewModel.saveAllSettings() 
                         })
                         Text("Label Each Pane (A1, B2...)")
                     }

                     Row(verticalAlignment = Alignment.CenterVertically) {
                         Checkbox(checked = viewModel.includeInstructions, onCheckedChange = { 
                             viewModel.includeInstructions = it
                             viewModel.logEvent(context, "Include instructions toggled", "enabled=$it")
                             viewModel.saveAllSettings() 
                         })
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
                drawLine(Color.White, Offset(10f, 30f), Offset(90f, 30f), 4f)
            }
            LineStyleIcon(label = "Dashed", isSelected = style == "Dashed", onClick = { onStyleChange("Dashed") }) {
                drawLine(Color.White, Offset(10f, 30f), Offset(90f, 30f), 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f))
            }
            LineStyleIcon(label = "Dotted", isSelected = style == "Dotted", onClick = { onStyleChange("Dotted") }) {
                drawLine(Color.White, Offset(10f, 30f), Offset(90f, 30f), 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 10f), 0f))
            }
        }
        
        Text("Thickness", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Thin", "Medium", "Heavy").forEach { t ->
                FilterChip(selected = thickness == t, onClick = { onThicknessChange(t) }, label = { Text(t) })
            }
        }
    }
}

@Composable
fun LineStyleIcon(label: String, isSelected: Boolean, onClick: () -> Unit, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(100.dp, 60.dp)
                .clip(RoundedCornerShape(12.dp))
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
fun FirstRunWizard(viewModel: MainViewModel, onDismiss: () -> Unit) {
    var selectedUnits by remember { mutableStateOf(viewModel.units) }
    
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Welcome to Poster PDF!") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Let's get you set up. You can always change these settings later in the side menu.")
                
                Text("Select your preferred measurement units:", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedUnits == "Inches", onClick = { selectedUnits = "Inches" })
                    Text("Inches")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(selected = selectedUnits == "Metric", onClick = { selectedUnits = "Metric" })
                    Text("Metric")
                }

                Text("Default Paper Size:", style = MaterialTheme.typography.labelLarge)
                PaperSizeSelector(viewModel)
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.units = selectedUnits
                if (selectedUnits == "Metric") {
                    viewModel.toggleUnits(true)
                }
                viewModel.saveAllSettings()
                onDismiss()
            }) { Text("Get Started") }
        }
    )
}

@Composable
fun ConfigInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    readOnly: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        readOnly = readOnly,
        colors = if (readOnly) OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledPlaceholderColor = MaterialTheme.colorScheme.outline
        ) else OutlinedTextFieldDefaults.colors()
    )
}
