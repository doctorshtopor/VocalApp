package com.example.vocalapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jtransforms.fft.DoubleFFT_1D

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                setupAndShowUI()
            } else {
                setContent {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Разрешение на использование микрофона не предоставлено.")
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (checkSelfPermission(Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> setupAndShowUI()
            else -> requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupAndShowUI() {
        setContent {
            var dominantFrequency by remember { mutableStateOf(0.0) }

            // Этот LaunchedEffect запускает сбор данных при первом отображении UI
            LaunchedEffect(Unit) {
                readAudio()
                    .collect { frequency ->
                        dominantFrequency = frequency
                    }
            }

            // UI, который будет отображаться
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Используем форматирование для красивого вывода
                Text(text = "Пиковая частота: %.1f Гц".format(dominantFrequency))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun readAudio(): Flow<Double> = flow {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        // Используем буфер побольше для лучшей точности на низких частотах
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

        val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)

        val audioBuffer = ShortArray(bufferSize)
        val fft = DoubleFFT_1D(bufferSize.toLong())
        val spectrum = DoubleArray(bufferSize * 2)

        try {
            audioRecord.startRecording()

            while (true) {
                audioRecord.read(audioBuffer, 0, bufferSize)

                for (i in audioBuffer.indices) {
                    spectrum[i] = audioBuffer[i].toDouble()
                }
                fft.realForward(spectrum)

                val magnitudes = DoubleArray(bufferSize / 2)
                for (i in magnitudes.indices) {
                    val realPart = spectrum[i * 2]
                    val imagPart = spectrum[i * 2 + 1]
                    magnitudes[i] = Math.sqrt(realPart * realPart + imagPart * imagPart)
                }

                var maxIndex = 0
                // Начинаем с 1, чтобы игнорировать постоянный ток (0 Гц)
                for (i in 1 until magnitudes.size) {
                    if (magnitudes[i] > magnitudes[maxIndex]) {
                        maxIndex = i
                    }
                }

                val frequency = maxIndex.toDouble() * sampleRate.toDouble() / bufferSize.toDouble()

                emit(frequency)
            }
        } finally {
            // Важно освободить ресурсы, когда корутина отменяется
            audioRecord.stop()
            audioRecord.release()
        }
    }.flowOn(Dispatchers.IO)
}

