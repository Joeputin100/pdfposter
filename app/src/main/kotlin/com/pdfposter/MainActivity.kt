package com.pdfposter

import androidx.activity.compose.BackHandler
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.VideoView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
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
    val activity = context as? android.app.Activity
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleGoogleSignInResult(result.data)
    }

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



    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Poster PDF",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )

                HistorySection(viewModel)

                Divider(Modifier.padding(vertical = 12.dp))

                Text(
                    "Settings",
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

                  ListItem(
                      headlineContent = { Text("Posters Generated") },
                      supportingContent = { Text("Total posters created") },
                      leadingContent = { Icon(Icons.Default.Dashboard, null) },
                      trailingContent = {
                          Text(
                              "${viewModel.postersMadeCount}",
                              style = MaterialTheme.typography.titleMedium,
                              fontWeight = FontWeight.Bold,
                              color = MaterialTheme.colorScheme.primary
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

                Divider(Modifier.padding(vertical = 12.dp))

                AccountSection(
                    viewModel = viewModel,
                    onSignInClick = {
                        activity?.let { signInLauncher.launch(viewModel.googleSignInIntent(it)) }
                    },
                )
                Spacer(Modifier.height(24.dp))
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

                val showNagBanner = viewModel.postersMadeCount >= 10 && !viewModel.nagwareDismissed
                LaunchedEffect(showNagBanner) {
                    if (showNagBanner) {
                        viewModel.nagwareCountdown = 5
                        while (viewModel.nagwareCountdown > 0) {
                            kotlinx.coroutines.delay(1000)
                            viewModel.nagwareCountdown--
                        }
                    }
                }
                if (showNagBanner) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFFEB3B),
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Support the Developer",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.Black
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "You've made ${viewModel.postersMadeCount} posters! Please consider registering on the Play Store for just \$2 to support development.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Black
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "play.google.com/store/apps/details?id=com.pdfposter",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Black
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                TextButton(
                                    onClick = { viewModel.dismissNagware() },
                                    enabled = viewModel.nagwareCountdown <= 0
                                ) {
                                    Text(
                                        if (viewModel.nagwareCountdown > 0) "Maybe Later (${viewModel.nagwareCountdown}s)" else "Maybe Later",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(onClick = { viewModel.dismissNagware() }) {
                                    Icon(Icons.Default.Close, "Dismiss", tint = Color.Black)
                                }
                            }
                        }
                    }
                }

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
                                             label = "Height ($unitLabel)",
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

                                    OrientationSelector(viewModel)

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
                                            viewModel.generatePoster(context) {
                                                saveLauncher.launch("poster_${System.currentTimeMillis()}.pdf")
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
    val context = LocalContext.current
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
                    val dims = com.pdfposter.ui.components.parsePaperSize(selectionOption)
                    DropdownMenuItem(
                        text = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (dims != null) {
                                    com.pdfposter.ui.components.PaperGraphic(
                                        widthInches = dims.first,
                                        heightInches = dims.second,
                                        boxSize = 32.dp,
                                        selected = (viewModel.paperSize == selectionOption)
                                    )
                                } else {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(32.dp))
                                }
                                Spacer(Modifier.width(12.dp))
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
fun OrientationSelector(viewModel: MainViewModel) {
    val context = LocalContext.current
    val dims = com.pdfposter.ui.components.parsePaperSize(viewModel.paperSize)
        ?: ((viewModel.customPaperWidth.toDoubleOrNull() ?: 8.5) to
            (viewModel.customPaperHeight.toDoubleOrNull() ?: 11.0))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Orientation", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Best Fit", "Portrait", "Landscape").forEach { orient ->
                val isSelected = viewModel.orientation == orient
                val bg = if (isSelected) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.surfaceVariant
                val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary
                         else MaterialTheme.colorScheme.onSurfaceVariant
                val displayOrient = when (orient) {
                    "Portrait" -> "Portrait"
                    "Landscape" -> "Landscape"
                    else -> null // Best Fit - show natural paper orientation
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(bg)
                        .clickable {
                            viewModel.orientation = orient
                            viewModel.logEvent(context, "Orientation changed", "value=$orient")
                            viewModel.saveAllSettings()
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    com.pdfposter.ui.components.PaperGraphic(
                        widthInches = dims.first,
                        heightInches = dims.second,
                        boxSize = 56.dp,
                        orientation = displayOrient,
                        showDogCow = (orient != "Best Fit"),
                        selected = isSelected,
                        relativeScale = false
                    )
                    Text(
                        orient,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun AdvancedOptionsSection(viewModel: MainViewModel) {
    val context = LocalContext.current
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
                    OutlineStyleDropdown(
                        selection = viewModel.outlineSelection,
                        onSelectionChange = {
                            viewModel.outlineSelection = it
                            viewModel.logEvent(context, "Outline selection changed", "value=$it")
                            viewModel.saveAllSettings()
                        }
                    )

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

val OUTLINE_OPTIONS = listOf(
    "None",
    "Solid Thin", "Solid Medium", "Solid Heavy",
    "Dashed Thin", "Dashed Medium", "Dashed Heavy",
    "Dotted Thin", "Dotted Medium", "Dotted Heavy",
    "Crop Marks"
)

@Composable
fun OutlineStyleDropdown(
    selection: String,
    onSelectionChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clickable { expanded = !expanded },
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinePreview(selection = selection, fullWidth = true, compact = false)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            OUTLINE_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { OutlinePreview(selection = option, fullWidth = true, compact = false) },
                    onClick = {
                        onSelectionChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun OutlinePreview(selection: String, fullWidth: Boolean = false, compact: Boolean = true) {
    if (selection == "None") {
        // Show the word "None" instead of a preview line
        Box(
            modifier = if (fullWidth) Modifier
                .fillMaxWidth()
                .height(if (compact) 32.dp else 88.dp)
            else Modifier.size(width = 96.dp, height = 28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                "None",
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        return
    }

    // Exaggerated preview line thicknesses so user can clearly distinguish Thin / Medium / Heavy
    val thickness = when {
        selection.startsWith("Crop Marks") -> if (compact) 3f else 8f
        selection.endsWith("Thin") -> if (compact) 2.5f else 8f
        selection.endsWith("Heavy") -> if (compact) 10f else 26f
        else -> if (compact) 6f else 16f
    }
    // Exaggerated dash/dot patterns
    val pathEffect = when {
        selection.startsWith("Dashed") -> PathEffect.dashPathEffect(
            if (compact) floatArrayOf(24f, 10f) else floatArrayOf(44f, 18f),
            0f
        )
        selection.startsWith("Dotted") -> PathEffect.dashPathEffect(
            if (compact) floatArrayOf(1f, 12f) else floatArrayOf(2f, 20f),
            0f
        )
        else -> null
    }
    Canvas(
        modifier = (if (fullWidth) Modifier
            .fillMaxWidth()
            .height(if (compact) 36.dp else 96.dp)
        else Modifier.size(width = 96.dp, height = 28.dp))
            .padding(horizontal = if (compact) 4.dp else 8.dp)
    ) {
        if (selection.startsWith("Crop Marks")) {
            val pad = if (compact) 2f else 6f
            val arm = if (compact) 8f else 26f
            val sw = if (compact) 2f else 6f
            // four corner L marks
            drawLine(Color.Black, Offset(pad, pad + arm), Offset(pad, pad), sw)
            drawLine(Color.Black, Offset(pad, pad), Offset(pad + arm, pad), sw)

            drawLine(Color.Black, Offset(size.width - pad - arm, pad), Offset(size.width - pad, pad), sw)
            drawLine(Color.Black, Offset(size.width - pad, pad), Offset(size.width - pad, pad + arm), sw)

            drawLine(Color.Black, Offset(pad, size.height - pad - arm), Offset(pad, size.height - pad), sw)
            drawLine(Color.Black, Offset(pad, size.height - pad), Offset(pad + arm, size.height - pad), sw)

            drawLine(Color.Black, Offset(size.width - pad - arm, size.height - pad), Offset(size.width - pad, size.height - pad), sw)
            drawLine(Color.Black, Offset(size.width - pad, size.height - pad - arm), Offset(size.width - pad, size.height - pad), sw)
        } else {
            drawLine(
                color = Color.Black,
                start = Offset(4f, size.height / 2),
                end = Offset(size.width - 4f, size.height / 2),
                strokeWidth = thickness,
                pathEffect = pathEffect,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
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
fun HistorySection(viewModel: MainViewModel) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshHistory() }) {
                Icon(Icons.Default.Refresh, "Refresh history", modifier = Modifier.size(20.dp))
            }
        }

        when {
            viewModel.isHistoryLoading -> {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            viewModel.historyItems.isEmpty() -> {
                Text(
                    "No posters yet. Generate one — it'll appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            else -> {
                Column {
                    viewModel.historyItems.take(8).forEach { item ->
                        HistoryRow(item)
                    }
                    if (viewModel.historyItems.size > 8) {
                        Text(
                            "+ ${viewModel.historyItems.size - 8} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryRow(item: com.pdfposter.data.backend.HistoryItem) {
    val icon = when (item.type) {
        "upscale_local", "upscale_remote" -> Icons.Default.AutoAwesome
        else -> Icons.Default.PictureAsPdf
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fileNameFromUri(item.localUri).ifEmpty { item.id.ifEmpty { item.type } },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Text(
                item.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun fileNameFromUri(uri: String): String =
    uri.substringAfterLast('/').substringBefore('?')

@Composable
fun AccountSection(viewModel: MainViewModel, onSignInClick: () -> Unit) {
    val s = viewModel.authSession
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        when {
            !s.signedIn -> Text(
                "Offline — Firebase not reachable. PDFs still work locally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            s.isAnonymous -> {
                Text(
                    "Signed in anonymously. Sign in with Google to keep your history across devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSignInClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Login, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in with Google")
                }
            }
            else -> {
                Text(s.displayName ?: s.email ?: "Signed in", style = MaterialTheme.typography.bodyMedium)
                s.email?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.signOut() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out")
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
