package com.ascendant.sentiment

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascendant.sentiment.engine.AstroEngine
import com.ascendant.sentiment.model.GeoLocation
import com.ascendant.sentiment.ui.*
import com.ascendant.sentiment.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AscendantSentimentTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = BgDark
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onHaptic = { triggerHaptic() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onHaptic: () -> Unit
) {
    var isLive by remember { mutableStateOf(true) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var centerTimeMs by remember { mutableLongStateOf((System.currentTimeMillis() / 300_000L) * 300_000L) }
    var inspectedTimeMs by remember { mutableLongStateOf((System.currentTimeMillis() / 300_000L) * 300_000L) }
    var windowHours by remember { mutableIntStateOf(6) }
    var selectedLocationIdx by remember { mutableIntStateOf(0) }
    var selectedTimezone by remember { mutableStateOf("Europe/London") }
    var useSd by remember { mutableStateOf(true) }   // v3.0: sign dignity switch
    var usePm by remember { mutableStateOf(true) }   // v3.0: planet-to-planet modifier switch
    var showMc by remember { mutableStateOf(true) }          // v3.2: Midheaven birth chart overlay
    var zoom by remember { mutableStateOf(2f) }              // v3.3: chart zoom (1 = all bars); v6.7: default 2×
    var taintAllMm by remember { mutableStateOf(true) } // v3.1: Mercury/Mars taint flips ALL Ascendant aspects
    var orbFalloff by remember { mutableStateOf(true) }   // v4.0: resonance orb falloff (1 - orb/3)
    var pairBoost by remember { mutableStateOf(true) }    // v4.0: x1.5 when a planet aspects its own natal position
    var trigger by remember { mutableStateOf(true) }      // v4.0: Ascendant / Midheaven trigger boost
    var showFlags by remember { mutableStateOf(true) }    // v4.3: zero-cross flags on the main chart
    var showShare by remember { mutableStateOf(false) }    // v4.3: bull share strip
    var showDetrAsc by remember { mutableStateOf(true) }  // v6.5: detrended Ascendant aspect total
    var showDetrMc by remember { mutableStateOf(false) }   // v6.4: detrended Midheaven-natal pane
    var showDetr by remember { mutableStateOf(false) }     // v4.2: detrended net pane
    var showMacd by remember { mutableStateOf(false) }     // v4.3: MACD pane
    var showMood by remember { mutableStateOf(true) }     // v5.0: Moon mood card
    var showVoc by remember { mutableStateOf(false) }      // v6.1: void-of-course Moon row
    var showStations by remember { mutableStateOf(false) } // v6.1: station row
    var moonInNatal by remember { mutableStateOf(true) }  // v6.2: transiting Moon in the sky card's natal line
    var showResonance by remember { mutableStateOf(false) } // v6.7: main resonance chart (off by default)
    var showCards by remember { mutableStateOf(false) }     // v6.7: breakdown, active Midheaven / Ascendant and exact hits cards
    var showSky by remember { mutableStateOf(true) }      // v6.1: sky field week card
    var skyTintOn by remember { mutableStateOf(true) }    // v6.1: sky tint on the detrended pane

    val locations = AstroEngine.PRESET_LOCATIONS
    val activeLocation = locations[selectedLocationIdx]

    // 1-second live ticker
    LaunchedEffect(isLive) {
        while (true) {
            val cur = System.currentTimeMillis()
            nowMs = cur
            if (isLive) {
                val snapped = (cur / 300_000L) * 300_000L
                centerTimeMs = snapped
                inspectedTimeMs = snapped
            }
            delay(1000)
        }
    }

    // Planetary series calculation (100% offline pure Kotlin)
    val series = remember(centerTimeMs, windowHours, activeLocation, useSd, usePm, taintAllMm, orbFalloff, pairBoost, trigger) {
        AstroEngine.generateSeries(
            centerMs = centerTimeMs,
            windowHours = windowHours,
            lat = activeLocation.lat,
            lon = activeLocation.lon,
            orbLimit = 3.0,
            useSd = useSd,
            usePm = usePm,
            taintAllMm = taintAllMm,
            orbFalloff = orbFalloff,
            pairBoost = pairBoost,
            trigger = trigger
        )
    }

    // v4.2: visible window shared by the main chart and the detrended pane, and the detrended net (net minus 1-hour average)
    val chartView = remember(series) { intArrayOf(0, series.size, 1000) }
    val detr = remember(series) { AstroEngine.detrendedNet(series) }
    val detrMc = remember(series, orbFalloff) { AstroEngine.detrendedAxisSum(series, true, orbFalloff) }    // v6.5
    val detrAsc = remember(series, orbFalloff) { AstroEngine.detrendedAxisSum(series, false, orbFalloff) }  // v6.5
    // v5.0: Moon mood for the local calendar day of the inspected time (recomputed only when the day changes)
    val dayBounds = remember(inspectedTimeMs, selectedTimezone) { AstroEngine.dayBoundsMs(inspectedTimeMs, selectedTimezone) }
    val moodPoints = remember(dayBounds.first, dayBounds.second, showMood) {
        if (showMood) AstroEngine.calculateMoodSeries(dayBounds.first, dayBounds.second) else emptyList()
    }
    val macd = remember(series) { AstroEngine.macdOfNet(series) }          // v4.3
    // v6.1: turning points (void-of-course Moon, stations) and the sky field per bar
    val td = remember(series, taintAllMm) { AstroEngine.turningData(series, 3.0, taintAllMm) }
    // v6.1: sky field week card, 6-hour bars, recomputed when the local week changes or the switches change
    val weekBounds = remember(inspectedTimeMs / 86_400_000L, selectedTimezone) { AstroEngine.weekBoundsMs(inspectedTimeMs, selectedTimezone) }
    val skyWeek = remember(weekBounds.first, showSky, moonInNatal, useSd, usePm, taintAllMm, orbFalloff, pairBoost) {
        if (showSky) AstroEngine.calculateSkyWeek(
            startMs = weekBounds.first, useSd = useSd, usePm = usePm, taintAllMm = taintAllMm,
            orbFalloff = orbFalloff, pairBoost = pairBoost, moonInNatal = moonInNatal
        ) else null
    }
    val crossFlags = remember(series) { AstroEngine.zeroCrossFlags(series) } // v4.3

    // v4.1: slow stochastic of the net resonance (yellow line)
    val stoch = remember(series) { AstroEngine.stochasticOfNet(series) }

    // Exact hits in window
    val exactHits = remember(series, taintAllMm) {
        AstroEngine.calculateExactHits(series, 3.0, taintAllMm)
    }

    // Inspected Bar
    val currentBar = remember(series, inspectedTimeMs) {
        if (series.isEmpty()) null
        else {
            val firstT = series.first().timeMs
            val idx = ((inspectedTimeMs - firstT) / 300_000.0).toInt().coerceIn(0, series.size - 1)
            series[idx]
        }
    }

    val hmSdf = remember(selectedTimezone) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone(selectedTimezone) } }
    val liveSdf = remember(selectedTimezone) {
        SimpleDateFormat("EEE, dd MMM HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(selectedTimezone)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Ascendant Sentiment",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate100
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = Emerald500.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Brent CFD",
                            color = Emerald400,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = liveSdf.format(Date(nowMs)) + if (isLive) " · LIVE" else " · PAUSED",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isLive) Emerald400 else Amber500
                )
            }

            Surface(
                color = CardDark,
                shape = RoundedCornerShape(8.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderDark))
            ) {
                Text(
                    text = "100% Offline Native",
                    color = Slate400,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Live & Location Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(14.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderDark))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Location & Window Selectors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        locations.forEachIndexed { idx, loc ->
                            val isSelected = idx == selectedLocationIdx
                            Surface(
                                color = if (isSelected) Emerald500.copy(alpha = 0.2f) else Slate950,
                                shape = RoundedCornerShape(8.dp),
                                border = CardDefaults.outlinedCardBorder().copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(if (isSelected) Emerald400 else BorderDark)
                                ),
                                modifier = Modifier.clickable {
                                    onHaptic()
                                    selectedLocationIdx = idx
                                }
                            ) {
                                Text(
                                    text = loc.name.split(" ").first(),
                                    fontSize = 11.sp,
                                    color = if (isSelected) Emerald400 else Slate400,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // v3.0: model switches, turn both off to see the original formula
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = useSd,
                            onCheckedChange = {
                                onHaptic()
                                useSd = it
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Emerald500)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sd  sign dignity", fontSize = 11.sp, color = Slate100)
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = usePm,
                            onCheckedChange = {
                                onHaptic()
                                usePm = it
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Emerald500)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pm  planet aspects", fontSize = 11.sp, color = Slate100)
                    }
                }

                // v3.1: Mercury & Mars taint switch (on = tainted flips all their Ascendant aspects)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = taintAllMm,
                        onCheckedChange = {
                            onHaptic()
                            taintAllMm = it
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Emerald500)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Mercury & Mars taint: all aspects (off = no taint effect)", fontSize = 11.sp, color = Slate100)
                }

                // v3.2: Midheaven overlay switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = showMc,
                            onCheckedChange = {
                                onHaptic()
                                showMc = it
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Emerald500)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MC  birth chart overlay", fontSize = 11.sp, color = Slate100)
                    }
                }

                // v4.0: resonance switches (turn all three off to see the plain Sp x Wp x Cj sums)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResonanceSwitch("Orb falloff", orbFalloff, Modifier.weight(1f)) { onHaptic(); orbFalloff = it }
                    ResonanceSwitch("Pair boost ×1.5", pairBoost, Modifier.weight(1f)) { onHaptic(); pairBoost = it }
                }
                ResonanceSwitch("Asc / MC trigger boost (+0.5 each, max ×2)", trigger, Modifier.fillMaxWidth()) { onHaptic(); trigger = it }

                // v4.3: show / hide the extra panes and flags (they take screen space)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResonanceSwitch("Zero-cross flags", showFlags, Modifier.weight(1f)) { onHaptic(); showFlags = it }
                    ResonanceSwitch("Bull share strip", showShare, Modifier.weight(1f)) { onHaptic(); showShare = it }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResonanceSwitch("Detrended pane", showDetr, Modifier.weight(1f)) { onHaptic(); showDetr = it }
                    ResonanceSwitch("Detrended Asc", showDetrAsc, Modifier.weight(1f)) { onHaptic(); showDetrAsc = it }
                    ResonanceSwitch("Detrended MC", showDetrMc, Modifier.weight(1f)) { onHaptic(); showDetrMc = it }
                    ResonanceSwitch("MACD pane", showMacd, Modifier.weight(1f)) { onHaptic(); showMacd = it }
                }
                ResonanceSwitch("Main resonance chart", showResonance, Modifier.fillMaxWidth()) { onHaptic(); showResonance = it }
                ResonanceSwitch("Detail cards (breakdown, active aspects, exact hits)", showCards, Modifier.fillMaxWidth()) { onHaptic(); showCards = it }
                ResonanceSwitch("Moon mood card (temperature of the day)", showMood, Modifier.fillMaxWidth()) { onHaptic(); showMood = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResonanceSwitch("VOC Moon row", showVoc, Modifier.weight(1f)) { onHaptic(); showVoc = it }
                    ResonanceSwitch("Station row", showStations, Modifier.weight(1f)) { onHaptic(); showStations = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResonanceSwitch("Sky field week card", showSky, Modifier.weight(1f)) { onHaptic(); showSky = it }
                    ResonanceSwitch("Sky trend tint", skyTintOn, Modifier.weight(1f)) { onHaptic(); skyTintOn = it }
                }
                ResonanceSwitch("Moon in sky card natal line", moonInNatal, Modifier.fillMaxWidth()) { onHaptic(); moonInNatal = it }
            }
        }

        // Sentiment Gauge
        SentimentGaugeCard(
            currentBar = currentBar,
            locationName = activeLocation.name,
            timeZoneId = selectedTimezone,
            orbLimit = 3.0
        )

        // 5-minute Sentiment Chart (v2.0: Active Aspects behind it, step toggles below it)
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
                        text = if (showResonance) "TRANSIT–NATAL RESONANCE PER 5-MIN BAR" else "PANES · 5-MIN BARS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate100,
                        modifier = Modifier.weight(1f)
                    )
                    // v3.3: zoom out / in
                    OutlinedButton(
                        onClick = {
                            onHaptic()
                            zoom = (zoom / 2f).coerceIn(1f, 12f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(width = 34.dp, height = 30.dp)
                    ) {
                        Text("−", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate100)
                    }
                    Text(
                        text = if (zoom < 10f) "%.1f×".format(zoom) else "%.0f×".format(zoom),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Slate400,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(38.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            onHaptic()
                            zoom = (zoom * 2f).coerceIn(1f, 12f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(width = 34.dp, height = 30.dp)
                    ) {
                        Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate100)
                    }
                }
                if (showResonance) Text(
                    text = "Green up: bullish  |  red down: bearish  |  white tick: net  |  sky line: base  |  violet: Asc/MC pulse  |  yellow: stochastic  |  ▲▼: net crosses zero  |  magenta: hard Pluto  |  auto scale  |  pinch or +/- to zoom",
                    fontSize = 10.sp,
                    color = Slate400,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (showResonance && currentBar != null) {
                    val stochNow = stoch.getOrNull(((currentBar.timeMs - series.first().timeMs) / 300_000L).toInt())
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    Text(
                        text = "Bull +%.2f   Bear −%.2f   Net %s".format(
                            currentBar.resBull,
                            currentBar.resBear,
                            (if (currentBar.resNet > 0) "+" else "") + "%.2f".format(currentBar.resNet)
                        ),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (currentBar.resNet > 0) Emerald400 else if (currentBar.resNet < 0) Rose400 else Slate400
                    )
                    if (stochNow != null) {
                        Text(
                            text = "Stoch %.0f".format(stochNow),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFACC15)
                        )
                    }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // v6.7: the window shared by every pane is worked out here, so zoom works with the main chart off
                if (series.isNotEmpty()) {
                    val viewMs = currentBar?.timeMs ?: inspectedTimeMs
                    updateChartView(chartView, series.size, zoom, ((viewMs - series.first().timeMs) / 300_000L).toInt().coerceIn(0, series.size - 1))
                }

                if (showResonance) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    // Back layer: faded bar chart
                    SentimentChart(
                        series = series,
                        inspectedTimeMs = currentBar?.timeMs ?: inspectedTimeMs,
                        stoch = stoch,
                        flags = if (showFlags) crossFlags else null,
                        flagged = td.flagged,
                        view = chartView,
                        zoom = zoom,
                        onZoomBy = { f -> zoom = (zoom * f).coerceIn(1f, 12f) },
                        onInspectTime = { t ->
                            onHaptic()
                            isLive = false
                            inspectedTimeMs = t
                        },
                        modifier = Modifier.matchParentSize()
                    )
                    // Front layer: opaque Resonance list
                    ResonanceLayer(
                        currentBar = currentBar,
                        showMc = showMc,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 260.dp)
                    )
                }
                }

                // v4.3: sub panes lined up under the chart (each one has a switch)
                val curIdx = currentBar
                    ?.let { ((it.timeMs - series.first().timeMs) / 300_000L).toInt().coerceIn(0, series.size - 1) }
                    ?: 0

                if (showShare && currentBar != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val share = currentBar.resShare
                    PaneHeader(
                        title = "BULL SHARE  (bull ÷ total, brighter = more resonance)",
                        value = "%.0f%%".format(share * 100.0),
                        valueColor = if (share > 0.55) Emerald400 else if (share < 0.45) Rose400 else Slate400
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                        BullSharePane(
                            series = series,
                            inspectedTimeMs = currentBar.timeMs,
                            view = chartView,
                            zoom = zoom
                        )
                    }
                }

                if (showDetrAsc) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val vAsc = detrAsc.getOrNull(curIdx)
                    PaneHeader(
                        title = "DETRENDED ASCENDANT  (aspect total − 1h average)",
                        value = vAsc?.let { (if (it > 0) "+" else "") + "%.2f".format(it) },
                        valueColor = if ((vAsc ?: 0.0) > 0) Emerald400 else if ((vAsc ?: 0.0) < 0) Rose400 else Slate400
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                        DetrendedPane(
                            series = series,
                            detr = detrAsc,
                            inspectedTimeMs = currentBar?.timeMs ?: inspectedTimeMs,
                            view = chartView,
                            zoom = zoom,
                            td = td,
                            skyTint = skyTintOn
                        )
                    }
                }

                if (showDetrMc) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val vMc = detrMc.getOrNull(curIdx)
                    PaneHeader(
                        title = "DETRENDED MIDHEAVEN–NATAL  (aspect total − 1h average)",
                        value = vMc?.let { (if (it > 0) "+" else "") + "%.2f".format(it) },
                        valueColor = if ((vMc ?: 0.0) > 0) Emerald400 else if ((vMc ?: 0.0) < 0) Rose400 else Slate400
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                        DetrendedPane(
                            series = series,
                            detr = detrMc,
                            inspectedTimeMs = currentBar?.timeMs ?: inspectedTimeMs,
                            view = chartView,
                            zoom = zoom,
                            td = td,
                            skyTint = skyTintOn
                        )
                    }
                }

                if (showDetr) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val detrNow = detr.getOrNull(curIdx)
                    PaneHeader(
                        title = "DETRENDED NET  (net − 1h average)",
                        value = detrNow?.let { (if (it > 0) "+" else "") + "%.2f".format(it) },
                        valueColor = if ((detrNow ?: 0.0) > 0) Emerald400 else if ((detrNow ?: 0.0) < 0) Rose400 else Slate400
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                        DetrendedPane(
                            series = series,
                            detr = detr,
                            inspectedTimeMs = currentBar?.timeMs ?: inspectedTimeMs,
                            view = chartView,
                            zoom = zoom,
                            td = td,
                            skyTint = skyTintOn
                        )
                    }
                }

                if (showVoc || showStations) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val vocNow = currentBar?.let { b -> td.voc.getOrNull(curIdx) } == true
                    val stnNow = td.stations.getOrNull(curIdx).orEmpty()
                    val vocEnd = td.vocSpans.firstOrNull { (currentBar?.timeMs ?: inspectedTimeMs) in it.startMs until it.endMs }?.endMs
                    PaneHeader(
                        title = "TURNING POINTS  (blue = Moon void of course, amber = station)",
                        value = buildString {
                            if (vocNow && vocEnd != null) append("VOC until " + hmSdf.format(Date(vocEnd)) + "  ")
                            append(stnNow.take(3).joinToString(" ") { it.glyph + if (it.turningRetro) "Rx" else "Dir" })
                        }.ifBlank { null },
                        valueColor = Amber500
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                        TurningStrip(
                            series = series,
                            td = td,
                            inspectedTimeMs = currentBar?.timeMs ?: inspectedTimeMs,
                            view = chartView,
                            zoom = zoom,
                            showVoc = showVoc,
                            showStations = showStations
                        )
                    }
                }

                if (showMacd) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val hNow = macd.hist.getOrNull(curIdx) ?: 0.0
                    val mNow = macd.macd.getOrNull(curIdx) ?: 0.0
                    val sNow = macd.signal.getOrNull(curIdx) ?: 0.0
                    PaneHeader(
                        title = "MACD OF NET  (cyan MACD, orange signal)",
                        value = "M %+.2f  S %+.2f  H %+.2f".format(mNow, sNow, hNow),
                        valueColor = if (hNow > 0) Emerald400 else if (hNow < 0) Rose400 else Slate400
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                        MacdPane(
                            series = series,
                            macd = macd,
                            flagged = td.flagged,
                            inspectedTimeMs = currentBar?.timeMs ?: inspectedTimeMs,
                            view = chartView,
                            zoom = zoom
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Time stepper: moves the white vertical line back / forward
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-60 to "−1h", -5 to "−5m", 5 to "+5m", 60 to "+1h").forEach { (mins, label) ->
                        OutlinedButton(
                            onClick = {
                                onHaptic()
                                isLive = false
                                val newT = inspectedTimeMs + mins * 60_000L
                                centerTimeMs = newT
                                inspectedTimeMs = newT
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                        ) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate100)
                        }
                    }
                }

                // Live / Resume Live (just below the time stepper)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            onHaptic()
                            isLive = !isLive
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLive) Emerald500 else CardDark
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isLive) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (isLive) Slate950 else Slate100,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isLive) "Live Active" else "Resume Live",
                            fontSize = 12.sp,
                            color = if (isLive) Slate950 else Slate100,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // v5.0: Moon mood, temperature of the day (below the chart card with its MACD pane and steppers)
        if (showMood && moodPoints.isNotEmpty()) {
            MoodCard(
                points = moodPoints,
                inspectedTimeMs = inspectedTimeMs,
                nowMs = nowMs,
                timeZoneId = selectedTimezone,
                onInspectTime = { t ->
                    isLive = false
                    if (series.isNotEmpty() && (t < series.first().timeMs || t > series.last().timeMs)) centerTimeMs = t
                    inspectedTimeMs = t
                }
            )
        }

        // v6.1: sky field for the week (transit-to-transit, transit-to-natal and total, with MACD of the total)
        if (showSky && skyWeek != null) {
            SkyFieldCard(
                week = skyWeek,
                inspectedTimeMs = inspectedTimeMs,
                nowMs = nowMs,
                timeZoneId = selectedTimezone,
                taintAllMm = taintAllMm,
                onInspectTime = { t ->
                    isLive = false
                    if (series.isNotEmpty() && (t < series.first().timeMs || t > series.last().timeMs)) centerTimeMs = t
                    inspectedTimeMs = t
                }
            )
        }

        if (showCards) {
        // v4.2: per-bar resonance breakdown (quiet panel directly below the chart)
        ResonanceBreakdownCard(currentBar = currentBar)

        // v3.2: Active Midheaven Aspects (below the chart)
        if (showMc) {
            ActiveMidheavenCard(currentBar = currentBar)
        }

        // v3.6: Active Ascendant Aspects now sits below the Midheaven box
        ActiveAscendantCard(currentBar = currentBar)

        // Exact Hits Card
        ExactHitsCard(
            hits = exactHits,
            timeZoneId = selectedTimezone,
            onJumpToHit = { hitMs ->
                onHaptic()
                isLive = false
                inspectedTimeMs = hitMs
                centerTimeMs = hitMs
            }
        )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "by A'RA",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Slate400
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** v4.0: small labelled switch used for the resonance model options. */
@Composable
private fun ResonanceSwitch(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Emerald500)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = Slate100)
    }
}

/** v4.3: title on the left, current value on the right, used above each sub pane. */
@Composable
private fun PaneHeader(title: String, value: String?, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
        }
    }
}
