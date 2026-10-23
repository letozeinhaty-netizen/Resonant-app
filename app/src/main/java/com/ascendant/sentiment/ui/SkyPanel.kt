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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascendant.sentiment.engine.AstroEngine
import com.ascendant.sentiment.model.AxisWeek
import com.ascendant.sentiment.model.SentimentBar
import com.ascendant.sentiment.model.SkyWeek
import com.ascendant.sentiment.model.TurningData
import com.ascendant.sentiment.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val SkyTT = Color(0xFF38BDF8)      // transit to transit
private val SkyTN = Color(0xFFE879F9)      // transit to natal
private val SkyTotal = Color(0xFFF8FAFC)   // total (bold)
private val SkyVoc = Color(0xFF60A5FA)
private val SkyStation = Color(0xFFF59E0B)
private val SkyMacdCyan = Color(0xFF22D3EE)
private val SkyMacdOrange = Color(0xFFFB923C)

private fun fmtS(v: Double): String = (if (v > 0.05) "+" else if (v < -0.05) "−" else "") + "%.1f".format(abs(v))

private fun barIndexAt(x: Float, leftPx: Float, plotW: Float, n: Int): Int =
    (((x - leftPx) / plotW) * n).toInt().coerceIn(0, n - 1)

private fun Modifier.weekScrub(key: Any, n: Int, startMs: Long, barMs: Long, onInspect: State<(Long) -> Unit>): Modifier =
    this
        .pointerInput(key) {
            val leftPx = 42.dp.toPx()
            val plotW = size.width - leftPx - 12.dp.toPx()
            detectTapGestures { pos ->
                val i = barIndexAt(pos.x, leftPx, plotW, n)
                onInspect.value(((startMs + i * barMs + barMs / 2) / 300_000L) * 300_000L)
            }
        }
        .pointerInput(key) {
            val leftPx = 42.dp.toPx()
            val plotW = size.width - leftPx - 12.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { pos ->
                    val i = barIndexAt(pos.x, leftPx, plotW, n)
                    onInspect.value(((startMs + i * barMs + barMs / 2) / 300_000L) * 300_000L)
                }
            ) { change, _ ->
                val i = barIndexAt(change.position.x, leftPx, plotW, n)
                onInspect.value(((startMs + i * barMs + barMs / 2) / 300_000L) * 300_000L)
                change.consume()
            }
        }

/**
 * v6.1: the sky field for the whole week in 6-hour bars. Thin blue line = transit-to-transit field, thin pink line =
 * transit-to-natal field, bold white line = their total, with the MACD of the total faint in the background.
 * Blue blocks along the bottom are the Moon void of course, amber marks are stations.
 */
@Composable
fun SkyFieldCard(
    week: SkyWeek,
    inspectedTimeMs: Long,
    nowMs: Long,
    timeZoneId: String,
    taintAllMm: Boolean,
    onInspectTime: (Long) -> Unit
) {
    val bars = week.bars
    if (bars.isEmpty()) return
    val n = bars.size
    val weekStart = bars.first().startMs
    val weekEnd = weekStart + n * week.barMs
    val selIdx = ((inspectedTimeMs - weekStart) / week.barMs).toInt().coerceIn(0, n - 1)
    val cur = bars[selIdx]
    val iHi = bars.indices.maxByOrNull { bars[it].total } ?: 0
    val iLo = bars.indices.minByOrNull { bars[it].total } ?: 0
    val timeFmt = remember(timeZoneId) { SimpleDateFormat("EEE HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone(timeZoneId) } }
    val dayFmt = remember(timeZoneId) { SimpleDateFormat("EEE d", Locale.US).apply { timeZone = TimeZone.getTimeZone(timeZoneId) } }
    val onInspect = rememberUpdatedState(onInspectTime)
    val maxLine = remember(week) { niceCeil(max(1.0, bars.maxOf { max(abs(it.tt), max(abs(it.tn), abs(it.total))) } * 1.08)) }
    val maxMacd = remember(week) { max(0.1, week.macd.maxOf { abs(it) }.coerceAtLeast(week.signal.maxOf { abs(it) }).coerceAtLeast(week.hist.maxOf { abs(it) })) * 1.1 }
    val hits = remember(selIdx, week, taintAllMm) {
        AstroEngine.skyHits(cur.startMs + week.barMs / 2, taintAllMm).take(3)
    }
    val aligned = cur.tt * cur.tn > 0
    val histNow = week.hist[selIdx]

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderDark))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SKY FIELD · WEEK (6h bars)", color = Slate400, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    timeFmt.format(Date(cur.startMs)) + " bar",
                    color = Slate400, fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total " + fmtS(cur.total), color = if (cur.total >= 0) Emerald400 else Rose400, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text("Sky " + fmtS(cur.tt), color = SkyTT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Natal " + fmtS(cur.tn), color = SkyTN, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                (if (aligned) "Sky and natal fields agree" else "Sky and natal fields pull opposite ways") +
                    " · MACD hist " + fmtS(histNow) + if (histNow > 0) " (rising)" else " (falling)",
                color = if (aligned) Slate100 else Amber500, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .weekScrub(week, n, weekStart, week.barMs, onInspect)
            ) {
                val left = 42.dp.toPx()
                val right = 12.dp.toPx()
                val top = 8.dp.toPx()
                val bottom = 26.dp.toPx()   // room for the VOC / station rows and day labels
                val plotW = size.width - left - right
                val plotH = size.height - top - bottom
                val cell = plotW / n
                fun xOf(i: Int) = left + (i + 0.5f) * cell
                fun yOf(v: Double) = (top + plotH / 2.0 * (1.0 - v / maxLine)).toFloat()
                fun yM(v: Double) = (top + plotH / 2.0 * (1.0 - v / maxMacd)).toFloat()
                val yZero = yOf(0.0)

                // VOC shading behind everything
                for (i in 0 until n) {
                    val b = bars[i]
                    if (b.vocShare > 0.02) {
                        drawRect(SkyVoc.copy(alpha = 0.10f + 0.12f * b.vocShare.toFloat()), Offset(left + i * cell, top), Size(cell, plotH))
                    }
                }
                // MACD background: histogram bars, MACD and signal lines (own scale, faint)
                for (i in 0 until n) {
                    val h = week.hist[i]
                    val y = yM(h)
                    val y0 = yM(0.0)
                    drawRect(
                        (if (h >= 0) Emerald500 else Rose500).copy(alpha = 0.22f),
                        Offset(left + i * cell + 1f, min(y, y0)), Size(max(cell - 2f, 1f), max(abs(y - y0), 1f))
                    )
                }
                fun line(values: DoubleArray, color: Color, w: Float) {
                    val p = Path()
                    for (i in 0 until n) { val x = xOf(i); val y = yM(values[i]); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                    drawPath(p, color, style = Stroke(width = w, cap = StrokeCap.Round))
                }
                line(week.macd, SkyMacdCyan.copy(alpha = 0.35f), 1.5f)
                line(week.signal, SkyMacdOrange.copy(alpha = 0.35f), 1.5f)

                // guides and zero line
                listOf(1.0, -1.0).forEach { f ->
                    drawLine(Color(0xFF475569).copy(alpha = 0.35f), Offset(left, yOf(f * maxLine)), Offset(left + plotW, yOf(f * maxLine)), 1f)
                }
                drawLine(Color(0xFF475569), Offset(left - 4.dp.toPx(), yZero), Offset(left + plotW, yZero), 1.5f)

                // day separators and labels
                var lastDay = ""
                for (i in 0 until n) {
                    val label = dayFmt.format(Date(bars[i].startMs))
                    if (label != lastDay) {
                        lastDay = label
                        drawLine(Color(0xFF334155).copy(alpha = 0.6f), Offset(left + i * cell, top), Offset(left + i * cell, top + plotH), 1f)
                        drawContext.canvas.nativeCanvas.drawText(
                            label, left + i * cell + 3.dp.toPx(), size.height - 2.dp.toPx(),
                            android.graphics.Paint().apply { isAntiAlias = true; textSize = 9.sp.toPx(); color = Slate400.toArgb() }
                        )
                    }
                }

                // the three field lines
                fun fieldLine(sel: (Int) -> Double, color: Color, w: Float) {
                    val p = Path()
                    for (i in 0 until n) { val x = xOf(i); val y = yOf(sel(i)); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                    drawPath(p, color, style = Stroke(width = w, cap = StrokeCap.Round))
                }
                fieldLine({ bars[it].tt }, SkyTT, 1.8.dp.toPx())
                fieldLine({ bars[it].tn }, SkyTN, 1.8.dp.toPx())
                fieldLine({ bars[it].total }, SkyTotal, 3.6.dp.toPx())
                // week high / low of the total
                drawCircle(Emerald400, 4.dp.toPx(), Offset(xOf(iHi), yOf(bars[iHi].total)))
                drawCircle(Rose400, 4.dp.toPx(), Offset(xOf(iLo), yOf(bars[iLo].total)))

                // station marks along the bottom of the plot
                val glyphPaint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 11.sp.toPx(); color = SkyStation.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
                for (i in 0 until n) {
                    val b = bars[i]
                    if (b.stationIndex >= com.ascendant.sentiment.engine.AstroEngine.STATION_CONTEXT) {
                        val a = (b.stationIndex / 100.0).toFloat().coerceIn(0.25f, 1f)
                        drawRect(SkyStation.copy(alpha = a), Offset(left + i * cell + 1f, top + plotH - 4.dp.toPx()), Size(max(cell - 2f, 1f), 4.dp.toPx()))
                        val isPeak = (i == 0 || bars[i - 1].stationIndex <= b.stationIndex) && (i == n - 1 || bars[i + 1].stationIndex < b.stationIndex)
                        if (isPeak && b.stations.isNotEmpty()) {
                            drawContext.canvas.nativeCanvas.drawText(
                                b.stations.take(2).joinToString("") { it.glyph + if (it.turningRetro) "R" else "D" },
                                xOf(i), top + plotH - 7.dp.toPx(), glyphPaint
                            )
                        }
                    }
                }

                // now marker and inspected cursor
                if (nowMs in weekStart until weekEnd) {
                    val nx = left + ((nowMs - weekStart).toFloat() / (weekEnd - weekStart)) * plotW
                    drawLine(Color(0xFFFBBF24), Offset(nx, top), Offset(nx, top + plotH), 1.5f)
                }
                drawLine(Color.White, Offset(xOf(selIdx), top), Offset(xOf(selIdx), top + plotH), 2f)

                // scale labels
                val lp = android.graphics.Paint().apply { isAntiAlias = true; textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.RIGHT }
                fun lab(t: String, v: Double, c: Color) {
                    lp.color = c.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(t, left - 7.dp.toPx(), yOf(v) + lp.textSize * 0.35f, lp)
                }
                lab("+" + fmtScale(maxLine), maxLine, Emerald400.copy(alpha = 0.8f))
                lab("0", 0.0, Slate400)
                lab("−" + fmtScale(maxLine), -maxLine, Rose400.copy(alpha = 0.8f))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "━ sky   ━ natal   ━━ total   · faint: MACD of total (cyan) / signal (orange) / hist · blue = Moon void of course · amber = station",
                color = Slate400, fontSize = 9.sp, lineHeight = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Week high ${fmtS(bars[iHi].total)} (${timeFmt.format(Date(bars[iHi].startMs))})  ·  low ${fmtS(bars[iLo].total)} (${timeFmt.format(Date(bars[iLo].startMs))})",
                color = Slate100, fontSize = 11.sp
            )
            if (cur.vocShare > 0.02) {
                Text("Moon void of course for ${"%.0f".format(cur.vocShare * 100)}% of this bar", color = SkyVoc, fontSize = 11.sp)
            }
            if (cur.stationIndex >= AstroEngine.STATION_CONTEXT && cur.stations.isNotEmpty()) {
                Text(
                    "Stations: " + cur.stations.joinToString(", ") { it.planetName + (if (it.turningRetro) " turning retrograde" else " turning direct") },
                    color = SkyStation, fontSize = 11.sp
                )
            }
            if (hits.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Strongest sky aspects now", color = Slate400, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                hits.forEach { h ->
                    Text(
                        "${h.actorGlyph} ${h.actorName} ${h.aspectAngle.toInt()}° ${h.targetGlyph} ${h.targetName}   ${fmtS(h.value)}",
                        color = if (h.value >= 0) Emerald400 else Rose400, fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/** v6.1: two thin rows under the detrended pane, lined up with the main chart window. Top: Moon void of course. Bottom: stations. */
@Composable
fun TurningStrip(
    series: List<SentimentBar>,
    td: TurningData,
    inspectedTimeMs: Long,
    view: IntArray,
    zoom: Float,
    showVoc: Boolean,
    showStations: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().height(40.dp)
) {
    if (series.isEmpty() || td.voc.size != series.size) return
    val numBars = series.size
    val selIdx = ((inspectedTimeMs - series.first().timeMs) / 300_000L).toInt().coerceIn(0, numBars - 1)
    val start = view[0].coerceIn(0, numBars - 1)
    val count = view[1].coerceIn(1, numBars - start)
    val zoomKey = zoom

    Canvas(modifier = modifier) {
        val left = 42.dp.toPx()
        val plotW = size.width - left - 12.dp.toPx()
        val bw = plotW / count
        val rowH = (size.height - 6.dp.toPx()) / 2f
        drawRect(BgDark, size = size)
        val glyph = android.graphics.Paint().apply { isAntiAlias = true; textSize = 10.sp.toPx(); color = SkyStation.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
        val tag = android.graphics.Paint().apply { isAntiAlias = true; textSize = 9.sp.toPx(); color = Slate400.toArgb(); textAlign = android.graphics.Paint.Align.RIGHT }
        drawContext.canvas.nativeCanvas.drawText("VOC", left - 7.dp.toPx(), 3.dp.toPx() + rowH * 0.8f, tag)
        drawContext.canvas.nativeCanvas.drawText("STN", left - 7.dp.toPx(), 3.dp.toPx() + rowH * 1.8f, tag)

        var lastGlyphX = -1000f
        for (i in start until start + count) {
            val x = left + (i - start) * bw
            if (showVoc && td.voc[i]) {
                drawRect(SkyVoc.copy(alpha = 0.7f), Offset(x, 3.dp.toPx()), Size(max(bw, 1f), rowH - 2.dp.toPx()))
            }
            if (showStations && td.stationIndex[i] >= AstroEngine.STATION_CONTEXT) {
                val a = (td.stationIndex[i] / 100.0).toFloat().coerceIn(0.3f, 1f)
                drawRect(SkyStation.copy(alpha = a), Offset(x, 3.dp.toPx() + rowH), Size(max(bw, 1f), rowH - 2.dp.toPx()))
                val items = td.stations[i]
                if (items.isNotEmpty() && x - lastGlyphX > 44.dp.toPx() && i % 12 == 0) {
                    lastGlyphX = x
                    drawContext.canvas.nativeCanvas.drawText(
                        items.take(2).joinToString("") { it.glyph + if (it.turningRetro) "R" else "D" },
                        x + 14.dp.toPx(), 3.dp.toPx() + rowH * 1.8f, glyph
                    )
                }
            }
        }
        val sx = left + (selIdx - start + 0.5f) * bw
        drawLine(Color.White, Offset(sx, 2.dp.toPx()), Offset(sx, size.height - 2.dp.toPx()), 2f)
    }
}

private val AxisAsc = Color(0xFFFBBF24)   // Ascendant line
private val AxisMc = Color(0xFFA78BFA)    // Midheaven line

private fun fmtI(v: Double): String = (if (v > 0.005) "+" else if (v < -0.005) "−" else "") + "%.2f".format(abs(v))

/**
 * v6.3: the Ascendant line and the Midheaven line for the week, 6-hour bars, same layout as the sky field card.
 * Amber = Ascendant against the transiting planets. Violet = Midheaven against the natal planets.
 */
@Composable
fun AxisFieldCard(
    week: AxisWeek,
    inspectedTimeMs: Long,
    nowMs: Long,
    timeZoneId: String,
    onInspectTime: (Long) -> Unit
) {
    val bars = week.bars
    if (bars.isEmpty()) return
    val n = bars.size
    val weekStart = bars.first().startMs
    val weekEnd = weekStart + n * week.barMs
    val selIdx = ((inspectedTimeMs - weekStart) / week.barMs).toInt().coerceIn(0, n - 1)
    val cur = bars[selIdx]
    val timeFmt = remember(timeZoneId) { SimpleDateFormat("EEE HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone(timeZoneId) } }
    val dayFmt = remember(timeZoneId) { SimpleDateFormat("EEE d", Locale.US).apply { timeZone = TimeZone.getTimeZone(timeZoneId) } }
    val onInspect = rememberUpdatedState(onInspectTime)
    val maxV = remember(week) { niceCeil(max(0.1, bars.maxOf { max(abs(it.asc), abs(it.mc)) } * 1.1)) }
    val iAscHi = bars.indices.maxByOrNull { bars[it].asc } ?: 0
    val iAscLo = bars.indices.minByOrNull { bars[it].asc } ?: 0
    val iMcHi = bars.indices.maxByOrNull { bars[it].mc } ?: 0
    val iMcLo = bars.indices.minByOrNull { bars[it].mc } ?: 0
    val aligned = cur.asc * cur.mc > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderDark))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ASCENDANT & MIDHEAVEN · WEEK (6h bars)", color = Slate400, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(timeFmt.format(Date(cur.startMs)) + " bar", color = Slate400, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Asc " + fmtI(cur.asc), color = AxisAsc, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("MC " + fmtI(cur.mc), color = AxisMc, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                if (aligned) "Ascendant and Midheaven point the same way" else "Ascendant and Midheaven point opposite ways",
                color = if (aligned) Slate100 else Amber500, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .weekScrub(week, n, weekStart, week.barMs, onInspect)
            ) {
                val left = 42.dp.toPx()
                val right = 12.dp.toPx()
                val top = 8.dp.toPx()
                val bottom = 16.dp.toPx()
                val plotW = size.width - left - right
                val plotH = size.height - top - bottom
                val cell = plotW / n
                fun xOf(i: Int) = left + (i + 0.5f) * cell
                fun yOf(v: Double) = (top + plotH / 2.0 * (1.0 - v / maxV)).toFloat()
                val yZero = yOf(0.0)

                listOf(1.0, -1.0).forEach { f ->
                    drawLine(Color(0xFF475569).copy(alpha = 0.35f), Offset(left, yOf(f * maxV)), Offset(left + plotW, yOf(f * maxV)), 1f)
                }
                drawLine(Color(0xFF475569), Offset(left - 4.dp.toPx(), yZero), Offset(left + plotW, yZero), 1.5f)

                var lastDay = ""
                for (i in 0 until n) {
                    val label = dayFmt.format(Date(bars[i].startMs))
                    if (label != lastDay) {
                        lastDay = label
                        drawLine(Color(0xFF334155).copy(alpha = 0.6f), Offset(left + i * cell, top), Offset(left + i * cell, top + plotH), 1f)
                        drawContext.canvas.nativeCanvas.drawText(
                            label, left + i * cell + 3.dp.toPx(), size.height - 2.dp.toPx(),
                            android.graphics.Paint().apply { isAntiAlias = true; textSize = 9.sp.toPx(); color = Slate400.toArgb() }
                        )
                    }
                }

                fun fieldLine(sel: (Int) -> Double, color: Color, w: Float) {
                    val p = Path()
                    for (i in 0 until n) { val x = xOf(i); val y = yOf(sel(i)); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                    drawPath(p, color, style = Stroke(width = w, cap = StrokeCap.Round))
                }
                fieldLine({ bars[it].asc }, AxisAsc, 2.4.dp.toPx())
                fieldLine({ bars[it].mc }, AxisMc, 2.4.dp.toPx())
                drawCircle(AxisAsc, 3.5.dp.toPx(), Offset(xOf(iAscHi), yOf(bars[iAscHi].asc)))
                drawCircle(AxisAsc, 3.5.dp.toPx(), Offset(xOf(iAscLo), yOf(bars[iAscLo].asc)))
                drawCircle(AxisMc, 3.5.dp.toPx(), Offset(xOf(iMcHi), yOf(bars[iMcHi].mc)))
                drawCircle(AxisMc, 3.5.dp.toPx(), Offset(xOf(iMcLo), yOf(bars[iMcLo].mc)))

                if (nowMs in weekStart until weekEnd) {
                    val nx = left + ((nowMs - weekStart).toFloat() / (weekEnd - weekStart)) * plotW
                    drawLine(Color(0xFFFBBF24).copy(alpha = 0.6f), Offset(nx, top), Offset(nx, top + plotH), 1.5f)
                }
                drawLine(Color.White, Offset(xOf(selIdx), top), Offset(xOf(selIdx), top + plotH), 2f)

                val lp = android.graphics.Paint().apply { isAntiAlias = true; textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.RIGHT }
                fun lab(t: String, v: Double, c: Color) {
                    lp.color = c.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(t, left - 7.dp.toPx(), yOf(v) + lp.textSize * 0.35f, lp)
                }
                lab("+" + fmtScale(maxV), maxV, Emerald400.copy(alpha = 0.8f))
                lab("0", 0.0, Slate400)
                lab("−" + fmtScale(maxV), -maxV, Rose400.copy(alpha = 0.8f))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "━ Ascendant vs transiting planets   ━ Midheaven vs natal planets   · dots = week high / low · each bar averages 15-minute readings",
                color = Slate400, fontSize = 9.sp, lineHeight = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Asc high ${fmtI(bars[iAscHi].asc)} (${timeFmt.format(Date(bars[iAscHi].startMs))}) · low ${fmtI(bars[iAscLo].asc)} (${timeFmt.format(Date(bars[iAscLo].startMs))})",
                color = AxisAsc, fontSize = 11.sp
            )
            Text(
                "MC high ${fmtI(bars[iMcHi].mc)} (${timeFmt.format(Date(bars[iMcHi].startMs))}) · low ${fmtI(bars[iMcLo].mc)} (${timeFmt.format(Date(bars[iMcLo].startMs))})",
                color = AxisMc, fontSize = 11.sp
            )
        }
    }
}
