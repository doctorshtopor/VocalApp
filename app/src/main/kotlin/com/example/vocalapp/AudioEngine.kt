package com.example.vocalapp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jtransforms.fft.DoubleFFT_1D
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "AudioEngine"

// =====================================================================
//  Pitch detector (NCCF + band-pass)
// =====================================================================

/**
 * Lightweight pitch detector based on the Normalised Cross-Correlation Function.
 * Robust enough for vocal signals in the 40 Hz – 900 Hz range.
 */
class PitchDetector(val sampleRate: Int) {
    private val minFreq = 40f
    private val maxFreq = 900f

    /** Returns the detected fundamental frequency in Hz, or 0f if no clear pitch found. */
    fun detect(frame: ShortArray): Float {
        if (frame.size < sampleRate / minFreq) return 0f

        val x = FloatArray(frame.size) { frame[it] / 32768f }
        val filtered = bandPass(x)
        val nccf = nccf(filtered)

        val minLag = (sampleRate / maxFreq).toInt()
        val maxLag = (sampleRate / minFreq).toInt().coerceAtMost(nccf.size - 2)

        var bestLag = -1
        var bestVal = 0f

        for (lag in minLag until maxLag) {
            if (nccf[lag] > 0.65f &&
                nccf[lag] > bestVal &&
                nccf[lag] > nccf[lag - 1] &&
                nccf[lag] > nccf[lag + 1]
            ) {
                bestVal = nccf[lag]
                bestLag = lag
            }
        }

        if (bestLag <= 0) return 0f

        // Octave correction: prefer a half-lag peak if it is nearly as strong
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
            out[lag] = if (den > 0f) 2f * num / den else 0f
        }
        return out
    }

    private fun bandPass(x: FloatArray): FloatArray {
        val y = x.copyOf()

        // High-pass at ~40 Hz
        var prev = 0f
        for (i in y.indices) {
            val cur = y[i]
            y[i] = cur - prev * 0.995f
            prev = cur
        }

        // Low-pass at ~900 Hz
        var acc = 0f
        for (i in y.indices) {
            acc += 0.1f * (y[i] - acc)
            y[i] = acc
        }

        return y
    }
}

// =====================================================================
//  Spectrum processor (FFT → log-frequency display bins)
// =====================================================================

internal class SpectrumProcessor {

    private val hann = createHanningWindow(FILE_FFT_SIZE)
    private val fftBuffer = DoubleArray(FILE_FFT_SIZE * 2)
    private val fft = DoubleFFT_1D(FILE_FFT_SIZE.toLong())
    private val logMin = log10(FREQ_LOG_MIN.toDouble())
    private val logMax = log10(FREQ_LOG_MAX.toDouble())

    /**
     * Process [pcm] (mono 16-bit samples, exactly [FILE_FFT_SIZE] of them) into:
     *  - a [DISPLAY_BINS]-sized array of normalised values for the spectrum display,
     *  - the raw maximum dB level seen this frame,
     *  - the raw spectral peak frequency (used as a fallback for pitch).
     *
     * Smoothing across time is applied using [previousSpectrum].
     */
    fun process(
        pcm: ShortArray,
        sampleRate: Int,
        settings: SpectrumSettings,
        previousSpectrum: FloatArray
    ): SpectrumResult {
        // Load samples + Hann window
        for (i in 0 until FILE_FFT_SIZE) {
            fftBuffer[i] = if (i < pcm.size) pcm[i].toDouble() / 32768.0 * hann[i] else 0.0
        }
        for (i in FILE_FFT_SIZE until fftBuffer.size) fftBuffer[i] = 0.0

        fft.realForward(fftBuffer)

        val half = FILE_FFT_SIZE / 2
        val magnitudes = DoubleArray(half)
        var maxMagnitude = 1e-12
        var maxIndex = 0
        for (i in 0 until half) {
            val re = fftBuffer[2 * i]
            val im = if (2 * i + 1 < fftBuffer.size) fftBuffer[2 * i + 1] else 0.0
            val mag = sqrt(re * re + im * im)
            magnitudes[i] = mag
            if (mag > maxMagnitude && i > 0) {
                maxMagnitude = mag
                maxIndex = i
            }
        }

        // dB + tilt + gain
        val dbs = DoubleArray(half)
        var frameMaxDb = -200.0
        for (i in 0 until half) {
            var db = 20.0 * log10(magnitudes[i].coerceAtLeast(1e-12))
            val freq = i.toDouble() * sampleRate / FILE_FFT_SIZE
            if (freq > 20.0) {
                db += settings.tilt * (ln(freq / 1000.0) / ln(2.0))
            }
            db += settings.gain * 20.0
            dbs[i] = db
            if (db > frameMaxDb) frameMaxDb = db
        }

        // Map onto log-frequency display bins (cubic interpolation)
        val rawDisplay = FloatArray(DISPLAY_BINS)
        for (i in 0 until DISPLAY_BINS) {
            val t = i.toDouble() / (DISPLAY_BINS - 1)
            val logPos = logMin + t * (logMax - logMin)
            val freq = 10.0.pow(logPos)

            val exactBin = ((freq / sampleRate) * FILE_FFT_SIZE).coerceIn(0.0, (half - 2).toDouble())
            val index = exactBin.toInt()
            val fraction = exactBin - index

            val y0 = dbs.getOrElse(index - 1) { dbs[index] }
            val y1 = dbs[index]
            val y2 = dbs.getOrElse(index + 1) { dbs[index] }
            val y3 = dbs.getOrElse(index + 2) { y2 }

            val dbValue = cubicInterpolate(y0, y1, y2, y3, fraction)
            val normalised = if (dbValue > settings.noiseThreshold) {
                ((dbValue - settings.noiseThreshold) / settings.dbRange).coerceAtLeast(0.0)
            } else 0.0
            rawDisplay[i] = normalised.toFloat()
        }

        // Frequency-domain smoothing (single 5-tap pass)
        val freqSmoothed = FloatArray(DISPLAY_BINS)
        for (i in 0 until DISPLAY_BINS) {
            val v0 = rawDisplay.getOrElse(i - 2) { rawDisplay[i] }
            val v1 = rawDisplay.getOrElse(i - 1) { rawDisplay[i] }
            val v2 = rawDisplay[i]
            val v3 = rawDisplay.getOrElse(i + 1) { rawDisplay[i] }
            val v4 = rawDisplay.getOrElse(i + 2) { rawDisplay[i] }
            freqSmoothed[i] = v0 * 0.06f + v1 * 0.24f + v2 * 0.4f + v3 * 0.24f + v4 * 0.06f
        }

        // Time-domain smoothing (asymmetric attack/decay)
        val finalDisplay = FloatArray(DISPLAY_BINS)
        val attack = 0.9f
        val decay = 0.80f + (settings.smoothingFactor * 0.19f)
        for (i in 0 until DISPLAY_BINS) {
            val cur = freqSmoothed[i]
            val prev = previousSpectrum.getOrElse(i) { 0f }
            finalDisplay[i] = if (cur > prev) prev * (1 - attack) + cur * attack
            else prev * decay + cur * (1 - decay)
        }

        // Spectral fallback frequency (used if pitch detector fails)
        val fallbackFreq = if (maxMagnitude > 0.001) {
            (maxIndex.toDouble() * sampleRate / FILE_FFT_SIZE).coerceIn(0.0, 1200.0)
        } else 0.0

        return SpectrumResult(finalDisplay, frameMaxDb.toFloat(), fallbackFreq)
    }

    private fun cubicInterpolate(y0: Double, y1: Double, y2: Double, y3: Double, mu: Double): Double {
        val a0 = y3 - y2 - y0 + y1
        val a1 = y0 - y1 - a0
        val a2 = y2 - y0
        val a3 = y1
        return a0 * mu * mu * mu + a1 * mu * mu + a2 * mu + a3
    }

    private fun createHanningWindow(size: Int): DoubleArray =
        DoubleArray(size) { i -> 0.5 * (1.0 - cos(2.0 * PI * i / (size - 1))) }
}

internal data class SpectrumResult(
    val display: FloatArray,
    val maxDb: Float,
    val fallbackFreq: Double
)

// =====================================================================
//  AudioEngine — public facade for everything audio
// =====================================================================

@UnstableApi
class AudioEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {

    enum class Mode { IDLE, MIC, FILE }

    // --- Public reactive state ---------------------------------------------------------------

    private val _audioFrame = MutableStateFlow(AudioFrame.EMPTY)
    val audioFrame: StateFlow<AudioFrame> = _audioFrame.asStateFlow()

    private val _pitchEvents = MutableSharedFlow<PitchPoint>(extraBufferCapacity = 64)
    val pitchEvents: SharedFlow<PitchPoint> = _pitchEvents.asSharedFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(1L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _mode = MutableStateFlow(Mode.IDLE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    var settings: SpectrumSettings = SpectrumSettings()
        @Synchronized get
        @Synchronized set

    var analysisSettings: AnalysisSettings = AnalysisSettings()
        @Synchronized get
        @Synchronized set

    // --- Internal state ----------------------------------------------------------------------

    private val ringBuffer = RingBuffer(RING_BUFFER_CAPACITY)
    private val micRingBuffer = RingBuffer(MIC_BUFFER_CAPACITY)
    private val spectrumProcessor = SpectrumProcessor()
    private var pitchDetector = PitchDetector(SAMPLE_RATE)

    private var fileSampleRate = SAMPLE_RATE
    private var smoothedFrequency = 0.0
    // Sliding window of recent raw MIDI detections for consensus filtering
    private val pitchWindowMidi = ArrayDeque<Float>(8)

    private var micRecordJob: Job? = null
    private var micFftJob: Job? = null
    private var fileFftJob: Job? = null
    private var decoderJob: Job? = null
    private var positionUpdaterJob: Job? = null

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var mediaFormat: MediaFormat? = null

    private var exoPlayer: ExoPlayer? = null
    private var currentUri: Uri? = null
    private val atomicPlayerPosition = AtomicLong(0)
    @Volatile private var visualizerSyncTimeMs = 0L
    @Volatile private var shouldProcessAudio = false

    // --- Public API --------------------------------------------------------------------------

    /** Switch to microphone mode and start recording. Caller must hold RECORD_AUDIO permission. */
    fun startMicMode() {
        scope.launch(Dispatchers.Main) {
            stopAllInternal()
            _mode.value = Mode.MIC
            _isLoading.value = false
            startMicRecording()
            startMicFftCollector()
        }
    }

    /** Switch to file mode, load [uri] into ExoPlayer and prepare the streaming decoder. */
    fun loadFile(uri: Uri) {
        scope.launch(Dispatchers.Main) {
            stopAllInternal()
            _mode.value = Mode.FILE
            _isLoading.value = true
            currentUri = uri

            val player = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_SYSTEM)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                setHandleAudioBecomingNoisy(true)
                addListener(playerListener)
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
            exoPlayer = player

            atomicPlayerPosition.set(0L)
            visualizerSyncTimeMs = 0L
            _positionMs.value = 0L

            startPositionUpdater()
            startDecoderAndFFT(uri, 0L)
            _isLoading.value = false
            Log.d(TAG, "File loaded: $uri")
        }
    }

    fun play() {
        scope.launch(Dispatchers.Main) { exoPlayer?.play() }
    }

    fun pause() {
        scope.launch(Dispatchers.Main) { exoPlayer?.pause() }
    }

    fun seekTo(positionMs: Long) {
        scope.launch(Dispatchers.Main) { exoPlayer?.seekTo(positionMs) }
    }

    /** Whether the player has reached the end of the track. */
    fun isAtEnd(): Boolean {
        val player = exoPlayer ?: return false
        return player.duration > 0 && player.currentPosition >= player.duration - 50
    }

    /** Release everything; call from Activity.onDestroy. */
    fun release() {
        scope.launch(Dispatchers.Main) {
            stopAllInternal()
        }
    }

    // --- Lifecycle helpers -------------------------------------------------------------------

    private fun stopAllInternal() {
        // Cancel jobs first, then release native resources
        micRecordJob?.cancel(); micRecordJob = null
        micFftJob?.cancel();    micFftJob = null
        fileFftJob?.cancel();   fileFftJob = null
        positionUpdaterJob?.cancel(); positionUpdaterJob = null

        cleanupDecoder()

        exoPlayer?.let {
            try {
                it.removeListener(playerListener)
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "ExoPlayer release error: ${e.message}")
            }
        }
        exoPlayer = null
        currentUri = null
        shouldProcessAudio = false

        _isPlaying.value = false
        _isLoading.value = false
        _positionMs.value = 0L
        _durationMs.value = 1L

        ringBuffer.clear()
        micRingBuffer.clear()
        smoothedFrequency = 0.0
        pitchWindowMidi.clear()
    }

    private fun cleanupDecoder() {
        decoderJob?.cancel(); decoderJob = null
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        try { extractor?.release() } catch (_: Exception) {}
        decoder = null
        extractor = null
        mediaFormat = null
        ringBuffer.clear()
    }

    // --- Microphone --------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startMicRecording() {
        micRecordJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
            val buffer = ShortArray(1024)
            try {
                record.startRecording()
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) micRingBuffer.write(buffer, 0, read)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mic record error: ${e.message}")
            } finally {
                try { record.stop() } catch (_: Exception) {}
                try { record.release() } catch (_: Exception) {}
            }
        }
    }

    private fun startMicFftCollector() {
        micFftJob = scope.launch(Dispatchers.Default) {
            var prevSpectrum = FloatArray(DISPLAY_BINS)
            while (isActive && _mode.value == Mode.MIC) {
                if (micRingBuffer.available() < FILE_FFT_SIZE) {
                    delay(5)
                    continue
                }
                val pcm = micRingBuffer.peek(FILE_FFT_SIZE)
                val pitchFrame = if (pcm.size >= 2048) pcm.copyOfRange(0, 2048) else null

                val res = spectrumProcessor.process(pcm, SAMPLE_RATE, settings, prevSpectrum)
                prevSpectrum = res.display

                val now = System.currentTimeMillis()
                val pitchFreq = detectAndSmoothPitch(pitchFrame, SAMPLE_RATE)
                val gateDb = analysisSettings.noiseGateDb
                val aboveGate = res.maxDb >= gateDb
                val effectiveFreq = if (pitchFreq > 30.0 && aboveGate) pitchFreq else 0.0
                val noteName = if (effectiveFreq > 20.0) frequencyToNote(effectiveFreq) else "..."

                _audioFrame.value = AudioFrame(res.display, res.maxDb, effectiveFreq, noteName)

                if (effectiveFreq > 20.0) {
                    _pitchEvents.tryEmit(PitchPoint(now, effectiveFreq, freqToMidi(effectiveFreq)))
                } else {
                    // Emit a silence marker so the UI can split note blocks across pauses.
                    _pitchEvents.tryEmit(PitchPoint(now, 0.0, 0f))
                }

                // Advance the ring buffer to keep latency bounded
                val available = micRingBuffer.available()
                val target = (SAMPLE_RATE * 0.016).toInt()
                val toConsume = if (available > 8192) available - 4096 else target
                micRingBuffer.consume(min(toConsume, available))

                delay(16)
            }
        }
    }

    // --- File mode: ExoPlayer + streaming MediaCodec for FFT ---------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    val d = exoPlayer?.duration ?: 1L
                    _durationMs.value = d.coerceAtLeast(1L)
                }
                Player.STATE_ENDED -> {
                    shouldProcessAudio = false
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            shouldProcessAudio = isPlaying
            if (!isPlaying) {
                // Stop the streaming decoder while paused so the ring buffer doesn't fill up
                decoderJob?.cancel(); decoderJob = null
            } else {
                val pos = atomicPlayerPosition.get()
                val uri = currentUri ?: return
                ringBuffer.clear()
                visualizerSyncTimeMs = pos
                startStreamingDecoder(uri, pos)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val newPos = newPosition.positionMs
            atomicPlayerPosition.set(newPos)
            _positionMs.value = newPos
            visualizerSyncTimeMs = newPos
            ringBuffer.clear()
            currentUri?.let { uri ->
                decoderJob?.cancel(); decoderJob = null
                // Only restart the streaming decoder if playback is active.
                // While paused we leave the ring buffer empty — onIsPlayingChanged
                // will start a fresh decoder when Play is pressed.
                if (shouldProcessAudio) {
                    startStreamingDecoder(uri, newPos)
                }
            }
        }
    }

    private fun startPositionUpdater() {
        positionUpdaterJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val player = exoPlayer
                if (player != null && player.isPlaying) {
                    val pos = player.currentPosition
                    atomicPlayerPosition.set(pos)
                    _positionMs.value = pos
                }
                delay(16)
            }
        }
    }

    private fun startDecoderAndFFT(uri: Uri, startPositionMs: Long) {
        startStreamingDecoder(uri, startPositionMs)
        startFileFftCollector()
    }

    private fun startStreamingDecoder(uri: Uri, startPositionMs: Long) {
        decoderJob?.cancel()

        decoderJob = scope.launch(Dispatchers.IO) {
            // Each coroutine owns its own MediaExtractor / MediaCodec via locals
            // (`ex`, `dec` below). Old coroutines clean themselves up in their
            // own `finally` block — we no longer share via the top-level
            // `decoder`/`extractor` fields for cleanup, so there's no risk of
            // a newly-started decoder being released by a dying old one.

            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
            } catch (e: Exception) {
                Log.e(TAG, "Extractor setDataSource failed: ${e.message}")
                return@launch
            }

            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    ex.selectTrack(i)
                    format = f
                    break
                }
            }
            if (format == null) {
                Log.e(TAG, "No audio track in file")
                ex.release()
                return@launch
            }
            ex.seekTo(startPositionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            extractor = ex
            mediaFormat = format
            fileSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                Log.e(TAG, "Missing MIME type")
                return@launch
            }

            val dec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decoder create failed: ${e.message}")
                return@launch
            }
            decoder = dec

            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var extractorFinished = false
            var emptyReads = 0
            val info = MediaCodec.BufferInfo()

            try {
                while (currentCoroutineContext().isActive) {
                    if (ringBuffer.available() > RING_BUFFER_CAPACITY - 4096) {
                        delay(10)
                        continue
                    }

                    if (!extractorFinished) {
                        val inIdx = dec.dequeueInputBuffer(DECODER_TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = dec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val sampleSize = ex.readSampleData(inBuf, 0)
                                if (sampleSize > 0) {
                                    dec.queueInputBuffer(inIdx, 0, sampleSize, ex.sampleTime, 0)
                                    ex.advance()
                                    emptyReads = 0
                                } else {
                                    emptyReads++
                                    if (emptyReads > 5) {
                                        dec.queueInputBuffer(
                                            inIdx, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        extractorFinished = true
                                    }
                                }
                            }
                        }
                    }

                    val outIdx = dec.dequeueOutputBuffer(info, DECODER_TIMEOUT_US)
                    if (outIdx >= 0) {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val sb: ShortBuffer = outBuf.asShortBuffer()
                            val numShorts = info.size / 2
                            val mono = ShortArray((numShorts + channelCount - 1) / channelCount)
                            var w = 0
                            var i = 0
                            while (i < numShorts) {
                                mono[w++] = sb.get(i)
                                i += channelCount
                            }
                            if (w > 0) ringBuffer.write(mono, 0, w)
                        }
                        try { dec.releaseOutputBuffer(outIdx, false) } catch (_: Exception) {}
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else {
                        delay(2)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decoder loop error: ${e.message}")
            } finally {
                // Release ONLY the resources owned by this coroutine. The shared
                // `decoder`/`extractor` fields may already point to a newer instance
                // started by a subsequent call — don't touch them in that case.
                try { dec.stop() } catch (_: Exception) {}
                try { dec.release() } catch (_: Exception) {}
                try { ex.release() } catch (_: Exception) {}
                if (decoder === dec) decoder = null
                if (extractor === ex) extractor = null
            }
        }
    }

    private fun startFileFftCollector() {
        fileFftJob?.cancel()
        fileFftJob = scope.launch(Dispatchers.Default) {
            var prevSpectrum = FloatArray(DISPLAY_BINS)
            visualizerSyncTimeMs = atomicPlayerPosition.get()

            while (isActive && _mode.value == Mode.FILE) {
                if (!shouldProcessAudio) {
                    delay(30)
                    visualizerSyncTimeMs = atomicPlayerPosition.get()
                    continue
                }

                val playerPos = atomicPlayerPosition.get()
                if (abs(playerPos - visualizerSyncTimeMs) > 300) {
                    visualizerSyncTimeMs = playerPos
                }

                // Skip ahead in the ring buffer to catch up with the player
                val timeDiffMs = playerPos - visualizerSyncTimeMs
                if (timeDiffMs > 0) {
                    val samplesToSkip = (timeDiffMs * fileSampleRate / 1000).toInt()
                    val available = ringBuffer.available()
                    val toConsume = min(samplesToSkip, available)
                    if (toConsume > 0) {
                        ringBuffer.consume(toConsume)
                        visualizerSyncTimeMs += toConsume * 1000L / fileSampleRate
                    }
                }

                if (ringBuffer.available() < FILE_FFT_SIZE) {
                    delay(10)
                    continue
                }

                val pcm = ringBuffer.peek(FILE_FFT_SIZE)
                val pitchFrame = if (pcm.size >= 2048) pcm.copyOfRange(0, 2048) else null

                val res = spectrumProcessor.process(pcm, fileSampleRate, settings, prevSpectrum)
                prevSpectrum = res.display

                val pitchFreq = detectAndSmoothPitch(pitchFrame, fileSampleRate)
                val gateDb = analysisSettings.noiseGateDb
                val aboveGate = res.maxDb >= gateDb
                val effectiveFreq = if (pitchFreq > 30.0 && aboveGate) pitchFreq else 0.0
                val noteName = if (effectiveFreq > 20.0) frequencyToNote(effectiveFreq) else "..."

                _audioFrame.value = AudioFrame(res.display, res.maxDb, effectiveFreq, noteName)

                // Use visualizerSyncTimeMs (file position) as the timestamp so it matches
                // currentTimeMs = positionMs in the UI. Using System.currentTimeMillis()
                // here would create a mismatch and filter out all points in PitchRollDisplay.
                val fileTimeMs = visualizerSyncTimeMs
                if (effectiveFreq > 20.0) {
                    _pitchEvents.tryEmit(PitchPoint(fileTimeMs, effectiveFreq, freqToMidi(effectiveFreq)))
                } else {
                    _pitchEvents.tryEmit(PitchPoint(fileTimeMs, 0.0, 0f))
                }

                // Advance ~16 ms of audio per frame so we don't keep peeking the same window
                val frameSamples = (fileSampleRate * 16 / 1000).coerceAtLeast(1)
                val avail = ringBuffer.available()
                if (avail > FILE_FFT_SIZE) {
                    val toConsume = min(frameSamples, avail - FILE_FFT_SIZE / 2)
                    if (toConsume > 0) {
                        ringBuffer.consume(toConsume)
                        visualizerSyncTimeMs += toConsume * 1000L / fileSampleRate
                    }
                }

                delay(16)
            }
        }
    }

    // --- Shared helpers ----------------------------------------------------------------------

    /**
     * Run pitch detection on [frame] and return a smoothed frequency.
     * Smoothing alpha is adaptive: stronger smoothing when the new frequency is far from the
     * previous one (likely an outlier), lighter when it tracks closely.
     */
    /**
     * Returns a stable pitch frequency using a sliding consensus window.
     *
     * Simple exponential smoothing averages real notes with detection errors, producing
     * intermediate garbage values. Consensus voting instead:
     *   • collects the last WINDOW_SIZE raw MIDI values
     *   • requires AGREEMENT_NEEDED of them to lie within ±SEMITONE_TOL of the median
     *   • only then returns the median pitch; otherwise returns 0 (silence)
     *
     * This filters out NCCF octave-error spikes and breath noise completely,
     * at the cost of ~4-frame onset latency (~90 ms at 23 ms/frame).
     */
    private fun detectAndSmoothPitch(frame: ShortArray?, sampleRate: Int): Double {
        if (frame == null) return 0.0
        if (pitchDetector.sampleRate != sampleRate) {
            pitchDetector = PitchDetector(sampleRate)
            pitchWindowMidi.clear()
        }
        val frameSize = min(frame.size, (sampleRate * 0.06).toInt()).coerceAtLeast(0)
        if (frameSize <= 0) return 0.0
        val sub = if (frameSize == frame.size) frame else frame.copyOfRange(0, frameSize)
        val raw = pitchDetector.detect(sub).toDouble()

        if (raw <= 30.0) {
            // Silence: clear window so next note starts fresh (no stale context)
            pitchWindowMidi.clear()
            smoothedFrequency = 0.0
            return 0.0
        }

        // Convert Hz → MIDI (equal-tempered, A4=69)
        val rawMidi = (12.0 * ln(raw / 440.0) / ln(2.0) + 69.0).toFloat()
        pitchWindowMidi.addLast(rawMidi)
        if (pitchWindowMidi.size > PITCH_WINDOW_SIZE) pitchWindowMidi.removeFirst()

        // Need a full window before committing to a pitch
        if (pitchWindowMidi.size < PITCH_WINDOW_SIZE) return 0.0

        // Median of the window as cluster centre
        val sorted = pitchWindowMidi.sorted()
        val medianMidi = sorted[sorted.size / 2]

        // Count frames that agree within ±SEMITONE_TOL of the median
        val agreeing = pitchWindowMidi.filter { abs(it - medianMidi) <= SEMITONE_TOLERANCE }
        if (agreeing.size < PITCH_AGREEMENT_COUNT) {
            // No consensus — the detector is bouncing (octave errors, noise spikes)
            return 0.0
        }

        // Stable pitch: average of agreeing frames → back to Hz
        val stableMidi = agreeing.average()
        smoothedFrequency = 440.0 * 2.0.pow((stableMidi - 69.0) / 12.0)
        return smoothedFrequency
    }

    /**
     * Offline pitch extraction for the "ghost" overlay feature.
     * Decodes the full audio file and runs pitch detection on every 40 ms frame.
     * Returns a list of GhostPoints with timestamps starting from 0 ms.
     * Runs on Dispatchers.IO, cancellable via coroutine scope.
     *
     * @param onProgress callback with 0..1 progress fraction
     */
    suspend fun extractGhostFromFile(
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): List<GhostPoint> = withContext(Dispatchers.IO) {
        val result = mutableListOf<GhostPoint>()
        val ex = MediaExtractor()
        try {
            try { ex.setDataSource(context, uri, null) }
            catch (e: Exception) { Log.e(TAG, "Ghost extractor failed: ${e.message}"); return@withContext result }

            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    ex.selectTrack(i); format = f; break
                }
            }
            val fmt = format ?: return@withContext result

            val sr       = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch       = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs    = try { fmt.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { -1L }
            val mime     = fmt.getString(MediaFormat.KEY_MIME) ?: return@withContext result

            val dec = try {
                MediaCodec.createDecoderByType(mime).apply { configure(fmt, null, null, 0); start() }
            } catch (e: Exception) { Log.e(TAG, "Ghost decoder failed: ${e.message}"); return@withContext result }

            // Own pitch detector — does not share state with the live detector
            val ghostDet   = PitchDetector(sr)
            val frameSize  = (sr * 0.04f).toInt()   // 40 ms per detection frame
            val monoFrame  = ShortArray(frameSize)
            var bufPos     = 0
            var frameTimeMs = 0L
            val msPerFrame = frameSize * 1000L / sr
            val info       = MediaCodec.BufferInfo()
            var inputDone  = false

            try {
                while (isActive) {
                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(DECODER_TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf = dec.getInputBuffer(inIdx)!!
                            val sz = ex.readSampleData(buf, 0)
                            if (sz > 0) { dec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0); ex.advance() }
                            else { dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        }
                    }
                    val outIdx = dec.dequeueOutputBuffer(info, DECODER_TIMEOUT_US)
                    if (outIdx >= 0) {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val sb = outBuf.asShortBuffer()
                            var i = 0
                            while (i < info.size / 2) {
                                monoFrame[bufPos++] = sb.get(i)
                                i += ch
                                if (bufPos >= frameSize) {
                                    val raw = ghostDet.detect(monoFrame.copyOf()).toDouble()
                                    if (raw > 30.0) {
                                        val midi = (12.0 * ln(raw / 440.0) / ln(2.0) + 69.0).toFloat()
                                        result.add(GhostPoint(frameTimeMs, midi))
                                    }
                                    frameTimeMs += msPerFrame
                                    bufPos = 0
                                    if (durUs > 0) onProgress((frameTimeMs * 1000f / durUs).coerceIn(0f, 1f))
                                }
                            }
                        }
                        try { dec.releaseOutputBuffer(outIdx, false) } catch (_: Exception) {}
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (inputDone) {
                        kotlinx.coroutines.delay(2)
                    }
                }
            } finally {
                try { dec.stop() } catch (_: Exception) {}
                try { dec.release() } catch (_: Exception) {}
            }
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
        result
    }

    companion object {
        private const val PITCH_WINDOW_SIZE    = 4      // ~90 ms at 23 ms/frame
        private const val PITCH_AGREEMENT_COUNT = 3     // 3 of 4 frames must agree
        private const val SEMITONE_TOLERANCE   = 2.0f   // ±2 semitones = same note
    }
}