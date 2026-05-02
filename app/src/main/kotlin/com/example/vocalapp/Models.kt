package com.example.vocalapp

import kotlin.math.ln
import kotlin.math.roundToInt

// =====================================================================
//  Constants
// =====================================================================

// Audio / FFT
const val SAMPLE_RATE = 44100
const val FILE_FFT_SIZE = 4096
const val RING_BUFFER_CAPACITY = 10 * SAMPLE_RATE      // ~10 s of mono PCM
const val MIC_BUFFER_CAPACITY = 8 * 4096               // 32 768 shorts
const val DECODER_TIMEOUT_US = 5_000L

// Spectrum display
const val DISPLAY_BINS = 2049
const val FREQ_LOG_MIN = 5f
const val FREQ_LOG_MAX = 30_000f

// Pitch tracking
const val PITCH_HISTORY_WINDOW_MS = 8_000L
const val NOTE_TOLERANCE_SEMITONES = 0.5f
const val NOTE_GAP_THRESHOLD_MS = 200L                 // gap that splits one note block into two

// =====================================================================
//  Data classes
// =====================================================================

/** User-tunable spectrum analyzer settings. */
data class SpectrumSettings(
    val noiseThreshold: Float = -35f,
    val smoothingFactor: Float = 0.85f,
    val speedFactor: Float = 0.7f,
    val gain: Float = 0.05f,
    val dbRange: Float = 80f,
    val tilt: Float = 4.5f
)

/** Single pitch sample at a given timestamp. freq == 0.0 means silence. */
data class PitchPoint(
    val timeMs: Long,
    val freq: Double,
    val midi: Float
)

/** A single pitch sample extracted from a reference (ghost) audio source. */
data class GhostPoint(val timeMs: Long, val midi: Float)

/** A contiguous run of pitch samples that belong to the same note. */
data class NoteBlock(
    val startMs: Long,
    val endMs: Long,     // always updated via .copy(), never mutated in-place
    val midi: Float,
    val noteName: String
)

/** Settings specific to the pitch / note analysis mode. */
data class AnalysisSettings(
    /** Signals below this level (dBFS) are treated as silence — pitch is not detected. */
    val noiseGateDb: Float = -28f,
    /** How many minutes of pitch history to keep for scroll-back while paused. */
    val historyWindowMinutes: Float = 2f
)

/** Snapshot of the spectrum/pitch state, emitted by the audio engine each frame. */
data class AudioFrame(
    val spectrum: FloatArray,
    val maxDb: Float,
    val frequency: Double,
    val noteName: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return maxDb == other.maxDb &&
                frequency == other.frequency &&
                noteName == other.noteName &&
                spectrum.contentEquals(other.spectrum)
    }

    override fun hashCode(): Int {
        var result = spectrum.contentHashCode()
        result = 31 * result + maxDb.hashCode()
        result = 31 * result + frequency.hashCode()
        result = 31 * result + noteName.hashCode()
        return result
    }

    companion object {
        val EMPTY = AudioFrame(
            spectrum = FloatArray(DISPLAY_BINS),
            maxDb = -80f,
            frequency = 0.0,
            noteName = "..."
        )
    }
}

// =====================================================================
//  Utility functions
// =====================================================================

/** Convert frequency (Hz) to fractional MIDI note number. Returns 0f for silence. */
fun freqToMidi(freq: Double): Float {
    if (freq <= 0.0) return 0f
    return (69.0 + 12.0 * (ln(freq / 440.0) / ln(2.0))).toFloat()
}

/** Convert frequency (Hz) to a human-readable note name like "A4", "C#5". */
fun frequencyToNote(frequency: Double): String {
    if (frequency < 16.35 || frequency > 4186.0) return "---"
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val semitones = 12.0 * (ln(frequency / 440.0) / ln(2.0))
    val noteIndex = ((semitones + 69.0).roundToInt() % 12).let { if (it < 0) it + 12 else it }
    val octave = ((semitones.roundToInt() + 69) / 12) - 1
    return "${noteNames[noteIndex]}$octave"
}

/** "mm:ss" format for player UI. */
fun formatTime(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val min = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(min, s)
}