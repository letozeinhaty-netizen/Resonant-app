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
import androidx.compose.ui.graphics.lerp
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
import com.ascendant.sentiment.engine.AstroEngine
import com.ascendant.sentiment.model.MacdSeries
import com.ascendant.sentiment.model.TurningData
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
private val BaseSky = Color(0xFF38BDF8)
private val PulseViolet = Color(0xFFA78BFA)
private val MacdCyan = Color(0xFF22D3EE)
private val SignalOrange = Color(0xFFFB923C)
private val ShareNeutral = Color(0xFF64748B)
private const val BAR_ALPHA = 0.40f
private const val BAR_ALPHA_MAGENTA = 0.75f

/** Rounds a positive value up to 1, 2, 2.5, 5 or 10 times a power of ten (clean axis labels). */
internal fun niceCeil(v: Double): Double {
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

internal fun fmtScale(v: Double): String = if (v < 0.1) "%.3f".format(v).trimEnd('0').trimEnd('.') else if (v >= 10.0) "%.0f".format(v) else if (v >= 1.0 && v == floor(v)) "%.0f".format(v) else "%.2f".format(v).trimEnd('0').trimEnd('.')

/**
 * v6.7: works out the visible window shared by the chart and the panes under it.
 * view[0] = first visible bar, view[1] = visible bar count, view[2] = zoom the view was last centred for.
 * The main chart calls this, and so does the screen itself, so the panes keep their zoom and scrolling
 * when the main chart is switched off.
 */
internal fun updateChartView(view: IntArray, numBars: Int, zoom: Float, selIdx: Int) {
    if (numBars <= 0) return
    val count = (numBars / zoom).roundToInt().coerceIn(minOf(12, numBars), numBars)
    val zoomKey = (zoom * 1000f).roundToInt()
    var start = view[0]
    if (zoomKey != view[2]) start = selIdx - count / 2            // zoom changed: centre on the cursor
    else if (selIdx < start) start = selIdx                       // cursor left the view: scroll just enough
    else if (selIdx >= start + count) start = selIdx - count + 1
    start = start.coerceIn(0, numBars - count)
    view[0] = start
    view[1] = count
    view[2] = zoomKey
}

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
    flags: IntArray? = null,
    flagged: BooleanArray? = null,   // v6.1: bars inside a void-of-course or station stretch (amber ring on their flags)
    view: IntArray = remember(series) { intArrayOf(0, series.size, 1000) },
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
    val selIdx = ((inspectedTimeMs - firstT) / 300_000.0).toInt().coerceIn(0, numBars - 1)
    updateChartView(view, numBars, zoom, selIdx)
    val count = view[1]

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

        // v4.2: base field (no trigger boost) as a sky line; the Asc / MC pulse is the violet stem from base up or down to the net tick
        run {
            val basePath = Path()
            val stemW = max(1.5f, minOf(barWidth * 0.35f, 2.5.dp.toPx()))
            for (i in firstVisible until firstVisible + count) {
                val bar = series[i]
                val x = leftMargin + (i - firstVisible + 0.5f) * barWidth
                val yBase = getY(bar.resBase)
                if (i == firstVisible) basePath.moveTo(x, yBase) else basePath.lineTo(x, yBase)
                if (abs(bar.resNet - bar.resBase) > 1e-9) {
                    drawLine(
                        color = PulseViolet,
                        start = Offset(x, yBase),
                        end = Offset(x, getY(bar.resNet)),
                        strokeWidth = stemW
                    )
                }
            }
            drawPath(
                path = basePath,
                color = BaseSky,
                style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // v4.3: zero-cross flags. Green triangle (bottom edge) = net turned bullish, red triangle (top edge) = turned bearish
        if (flags != null && flags.size == numBars) {
            val s = 5.dp.toPx()
            val yBottom = topMargin + plotHeight - 2.dp.toPx()
            val yTop = topMargin + 2.dp.toPx()
            for (i in firstVisible until firstVisible + count) {
                val f = flags[i]
                if (f == 0) continue
                val x = leftMargin + (i - firstVisible + 0.5f) * barWidth
                val col = if (f > 0) Emerald400 else Rose400
                drawLine(
                    color = col.copy(alpha = 0.3f),
                    start = Offset(x, topMargin),
                    end = Offset(x, topMargin + plotHeight),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
                )
                val tri = Path()
                if (f > 0) {
                    tri.moveTo(x, yBottom - s)
                    tri.lineTo(x - s, yBottom)
                    tri.lineTo(x + s, yBottom)
                } else {
                    tri.moveTo(x, yTop + s)
                    tri.lineTo(x - s, yTop)
                    tri.lineTo(x + s, yTop)
                }
                tri.close()
                drawPath(path = tri, color = col)
                if (flagged != null && flagged.size == numBars && flagged[i]) {
                    val cy = if (f > 0) yBottom - s * 0.5f else yTop + s * 0.5f
                    drawCircle(
                        color = Amber500,
                        radius = s + 3.dp.toPx(),
                        center = Offset(x, cy),
                        style = Stroke(width = 1.8f)
                    )
                }
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

/**
 * v4.2: detrended net (net minus its 1-hour average) as a histogram, lined up under the main chart.
 * It reads the visible window written by the main chart (view), so zoom and scrolling stay in step.
 */
@Composable
fun DetrendedPane(
    series: List<SentimentBar>,
    detr: DoubleArray,
    inspectedTimeMs: Long,
    view: IntArray,
    zoom: Float,
    td: TurningData? = null,          // v6.1: void-of-course dimming and station rings
    skyTint: Boolean = false,         // v6.1: background tinted by the sign of the sky field
    minScale: Double = 0.5,           // v6.4: smallest autoscale ceiling (the Midheaven index needs a much smaller one)
    modifier: Modifier = Modifier.fillMaxWidth().height(96.dp)
) {
    if (series.isEmpty() || detr.size != series.size) return

    val numBars = series.size
    val firstT = series.first().timeMs
    val selIdx = ((inspectedTimeMs - firstT) / 300_000.0).toInt().coerceIn(0, numBars - 1)
    // the main chart is composed first, so the window in view is already up to date here
    val start = view[0].coerceIn(0, numBars - 1)
    val count = view[1].coerceIn(1, numBars - start)
    // autoscale on the biggest detrended swing in the series (minimum 0.5 unit)
    val maxVal = remember(detr) { niceCeil(max(minScale, detr.maxOf { abs(it) } * 1.05)) }
    val zoomKey = zoom   // reading zoom keeps this pane recomposing when the chart zoom changes

    Canvas(modifier = modifier) {
        val leftMargin = 42.dp.toPx()
        val topMargin = 6.dp.toPx()
        val bottomMargin = 6.dp.toPx()
        val plotWidth = size.width - leftMargin - 12.dp.toPx()
        val plotHeight = size.height - topMargin - bottomMargin
        fun getY(v: Double): Float = (topMargin + (plotHeight / 2.0) * (1.0 - v / maxVal)).toFloat()

        drawRect(color = BgDark, size = size)
        val yZero = getY(0.0)

        // v6.6: background tint follows the transit-to-transit field: green while it is rising (higher than the
        // previous bar), red while it is falling
        if (skyTint && td != null && td.sky.size == numBars && numBars > 1) {
            val bwT = plotWidth / count
            for (i in start until start + count) {
                val d = if (i == 0) td.sky[1] - td.sky[0] else td.sky[i] - td.sky[i - 1]
                if (d == 0.0) continue
                drawRect(
                    color = (if (d > 0) Emerald500 else Rose500).copy(alpha = 0.2f),
                    topLeft = Offset(leftMargin + (i - start) * bwT, topMargin),
                    size = Size(maxOf(bwT, 1f), plotHeight)
                )
            }
        }

        // faint +-max guides and the zero line
        listOf(1.0, -1.0).forEach { f ->
            drawLine(
                color = Color(0xFF475569).copy(alpha = 0.35f),
                start = Offset(leftMargin, getY(f * maxVal)),
                end = Offset(leftMargin + plotWidth, getY(f * maxVal)),
                strokeWidth = 1f
            )
        }
        drawLine(
            color = Color(0xFF475569),
            start = Offset(leftMargin - 4.dp.toPx(), yZero),
            end = Offset(leftMargin + plotWidth, yZero),
            strokeWidth = 1.5f
        )

        val barWidth = plotWidth / count
        for (i in start until start + count) {
            val v = detr[i]
            if (v == 0.0) continue
            val y = getY(v)
            val dim = td != null && td.voc.size == numBars && td.voc[i]
            drawRect(
                color = (if (v > 0) Emerald500 else Rose500).copy(alpha = if (dim) 0.35f else 0.75f),
                topLeft = Offset(leftMargin + (i - start) * barWidth, minOf(yZero, y)),
                size = Size(maxOf(barWidth - 1f, 1.5f), maxOf(abs(y - yZero), 1f))
            )
        }

        // v6.1: amber rings on swing peaks and troughs that fall inside a station zone
        if (td != null && td.stationIndex.size == numBars) {
            for (i in maxOf(start, 1) until minOf(start + count, numBars - 1)) {
                val v = detr[i]
                val isPeak = v > 0 && v >= detr[i - 1] && v > detr[i + 1] && v >= 0.5 * maxVal
                val isTrough = v < 0 && v <= detr[i - 1] && v < detr[i + 1] && v <= -0.5 * maxVal
                if ((isPeak || isTrough) && td.stationIndex[i] >= AstroEngine.STATION_CONTEXT) {
                    drawCircle(
                        color = Amber500,
                        radius = 4.dp.toPx(),
                        center = Offset(leftMargin + (i - start + 0.5f) * barWidth, getY(v)),
                        style = Stroke(width = 1.8f)
                    )
                }
            }
        }

        // Inspected bar cursor, same white line as the main chart
        val selX = leftMargin + (selIdx - start + 0.5f) * barWidth
        drawLine(
            color = Color.White,
            start = Offset(selX, topMargin),
            end = Offset(selX, topMargin + plotHeight),
            strokeWidth = 2f
        )

        // scale labels in the left gap
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
        scaleLabel("0", 0.0, Slate400)
        scaleLabel("−" + fmtScale(maxVal), -maxVal, Rose400.copy(alpha = 0.8f))
    }
}

/**
 * v4.3: MACD sub pane. Histogram = macd - signal (green above zero, red below),
 * cyan line = MACD (fast EMA - slow EMA of the net), orange line = signal. Shares the main chart window.
 */
@Composable
fun MacdPane(
    series: List<SentimentBar>,
    macd: MacdSeries,
    inspectedTimeMs: Long,
    view: IntArray,
    zoom: Float,
    flagged: BooleanArray? = null,   // v6.1: amber dots on MACD crossings inside void-of-course or station stretches
    modifier: Modifier = Modifier.fillMaxWidth().height(104.dp)
) {
    if (series.isEmpty() || macd.macd.size != series.size) return

    val numBars = series.size
    val firstT = series.first().timeMs
    val selIdx = ((inspectedTimeMs - firstT) / 300_000.0).toInt().coerceIn(0, numBars - 1)
    val start = view[0].coerceIn(0, numBars - 1)
    val count = view[1].coerceIn(1, numBars - start)
    val maxVal = remember(macd) {
        var m = 0.5
        for (i in 0 until numBars) {
            m = max(m, max(abs(macd.macd[i]), max(abs(macd.signal[i]), abs(macd.hist[i]))) * 1.05)
        }
        niceCeil(m)
    }
    val zoomKey = zoom

    Canvas(modifier = modifier) {
        val leftMargin = 42.dp.toPx()
        val topMargin = 6.dp.toPx()
        val bottomMargin = 6.dp.toPx()
        val plotWidth = size.width - leftMargin - 12.dp.toPx()
        val plotHeight = size.height - topMargin - bottomMargin
        fun getY(v: Double): Float = (topMargin + (plotHeight / 2.0) * (1.0 - v / maxVal)).toFloat()

        drawRect(color = BgDark, size = size)
        val yZero = getY(0.0)

        listOf(1.0, -1.0).forEach { f ->
            drawLine(
                color = Color(0xFF475569).copy(alpha = 0.35f),
                start = Offset(leftMargin, getY(f * maxVal)),
                end = Offset(leftMargin + plotWidth, getY(f * maxVal)),
                strokeWidth = 1f
            )
        }
        drawLine(
            color = Color(0xFF475569),
            start = Offset(leftMargin - 4.dp.toPx(), yZero),
            end = Offset(leftMargin + plotWidth, yZero),
            strokeWidth = 1.5f
        )

        val barWidth = plotWidth / count

        // histogram
        for (i in start until start + count) {
            val h = macd.hist[i]
            if (h == 0.0) continue
            val y = getY(h)
            drawRect(
                color = (if (h > 0) Emerald500 else Rose500).copy(alpha = 0.55f),
                topLeft = Offset(leftMargin + (i - start) * barWidth, minOf(yZero, y)),
                size = Size(maxOf(barWidth - 1f, 1.5f), maxOf(abs(y - yZero), 1f))
            )
        }

        // MACD and signal lines
        fun line(values: DoubleArray, color: Color, width: Float) {
            val p = Path()
            for (i in start until start + count) {
                val x = leftMargin + (i - start + 0.5f) * barWidth
                val y = getY(values[i])
                if (i == start) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(
                path = p,
                color = color,
                style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        line(macd.signal, SignalOrange, 1.8f)
        line(macd.macd, MacdCyan, 2.2f)
        if (flagged != null && flagged.size == numBars) {
            for (i in maxOf(start, 1) until start + count) {
                if (flagged[i] && macd.hist[i] * macd.hist[i - 1] < 0) {
                    drawCircle(
                        color = Amber500,
                        radius = 4.dp.toPx(),
                        center = Offset(leftMargin + (i - start + 0.5f) * barWidth, getY(macd.macd[i]))
                    )
                }
            }
        }

        // cursor
        val selX = leftMargin + (selIdx - start + 0.5f) * barWidth
        drawLine(
            color = Color.White,
            start = Offset(selX, topMargin),
            end = Offset(selX, topMargin + plotHeight),
            strokeWidth = 2f
        )

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
        scaleLabel("0", 0.0, Slate400)
        scaleLabel("−" + fmtScale(maxVal), -maxVal, Rose400.copy(alpha = 0.8f))
    }
}

/**
 * v4.3: bull share strip. One cell per bar. Colour = bull share of the total resonance
 * (red = all bearish, grey = 50/50, green = all bullish); brighter = more total resonance on that bar.
 */
@Composable
fun BullSharePane(
    series: List<SentimentBar>,
    inspectedTimeMs: Long,
    view: IntArray,
    zoom: Float,
    modifier: Modifier = Modifier.fillMaxWidth().height(26.dp)
) {
    if (series.isEmpty()) return

    val numBars = series.size
    val firstT = series.first().timeMs
    val selIdx = ((inspectedTimeMs - firstT) / 300_000.0).toInt().coerceIn(0, numBars - 1)
    val start = view[0].coerceIn(0, numBars - 1)
    val count = view[1].coerceIn(1, numBars - start)
    val maxTotal = remember(series) { max(1e-9, series.maxOf { it.resBull + it.resBear }) }
    val zoomKey = zoom

    Canvas(modifier = modifier) {
        val leftMargin = 42.dp.toPx()
        val plotWidth = size.width - leftMargin - 12.dp.toPx()
        val barWidth = plotWidth / count

        drawRect(color = BgDark, size = size)

        for (i in start until start + count) {
            val b = series[i]
            val total = b.resBull + b.resBear
            if (total < 1e-9) continue
            val share = b.resShare.toFloat()
            val base = if (share < 0.5f) lerp(Rose500, ShareNeutral, share * 2f) else lerp(ShareNeutral, Emerald500, (share - 0.5f) * 2f)
            val activity = (0.35f + 0.65f * (total / maxTotal).toFloat()).coerceIn(0.35f, 1f)
            drawRect(
                color = base.copy(alpha = activity),
                topLeft = Offset(leftMargin + (i - start) * barWidth, 2.dp.toPx()),
                size = Size(maxOf(barWidth, 1.5f), size.height - 4.dp.toPx())
            )
        }

        // cursor
        val selX = leftMargin + (selIdx - start + 0.5f) * barWidth
        drawLine(
            color = Color.White,
            start = Offset(selX, 0f),
            end = Offset(selX, size.height),
            strokeWidth = 2f
        )

        // label in the left gap
        val labelPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9.sp.toPx()
            textAlign = android.graphics.Paint.Align.RIGHT
            color = Slate400.toArgb()
        }
        drawContext.canvas.nativeCanvas.drawText(
            "bull %",
            leftMargin - 7.dp.toPx(),
            size.height / 2f + labelPaint.textSize * 0.35f,
            labelPaint
        )
    }
}
