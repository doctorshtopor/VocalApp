package com.example.vocalapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jtransforms.fft.DoubleFFT_1D
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.ui.graphics.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.os.Build
import android.view.WindowManager
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.mutableStateListOf


import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.*
typealias PitchDetectionFunction = (ShortArray, Int) -> PitchDetectionResult



private var audioTrack: AudioTrack? = null

// Константы для потокового декодера
private const val FILE_FFT_SIZE = 4096
private const val RING_BUFFER_CAPACITY = 10 * 44100
private const val MIC_BUFFER_CAPACITY = 8 * 4096
private const val DECODER_TIMEOUT_US = 5000L
private const val SAMPLE_RATE = 44100



// Глобальные переменные для потокового декодера
private var extractor: MediaExtractor? = null
private var decoder: MediaCodec? = null
private val ringBuffer = RingBuffer(RING_BUFFER_CAPACITY)
private val micRingBuffer = RingBuffer(MIC_BUFFER_CAPACITY)
private var decoderJob: Job? = null
private var fileFftJob: Job? = null
private var micFftJob: Job? = null
private var mediaFormat: MediaFormat? = null
private var fileSampleRate by mutableStateOf(44100)
private var pllFrequency by mutableStateOf(0.0)
private var pllConfidence by mutableStateOf(0.0)
private var pllPhase by mutableStateOf(0.0)

private var lastSmoothedPitch by mutableFloatStateOf(60f)
private var smoothedCenterMidi by mutableFloatStateOf(60f)

private var renderTime by mutableLongStateOf(System.currentTimeMillis())
private var renderTimeJob: Job? = null


// Структура для трекинга питча
data class PitchTrack(
    val frequency: Double,
    val confidence: Double,
    val timeMs: Long
)

data class PitchDetectionResult(
    val frequency: Double,
    val confidence: Double
)

data class PitchCandidate(
    val frequency: Double,
    val confidence: Double,
    val harmonicScore: Double
)

private val pitchTracks = mutableListOf<PitchTrack>()
private const val MAX_TRACKS = 100

// Переменные ExoPlayer
private lateinit var exoPlayer: ExoPlayer

// Константы для FFT и отрисовки
private const val DISPLAY_BINS = 2049

data class SpectrumSettings(
    val noiseThreshold: Float,
    val smoothingFactor: Float,
    val speedFactor: Float,
    val gain: Float,
    val dbRange: Float,
    val tilt: Float
)

val settingsFlow = MutableStateFlow(
    SpectrumSettings(
        noiseThreshold = -35f,
        smoothingFactor = 0.85f,
        speedFactor = 0.7f,
        gain = 0.05f,
        dbRange = 80f,
        tilt = 4.5f
    )
)

//data class PitchPoint(val timeMs: Long, val freq: Double, val midi: Int)
data class PitchPoint(
    val timeMs: Long,
    val freq: Double,
    val midi: Float   // ВАЖНО
)

// Добавьте этот класс в начале файла после импортов
class PitchDetector(val sampleRate: Int) {
    private val minFreq = 40f
    private val maxFreq = 900f

    fun detect(frame: ShortArray): Float {
        if (frame.size < sampleRate / minFreq) return 0f

        val x = frame.map { it / 32768f }.toFloatArray()
        val filtered = bandPass(x)
        val nccf = nccf(filtered)

        val minLag = (sampleRate / maxFreq).toInt()
        val maxLag = (sampleRate / minFreq).toInt()

        var bestLag = -1
        var bestVal = 0f

        for (lag in minLag until maxLag) {
            if (lag > 0 && lag < nccf.size - 1) {
                if (nccf[lag] > 0.6f &&
                    nccf[lag] > bestVal &&
                    nccf[lag] > nccf[lag - 1] &&
                    nccf[lag] > nccf[lag + 1]
                ) {
                    bestVal = nccf[lag]
                    bestLag = lag
                }
            }
        }

        if (bestLag <= 0) return 0f

        // octave correction
        val half = bestLag / 2
        if (half > minLag && half < nccf.size && nccf[half] > 0.9f * bestVal) {
            bestLag = half
        }

        return sampleRate.toFloat() / bestLag
    }

    private fun nccf(x: FloatArray): FloatArray {
        val size = x.size
        val out = FloatArray(size)

        for (lag in 1 until size) {
            var num = 0f
            var den = 0f
            for (i in 0 until size - lag) {
                num += x[i] * x[i + lag]
                den += x[i] * x[i] + x[i + lag] * x[i + lag]
            }
            out[lag] = if (den > 0) 2f * num / den else 0f
        }
        return out
    }

    private fun bandPass(x: FloatArray): FloatArray {
        val y = x.copyOf()

        // HPF ~40Hz
        var prev = 0f
        for (i in y.indices) {
            val cur = y[i]
            y[i] = cur - prev * 0.995f
            prev = cur
        }

        // LPF ~900Hz
        var acc = 0f
        for (i in y.indices) {
            acc += 0.1f * (y[i] - acc)
            y[i] = acc
        }

        return y
    }
}

data class NoteBlock(val startMs: Long, var endMs: Long, val midi: Float, val noteName: String)

private const val PITCH_HISTORY_WINDOW_MS = 8000L // окно времени (мс) видимое по горизонтали
private const val NOTE_TOLERANCE_SEMITONES = 0.5 // при группировке в ту же ноту



@UnstableApi
class MainActivity : ComponentActivity() {

    private var currentPlaybackPositionMs by mutableLongStateOf(0L)
    private var shouldProcessAudio by mutableStateOf(false)

    private var spectrumData by mutableStateOf(FloatArray(DISPLAY_BINS) { 0f })
    private var currentMaxDb by mutableStateOf(-80f)
    private var dominantFrequency by mutableStateOf(0.0)
    private var noteName by mutableStateOf("...")

    private var isPlayerInitialized by mutableStateOf(false)
    private var isDecoderRunning by mutableStateOf(false)

    // Переменная для синхронизации времени визуализатора с плеером
    private var visualizerSyncTimeMs = 0L

    // AtomicLong для безопасной передачи позиции из UI-потока в поток FFT
    private val atomicCurrentPlayerPosition = AtomicLong(0)
    private var positionUpdaterJob: Job? = null

    private val pitchHistory = mutableStateListOf<PitchPoint>()
    private val noteBlocks = mutableStateListOf<NoteBlock>()

    private var targetOctave: Float by mutableFloatStateOf(0.0F)


    // Для микрофона
    private var micRecordJob: Job? = null

    private var pitchDetector: PitchDetector? = null

    private var noteRefreshJob: Job? = null

    private var isNoteAnalysisPaused by mutableStateOf(false)


    private fun startNoteRefresh() {
        noteRefreshJob?.cancel()
        noteRefreshJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(50) // Обновляем каждые 50 мс
                if (!isNoteAnalysisPaused) {
                    val currentTime = timelineState.getDisplayTimeMs()
                    updateNoteBlocks(currentTime)
                }
            }
        }
    }


    /*
    private fun startNoteRefresh() {
        noteRefreshJob?.cancel()
        noteRefreshJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(50) // Обновляем каждые 50 мс даже при тишине
                val currentTime = timelineState.getDisplayTimeMs()
                updateNoteBlocks(currentTime)
            }
        }
    }

     */

    private var timeUpdateJob: Job? = null

    private fun startTimeUpdater() {
        timeUpdateJob?.cancel()
        timeUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                // Обновляем состояние времени каждые 16 мс (60 FPS)
                timelineState.update(16)
                delay(16)
            }
        }
    }

    // ДОБАВЬТЕ ЭТОТ КЛАСС В НАЧАЛО MainActivity
    private class TimelineState {
        var currentTimeMs: Long = System.currentTimeMillis()
        var isPaused: Boolean = false
        var userOffsetMs: Long = 0  // для прокрутки при паузе
        var playbackSpeed: Float = 1.0f

        fun update(realTimeDeltaMs: Long) {
            if (!isPaused) {
                currentTimeMs += (realTimeDeltaMs * playbackSpeed).toLong()
            }
        }

        fun getDisplayTimeMs(): Long {
            return currentTimeMs + userOffsetMs
        }

        fun resetTime() {
            currentTimeMs = System.currentTimeMillis() // Сбрасываем на текущее время
            userOffsetMs = 0
            isPaused = false
            playbackSpeed = 1.0f
        }

        fun resetOffset() {
            userOffsetMs = 0
        }
    }

    // ДОБАВЬТЕ ЭТИ ПЕРЕМЕННЫЕ В КЛАСС MainActivity
    private val timelineState = TimelineState()
    private var isTimelinePaused by mutableStateOf(false)
    private var timelineUpdateJob: Job? = null


    private fun startTimelineUpdater() {
        timelineUpdateJob?.cancel()
        timelineUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(16) // 60 FPS
                val currentTime = System.currentTimeMillis()
                val delta = currentTime - lastTime
                lastTime = currentTime

                if (!isTimelinePaused) {
                    timelineState.update(delta)

                    // ДОБАВЛЕНО СЮДА: Плавное движение камеры независимо от звука
                    if (abs(cameraCenterTarget - cameraCenter) > 0.01f) {
                        cameraCenter += (cameraCenterTarget - cameraCenter) * 0.1f
                    }
                }
            }
        }
    }

    private fun stopTimelineUpdater() {
        timelineUpdateJob?.cancel()
        timelineUpdateJob = null
    }
    // В классе MainActivity, замените функцию appendPitchPoint на эту версию:


    private fun appendPitchPoint(point: PitchPoint) {
        if (point.freq <= 20.0) return

        lifecycleScope.launch(Dispatchers.Main) {
            // Проверяем, не на паузе ли мы
            if (isNoteAnalysisPaused) {
                return@launch
            }

            val currentTime = timelineState.getDisplayTimeMs()

            // Создаем точку с правильным временем
            val correctedPoint = PitchPoint(currentTime, point.freq, point.midi)

            pitchHistory.add(correctedPoint)

            // Обновляем камеру только если не на паузе
            if (!isNoteAnalysisPaused) {
                updateCamera(point.midi)
                //cameraCenter += (cameraCenterTarget - cameraCenter) * 0.12f
            }

            // Очистка старых данных
            val cutoff = currentTime - PITCH_HISTORY_WINDOW_MS
            while (pitchHistory.isNotEmpty() && pitchHistory.first().timeMs < cutoff) {
                pitchHistory.removeAt(0)
            }

            updateNoteBlocks(currentTime)
        }
    }

    private fun updateNoteBlocks(now: Long) {
        if (isNoteAnalysisPaused) return
        if (pitchHistory.isEmpty()) return

        noteBlocks.clear()

        // УБРАНО: val sorted = pitchHistory.sortedBy { it.timeMs }
        // Точки уже добавляются хронологически, сортировка убивала производительность!
        val snapshot = pitchHistory.toList()
        var currentBlock: NoteBlock? = null

        for (point in snapshot) {
            if (point.freq <= 20.0) {
                currentBlock?.let { noteBlocks.add(it) }
                currentBlock = null
                continue
            }

            val roundedMidi = point.midi.roundToInt().toFloat()

            if (currentBlock == null) {
                currentBlock = NoteBlock(
                    startMs = point.timeMs,
                    endMs = point.timeMs,
                    midi = roundedMidi,
                    noteName = frequencyToNote(point.freq)
                )
            } else if (abs(roundedMidi - currentBlock.midi) <= NOTE_TOLERANCE_SEMITONES) {
                currentBlock.endMs = point.timeMs
            } else {
                noteBlocks.add(currentBlock)
                currentBlock = NoteBlock(
                    startMs = point.timeMs,
                    endMs = point.timeMs,
                    midi = roundedMidi,
                    noteName = frequencyToNote(point.freq)
                )
            }
        }

        currentBlock?.let { noteBlocks.add(it) }

        val cutoff = now - PITCH_HISTORY_WINDOW_MS
        noteBlocks.removeAll { block ->
            block.endMs < cutoff
        }
    }

    private fun cleanupResources() {
        micRecordJob?.cancel()
        micFftJob?.cancel()
        playbackJob?.cancel()
        positionUpdaterJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        cleanupDecoderResources()

        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        }
        isPlayerInitialized = false

        micRingBuffer.clear()
        Log.d("MainActivity", "Resources cleaned up")
    }

    private fun cleanupDecoderResources() {
        isDecoderRunning = false
        fileFftJob?.cancel()
        decoderJob?.cancel()
        try {
            decoder?.stop()
            decoder?.release()
            extractor?.release()
        } catch (e: Exception) {
            Log.e("Cleanup", "Error releasing media resources: ${e.message}")
        }
        decoder = null
        extractor = null
        ringBuffer.clear()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupAndShowUI()
        } else {
            setContent {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Разрешение на использование микрофона не предоставлено.",
                        color = Color.Red
                    )
                }
            }
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            micRecordJob?.cancel()
            micFftJob?.cancel()
            micRingBuffer.clear()
            audioUri = uri
            isFileMode = true
            isLoading = true
            isPlaying = false
        }
    }

    private var isFileMode by mutableStateOf(false)
    private var audioUri by mutableStateOf<Uri?>(null)
    private var isPlaying by mutableStateOf(false)
    private var isLoading by mutableStateOf(false)
    private var currentPositionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private val positionSample = AtomicLong(0)
    private var baseSample by mutableStateOf(0L)
    private var pcmByteArray by mutableStateOf<ByteArray?>(null)
    private var monoShorts by mutableStateOf<ShortArray?>(null)
    private var playbackJob by mutableStateOf<Job?>(null)

    private fun startRenderTimer() {
        renderTimeJob?.cancel()
        renderTimeJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                renderTime = System.currentTimeMillis()
                delay(16) // ~60 FPS
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        when {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                setupAndShowUI()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        startRenderTimer() // ДОБАВИТЬ ЭТУ СТРОКУ
    }



    // ==================== ФАЙЛОВЫЙ РЕЖИМ ====================

    private fun startStreamingDecoder(uri: Uri, startPositionMs: Long = 0L) = lifecycleScope.launch(Dispatchers.IO) {
        try {
            decoder?.release()
            extractor?.release()
        } catch (e: Exception) { }

        isDecoderRunning = true
        ringBuffer.clear()

        extractor = MediaExtractor().apply {
            try {
                setDataSource(this@MainActivity, uri, null)
            } catch (e: Exception) {
                Log.e("VocalApp", "Ошибка установки источника: ${e.message}")
                isDecoderRunning = false
                return@launch
            }

            for (i in 0 until trackCount) {
                val format = getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    selectTrack(i)
                    mediaFormat = format
                    break
                }
            }
            seekTo(startPositionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        val format = mediaFormat ?: run {
            isDecoderRunning = false
            return@launch
        }

        fileSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        try {
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            Log.e("VocalApp", "Ошибка создания декодера: ${e.message}")
            isDecoderRunning = false
            return@launch
        }

        val currentDecoder = decoder ?: return@launch
        val currentExtractor = extractor ?: return@launch

        var isExtractorFinished = false
        var consecutiveEmptyReads = 0

        try {
            while (isDecoderRunning && currentCoroutineContext().isActive) {
                if (ringBuffer.available() > RING_BUFFER_CAPACITY - 4096) {
                    delay(10)
                    continue
                }

                // 1. INPUT
                if (!isExtractorFinished) {
                    val inputBufferId = currentDecoder.dequeueInputBuffer(DECODER_TIMEOUT_US)
                    if (inputBufferId >= 0) {
                        val inputBuffer = currentDecoder.getInputBuffer(inputBufferId)!!
                        val sampleSize = currentExtractor.readSampleData(inputBuffer, 0)

                        if (sampleSize > 0) {
                            currentDecoder.queueInputBuffer(
                                inputBufferId, 0, sampleSize, currentExtractor.sampleTime, 0
                            )
                            currentExtractor.advance()
                            consecutiveEmptyReads = 0
                        } else {
                            consecutiveEmptyReads++
                            if (consecutiveEmptyReads > 5) {
                                currentDecoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorFinished = true
                            }
                        }
                    }
                }

                // 2. OUTPUT
                val bufferInfo = MediaCodec.BufferInfo()
                val outputBufferId = currentDecoder.dequeueOutputBuffer(bufferInfo, DECODER_TIMEOUT_US)

                if (outputBufferId >= 0) {
                    val outputBuffer = currentDecoder.getOutputBuffer(outputBufferId)!!
                    val shortBuffer: ShortBuffer = outputBuffer.asShortBuffer()

                    val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val numShorts = bufferInfo.size / 2

                    if (numShorts > 0) {
                        val pcmChunk = ShortArray(numShorts / channelCount)
                        for (i in 0 until numShorts step channelCount) {
                            pcmChunk[i / channelCount] = shortBuffer.get(i)
                        }
                        ringBuffer.write(pcmChunk)
                    }
                    currentDecoder.releaseOutputBuffer(outputBufferId, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else {
                    delay(2)
                }
            }
        } catch (e: Exception) {
            Log.e("Decoder", "Ошибка в цикле декодирования: ${e.message}")
        } finally {
            isDecoderRunning = false
        }
    }

    private var cameraCenterTarget by mutableFloatStateOf(60f)
    private var cameraCenter by mutableFloatStateOf(60f)
    private val cameraRange = 6f // Пол-октавы вверх и вниз

    private fun updateCamera(currentMidi: Float) {
        // Если нота вышла за пределы текущего диапазона камеры
        if (currentMidi > cameraCenter + cameraRange ||
            currentMidi < cameraCenter - cameraRange) {
            // Мгновенно устанавливаем новую цель
            cameraCenterTarget = currentMidi
        }
    }

    private fun fileFftFlow(): Flow<Quadruple<FloatArray, Float, Double, String>> = flow {
        val fft = DoubleFFT_1D(FILE_FFT_SIZE.toLong())
        val fftArray = DoubleArray(FILE_FFT_SIZE * 2)
        val hanningWindow = createHanningWindow(FILE_FFT_SIZE)
        var previousSpectrum = FloatArray(DISPLAY_BINS) { 0f }

        visualizerSyncTimeMs = atomicCurrentPlayerPosition.get()

        while (isFileMode && currentCoroutineContext().isActive) {
            val isPlayingLocal = shouldProcessAudio

            if (!isPlayingLocal) {
                delay(30)
                visualizerSyncTimeMs = atomicCurrentPlayerPosition.get()
                continue
            }

            val playerPosition = atomicCurrentPlayerPosition.get()


            if (kotlin.math.abs(playerPosition - visualizerSyncTimeMs) > 300) {
                visualizerSyncTimeMs = playerPosition
            }

            val timeDiffMs = playerPosition - visualizerSyncTimeMs

            if (timeDiffMs > 0) {
                val samplesToSkip = (timeDiffMs * fileSampleRate / 1000).toInt()
                val available = ringBuffer.available()
                val toConsume = min(samplesToSkip, available)

                if (toConsume > 0) {
                    ringBuffer.consume(toConsume)
                    visualizerSyncTimeMs += (toConsume * 1000 / fileSampleRate)
                }
            }

            if (ringBuffer.available() < FILE_FFT_SIZE) {
                delay(10)
                continue
            }

            val pcmBlock = ringBuffer.peek(FILE_FFT_SIZE)

            // Сохраняем копию для YIN (первые 2048 сэмплов)
            val yinData = if (pcmBlock.size >= 2048) {
                ShortArray(2048) { i -> pcmBlock[i] }
            } else {
                null
            }

            for (i in pcmBlock.indices) {
                fftArray[i] = pcmBlock[i].toDouble() / 32768.0
            }
            applyWindow(fftArray, hanningWindow)
            for (i in FILE_FFT_SIZE until fftArray.size) fftArray[i] = 0.0

            fft.realForward(fftArray)

            val timeDomainData = if (pcmBlock.size >= 2048) {
                ShortArray(2048) { i -> pcmBlock[i] }
            } else {
                null
            }

            val (spectrum, maxDb, dominantFreq, noteName) = processDoubleFftData(
                fftArray,
                FILE_FFT_SIZE,
                fileSampleRate,
                settingsFlow.value,
                previousSpectrum,
                timeDomainData
            )

            previousSpectrum = spectrum
            emit(Quadruple(spectrum, maxDb, dominantFreq, noteName))

            // Продвигаем буфер на ~16мс аудио за кадр.
            // Без этого peek() каждый раз читает одни и те же сэмплы, пока
            // sync-код не "прыгнет" вперёд — отсюда рассинхрон на MP3/WAV/видео.
            val frameSamples = (fileSampleRate * 16 / 1000).coerceAtLeast(1)
            val avail = ringBuffer.available()
            if (avail > FILE_FFT_SIZE) {
                // Оставляем не менее половины FFT-окна для следующего кадра
                val toConsume = minOf(frameSamples, avail - FILE_FFT_SIZE / 2)
                if (toConsume > 0) {
                    ringBuffer.consume(toConsume)
                    visualizerSyncTimeMs += (toConsume * 1000L / fileSampleRate)
                }
            }

            delay(16)
        }
    }.flowOn(Dispatchers.Default)

    // ==================== МИКРОФОННЫЙ РЕЖИМ ====================

    private fun micFftFlow(): Flow<Quadruple<FloatArray, Float, Double, String>> = flow {
        val fft = DoubleFFT_1D(FILE_FFT_SIZE.toLong())
        val fftArray = DoubleArray(FILE_FFT_SIZE * 2)
        val hanningWindow = DoubleArray(FILE_FFT_SIZE) { i -> 0.5 * (1.0 - cos(2.0 * PI * i / (FILE_FFT_SIZE - 1))) }
        var previousSpectrum = FloatArray(DISPLAY_BINS) { 0f }

        while (!isFileMode && currentCoroutineContext().isActive) {
            if (micRingBuffer.available() >= FILE_FFT_SIZE) {
                val pcm = micRingBuffer.peek(FILE_FFT_SIZE)
                val timeDomainData = if (pcm.size >= 2048) {
                    ShortArray(2048) { i -> pcm[i] }
                } else {
                    null
                }
                for (i in pcm.indices) fftArray[i] = pcm[i].toDouble() / 32768.0
                for (i in pcm.indices) fftArray[i] *= hanningWindow[i]
                for (i in FILE_FFT_SIZE until fftArray.size) fftArray[i] = 0.0

                fft.realForward(fftArray)
                // Всегда обрабатываем питч, даже при тишине
                if (timeDomainData != null) {
                    processPitch(timeDomainData, System.currentTimeMillis(), 44100)
                }

                val result = processDoubleFftData(fftArray, FILE_FFT_SIZE, 44100, settingsFlow.value, previousSpectrum, timeDomainData)
                previousSpectrum = result.first
                emit(result)

                val now = System.currentTimeMillis()
                if (result.third < 20.0) {
                    appendPitchPoint(PitchPoint(now, 0.0, 0f))
                }

                val samplesToConsume = (44100 * 0.016).toInt()
                val available = micRingBuffer.available()
                val toConsume = if (available > 8192) available - 4096 else samplesToConsume
                micRingBuffer.consume(min(toConsume, available))

                delay(16)
            } else {
                delay(5)
            }
        }
    }.flowOn(Dispatchers.Default)

    @SuppressLint("MissingPermission")
    private fun startMicRecording() {
        micRecordJob?.cancel()
        micRingBuffer.clear()

        micRecordJob = lifecycleScope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            val buffer = ShortArray(1024)

            try {
                record.startRecording()
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        micRingBuffer.write(buffer, 0, read)

                    }
                }
            } catch (e: Exception) {
                Log.e("Mic", "Error: ${e.message}")
            } finally {
                record.stop()
                record.release()
            }
        }
    }

    // ==================== ОБЩИЕ ФУНКЦИИ ====================

    private fun createHanningWindow(size: Int): DoubleArray {
        return DoubleArray(size) { i ->
            0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))
        }
    }



    private fun applyWindow(data: DoubleArray, window: DoubleArray) {
        for (i in data.indices) {
            if (i < window.size) {
                data[i] *= window[i]
            }
        }
    }

    private fun cubicInterpolate(y0: Double, y1: Double, y2: Double, y3: Double, mu: Double): Double {
        val a0 = y3 - y2 - y0 + y1
        val a1 = y0 - y1 - a0
        val a2 = y2 - y0
        val a3 = y1
        return a0 * mu * mu * mu + a1 * mu * mu + a2 * mu + a3
    }

    private fun processDoubleFftData(
        fftArray: DoubleArray,
        fftSize: Int,
        sampleRate: Int,
        settings: SpectrumSettings,
        previousSpectrum: FloatArray,
        timeDomainData: ShortArray? = null
    ): Quadruple<FloatArray, Float, Double, String> {
        val half = fftSize / 2
        val magnitudes = DoubleArray(half)
        var maxMagnitude = 1e-12
        var maxIndex = 0

        // 1. Вычисление магнитуд
        for (i in 0 until half) {
            val re = fftArray[2 * i]
            val im = if (2 * i + 1 < fftArray.size) fftArray[2 * i + 1] else 0.0
            val mag = sqrt(re * re + im * im)
            magnitudes[i] = mag
            if (mag > maxMagnitude && i > 0) {
                maxMagnitude = mag
                maxIndex = i
            }
        }

        // 2. Конвертация в dB
        val dbs = DoubleArray(half)
        var frameMaxDb = -200.0
        for (i in 0 until half) {
            var db = 20.0 * log10(magnitudes[i].coerceAtLeast(1e-12))

            // Tilt
            val freq = i.toDouble() * sampleRate / fftSize
            if (freq > 20.0) {
                db += settings.tilt * (ln(freq / 1000.0) / ln(2.0))
            }
            db += settings.gain * 20.0
            dbs[i] = db
            if (db > frameMaxDb) frameMaxDb = db
        }

        // 3. Mapping на логарифмическую шкалу
        val rawDisplay = FloatArray(DISPLAY_BINS)
        // ИЗМЕНЕНИЕ: расширенный диапазон частот 5-30000 Гц
        val logMin = log10(5.0)
        val logMax = log10(30000.0)

        for (i in 0 until DISPLAY_BINS) {
            val t = i.toDouble() / (DISPLAY_BINS - 1)
            val logPos = logMin + t * (logMax - logMin)
            val freq = 10.0.pow(logPos)

            val exactBin = ((freq / sampleRate) * fftSize).coerceIn(0.0, (half - 2).toDouble())
            val index = exactBin.toInt()
            val fraction = exactBin - index

            val y0 = dbs.getOrElse(index - 1) { dbs[index] }
            val y1 = dbs[index]
            val y2 = dbs.getOrElse(index + 1) { dbs[index] }
            val y3 = dbs.getOrElse(index + 2) { y2 }

            val dbValue = cubicInterpolate(y0, y1, y2, y3, fraction)

            var normalized = if (dbValue > settings.noiseThreshold) {
                (dbValue - settings.noiseThreshold) / settings.dbRange
            } else {
                0.0
            }
            rawDisplay[i] = normalized.toFloat().coerceAtLeast(0f)
        }

        // 4. СГЛАЖИВАНИЕ ПО ЧАСТОТЕ
        val smoothedFreqDisplay = FloatArray(DISPLAY_BINS)
        for (pass in 0..0) {
            for (i in 0 until DISPLAY_BINS) {
                val v0 = rawDisplay.getOrElse(i - 2) { rawDisplay[i] }
                val v1 = rawDisplay.getOrElse(i - 1) { rawDisplay[i] }
                val v2 = rawDisplay[i]
                val v3 = rawDisplay.getOrElse(i + 1) { rawDisplay[i] }
                val v4 = rawDisplay.getOrElse(i + 2) { rawDisplay[i] }

                smoothedFreqDisplay[i] = v0*0.06f + v1*0.24f + v2*0.4f + v3*0.24f + v4*0.06f
            }
        }

        // 5. СГЛАЖИВАНИЕ ПО ВРЕМЕНИ
        val finalDisplay = FloatArray(DISPLAY_BINS)
        val attackFactor = 0.9f
        val decayFactor = 0.80f + (settings.smoothingFactor * 0.19f)

        for (i in 0 until DISPLAY_BINS) {
            val currentVal = smoothedFreqDisplay[i]
            val prevVal = previousSpectrum.getOrElse(i) { 0f }

            val smoothed = if (currentVal > prevVal) {
                prevVal * (1 - attackFactor) + currentVal * attackFactor
            } else {
                prevVal * decayFactor + currentVal * (1 - decayFactor)
            }
            finalDisplay[i] = smoothed
        }

        //обновленный алгоритм определения нот
        // Конвертируем fftArray в ShortArray для YIN

        val audioForYin = ShortArray(half)
        for (i in 0 until half) {
            val re = fftArray[2 * i]
            val im = if (2 * i + 1 < fftArray.size) fftArray[2 * i + 1] else 0.0
            val magnitude = sqrt(re * re + im * im)
            audioForYin[i] = (magnitude * 32767).toInt().toShort()
        }

        // Определение частоты с помощью нового детектора
        val dominantFreq = if (timeDomainData != null) {
            val timestamp = if (isFileMode) visualizerSyncTimeMs else System.currentTimeMillis()
            processPitch(timeDomainData, timestamp, sampleRate)
            pllFrequency // Используем сглаженную частоту из PLL
        } else {
            // Fallback только если нет временных данных
            if (maxMagnitude > 0.001) {
                val spectralFreq = maxIndex.toDouble() * sampleRate / fftSize
                spectralFreq.coerceIn(0.0, 1200.0)
            } else {
                0.0
            }
        }

        // Если YIN не нашел частоту, используем старый метод как fallback
        //val fallbackFreq = if (maxMagnitude > 0.001) maxIndex.toDouble() * sampleRate / fftSize else 0.0
        //val finalFreq = if (dominantFreq > 20.0) dominantFreq else fallbackFreq

        val note = if (dominantFreq > 20.0) frequencyToNote(dominantFreq) else "..."
        //конец обновленного алгоритма



        return Quadruple(finalDisplay, frameMaxDb.toFloat(), dominantFreq, note)
    }

    // Адаптивная фильтрация
    private fun applyAdaptiveFilter(frequency: Double, confidence: Double): Double {
        if (frequency <= 0) return 0.0

        // Проверяем, не является ли частота гармоникой
        if (pllFrequency > 0) {
            val ratio = frequency / pllFrequency

            // Если частота близка к 2x или 3x текущей, скорее всего это гармоника
            if (abs(ratio - 2.0) < 0.1 || abs(ratio - 3.0) < 0.1) {
                return pllFrequency // Игнорируем гармонику
            }
        }

        // Адаптивное сглаживание
        val alpha = when {
            confidence > 0.8 -> 0.2  // Высокая уверенность
            confidence > 0.5 -> 0.1  // Средняя уверенность
            else -> 0.05              // Низкая уверенность
        }

        return if (pllFrequency > 0) {
            pllFrequency * (1 - alpha) + frequency * alpha
        } else {
            frequency
        }
    }




    private fun detectPitchByYIN(audioData: ShortArray, sampleRate: Int): Double {
        if (audioData.size < 2048) return 0.0

        val bufferSize = 2048
        val buffer = ShortArray(bufferSize) {
            if (it < audioData.size) audioData[it] else 0
        }

        // Нормализация
        val normalized = FloatArray(bufferSize)
        var max = 0f
        for (i in 0 until bufferSize) {
            normalized[i] = buffer[i] / 32768f
            if (abs(normalized[i]) > max) max = abs(normalized[i])
        }
        if (max > 0) {
            for (i in 0 until bufferSize) {
                normalized[i] /= max
            }
        }

        // Размер для YIN
        val yinBufferSize = 1024
        if (bufferSize < yinBufferSize) return 0.0

        // Выбираем середину буфера для стабильности
        val start = (bufferSize - yinBufferSize) / 2

        // Вычисление разностной функции
        val difference = FloatArray(yinBufferSize / 2)

        for (tau in 0 until difference.size) {
            var sum = 0f
            for (i in 0 until yinBufferSize - tau) {
                val delta = normalized[start + i] - normalized[start + i + tau]
                sum += delta * delta
            }
            difference[tau] = sum
        }

        // Кумулятивная нормализация
        val cmnd = FloatArray(difference.size)
        cmnd[0] = 1f
        for (tau in 1 until cmnd.size) {
            var cumulative = 0f
            for (j in 1..tau) {
                cumulative += difference[j]
            }
            if (cumulative > 0) {
                cmnd[tau] = difference[tau] * tau / cumulative
            } else {
                cmnd[tau] = 1f
            }
        }

        // Поиск минимума ниже порога
        val threshold = 0.1f
        var tauMin = 0
        for (tau in 1 until cmnd.size) {
            if (cmnd[tau] < threshold) {
                tauMin = tau
                break
            }
        }

        // Если не нашли минимум, ищем абсолютный минимум
        if (tauMin == 0) {
            var minValue = cmnd[1]
            for (tau in 2 until cmnd.size) {
                if (cmnd[tau] < minValue) {
                    minValue = cmnd[tau]
                    tauMin = tau
                }
            }
        }

        // Уточнение минимума параболической интерполяцией
        var preciseTau = tauMin.toFloat()
        if (tauMin > 1 && tauMin < cmnd.size - 1) {
            val alpha = cmnd[tauMin - 1]
            val beta = cmnd[tauMin]
            val gamma = cmnd[tauMin + 1]
            preciseTau = tauMin + (alpha - gamma) / (2 * (alpha - 2 * beta + gamma))
        }

        return if (tauMin > 0 && preciseTau > 0) {
            val freq = sampleRate.toFloat() / preciseTau
            freq.toDouble().coerceIn(80.0, 1200.0)
        } else {
            0.0
        }
    }

    // Структура для трекинга питча



    // Основная функция определения частоты
    private fun advancedPitchDetection(
        audioData: ShortArray,
        sampleRate: Int,
        previousFrequency: Double = 0.0
    ): PitchDetectionResult {
        // Проверяем, есть ли достаточно данных
        if (audioData.size < 2048) {
            return PitchDetectionResult(0.0, 0.0)
        }

        // Комбинируем несколько методов
        val yinResult = yinPitchDetection(audioData, sampleRate)
        val spectralResult = spectralPeakMethod(audioData, sampleRate)
        val autocorrResult = autocorrelationMethod(audioData, sampleRate)
        val cepstralResult = cepstralMethod(audioData, sampleRate)

        // Собираем все результаты с достаточной уверенностью
        val validResults = listOf(yinResult, spectralResult, autocorrResult, cepstralResult)
            .filter { it.frequency in 65.0..1200.0 && it.confidence > 0.3 }

        if (validResults.isEmpty()) {
            return PitchDetectionResult(0.0, 0.0)
        }

        // Дополнительно: многополосный анализ для низких нот
        if (previousFrequency < 200) {
            val multiBandResult = multiBandAnalysis(audioData, sampleRate)
            if (multiBandResult.confidence > 0.5) {
                validResults.plus(multiBandResult)
            }
        }

        // Используем PLL для отслеживания, если есть предыдущая частота
        val pllResult = if (previousFrequency > 0) {
            trackWithPLL(audioData, sampleRate, previousFrequency)
        } else {
            PitchDetectionResult(0.0, 0.0)
        }

        if (pllResult.confidence > 0.6) {
            validResults.plus(pllResult)
        }

        // Выбираем лучший результат
        return selectBestResult(validResults, previousFrequency)
    }

    // 1. YIN метод (уже есть, адаптируем под новый формат)
    private fun yinPitchDetection(audioData: ShortArray, sampleRate: Int): PitchDetectionResult {
        val freq = detectPitchByYIN(audioData, sampleRate)
        // Упрощенная оценка уверенности для YIN
        val confidence = if (freq > 0) {
            // Проверяем энергию сигнала
            val energy = audioData.take(1024).sumOf { it.toDouble() * it.toDouble() } / 1024
            val normalizedEnergy = (energy / (32768.0 * 32768.0)).coerceIn(0.0, 1.0)
            // Уверенность зависит от энергии и частоты
            normalizedEnergy * 0.8
        } else {
            0.0
        }
        return PitchDetectionResult(freq, confidence)
    }

    // 2. Спектральный метод
    private fun spectralPeakMethod(audioData: ShortArray, sampleRate: Int): PitchDetectionResult {
        val fftSize = 4096
        if (audioData.size < fftSize) return PitchDetectionResult(0.0, 0.0)

        val fft = DoubleFFT_1D(fftSize.toLong())
        val fftArray = DoubleArray(fftSize * 2)

        // Копируем данные с применением окна Ханнинга
        val window = createHanningWindow(fftSize)
        for (i in 0 until fftSize) {
            fftArray[i] = audioData[i].toDouble() / 32768.0 * window[i]
        }

        fft.realForward(fftArray)

        // Ищем максимальный пик
        var maxMagnitude = 0.0
        var maxIndex = 0

        for (i in 10 until fftSize / 2) { // Игнорируем самые низкие частоты
            val re = fftArray[2 * i]
            val im = if (2 * i + 1 < fftArray.size) fftArray[2 * i + 1] else 0.0
            val magnitude = sqrt(re * re + im * im)

            if (magnitude > maxMagnitude) {
                maxMagnitude = magnitude
                maxIndex = i
            }
        }

        if (maxMagnitude < 0.001) {
            return PitchDetectionResult(0.0, 0.0)
        }

        // Точное определение частоты с параболической интерполяцией
        val preciseFreq = findSpectralPeakWithInterpolation(fftArray, fftSize, sampleRate, maxIndex)

        // Проверяем гармоническую структуру для оценки уверенности
        val harmonicScore = checkHarmonicStructure(fftArray, fftSize, sampleRate, preciseFreq)

        return PitchDetectionResult(
            frequency = preciseFreq,
            confidence = (maxMagnitude.coerceIn(0.0, 1.0) * 0.5 + harmonicScore * 0.5).coerceIn(0.0, 1.0)
        )
    }

    // Проверка гармонической структуры
    private fun checkHarmonicStructure(
        fftArray: DoubleArray,
        fftSize: Int,
        sampleRate: Int,
        fundamentalFreq: Double
    ): Double {
        if (fundamentalFreq < 65.0) return 0.0

        var harmonicScore = 0.0
        val harmonicsToCheck = min(4, (sampleRate / 2 / fundamentalFreq).toInt())

        for (h in 2..harmonicsToCheck) {
            val harmonicFreq = fundamentalFreq * h
            val bin = (harmonicFreq * fftSize / sampleRate).toInt()

            if (bin in 1 until fftSize / 2) {
                val re = fftArray[2 * bin]
                val im = fftArray[2 * bin + 1]
                val magnitude = sqrt(re * re + im * im)
                harmonicScore += magnitude.coerceIn(0.0, 1.0)
            }
        }

        return (harmonicScore / (harmonicsToCheck - 1)).coerceIn(0.0, 1.0)
    }

    // 8. Точное определение спектрального пика с интерполяцией
    private fun findSpectralPeakWithInterpolation(
        fftArray: DoubleArray,
        fftSize: Int,
        sampleRate: Int,
        maxIndex: Int
    ): Double {
        if (maxIndex <= 0 || maxIndex >= fftSize / 2 - 1) {
            return maxIndex.toDouble() * sampleRate / fftSize
        }

        // Получаем значения амплитуд для пика и соседних бинов
        val magnitudes = DoubleArray(3)

        for (i in -1..1) {
            val idx = maxIndex + i
            if (idx >= 0 && idx < fftSize / 2) {
                val re = fftArray[2 * idx]
                val im = if (2 * idx + 1 < fftArray.size) fftArray[2 * idx + 1] else 0.0
                magnitudes[i + 1] = sqrt(re * re + im * im)
            } else {
                magnitudes[i + 1] = 0.0
            }
        }

        // Параболическая интерполяция в логарифмической шкале (более точная)
        val y0 = 20 * log10(magnitudes[0].coerceAtLeast(1e-12))
        val y1 = 20 * log10(magnitudes[1].coerceAtLeast(1e-12))
        val y2 = 20 * log10(magnitudes[2].coerceAtLeast(1e-12))

        // Находим смещение относительно центрального бина
        val offset = (y2 - y0) / (2 * (2 * y1 - y0 - y2))

        // Уточненный индекс пика
        val preciseIndex = maxIndex + offset

        // Рассчитываем частоту
        return preciseIndex * sampleRate / fftSize
    }

    // 3. Метод автокорреляции
    private fun autocorrelationMethod(audioData: ShortArray, sampleRate: Int): PitchDetectionResult {
        val bufferSize = min(4096, audioData.size)

        // Нормализация
        val normalized = FloatArray(bufferSize)
        var max = 0f
        for (i in 0 until bufferSize) {
            normalized[i] = audioData[i] / 32768f
            if (abs(normalized[i]) > max) max = abs(normalized[i])
        }

        if (max < 0.01f) return PitchDetectionResult(0.0, 0.0)

        for (i in normalized.indices) {
            normalized[i] /= max
        }

        // Вычисление автокорреляции
        val correlationSize = min(1024, bufferSize / 2)
        val correlation = FloatArray(correlationSize)

        for (tau in 0 until correlationSize) {
            var sum = 0f
            for (i in 0 until bufferSize - tau) {
                sum += normalized[i] * normalized[i + tau]
            }
            correlation[tau] = sum / (bufferSize - tau)
        }

        // Ищем первый значимый максимум после первого минимума
        var firstMinFound = false
        var peakTau = 0
        var peakValue = 0f

        val minTau = (sampleRate / 500).coerceAtLeast(10)  // Не ниже 500 Гц
        val maxTau = (sampleRate / 65).coerceAtMost(correlationSize - 1)  // Не выше 65 Гц

        for (tau in minTau until maxTau) {
            if (!firstMinFound && correlation[tau] < correlation[tau - 1]) {
                firstMinFound = true
                continue
            }

            if (firstMinFound && correlation[tau] > correlation[tau - 1] &&
                correlation[tau] > correlation[tau + 1] && correlation[tau] > 0.3f) {
                peakTau = tau
                peakValue = correlation[tau]
                break
            }
        }

        if (peakTau == 0) {
            // Fallback: ищем глобальный максимум
            for (tau in minTau until maxTau) {
                if (correlation[tau] > peakValue) {
                    peakValue = correlation[tau]
                    peakTau = tau
                }
            }
        }

        if (peakTau == 0 || peakValue < 0.2f) {
            return PitchDetectionResult(0.0, 0.0)
        }

        // Уточняем позицию максимума параболической интерполяцией
        val preciseTau = parabolicPeakInterpolation(correlation, peakTau)
        val frequency = sampleRate.toDouble() / preciseTau

        return PitchDetectionResult(
            frequency = frequency.coerceIn(65.0, 1200.0),
            confidence = peakValue.toDouble().coerceIn(0.0, 1.0)
        )
    }

    // Параболическая интерполяция для точного определения пика
    private fun parabolicPeakInterpolation(data: FloatArray, index: Int): Float {
        if (index <= 0 || index >= data.size - 1) return index.toFloat()

        val alpha = data[index - 1]
        val beta = data[index]
        val gamma = data[index + 1]

        return index + (alpha - gamma) / (2 * (alpha - 2 * beta + gamma))
    }

    // 4. Кепстральный метод
    private fun cepstralMethod(audioData: ShortArray, sampleRate: Int): PitchDetectionResult {
        val fftSize = 4096
        if (audioData.size < fftSize) return PitchDetectionResult(0.0, 0.0)

        // FFT
        val fft = DoubleFFT_1D(fftSize.toLong())
        val fftArray = DoubleArray(fftSize * 2)

        val window = createHanningWindow(fftSize)
        for (i in 0 until fftSize) {
            fftArray[i] = audioData[i].toDouble() / 32768.0 * window[i]
        }

        fft.realForward(fftArray)

        // Логарифм амплитуды
        for (i in 0 until fftSize / 2) {
            val re = fftArray[2 * i]
            val im = fftArray[2 * i + 1]
            val magnitude = sqrt(re * re + im * im).coerceAtLeast(1e-12)
            fftArray[2 * i] = ln(magnitude)
            fftArray[2 * i + 1] = 0.0
        }

        // Обратный FFT (кепстр)
        fft.realInverse(fftArray, true)

        // Ищем пик в кепстре (игнорируем quefrency = 0)
        val minQuefrency = (sampleRate / 1200.0).toInt()  // Для 1200 Гц
        val maxQuefrency = (sampleRate / 65.0).toInt()   // Для 65 Гц
        val searchStart = max(10, minQuefrency)
        val searchEnd = min(maxQuefrency, fftSize / 2 - 1)

        var maxCepstrum = 0.0
        var maxQuefrencyIndex = 0

        for (i in searchStart..searchEnd) {
            val cepstrumValue = abs(fftArray[2 * i])
            if (cepstrumValue > maxCepstrum) {
                maxCepstrum = cepstrumValue
                maxQuefrencyIndex = i
            }
        }

        if (maxQuefrencyIndex == 0 || maxCepstrum < 0.1) {
            return PitchDetectionResult(0.0, 0.0)
        }

        val frequency = sampleRate.toDouble() / maxQuefrencyIndex
        val confidence = maxCepstrum.coerceIn(0.0, 1.0)

        return PitchDetectionResult(frequency, confidence)
    }

    // 5. PLL трекер
    private fun trackWithPLL(
        audioData: ShortArray,
        sampleRate: Int,
        previousFrequency: Double
    ): PitchDetectionResult {
        val pll = PhaseLockedLoop(previousFrequency, sampleRate)

        // Обрабатываем первые N сэмплов
        val processSamples = min(512, audioData.size)
        var confidenceSum = 0.0
        var frequencySum = 0.0

        for (i in 0 until processSamples) {
            val sample = audioData[i].toDouble() / 32768.0
            val result = pll.process(sample)
            confidenceSum += result.confidence
            frequencySum += result.frequency
        }

        val avgFrequency = frequencySum / processSamples
        val avgConfidence = confidenceSum / processSamples

        return PitchDetectionResult(
            frequency = avgFrequency.coerceIn(65.0, 1200.0),
            confidence = avgConfidence.coerceIn(0.0, 1.0)
        )
    }

    // Класс PLL
    class PhaseLockedLoop(
        initialFrequency: Double,
        private val sampleRate: Int
    ) {
        private var phase = 0.0
        private var frequency = initialFrequency
        private var phaseErrorIntegral = 0.0

        // Коэффициенты PLL (можно настроить)
        private val kp = 0.1  // Пропорциональный коэффициент
        private val ki = 0.01 // Интегральный коэффициент

        fun process(sample: Double): PitchDetectionResult {
            // Генерируем опорный сигнал
            val reference = sin(phase)

            // Детектор фазы (простой перемножитель)
            val phaseError = sample * reference

            // Loop filter (PI-фильтр)
            phaseErrorIntegral += phaseError * ki
            phaseErrorIntegral = phaseErrorIntegral.coerceIn(-1.0, 1.0)

            val frequencyCorrection = phaseError * kp + phaseErrorIntegral

            // Обновляем частоту и фазу
            frequency += frequencyCorrection
            frequency = frequency.coerceIn(65.0, 1200.0)

            phase += 2 * PI * frequency / sampleRate
            if (phase > 2 * PI) phase -= 2 * PI

            // Оценка уверенности по величине ошибки фазы
            val confidence = exp(-abs(phaseError)).coerceIn(0.0, 1.0)

            return PitchDetectionResult(frequency, confidence)
        }
    }

    // 6. Многополосный анализ
    private fun multiBandAnalysis(audioData: ShortArray, sampleRate: Int): PitchDetectionResult {
        val candidates = mutableListOf<PitchCandidate>()

        // Анализ в разных частотных диапазонах
        val bands = listOf(
            Pair(65.0, 150.0),   // Низкие ноты
            Pair(150.0, 300.0),  // Средние низкие
            Pair(300.0, 600.0),  // Средние
            Pair(600.0, 1200.0)  // Высокие
        )

        for ((lowFreq, highFreq) in bands) {
            val filtered = bandpassFilter(audioData, sampleRate, lowFreq, highFreq)
            val result = autocorrelationMethod(filtered, sampleRate)

            if (result.frequency in lowFreq..highFreq && result.confidence > 0.4) {
                val harmonicScore = checkHarmonicConsistency(audioData, sampleRate, result.frequency)
                candidates.add(
                    PitchCandidate(
                        frequency = result.frequency,
                        confidence = result.confidence,
                        harmonicScore = harmonicScore
                    )
                )
            }
        }

        if (candidates.isEmpty()) {
            return PitchDetectionResult(0.0, 0.0)
        }

        // Выбираем кандидата с лучшей комбинацией уверенности и гармонической согласованности
        val bestCandidate = candidates.maxByOrNull {
            it.confidence * 0.7 + it.harmonicScore * 0.3
        }!!

        return PitchDetectionResult(
            frequency = bestCandidate.frequency,
            confidence = bestCandidate.confidence
        )
    }

    // 7. Функция выбора лучшего результата
    private fun selectBestResult(
        results: List<PitchDetectionResult>,
        previousFrequency: Double
    ): PitchDetectionResult {
        if (results.isEmpty()) return PitchDetectionResult(0.0, 0.0)

        // Если есть предыдущая частота, учитываем непрерывность
        val weightedResults = if (previousFrequency > 0) {
            results.map { result ->
                val continuityScore = if (previousFrequency > 0) {
                    val freqDiff = abs(result.frequency - previousFrequency)
                    // Насколько близко к предыдущей частоте (0-1)
                    1.0 - (freqDiff / min(previousFrequency, 100.0)).coerceIn(0.0, 1.0)
                } else {
                    1.0
                }

                val totalScore = result.confidence * 0.7 + continuityScore * 0.3
                Pair(result, totalScore)
            }
        } else {
            results.map { Pair(it, it.confidence) }
        }

        // Выбираем результат с максимальным весом
        val best = weightedResults.maxByOrNull { it.second }

        return if (best != null && best.second > 0.5) {
            best.first
        } else if (results.isNotEmpty()) {
            // Возвращаем результат с максимальной уверенностью
            results.maxByOrNull { it.confidence } ?: PitchDetectionResult(0.0, 0.0)
        } else {
            PitchDetectionResult(0.0, 0.0)
        }
    }

    // Проверка гармонической согласованности
    private fun checkHarmonicConsistency(
        audio: ShortArray,
        sampleRate: Int,
        fundamentalFreq: Double
    ): Double {
        if (fundamentalFreq < 65.0) return 0.0

        val fftSize = 4096
        val fft = DoubleFFT_1D(fftSize.toLong())
        val fftArray = DoubleArray(fftSize * 2)

        val window = createHanningWindow(fftSize)
        for (i in 0 until min(fftSize, audio.size)) {
            fftArray[i] = audio[i].toDouble() / 32768.0 * window[i]
        }

        fft.realForward(fftArray)

        var harmonicScore = 0.0
        val harmonicsToCheck = min(3, (sampleRate / 2 / fundamentalFreq).toInt())

        for (h in 1..harmonicsToCheck) {
            val harmonicFreq = fundamentalFreq * h
            val bin = (harmonicFreq * fftSize / sampleRate).toInt()

            if (bin in 1 until fftSize / 2) {
                val re = fftArray[2 * bin]
                val im = fftArray[2 * bin + 1]
                val magnitude = sqrt(re * re + im * im)
                // Вес гармоник уменьшается с номером
                harmonicScore += magnitude * (1.0 / h)
            }
        }

        return harmonicScore.coerceIn(0.0, 1.0)
    }

    // Полосовой фильтр
    private fun bandpassFilter(
        audio: ShortArray,
        sampleRate: Int,
        lowFreq: Double,
        highFreq: Double
    ): ShortArray {
        // Простой рекурсивный фильтр (для демонстрации)
        val dt = 1.0 / sampleRate
        val RC1 = 1.0 / (2 * PI * lowFreq)
        val RC2 = 1.0 / (2 * PI * highFreq)

        val alpha1 = dt / (RC1 + dt)
        val alpha2 = dt / (RC2 + dt)

        val filtered = ShortArray(audio.size)
        var lowpass = audio[0].toDouble()
        var bandpass = 0.0

        for (i in audio.indices) {
            // ФВЧ (убираем низкие частоты)
            val highpass = audio[i].toDouble() - lowpass
            // Обновляем ФНЧ
            lowpass += alpha1 * highpass
            // Второй ФНЧ для полосы пропускания
            bandpass += alpha2 * (highpass - bandpass)

            filtered[i] = bandpass.toInt().toShort()
        }

        return filtered
    }

    private fun processPitch(
        pcm: ShortArray,
        timestampMs: Long,
        sampleRate: Int
    ) {
        if (pcm.size < 2048) return

        // Создаем или обновляем детектор при необходимости
        if (pitchDetector == null || pitchDetector!!.sampleRate != sampleRate) {
            pitchDetector = PitchDetector(sampleRate)
        }

        val frameSize = min(pcm.size, (sampleRate * 0.06).toInt())
        val frame = pcm.take(frameSize).toShortArray()

        val freq = pitchDetector!!.detect(frame).toDouble()

        lifecycleScope.launch(Dispatchers.Main) {
            if (freq > 30.0) {
                // Есть звук
                val filteredFreq = if (pllFrequency > 0) {
                    val alpha = if (freq in 65.0..1200.0) 0.1 else 0.05
                    pllFrequency * (1 - alpha) + freq * alpha
                } else {
                    freq
                }

                pllFrequency = filteredFreq
                val midi = freqToMidi(filteredFreq)

                // Обновляем UI
                noteName = frequencyToNote(filteredFreq)
                dominantFrequency = filteredFreq

                // Добавляем точку в историю
                appendPitchPoint(PitchPoint(timestampMs, filteredFreq, midi))
            } else {
                // Тишина - сбрасываем UI
                noteName = "..."
                dominantFrequency = 0.0

                // Добавляем точку тишины
                appendPitchPoint(PitchPoint(timestampMs, 0.0, 0f))
            }
        }
    }



    @UnstableApi
    private fun loadMediaFile(uri: Uri) {
        // Сбрасываем флаг обработки — спектр не должен стартовать до нажатия Play.
        // Без этого при загрузке нового файла пока старый играл флаг оставался true.
        shouldProcessAudio = false

        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
            isPlayerInitialized = false
        }

        cleanupDecoderResources()
        currentPlaybackPositionMs = 0L
        visualizerSyncTimeMs = 0L
        atomicCurrentPlayerPosition.set(0L)

        exoPlayer = ExoPlayer.Builder(this).build()
        isPlayerInitialized = true

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_SYSTEM)
            .build()

        exoPlayer.setAudioAttributes(audioAttributes, true)
        exoPlayer.setHandleAudioBecomingNoisy(true)

        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.addListener(playerListener)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        positionUpdaterJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && isPlayerInitialized) {
                if (exoPlayer.isPlaying) {
                    atomicCurrentPlayerPosition.set(exoPlayer.currentPosition)
                }
                delay(16)
            }
        }

        startDecoderAndFFT(uri, 0L)

        positionSample.set(0)
        isLoading = false
        Log.d("ExoPlayer", "Player initialized")
    }

    private fun startDecoderAndFFT(uri: Uri, startPositionMs: Long) {
        decoderJob?.cancel()
        fileFftJob?.cancel()

        decoderJob = startStreamingDecoder(uri, startPositionMs)

        fileFftJob = lifecycleScope.launch {
            fileFftFlow().collect { (spectrum, maxDb, freq, note) ->
                spectrumData = spectrum
                currentMaxDb = maxDb
                dominantFrequency = freq
                noteName = note

                // Добавляем точку в pitchHistory (используем синхронизированное время плеера)
                val now = System.currentTimeMillis()
                val midi = freqToMidi(freq)
                if (freq > 0.0) {
                    appendPitchPoint(PitchPoint(now, freq, midi))
                }
            }
        }
    }

    private fun startMicFftCollector() {
        micFftJob?.cancel()
        micFftJob = lifecycleScope.launch {
            micFftFlow().collect { (spectrum, maxDb, freq, note) ->
                spectrumData = spectrum
                currentMaxDb = maxDb
                dominantFrequency = freq
                noteName = note
                //appendPitchPoint(freq) // Теперь данные идут в график!

                if (freq > 20.0) {
                    val now = System.currentTimeMillis()
                    val midi = freqToMidi(freq)
                    appendPitchPoint(PitchPoint(now, freq, midi))
                }
            }
        }
    }

    /*
    private fun appendPitchPoint(point: PitchPoint) {
        // Ограничиваем частоту добавлений (например ставим только если есть значимое изменение)
        if (point.freq <= 0.0) return
        // Добавляем и чистим старые
        pitchHistory.add(point)
        val cutoff = System.currentTimeMillis() - PITCH_HISTORY_WINDOW_MS
        while (pitchHistory.isNotEmpty() && pitchHistory.first().timeMs < cutoff) {
            pitchHistory.removeAt(0)
        }
    }

     */




    /*
        private fun freqToMidi(freq: Double): Int {
            if (freq <= 0) return -1
            val midi = (69 + 12 * (ln(freq / 440.0) / ln(2.0))).roundToInt()
            return midi
        }

     */
    private fun freqToMidi(freq: Double): Float {
        return (69f + 12f * (ln(freq / 440.0) / ln(2.0))).toFloat()
    }




    private fun onSeekTo(positionMs: Long) {
        if (isPlayerInitialized) {
            exoPlayer.seekTo(positionMs)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    durationMs = exoPlayer.duration.coerceAtLeast(1L)
                }
                Player.STATE_ENDED -> {
                    shouldProcessAudio = false
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            shouldProcessAudio = isPlaying
            if (!isPlaying) {
                // Останавливаем декодер на паузе — иначе ringBuffer набивается
                // пока ждём Play, и при старте возникает фриз из-за большого timeDiff
                decoderJob?.cancel()
            } else {
                // Возобновляем декодер с текущей позиции плеера
                val currentPos = atomicCurrentPlayerPosition.get()
                audioUri?.let { uri ->
                    ringBuffer.clear()
                    visualizerSyncTimeMs = currentPos
                    decoderJob = startStreamingDecoder(uri, currentPos)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (isFileMode) {
                val newPosMs = newPosition.positionMs
                currentPlaybackPositionMs = newPosMs
                atomicCurrentPlayerPosition.set(newPosMs)

                visualizerSyncTimeMs = newPosMs
                ringBuffer.clear()

                audioUri?.let { uri ->
                    decoderJob?.cancel()
                    decoderJob = startStreamingDecoder(uri, newPosMs)
                }
            }
        }
    }

    // ==================== UI КОД ====================

    private fun setupAndShowUI() {
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var showTools by remember { mutableStateOf(false) }
            val currentSettings by settingsFlow.collectAsState()
            var currentFps by remember { mutableStateOf(0) }
            var frameCount by remember { mutableStateOf(0) }
            var lastTime by remember { mutableStateOf(System.currentTimeMillis()) }
            val coroutineScope = rememberCoroutineScope()
            var isAnalysisMode by remember { mutableStateOf(false) }

            var isPlayerPanelVisible by remember { mutableStateOf(true) }
            var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
            var isSeeking by remember { mutableStateOf(false) }
            var sliderPosition by remember { mutableStateOf(0f) }

            var isGrabLocked by remember { mutableStateOf(false) }
            var isFingerDown by remember { mutableStateOf(false) }

            // Состояние для Spectrum Grab
            var isSpectrumGrabActive by remember { mutableStateOf(false) }
            var isDecaying by remember { mutableStateOf(false) }
            var accumulatedSpectrum by remember { mutableStateOf(FloatArray(DISPLAY_BINS) { 0f }) }

            // Новая переменная для отслеживания максимального значения grabbed спектра
            var maxAccumulatedValue by remember { mutableStateOf(0f) }

            val mainAlpha by animateFloatAsState(
                targetValue = if (isSpectrumGrabActive) 1f else 0f,
                animationSpec = tween(durationMillis = 300)
            )

            // Измененная логика для grabbedAlpha - теперь зависит от максимального значения спектра
            val grabbedAlpha by animateFloatAsState(
                targetValue = when {
                    isSpectrumGrabActive -> 1f
                    isDecaying -> maxAccumulatedValue.coerceIn(0f, 1f)
                    else -> 0f
                },
                animationSpec = tween(durationMillis = 175)
            )

            LaunchedEffect(isSpectrumGrabActive) {
                if (isSpectrumGrabActive) {
                    if (!isDecaying) {
                        accumulatedSpectrum = spectrumData.copyOf()
                        // Обновляем максимальное значение при активации grab
                        maxAccumulatedValue = (accumulatedSpectrum.maxOrNull() ?: 0f).coerceIn(0f, 1f)
                    }
                    isDecaying = false
                } else {
                    isDecaying = true
                }
            }

            LaunchedEffect(Unit) {
                val attackFactor = 0.9f
                while (true) {
                    val decayFactor = 0.80f + (currentSettings.smoothingFactor * 0.19f)
                    if (isSpectrumGrabActive) {
                        val new = accumulatedSpectrum.copyOf()
                        var changed = false
                        for (i in 0 until DISPLAY_BINS) {
                            val currentVal = spectrumData.getOrElse(i) { 0f }
                            val prevVal = new[i]
                            if (currentVal > prevVal) {
                                new[i] = prevVal * (1 - attackFactor) + currentVal * attackFactor
                                changed = true
                            }
                        }
                        if (changed) {
                            accumulatedSpectrum = new
                            // Обновляем максимальное значение с ограничением
                            maxAccumulatedValue = (new.maxOrNull() ?: 0f).coerceIn(0f, 1f)
                        }
                    } else if (isDecaying) {
                        val new = accumulatedSpectrum.copyOf()
                        var allZero = true
                        for (i in 0 until DISPLAY_BINS) {
                            new[i] *= decayFactor
                            if (new[i] < 0.01f) new[i] = 0f
                            if (new[i] > 0f) allZero = false
                        }
                        accumulatedSpectrum = new
                        // Обновляем максимальное значение для alpha с ограничением
                        maxAccumulatedValue = (new.maxOrNull() ?: 0f).coerceIn(0f, 1f)
                        if (allZero) {
                            isDecaying = false
                            maxAccumulatedValue = 0f
                        }
                    }
                    delay(16)
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    val currentTime = System.currentTimeMillis()
                    // Прячем панель только если нет активного взаимодействия (Grab или Drag)
                    if (!isSpectrumGrabActive && currentTime - lastInteractionTime > 2000 && isPlayerPanelVisible && !isSeeking) {
                        isPlayerPanelVisible = false
                    }
                    delay(100)
                }
            }

            val screenClickHandler = {
                lastInteractionTime = System.currentTimeMillis()
                isPlayerPanelVisible = !isPlayerPanelVisible
            }

            LaunchedEffect(isLoading) {
                if (!isLoading && isFileMode) {
                    isPlayerPanelVisible = true
                    lastInteractionTime = System.currentTimeMillis()
                    delay(3000)
                }
            }

            LaunchedEffect(isFileMode, isAnalysisMode) {
                if (!isFileMode && isAnalysisMode) {
                    startNoteRefresh()
                } else {
                    noteRefreshJob?.cancel()
                }
            }

            LaunchedEffect(isFileMode, isAnalysisMode) {
                if (isAnalysisMode) {
                    startTimeUpdater()
                } else {
                    timeUpdateJob?.cancel()
                }
            }

            LaunchedEffect(isAnalysisMode) {
                if (isAnalysisMode) {
                    startNoteRefresh()
                    startTimelineUpdater()
                } else {
                    noteRefreshJob?.cancel()
                    timelineUpdateJob?.cancel()
                }
            }

            LaunchedEffect(isFileMode, isPlayerInitialized) {
                if (isFileMode && isPlayerInitialized) {
                    while (true) {
                        if (!isSeeking) {
                            val currentPos = exoPlayer.currentPosition.toFloat()
                            val duration = exoPlayer.duration.toFloat().coerceAtLeast(1f)
                            sliderPosition = (currentPos / duration).coerceIn(0f, 1f)
                        }
                        delay(50)
                    }
                }
            }

            LaunchedEffect(audioUri, isFileMode) {
                val currentUri = audioUri
                if (currentUri != null && isFileMode) {
                    loadMediaFile(currentUri)
                } else {
                    if (::exoPlayer.isInitialized) {
                        exoPlayer.release()
                    }
                    isPlayerInitialized = false
                }
            }

            LaunchedEffect(isFileMode) {
                if (!isFileMode) {
                    // Останавливаем таймлайн (если был запущен для файлового режима)
                    stopTimelineUpdater()

                    // СБРОС И ЗАПУСК ТАЙМЛАЙНА ДЛЯ МИКРОФОННОГО РЕЖИМА
                    timelineState.resetTime()
                    timelineState.isPaused = false
                    timelineState.playbackSpeed = 1.0f
                    startTimelineUpdater()  // ЗАПУСКАЕМ ОБНОВЛЕНИЕ ВРЕМЕНИ

                    cleanupDecoderResources()
                    playbackJob?.cancel()
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                    pcmByteArray = null
                    monoShorts = null
                    positionSample.set(0)
                    baseSample = 0L
                    currentPositionMs = 0L
                    durationMs = 0L

                    startMicRecording()
                    startMicFftCollector()
                    micFftJob?.cancel()
                    micFftJob = lifecycleScope.launch {
                        micFftFlow().collect { (spectrum, maxDb, frequency, note) ->
                            spectrumData = spectrum
                            currentMaxDb = maxDb
                            dominantFrequency = frequency
                            noteName = note
                        }
                    }
                }
                else{
                    timelineState.resetOffset()
                    timelineState.isPaused = false
                    startTimelineUpdater()
                    startMicRecording()
                    startMicFftCollector()
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    frameCount++
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTime >= 1000) {
                        currentFps = frameCount
                        frameCount = 0
                        lastTime = currentTime
                    }
                    delay(16)
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    playbackJob?.cancel()
                    positionUpdaterJob?.cancel()
                    micRecordJob?.cancel()
                    micFftJob?.cancel()
                    cleanupDecoderResources()
                }
            }



            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Умная обработка жестов: Tap vs Hold
                        awaitEachGesture {
                            awaitFirstDown() // Палец коснулся экрана


                            // Ждем до 250мс.
                            // Если палец поднимется раньше -> это TAP (открываем плеер).
                            // Если тайм-аут выйдет -> это HOLD (включаем Spectrum Grab).

                            try {
                                withTimeout(250) {
                                    waitForUpOrCancellation()
                                    // Если мы здесь, значит палец поднялся быстро -> ТАП
                                    screenClickHandler()
                                }
                            } catch (e: PointerEventTimeoutCancellationException) {
                                // Тайм-аут вышел, палец все еще на экране -> УДЕРЖАНИЕ
                                isSpectrumGrabActive = true
                                lastInteractionTime = System.currentTimeMillis()

                                if (waitForUpOrCancellation() != null) {
                                    isFingerDown = false
                                    if (!isGrabLocked) {
                                        isSpectrumGrabActive = false
                                        isDecaying = true
                                    }
                                }


                            }
                        }
                    }
            ) {

                // UIControls убран: кнопка файла дублировалась с боковой панелью
                // и была невидима (перекрыта Canvas), но оставалась кликабельной


                // НОВЫЙ МОДУЛЬ: График нот (Piano Roll)
                if (isAnalysisMode) {


                    // Режим анализа нот - полный экран с темным фоном
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D0D0D))
                    ) {
                        PitchRollDisplay(
                            historyProvider = { pitchHistory },
                            blocksProvider = { noteBlocks },
                            cameraCenterProvider = { cameraCenter },
                            currentTimeMsProvider = { timelineState.getDisplayTimeMs() },
                            //cameraCenter = cameraCenter,
                            //currentTimeMs = timelineState.getDisplayTimeMs(),
                            isPaused = isTimelinePaused,
                            onOffsetChange = { deltaMs ->
                                timelineState.userOffsetMs += deltaMs
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Информация о текущей ноте сверху
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.Black.copy(alpha = 0.7f)
                                ),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = "Текущая нота: $noteName",
                                        color = Color.Cyan,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${"%.1f".format(dominantFrequency)} Гц",
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "MIDI: ${if (dominantFrequency > 0) freqToMidi(dominantFrequency) else "---"}",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 12.sp
                                    )
                                    // Добавляем статус паузы
                                    if (isNoteAnalysisPaused) {
                                        Text(
                                            text = "⏸ ПАУЗА",
                                            color = Color.Yellow,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Кнопка паузы/продолжения - в центре внизу
                        IconButton(
                            onClick = {
                                isNoteAnalysisPaused = !isNoteAnalysisPaused
                                isTimelinePaused = isNoteAnalysisPaused
                                timelineState.isPaused = isNoteAnalysisPaused

                                // Останавливаем обработку аудио при паузе
                                if (isNoteAnalysisPaused) {
                                    // Останавливаем обновление таймлайна
                                    timelineUpdateJob?.cancel()
                                    timelineUpdateJob = null

                                    // Останавливаем обновление нот при паузе
                                    noteRefreshJob?.cancel()
                                    noteRefreshJob = null
                                } else {
                                    // Возобновляем обновление времени
                                    startTimelineUpdater()

                                    // Возобновляем обновление нот
                                    startNoteRefresh()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                                .size(56.dp)
                                .background(
                                    color = Color.Black.copy(alpha = 0.7f),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        ) {
                            Icon(
                                if (isNoteAnalysisPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Пауза/Продолжить",
                                tint = if (isNoteAnalysisPaused) Color.Cyan else Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                }
                else{
                    ProfessionalSpectrumDisplay(
                        spectrumData = spectrumData,
                        accumulatedSpectrum = accumulatedSpectrum,
                        mainAlpha = mainAlpha,
                        grabbedAlpha = grabbedAlpha,
                        settings = currentSettings,
                        pitchHistory = pitchHistory.toList(), // snapshot
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isSpectrumGrabActive) {
                    IconButton(
                        onClick = {
                            isGrabLocked = !isGrabLocked
                            if (!isGrabLocked && !isFingerDown) {
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
                            contentDescription = "Lock Grabbed Spectrum",
                            tint = Color.White
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                    ) {
                        Column {
                            IconButton(
                                onClick = { isAnalysisMode = !isAnalysisMode },
                                //containerColor = if (isAnalysisMode) Color.Cyan else Color.Gray
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = "Анализ нот",
                                    tint = if (isAnalysisMode) Color.Cyan else Color.White
                                )
                            }
                            IconButton(
                                onClick = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    showSettings = !showSettings
                                },
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Настройки",
                                    tint = if (showSettings) Color.Cyan else Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            /*
                            IconButton(
                                onClick = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    showTools = !showTools
                                },
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = "Инструменты",
                                    tint = Color.White
                                )
                            }

                             */
                            Spacer(modifier = Modifier.height(8.dp))
                            IconButton(
                                onClick = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    if (isFileMode) {
                                        isFileMode = false
                                        isPlaying = false
                                        audioUri = null
                                        isLoading = false
                                    } else {
                                        // "*/*" чтобы принимать аудио, видео и любые форматы
                                        filePicker.launch("*/*")
                                    }
                                },
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = "Загрузить аудиофайл",
                                    tint = if (isFileMode) Color.Cyan else Color.White
                                )
                            }
                        }
                    }
                    /*
                    DebugInfo(
                        currentMaxDb = currentMaxDb,
                        noiseThreshold = currentSettings.noiseThreshold,
                        currentFps = currentFps,
                        gain = currentSettings.gain,
                        dbRange = currentSettings.dbRange,
                        isFileMode = isFileMode,
                        isGrabActive = isSpectrumGrabActive,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    if (showTools) {
                        ToolsPanel(
                            dominantFrequency = dominantFrequency,
                            noteName = noteName,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 60.dp)
                        )
                    }

                     */
                }

                if (showSettings) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SettingsPanel(
                            noiseThreshold = currentSettings.noiseThreshold,
                            onNoiseThresholdChange = { newValue ->
                                settingsFlow.update { it.copy(noiseThreshold = newValue) }
                            },
                            smoothingFactor = currentSettings.smoothingFactor,
                            onSmoothingChange = { newValue ->
                                settingsFlow.update { it.copy(smoothingFactor = newValue) }
                            },
                            speedFactor = currentSettings.speedFactor,
                            onSpeedChange = { newValue ->
                                settingsFlow.update { it.copy(speedFactor = newValue) }
                            },
                            gain = currentSettings.gain,
                            onGainChange = { newValue ->
                                settingsFlow.update { it.copy(gain = newValue) }
                            },
                            dbRange = currentSettings.dbRange,
                            onDbRangeChange = { newValue ->
                                settingsFlow.update { it.copy(dbRange = newValue) }
                            },
                            tilt = currentSettings.tilt,
                            onTiltChange = { newValue ->
                                settingsFlow.update { it.copy(tilt = newValue) }
                            },
                            currentFps = currentFps,
                            onClose = {
                                lastInteractionTime = System.currentTimeMillis()
                                showSettings = false
                            }
                        )
                    }

                }

                val currentPositionMs = if (isPlayerInitialized) exoPlayer.currentPosition else 0L
                val durationPlayerMs = if (isPlayerInitialized) exoPlayer.duration.coerceAtLeast(1L) else 1L
                val isPlayerPlaying = if (isPlayerInitialized) exoPlayer.isPlaying else false

                if (isFileMode) {
                    AnimatedVisibility(
                        visible = isPlayerPanelVisible,
                        enter = fadeIn(animationSpec = tween(300)) + slideInVertically(
                            animationSpec = tween(300),
                            initialOffsetY = { it }
                        ),
                        exit = fadeOut(animationSpec = tween(200)) + slideOutVertically(
                            animationSpec = tween(200),
                            targetOffsetY = { it }
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Black.copy(alpha = 0.85f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                if (isLoading) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            color = Color.White,
                                            strokeWidth = 1.5.dp
                                        )
                                        Text(
                                            text = " Загрузка...",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(start = 6.dp)
                                        )
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatTime((sliderPosition * durationPlayerMs).toLong()),
                                            color = Color.White.copy(alpha = 0.8f),
                                            fontSize = 10.sp,
                                            modifier = Modifier.width(40.dp)
                                        )

                                        IconButton(
                                            onClick = {
                                                lastInteractionTime = System.currentTimeMillis()
                                                if (isPlayerInitialized) {
                                                    if (isPlayerPlaying) {
                                                        exoPlayer.pause()
                                                    } else {
                                                        if (currentPositionMs >= durationPlayerMs - 50) {
                                                            exoPlayer.seekTo(0)
                                                        }
                                                        exoPlayer.play()
                                                    }
                                                }
                                            },
                                            enabled = isPlayerInitialized && !isLoading,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                if (isPlayerPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play/Pause",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Text(
                                            text = formatTime(durationPlayerMs),
                                            color = Color.White.copy(alpha = 0.8f),
                                            fontSize = 10.sp,
                                            modifier = Modifier.width(40.dp)
                                        )
                                    }

                                    Slider(
                                        value = sliderPosition,
                                        onValueChange = { newValue ->
                                            lastInteractionTime = System.currentTimeMillis()
                                            isSeeking = true
                                            sliderPosition = newValue
                                        },
                                        onValueChangeFinished = {
                                            lastInteractionTime = System.currentTimeMillis()
                                            if (isPlayerInitialized) {
                                                val targetMs = (sliderPosition * durationPlayerMs).toLong()
                                                onSeekTo(targetMs)
                                            }
                                            coroutineScope.launch {
                                                delay(200)
                                                isSeeking = false
                                            }
                                        },
                                        enabled = isPlayerInitialized && !isLoading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        colors = androidx.compose.material3.SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color(0xFF4FC3F7),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                if (isLoading && isFileMode) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF4FC3F7)
                            )
                            Text(
                                text = "Загрузка аудиофайла...",
                                color = Color.White,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val sec = ms / 1000
        val min = sec / 60
        val s = sec % 60
        return "%02d:%02d".format(min, s)
    }

    private fun frequencyToNote(frequency: Double): String {
        if (frequency < 16.35 || frequency > 4186.0) return "---"
        val A4 = 440.0
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val semitones = 12.0 * (ln(frequency / A4) / ln(2.0))
        val noteIndex = ((semitones + 69.0).roundToInt() % 12).let { if (it < 0) it + 12 else it }
        val octave = ((semitones.roundToInt() + 69) / 12) - 1
        return "${noteNames[noteIndex]}$octave"
    }
}

// ==================== COMPOSABLE ФУНКЦИИ ====================
@Composable
fun UIControls(
    onFilePick: () -> Unit,
    isFileMode: Boolean,
    noteName: String,
    freq: Double,
    isSilent: Boolean = false  // Добавьте этот параметр
) {
    Column(modifier = Modifier.padding(32.dp)) {
        Text(
            text = if (isSilent) "Тишина" else "Нота: $noteName",
            color = if (isSilent) Color.Gray else Color.White,
            fontSize = 24.sp
        )
        Text(
            text = if (isSilent) "--- Гц" else "${"%.1f".format(freq)} Гц",
            color = if (isSilent) Color.Gray else Color.Cyan,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onFilePick) {
            Icon(Icons.Default.Folder, null)
            Text(if (isFileMode) " Сменить файл" else " Открыть файл")
        }
    }
}

@Composable
fun IsolatedNoteText(noteProvider: () -> String) {
    // Теперь переменная читается ТОЛЬКО внутри этого маленького компонента!
    Text(
        text = "Нота: ${noteProvider()}",
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
}
@Composable
fun PitchRollDisplay(
    historyProvider: () -> List<PitchPoint>,
    blocksProvider: () -> List<NoteBlock>,
    cameraCenterProvider: () -> Float, // Лямбда!
    currentTimeMsProvider: () -> Long, // Лямбда!
    isPaused: Boolean = false,
    onOffsetChange: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val visibleTimeWindow = PITCH_HISTORY_WINDOW_MS.toFloat()
    val playheadOffsetRight = 0.85f
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val history = historyProvider() // Данные прочитаются ТОЛЬКО в фазе Draw!
        val blocks = blocksProvider()
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        // Считываем значения внутри Canvas - Compose перерисует только графику, а не UI!
        val cameraCenter = cameraCenterProvider()
        val currentTimeMs = currentTimeMsProvider()

        val visibleMinMidi = cameraCenter - 6f
        val visibleMaxMidi = cameraCenter + 6f

        val pianoWidth = 70.dp.toPx().coerceAtMost(width * 0.3f)
        if (pianoWidth <= 0) return@Canvas

        val graphAreaWidth = (width - pianoWidth).coerceAtLeast(1f)
        val playheadX = pianoWidth + graphAreaWidth * playheadOffsetRight
        val pixelsPerMs = (graphAreaWidth * playheadOffsetRight) / visibleTimeWindow

        fun timeToX(timeMs: Long): Float {
            val deltaMs = (currentTimeMs - timeMs).coerceIn(0L, PITCH_HISTORY_WINDOW_MS)
            return playheadX - deltaMs * pixelsPerMs
        }

        fun midiToY(midi: Float): Float {
            val range = (visibleMaxMidi - visibleMinMidi).coerceAtLeast(1f)
            val normalized = ((midi - visibleMinMidi) / range).coerceIn(0f, 1f)
            return height * (1f - normalized)
        }

        val blackKeys = setOf(1, 3, 6, 8, 10)
        val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        // 1. ДИНАМИЧНЫЙ ФОН - ПРОДОЛЖЕНИЕ КЛАВИШ
        val minMidiInt = kotlin.math.floor(visibleMinMidi).toInt() - 1
        val maxMidiInt = kotlin.math.ceil(visibleMaxMidi).toInt() + 1

        for (midiNote in minMidiInt..maxMidiInt) {
            val isBlackKey = blackKeys.contains(midiNote % 12)
            val yTop = midiToY(midiNote + 0.5f)
            val yBottom = midiToY(midiNote - 0.5f)
            val laneH = (yBottom - yTop).coerceAtLeast(1f)

            // Чередование серого цвета для фона дорожек
            val laneColor = if (isBlackKey) Color(0xFF161616) else Color(0xFF242424)
            drawRect(
                color = laneColor,
                topLeft = Offset(pianoWidth, yTop),
                size = Size(graphAreaWidth, laneH)
            )

            // Сетка
            drawLine(
                color = Color(0xFF0F0F0F),
                start = Offset(pianoWidth, yBottom),
                end = Offset(width, yBottom),
                strokeWidth = 1f
            )
        }

        // 2. КЛАВИШИ СЛЕВА
        drawRect(color = Color(0xFF111111), topLeft = Offset(0f, 0f), size = Size(pianoWidth, height))
        for (i in 0..11) {
            val midiNote = (visibleMinMidi + i).coerceIn(0f, 127f)
            if (midiNote > visibleMaxMidi) continue

            val isBlackKey = blackKeys.contains(midiNote.toInt() % 12)
            val yTop = midiToY(midiNote + 0.5f)
            val yBottom = midiToY(midiNote - 0.5f)
            val keyH = (yBottom - yTop).coerceAtLeast(1f)

            if (yTop >= height || yBottom <= 0f) continue

            val keyColor = if (isBlackKey) Color(0xFF222222) else Color.White
            drawRect(color = keyColor, topLeft = Offset(0f, yTop), size = Size(pianoWidth, keyH))

            val borderColor = if (isBlackKey) Color(0xFF444444) else Color.Black
            drawRect(color = borderColor, topLeft = Offset(0f, yTop), size = Size(pianoWidth, keyH), style = Stroke(width = 1f))

            if (pianoWidth > 30 && keyH > 10f) {
                val noteIndex = midiNote.toInt() % 12
                val octave = (midiNote.toInt() / 12) - 1
                val textColor = if (isBlackKey) Color.White else Color.Black
                drawText(
                    textMeasurer = textMeasurer,
                    text = "${noteNames[noteIndex]}$octave",
                    style = TextStyle(color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    topLeft = Offset(8f, yTop + keyH / 2 - 5f)
                )
            }
        }

        // 3. PLAYHEAD И ВЕРТИКАЛЬНЫЕ ЛИНИИ
        drawLine(color = Color.White, start = Offset(playheadX, 0f), end = Offset(playheadX, height), strokeWidth = 2f)

        for (seconds in 0..8 step 2) {
            val time = currentTimeMs - seconds * 1000
            val x = timeToX(time)
            if (x > pianoWidth && x < playheadX) {
                drawLine(color = Color(0x22FFFFFF), start = Offset(x, 0f), end = Offset(x, height), strokeWidth = if (seconds % 4 == 0) 1.2f else 0.5f)
            }
        }

        // 4. ПРЯМОУГОЛЬНИКИ НОТ
        val keyHeight = (height / 12f).coerceAtLeast(1f)
        blocks.forEach { block ->
            if (block.midi < visibleMinMidi || block.midi > visibleMaxMidi) return@forEach

            val xStart = timeToX(block.startMs)
            val xEnd = timeToX(block.endMs)
            if (xEnd <= pianoWidth || xStart >= playheadX) return@forEach

            val yPos = midiToY(block.midi)
            val rectHeight = keyHeight * 0.7f
            val rectY = (yPos - rectHeight / 2).coerceIn(0f, height - rectHeight)

            val visibleXStart = maxOf(xStart, pianoWidth)
            val visibleXEnd = minOf(xEnd, playheadX)
            val visibleWidth = maxOf(3f, visibleXEnd - visibleXStart)

            if (visibleWidth <= 0) return@forEach

            clipRect(left = pianoWidth, top = 0f, right = playheadX, bottom = height) {
                drawRoundRect(color = Color(0xFF9E47F5).copy(alpha = 0.5f), topLeft = Offset(visibleXStart, rectY), size = Size(visibleWidth, rectHeight), cornerRadius = CornerRadius(4f, 4f))
                drawRoundRect(color = Color.White, topLeft = Offset(visibleXStart, rectY), size = Size(visibleWidth, rectHeight), cornerRadius = CornerRadius(4f, 4f), style = Stroke(width = 1.5f))

                if (visibleWidth > 40 && rectY >= 0 && rectY + rectHeight <= height) {
                    drawText(textMeasurer = textMeasurer, text = block.noteName, style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold), topLeft = Offset(visibleXStart + 5f, rectY + rectHeight / 2 - 5f))
                }
            }
        }

        // 5. ОПТИМИЗИРОВАННАЯ ЛИНИЯ ГРАФИКА (БЕЗ ФИЛЬТРАЦИИ И СОРТИРОВКИ)
        val GAP_THRESHOLD_MS = 120L
        //val path = Path()
        path.reset()
        var hasMoved = false
        var previousPoint: PitchPoint? = null

        // Просто линейно итерируемся по массиву

        for (i in 0 until history.size) {
            val point = history[i]

            // Быстрая фильтрация прямо в цикле
            val timeDiff = currentTimeMs - point.timeMs
            if (timeDiff !in 0L..PITCH_HISTORY_WINDOW_MS) continue
            if (point.midi !in visibleMinMidi..visibleMaxMidi) continue

            val x = timeToX(point.timeMs)
            val y = midiToY(point.midi)

            if (x in pianoWidth..playheadX && y in 0f..height) {
                if (!hasMoved) {
                    path.moveTo(x, y)
                    hasMoved = true
                } else {
                    val prev = previousPoint
                    if (prev != null) {
                        if (point.timeMs - prev.timeMs > GAP_THRESHOLD_MS) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                }
                previousPoint = point
            }
        }

        if (hasMoved) {
            drawPath(path = path, color = Color.Cyan, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        drawLine(color = Color.White.copy(alpha = 0.5f), start = Offset(pianoWidth, 0f), end = Offset(pianoWidth, height), strokeWidth = 2f)
    }
}

@Composable
fun ProfessionalSpectrumDisplay(
    spectrumData: FloatArray,
    accumulatedSpectrum: FloatArray,
    mainAlpha: Float,
    grabbedAlpha: Float,
    settings: SpectrumSettings,
    //pitchHistory: List, // snapshot
    modifier: Modifier = Modifier,
    pitchHistory: List<PitchPoint>
) {
    val noiseThreshold = settings.noiseThreshold
    val dbRange = settings.dbRange
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // Фон
        drawRect(color = Color(0xFF0D0D0D), size = size)

        // Сетка и оси
        drawProfessionalGrid(width, height, dbRange)

        // Линия порога шума
        // ИЗМЕНЕНИЕ: минимальные отступы для устранения рамок
        val paddingLeft = 40f
        val paddingBottom = 25f
        val paddingTop = 5f
        val paddingRight = 5f
        val availableHeight = height - paddingBottom - paddingTop

        val thresholdY = height - paddingBottom - ((noiseThreshold + dbRange) / dbRange * availableHeight)
        drawLine(
            color = Color.Red.copy(alpha = 0.4f),
            start = Offset(paddingLeft, thresholdY),
            end = Offset(width - paddingRight, thresholdY),
            strokeWidth = 1f
        )

        // Рисуем реальный спектр
        val realColor = lerp(Color(0xFF4FC3F7), Color.Gray, mainAlpha)
        val realStrokeWidth = 3f - mainAlpha * 2f
        val realFillAlpha = 0.2f - mainAlpha * 0.2f

        drawSmoothSpectrum(
            spectrum = spectrumData,
            width = width,
            height = height,
            dbRange = dbRange,
            alpha = 1f,
            strokeWidth = realStrokeWidth,
            fill = true,
            color = realColor,
            separateFillAlpha = realFillAlpha
        )


        // Рисуем grabbed спектр - теперь прозрачность зависит от grabbedAlpha
        if (grabbedAlpha > 0.01f) {
            drawSmoothSpectrum(
                spectrum = accumulatedSpectrum,
                width = width,
                height = height,
                dbRange = dbRange,
                alpha = grabbedAlpha.coerceIn(0f, 1f),
                strokeWidth = 3f,
                fill = true,
                color = Color.White,
                separateFillAlpha = null
            )
        }

        // Оси с подписями
        drawProfessionalAxes(textMeasurer, width, height, dbRange)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSmoothSpectrum(
    spectrum: FloatArray,
    width: Float,
    height: Float,
    dbRange: Float,
    alpha: Float,
    strokeWidth: Float,
    fill: Boolean,
    color: Color,
    separateFillAlpha: Float? = null
) {
    val points = mutableListOf<Offset>()
    // ИЗМЕНЕНИЕ: расширенный диапазон частот 5-30000 Гц
    val logMin = log10(5f)
    val logMax = log10(30000f)
    // ИЗМЕНЕНИЕ: минимальные отступы для устранения рамок
    val paddingLeft = 40f
    val paddingBottom = 25f
    val paddingTop = 5f
    val paddingRight = 5f
    val availableWidth = width - paddingLeft - paddingRight
    val availableHeight = height - paddingBottom - paddingTop

    // Подготовка данных для сплайна (упрощенная версия для скорости отрисовки двух слоев)
    val displayTicks = 600
    val interpolatedData = FloatArray(displayTicks)

    for (i in 0 until displayTicks) {
        val t = i.toFloat() / (displayTicks - 1)
        val mappedIndex = (t * (spectrum.size - 1))
        val index = mappedIndex.toInt()
        val fraction = mappedIndex - index

        val v1 = spectrum.getOrElse(index) { 0f }
        val v2 = spectrum.getOrElse(index + 1) { 0f }

        interpolatedData[i] = (v1 * (1 - fraction) + v2 * fraction)
    }

    // Gaussian Blur
    val smoothedData = FloatArray(displayTicks)
    val kernelRadius = 3
    for (i in interpolatedData.indices) {
        var sum = 0f
        var weightSum = 0f
        for (k in -kernelRadius..kernelRadius) {
            val idx = (i + k).coerceIn(0, displayTicks - 1)
            val weight = exp(-(k * k) / (2f * 1.5f * 1.5f)).toFloat()
            sum += interpolatedData[idx] * weight
            weightSum += weight
        }
        smoothedData[i] = sum / weightSum
    }

    val path = Path()

    for (i in smoothedData.indices) {
        val t = i.toFloat() / (displayTicks - 1)
        val logFreq = logMin + t * (logMax - logMin)
        val x = paddingLeft + ((logFreq - logMin) / (logMax - logMin)) * availableWidth

        var valY = smoothedData[i]
        if (valY > 1.0f) {
            val excess = valY - 1.0f
            valY = 1.0f + (1f - exp(-excess)) * 0.2f
        }

        val y = height - paddingBottom - (valY * availableHeight * 0.95f)
        points.add(Offset(x, y))
    }

    if (points.isNotEmpty()) {
        path.moveTo(points[0].x, points[0].y)

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val cx1 = p1.x + (p2.x - p1.x) / 2f
            val cy1 = p1.y
            val cx2 = p1.x + (p2.x - p1.x) / 2f
            val cy2 = p2.y
            path.cubicTo(cx1, cy1, cx2, cy2, p2.x, p2.y)
        }
    }

    if (fill) {
        val fillPath = Path()
        fillPath.addPath(path)
        fillPath.lineTo(width - paddingRight, height)
        fillPath.lineTo(paddingLeft, height)
        fillPath.close()
        val fillAlpha = (separateFillAlpha ?: (alpha * 0.2f)).coerceIn(0f, 1f)
        val fillColor = if (color == Color.White) Color(0xFF9E47F5).copy(alpha = (alpha * 0.15f).coerceIn(0f, 1f)) else color.copy(alpha = fillAlpha)
        drawPath(fillPath, fillColor)
    }

    drawPath(path, color.copy(alpha = alpha.coerceIn(0f, 1f)), style = Stroke(strokeWidth))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawProfessionalGrid(
    width: Float,
    height: Float,
    dbRange: Float
) {
    // ИЗМЕНЕНИЕ: минимальные отступы для устранения рамок
    val paddingLeft = 40f
    val paddingBottom = 25f
    val paddingTop = 5f
    val paddingRight = 5f
    val availableWidth = width - paddingLeft - paddingRight
    val availableHeight = height - paddingBottom - paddingTop

    // Горизонтальные линии (уровни dB)
    val dbLevels = listOf(-80, -60, -40, -20, 0, 20)
    for (db in dbLevels) {
        val normalizedDb = (db + dbRange) / dbRange
        val y = height - paddingBottom - (normalizedDb * availableHeight)
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(paddingLeft, y),
            end = Offset(width - paddingRight, y),
            strokeWidth = 0.5f
        )
    }

    // Вертикальные линии (частоты)
    // ИЗМЕНЕНИЕ: обновленные частоты для расширенного диапазона
    val freqPoints = listOf(5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 30000)
    // ИЗМЕНЕНИЕ: расширенный диапазон частот 5-30000 Гц
    val logMin = log10(5f)
    val logMax = log10(30000f)
    for (freq in freqPoints) {
        val logFreq = log10(freq.toFloat())
        val x = paddingLeft + ((logFreq - logMin) / (logMax - logMin)) * availableWidth
        if (x in paddingLeft..(width - paddingRight)) {
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(x, paddingTop),
                end = Offset(x, height - paddingBottom),
                strokeWidth = 0.5f
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawProfessionalAxes(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    width: Float,
    height: Float,
    dbRange: Float
) {
    // ИЗМЕНЕНИЕ: минимальные отступы для устранения рамок
    val paddingLeft = 40f
    val paddingBottom = 25f
    val paddingTop = 5f
    val paddingRight = 5f
    val availableHeight = height - paddingBottom - paddingTop

    // Подписи уровней dB (слева)
    val dbLevels = listOf(-120,-100,-80, -60, -40, -20, 0, 20)
    for (db in dbLevels) {
        val normalizedDb = (db + dbRange) / dbRange
        val y = height - paddingBottom - (normalizedDb * availableHeight)
        if (y < paddingTop - 8f || y > height - paddingBottom + 8f) continue
        drawText(
            textMeasurer = textMeasurer,
            text = "${db}",
            style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp),
            topLeft = Offset(8f, y - 6f)
        )

    }

    // Подписи частот (снизу)
    // ИЗМЕНЕНИЕ: обновленные частоты для расширенного диапазона
    val freqPoints = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 30000)
    // ИЗМЕНЕНИЕ: расширенный диапазон частот 5-30000 Гц
    val logMin = log10(5f)
    val logMax = log10(30000f)
    val availableWidth = width - paddingLeft - paddingRight

    for (freq in freqPoints) {
        val logFreq = log10(freq.toFloat())
        val x = paddingLeft + ((logFreq - logMin) / (logMax - logMin)) * availableWidth
        if (x in paddingLeft..(width - paddingRight)) {
            val text = when {
                freq >= 1000 -> "${freq / 1000}k"
                else -> "$freq"
            }
            drawText(
                textMeasurer = textMeasurer,
                text = text,
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp),
                topLeft = Offset(x - 8f, height - paddingBottom-10f)
            )
        }
    }
}

@Composable
fun DebugInfo(
    currentMaxDb: Float,
    noiseThreshold: Float,
    currentFps: Int,
    gain: Float,
    dbRange: Float,
    isFileMode: Boolean,
    isGrabActive: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "Mode: ${if (isFileMode) "FILE" else "MIC"}",
                color = Color.White,
                fontSize = 12.sp
            )
            if(isGrabActive) {
                Text(
                    text = "SPECTRUM GRAB ACTIVE",
                    color = Color(0xFF9E47F5),
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            Text(
                text = "FPS: $currentFps",
                color = when {
                    currentFps >= 58 -> Color.Green
                    currentFps >= 50 -> Color(0xFFCC9900)
                    else -> Color.Red
                },
                fontSize = 12.sp
            )
            Text(
                text = "Усиление: ${(gain * 100).toInt()}%",
                color = Color.White,
                fontSize = 10.sp
            )
            Text(
                text = "Диапазон: ${dbRange.toInt()}dB",
                color = Color.White,
                fontSize = 10.sp
            )
            Text(
                text = "Макс: ${"%.1f".format(currentMaxDb)} dB",
                color = Color.White,
                fontSize = 12.sp
            )
            Text(
                text = "Порог: ${noiseThreshold.toInt()} dB",
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    noiseThreshold: Float,
    onNoiseThresholdChange: (Float) -> Unit,
    smoothingFactor: Float,
    onSmoothingChange: (Float) -> Unit,
    speedFactor: Float,
    onSpeedChange: (Float) -> Unit,
    gain: Float,
    onGainChange: (Float) -> Unit,
    dbRange: Float,
    onDbRangeChange: (Float) -> Unit,
    tilt: Float,
    onTiltChange: (Float) -> Unit,
    currentFps: Int,
    onClose: () -> Unit
) {
    Card(modifier = Modifier
        .width(340.dp)
        .height(620.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Настройки спектр-анализатора",
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.width(32.dp).height(32.dp)
                ) {
                    Text("✕", color = Color.Black, fontSize = 18.sp)
                }
            }
            Column {
                Text(
                    text = "Производительность",
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "FPS: $currentFps",
                    color = when {
                        currentFps >= 58 -> Color.Green
                        currentFps >= 50 -> Color(0xFFCC9900)
                        else -> Color.Red
                    },
                    fontSize = 14.sp
                )
                Text(
                    text = "• БПФ 4096 точек\n• 800 точек отрисовки\n• Высокое разрешение пиков",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            Column {
                Text(
                    text = "Усиление: ${(gain * 100).toInt()}%",
                    fontSize = 14.sp
                )
                Slider(
                    value = gain,
                    onValueChange = onGainChange,
                    valueRange = 0.001f..0.2f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Очень чувствительный микрофон\nНачинайте с 1-5%",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            Column {
                Text(
                    text = "Диапазон dB: ${dbRange.toInt()}",
                    fontSize = 14.sp
                )
                Slider(
                    value = dbRange,
                    onValueChange = onDbRangeChange,
                    valueRange = 40f..120f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Меньше - видно тихие сигналы\nБольше - видно громкие сигналы",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            Column {
                Text(
                    text = "Скорость реакции: ${(speedFactor * 100).toInt()}%",
                    fontSize = 14.sp
                )
                Slider(
                    value = speedFactor,
                    onValueChange = onSpeedChange,
                    valueRange = 0.1f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Меньше - плавнее как FabFilter\nБольше - быстрее реакция",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            Column {
                Text(
                    text = "Сглаживание: ${(smoothingFactor * 100).toInt()}%",
                    fontSize = 14.sp
                )
                Slider(
                    value = smoothingFactor,
                    onValueChange = onSmoothingChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Убирает мелкие колебания",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            Column {
                Text(
                    text = "Наклон спектра: ${"%.1f".format(tilt)} dB/okt",
                    fontSize = 14.sp
                )
                Slider(
                    value = tilt,
                    onValueChange = onTiltChange,
                    valueRange = 0f..6f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "0 - сырой спектр\n4.5 - как слух (FabFilter default)",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            Column {
                Text(
                    text = "Порог шума: ${noiseThreshold.toInt()} dB",
                    fontSize = 14.sp
                )
                Slider(
                    value = noiseThreshold,
                    onValueChange = onNoiseThresholdChange,
                    valueRange = -80f..0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


@Composable
fun ToolsPanel(
    dominantFrequency: Double,
    noteName: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Инструмент: Анализатор нот",
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "Пиковая частота: ${"%.1f".format(dominantFrequency)} Гц",
                color = Color.Black,
                fontSize = 14.sp
            )
            Text(
                text = "Нота: $noteName",
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Text(
                text = "(будущий инструмент - аналог Melodyne)",
                color = Color.Gray,
                fontSize = 10.sp
            )
        }
    }
}



data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)