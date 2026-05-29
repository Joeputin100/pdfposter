package com.posterpdf

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.VideoView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.LiveHelp
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.posterpdf.ui.components.AccountAvatarMenu
import com.posterpdf.ui.components.CloudSpeedWarningModal
import com.posterpdf.ui.components.OfflineBlockDialog
import com.posterpdf.ui.components.CreditBadge
import com.posterpdf.ui.components.CreditChip
import com.posterpdf.ui.components.GeminiQaSheet
import com.posterpdf.ui.components.GlassCard
import com.posterpdf.ui.components.glassBackdrop
import com.posterpdf.ui.components.glintEffect
import com.posterpdf.ui.components.pulseEffect
import com.posterpdf.ui.components.ImagePickerHeader
import com.posterpdf.ui.components.LowDpiUpgradeModal
import com.posterpdf.ui.components.ManageAccountDialog
import com.posterpdf.ui.components.PaperSizeCardRow
import com.posterpdf.ui.components.PosterPreview
import com.posterpdf.ui.components.PurchaseSheet
import com.posterpdf.ui.components.UnitsToggleCard
import com.posterpdf.ui.screens.CreditsHistoryScreen
import com.posterpdf.ui.screens.FaqScreen
import com.posterpdf.ui.screens.GettingStartedScreen
import com.posterpdf.ui.screens.HelpScreen
import com.posterpdf.ui.screens.HistoryScreen
import com.posterpdf.ui.screens.PrivacyPolicyScreen
import com.posterpdf.ui.theme.PDFPosterTheme
import com.posterpdf.ui.util.Hapt
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val POST_NOTIFICATIONS_REQUEST_CODE = 4711
    /**
     * RC21: JankStats handle so we can stop tracking on Activity tear-down.
     * Initialized in onCreate after setContent so the window is attached.
     */
    private var jankStats: androidx.metrics.performance.JankStats? = null
    private var lastJankCustomKeyMs: Long = 0L

    // RC66: class-level handle on the same Activity-scoped MainViewModel
    // that MainScreen's viewModel() resolves to. Used by the share-target
    // intent handler so we can call updateImage() from onCreate/onNewIntent.
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // RC8: install a global UncaughtExceptionHandler that writes the
        // stack trace to the debug log BEFORE the JVM dies. Crash diagnosis
        // was previously impossible without adb because the prior
        // logEvent() coroutine never got a chance to run during a crash.
        // We chain through the prior handler so the system still shows
        // its default "App keeps stopping" dialog.
        run {
            val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
            val appCtx = applicationContext
            Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
                try {
                    val sw = java.io.StringWriter()
                    ex.printStackTrace(java.io.PrintWriter(sw))
                    val ts = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US,
                    ).format(java.util.Date())
                    MainViewModel.writeLogLineSync(
                        appCtx,
                        "$ts - CRASH on ${thread.name}: ${ex.javaClass.simpleName}: " +
                            "${ex.message}\n$sw\n",
                    )
                } catch (_: Throwable) { /* best-effort */ }
                priorHandler?.uncaughtException(thread, ex)
            }
        }
        // Edge-to-edge: required for Android 15 (API 35), where system bars
        // draw over content by default unless the app opts in. Must run
        // before super.onCreate.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // RC66: handle a share/open-with that cold-launched the app.
        handleIncomingShareIntent(intent)
        // RC69/73: CI test-only deep link (screenshot capture + upscale
        // device-test). Gated by BuildConfig.ENABLE_TEST_HOOKS — true for the
        // debug + benchmark variants, false for shipping release builds.
        if (com.posterpdf.BuildConfig.ENABLE_TEST_HOOKS) {
            val screenshotExtra = intent?.getStringExtra("screenshot")
            android.util.Log.i("UPSCALE_TEST", "debug hook fired, screenshot extra=$screenshotExtra")
            when (screenshotExtra) {
                "compare" -> viewModel.showUpscaleComparison = true
                "model_picker" -> {
                    // Seed a bundled test image so the picker has content, then open it.
                    viewModel.seedScreenshotImage(this)
                    viewModel.showLowDpiModal = true
                }
                "upscale_test" -> {
                    // RC73: FTL on-device upscale→PDF timing test. Skip the
                    // first-run wizard (belt-and-suspenders; the screenshot
                    // launch already suppresses splash) and run the headless
                    // pipeline driver.
                    // RC76: `--es upscale_mode small|large|cpu` (default small)
                    // selects the scenario — see runUpscaleAndPdfDeviceTest.
                    viewModel.isFirstRun = false
                    val upscaleMode = intent?.getStringExtra("upscale_mode") ?: "small"
                    viewModel.runUpscaleAndPdfDeviceTest(this, upscaleMode)
                }
                // RC77: open dense screens for the UX edge-case screenshot
                // matrix (font360 / cutout / flip). getting_started is a plain
                // flag; settings opens the nav drawer via its initialValue
                // (read below, before setContent paints — see drawerState).
                "getting_started" -> viewModel.showGettingStarted = true
                "settings" -> viewModel.openSettingsForScreenshot = true
                else -> { /* main: no-op */ }
            }
        }
        // RC11: request POST_NOTIFICATIONS on Android 13+ so the upscale
        // foreground service can surface its progress notification (and
        // future billing alerts can land too). Best-effort — if user
        // denies, the service still runs without a visible notification.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    POST_NOTIFICATIONS_REQUEST_CODE,
                )
            }
        }
        setContent {
            PDFPosterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    com.posterpdf.ui.components.BackdropBlur(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // RC66: skip splash when share/Open-with launched the app — the user
                        // came here intending to act on an image, splash is friction.
                        // RC69: skip splash for share launches AND for CI
                        // screenshot launches (--es screenshot <state>) so the
                        // captured frame shows real UI, not the splash video.
                        var showSplash by remember {
                            mutableStateOf(
                                !isShareLaunch(intent) &&
                                    !(com.posterpdf.BuildConfig.ENABLE_TEST_HOOKS &&
                                        intent?.getStringExtra("screenshot") != null),
                            )
                        }
                        if (showSplash) {
                            SplashScreen { showSplash = false }
                        } else {
                            MainScreen()
                        }
                    }
                }
            }
        }

        // RC21: JankStats — track janky frames and feed the slowest one
        // (per-minute throttled) into Crashlytics custom keys. NOT recorded
        // as exceptions because jank isn't a crash and would otherwise
        // flood Crashlytics. The custom keys ride along on a future actual
        // crash so triage gets recent jank data for free.
        jankStats = androidx.metrics.performance.JankStats.createAndTrack(window) { frameData ->
            if (!frameData.isJank) return@createAndTrack
            val now = System.currentTimeMillis()
            if (now - lastJankCustomKeyMs < 60_000L) return@createAndTrack
            lastJankCustomKeyMs = now
            try {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("last_jank_ui_ns", frameData.frameDurationUiNanos)
                    setCustomKey("last_jank_state_count", frameData.states.size)
                }
            } catch (_: Throwable) {
                // Crashlytics not initialized in test/stub builds — ignore.
            }
        }
    }

    override fun onDestroy() {
        jankStats?.isTrackingEnabled = false
        jankStats = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // RC66: with launchMode=singleTask, a share while running routes
        // here instead of onCreate. Update the Activity's stored intent
        // (some Compose state observers read it) and forward to the
        // shared handler.
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun isShareLaunch(intent: Intent?): Boolean {
        if (intent == null) return false
        val mime = intent.type ?: return false
        if (!mime.startsWith("image/")) return false
        return intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (!isShareLaunch(intent)) return
        val uri: Uri? = when (intent!!.action) {
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            // RC66: existing entry point — copies bytes to app-private
            // storage so we survive the share-grant expiring.
            viewModel.updateImage(this, uri)
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
        // RC69: Compare no longer drives a full-screen swap; it renders as a
        // bottom-docked drawer inside the Scaffold body (see MainScreenContent).
        viewModel.showHistoryScreen -> "history"
        viewModel.showCreditsHistory -> "credits_history"
        viewModel.showGettingStarted -> "getting_started"
        viewModel.showHelp -> "help"
        viewModel.showFaq -> "faq"
        viewModel.showPrivacy -> "privacy"
        viewModel.showSupport -> "support"
        viewModel.showCommunity && viewModel.composingCommunityPost -> "community_compose"
        viewModel.showCommunity && viewModel.selectedCommunityPostId != null -> "community_post"
        viewModel.showCommunity -> "community"
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
                "credits_history" to 2,
                "compare" to 3,
                "getting_started" to 4,
                "help" to 5,
                "faq" to 6,
                "privacy" to 7,
                "support" to 8,
                "community" to 9,
                "community_post" to 10,
                "community_compose" to 11,
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
            // RC69: "compare" branch removed — Compare renders as a docked drawer
            // inside the Scaffold body (MainScreenContent) so the top bar stays
            // visible and tappable.
            "history" -> {
                BackHandler { viewModel.showHistoryScreen = false }
                HistoryScreen(viewModel = viewModel, onBack = { viewModel.showHistoryScreen = false })
            }
            "credits_history" -> {
                BackHandler { viewModel.showCreditsHistory = false }
                CreditsHistoryScreen(viewModel = viewModel, onBack = { viewModel.showCreditsHistory = false })
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
            "support" -> {
                BackHandler { viewModel.showSupport = false }
                com.posterpdf.ui.screens.SupportScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.showSupport = false },
                )
            }
            "community" -> {
                BackHandler { viewModel.showCommunity = false }
                com.posterpdf.ui.screens.CommunityScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.showCommunity = false },
                    onOpenPost = { id -> viewModel.selectedCommunityPostId = id },
                    onCompose = { viewModel.composingCommunityPost = true },
                )
            }
            "community_post" -> {
                BackHandler { viewModel.selectedCommunityPostId = null }
                com.posterpdf.ui.screens.CommunityPostScreen(
                    viewModel = viewModel,
                    postId = viewModel.selectedCommunityPostId.orEmpty(),
                    onBack = { viewModel.selectedCommunityPostId = null },
                )
            }
            "community_compose" -> {
                BackHandler { viewModel.composingCommunityPost = false }
                com.posterpdf.ui.screens.CommunityComposeScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.composingCommunityPost = false },
                )
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
    // RC77: the UX screenshot harness opens Settings by seeding the drawer's
    // initial value (the hook sets this flag in onCreate, before setContent),
    // so the drawer is already open on first paint — no LaunchedEffect race.
    val drawerState = rememberDrawerState(
        initialValue = if (viewModel.openSettingsForScreenshot) DrawerValue.Open else DrawerValue.Closed,
    )
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

    // RC69: live credit balance is observed in MainViewModel.creditBalance
    // (Firestore snapshot listener); read it directly at each call site.
    var showPurchaseSheet by remember { mutableStateOf(false) }
    var showGeminiSheet by remember { mutableStateOf(false) }

    // Handle back button to close drawer
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    // RC69: dismiss the body-docked drawers on back.
    BackHandler(enabled = viewModel.showUpscaleComparison) {
        viewModel.showUpscaleComparison = false
    }
    BackHandler(enabled = viewModel.showLowDpiModal) {
        viewModel.showLowDpiModal = false
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

    // RC13 — free-upscale progress dialog rebuilt to read tile-level
    // progress from the ViewModel (same source the notification pill
    // uses). Earlier RC4/RC7/RC10 versions ran a benchmark-based ETA
    // estimate that diverged from actual completion; the user reported
    // pill at 11% (16/1474 tiles) while modal said 63% / 63 s left.
    //
    // New logic:
    //   • progress fraction = done / total (ground truth, no asymptote)
    //   • ETA = (elapsedSinceStart / done) × (total - done), once at
    //     least one tile has completed (so we have a real per-tile rate).
    //     Self-correcting on slow devices, no benchmark needed.
    //   • elapsed/remaining formatted with prettyDuration() so a
    //     242-second value reads as "4 minutes 2 seconds" not "242 s".
    if (viewModel.isFreeUpscaling) {
        val done = viewModel.freeUpscaleTilesDone
        val total = viewModel.freeUpscaleTotalTiles
        val startMs = viewModel.freeUpscaleStartMs
        var elapsedMs by remember { mutableLongStateOf(0L) }
        // RC16: smoothed ETA. The instantaneous formula (elapsed/done × remaining)
        // jittered between high and low values as tiles ran at different speeds
        // (the user reported "~3 min at 33%, then ~2:30 at 60% — needs to be a
        // lot better and smoothed"). We low-pass with α=0.15: smoothed_eta is
        // a slow chase of the instantaneous reading. Once 100% complete we
        // also show a dedicated "Saving…" state because the PNG write tail
        // takes a few seconds the user noticed as a "paused at 100%" beat.
        var smoothedRemainingMs by remember { mutableLongStateOf(0L) }
        // RC18: read viewModel state INSIDE the loop, not via captured
        // outer-scope `done`/`total`. Pre-RC18 the LaunchedEffect lambda
        // captured Compose State READS at composition (a snapshot, not a
        // live subscription), so `done` was stuck at 0 for the lifetime
        // of the dialog, the inner if-condition stayed false, and
        // smoothedRemainingMs never updated. The text fell through to
        // "Almost done…" for the entire 7-min upscale (user report).
        LaunchedEffect(viewModel.isFreeUpscaling) {
            while (viewModel.isFreeUpscaling) {
                val curDone = viewModel.freeUpscaleTilesDone
                val curTotal = viewModel.freeUpscaleTotalTiles
                elapsedMs = System.currentTimeMillis() - startMs
                if (curDone > 0 && curDone < curTotal) {
                    val instantRemaining = elapsedMs * (curTotal - curDone) / curDone
                    smoothedRemainingMs = if (smoothedRemainingMs == 0L) instantRemaining
                    else (smoothedRemainingMs * 85 + instantRemaining * 15) / 100
                }
                kotlinx.coroutines.delay(500)
            }
        }
        val progress = if (total > 0) {
            (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else 0f
        AlertDialog(
            // RC31: glass treatment shared across progress/warning/failure
            // modals. Transparent container + 0 elevation + glassBackdrop
            // modifier on the same shape clips the dialog to a 28dp rounded
            // rectangle and draws the BackdropBlur layer underneath. Pre-API-31
            // and dialogs whose composition can't reach the backdrop layer
            // fall back to the translucent-gradient path inside glassBackdrop.
            modifier = Modifier.glassBackdrop(
                shape = RoundedCornerShape(28.dp),
                tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            onDismissRequest = { /* not dismissable — must Cancel or wait */ },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            title = { Text(stringResource(R.string.upscale_free_dialog_title)) },
            text = {
                Column {
                    val pct = (progress * 100).toInt()
                    val isSaving = total > 0 && done >= total
                    // RC16: separate lines per user request — time on top,
                    // tile counter underneath.
                    Text(
                        text = when {
                            total == 0 -> stringResource(R.string.free_upscale_preparing)
                            isSaving -> stringResource(R.string.free_upscale_saving)
                            smoothedRemainingMs > 0L -> stringResource(R.string.free_upscale_about_left, prettyDuration(context, smoothedRemainingMs))
                            else -> stringResource(R.string.free_upscale_almost_done)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (total > 0 && !isSaving) {
                        Text(
                            text = stringResource(R.string.free_upscale_progress_inline, pct, done, total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
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

    // RC76 — ">10 minutes, are you sure?" confirmation, shown before a long
    // on-device upscale actually starts (gateLongJob in MainViewModel). The
    // memory net (oneBandFits) is a separate, silent steer-to-cloud that sets
    // errorMessage instead of popping a dialog.
    if (viewModel.showLongJobConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLongJob() },
            title = { Text(stringResource(R.string.upscale_long_job_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.upscale_long_job_msg,
                        viewModel.pendingUpscaleEtaMin,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmLongJob() }) {
                    Text(stringResource(R.string.upscale_long_job_proceed))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLongJob() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // Cloud-gate (2026-05-29) — pre-flight connection & upload-speed gate for
    // CLOUD upscales (FAL / Imagen). gateCloudUpload in MainViewModel probes
    // first, then flips one of these two flags. Mirrors the showLongJobConfirm
    // pattern above (the on-device equivalent). The slow warning is dismissible
    // (Continue / Cancel); offline is a hard block (Got it) that steers to the
    // free on-device upscale.
    if (viewModel.showCloudSpeedWarn) {
        CloudSpeedWarningModal(
            etaRange = viewModel.pendingCloudUploadEta,
            onContinue = { viewModel.confirmCloudUpload() },
            onCancel = { viewModel.dismissCloudUpload() },
        )
    }
    if (viewModel.showOfflineBlock) {
        OfflineBlockDialog(
            onDismiss = { viewModel.dismissOfflineBlock() },
        )
    }

    // RC19 — AI upscale progress dialog. Mirrors the free-upscale dialog
    // pattern but reads aiUpscalePhase / aiUpscaleProgress directly from
    // the ViewModel (the repository does its own phase + fraction tracking).
    if (viewModel.isAiUpscaling) {
        AlertDialog(
            // RC31: glass treatment (see free-upscale dialog above).
            modifier = Modifier.glassBackdrop(
                shape = RoundedCornerShape(28.dp),
                tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            onDismissRequest = { /* not dismissable — must Cancel or wait */ },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            title = { Text(stringResource(R.string.upscale_ai_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = viewModel.aiUpscalePhase.ifEmpty { stringResource(R.string.vm_phase_starting) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // RC21: detail line — "Queue position 3", "Processing image",
                    // etc. when the backend has populated queuePosition. Hidden
                    // during the upload / download / save phases where there's
                    // no FAL queue to report.
                    val detail = viewModel.aiUpscaleDetail
                    if (!detail.isNullOrEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // RC24: indeterminate progress bar while we're waiting in
                    // queue WITHOUT a position estimate. As soon as the backend
                    // reports a queuePosition (detail starts with "Queue
                    // position"), or the job moves into download/save/etc.,
                    // switch to the determinate fraction. The phase string is
                    // "Waiting in queue…" while IN_QUEUE; pre-RC24 the bar sat
                    // at 55 % (the placeholder fraction) the whole time, which
                    // read as "stuck" rather than "waiting."
                    val phaseText = viewModel.aiUpscalePhase
                    val isIndeterminate = phaseText.startsWith("Waiting in queue") &&
                        viewModel.aiUpscaleDetail?.startsWith("Queue position") != true
                    if (isIndeterminate) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { viewModel.aiUpscaleProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (!isIndeterminate) {
                        Text(
                            text = "${(viewModel.aiUpscaleProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.cancelAiUpscale() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // RC24 — AI upscale failure dialog. When runAiUpscale's onFailure
    // path sets aiUpscaleFailure (mutually exclusive with isAiUpscaling
    // because of the `finally` block), show a modal that:
    //   • Apologizes + names the failure
    //   • Reassures the user their credits were refunded by the backend
    //     (refundAndFail in upscale.ts handles this atomically)
    //   • Offers Retry (re-run the same upscale) or Close (dismiss).
    val failureMsg = viewModel.aiUpscaleFailure
    if (!viewModel.isAiUpscaling && failureMsg != null) {
        AlertDialog(
            // RC31: glass treatment.
            modifier = Modifier.glassBackdrop(
                shape = RoundedCornerShape(28.dp),
                tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            onDismissRequest = { viewModel.aiUpscaleFailure = null },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            title = { Text(stringResource(R.string.upscale_failed_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.upscale_failed_dialog_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.upscale_failed_dialog_details, failureMsg),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.retryAiUpscale(context) }) {
                    Text(stringResource(R.string.common_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.aiUpscaleFailure = null }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }

    // RC27 — below-target-DPI confirm gate. requestAiUpscale() projects the
    // chosen model's max output (4× scale, capped at the model's long-edge
    // limit) and surfaces this dialog when the projected DPI on the user's
    // poster size won't reach their target. Three of the four FAL models
    // are 4×-locked, and Recraft additionally caps the long edge at 4096 px,
    // so a low-res source on a large poster genuinely can't hit 150 DPI —
    // the user should know that before paying for the run.
    viewModel.aiUpscaleConfirm?.let { confirm ->
        AlertDialog(
            // RC31: glass treatment.
            modifier = Modifier.glassBackdrop(
                shape = RoundedCornerShape(28.dp),
                tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            onDismissRequest = { viewModel.cancelAiUpscaleConfirm() },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            title = { Text(stringResource(R.string.upscale_confirm_below_dpi_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.upscale_confirm_below_dpi_body, confirm.displayName, confirm.effectiveDpi, confirm.targetDpi),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.upscale_confirm_below_dpi_detail, confirm.outputW, confirm.outputH),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmAiUpscaleAfterWarning(context) }) {
                    Text(stringResource(R.string.common_continue_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelAiUpscaleConfirm() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showGeminiSheet) {
        GeminiQaSheet(
            state = viewModel.geminiQaState,
            suggestions = listOf(
                stringResource(R.string.gemini_qa_suggestion_paper),
                stringResource(R.string.gemini_qa_suggestion_sharp),
                stringResource(R.string.gemini_qa_suggestion_model),
            ),
            onSendPrompt = { prompt ->
                viewModel.askGemini(prompt, viewModel.debugCreditOverride ?: viewModel.creditBalance)
            },
            onDismiss = {
                showGeminiSheet = false
                viewModel.resetGeminiQaState()
            },
        )
    }

    if (showPurchaseSheet) {
        PurchaseSheet(
            balance = viewModel.creditBalance,
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

    // RC35: Manage Account modal — credit balance, profile, upgrade,
    // DANGER ZONE (erase). Opened from the top-bar account-pfp menu.
    if (viewModel.showManageAccount) {
        ManageAccountDialog(
            viewModel = viewModel,
            creditBalance = viewModel.debugCreditOverride ?: viewModel.creditBalance,
            onDismiss = { viewModel.showManageAccount = false },
            onUpgrade = {
                viewModel.showManageAccount = false
                showPurchaseSheet = true
            },
            onCreditsHistory = {
                viewModel.showManageAccount = false
                viewModel.showCreditsHistory = true
            },
            onEraseAccount = {
                viewModel.showManageAccount = false
                viewModel.logEvent(context, "Account erase confirmed")
                // Backend wipe lands in a follow-up RC; for now we
                // sign the user out locally so subsequent sessions
                // are decoupled from the previous identity.
                viewModel.signOut()
            },
        )
    }



    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.Transparent,
                drawerTonalElevation = 0.dp,
                // RC32: full-screen-width drawer per user request — overrides
                // M3's default ~360dp pinning. fillMaxWidth() makes the sheet
                // span the viewport on phones, foldables, and tablets.
                // RC33: scroll moved off the sheet modifier and onto the
                // inner Box so we can constrain content width (otherwise
                // settings rows stretched to full tablet width and looked
                // bad). Box aligns the constrained Column to TopStart.
                modifier = Modifier
                    .fillMaxWidth()
                    .glassBackdrop(
                        shape = DrawerDefaults.shape,
                        tint = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.82f),
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopStart,
                ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(),
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

                  // RC36: debug set-credit / spend-credit UI removed (RC33
                  // shipped it for digiflip testing; user confirmed the
                  // animation works in RC35 so the controls aren't needed).
                  // The viewModel.debugCreditOverride field stays so we can
                  // re-introduce a similar affordance later without churning
                  // the data model.

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

                  // RC8: "Share debug log" — uses FileProvider to share the
                  // log file via ACTION_SEND so the user can submit it
                  // (email, Drive, Gmail, anything that takes a text/plain
                  // attachment). Without this, the log existed only inside
                  // the app's external-files dir which most file managers
                  // can't browse on Android 11+ scoped storage.
                  ListItem(
                      headlineContent = { Text(stringResource(R.string.drawer_share_debug_log)) },
                      supportingContent = {
                          val f = MainViewModel.debugLogFile(context)
                          Text(
                              text = if (f != null) stringResource(R.string.drawer_share_debug_subtitle_size, (f.length() / 1024).toInt())
                              else stringResource(R.string.drawer_share_debug_subtitle_empty),
                              style = MaterialTheme.typography.bodySmall,
                          )
                      },
                      leadingContent = { Icon(Icons.Default.Share, null) },
                      modifier = Modifier.clickable {
                          val f = MainViewModel.debugLogFile(context)
                          if (f != null) {
                              val uri = androidx.core.content.FileProvider.getUriForFile(
                                  context,
                                  "${context.packageName}.provider",
                                  f,
                              )
                              val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                  type = "text/plain"
                                  putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                  putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.drawer_debug_log_email_subject))
                                  addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                              }
                              context.startActivity(
                                  android.content.Intent.createChooser(send, context.getString(R.string.drawer_share_debug_chooser)),
                              )
                          }
                      },
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

                 // RC5 — language picker. Drives the per-app locale via
                 // AppCompatDelegate.setApplicationLocales (API 33+ uses the
                 // platform LocaleManager under the hood; older Android falls
                 // back to a Configuration override the AppCompat delegate
                 // handles internally). Persists across app restarts. Pass an
                 // empty list tag for "System default".
                 //
                 // RC8: replaced the plain "Language" label with a FlowRow of
                 // the word "Language" in all 10 supported locales, with the
                 // currently active locale rendered larger + accented. The
                 // user wanted to be able to read what locale is selected
                 // without opening the dialog.
                 var showLanguageDialog by remember { mutableStateOf(false) }
                 LanguageDrawerCell(
                     onClick = {
                         viewModel.logEvent(context, "Language picker opened")
                         showLanguageDialog = true
                     },
                 )
                 if (showLanguageDialog) {
                     com.posterpdf.ui.components.LanguagePickerDialog(
                         onDismiss = { showLanguageDialog = false },
                         onPick = { tag ->
                             // tag = "" → system default
                             val locales = if (tag.isEmpty()) {
                                 androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                             } else {
                                 androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                             }
                             // RC19: seamless locale switch (no Activity recreate).
                             // We added `locale` to AndroidManifest configChanges,
                             // so the system delivers onConfigurationChanged in
                             // place instead of destroying + recreating the
                             // Activity. Compose's LocalConfiguration provider
                             // automatically updates on that callback, so any
                             // composables that read stringResource() recompose
                             // with the new translations on the next frame —
                             // without losing in-progress state (poster size,
                             // selected image, scroll position, etc.).
                             //
                             // RC17 had to call activity.recreate() because
                             // locale was NOT in configChanges, so the system
                             // would destroy the Activity on locale change
                             // anyway. Now that we handle it, the recreate is
                             // not just unnecessary — it's actively harmful
                             // (loses state, restarts to splash).
                             androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
                             if (android.os.Build.VERSION.SDK_INT >= 33) {
                                 val lm = context.getSystemService(android.app.LocaleManager::class.java)
                                 lm?.applicationLocales = if (tag.isEmpty()) {
                                     android.os.LocaleList.getEmptyLocaleList()
                                 } else {
                                     android.os.LocaleList.forLanguageTags(tag)
                                 }
                             }
                             viewModel.logEvent(context, "language_picked", "tag=$tag")
                             showLanguageDialog = false
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
                             // RC20: render in the user's chosen unit so the
                             // label matches the chip on the main page.
                             stringResource(
                                 R.string.drawer_target_print_resolution,
                                 viewModel.currentResolutionUnitLabel,
                                 viewModel.targetDpiDisplay,
                             ),
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
                         // RC20: copy is unit-agnostic — the slider stores DPI canonically
                         // but the printer + comparison values need the right unit so they
                         // make sense in either mode.
                         if (viewModel.units == "Metric")
                             stringResource(R.string.drawer_target_dpi_help_metric)
                         else
                             stringResource(R.string.drawer_target_dpi_help),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                     )
                 }

                 // H-P2.1 / H-P2.2 / H-P2.3 / H-P2.4 / H-P2.5: content drawer entries.
                 NavigationDrawerItem(
                     label = { Text(stringResource(R.string.drawer_getting_started_label)) },
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
                     label = { Text(stringResource(R.string.drawer_help)) },
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
                     label = { Text(stringResource(R.string.drawer_faq)) },
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
                     label = { Text(stringResource(R.string.drawer_privacy_policy)) },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Privacy Policy opened")
                         viewModel.showPrivacy = true
                         scope.launch { drawerState.close() }
                     },
                     icon = { Icon(Icons.Default.PrivacyTip, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )
                 // RC17: Support / feedback form. Opt-in diagnostic payload
                 // submitted to Firestore /support/{auto-id}.
                 // RC43: merged the separate "Support" GitHub-issues entry
                 // into this single item — both feedback and support requests
                 // now flow through the in-app form (Firestore ticket system),
                 // so the user has one obvious place to write us.
                 NavigationDrawerItem(
                     label = { Text(stringResource(R.string.drawer_send_feedback)) },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Support opened")
                         viewModel.showSupport = true
                         scope.launch { drawerState.close() }
                     },
                     icon = { Icon(Icons.AutoMirrored.Filled.Send, null) },
                     modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                 )
                 // RC44: Community board — public-read discussion across
                 // 4 topics (Release Notes / Feature Requests / Troubleshooting
                 // / Discussion). Anyone can read; non-anonymous users can
                 // post and reply. Release-Notes posting is admin-gated.
                 NavigationDrawerItem(
                     label = { Text(stringResource(R.string.drawer_community)) },
                     selected = false,
                     onClick = {
                         viewModel.logEvent(context, "Community opened")
                         viewModel.showCommunity = true
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
                // RC14 — version footer so the user can verify which RC they
                // installed when reporting bugs. BuildConfig.VERSION_NAME comes
                // from app/build.gradle.kts defaultConfig.versionName, which
                // we now bump per RC.
                Text(
                    stringResource(R.string.drawer_build_label, com.posterpdf.BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                }  // RC33: close inner Column (max-width content)
                }  // RC33: close outer Box (scroll + TopStart alignment)
            }
        }
    ) {
        Scaffold(
            topBar = {
                // RC35: OpenArt-style top bar — hamburger | halftone logo |
                // credit chip (coin + balance + Upgrade) | account-pfp menu.
                // Replaces the legacy CenterAlignedTopAppBar whose Badge
                // clipped at 99 credits and didn't expose the account menu
                // the user wanted. Built as a custom Surface row so we can
                // place arbitrary widgets in the actions slot without the
                // MD3 TopAppBar measurement constraints.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    // RC68: split the top bar into two rows — row 1 keeps the
                    // wordmark + credit chip + avatar, row 2 is a full-width
                    // 'Ask Gemini' affordance with icon + label. RC66/67's
                    // single-row layout was too crowded after the sparkle
                    // button landed; moving it to its own row frees ~40dp of
                    // horizontal space and turns the Gemini CTA into a true
                    // first-class entry point.
                    Column {
                    Row(
                        // RC36: statusBarsPadding pushes the top bar below
                        // the status bar / camera cutout. Without it the
                        // custom Surface (RC35) drew under the system UI
                        // because it isn't a Material 3 TopAppBar — those
                        // get the inset for free; arbitrary Surfaces don't.
                        // RC48: also include horizontal insets via
                        // safeDrawing.only(Top + Horizontal) so the trailing
                        // account avatar doesn't get clipped by camera
                        // cutouts in portrait or overlap the system 3-button
                        // nav bar in landscape (where the back button can
                        // dock on the right side of the screen). We restrict
                        // to Top + Horizontal — adding Bottom would push
                        // every top-bar widget down by the nav bar height.
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                ),
                            )
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = {
                            viewModel.logEvent(context, "Drawer opened")
                            // RC18: auth-state diagnostic on every drawer open.
                            viewModel.logEvent(
                                context,
                                "auth_session_snapshot",
                                "signedIn=${viewModel.authSession.signedIn} " +
                                    "anon=${viewModel.authSession.isAnonymous} " +
                                    "email=${viewModel.authSession.email ?: "<null>"} " +
                                    "photoUrl=${viewModel.authSession.photoUrl ?: "<null>"}",
                            )
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.drawer_settings_cd))
                        }

                        // RC48: brand wordmark replaces the halftone Mona-Lisa
                        // logo. Text reads instantly at top-bar size; the
                        // halftone mark survives where it actually adds
                        // brand presence (the generated PDF cover page).
                        // RC56: dropped from titleLarge → titleMedium and
                        // added maxLines=1+softWrap=false. titleLarge at
                        // ExtraBold measured ~160dp intrinsic, which on
                        // 360dp portrait left no room for the trailing
                        // avatar — Row's measure-in-order behavior squeezed
                        // the last child (avatar Box) below 36dp, so the
                        // RoundedCornerShape(50) clip rendered as an ellipse.
                        // titleMedium frees ~45dp horizontally; the maxLines
                        // guard prevents wrap-eats-row in case a translation
                        // is longer.
                        Text(
                            text = stringResource(R.string.top_bar_wordmark),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                        )

                        Spacer(Modifier.weight(1f))

                        // RC56: only render the credit chip when the user
                        // actually has an account. Anonymous users have no
                        // meaningful balance (always 0) and the "Upgrade"
                        // pill on the chip needs a Google account to land
                        // anywhere useful — so the chip both wastes ~140dp
                        // of top-bar width and presents a misleading CTA
                        // for signed-out users. Hiding it here frees space
                        // for the trailing "Login / Sign Up" button to
                        // render its full label without truncation in
                        // portrait orientation.
                        val signedInForChip = viewModel.authSession.signedIn &&
                            !viewModel.authSession.isAnonymous
                        if (signedInForChip) {
                            CreditChip(
                                // RC33: debug override lets the user preview
                                // arbitrary balances (e.g. four-digit) and
                                // exercise the digiflip animation. null = real.
                                balance = viewModel.debugCreditOverride ?: viewModel.creditBalance,
                                isAdmin = viewModel.isAdmin,
                                onTapBalance = {
                                    hapt.tap()
                                    viewModel.logEvent(context, "Credit chip tapped", "balance=${viewModel.creditBalance}")
                                    showPurchaseSheet = true
                                },
                                onTapUpgrade = {
                                    hapt.tap()
                                    viewModel.logEvent(context, "Upgrade button tapped")
                                    showPurchaseSheet = true
                                },
                            )
                        }

                        AccountAvatarMenu(
                            session = viewModel.authSession,
                            onManageAccount = { viewModel.showManageAccount = true },
                            onCreationHistory = { viewModel.showHistoryScreen = true },
                            onCreditsHistory = { viewModel.showCreditsHistory = true },
                            onSignOut = {
                                viewModel.logEvent(context, "Sign out tapped")
                                viewModel.signOut()
                            },
                            onSignIn = {
                                activity?.let { signInLauncher.launch(viewModel.googleSignInIntent(it)) }
                            },
                        )
                    }

                    // RC68: row 2 — full-width 'Ask Gemini about your poster'
                    // affordance. Reuses gemini_qa_sheet_header so no new
                    // i18n strings are needed (already in all 9 locales).
                    // surfaceContainer tints just enough to visually separate
                    // from row 1 + the editor content below.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                hapt.tap()
                                viewModel.logEvent(context, "Gemini sparkle tapped")
                                showGeminiSheet = true
                            },
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.gemini_qa_sheet_header),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    }
                }
            }
        ) { padding ->
            // RC69: suppress the first-run wizard for CI screenshot launches
            // (fresh-install emulator is always first-run, which would hide
            // the UI we're capturing). DEBUG-gated; normal launches unaffected.
            val screenshotLaunch = com.posterpdf.BuildConfig.ENABLE_TEST_HOOKS &&
                (context as? android.app.Activity)?.intent?.getStringExtra("screenshot") != null
            if (viewModel.isFirstRun && !screenshotLaunch) {
                FirstRunWizard(viewModel = viewModel, onDismiss = { viewModel.saveAllSettings() })
            }

            // RC30: adaptive width wrap. On phones (<600dp width) the Column
            // fills width as before. On foldables-unfolded and tablets the
            // outer Box centers the Column and constrains it to 600dp so
            // text/buttons don't sprawl edge-to-edge across a 10" screen.
            // Phase 2 (true 2-column list/detail) is a future RC; this is
            // the minimal-risk "looks acceptable on big screens" pass.
            // RC69: padded body region. The scrolling content and the docked
            // drawers are siblings here so a drawer's body-only scrim covers the
            // scrolling content but NOT the two-row top bar above this padding.
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            // RC73: DEBUG-only FTL device-test marker. Composed as the first
            // child of the Scaffold body so it's reliably present (and on-screen
            // at the top) whenever the upscale→PDF device test has reported a
            // result. The FTL UiAutomator test finds it via By.textContains
            // "UPSCALE_TEST_DONE". Never composed in release builds.
            if (com.posterpdf.BuildConfig.ENABLE_TEST_HOOKS && viewModel.deviceTestStatus.isNotEmpty()) {
                // RC75b: the sibling scroll Box below is fillMaxSize and, as a
                // later Box child, draws ON TOP — which occluded this marker so
                // the FTL UiAutomator By.textContains could never see it. Force
                // it above with zIndex + an opaque background so it's reliably
                // visibleToUser. (The test's primary signal is now the logcat
                // marker; this keeps the on-screen path working as a fallback
                // and makes device-test screenshots legible.)
                Text(
                    text = viewModel.deviceTestStatus,
                    color = Color.Black,
                    modifier = Modifier
                        .zIndex(1f)
                        .background(Color.White),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                contentAlignment = Alignment.TopCenter,
            ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
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
                                    stringResource(R.string.nag_body_inline, viewModel.postersMadeCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Black
                                )
                                Spacer(Modifier.height(4.dp))
                                // RC37: live Play Store link. Try market:// first
                                // (opens Play Store app directly), fall back to
                                // https:// if Play isn't installed (e.g. emulator
                                // without Play Services). The actual URL stays in
                                // the string resource so locales can swap copy.
                                Text(
                                    stringResource(R.string.nag_play_store_url),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Black,
                                    modifier = Modifier.clickable {
                                        viewModel.logEvent(context, "Play Store link tapped")
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("market://details?id=com.pdfposter"),
                                        )
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.pdfposter"),
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                TextButton(
                                    onClick = { viewModel.dismissNagware() },
                                    enabled = viewModel.nagwareCountdown <= 0
                                ) {
                                    Text(
                                        if (viewModel.nagwareCountdown > 0) stringResource(R.string.nag_maybe_later_count, viewModel.nagwareCountdown) else stringResource(R.string.nag_maybe_later_plain),
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(onClick = { viewModel.dismissNagware() }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.nag_dismiss_cd), tint = Color.Black)
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
                            // RC34: SVG is vector — pixel resolution doesn't
                            // describe the source. Replace the chip with a
                            // Format/Vector badge so the user reads "the
                            // image scales to any poster size cleanly".
                            if (viewModel.sourceIsSvg) {
                                InfoChip(label = stringResource(R.string.info_chip_format), value = stringResource(R.string.info_chip_format_value_vector_svg))
                            } else {
                                InfoChip(label = stringResource(R.string.info_chip_resolution), value = meta.resolution)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InfoChip(label = stringResource(R.string.info_chip_aspect_ratio), value = meta.aspectRatioString)
                                val aspectTitle = stringResource(R.string.info_aspect_title)
                                val aspectBody = stringResource(R.string.info_aspect_body)
                                IconButton(onClick = {
                                    infoDialogContent = aspectTitle to aspectBody
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
                                            label = stringResource(R.string.poster_width_with_unit_inline, unitLabel),
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
                                             label = stringResource(R.string.poster_height_with_unit_inline, unitLabel),
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

                                    // RC8: DPI summary row — original / upscaled / target.
                                    DpiSummaryRow(viewModel = viewModel)
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
                                        usePulseEffect = viewModel.usePulseEffect,
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
                                        val marginTitle = stringResource(R.string.info_margin_title)
                                        val marginBody = stringResource(R.string.info_margin_body)
                                        val overlapTitle = stringResource(R.string.info_overlap_title)
                                        val overlapBody = stringResource(R.string.info_overlap_body)
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                             ConfigInput(
                                                 label = stringResource(R.string.margin_with_unit, unitLabel),
                                                 value = viewModel.margin,
                                                 onValueChange = {
                                                     viewModel.margin = it
                                                     viewModel.logEvent(context, "Margin changed", "value=$it")
                                                     viewModel.saveAllSettings()
                                                 },
                                                 modifier = Modifier.weight(1f)
                                             )
                                            IconButton(onClick = {
                                                infoDialogContent = marginTitle to marginBody
                                            }) { Icon(Icons.Default.Info, null, Modifier.size(18.dp)) }
                                        }
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                             ConfigInput(
                                                 label = stringResource(R.string.overlap_with_unit, unitLabel),
                                                 value = viewModel.overlap,
                                                 onValueChange = {
                                                     viewModel.overlap = it
                                                     viewModel.logEvent(context, "Overlap changed", "value=$it")
                                                     viewModel.saveAllSettings()
                                                 },
                                                 modifier = Modifier.weight(1f)
                                             )
                                            IconButton(onClick = {
                                                infoDialogContent = overlapTitle to overlapBody
                                            }) { Icon(Icons.Default.Info, null, Modifier.size(18.dp)) }
                                        }
                                    }
                                    
                                    viewModel.getPaneCount()?.let { (total, rows, cols) ->
                                        Text(
                                            stringResource(R.string.project_scope_inline, total, rows, cols),
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
                                // RC14: broadened the bypass condition — RC13
                                // only checked pendingUpscaleModelLabel; user
                                // still saw the warning fire after picking
                                // Recraft Crisp. Now also bypasses when the
                                // free upscale is actively running. Logs the
                                // state at gate-time so the next debug log
                                // tells us which condition the gate saw.
                                val pendingLabel = viewModel.pendingUpscaleModelLabel
                                val freeRunning = viewModel.isFreeUpscaling
                                val upscaleQueued = pendingLabel != null || freeRunning
                                val dpi = viewModel.computeCurrentDpi()
                                viewModel.logEvent(
                                    context,
                                    "dpi_gate: check",
                                    "dpi=$dpi pendingLabel=$pendingLabel freeRunning=$freeRunning bypass=$upscaleQueued",
                                )
                                // RC18: threshold is 150 DPI = 59.05 DPCM,
                                // computed by the ViewModel based on units.
                                // RC34: SVG sources skip the gate entirely
                                // — vector scales cleanly to any size.
                                val threshold = viewModel.lowResolutionThreshold
                                if (!upscaleQueued && !viewModel.sourceIsSvg && dpi > 0.1f && dpi < threshold) {
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
                                                    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.chooser_title_open_pdf)))
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
                                                    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.chooser_title_share_pdf)))
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
                                    // RC31: glass treatment.
                                    modifier = Modifier.glassBackdrop(
                                        shape = RoundedCornerShape(28.dp),
                                        tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    ),
                                    containerColor = Color.Transparent,
                                    tonalElevation = 0.dp,
                                    shape = RoundedCornerShape(28.dp),
                                    onDismissRequest = { lowDpiPendingAction = null },
                                    title = { Text(stringResource(R.string.low_dpi_dialog_title)) },
                                    text = {
                                        Text(
                                            stringResource(R.string.low_dpi_dialog_body_inline, viewModel.computeCurrentDpi().toInt())
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val a = action; lowDpiPendingAction = null; a()
                                        }) { Text(stringResource(R.string.low_dpi_dialog_continue)) }
                                    },
                                    dismissButton = {
                                        // RC53: "Upscale source" routes to the upscale picker
                                        // (LowDpiUpgradeModal). Pre-RC53 it just dismissed the
                                        // dialog, dropping the user back at the main screen with
                                        // no path forward — confusing because the button copy
                                        // implied an action ("Upgrade source first"). Now the
                                        // user lands directly on the upscale options.
                                        TextButton(onClick = {
                                            lowDpiPendingAction = null
                                            viewModel.showLowDpiModal = true
                                        }) {
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
            }  // RC30: Box wrapping the inner Column for adaptive max-width

            // RC69: Compare drawer, docked below the top bar.
            com.posterpdf.ui.components.DockedDrawer(
                visible = viewModel.showUpscaleComparison,
                onScrimTap = { viewModel.showUpscaleComparison = false },
            ) {
                com.posterpdf.ui.screens.UpscaleComparisonScreen(
                    onBack = { viewModel.showUpscaleComparison = false },
                )
            }

            // RC71: BringYourOwn help-dialog state hoisted OUT of the drawer
            // content. It used to live inside the DockedDrawer lambda, but
            // "Show me how" closes the picker (showLowDpiModal=false), which
            // tears down the drawer's composition and destroyed the dialog's
            // remember mid-exit-animation — the dialog flashed for ~1s then
            // vanished. Hoisting it here (and rendering it as a sibling of the
            // drawer) keeps it alive independent of the picker's visibility.
            var showBringYourOwnHelp by remember { mutableStateOf(false) }
            val byoLauncher = rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
            ) { uri ->
                if (uri != null) {
                    viewModel.updateImage(context, uri)
                    viewModel.showLowDpiModal = false
                }
            }

            // RC69: Model picker (low-DPI upgrade) drawer, relocated from
            // PosterPreview's ModalBottomSheet so it docks below the top bar.
            // Bitmap + derived params are sourced from the ViewModel.
            com.posterpdf.ui.components.DockedDrawer(
                visible = viewModel.showLowDpiModal && viewModel.sourcePreviewBitmap != null,
                onScrimTap = { viewModel.showLowDpiModal = false },
            ) {
                val src = viewModel.sourcePreviewBitmap
                if (src != null) {
                    val srcAndroid = src.asAndroidBitmap()
                    val inputMpD = (srcAndroid.width.toLong() * srcAndroid.height).toDouble() / 1_000_000.0
                    val inputBytesL = srcAndroid.byteCount.toLong()
                    val posterWInchesD = viewModel.posterWidth.toDoubleOrNull() ?: 0.0
                    val posterHInchesD = viewModel.posterHeight.toDoubleOrNull() ?: 0.0
                    val currentDpi = viewModel.computeCurrentDpi()
                    LowDpiUpgradeModal(
                        sourceBitmap = src,
                        inputMp = inputMpD,
                        inputBytes = inputBytesL,
                        currentDpi = currentDpi,
                        posterWInches = posterWInchesD,
                        posterHInches = posterHInchesD,
                        usePulseEffect = viewModel.usePulseEffect,
                        sourceIsSvg = viewModel.sourceIsSvg,
                        creditBalance = viewModel.debugCreditOverride ?: viewModel.creditBalance,
                        usdPerCredit = 0.01,
                        isAnonymous = viewModel.authSession.isAnonymous || !viewModel.authSession.signedIn,
                        isAdmin = viewModel.isAdmin,
                        targetDpi = viewModel.targetDpi,
                        onDismiss = { viewModel.showLowDpiModal = false },
                        onFreeUpscale = {
                            viewModel.showLowDpiModal = false
                            viewModel.pendingUpscaleModelLabel = "Free upscale"
                            viewModel.runFreeUpscale(context)
                        },
                        onAiUpscale = { modelId, minScale ->
                            viewModel.showLowDpiModal = false
                            viewModel.requestAiUpscale(context, modelId, minScale)
                        },
                        onPickAlreadyUpscaled = { viewModel.showLowDpiModal = false },
                        onShowBringYourOwnHelp = {
                            viewModel.showLowDpiModal = false
                            showBringYourOwnHelp = true
                        },
                        onSignIn = { viewModel.showLowDpiModal = false },
                        onBuyCredits = { viewModel.showLowDpiModal = false },
                        onCompareModels = {
                            viewModel.showLowDpiModal = false
                            viewModel.showUpscaleComparison = true
                        },
                    )
                }
            }

            // RC71: rendered as a sibling of the drawer (not inside it) so it
            // survives the picker closing when "Show me how" is tapped.
            if (showBringYourOwnHelp) {
                com.posterpdf.ui.components.BringYourOwnHelpDialog(
                    onDismiss = { showBringYourOwnHelp = false },
                    onPickAlreadyUpscaled = {
                        showBringYourOwnHelp = false
                        byoLauncher.launch("image/*")
                    },
                )
            }
            }  // RC69: padded body Box (top bar stays outside this padding)
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
        
        OnboardingStep(1, stringResource(R.string.onboarding_step1_inline))
        OnboardingStep(2, stringResource(R.string.onboarding_step2_inline))
        OnboardingStep(3, stringResource(R.string.onboarding_step3_inline))
        OnboardingStep(4, stringResource(R.string.onboarding_step4_inline))
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
                     label = stringResource(R.string.poster_width_with_unit_inline, unitLabel),
                     value = viewModel.customPaperWidth,
                     onValueChange = {
                         viewModel.customPaperWidth = it
                         viewModel.logEvent(context, "Custom paper width changed", "value=$it")
                         viewModel.saveAllSettings()
                     },
                     modifier = Modifier.weight(1f)
                 )
                 ConfigInput(
                     label = stringResource(R.string.poster_height_with_unit_inline, unitLabel),
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
                val displayLabel = when (orient) {
                    "Best Fit" -> stringResource(R.string.orientation_best_fit)
                    "Portrait" -> stringResource(R.string.orientation_portrait)
                    "Landscape" -> stringResource(R.string.orientation_landscape)
                    else -> orient
                }
                OrientationCard(
                    storageKey = orient,
                    displayLabel = displayLabel,
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
    storageKey: String,
    displayLabel: String,
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
                when (storageKey) {
                    "Portrait" -> Icon(
                        painter = painterResource(id = com.posterpdf.R.drawable.clarus_portrait),
                        contentDescription = stringResource(R.string.orientation_clarus_portrait_cd),
                        tint = dogcowTint,
                        modifier = Modifier.size(56.dp),
                    )
                    "Landscape" -> Icon(
                        painter = painterResource(id = com.posterpdf.R.drawable.clarus_landscape),
                        contentDescription = stringResource(R.string.orientation_clarus_landscape_cd),
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
                            contentDescription = stringResource(R.string.orientation_clarus_best_fit_cd),
                            tint = dogcowTint,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            Text(
                displayLabel,
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

                     // RC53: each checkbox row gets a body explainer below the
                     // label so the section is visually consistent with the
                     // Page-borders block above (title + body + control). The
                     // checkbox itself stays inline with the title; the body
                     // sits below at the same left-indent so the layout feels
                     // intentional rather than jagged.
                     Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                         Row(verticalAlignment = Alignment.CenterVertically) {
                             Checkbox(checked = viewModel.labelPanes, onCheckedChange = {
                                 viewModel.labelPanes = it
                                 viewModel.logEvent(context, "Label panes toggled", "enabled=$it")
                                 viewModel.saveAllSettings()
                             })
                             Text(
                                 stringResource(R.string.advanced_label_panes),
                                 style = MaterialTheme.typography.labelLarge,
                             )
                         }
                         Text(
                             stringResource(R.string.advanced_label_panes_body),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.padding(start = 56.dp),
                         )
                     }

                     Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                         Row(verticalAlignment = Alignment.CenterVertically) {
                             Checkbox(checked = viewModel.includeInstructions, onCheckedChange = {
                                 viewModel.includeInstructions = it
                                 viewModel.logEvent(context, "Include instructions toggled", "enabled=$it")
                                 viewModel.saveAllSettings()
                             })
                             Text(
                                 stringResource(R.string.advanced_include_instructions),
                                 style = MaterialTheme.typography.labelLarge,
                             )
                         }
                         Text(
                             stringResource(R.string.advanced_include_instructions_body),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.padding(start = 56.dp),
                         )
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
                Icon(Icons.Default.Refresh, stringResource(R.string.history_refresh_cd2), modifier = Modifier.size(20.dp))
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
                                stringResource(R.string.history_view_all_n, viewModel.historyItems.size)
                            else
                                stringResource(R.string.history_view_all_plain),
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
                        // RC13: explicit error/fallback painters so a failed
                        // remote load surfaces the icon instead of an empty
                        // 40dp gap. Without these, a failed Coil request just
                        // leaves a blank circle and the user reads the avatar
                        // as "missing." Logging in AuthRepository.toSession()
                        // confirms whether the URL is actually null vs. set.
                        val accountPainter = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                            image = Icons.Default.AccountCircle,
                        )
                        coil.compose.AsyncImage(
                            model = s.photoUrl,
                            contentDescription = stringResource(R.string.account_profile_picture_cd2),
                            error = accountPainter,
                            fallback = accountPainter,
                            placeholder = accountPainter,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape),
                        )
                    } else {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.account_profile_picture_cd2),
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(s.displayName ?: s.email ?: stringResource(R.string.account_signed_in_label), style = MaterialTheme.typography.bodyMedium)
                        s.email?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // RC12: storage usage line. Reads from
                // viewModel.storageBilling which the daily-sweep Cloud
                // Function writes per-user. Displays formatted bytes,
                // poster count, and credits charged in the current
                // billing cycle. Hidden when there\'s no billable storage
                // (no cloud-stored PDFs past the free 30-day window).
                viewModel.storageBilling?.let { sb ->
                    StorageBillingRow(sb)
                    Spacer(Modifier.height(8.dp))
                }
                // RC33: DebugPushTestRow removed — fixture isn't needed
                // now that storage-event pushes are validated end-to-end.
                OutlinedButton(onClick = { viewModel.signOut() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.account_sign_out))
                }
            }
        }
    }
}

/**
 * RC12 — storage usage display for the drawer Account section. Renders:
 *   "Storage: 4 credits this month for 50 posters · 1.2 GB used"
 * In grace period:
 *   "Storage: 50 posters · 1.2 GB · DELETION IN N HOURS — top up credits"
 */
@Composable
private fun StorageBillingRow(s: MainViewModel.StorageBillingAggregate) {
    val container: Color
    val onContainer: Color
    val text: String
    val inGrace = s.gracePeriodStartedMs != null
    if (inGrace) {
        val graceEndsMs = s.gracePeriodStartedMs + 30L * 24 * 60 * 60 * 1000
        val hoursLeft = ((graceEndsMs - System.currentTimeMillis()) / (60L * 60 * 1000))
            .coerceAtLeast(0)
        container = MaterialTheme.colorScheme.errorContainer
        onContainer = MaterialTheme.colorScheme.onErrorContainer
        text = "${s.posters} posters · ${prettyBytes(s.bytes)} · " +
            "deletion in ${hoursLeft}h — top up credits"
    } else {
        container = MaterialTheme.colorScheme.surfaceVariant
        onContainer = MaterialTheme.colorScheme.onSurfaceVariant
        text = "${s.lastBilledCredits} credits this month for ${s.posters} posters · " +
            prettyBytes(s.bytes) + " used"
    }
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CloudQueue,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.account_storage_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer.copy(alpha = 0.85f),
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
        }
    }
}

// RC33: DebugPushTestRow removed (was RC12c fixture). Storage-event
// pushes are validated end-to-end now; the test row was bloating the
// account section in widescreen mode.

/**
 * RC13: 242000 → "4 minutes 2 seconds", 65000 → "1 minute 5 seconds",
 * 30000 → "30 seconds", 3700000 → "1 hour 1 minute". Used by the
 * free-upscale progress modal so user-visible durations don't read
 * as bare second counts ("242 s" was the prior format).
 *
 * Conventions: spell out the unit in singular/plural form (one word
 * per slot) so the string is readable as a sentence. Truncates to
 * the two coarsest non-zero units; smaller granularity isn't useful
 * for ETAs.
 */
private fun prettyDuration(context: android.content.Context, ms: Long): String {
    val total = ms / 1000
    if (total < 60) return if (total == 1L)
        context.getString(R.string.duration_one_second)
    else
        context.getString(R.string.duration_n_seconds, total.toInt())
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val parts = mutableListOf<String>()
    if (hours > 0) parts.add(
        if (hours == 1L) context.getString(R.string.duration_one_hour)
        else context.getString(R.string.duration_n_hours, hours.toInt())
    )
    if (minutes > 0) parts.add(
        if (minutes == 1L) context.getString(R.string.duration_one_minute)
        else context.getString(R.string.duration_n_minutes, minutes.toInt())
    )
    if (hours == 0L && seconds > 0) {
        parts.add(
            if (seconds == 1L) context.getString(R.string.duration_one_second)
            else context.getString(R.string.duration_n_seconds, seconds.toInt())
        )
    }
    return parts.joinToString(" ")
}

/** RC12: 1.234 → "1.2 KB", 1234567 → "1.2 MB", 1.5e9 → "1.5 GB". */
private fun prettyBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024) return "%.1f KB".format(bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
    return "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
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
 * RC8 — Language picker drawer cell. Shows the word "Language" rendered
 * natively in every locale we ship strings.xml for; the currently active
 * locale's word is bigger and primary-colored. Tapping anywhere on the
 * cell opens the locale picker dialog.
 *
 * Uses AppCompatDelegate.getApplicationLocales().toLanguageTags() to
 * detect the currently active tag (empty = "system default" → fall
 * back to the device's first matched supported locale).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LanguageDrawerCell(onClick: () -> Unit) {
    // RC17: same fix as the dialog read — prefer LocaleManager on API 33+.
    // RC48: also fall back if LocaleManager returns empty even after a per-app
    // override was set via AppCompatDelegate (some devices/timing combos).
    val ctxForLocale = LocalContext.current
    val activeTag = remember {
        readActiveLocaleTag(ctxForLocale)
    }
    // Native word for "Language" per locale. Order matches the dialog.
    val words: List<Pair<String, String>> = listOf(
        "en" to "Language",
        "es" to "Idioma",
        "de" to "Sprache",
        "fr" to "Langue",
        "pt-BR" to "Idioma",
        "ru" to "Язык",
        "ja" to "言語",
        "zh-CN" to "语言",
        "hi" to "भाषा",
        "ar" to "اللغة",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Language, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.drawer_language),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp),
        ) {
            words.forEach { (tag, word) ->
                val isActive = localeTagsMatch(tag, activeTag)
                Text(
                    text = word,
                    style = if (isActive) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.labelSmall,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * RC48: detect the currently active locale tag for the per-app locale.
 * On API 33+ LocaleManager is the platform source of truth, but AppCompat
 * sometimes propagates the override before LocaleManager picks it up
 * (notably on first launch after install) — read both, prefer non-empty.
 * Returns "" when no per-app override is set (system default).
 */
internal fun readActiveLocaleTag(context: android.content.Context): String {
    val managerTag = if (android.os.Build.VERSION.SDK_INT >= 33) {
        val lm = context.getSystemService(android.app.LocaleManager::class.java)
        val l = lm?.applicationLocales
        if (l == null || l.isEmpty) "" else l.toLanguageTags().substringBefore(',')
    } else {
        ""
    }
    if (managerTag.isNotEmpty()) return managerTag
    val appCompat = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
    return if (appCompat.isEmpty) "" else appCompat.toLanguageTags().substringBefore(',')
}

/**
 * RC48: locale-tag matcher that handles "de" vs "de-DE" cleanly.
 *
 * The bug it fixes: literal-string equality on language tags broke on
 * devices where LocaleManager returned a region-suffixed form ("de-DE")
 * while the picker entries used the bare language ("de"). With nothing
 * exact-matched, the highlight fell on whichever entry happened to share
 * a word with the active language — Spanish and Portuguese-BR both
 * write "language" as "Idioma," so picking German could highlight Idioma
 * if the matcher silently failed and the FlowRow rendering fell through
 * to a default-styled first item.
 *
 * Match rule: same primary language code; if either side has a region,
 * the other must agree or be unspecified. So "de" matches "de-DE" and
 * vice versa, but "pt-BR" never matches "pt-PT".
 */
internal fun localeTagsMatch(a: String, b: String): Boolean {
    if (a.equals(b, ignoreCase = true)) return true
    if (a.isEmpty() || b.isEmpty()) return false
    val al = java.util.Locale.forLanguageTag(a)
    val bl = java.util.Locale.forLanguageTag(b)
    if (al.language.isEmpty() || al.language != bl.language) return false
    if (al.country.isEmpty() || bl.country.isEmpty()) return true
    return al.country.equals(bl.country, ignoreCase = true)
}

/**
 * RC8 — DPI summary row under the Poster Size inputs. Shows three DPI
 * values: the source image's effective DPI at the chosen poster size
 * (Original), the post-upscale DPI if a model is queued (Upscaled),
 * and the user's target DPI from the drawer slider (Target). A trailing
 * info icon opens a brief explainer dialog.
 *
 * Effective DPI = sourcePixelsWide / posterInchesWide. The target is
 * displayed even if no upscale is queued so the user always sees how
 * their original compares to where it needs to be.
 */
@Composable
private fun DpiSummaryRow(viewModel: MainViewModel) {
    val context = LocalContext.current
    val originalDpi = viewModel.computeCurrentDpi().toInt()
    // RC20: targetDpi is canonical (always DPI). Both the displayed chip
    // value and the meets-target comparison need to use the same unit as
    // originalDpi (which comes from computeCurrentDpi → native unit).
    val targetDisplay = viewModel.targetDpiDisplay
    val pendingLabel = viewModel.pendingUpscaleModelLabel
    val upscaledDpi = if (pendingLabel != null) originalDpi * 4 else null
    var showHelp by remember { mutableStateOf(false) }

    // RC16: when the source IS an already-upscaled image, label the chip
    // "Upscaled X ✓" (highlight + checkmark) instead of "Original X". The
    // user reported "main page DPI is now showing as Original 256 — should
    // be Upscaled 256 with a checkmark since 256 is higher than the target."
    val sourceIsUpscaled = viewModel.wasUpscaled
    val firstChipLabel = if (sourceIsUpscaled) stringResource(R.string.dpi_chip_upscaled) else stringResource(R.string.dpi_chip_original)
    // RC18: unit-aware threshold + label. DPI in Inches mode, DPCM in Metric.
    val unitLabel = viewModel.currentResolutionUnitLabel
    val warnThreshold = viewModel.lowResolutionThreshold.toInt()
    val firstChipMeetsTarget = originalDpi >= targetDisplay
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (originalDpi > 0) {
            DpiChip(
                label = firstChipLabel,
                dpi = originalDpi,
                unitLabel = unitLabel,
                isWarn = originalDpi < warnThreshold,
                isHighlight = sourceIsUpscaled,
                trailing = if (sourceIsUpscaled && firstChipMeetsTarget) " ✓" else "",
            )
        }
        if (upscaledDpi != null) {
            DpiChip(label = stringResource(R.string.dpi_chip_upscaled), dpi = upscaledDpi, unitLabel = unitLabel, isWarn = false, isHighlight = true)
        }
        DpiChip(label = stringResource(R.string.dpi_chip_target), dpi = targetDisplay, unitLabel = unitLabel, isWarn = false)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { showHelp = true }) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.dpi_chip_about_print_resolution_cd))
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.print_resolution_dialog_title)) },
            text = {
                Text(stringResource(R.string.print_resolution_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(R.string.common_got_it))
                }
            },
        )
    }
}

@Composable
private fun DpiChip(
    label: String,
    dpi: Int,
    isWarn: Boolean,
    isHighlight: Boolean = false,
    trailing: String = "",
    unitLabel: String = "DPI",
) {
    val container = when {
        isWarn -> MaterialTheme.colorScheme.errorContainer
        isHighlight -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when {
        isWarn -> MaterialTheme.colorScheme.onErrorContainer
        isHighlight -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.85f),
            )
            Text(
                "$dpi $unitLabel$trailing",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
        }
    }
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
private fun SharpenForPrintCta(usePulseEffect: Boolean = false, onClick: () -> Unit) {
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
        modifier = Modifier
            .fillMaxWidth()
            // RC18: clip BEFORE the pulse effect modifier so the pulse's
            // gradient sweep is bounded by the rounded-corner frame. Pre-RC18
            // the pulse painted into a square area and the sweep escaped
            // past the corners (user: "the pulse effect on the 'Sharpen
            // your image' button escapes the bounds of the button roundrect
            // corners").
            .clip(RoundedCornerShape(20.dp))
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(20.dp),
            )
            .let {
                if (usePulseEffect) it.pulseEffect(active = true)
                else it.glintEffect(active = true)
            },
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                    stringResource(R.string.sharpen_for_print_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    stringResource(R.string.sharpen_for_print_subtitle),
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
