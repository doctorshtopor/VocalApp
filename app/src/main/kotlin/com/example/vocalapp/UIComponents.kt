package com.example.vocalapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

// =====================================================================
//  Professional spectrum display (real-time + accumulated "grabbed" overlay)
// =====================================================================

@Composable
fun ProfessionalSpectrumDisplay(
    spectrumData: FloatArray,
    accumulatedSpectrum: FloatArray,
    mainAlpha: Float,
    grabbedAlpha: Float,
    settings: SpectrumSettings,
    modifier: Modifier = Modifier
) {
    val noiseThreshold = settings.noiseThreshold
    val dbRange = settings.dbRange
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        // Background
        drawRect(color = Color(0xFF0D0D0D), size = size)

        // Grid + axes
        drawProfessionalGrid(width, height, dbRange)

        val paddingLeft = 40f
        val paddingBottom = 25f
        val paddingTop = 5f
        val paddingRight = 5f
        val availableHeight = height - paddingBottom - paddingTop

        // Noise threshold line
        val thresholdY = height - paddingBottom - ((noiseThreshold + dbRange) / dbRange * availableHeight)
        drawLine(
            color = Color.Red.copy(alpha = 0.4f),
            start = Offset(paddingLeft, thresholdY),
            end = Offset(width - paddingRight, thresholdY),
            strokeWidth = 1f
        )

        // Real-time spectrum (fades to grey while a grabbed snapshot is shown)
        val realColor = lerpColor(Color(0xFF4FC3F7), Color.Gray, mainAlpha)
        val realStrokeWidth = 3f - mainAlpha * 2f
        val realFillAlpha = 0.2f - mainAlpha * 0.2f
        drawSmoothSpectrum(
            spectrum = spectrumData,
            width = width,
            height = height,
            alpha = 1f,
            strokeWidth = realStrokeWidth,
            fill = true,
            color = realColor,
            separateFillAlpha = realFillAlpha
        )

        // Grabbed (frozen / accumulated) spectrum overlay
        if (grabbedAlpha > 0.01f) {
            drawSmoothSpectrum(
                spectrum = accumulatedSpectrum,
                width = width,
                height = height,
                alpha = grabbedAlpha.coerceIn(0f, 1f),
                strokeWidth = 3f,
                fill = true,
                color = Color.White,
                separateFillAlpha = null
            )
        }

        drawProfessionalAxes(textMeasurer, width, height, dbRange)
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt
    )
}

private fun DrawScope.drawSmoothSpectrum(
    spectrum: FloatArray,
    width: Float,
    height: Float,
    alpha: Float,
    strokeWidth: Float,
    fill: Boolean,
    color: Color,
    separateFillAlpha: Float? = null
) {
    val logMin = log10(FREQ_LOG_MIN)
    val logMax = log10(FREQ_LOG_MAX)
    val paddingLeft = 40f
    val paddingBottom = 25f
    val paddingTop = 5f
    val paddingRight = 5f
    val availableWidth = width - paddingLeft - paddingRight
    val availableHeight = height - paddingBottom - paddingTop

    val displayTicks = 600
    val interpolated = FloatArray(displayTicks)
    for (i in 0 until displayTicks) {
        val t = i.toFloat() / (displayTicks - 1)
        val mapped = t * (spectrum.size - 1)
        val idx = mapped.toInt()
        val frac = mapped - idx
        val v1 = spectrum.getOrElse(idx) { 0f }
        val v2 = spectrum.getOrElse(idx + 1) { 0f }
        interpolated[i] = v1 * (1 - frac) + v2 * frac
    }

    // Gaussian blur (sigma=1.5)
    val smoothed = FloatArray(displayTicks)
    val kr = 3
    for (i in interpolated.indices) {
        var sum = 0f
        var w = 0f
        for (k in -kr..kr) {
            val idx = (i + k).coerceIn(0, displayTicks - 1)
            val weight = exp(-(k * k) / (2f * 1.5f * 1.5f)).toFloat()
            sum += interpolated[idx] * weight
            w += weight
        }
        smoothed[i] = sum / w
    }

    val points = ArrayList<Offset>(displayTicks)
    for (i in smoothed.indices) {
        val t = i.toFloat() / (displayTicks - 1)
        val logFreq = logMin + t * (logMax - logMin)
        val x = paddingLeft + ((logFreq - logMin) / (logMax - logMin)) * availableWidth
        var v = smoothed[i]
        if (v > 1f) {
            val excess = v - 1f
            v = 1f + (1f - exp(-excess.toDouble()).toFloat()) * 0.2f
        }
        val y = height - paddingBottom - (v * availableHeight * 0.95f)
        points.add(Offset(x, y))
    }

    val path = Path()
    if (points.isNotEmpty()) {
        path.moveTo(points[0].x, points[0].y)
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val cx = p1.x + (p2.x - p1.x) / 2f
            path.cubicTo(cx, p1.y, cx, p2.y, p2.x, p2.y)
        }
    }

    if (fill) {
        val fillPath = Path()
        fillPath.addPath(path)
        fillPath.lineTo(width - paddingRight, height)
        fillPath.lineTo(paddingLeft, height)
        fillPath.close()
        val fillAlpha = (separateFillAlpha ?: (alpha * 0.2f)).coerceIn(0f, 1f)
        val fillColor = if (color == Color.White)
            Color(0xFF9E47F5).copy(alpha = (alpha * 0.15f).coerceIn(0f, 1f))
        else
            color.copy(alpha = fillAlpha)
        drawPath(fillPath, fillColor)
    }

    drawPath(path, color.copy(alpha = alpha.coerceIn(0f, 1f)), style = Stroke(strokeWidth))
}

private fun DrawScope.drawProfessionalGrid(
    width: Float,
    height: Float,
    dbRange: Float
) {
    val paddingLeft = 40f
    val paddingBottom = 25f
    val paddingTop = 5f
    val paddingRight = 5f
    val availableWidth = width - paddingLeft - paddingRight
    val availableHeight = height - paddingBottom - paddingTop

    val dbLevels = listOf(-80, -60, -40, -20, 0, 20)
    for (db in dbLevels) {
        val normalised = (db + dbRange) / dbRange
        val y = height - paddingBottom - (normalised * availableHeight)
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(paddingLeft, y),
            end = Offset(width - paddingRight, y),
            strokeWidth = 0.5f
        )
    }

    val freqPoints = listOf(5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 30000)
    val logMin = log10(FREQ_LOG_MIN)
    val logMax = log10(FREQ_LOG_MAX)
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

private fun DrawScope.drawProfessionalAxes(
    textMeasurer: TextMeasurer,
    width: Float,
    height: Float,
    dbRange: Float
) {
    val paddingLeft = 40f
    val paddingBottom = 25f
    val paddingTop = 5f
    val paddingRight = 5f
    val availableHeight = height - paddingBottom - paddingTop

    val dbLevels = listOf(-120, -100, -80, -60, -40, -20, 0, 20)
    for (db in dbLevels) {
        val normalised = (db + dbRange) / dbRange
        val y = height - paddingBottom - (normalised * availableHeight)
        if (y < paddingTop - 8f || y > height - paddingBottom + 8f) continue
        drawText(
            textMeasurer = textMeasurer,
            text = "$db",
            style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp),
            topLeft = Offset(8f, y - 6f)
        )
    }

    val freqPoints = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 30000)
    val logMin = log10(FREQ_LOG_MIN)
    val logMax = log10(FREQ_LOG_MAX)
    val availableWidth = width - paddingLeft - paddingRight
    for (freq in freqPoints) {
        val logFreq = log10(freq.toFloat())
        val x = paddingLeft + ((logFreq - logMin) / (logMax - logMin)) * availableWidth
        if (x in paddingLeft..(width - paddingRight)) {
            val text = if (freq >= 1000) "${freq / 1000}k" else "$freq"
            drawText(
                textMeasurer = textMeasurer,
                text = text,
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp),
                topLeft = Offset(x - 8f, height - paddingBottom - 10f)
            )
        }
    }
}

// =====================================================================
//  Pitch roll display (piano roll with playhead and detected note blocks)
// =====================================================================

@Composable
fun PitchRollDisplay(
    historyProvider: () -> List<PitchPoint>,
    blocksProvider: () -> List<NoteBlock>,
    cameraCenterProvider: () -> Float,
    currentTimeMsProvider: () -> Long,
    scrollOffsetMs: Long = 0L,
    isPaused: Boolean = false,
    ghostPointsProvider: (() -> List<GhostPoint>)? = null,
    ghostOffsetMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val visibleTimeWindow = PITCH_HISTORY_WINDOW_MS.toFloat()
    val playheadOffsetRight = 0.85f
    val path = remember { Path() }

    val history     = historyProvider()
    val blocks      = blocksProvider()
    val ghostPoints = ghostPointsProvider?.invoke() ?: emptyList()
    val cameraCenter  = cameraCenterProvider()
    val currentTimeMs = currentTimeMsProvider()
    @Suppress("UNUSED_VARIABLE") val _histSub  = history.size
    @Suppress("UNUSED_VARIABLE") val _blkSub   = blocks.size
    @Suppress("UNUSED_VARIABLE") val _ghostSub = ghostPoints.size

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        // effectiveTimeMs = the timestamp the canvas is currently "centred on".
        // When scrolled back, this is less than currentTimeMs so older content appears.
        val effectiveTimeMs = currentTimeMs - scrollOffsetMs

        val visibleMinMidi = cameraCenter - 6f
        val visibleMaxMidi = cameraCenter + 6f

        val pianoWidth = 70.dp.toPx().coerceAtMost(width * 0.3f)
        if (pianoWidth <= 0) return@Canvas

        val graphAreaWidth = (width - pianoWidth).coerceAtLeast(1f)
        val playheadX = pianoWidth + graphAreaWidth * playheadOffsetRight
        val pixelsPerMs = (graphAreaWidth * playheadOffsetRight) / visibleTimeWindow

        fun timeToX(timeMs: Long): Float {
            val deltaMs = effectiveTimeMs - timeMs
            return playheadX - deltaMs * pixelsPerMs
        }

        // While paused the playhead becomes PART of the scrollable canvas.
        // timeToX(currentTimeMs) = playheadX + scrollOffsetMs*pxPerMs — it moves
        // right as the user scrolls back, and can go off-screen.
        // The right clip also opens to full width so content is not cut at playheadX.
        val playheadDrawX = if (isPaused) timeToX(currentTimeMs) else playheadX
        val rightClip     = if (isPaused) width else playheadX

        fun midiToY(midi: Float): Float {
            val range = (visibleMaxMidi - visibleMinMidi).coerceAtLeast(1f)
            // No coerceIn: notes outside the visible range go to their real
            // off-screen position. clipRect() clips the visual result.
            val normalised = (midi - visibleMinMidi) / range
            return height * (1f - normalised)
        }

        val blackKeys = setOf(1, 3, 6, 8, 10)
        val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        // 1. Lane backgrounds + full-width separator lines
        //    Separator lines span the ENTIRE width (including piano area) so they are
        //    pixel-perfect with the piano key boundaries — no 0.5-px Stroke offset.
        val minMidiInt = floor(visibleMinMidi).toInt() - 1
        val maxMidiInt = ceil(visibleMaxMidi).toInt() + 1
        for (midiNote in minMidiInt..maxMidiInt) {
            val isBlackKey = blackKeys.contains(((midiNote % 12) + 12) % 12)
            val yTop = midiToY(midiNote + 0.5f)
            val yBottom = midiToY(midiNote - 0.5f)
            val laneH = (yBottom - yTop).coerceAtLeast(1f)
            val laneColor = if (isBlackKey) Color(0xFF161616) else Color(0xFF242424)
            drawRect(
                color = laneColor,
                topLeft = Offset(pianoWidth, yTop),
                size = Size(graphAreaWidth, laneH)
            )
            // Full-width separator (0..width) so piano key boundaries align exactly
            drawLine(
                color = Color(0xFF0F0F0F),
                start = Offset(0f, yBottom),
                end = Offset(width, yBottom),
                strokeWidth = 1f
            )
        }

        // 2. Piano keys — fill only, no Stroke border.
        //    Key boundaries are already drawn as full-width separator lines in section 1.
        //    Removing Stroke eliminates the 0.5-px bias that made piano drift from the grid.
        drawRect(color = Color(0xFF111111), topLeft = Offset(0f, 0f), size = Size(pianoWidth, height))
        for (midiInt in minMidiInt..maxMidiInt) {
            if (midiInt < 0 || midiInt > 127) continue
            val yTop    = midiToY(midiInt + 0.5f)
            val yBottom = midiToY(midiInt - 0.5f)
            val keyH    = (yBottom - yTop)
            if (keyH < 1f || yTop >= height || yBottom <= 0f) continue

            val isBlackKey = blackKeys.contains(((midiInt % 12) + 12) % 12)
            val keyColor   = if (isBlackKey) Color(0xFF222222) else Color(0xFFF0F0F0)
            drawRect(color = keyColor, topLeft = Offset(0f, yTop), size = Size(pianoWidth, keyH))

            if (pianoWidth > 30 && keyH > 10f) {
                val noteIndex = ((midiInt % 12) + 12) % 12
                val octave    = (midiInt / 12) - 1
                val textColor = if (isBlackKey) Color.White else Color.Black
                val labelY    = yTop + keyH / 2 - 5f
                if (labelY >= 2f && labelY + 14f <= height) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "${noteNames[noteIndex]}$octave",
                        style = TextStyle(color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        topLeft = Offset(8f, labelY)
                    )
                }
            }
        }

        // 3. Playhead + vertical time grid
        drawLine(
            color = Color.White,
            start = Offset(playheadDrawX, 0f),
            end = Offset(playheadDrawX, height),
            strokeWidth = 2f
        )
        for (seconds in 0..8 step 2) {
            val t = effectiveTimeMs - seconds * 1000L
            val x = timeToX(t)
            if (x > pianoWidth && x < rightClip) {
                drawLine(
                    color = Color(0x22FFFFFF),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = if (seconds % 4 == 0) 1.2f else 0.5f
                )
            }
        }

        // 4. Note blocks
        clipRect(left = pianoWidth, top = 0f, right = rightClip, bottom = height) {
            blocks.forEach { block ->
                if (block.midi < visibleMinMidi - 2f || block.midi > visibleMaxMidi + 2f) return@forEach
                val xStart = timeToX(block.startMs)
                val xEnd   = timeToX(block.endMs)
                if (xEnd <= pianoWidth || xStart >= rightClip) return@forEach

                val laneHeight = midiToY(block.midi - 0.5f) - midiToY(block.midi + 0.5f)
                val rectHeight = (laneHeight * 0.72f).coerceAtLeast(4f)
                val yCenter    = midiToY(block.midi)
                val rectY      = yCenter - rectHeight / 2f
                val blockWidth = (xEnd - xStart).coerceAtLeast(4f)

                drawRoundRect(
                    color = Color(0xFF9E47F5).copy(alpha = 0.55f),
                    topLeft = Offset(xStart, rectY),
                    size = Size(blockWidth, rectHeight),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(xStart, rectY),
                    size = Size(blockWidth, rectHeight),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = Stroke(width = 1.5f)
                )
                val textY = yCenter - 6f
                val textX = xStart + 5f
                // Guard only against the Compose crash (maxWidth < 0).
                // softWrap=false + maxLines=1 prevents line-wrapping in narrow space;
                // clipRect handles visual clipping identically to the piano left edge.
                if (blockWidth > 50f
                    && textX + 5f <= width
                    && textY >= 4f
                    && textY + 16f <= height
                ) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = block.noteName,
                        style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        topLeft = Offset(textX, textY),
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }

        // 5. Pitch curve
        // timeDiffMin: lower bound for the time filter. When paused + scrolled back,
        // allow points between effectiveTimeMs and currentTimeMs (they're visible
        // right of the playhead). Declared here so ghost section (6) can reuse it.
        val timeDiffMin = if (isPaused) -scrollOffsetMs else 0L
        clipRect(left = pianoWidth, top = 0f, right = rightClip, bottom = height) {
            path.reset()
            var hasMoved = false
            var previousPoint: PitchPoint? = null
            for (point in history) {
                if (point.freq <= 20.0) { previousPoint = null; continue }
                val timeDiff = effectiveTimeMs - point.timeMs
                if (timeDiff !in timeDiffMin..PITCH_HISTORY_WINDOW_MS) { previousPoint = null; continue }
                val x = timeToX(point.timeMs)
                val y = midiToY(point.midi)
                val prev = previousPoint
                val midiJump = if (prev != null) abs(point.midi - prev.midi) else 0f
                when {
                    prev == null || !hasMoved               -> { path.moveTo(x, y); hasMoved = true }
                    point.timeMs - prev.timeMs > NOTE_GAP_THRESHOLD_MS -> path.moveTo(x, y)
                    midiJump > 8f                           -> path.moveTo(x, y)
                    else                                    -> path.lineTo(x, y)
                }
                previousPoint = point
            }
            if (hasMoved) {
                drawPath(path, Color.Cyan, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        drawLine(
            color = Color.White.copy(alpha = 0.5f),
            start = Offset(pianoWidth, 0f),
            end = Offset(pianoWidth, height),
            strokeWidth = 2f
        )

        // 6. Ghost pitch curve (orange, semi-transparent — the "ghost racer")
        if (ghostPoints.isNotEmpty()) {
            clipRect(left = pianoWidth, top = 0f, right = rightClip, bottom = height) {
                path.reset()
                var hasMoved = false
                var prevGhost: GhostPoint? = null
                for (gp in ghostPoints) {
                    val ghostTimeInView = gp.timeMs + ghostOffsetMs
                    val tDiff: Long = effectiveTimeMs - ghostTimeInView
                    if (tDiff !in timeDiffMin..PITCH_HISTORY_WINDOW_MS) { prevGhost = null; continue }
                    val x = timeToX(ghostTimeInView)
                    val y = midiToY(gp.midi)
                    val jump = if (prevGhost != null) abs(gp.midi - prevGhost!!.midi) else 0f
                    when {
                        prevGhost == null || !hasMoved -> { path.moveTo(x, y); hasMoved = true }
                        gp.timeMs - prevGhost!!.timeMs > NOTE_GAP_THRESHOLD_MS -> path.moveTo(x, y)
                        jump > 8f -> path.moveTo(x, y)
                        else -> path.lineTo(x, y)
                    }
                    prevGhost = gp
                }
                if (hasMoved) {
                    drawPath(path, Color(0xCCFF9800),
                        style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

// =====================================================================
//  Settings panel
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    settings: SpectrumSettings,
    onSettingsChange: (SpectrumSettings) -> Unit,
    currentFps: Int,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
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
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("✕", fontSize = 18.sp)
                }
            }

            Column {
                Text(
                    text = "Производительность",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
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
                    text = "• БПФ 4096 точек\n• 600 точек отрисовки\n• Высокое разрешение пиков",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }

            SettingsSlider(
                label = "Усиление: ${(settings.gain * 100).toInt()}%",
                value = settings.gain,
                range = 0.001f..0.2f,
                onChange = { onSettingsChange(settings.copy(gain = it)) },
                hint = "Очень чувствительный микрофон\nНачинайте с 1-5%"
            )
            SettingsSlider(
                label = "Диапазон dB: ${settings.dbRange.toInt()}",
                value = settings.dbRange,
                range = 40f..120f,
                onChange = { onSettingsChange(settings.copy(dbRange = it)) },
                hint = "Меньше — видно тихие сигналы\nБольше — видно громкие сигналы"
            )
            SettingsSlider(
                label = "Скорость реакции: ${(settings.speedFactor * 100).toInt()}%",
                value = settings.speedFactor,
                range = 0.1f..1f,
                onChange = { onSettingsChange(settings.copy(speedFactor = it)) },
                hint = "Меньше — плавнее, как FabFilter\nБольше — быстрее реакция"
            )
            SettingsSlider(
                label = "Сглаживание: ${(settings.smoothingFactor * 100).toInt()}%",
                value = settings.smoothingFactor,
                range = 0f..1f,
                onChange = { onSettingsChange(settings.copy(smoothingFactor = it)) },
                hint = "Убирает мелкие колебания"
            )
            SettingsSlider(
                label = "Наклон спектра: ${"%.1f".format(settings.tilt)} dB/окт",
                value = settings.tilt,
                range = 0f..6f,
                onChange = { onSettingsChange(settings.copy(tilt = it)) },
                hint = "0 — сырой спектр\n4.5 — как слух (FabFilter default)"
            )
            SettingsSlider(
                label = "Порог шума: ${settings.noiseThreshold.toInt()} dB",
                value = settings.noiseThreshold,
                range = -80f..0f,
                onChange = { onSettingsChange(settings.copy(noiseThreshold = it)) },
                hint = null
            )
        }
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    hint: String?
) {
    Column {
        Text(text = label, fontSize = 14.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
        if (hint != null) {
            Text(text = hint, color = Color.Gray, fontSize = 10.sp)
        }
    }
}

// =====================================================================
//  Player panel (bottom controls in file mode)
// =====================================================================

@Composable
fun PlayerPanel(
    visible: Boolean,
    isLoading: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isSeeking by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(positionMs, durationMs, isSeeking) {
        if (!isSeeking) {
            sliderValue = (positionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(300)) +
                slideInVertically(animationSpec = tween(300), initialOffsetY = { it }),
        exit = fadeOut(animationSpec = tween(200)) +
                slideOutVertically(animationSpec = tween(200), targetOffsetY = { it }),
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                    val displayMs = (sliderValue * durationMs).toLong()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(displayMs),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                            modifier = Modifier.width(40.dp)
                        )
                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onPlayPause()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = formatTime(durationMs),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                            modifier = Modifier.width(40.dp)
                        )
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = { newValue ->
                            onUserInteraction()
                            isSeeking = true
                            sliderValue = newValue
                        },
                        onValueChangeFinished = {
                            onUserInteraction()
                            val targetMs = (sliderValue * durationMs).toLong()
                            onSeek(targetMs)
                            coroutineScope.launch {
                                delay(200)
                                isSeeking = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        colors = SliderDefaults.colors(
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

// =====================================================================
//  Mode indicator toast — pops up briefly when the user swipes to switch
// =====================================================================

/**
 * Small animated pill that appears at the top of the screen when the user
 * swipes to switch between Spectrum and Analysis modes.
 * Active mode icon = cyan, inactive = dim. Both icons are always visible
 * so the user sees which mode they switched FROM and which they switched TO.
 */
@Composable
fun ModeIndicatorToast(
    analysisProgress: Float,   // 0.0 = fully spectrum, 1.0 = fully analysis
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    // Colors derived directly from drag progress — no animateColorAsState needed,
    // the pager's drag already provides smooth frame-by-frame interpolation.
    val cyan = Color(0xFF00E5FF)
    val dim  = Color(0xFF333333)
    val specColor  = lerpColor(cyan, dim, analysisProgress)
    val noteColor  = lerpColor(dim, cyan, analysisProgress)

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(150), initialScale = 0.7f) + fadeIn(tween(150)),
        exit  = scaleOut(tween(180), targetScale = 0.7f) + fadeOut(tween(180)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .background(Color(0xE0000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Equalizer, contentDescription = null,
                tint = specColor, modifier = Modifier.size(14.dp))
            Icon(Icons.Default.MusicNote, contentDescription = null,
                tint = noteColor, modifier = Modifier.size(14.dp))
        }
    }
}

// =====================================================================
//  Note info HUD — compact tuner-style bar at the top of the piano roll
// =====================================================================

@Composable
fun AnalysisOverlay(
    noteName: String,
    frequency: Double,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val hasNote = frequency > 20.0

    val rawCents = if (hasNote) {
        val fractMidi = freqToMidi(frequency)
        ((fractMidi - fractMidi.roundToInt()) * 100f).roundToInt().coerceIn(-50, 50)
    } else 0
    val animCents by animateFloatAsState(
        targetValue = rawCents.toFloat(),
        animationSpec = tween(durationMillis = 80),
        label = "cents"
    )

    Column(
        modifier = modifier
            .width(220.dp)                // fixed compact width — centred by the caller
            .background(
                color = Color(0xD0111318),
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // ── -50C | note | +50C ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("-50C", color = Color(0xFF666666), fontSize = 11.sp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (hasNote) noteName else "—",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 30.sp
                )
                if (isPaused) {
                    Text(
                        text = "⏸",
                        color = Color(0xFFFFD700),
                        fontSize = 14.sp
                    )
                }
            }

            Text("+50C", color = Color(0xFF666666), fontSize = 11.sp)
        }

        // ── Indicator bar ─────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        ) {
            val r = CornerRadius(3f.dp.toPx())
            drawRoundRect(color = Color(0xFF252525), cornerRadius = r)
            drawLine(
                color = Color(0xFF3A3A3A),
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 1.5f
            )
            if (hasNote) {
                val pillW    = size.width * 0.30f
                val halfRange = (size.width - pillW) / 2f
                val pillX    = size.width / 2f - pillW / 2f + (animCents / 50f) * halfRange
                val pillColor = when {
                    abs(animCents) < 5f  -> Color(0xFF00E5FF)
                    abs(animCents) < 20f -> Color(0xFF8BC34A)
                    else                 -> Color(0xFFBBBBBB)
                }
                drawRoundRect(
                    color = pillColor,
                    topLeft = Offset(pillX, 0f),
                    size = Size(pillW, size.height),
                    cornerRadius = r
                )
            }
        }
    }
}


// =====================================================================
//  Settings panel for the note / pitch analysis mode
// =====================================================================

@Composable
fun AnalysisSettingsPanel(
    settings: AnalysisSettings,
    onSettingsChange: (AnalysisSettings) -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(300.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Анализ нот",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Text("✕", fontSize = 16.sp)
                }
            }

            // Noise gate slider
            val gateLabel = "${"%.0f".format(settings.noiseGateDb)} дБ"
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Порог шума", fontSize = 13.sp)
                    Text(gateLabel, fontSize = 13.sp, color = Color(0xFF4FC3F7))
                }
                Slider(
                    value = settings.noiseGateDb,
                    onValueChange = { onSettingsChange(settings.copy(noiseGateDb = it)) },
                    valueRange = -80f..-10f,
                    steps = 70,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF4FC3F7),
                        activeTrackColor = Color(0xFF4FC3F7)
                    )
                )
                Text(
                    text = "Ноты не регистрируются ниже ${gateLabel}.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    lineHeight = 15.sp
                )
            }

            // History window slider
            val winLabel = "${"%.0f".format(settings.historyWindowMinutes)} мин"
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("История пения", fontSize = 13.sp)
                    Text(winLabel, fontSize = 13.sp, color = Color(0xFF4FC3F7))
                }
                Slider(
                    value = settings.historyWindowMinutes,
                    onValueChange = { onSettingsChange(settings.copy(historyWindowMinutes = it)) },
                    valueRange = 1f..10f,
                    steps = 17,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF4FC3F7),
                        activeTrackColor = Color(0xFF4FC3F7)
                    )
                )
                Text(
                    text = "Сколько минут графика хранить. На паузе можно листать назад.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    lineHeight = 15.sp
                )
            }
        }
    }
}



@Composable
fun AnalysisPauseButton(
    isPaused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .size(56.dp)
            .background(color = Color.Black.copy(alpha = 0.7f), shape = CircleShape)
    ) {
        Icon(
            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = "Пауза/Продолжить",
            tint = if (isPaused) Color.Cyan else Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Color(0xFF4FC3F7))
            Text(
                text = "Загрузка аудиофайла...",
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun PermissionDeniedScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Разрешение на использование микрофона не предоставлено.",
            color = Color.Red
        )
    }
}