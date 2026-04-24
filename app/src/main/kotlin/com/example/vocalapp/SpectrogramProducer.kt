package com.example.vocalapp

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.*
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class SpectrogramProducer(
    private val sampleRate: Int = 44100,
    private val fftSize: Int = 2048,
    private val heightPx: Int = 256,
    private val colWidth: Int = 2
) {
    private val fft = DoubleFFT_1D(fftSize.toLong())
    private val window = DoubleArray(fftSize) { i ->
        0.5 * (1 - kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))) // окно Хэнна
    }

    val bitmap = Bitmap.createBitmap(400, heightPx, Bitmap.Config.ARGB_8888)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun stop() = scope.cancel()

    fun pushAudioFrame(shorts: ShortArray, onBitmapUpdated: (Bitmap) -> Unit) {
        scope.launch {
            val fftBuffer = DoubleArray(fftSize)
            for (i in shorts.indices) {
                fftBuffer[i] = shorts[i].toDouble() * window[i]
            }

            fft.realForward(fftBuffer)

            val magnitudes = DoubleArray(fftSize / 2)
            for (i in 0 until fftSize / 2) {
                val re = fftBuffer[2 * i]
                val im = fftBuffer[2 * i + 1]
                magnitudes[i] = sqrt(re * re + im * im)
            }

            val col = IntArray(heightPx)
            for (y in 0 until heightPx) {
                val frac = 1.0 - y.toDouble() / heightPx
                val bin = (frac * (fftSize / 2 - 1)).toInt()
                val mag = magnitudes.getOrElse(bin) { 0.0 }
                val db = 20 * log10(max(1e-6, mag))
                val color = dbToColor(db)
                col[y] = color
            }

            drawColumn(col)

            withContext(Dispatchers.Main) {
                onBitmapUpdated(bitmap)
            }
        }
    }

    private fun dbToColor(db: Double): Int {
        val norm = ((db + 100) / 100).coerceIn(0.0, 1.0)
        val r = (255 * norm).toInt()
        val g = (255 * norm * norm).toInt()
        val b = (255 * (1 - norm)).toInt()
        return Color.rgb(r, g, b)
    }

    private fun drawColumn(col: IntArray) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // сдвигаем всё влево
        for (y in 0 until h) {
            for (x in 0 until w - colWidth) {
                pixels[y * w + x] = pixels[y * w + x + colWidth]
            }
        }

        // добавляем новую колонку справа
        val startX = w - colWidth
        for (y in 0 until h) {
            for (dx in 0 until colWidth) {
                pixels[y * w + startX + dx] = col[y]
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
