package com.posterpdf

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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.LiveHelp
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.posterpdf.ui.components.CreditBadge
import com.posterpdf.ui.components.GlassCard
import com.posterpdf.ui.components.ImagePickerHeader
import com.posterpdf.ui.components.PaperSizeCardRow
import com.posterpdf.ui.components.PosterPreview
import com.posterpdf.ui.components.PurchaseSheet
import com.posterpdf.ui.components.UnitsToggleCard
import com.posterpdf.ui.screens.FaqScreen
import com.posterpdf.ui.screens.GettingStartedScreen
import com.posterpdf.ui.screens.HelpScreen
import com.posterpdf.ui.screens.HistoryScreen
import com.posterpdf.ui.screens.PrivacyPolicyScreen
import com.posterpdf.ui.theme.PDFPosterTheme
import com.posterpdf.ui.util.Hapt
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: required for Android 15 (API 35), where system bars
        // draw over content by default unless the app opts in. Must run
        // before super.onCreate.
        enableEdgeToEdge()
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
            text = stringResource(R.string.splash_tap_to_skip),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    // Hoisted out of transitionSpec because that lambda is not @Composable —
    // MaterialTheme.motionScheme reads a CompositionLocal so it must be captured here.
    val swapSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    // Encode three mutually-exclusive screen states as a single key for the
    // AnimatedContent so any transition crossfades cleanly.
    val screenKey = when {
        viewModel.showUpscaleComparison -> "compare"
        viewModel.showHistoryScreen -> "history"
        viewModel.showGettingStarted -> "getting_started"
        viewModel.showHelp -> "help"
        viewModel.showFaq -> "faq"
        viewModel.showPrivacy -> "privacy"
        else -> "main"
    }
    AnimatedContent(
        targetState = screenKey,
        transitionSpec = {
            // History is "to the right" of main; compare is "to the right" of history.
            // Slide direction picks based on the lexical screen ordering.
            val order = mapOf(
                "main" to 0,
                "history" to 1,
                "compare" to 2,
                "getting_started" to 3,
                "help" to 4,
                "faq" to 5,
                "privacy" to 6,
            )
            val from = order[initialState] ?: 0
            val to = order[targetState] ?: 0
            if (to > from) {
                (slideInHorizontally(swapSpec) { it } + fadeIn())
                    .togetherWith(slideOutHorizontally(swapSpec) { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally(swapSpec) { -it / 4 } + fadeIn())
                    .togetherWith(slideOutHorizontally(swapSpec) { it } + fadeOut())
            }
        },
        label = "screen-swap",
    ) { key ->
        when (key) {
            "compare" -> {
                BackHandler { viewModel.showUpscaleComparison = false }
                com.posterpdf.ui.screens.UpscaleComparisonScreen(
                    onBack = { viewModel.showUpscaleComparison = false },
                )
            }
            "history" -> {
                BackHandler { viewModel.showHistoryScreen = false }
                HistoryScreen(viewModel = viewModel, onBack = { viewModel.showHistoryScreen = false })
            }
            "getting_started" -> {
                BackHandler { viewModel.showGettingStarted = false }
                GettingStartedScreen(onBack = { viewModel.showGettingStarted = false })
            }
            "help" -> {
                BackHandler { viewModel.showHelp = false }
                HelpScreen(onBack = { viewModel.showHelp = false })
            }
            "faq" -> {
                BackHandler { viewModel.showFaq = false }
                FaqScreen(onBack = { viewModel.showFaq = false })
            }
            "privacy" -> {
                BackHandler { viewModel.showPrivacy = false }
                PrivacyPolicyScreen(onBack = { viewModel.showPrivacy = false })
            }
            else -> MainScreenContent(viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainScreenContent(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val hapt = Hapt(LocalHapticFeedback.current)

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

    // RC3+: credit balance — admin shows ∞; non-admin shows the real Firestore
    // balance (still 0 placeholder until G12 wires the live observer).
    var creditBalance by remember { mutableStateOf(0) }
    var showPurchaseSheet by remember { mutableStateOf(false) }

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
            confirmButton = { TextButton(onClick = { infoDialogContent = null }) { Text(stringResource(R.string.common_ok)) } }
        )
    }

    // RC4 — free-upscale progress dialog. The on-device ESRGAN runs ~30-90s
    // on a typical phone; users were tapping "Upscale free" then seeing the
    // pre-upscale low-DPI warning still on-screen and assuming nothing
    // happened. This dialog blocks the main UI during the upscale and lets
    // the user cancel cleanly.
    if (viewModel.isFreeUpscaling) {
        AlertDialog(
            onDismissRequest = { /* not dismissable — must Cancel or wait */ },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            title = { Text("Sharpening your photo") },
            text = {
                Column {
                    Text(
                        "The free on-device upscaler is running. This usually takes 30–90 seconds, depending on your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.cancelFreeUpscale() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showPurchaseSheet) {
        PurchaseSheet(
            balance = creditBalance,
            isAnonymous = viewModel.authSession.isAnonymous || !viewModel.authSession.signedIn,
            onDismiss = { showPurchaseSheet = false },
            onBuy = { sku ->
                // G3 will wire BillingClient. For now just log the intent
                // so we can verify the row tap fires end-to-end.
                viewModel.logEvent(context, "Credit pack tapped", "sku=$sku")
            },
            onRestore = {
                viewModel.logEvent(context, "Restore purchases tapped")
            },
            onSignInClick = {
                showPurchaseSheet = false
                activity?.let { signInLauncher.launch(viewModel.googleSignInIntent(it)) }
            },
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
                    stringResource(R.string.app_name),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )

                HistorySection(
                    viewModel = viewModel,
                    onViewAll = {
                        hapt.tap()
                        viewModel.showHistoryScreen = true
                        scope.launch { drawerState.close() }
                    },
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text(
                    stringResource(R.string.drawer_settings_header),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium
                )

                 // H-P1.2: drawer Units control replaces the Switch row with
                 // the ruler-infographic UnitsToggleCard. The supporting label
                 // sits above so the section reads as a settings item, not a
                 // freestanding card.
                 Text(
                     stringResource(R.string.drawer_units_label),
                     modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                     style = MaterialTheme.typography.labelMedium
                 )
                 Box(Modifier.padding(horizontal = 16.dp)) {
                     UnitsToggleCard(
                         selectedUnits = viewModel.units,
                         onSelect = { stored ->
                             viewModel.logEvent(context, "Units toggled", "value=$stored")
                             viewModel.toggleUnits(stored == "Metric")
                         },
                     )
                 }

                  ListItem(
                      headlineContent = { Text(stringResource(R.string.drawer_debug_logging_title)) },
                      supportingContent = { Text(stringResource(R.string.drawer_debug_logging_subtitle)) },
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
                      headlineContent = { Text(stringResource(R.string.drawer_posters_generated_title)) },
                      supportingContent = { Text(stringResource(R.string.drawer_posters_generated_subtitle)) },
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
                      stringResource(R.string.drawer_default_paper_size),
                      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                      style = MaterialTheme.typography.labelMedium
                  )
                Box(Modifier.padding(horizontal = 16.dp)) {
                    PaperSizeSelector(viewModel)
                }

                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                
                Text(stringResource(R.string.drawer_supported_file_types_label),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(stringResource(R.string.drawer_supported_file_types_value),
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(Modifier.padding(vertical = 16.dp))

                 var showStorageDialog by remember { mutableStateOf(false) }
                 NavigationDrawerItem(
                     label = { Text(stringResource(R.string.drawer_cloud_storage)) },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Cloud storage settings opened")
                         showStorageDialog = true
                     },
                     icon = { Icon(Icons.Default.CloudQueue, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )
                 if (showStorageDialog) {
                     com.posterpdf.ui.components.StorageRetentionDialog(
                         initialMode = viewModel.storageRetentionMode,
                         onDismiss = { showStorageDialog = false },
                         onConfirm = { mode ->
                             viewModel.chooseStorageRetention(mode)
                             showStorageDialog = false
                         },
                     )
                 }

                 // RC3+: target print DPI slider — drives the smallest-scale-
                 // that-meets-target upscale strategy in the backend.
                 Column(
                     modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                 ) {
                     Row(verticalAlignment = Alignment.CenterVertically) {
                         Icon(
                             Icons.Default.Print,
                             null,
                             tint = MaterialTheme.colorScheme.primary,
                             modifier = Modifier.size(20.dp),
                         )
                         Spacer(Modifier.width(12.dp))
                         Text(
                             "Target print DPI: ${viewModel.targetDpi}",
                             style = MaterialTheme.typography.labelLarge,
                         )
                     }
                     Slider(
                         value = viewModel.targetDpi.toFloat(),
                         onValueChange = { viewModel.chooseTargetDpi(it.toInt()) },
                         valueRange = 75f..1200f,
                         steps = 14, // ~75-step increments
                     )
                     Text(
                         "Higher DPI → sharper print, more upscale credits used. " +
                             "150 is standard for posters; 300 for photos; 600+ for fine art.",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                     )
                 }

                 // H-P2.1 / H-P2.2 / H-P2.3 / H-P2.4 / H-P2.5: content drawer entries.
                 NavigationDrawerItem(
                     label = { Text("Getting Started") },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Getting Started opened")
                         viewModel.showGettingStarted = true
                         scope.launch { drawerState.close() }
                     },
                     icon = { Icon(Icons.Default.RocketLaunch, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )
                 NavigationDrawerItem(
                     label = { Text("Help") },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Help opened")
                         viewModel.showHelp = true
                         scope.launch { drawerState.close() }
                     },
                     icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )
                 NavigationDrawerItem(
                     label = { Text("FAQ") },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "FAQ opened")
                         viewModel.showFaq = true
                         scope.launch { drawerState.close() }
                     },
                     icon = { Icon(Icons.AutoMirrored.Filled.LiveHelp, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )
                 NavigationDrawerItem(
                     label = { Text("Privacy Policy") },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Privacy Policy opened")
                         viewModel.showPrivacy = true
                         scope.launch { drawerState.close() }
                     },
                     icon = { Icon(Icons.Default.PrivacyTip, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )
                 NavigationDrawerItem(
                     label = { Text(stringResource(R.string.drawer_support)) },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Support tapped")
                         val intent = android.content.Intent(
                             android.content.Intent.ACTION_VIEW,
                             android.net.Uri.parse("https://github.com/Joeputin/pdfposter/issues/new/choose"),
                         )
                         try {
                             context.startActivity(intent)
                         } catch (e: android.content.ActivityNotFoundException) {
                             android.widget.Toast.makeText(
                                 context,
                                 "No browser installed to open the Support link.",
                                 android.widget.Toast.LENGTH_SHORT,
                             ).show()
                         }
                         scope.launch { drawerState.close() }
                     },
                     icon = { Icon(Icons.Default.Forum, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )

                 NavigationDrawerItem(
                     label = { Text(stringResource(R.string.drawer_reset_to_defaults)) },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Reset to defaults triggered")
                         viewModel.resetToDefaults()
                         scope.launch { drawerState.close() }
                     },
                     icon = { Icon(Icons.Default.Refresh, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

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
                        Text(stringResource(R.string.app_name),
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
                    },
                    actions = {
                        CreditBadge(
                            balance = creditBalance,
                            isAdmin = viewModel.isAdmin,
                            onClick = {
                                hapt.tap()
                                viewModel.logEvent(context, "Credit badge tapped", "balance=$creditBalance")
                                showPurchaseSheet = true
                            }
                        )
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
                EnterStagger(index = 0) {
                    ImagePickerHeader(
                        selectedImageUri = viewModel.selectedImageUri,
                        onImageSelected = {
                            viewModel.logEvent(context, "Image selected", "uri=$it")
                            viewModel.updateImage(context, it)
                        }
                    )
                }

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
                    EnterStagger(index = 1) {
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
                                Text(stringResource(R.string.nag_title),
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
                                Text(stringResource(R.string.nag_play_store_url),
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
                }

                if (viewModel.selectedImageUri == null) {
                    EnterStagger(index = 2) { OnboardingView() }
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
                            EnterStagger(index = 3) {
                            GlassCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(stringResource(R.string.poster_size_section), style = MaterialTheme.typography.labelLarge)
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
                                    
                                    // Low-DPI warning is shown once on the screen — under the
                                    // construction preview where it's tappable to open the upgrade
                                    // modal. The duplicate that lived here used to render the same
                                    // info in static amber text; the under-preview Card is more
                                    // actionable, so this site stays silent.
                                }
                            }
                            }

                            // RC4 — "Sharpen for print" CTA between Poster Size
                            // and Paper & Layout. Tappable card with a
                            // continuously-pulsing magic-wand icon. Opens the
                            // same upscale modal that the under-preview low-DPI
                            // warning opens. Hidden when the source is an SVG
                            // (vector — no upscale needed).
                            if (!viewModel.sourceIsSvg) {
                                EnterStagger(index = 3) {
                                    SharpenForPrintCta(
                                        onClick = { viewModel.showLowDpiModal = true },
                                    )
                                }
                            }

                            // 4. Paper & Layout
                            EnterStagger(index = 4) {
                            GlassCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(stringResource(R.string.paper_layout_section), style = MaterialTheme.typography.labelLarge)
                                    
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            }

                            EnterStagger(index = 5) { AdvancedOptionsSection(viewModel) }
                            EnterStagger(index = 6) { PosterPreview(viewModel) }

                            // 7. Unified Actions
                            // H-P1.9: a tap on View/Save/Share at <150 DPI shows a confirm
                            // dialog before running the action. lowDpiPendingAction holds
                            // the closure waiting for the user's choice.
                            var lowDpiPendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
                            val runWithDpiGate: ((() -> Unit) -> Unit) = { action ->
                                if (viewModel.computeCurrentDpi() in 0.1f..149.99f) {
                                    lowDpiPendingAction = action
                                } else {
                                    action()
                                }
                            }
                            EnterStagger(index = 7) {
                            if (viewModel.isGenerating) {
                                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                                    LoadingIndicator()
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // RC3 fix: chips wrap text on narrow phones. Switch to
                                    // vertical layout (icon above text) + min height that
                                    // accommodates both lines without truncation.
                                    Button(
                                        onClick = {
                                            runWithDpiGate {
                                                hapt.confirm()
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
                                        modifier = Modifier.weight(1f).height(80.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(8.dp),
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Visibility, null, Modifier.size(28.dp))
                                            Spacer(Modifier.height(4.dp))
                                            // Layer 1 i18n hardening: allow 2 lines + ellipsis so DE/FR/RU
                                            // expansion (e.g. "Anzeigen", "Просмотреть") doesn't overflow.
                                            Text(
                                                stringResource(R.string.action_view),
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            runWithDpiGate {
                                                hapt.confirm()
                                                viewModel.generatePoster(context) {
                                                    saveLauncher.launch("poster_${System.currentTimeMillis()}.pdf")
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(80.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Save, null, Modifier.size(28.dp))
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                stringResource(R.string.action_save),
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            runWithDpiGate {
                                                hapt.tap()
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
                                        modifier = Modifier.weight(1f).height(80.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Share, null, Modifier.size(28.dp))
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                stringResource(R.string.action_share),
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                            }
                            lowDpiPendingAction?.let { action ->
                                AlertDialog(
                                    onDismissRequest = { lowDpiPendingAction = null },
                                    title = { Text(stringResource(R.string.low_dpi_dialog_title)) },
                                    text = {
                                        Text(
                                            "Your poster is currently around ${viewModel.computeCurrentDpi().toInt()} DPI. " +
                                                "For a sharp print you want at least 150 DPI. Continue anyway, or upscale your image first?"
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val a = action; lowDpiPendingAction = null; a()
                                        }) { Text(stringResource(R.string.low_dpi_dialog_continue)) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { lowDpiPendingAction = null }) {
                                            Text(stringResource(R.string.low_dpi_dialog_upgrade))
                                        }
                                    },
                                )
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
        Text(stringResource(R.string.onboarding_how_to_get_started), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        
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

/**
 * Paper size selector — H-P1.1 infographic version.
 *
 * Replaces the prior horizontal ToggleButton row with a row of
 * [com.posterpdf.ui.components.PaperSizeCard]s rendering each paper size as
 * a to-scale rectangle drawable. Letter carries a sparkle star + tooltip
 * marking it as the North America default. State plumbing is unchanged:
 * the ViewModel still owns `paperSize` as the canonical label string
 * ("Letter (8.5x11)", "A4 (8.27x11.69)", "Legal (8.5x14)", "Tabloid (11x17)",
 * or "Custom").
 */
@Composable
fun PaperSizeSelector(viewModel: MainViewModel) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.paper_size_section), style = MaterialTheme.typography.bodyMedium)

        PaperSizeCardRow(
            selectedLabel = viewModel.paperSize,
            onSelect = { label ->
                if (label != viewModel.paperSize) {
                    viewModel.paperSize = label
                    viewModel.logEvent(context, "Paper size selected", "size=$label")
                    viewModel.saveAllSettings()
                }
            },
        )

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

/**
 * Orientation selector — H-P1.3 (Clarus the Dogcow restored).
 *
 * Three cards: Best Fit / Portrait / Landscape. Portrait shows Clarus
 * upright; Landscape shows Clarus rotated 90 degrees. Best Fit shows both
 * miniaturized side-by-side so the user knows the renderer will pick.
 *
 * State plumbing is identical to the prior ToggleButton-row implementation:
 * `viewModel.orientation` stores one of "Best Fit", "Portrait", "Landscape".
 */
@Composable
fun OrientationSelector(viewModel: MainViewModel) {
    val context = LocalContext.current
    val orientations = listOf("Best Fit", "Portrait", "Landscape")
    val hapt = Hapt(LocalHapticFeedback.current)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.orientation_section), style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (orient in orientations) {
                OrientationCard(
                    label = orient,
                    isSelected = viewModel.orientation == orient,
                    onClick = {
                        if (viewModel.orientation != orient) {
                            hapt.tap()
                            viewModel.orientation = orient
                            viewModel.logEvent(context, "Orientation changed", "value=$orient")
                            viewModel.saveAllSettings()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One orientation card — Clarus dogcow rotated 0 / 90 degrees, or both for
 * "Best Fit". Long-press reveals the classic "moof!" — er, no, just the
 * orientation label. Selection inflates the border + tints the surface.
 */
@Composable
private fun OrientationCard(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isSelected) 2.5.dp else 1.dp
    val containerColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else
        MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp)),
        color = containerColor,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Clarus is a single-color trace. Use Icon with onSurface tint
                // so it reads against both light and dark surfaceVariant backgrounds.
                val dogcowTint = MaterialTheme.colorScheme.onSurface
                when (label) {
                    "Portrait" -> Icon(
                        painter = painterResource(id = com.posterpdf.R.drawable.clarus_portrait),
                        contentDescription = "Portrait orientation (Clarus the Dogcow standing)",
                        tint = dogcowTint,
                        modifier = Modifier.size(56.dp),
                    )
                    "Landscape" -> Icon(
                        painter = painterResource(id = com.posterpdf.R.drawable.clarus_landscape),
                        contentDescription = "Landscape orientation (Clarus the Dogcow on its side)",
                        tint = dogcowTint,
                        modifier = Modifier.size(56.dp),
                    )
                    else -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = com.posterpdf.R.drawable.clarus_portrait),
                            contentDescription = null,
                            tint = dogcowTint,
                            modifier = Modifier.size(28.dp),
                        )
                        Icon(
                            painter = painterResource(id = com.posterpdf.R.drawable.clarus_landscape),
                            contentDescription = "Best fit orientation",
                            tint = dogcowTint,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface,
            )
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
                Text(stringResource(R.string.advanced_section), style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.advanced_borders_title),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            "Printed on each page to help you trim and align after printing. " +
                                "\"Crop Marks\" prints corner ticks showing where to cut, like a pro print shop. " +
                                "Solid/dashed/dotted draw a full border at varying weights. Pick \"None\" if you want clean edges.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                         Text(stringResource(R.string.advanced_label_panes))
                     }

                     Row(verticalAlignment = Alignment.CenterVertically) {
                         Checkbox(checked = viewModel.includeInstructions, onCheckedChange = { 
                             viewModel.includeInstructions = it
                             viewModel.logEvent(context, "Include instructions toggled", "enabled=$it")
                             viewModel.saveAllSettings() 
                         })
                         Text(stringResource(R.string.advanced_include_instructions))
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
            Text(stringResource(R.string.outline_none),
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
    // Use onSurface so the preview reads against both light and dark surfaceVariant.
    val strokeColor = MaterialTheme.colorScheme.onSurface
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
            drawLine(strokeColor, Offset(pad, pad + arm), Offset(pad, pad), sw)
            drawLine(strokeColor, Offset(pad, pad), Offset(pad + arm, pad), sw)

            drawLine(strokeColor, Offset(size.width - pad - arm, pad), Offset(size.width - pad, pad), sw)
            drawLine(strokeColor, Offset(size.width - pad, pad), Offset(size.width - pad, pad + arm), sw)

            drawLine(strokeColor, Offset(pad, size.height - pad - arm), Offset(pad, size.height - pad), sw)
            drawLine(strokeColor, Offset(pad, size.height - pad), Offset(pad + arm, size.height - pad), sw)

            drawLine(strokeColor, Offset(size.width - pad - arm, size.height - pad), Offset(size.width - pad, size.height - pad), sw)
            drawLine(strokeColor, Offset(size.width - pad, size.height - pad - arm), Offset(size.width - pad, size.height - pad), sw)
        } else {
            drawLine(
                color = strokeColor,
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
        title = { Text(stringResource(R.string.first_run_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.first_run_intro))
                
                Text(stringResource(R.string.first_run_units_label), style = MaterialTheme.typography.labelLarge)
                // H-P1.2: replaced RadioButton row with the ruler-infographic
                // UnitsToggleCard. Storage keeps the legacy "Inches" / "Metric"
                // values so MainViewModel.units and toggleUnits(...) work
                // unchanged; the user-facing label on the second card reads
                // "Centimeters".
                UnitsToggleCard(
                    selectedUnits = selectedUnits,
                    onSelect = { selectedUnits = it },
                )

                Text(stringResource(R.string.first_run_paper_label), style = MaterialTheme.typography.labelLarge)
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
            }) { Text(stringResource(R.string.first_run_get_started)) }
        }
    )
}

@Composable
fun HistorySection(viewModel: MainViewModel, onViewAll: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.help_history_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                Text(stringResource(R.string.history_empty_drawer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            else -> {
                Column {
                    viewModel.historyItems.take(5).forEach { item ->
                        HistoryRow(item)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onViewAll, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (viewModel.historyItems.size > 5)
                                "View all (${viewModel.historyItems.size})"
                            else
                                "View all",
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryRow(item: com.posterpdf.data.backend.HistoryItem) {
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
            Text(stringResource(R.string.account_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        when {
            !s.signedIn -> Text(stringResource(R.string.account_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            s.isAnonymous -> {
                Text(stringResource(R.string.account_anonymous_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSignInClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        painter = painterResource(id = com.posterpdf.R.drawable.ic_google_g),
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_sign_in_with_google))
                }
            }
            else -> {
                // RC3+: render the Google profile photo + display name in a
                // standard "avatar + name" Row. Uses Coil (already on the
                // classpath) for async loading, with an account-circle
                // fallback if photoUrl is missing or fails to load.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.photoUrl != null) {
                        coil.compose.AsyncImage(
                            model = s.photoUrl,
                            contentDescription = "Profile picture",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape),
                        )
                    } else {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Profile picture",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(s.displayName ?: s.email ?: "Signed in", style = MaterialTheme.typography.bodyMedium)
                        s.email?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.signOut() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_sign_out))
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

/**
 * RC4 — "Sharpen for print" CTA between Poster Size and Paper & Layout.
 *
 * A continuously-pulsing magic-wand card that opens the upscale modal. The
 * pulse runs even when the source is high-DPI (no warning needed) so the
 * user always knows AI sharpening is one tap away. The card uses
 * surfaceVariant + a subtle glint sweep so it reads as "tappable polish"
 * rather than "warning."
 */
@Composable
private fun SharpenForPrintCta(onClick: () -> Unit) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "sharpen_cta_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "sharpen_cta_alpha",
    )
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "🪄",
                fontSize = 26.sp,
                modifier = Modifier.alpha(pulseAlpha),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Sharpen for print",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "Free or AI upscale — pick what fits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * Wraps a top-level scroll child to slide-and-fade in on first composition,
 * staggered by 80ms * index. The LaunchedEffect keys on Unit so subsequent
 * recompositions don't re-trigger the entrance — it plays exactly once.
 */
@Composable
private fun EnterStagger(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80L * index)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        ) { it / 8 } + fadeIn(),
    ) { content() }
}
