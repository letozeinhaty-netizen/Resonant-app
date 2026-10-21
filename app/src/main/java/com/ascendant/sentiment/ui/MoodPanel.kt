package com.ascendant.sentiment.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascendant.sentiment.engine.AstroEngine
import com.ascendant.sentiment.model.MoodPoint
import com.ascendant.sentiment.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Temperature colours: blue (cold) - grey (neutral) - orange (hot). Violet = electric.
// Green and red are left out on purpose, they already mean bullish and bearish elsewhere in the app.
private val MoodCold = Color(0xFF3B82F6)
private val MoodNeutral = Color(0xFF94A3B8)
private val MoodHot = Color(0xFFF97316)
private val MoodElectric = Color(0xFFA78BFA)

private val ASPECT_NAMES = mapOf(
    0 to "conjunction", 30 to "semi-sextile", 45 to "semi-square", 60 to "sextile", 72 to "quintile",
    90 to "square", 120 to "trine", 135 to "sesquiquadrate", 144 to "biquintile", 150 to "quincunx", 180 to "opposition"
)

private fun tempColor(t: Double): Color {
    val f = (abs(t) / 70.0).toFloat().coerceIn(0f, 1f)
    return if (t >= 0) lerp(MoodNeutral, MoodHot, f) else lerp(MoodNeutral, MoodCold, f)
}

private fun fmtSigned(v: Double): String =
    (if (v > 0.5) "+" else if (v < -0.5) "−" else "") + "%.0f".format(abs(v))

private fun clsWord(cls: Int): String = when (cls) {
    1 -> "hot"
    -1 -> "cold"
    2 -> "electric"
    else -> "neutral"
}

private fun pickIndex(x: Float, leftPx: Float, plotW: Float, n: Int): Int =
    (((x - leftPx) / plotW) * n).toInt().coerceIn(0, n - 1)

/** Tap or drag across the plot to move the inspected time. */
private fun Modifier.scrub(points: List<MoodPoint>, onInspect: State<(Long) -> Unit>): Modifier =
    this
        .pointerInput(points) {
            val leftPx = 42.dp.toPx()
            val plotW = size.width - leftPx - 12.dp.toPx()
            detectTapGestures { pos ->
                onInspect.value(points[pickIndex(pos.x, leftPx, plotW, points.size)].timeMs)
            }
        }
        .pointerInput(points) {
            val leftPx = 42.dp.toPx()
            val plotW = size.width - leftPx - 12.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { pos ->
                    onInspect.value(points[pickIndex(pos.x, leftPx, plotW, points.size)].timeMs)
                }
            ) { change, _ ->
                onInspect.value(points[pickIndex(change.position.x, leftPx, plotW, points.size)].timeMs)
                change.consume()
            }
        }

/**
 * v5.0: Moon mood for the local calendar day. Temperature oscillator (cold to hot, -100 to +100) with the day's
 * extremes marked, plus an electric strip (Mercury and Uranus aspects to the Moon) underneath.
 */
@Composable
fun MoodCard(
    points: List<MoodPoint>,
    inspectedTimeMs: Long,
    nowMs: Long,
    timeZoneId: String,
    onInspectTime: (Long) -> Unit
) {
    if (points.isEmpty()) return

    val n = points.size
    val selIdx = (((inspectedTimeMs - points.first().timeMs) / 300_000L).toInt()).coerceIn(0, n - 1)
    val cur = points[selIdx]
    val iHigh = points.indices.maxByOrNull { points[it].temp } ?: 0
    val iLow = points.indices.minByOrNull { points[it].temp } ?: 0
    val iElec = points.indices.maxByOrNull { points[it].electric } ?: 0
    val avgT = points.sumOf { it.temp } / n
    val avgE = points.sumOf { it.electric } / n

    val timeFmt = remember(timeZoneId) {
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }
    }
    val dateFmt = remember(timeZoneId) {
        SimpleDateFormat("EEE, dd MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }
    }
    val currentOnInspect = rememberUpdatedState(onInspectTime)

    val chipColor = if (cur.quiet) MoodNeutral else tempColor(cur.temp)
    val dayStart = points.first().timeMs
    val dayEnd = points.last().timeMs + 300_000L

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderDark))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MOON MOOD  ·  TEMPERATURE OF THE DAY",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate100,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = dateFmt.format(Date(dayStart)),
                    fontSize = 11.sp,
                    color = Slate400
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mood now (at the inspected time)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = chipColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(chipColor.copy(alpha = 0.5f))
                    )
                ) {
                    Text(
                        text = AstroEngine.moodLabel(cur.temp, cur.electric, cur.quiet),
                        color = chipColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = "${timeFmt.format(Date(cur.timeMs))}   T ${fmtSigned(cur.temp)}   E %.0f".format(cur.electric),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = chipColor
                )
            }

            // What is driving it right now
            if (cur.quiet) {
                Text(
                    text = "The Moon has no aspect within orb at this moment.",
                    fontSize = 10.sp,
                    color = Slate400,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                cur.hits.take(3).forEach { h ->
                    val hc = when (h.cls) {
                        1 -> MoodHot
                        -1 -> MoodCold
                        2 -> MoodElectric
                        else -> MoodNeutral
                    }
                    Text(
                        text = "☽ ${ASPECT_NAMES[h.aspectAngle] ?: "${h.aspectAngle}°"} " +
                            (if (h.fromNatal) "natal" else "transit") + " ${h.glyph} ${h.planetName}  ·  ${clsWord(h.cls)}",
                        fontSize = 10.sp,
                        color = hc,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            // The day in one glance
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "High ${fmtSigned(points[iHigh].temp)} at ${timeFmt.format(Date(points[iHigh].timeMs))}   " +
                    "Low ${fmtSigned(points[iLow].temp)} at ${timeFmt.format(Date(points[iLow].timeMs))}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Slate100
            )
            Text(
                text = "Peak electric %.0f at ${timeFmt.format(Date(points[iElec].timeMs))}   Day average ${fmtSigned(avgT)}  ${AstroEngine.moodLabel(avgT, avgE)}".format(points[iElec].electric),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Slate400,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = "Cold ↔ hot, −100 to +100. Tinted bands beyond ±60 are the extremes. Tap or drag to move the time.",
                fontSize = 9.sp,
                color = Slate400,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
            )

            // ---------- temperature oscillator ----------
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .scrub(points, currentOnInspect)
                ) {
                    val left = 42.dp.toPx()
                    val right = 12.dp.toPx()
                    val top = 8.dp.toPx()
                    val bottom = 20.dp.toPx()
                    val plotW = size.width - left - right
                    val plotH = size.height - top - bottom
                    val cellW = plotW / n
                    fun yOf(v: Double): Float = top + (plotH / 2f) * (1f - (v / 100.0).toFloat())
                    fun xOf(i: Int): Float = left + (i + 0.5f) * cellW
                    val yZero = yOf(0.0)

                    drawRect(color = BgDark, size = size)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(MoodHot.copy(alpha = 0.20f), Color.Transparent, MoodCold.copy(alpha = 0.20f)),
                            startY = top,
                            endY = top + plotH
                        ),
                        topLeft = Offset(left, top),
                        size = Size(plotW, plotH)
                    )

                    // extreme zones beyond +-60
                    val yHot = yOf(AstroEngine.MOOD_EXTREME)
                    val yCold = yOf(-AstroEngine.MOOD_EXTREME)
                    drawRect(color = MoodHot.copy(alpha = 0.10f), topLeft = Offset(left, top), size = Size(plotW, yHot - top))
                    drawRect(color = MoodCold.copy(alpha = 0.10f), topLeft = Offset(left, yCold), size = Size(plotW, top + plotH - yCold))
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    drawLine(MoodHot.copy(alpha = 0.45f), Offset(left, yHot), Offset(left + plotW, yHot), 1f, pathEffect = dash)
                    drawLine(MoodCold.copy(alpha = 0.45f), Offset(left, yCold), Offset(left + plotW, yCold), 1f, pathEffect = dash)

                    // quiet stretches (no Moon aspect in orb)
                    for (i in 0 until n) {
                        if (points[i].quiet) {
                            drawRect(
                                color = MoodNeutral.copy(alpha = 0.14f),
                                topLeft = Offset(left + i * cellW, top),
                                size = Size(cellW + 0.5f, plotH)
                            )
                        }
                    }

                    drawLine(Color(0xFF475569), Offset(left - 4.dp.toPx(), yZero), Offset(left + plotW, yZero), 1.5f)

                    // soft fill from zero to the value
                    for (i in 0 until n) {
                        val v = points[i].temp
                        val y = yOf(v)
                        drawRect(
                            color = tempColor(v).copy(alpha = 0.30f),
                            topLeft = Offset(left + i * cellW, min(yZero, y)),
                            size = Size(cellW + 0.5f, max(abs(y - yZero), 0.5f))
                        )
                    }

                    // the oscillator line, coloured by temperature
                    for (i in 0 until n - 1) {
                        val a = points[i].temp
                        val b = points[i + 1].temp
                        drawLine(
                            color = tempColor((a + b) / 2.0),
                            start = Offset(xOf(i), yOf(a)),
                            end = Offset(xOf(i + 1), yOf(b)),
                            strokeWidth = 2.6f,
                            cap = StrokeCap.Round
                        )
                    }

                    // now marker
                    if (nowMs in dayStart until dayEnd) {
                        val nx = left + ((nowMs - dayStart).toFloat() / (dayEnd - dayStart).toFloat()) * plotW
                        drawLine(Amber500, Offset(nx, top), Offset(nx, top + plotH), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f))
                    }

                    // inspected time cursor
                    val sx = xOf(selIdx)
                    drawLine(Color.White, Offset(sx, top), Offset(sx, top + plotH), 2f)
                    drawCircle(Color.White, 4.dp.toPx(), Offset(sx, yOf(cur.temp)))
                    drawCircle(tempColor(cur.temp), 2.5.dp.toPx(), Offset(sx, yOf(cur.temp)))

                    val labelPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 9.sp.toPx()
                        color = Slate100.toArgb()
                    }

                    // day high and low markers with value and time
                    fun marker(idx: Int, above: Boolean) {
                        val p = points[idx]
                        val x = xOf(idx)
                        val y = yOf(p.temp)
                        drawCircle(Color.White, 5.dp.toPx(), Offset(x, y))
                        drawCircle(tempColor(p.temp), 3.5.dp.toPx(), Offset(x, y))
                        val rightSide = x > size.width * 0.62f
                        labelPaint.textAlign = if (rightSide) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
                        drawContext.canvas.nativeCanvas.drawText(
                            fmtSigned(p.temp) + "  " + timeFmt.format(Date(p.timeMs)),
                            if (rightSide) x - 8.dp.toPx() else x + 8.dp.toPx(),
                            y + if (above) -8.dp.toPx() else 14.dp.toPx(),
                            labelPaint
                        )
                    }
                    if (points[iHigh].temp > 5.0) marker(iHigh, true)
                    if (points[iLow].temp < -5.0) marker(iLow, false)

                    // value labels in the left gap
                    labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                    listOf(100.0 to "+100", 60.0 to "+60", 0.0 to "0", -60.0 to "−60", -100.0 to "−100").forEach { (v, t) ->
                        labelPaint.color = (if (v > 0) MoodHot else if (v < 0) MoodCold else MoodNeutral).toArgb()
                        drawContext.canvas.nativeCanvas.drawText(t, left - 7.dp.toPx(), yOf(v) + labelPaint.textSize * 0.35f, labelPaint)
                    }

                    // clock labels every 3 hours
                    labelPaint.color = Slate400.toArgb()
                    labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                    var i = 0
                    while (i < n) {
                        drawContext.canvas.nativeCanvas.drawText(
                            timeFmt.format(Date(points[i].timeMs)),
                            left + i * cellW,
                            size.height - 4.dp.toPx(),
                            labelPaint
                        )
                        i += 36
                    }
                }
            }

            // ---------- electric strip ----------
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ELECTRIC  (Mercury and Uranus aspects to the Moon, brighter = stronger)",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .scrub(points, currentOnInspect)
                ) {
                    val left = 42.dp.toPx()
                    val plotW = size.width - left - 12.dp.toPx()
                    val cellW = plotW / n
                    drawRect(color = BgDark, size = size)
                    for (i in 0 until n) {
                        val e = points[i].electric
                        if (e < 1.0) continue
                        drawRect(
                            color = MoodElectric.copy(alpha = (0.08f + 0.92f * (e / 100.0).toFloat()).coerceIn(0.08f, 1f)),
                            topLeft = Offset(left + i * cellW, 3.dp.toPx()),
                            size = Size(cellW + 0.5f, size.height - 6.dp.toPx())
                        )
                    }
                    if (points[iElec].electric >= 20.0) {
                        drawCircle(Color.White, 2.5.dp.toPx(), Offset(left + (iElec + 0.5f) * cellW, size.height / 2f))
                    }
                    if (nowMs in dayStart until dayEnd) {
                        val nx = left + ((nowMs - dayStart).toFloat() / (dayEnd - dayStart).toFloat()) * plotW
                        drawLine(Amber500, Offset(nx, 0f), Offset(nx, size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f))
                    }
                    val sx = left + (selIdx + 0.5f) * cellW
                    drawLine(Color.White, Offset(sx, 0f), Offset(sx, size.height), 2f)

                    val lp = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 9.sp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                        color = MoodElectric.toArgb()
                    }
                    drawContext.canvas.nativeCanvas.drawText("elec", left - 7.dp.toPx(), size.height / 2f + lp.textSize * 0.35f, lp)
                }
            }
        }
    }
}
