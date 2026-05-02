package com.example.vocalapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

@UnstableApi
class MainActivity : ComponentActivity() {

    private lateinit var audioEngine: AudioEngine
    private var hasMicPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            audioEngine.startMicMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive fullscreen — hide status & nav bars, draw under cutout
        setupFullscreen()

        audioEngine = AudioEngine(this, lifecycleScope)

        hasMicPermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            VocalAppRoot(audioEngine = audioEngine, hasMicPermission = { hasMicPermission })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::audioEngine.isInitialized) audioEngine.release()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}

// =====================================================================
//  Root composable — orchestrates audio-engine state and UI
// =====================================================================

@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VocalAppRoot(
    audioEngine: AudioEngine,
    hasMicPermission: () -> Boolean
) {
    // ----- Audio engine state ----------------------------------------------------------------
    val audioFrame by audioEngine.audioFrame.collectAsState()
    val isPlaying by audioEngine.isPlaying.collectAsState()
    val isLoading by audioEngine.isLoading.collectAsState()
    val positionMs by audioEngine.positionMs.collectAsState()
    val durationMs by audioEngine.durationMs.collectAsState()
    val mode by audioEngine.mode.collectAsState()
    val isFileMode = mode == AudioEngine.Mode.FILE

    // ----- Local UI state --------------------------------------------------------------------
    var settings by remember { mutableStateOf(SpectrumSettings()) }
    LaunchedEffect(settings) { audioEngine.settings = settings }

    var analysisSettings by remember { mutableStateOf(AnalysisSettings()) }
    LaunchedEffect(analysisSettings) { audioEngine.analysisSettings = analysisSettings }

    var showSettings by remember { mutableStateOf(false) }
    var isAnalysisMode by remember { mutableStateOf(false) }
    var isAnalysisPaused by remember { mutableStateOf(false) }
    var pauseScrollOffsetMs by remember { mutableLongStateOf(0L) }

    // Mode indicator toast (shown briefly on mode switch via swipe or button)
    var showModeIndicator by remember { mutableStateOf(false) }
    var modeToastVersion by remember { mutableIntStateOf(0) }
    var lastSwipeDir by remember { mutableIntStateOf(1) }
    LaunchedEffect(modeToastVersion) {
        if (modeToastVersion > 0) {
            showModeIndicator = true
            delay(1500)
            showModeIndicator = false
        }
    }

    // Pager: page 0 = spectrum, page 1 = analysis
    val pagerState = rememberPagerState(
        initialPage = if (isAnalysisMode) 1 else 0,
        pageCount = { 2 }
    )

    // Pager → isAnalysisMode: user dragged to a new page
    LaunchedEffect(pagerState.currentPage) {
        val newAnalysis = pagerState.currentPage == 1
        if (newAnalysis != isAnalysisMode) {
            isAnalysisMode = newAnalysis
            modeToastVersion++
        }
    }

    // Spectrum-grab gesture
    var isSpectrumGrabActive by remember { mutableStateOf(false) }
    var isGrabLocked by remember { mutableStateOf(false) }
    var isDecaying by remember { mutableStateOf(false) }
    val accumulatedSpectrum = remember { FloatArray(DISPLAY_BINS) }
    var maxAccumulatedValue by remember { mutableFloatStateOf(0f) }
    var spectrumVersion by remember { mutableIntStateOf(0) }   // bumps to force redraw

    // Isolate modes + sync pager when isAnalysisMode changes via button/external trigger
    LaunchedEffect(isAnalysisMode) {
        if (isAnalysisMode) {
            isSpectrumGrabActive = false
            isGrabLocked = false
            isDecaying = false
            accumulatedSpectrum.fill(0f)
            maxAccumulatedValue = 0f
            spectrumVersion++
        } else {
            isAnalysisPaused = false
            pauseScrollOffsetMs = 0L
        }
        // isAnalysisMode → pager (button tap).
        val targetPage = if (isAnalysisMode) 1 else 0
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    val mainAlpha by animateFloatAsState(
        targetValue = if (isSpectrumGrabActive) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "mainAlpha"
    )
    val grabbedAlpha by animateFloatAsState(
        targetValue = when {
            isSpectrumGrabActive -> 1f
            isDecaying -> maxAccumulatedValue.coerceIn(0f, 1f)
            else -> 0f
        },
        animationSpec = tween(durationMillis = 175),
        label = "grabbedAlpha"
    )

    // Player-panel auto-hide
    var isPlayerPanelVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // FPS counter
    var currentFps by remember { mutableIntStateOf(0) }

    // Pitch history & note blocks (for piano roll). SnapshotStateList lets Compose
    // observe mutations natively — no manual version counter required.
    val pitchHistory: SnapshotStateList<PitchPoint> = remember { mutableStateListOf() }
    val noteBlocks: SnapshotStateList<NoteBlock> = remember { mutableStateListOf() }

    // Timeline clock. In FILE mode = positionMs. In MIC mode = virtual clock that
    // "skips over" pauses so the graph continues seamlessly after unpausing.
    var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Virtual clock for mic mode: total wall-clock time spent paused so far.
    // currentTimeMs (mic) = System.currentTimeMillis() - micClockOffset
    // Pitch points are stored with the same virtual timestamp, so there's no jump.
    var micClockOffset by remember { mutableLongStateOf(0L) }
    var pauseStartWallClock by remember { mutableLongStateOf(0L) }

    // How many ms of pitch history to keep (driven by AnalysisSettings).
    // Recomputed whenever analysisSettings changes.
    val historyWindowMs = (analysisSettings.historyWindowMinutes * 60_000f).toLong()
        .coerceAtLeast(PITCH_HISTORY_WINDOW_MS + 5_000L)

    // Camera follow (cameraCenter is what's drawn, target is where pitch wants it)
    var cameraCenter by remember { mutableFloatStateOf(60f) }       // C4
    var cameraCenterTarget by remember { mutableFloatStateOf(60f) }

    // ----- Coroutine scope for button-triggered pager animations -------------------------
    val scope = rememberCoroutineScope()
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // Reset piano-roll state so a new file starts with a clean canvas
            pitchHistory.clear()
            noteBlocks.clear()
            currentTimeMs = 0L
            audioEngine.loadFile(uri)
            isPlayerPanelVisible = true
            lastInteractionTime = System.currentTimeMillis()
        }
    }

    // ----- Subscribe to pitch events ---------------------------------------------------------
    LaunchedEffect(audioEngine) {
        audioEngine.pitchEvents.collect { point ->
            if (isAnalysisPaused) return@collect
            // In MIC mode store virtual timestamp so the timeline is continuous across
            // pauses (paused wall-clock time is subtracted via micClockOffset).
            val storedPoint = if (isFileMode) point
            else point.copy(timeMs = point.timeMs - micClockOffset)
            pitchHistory.add(storedPoint)
            val hwMs = (analysisSettings.historyWindowMinutes * 60_000f).toLong()
                .coerceAtLeast(PITCH_HISTORY_WINDOW_MS + 5_000L)
            val cutoff = storedPoint.timeMs - hwMs - 1_000L
            while (pitchHistory.isNotEmpty() && pitchHistory[0].timeMs < cutoff) {
                pitchHistory.removeAt(0)
            }
            trimOldBlocks(noteBlocks, storedPoint.timeMs, hwMs)
            extendNoteBlocks(storedPoint, noteBlocks)
            if (storedPoint.freq > 20.0) { cameraCenterTarget = storedPoint.midi }
        }
    }

    // ----- Single timeline updater + camera tween + FPS --------------------------------------
    LaunchedEffect(isFileMode, isAnalysisPaused) {
        var frameCount = 0
        var lastFpsTime = System.currentTimeMillis()
        while (isActive) {
            if (!isAnalysisPaused) {
                currentTimeMs = if (isFileMode) positionMs
                else System.currentTimeMillis() - micClockOffset
            }
            cameraCenter = cameraCenter * 0.92f + cameraCenterTarget * 0.08f
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                currentFps = frameCount; frameCount = 0; lastFpsTime = now
            }
            delay(16)
        }
    }

    // ----- Reset state when entering mic mode ------------------------------------------------
    LaunchedEffect(isFileMode) {
        if (!isFileMode) {
            micClockOffset = 0L
            currentTimeMs = System.currentTimeMillis()
            pitchHistory.clear()
            noteBlocks.clear()
            if (hasMicPermission()) { audioEngine.startMicMode() }
        }
    }

    // ----- Spectrum-grab attack/decay (only active in spectrum mode) -------------------------
    LaunchedEffect(audioFrame, isSpectrumGrabActive, isDecaying, isAnalysisMode) {
        if (isAnalysisMode) return@LaunchedEffect   // grab is OFF when in analysis mode
        val source = audioFrame.spectrum
        when {
            isSpectrumGrabActive -> {
                val attack = 0.9f
                var changed = false
                for (i in 0 until DISPLAY_BINS) {
                    val cur = source.getOrElse(i) { 0f }
                    val prev = accumulatedSpectrum[i]
                    if (cur > prev) { accumulatedSpectrum[i] = prev * (1 - attack) + cur * attack; changed = true }
                }
                if (changed) { maxAccumulatedValue = (accumulatedSpectrum.maxOrNull() ?: 0f).coerceIn(0f, 1f); spectrumVersion++ }
            }
            isDecaying -> {
                val decay = 0.80f + (settings.smoothingFactor * 0.19f)
                var allZero = true
                for (i in 0 until DISPLAY_BINS) {
                    accumulatedSpectrum[i] *= decay
                    if (accumulatedSpectrum[i] < 0.01f) accumulatedSpectrum[i] = 0f
                    if (accumulatedSpectrum[i] > 0f) allZero = false
                }
                maxAccumulatedValue = (accumulatedSpectrum.maxOrNull() ?: 0f).coerceIn(0f, 1f)
                spectrumVersion++
                if (allZero) { isDecaying = false; maxAccumulatedValue = 0f }
            }
        }
    }

    // Grab snapshot when grab toggles ON (spectrum mode only)
    LaunchedEffect(isSpectrumGrabActive) {
        if (isAnalysisMode) return@LaunchedEffect
        if (isSpectrumGrabActive) {
            if (!isDecaying) {
                val src = audioFrame.spectrum
                for (i in 0 until DISPLAY_BINS) { accumulatedSpectrum[i] = src.getOrElse(i) { 0f } }
                maxAccumulatedValue = (accumulatedSpectrum.maxOrNull() ?: 0f).coerceIn(0f, 1f)
                spectrumVersion++
            }
            isDecaying = false
        } else {
            isDecaying = true
        }
    }

    // ----- Player-panel auto-hide ------------------------------------------------------------
    LaunchedEffect(Unit) {
        while (isActive) {
            val now = System.currentTimeMillis()
            if (!isSpectrumGrabActive &&
                isPlayerPanelVisible &&
                now - lastInteractionTime > 2000
            ) {
                isPlayerPanelVisible = false
            }
            delay(150)
        }
    }
    LaunchedEffect(isLoading, isFileMode) {
        if (!isLoading && isFileMode) {
            isPlayerPanelVisible = true
            lastInteractionTime = System.currentTimeMillis()
        }
    }

    // ----- Cleanup ---------------------------------------------------------------------------
    DisposableEffect(Unit) {
        onDispose { /* AudioEngine is released in Activity.onDestroy */ }
    }

    // =========================================================================================
    //  UI tree
    // =========================================================================================

    if (!hasMicPermission() && !isFileMode) {
        PermissionDeniedScreen()
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // ── Unified tap / swipe / hold ─────────────────────────────────────────
            // All three gestures start with awaitFirstDown. Within the first 250ms:
            //   • Significant horizontal movement → SWIPE → switch mode
            //   • Finger lifts with minimal movement → TAP → toggle player panel
            //   • 250ms elapse without lift or swipe → HOLD:
            //       spectrum mode: activate grab
            //       analysis mode (not paused): nothing (hold reserved for future)
            .pointerInput(isAnalysisMode, isAnalysisPaused) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var totalX = 0f
                    var totalY = 0f
                    var lifted = false
                    var timedOut = false

                    try {
                        withTimeout(250L) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c  = ev.changes.firstOrNull { it.id == down.id } ?: break
                                val delta = c.position - c.previousPosition
                                totalX += delta.x
                                totalY += delta.y
                                if (!c.pressed) { lifted = true; break }
                                // If the pager has clearly taken this drag, step aside
                                if (abs(totalX) > 24f && abs(totalX) > abs(totalY) * 1.5f) break
                            }
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        timedOut = true
                    }

                    when {
                        // ── HOLD in spectrum mode (minimal horizontal movement) → grab ──
                        timedOut && !isAnalysisMode && abs(totalX) < 30f -> {
                            isSpectrumGrabActive = true
                            lastInteractionTime = System.currentTimeMillis()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c  = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                            }
                            if (!isGrabLocked) {
                                isSpectrumGrabActive = false
                                isDecaying = true
                            }
                        }

                        // ── TAP (minimal movement) → toggle player panel ─────────────
                        lifted && abs(totalX) < 20f && abs(totalY) < 20f -> {
                            lastInteractionTime = System.currentTimeMillis()
                            isPlayerPanelVisible = !isPlayerPanelVisible
                        }
                    }
                }
            }
    ) {
        // ----- Main visualisation (HorizontalPager = gallery-style interactive drag) ----------
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !(isAnalysisMode && isAnalysisPaused),
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> {
                    @Suppress("UNUSED_EXPRESSION") spectrumVersion
                    ProfessionalSpectrumDisplay(
                        spectrumData = audioFrame.spectrum,
                        accumulatedSpectrum = accumulatedSpectrum,
                        mainAlpha = mainAlpha,
                        grabbedAlpha = grabbedAlpha,
                        settings = settings,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D0D0D))
                            // Pause-scroll handler lives HERE (inside analysis page) so it
                            // never blocks HorizontalPager when not paused. detectDragGestures
                            // consumes events, so keeping it on the outer Box would prevent
                            // the pager from receiving any touch input.
                            .pointerInput(isAnalysisPaused, analysisSettings) {
                                if (isAnalysisPaused) {
                                    detectDragGestures { _, dragAmount ->
                                        val hwMs = (analysisSettings.historyWindowMinutes * 60_000f).toLong()
                                            .coerceAtLeast(PITCH_HISTORY_WINDOW_MS + 5_000L)
                                        val msPerPx = PITCH_HISTORY_WINDOW_MS.toFloat() / size.width
                                        pauseScrollOffsetMs =
                                            (pauseScrollOffsetMs + (dragAmount.x * msPerPx).toLong())
                                                .coerceIn(0L, (hwMs - PITCH_HISTORY_WINDOW_MS).coerceAtLeast(0L))
                                        val midiPerPx = 12f / size.height
                                        val newCenter = (cameraCenter + dragAmount.y * midiPerPx).coerceIn(12f, 115f)
                                        cameraCenter = newCenter
                                        cameraCenterTarget = newCenter
                                    }
                                }
                                // When not paused: block exits immediately, events pass
                                // through freely to HorizontalPager.
                            }
                    ) {
                        PitchRollDisplay(
                            historyProvider = { pitchHistory },
                            blocksProvider = { noteBlocks },
                            cameraCenterProvider = { cameraCenter },
                            currentTimeMsProvider = { currentTimeMs },
                            scrollOffsetMs = pauseScrollOffsetMs,
                            isPaused = isAnalysisPaused,
                            modifier = Modifier.fillMaxSize()
                        )
                        AnalysisOverlay(
                            noteName = audioFrame.noteName,
                            frequency = audioFrame.frequency,
                            isPaused = isAnalysisPaused,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                        AnalysisPauseButton(
                            isPaused = isAnalysisPaused,
                            onToggle = {
                                if (!isAnalysisPaused) {
                                    pauseStartWallClock = System.currentTimeMillis()
                                    isAnalysisPaused = true
                                } else {
                                    if (!isFileMode) {
                                        micClockOffset += System.currentTimeMillis() - pauseStartWallClock
                                    }
                                    pauseScrollOffsetMs = 0L
                                    isAnalysisPaused = false
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                        )
                    }
                }
            }
        }

        // ----- Lock toggle (visible only when grab is active) -------------------------------
        if (isSpectrumGrabActive || isGrabLocked) {
            IconButton(
                onClick = {
                    isGrabLocked = !isGrabLocked
                    if (!isGrabLocked) {
                        isSpectrumGrabActive = false
                        isDecaying = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .size(32.dp)
            ) {
                Icon(
                    imageVector = if (isGrabLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Lock grabbed spectrum",
                    tint = Color.White
                )
            }
        }

        // ----- Mode indicator toast (top-center) --------------------------------------------
        // analysisProgress: 0.0 = fully spectrum, 1.0 = fully analysis.
        // Formula: currentPage + offsetFraction gives a continuous 0..1 value:
        //   page=0, offset=0.0  → 0.0 (settled on spectrum)
        //   page=0, offset=0.5  → 0.5 (halfway to analysis)
        //   page=1, offset=-0.5 → 0.5 (same halfway, continued — no jump!)
        //   page=1, offset=0.0  → 1.0 (settled on analysis)
        val analysisProgress = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
            .coerceIn(0f, 1f)
        ModeIndicatorToast(
            analysisProgress = analysisProgress,
            visible = showModeIndicator || pagerState.isScrollInProgress,
            modifier = Modifier.align(Alignment.Center)
        )

        // ----- Top-right controls -----------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            // ── Mode icons (both always visible; active = cyan) ──────────────────
            val spectrumIconColor by animateColorAsState(
                targetValue = if (!isAnalysisMode) Color(0xFF00E5FF) else Color(0xFF666666),
                animationSpec = tween(300), label = "specBtn"
            )
            val analysisIconColor by animateColorAsState(
                targetValue = if (isAnalysisMode) Color(0xFF00E5FF) else Color(0xFF666666),
                animationSpec = tween(300), label = "noteBtn"
            )
            IconButton(onClick = {
                if (isAnalysisMode) {
                    scope.launch { pagerState.animateScrollToPage(0) }
                    modeToastVersion++
                }
            }) {
                Icon(Icons.Default.Equalizer, contentDescription = "Спектр", tint = spectrumIconColor)
            }
            IconButton(onClick = {
                if (!isAnalysisMode) {
                    scope.launch { pagerState.animateScrollToPage(1) }
                    modeToastVersion++
                }
            }) {
                Icon(Icons.Default.MusicNote, contentDescription = "Ноты", tint = analysisIconColor)
            }

            // ── Settings ────────────────────────────────────────────────────────
            IconButton(onClick = {
                lastInteractionTime = System.currentTimeMillis()
                showSettings = !showSettings
            }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Настройки",
                    tint = if (showSettings) Color(0xFF00E5FF) else Color(0xFF666666)
                )
            }

            // ── File picker / mic toggle ─────────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            IconButton(onClick = {
                lastInteractionTime = System.currentTimeMillis()
                if (isFileMode) {
                    pitchHistory.clear(); noteBlocks.clear()
                    if (hasMicPermission()) audioEngine.startMicMode()
                } else {
                    filePicker.launch("*/*")
                }
            }) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Загрузить аудиофайл",
                    tint = if (isFileMode) Color(0xFF00E5FF) else Color(0xFF666666)
                )
            }
        }

        // ----- Player panel (only in file mode) ---------------------------------------------
        if (isFileMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                PlayerPanel(
                    visible = isPlayerPanelVisible || isLoading,
                    isLoading = isLoading,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onPlayPause = {
                        if (isPlaying) {
                            audioEngine.pause()
                        } else {
                            // If we're at the end of the track, jump back to start
                            if (audioEngine.isAtEnd()) audioEngine.seekTo(0L)
                            audioEngine.play()
                        }
                    },
                    onSeek = { audioEngine.seekTo(it) },
                    onUserInteraction = {
                        lastInteractionTime = System.currentTimeMillis()
                        isPlayerPanelVisible = true
                    }
                )
            }
        }

        // ----- Settings overlay (context-sensitive) -----------------------------------------
        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isAnalysisMode) {
                    AnalysisSettingsPanel(
                        settings = analysisSettings,
                        onSettingsChange = { analysisSettings = it },
                        onClose = { showSettings = false }
                    )
                } else {
                    SettingsPanel(
                        settings = settings,
                        onSettingsChange = { settings = it },
                        currentFps = currentFps,
                        onClose = { showSettings = false }
                    )
                }
            }
        }

        // ----- Loading overlay --------------------------------------------------------------
        if (isLoading && isFileMode) {
            FullScreenLoading()
        }
    }
}

// =====================================================================
//  Note-block helpers — incremental update (O(1) per pitch event)
// =====================================================================

private fun extendNoteBlocks(
    point: PitchPoint,
    blocks: SnapshotStateList<NoteBlock>
) {
    if (point.freq <= 20.0) return                  // silence — let the next non-silence open a new block
    val lastIdx = blocks.lastIndex
    val last = blocks.lastOrNull()
    if (last != null &&
        abs(point.midi - last.midi) <= NOTE_TOLERANCE_SEMITONES &&
        point.timeMs - last.endMs <= NOTE_GAP_THRESHOLD_MS
    ) {
        // Replace the last element with an updated copy so SnapshotStateList
        // notices the change and the Canvas redraws (deep mutation of
        // `last.endMs` would NOT trigger recomposition).
        blocks[lastIdx] = last.copy(endMs = point.timeMs)
    } else {
        blocks.add(
            NoteBlock(
                startMs = point.timeMs,
                endMs = point.timeMs,
                midi = point.midi,
                noteName = frequencyToNote(point.freq)
            )
        )
    }
}

private fun trimOldBlocks(
    blocks: SnapshotStateList<NoteBlock>,
    currentTimeMs: Long,
    historyWindowMs: Long = PITCH_HISTORY_WINDOW_MS
) {
    val cutoff = currentTimeMs - historyWindowMs - 1_000L
    while (blocks.isNotEmpty() && blocks[0].endMs < cutoff) {
        blocks.removeAt(0)
    }
}