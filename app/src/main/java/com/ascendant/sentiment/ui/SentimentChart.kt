package com.ascendant.sentiment.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascendant.sentiment.model.SentimentBar
import com.ascendant.sentiment.ui.theme.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private val MagentaBright = Color(0xFFFF00FF)
private val StochYellow = Color(0xFFFACC15)
private const val BAR_ALPHA = 0.40f
private const val BAR_ALPHA_MAGENTA = 0.75f

/** Rounds a positive value up to 1, 2, 2.5, 5 or 10 times a power of ten (clean axis labels). */
private fun niceCeil(v: Double): Double {
    val p = 10.0.pow(floor(log10(v)))
    val f = v / p
    val n = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return n * p
}

private fun fmtScale(v: Double): String = if (v >= 10.0) "%.0f".format(v) else if (v >= 1.0 && v == floor(v)) "%.0f".format(v) else "%.2f".format(v).trimEnd('0').trimEnd('.')

/**
 * v4.0: 5-minute transit vs natal RESONANCE bars. Green bar up = weighted bullish resonance,
 * red bar down = weighted bearish resonance (magenta part = hard Pluto), white tick = net.
 * v4.1: the yellow line is the slow stochastic of the net (0 to 100 mapped onto the full chart height,
 * 50 on the zero line, dashed lines at 80 and 20).
 * The vertical scale is automatic: the largest bar in the whole series sets it (labels sit in the left gap).
 * zoom = 1 shows every bar; higher values show fewer bars, wider, around the selected bar.
 * Pinch with two fingers, or use the zoom buttons, to change zoom (onZoomBy gets a multiplier).
 */
@Composable
fun SentimentChart(
    series: List<SentimentBar>,
    inspectedTimeMs: Long,
    onInspectTime: (Long) -> Unit,
    stoch: DoubleArray? = null,
    zoom: Float = 1f,
    onZoomBy: (Float) -> Unit = {},
    modifier: Modifier = Modifier.fillMaxWidth().height(260.dp)
) {
    if (series.isEmpty()) return

    val numBars = series.size
    // Autoscale: largest bullish or bearish bar in the series, +5% headroom, minimum 1 unit so a quiet day is not blown up
    val maxVal = remember(series) {
        niceCeil(max(1.0, series.maxOf { max(it.resBull, it.resBear) } * 1.05))
    }
    val firstT = series.first().timeMs
    val count = (numBars / zoom).roundToInt().coerceIn(minOf(12, numBars), numBars)
    val selIdx = ((inspectedTimeMs - firstT) / 300_000.0).toInt().coerceIn(0, numBars - 1)

    // view[0] = first visible bar, view[1] = visible bar count, view[2] = zoom the view was last centred for
    val view = remember(series) { intArrayOf(0, numBars, 1000) }
    val zoomKey = (zoom * 1000f).roundToInt()
    var start = view[0]
    if (zoomKey != view[2]) start = selIdx - count / 2            // zoom changed: centre on the cursor
    else if (selIdx < start) start = selIdx                       // cursor left the view: scroll just enough
    else if (selIdx >= start + count) start = selIdx - count + 1
    start = start.coerceIn(0, numBars - count)
    view[0] = start
    view[1] = count
    view[2] = zoomKey

    val currentOnInspect = rememberUpdatedState(onInspectTime)
    val currentOnZoom = rememberUpdatedState(onZoomBy)

    Canvas(
        modifier = modifier
            .pointerInput(series) {
                awaitEachGesture {
                    val leftPx = 42.dp.toPx()
                    val plotW = size.width - leftPx - 12.dp.toPx()

                    // clamp = true: touches stay inside the plot. clamp = false (dragging): going past an
                    // edge selects bars beyond it, which scrolls the zoomed view.
                    fun idxAt(x: Float, clamp: Boolean): Int {
                        val barW = plotW / view[1]
                        val rel = if (clamp) {
                            ((x - leftPx).coerceIn(0f, plotW - 0.001f) / barW).toInt()
                        } else {
                            kotlin.math.floor((x - leftPx) / barW).toInt()
                        }
                        return (view[0] + rel).coerceIn(0, numBars - 1)
                    }

                    var lastIdx = -1
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var idx = idxAt(down.position.x, true)
                    lastIdx = idx
                    currentOnInspect.value(series[idx].timeMs)

                    var prevDist = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // two fingers: pinch to zoom
                            val dist = (pressed[0].position - pressed[1].position).getDistance()
                            if (prevDist > 0f && dist > 0f) currentOnZoom.value(dist / prevDist)
                            prevDist = dist
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            // one finger: move the white cursor
                            prevDist = 0f
                            val c = pressed[0]
                            if (c.positionChanged()) {
                                idx = idxAt(c.position.x, false)
                                if (idx != lastIdx) {
                                    lastIdx = idx
                                    currentOnInspect.value(series[idx].timeMs)
                                }
                                c.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val leftMargin = 42.dp.toPx()
        val bottomMargin = 24.dp.toPx()
        val topMargin = 12.dp.toPx()
        val plotWidth = width - leftMargin - 12.dp.toPx()
        val plotHeight = height - bottomMargin - topMargin

        // Autoscaled: +maxVal at the top, -maxVal at the bottom
        fun getY(v: Double): Float = (topMargin + (plotHeight / 2.0) * (1.0 - v / maxVal)).toFloat()

        val barWidth = plotWidth / count
        val firstVisible = view[0]

        // Background (semi-transparent so the Resonance list in front stays readable)
        drawRect(color = BgDark, size = size)

        val yZero = getY(0.0)

        // Bullish half and bearish half tints
        drawRect(
            color = Emerald500.copy(alpha = 0.08f),
            topLeft = Offset(leftMargin, topMargin),
            size = Size(plotWidth, max(0f, yZero - topMargin))
        )
        drawRect(
            color = Rose500.copy(alpha = 0.08f),
            topLeft = Offset(leftMargin, yZero),
            size = Size(plotWidth, max(0f, topMargin + plotHeight - yZero))
        )

        // Scale grid lines at +-max and +-max/2
        listOf(1.0, 0.5, -0.5, -1.0).forEach { f ->
            val gy = getY(f * maxVal)
            drawLine(
                color = Color(0xFF475569).copy(alpha = 0.5f),
                start = Offset(leftMargin - 4.dp.toPx(), gy),
                end = Offset(leftMargin + plotWidth, gy),
                strokeWidth = 1f
            )
        }

        // Zero line
        drawLine(
            color = Color(0xFF475569),
            start = Offset(leftMargin - 4.dp.toPx(), yZero),
            end = Offset(leftMargin + plotWidth, yZero),
            strokeWidth = 1.5f
        )

        // Resonance bars (visible slice only): bullish up, bearish down, white net tick
        for (i in firstVisible until firstVisible + count) {
            val bar = series[i]
            val bx = leftMargin + (i - firstVisible) * barWidth
            val bw = maxOf(barWidth - 1f, 1.5f)

            if (bar.resBull > 0.0) {
                val yTop = getY(bar.resBull)
                drawRect(
                    color = Emerald500.copy(alpha = BAR_ALPHA),
                    topLeft = Offset(bx, yTop),
                    size = Size(bw, maxOf(yZero - yTop, 1f))
                )
            }
            if (bar.resBear > 0.0) {
                val yPluto = getY(-bar.resBearPluto)
                val yBot = getY(-bar.resBear)
                if (bar.resBearPluto > 0.0) {
                    // hard Pluto part sits next to the zero line in bright magenta
                    drawRect(
                        color = MagentaBright.copy(alpha = BAR_ALPHA_MAGENTA),
                        topLeft = Offset(bx, yZero),
                        size = Size(bw, maxOf(yPluto - yZero, 1f))
                    )
                }
                if (yBot > yPluto) {
                    drawRect(
                        color = Rose500.copy(alpha = BAR_ALPHA),
                        topLeft = Offset(bx, yPluto),
                        size = Size(bw, maxOf(yBot - yPluto, 1f))
                    )
                }
            }
            if (bar.resBull > 0.0 || bar.resBear > 0.0) {
                val yNet = getY(bar.resNet)
                drawLine(
                    color = Color.White.copy(alpha = 0.9f),
                    start = Offset(bx, yNet),
                    end = Offset(bx + bw, yNet),
                    strokeWidth = 2f
                )
            }
        }

        // Slow stochastic of the net: yellow line, 0..100 mapped to -max..+max
        if (stoch != null && stoch.size == numBars) {
            fun stochY(k: Double): Float = getY((k / 50.0 - 1.0) * maxVal)

            // overbought / oversold guides at 80 and 20
            listOf(80.0, 20.0).forEach { lvl ->
                drawLine(
                    color = StochYellow.copy(alpha = 0.25f),
                    start = Offset(leftMargin, stochY(lvl)),
                    end = Offset(leftMargin + plotWidth, stochY(lvl)),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
                )
            }

            val path = Path()
            for (i in firstVisible until firstVisible + count) {
                val x = leftMargin + (i - firstVisible + 0.5f) * barWidth
                val y = stochY(stoch[i])
                if (i == firstVisible) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.55f),
                style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = StochYellow,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Current real-time "Now" vertical dashed line
        val now = System.currentTimeMillis()
        val nowIdx = (now - firstT) / 300_000.0
        if (nowIdx >= firstVisible && nowIdx <= firstVisible + count) {
            val nowX = (leftMargin + (nowIdx - firstVisible) * barWidth).toFloat()
            drawLine(
                color = Amber500,
                start = Offset(nowX, topMargin),
                end = Offset(nowX, topMargin + plotHeight),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
            )
        }

        // Inspected bar vertical cursor
        val selX = leftMargin + (selIdx - firstVisible + 0.5f) * barWidth
        drawLine(
            color = Color.White,
            start = Offset(selX, topMargin),
            end = Offset(selX, topMargin + plotHeight),
            strokeWidth = 2f
        )

        // Net node on the selected bar
        val selBar = series[selIdx]
        if (selBar.resBull > 0.0 || selBar.resBear > 0.0) {
            val selY = getY(selBar.resNet)
            drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(selX, selY))
            drawCircle(
                color = if (selBar.resNet > 0) Emerald400 else if (selBar.resNet < 0) Rose400 else Color.Gray,
                radius = 2.5.dp.toPx(),
                center = Offset(selX, selY)
            )
        }

        // Stochastic node on the selected bar
        if (stoch != null && stoch.size == numBars) {
            val sy = getY((stoch[selIdx] / 50.0 - 1.0) * maxVal)
            drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(selX, sy))
            drawCircle(color = StochYellow, radius = 2.2.dp.toPx(), center = Offset(selX, sy))
        }

        // Vertical autoscale labels in the left gap
        val labelPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9.sp.toPx()
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        fun scaleLabel(text: String, v: Double, color: Color) {
            labelPaint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                text,
                leftMargin - 7.dp.toPx(),
                getY(v) + labelPaint.textSize * 0.35f,
                labelPaint
            )
        }
        scaleLabel("+" + fmtScale(maxVal), maxVal, Emerald400.copy(alpha = 0.8f))
        scaleLabel("+" + fmtScale(maxVal / 2), maxVal / 2, Slate400)
        scaleLabel("0", 0.0, Slate400)
        scaleLabel("−" + fmtScale(maxVal / 2), -maxVal / 2, Slate400)
        scaleLabel("−" + fmtScale(maxVal), -maxVal, Rose400.copy(alpha = 0.8f))
    }
}
