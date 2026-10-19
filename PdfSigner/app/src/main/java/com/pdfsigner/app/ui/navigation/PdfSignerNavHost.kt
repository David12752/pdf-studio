package com.pdfsigner.app.ui.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pdfsigner.app.R
import com.pdfsigner.app.ui.screens.*
import com.pdfsigner.app.ui.viewmodel.DocumentViewerViewModel
import com.pdfsigner.app.ui.viewmodel.PdfSignerViewModel
import com.pdfsigner.app.ui.viewmodel.PdfToolsViewModel
import com.pdfsigner.app.ui.viewmodel.RemoteSignViewModel
import com.pdfsigner.app.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object Routes {
    const val HOME         = "home"
    const val PDF_PICKER   = "pdf_picker/{mode}"
    const val SIGNATURE    = "signature"
    const val PLACEMENT    = "placement"
    const val TEXT_STAMP   = "text_stamp"
    const val EXPORT       = "export"
    const val PDF_TOOLS    = "pdf_tools"
    const val SCAN_ENHANCE = "scan_enhance"
    const val SETTINGS     = "settings"
    const val DOC_VIEWER   = "doc_viewer"
    const val REMOTE_SIGN  = "remote_sign"
    const val REMOTE_SIGNER = "remote_signer/{sessionId}"

    // mode values: "sign" | "merge" | "split" | "extract" | "convert"
    fun pdfPicker(mode: String) = "pdf_picker/$mode"
    fun remoteSigner(sessionId: String) = "remote_signer/$sessionId"
}

private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.PDF_TOOLS, Routes.SETTINGS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSignerNavHost(
    incomingPdfUri: Uri? = null,
    incomingDocUri: Uri? = null,
    incomingSignerSessionId: String? = null,
    settingsVm: SettingsViewModel
) {
    val navController    = rememberNavController()
    val signerVm: PdfSignerViewModel         = viewModel()
    val toolsVm: PdfToolsViewModel           = viewModel()
    val docViewerVm: DocumentViewerViewModel = viewModel()
    val remoteSignVm: RemoteSignViewModel    = viewModel()

    val context          = LocalContext.current
    val backStackEntry   by navController.currentBackStackEntryAsState()
    val currentRoute     = backStackEntry?.destination?.route

    var pendingScannedUri by remember { mutableStateOf<Uri?>(null) }

    // ── Determine the real start destination ────────────────────────────
    // If we were launched with an external file, pre-load it into the
    // ViewModel immediately (synchronous, no coroutine needed here because
    // loadDocument only stores the URI — actual rendering is async inside it)
    // and start the NavHost directly on DOC_VIEWER so HomeScreen is NEVER
    // rendered on the first frame.
    val incomingUri = remember { incomingDocUri ?: incomingPdfUri }
    val startDestination = remember {
        when {
            incomingSignerSessionId != null -> Routes.remoteSigner(incomingSignerSessionId)
            incomingUri != null -> Routes.DOC_VIEWER
            else -> Routes.HOME
        }
    }
    // Pre-load the document before the first composition so the viewer
    // has the URI when it draws its first frame.
    remember(incomingUri) {
        if (incomingUri != null) docViewerVm.loadDocument(incomingUri)
    }

    // ── Storage permission (API 29-32) ───────────────────────────────────
    var permissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted = it }

    LaunchedEffect(Unit) {
        if (!permissionGranted && Build.VERSION.SDK_INT in 29..32)
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    // ── Cache cleanup ────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 3_600_000L
            context.cacheDir.listFiles()
                ?.filter {
                    it.isFile && it.lastModified() < cutoff &&
                    (it.name.startsWith("picker_temp_") ||
                     it.name.startsWith("pdftool_") ||
                     it.name.startsWith("sig_tmp_") ||
                     it.name.startsWith("enhanced_scan_") ||
                     it.name.startsWith("merged_output") ||
                     it.name.startsWith("extracted_") ||
                     it.name.startsWith("temp_signed_") ||
                     it.name.startsWith("signed_temp") ||
                     it.name.startsWith("watermarked_") ||
                     it.name.startsWith("compressed_") ||
                     it.name.startsWith("protected_") ||
                     it.name.startsWith("audit_"))
                }?.forEach { it.delete() }
            context.cacheDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("split_") && it.lastModified() < cutoff }
                ?.forEach { it.deleteRecursively() }
        }
    }

    // ── URI helper: always copy content:// to a local cache file ────────
    suspend fun ensureReadableUri(uri: Uri): Uri = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri
        runCatching {
            val tmp = File(
                context.cacheDir,
                "picker_temp_${System.currentTimeMillis()}_${System.nanoTime()}.pdf"
            )
            context.contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
            Uri.fromFile(tmp)
        }.getOrDefault(uri)
    }

    // ── Open a URI directly in DocumentViewer ────────────────────────────
    fun openInViewer(uri: Uri) {
        docViewerVm.loadDocument(uri)
        navController.navigate(Routes.DOC_VIEWER)
    }

    // ── Start the signing flow from a file URI ───────────────────────────
    fun startSigningFlow(scope: kotlinx.coroutines.CoroutineScope, uri: Uri) {
        scope.launch {
            val readable = ensureReadableUri(uri)
            signerVm.loadPdf(readable)
            navController.navigate(Routes.SIGNATURE) {
                popUpTo(Routes.HOME) { inclusive = false }
            }
        }
    }

    // ── Handle incoming intents (open-with / share) ──────────────────────
    // NOTE: No LaunchedEffect navigation needed — the startDestination above
    // handles it at graph construction time. A LaunchedEffect would always
    // fire AFTER the first frame (HomeScreen flash). Removed entirely.

    Scaffold(
        bottomBar = {
            if (currentRoute in BOTTOM_NAV_ROUTES) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick  = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon  = { Icon(Icons.Filled.Home, null) },
                        label = { Text(stringResource(R.string.nav_home)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.PDF_TOOLS,
                        onClick  = {
                            navController.navigate(Routes.PDF_TOOLS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon  = { Icon(Icons.Filled.Build, null) },
                        label = { Text(stringResource(R.string.nav_tools)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick  = {
                            navController.navigate(Routes.SETTINGS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon  = { Icon(Icons.Filled.Settings, null) },
                        label = { Text(stringResource(R.string.nav_settings)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController      = navController,
            startDestination   = startDestination,
            modifier           = Modifier.padding(innerPadding),
            enterTransition    = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260)) },
            exitTransition     = { slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(260)) },
            popEnterTransition = { slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(260)) },
            popExitTransition  = { slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(260)) }
        ) {

            // ── HOME ──────────────────────────────────────────────────────
            composable(Routes.HOME) {
                val scope = rememberCoroutineScope()
                HomeScreen(
                    viewModel      = signerVm,
                    // Picker for signing: open the PDF in viewer first, sign from there
                    onOpenPicker   = { navController.navigate(Routes.pdfPicker("view")) },
                    onScanComplete = { scannedUri ->
                        pendingScannedUri = scannedUri
                        navController.navigate(Routes.SCAN_ENHANCE)
                    },
                    onPdfTools     = { navController.navigate(Routes.PDF_TOOLS) },
                    // Any file opened from Home → viewer
                    onOpenFile     = { uri -> openInViewer(uri) },
                    // Recent PDF tapped → viewer
                    onSignPdf      = { uri -> openInViewer(uri) }
                )
            }

            // ── PDF PICKER ────────────────────────────────────────────────
            composable(
                route     = Routes.PDF_PICKER,
                arguments = listOf(navArgument("mode") { type = NavType.StringType })
            ) { backStack ->
                val mode  = backStack.arguments?.getString("mode") ?: "view"
                val title = when (mode) {
                    "sign" -> stringResource(R.string.pick_pdf_to_sign)
                    else   -> stringResource(R.string.pick_pdf_for_tools)
                }
                val scope = rememberCoroutineScope()

                PdfPickerScreen(
                    title    = title,
                    onPicked = { rawUri ->
                        scope.launch {
                            val uri = ensureReadableUri(rawUri)
                            when (mode) {
                                // "view" and default → open in viewer, sign button available there
                                "view" -> {
                                    navController.popBackStack()
                                    openInViewer(uri)
                                }
                                // Direct sign (only used from scan/internal flows)
                                "sign" -> {
                                    signerVm.loadPdf(uri)
                                    navController.navigate(Routes.SIGNATURE) {
                                        popUpTo(Routes.HOME) { inclusive = false }
                                    }
                                }
                                "merge" -> {
                                    toolsVm.addMergeUri(uri)
                                    navController.popBackStack()
                                }
                                "split" -> {
                                    val pageCount = withContext(Dispatchers.IO) {
                                        runCatching {
                                            context.contentResolver
                                                .openFileDescriptor(rawUri, "r")?.use { pfd ->
                                                    android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
                                                }
                                        }.getOrNull() ?: 1
                                    }
                                    toolsVm.setSplitUri(uri, pageCount)
                                    navController.popBackStack()
                                }
                                "extract" -> {
                                    val pageCount = withContext(Dispatchers.IO) {
                                        runCatching {
                                            context.contentResolver
                                                .openFileDescriptor(rawUri, "r")?.use { pfd ->
                                                    android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
                                                }
                                        }.getOrNull() ?: 1
                                    }
                                    toolsVm.setExtractUri(uri, pageCount)
                                    navController.popBackStack()
                                }
                                "convert"   -> { toolsVm.setConvertUri(uri);   navController.popBackStack() }
                                "watermark" -> { toolsVm.setWatermarkUri(uri); navController.popBackStack() }
                                "compress"  -> { toolsVm.setCompressUri(uri);  navController.popBackStack() }
                                "protect"   -> { toolsVm.setProtectUri(uri);   navController.popBackStack() }
                                "audit"     -> { toolsVm.setAuditUri(uri);     navController.popBackStack() }
                                else        -> { navController.popBackStack(); openInViewer(uri) }
                            }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            // ── SCAN ENHANCE ──────────────────────────────────────────────
            composable(Routes.SCAN_ENHANCE) {
                val uri = pendingScannedUri
                val scope = rememberCoroutineScope()
                if (uri != null) {
                    ScanEnhanceScreen(
                        scannedPdfUri = uri,
                        onConfirm     = { confirmedUri, action ->
                            pendingScannedUri = null
                            navController.popBackStack()
                            when (action) {
                                ScanAction.VIEW -> openInViewer(confirmedUri)
                                ScanAction.SIGN -> scope.launch {
                                    val readable = ensureReadableUri(confirmedUri)
                                    signerVm.loadPdf(readable)
                                    navController.navigate(Routes.SIGNATURE)
                                }
                                ScanAction.SHARE -> {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, confirmedUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, null))
                                }
                                ScanAction.MERGE -> {
                                    scope.launch {
                                        val readable = ensureReadableUri(confirmedUri)
                                        toolsVm.addMergeUri(readable)
                                        navController.navigate(Routes.PDF_TOOLS)
                                    }
                                }
                                ScanAction.COMPRESS -> {
                                    scope.launch {
                                        val readable = ensureReadableUri(confirmedUri)
                                        toolsVm.setCompressUri(readable)
                                        navController.navigate(Routes.PDF_TOOLS)
                                    }
                                }
                                ScanAction.CONVERT -> {
                                    scope.launch {
                                        val readable = ensureReadableUri(confirmedUri)
                                        toolsVm.setConvertUri(readable)
                                        navController.navigate(Routes.PDF_TOOLS)
                                    }
                                }
                            }
                        },
                        onBack = {
                            pendingScannedUri = null
                            navController.popBackStack()
                        }
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }

            // ── PDF TOOLS ─────────────────────────────────────────────────
            composable(Routes.PDF_TOOLS) {
                PdfToolsScreen(
                    toolsViewModel = toolsVm,
                    onOpenPicker   = { mode -> navController.navigate(Routes.pdfPicker(mode)) },
                    onBack         = { navController.popBackStack() },
                    onViewFile     = { uri -> openInViewer(uri) }
                )
            }

            // ── SETTINGS ─────────────────────────────────────────────────
            composable(Routes.SETTINGS) {
                SettingsScreen(viewModel = settingsVm)
            }

            // ── SIGNATURE ────────────────────────────────────────────────
            composable(Routes.SIGNATURE) {
                SignatureScreen(
                    viewModel        = signerVm,
                    onSignatureSaved = { navController.navigate(Routes.PLACEMENT) },
                    onBack           = {
                        signerVm.clearPdfDocument()
                        navController.popBackStack()
                    },
                    onHome           = {
                        signerVm.clearAllAnnotations()
                        signerVm.clearPdfDocument()
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                )
            }

            // ── PLACEMENT ────────────────────────────────────────────────
            composable(Routes.PLACEMENT) {
                PlacementScreen(
                    viewModel = signerVm,
                    onConfirm = { navController.navigate(Routes.EXPORT) },
                    onBack    = { navController.popBackStack() },
                    onAddText = { navController.navigate(Routes.TEXT_STAMP) },
                    onHome    = {
                        signerVm.clearAllAnnotations()
                        signerVm.clearPdfDocument()
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                )
            }

            // ── TEXT STAMP ───────────────────────────────────────────────
            composable(Routes.TEXT_STAMP) {
                TextStampScreen(
                    viewModel = signerVm,
                    onConfirm = {
                        navController.navigate(Routes.EXPORT) {
                            popUpTo(Routes.PLACEMENT) { inclusive = false }
                        }
                    },
                    onBack = { navController.popBackStack() },
                    onHome = {
                        signerVm.clearAllAnnotations()
                        signerVm.clearPdfDocument()
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                )
            }

            // ── EXPORT ───────────────────────────────────────────────────
            composable(Routes.EXPORT) {
                ExportScreen(
                    viewModel   = signerVm,
                    onStartOver = {
                        signerVm.clearAllAnnotations()
                        signerVm.clearPdfDocument()
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                    onShare = { uri ->
                        val mimeType = context.contentResolver.getType(uri) ?: "application/pdf"
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = mimeType
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, null
                            )
                        )
                    },
                    onHome = {
                        signerVm.clearAllAnnotations()
                        signerVm.clearPdfDocument()
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                )
            }

            // ── DOC VIEWER ───────────────────────────────────────────────
            composable(Routes.DOC_VIEWER) {
                val scope = rememberCoroutineScope()
                // If the viewer was the start destination (external file open) or
                // there's nothing meaningful to go back to, show Home button
                val isExternalLaunch = startDestination == Routes.DOC_VIEWER
                DocumentViewerScreen(
                    viewModel = docViewerVm,
                    onBack    = {
                        docViewerVm.clear()
                        if (isExternalLaunch) {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.DOC_VIEWER) { inclusive = true }
                                launchSingleTop = true
                            }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onHome = if (isExternalLaunch) {
                        {
                            docViewerVm.clear()
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.DOC_VIEWER) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    } else null,
                    onSignPdf = { uri ->
                        scope.launch {
                            val readable = ensureReadableUri(uri)
                            signerVm.loadPdf(readable)
                            navController.navigate(Routes.SIGNATURE)
                        }
                    },
                    onRemoteSign = {
                        navController.navigate(Routes.REMOTE_SIGN)
                    }
                )
            }

            // ── REMOTE SIGN (sender) ──────────────────────────────────────
            composable(Routes.REMOTE_SIGN) {
                val state = docViewerVm.uiState.collectAsState().value
                val uri   = state.uri
                val name  = state.fileName
                if (uri != null) {
                    RemoteSignScreen(
                        docUri          = uri,
                        docName         = name.ifBlank { "document.pdf" },
                        onBack          = { navController.popBackStack() },
                        onSignedDocReady = { signedUri -> openInViewer(signedUri) },
                        vm              = remoteSignVm
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }

            // ── REMOTE SIGNER (receiver deep link) ───────────────────────
            composable(
                route     = Routes.REMOTE_SIGNER,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { back ->
                val sessionId = back.arguments?.getString("sessionId") ?: ""
                val scope = rememberCoroutineScope()
                RemoteSignerScreen(
                    sessionId       = sessionId,
                    onBack          = { navController.popBackStack() },
                    onProceedToSign = { docName, sid ->
                        // Navigate to the signature canvas then return here to submit
                        scope.launch {
                            navController.navigate(Routes.SIGNATURE)
                        }
                    },
                    vm = remoteSignVm
                )
            }
        }
    }
}
