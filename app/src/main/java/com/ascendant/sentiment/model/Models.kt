package com.ascendant.sentiment.model

data class PlanetDef(
    val name: String,
    val glyph: String,
    val weight: Int,
    val defaultPolarity: Int // 1 = positive, -1 = negative, 0 = contextual
)

// v3.4: hard aspects (same set as the engine's HARD_ANGLES) used for the Pluto highlight
private val PLUTO_HARD_ANGLES = setOf(45, 90, 135, 150, 180)

data class AspectInfo(
    val planetName: String,
    val glyph: String,
    val weight: Int,
    val aspectAngle: Int,
    val orbDiff: Double,
    val polarity: Int,
    val coeff: Double,
    val dynWeight: Double = weight.toDouble() // v3.0: dynamic weight (Wp x Sd + Pm)
) {
    val contribution: Double
        get() = polarity * dynWeight * coeff

    /** v3.4: a hard aspect (45, 90, 135, 150, 180) made by Pluto. */
    val isHardPluto: Boolean
        get() = planetName == "Pluto" && aspectAngle in PLUTO_HARD_ANGLES
}

/**
 * v4.0: one transit-to-natal resonance hit. A transiting planet aspects a fixed birth chart point.
 * value = Sp x Wp(dynamic, transiting planet) x Cj x orbFactor x boost  (positive = bullish, negative = bearish)
 */
data class ResonanceHit(
    val transitName: String,
    val transitGlyph: String,
    val natalName: String,
    val natalGlyph: String,
    val aspectAngle: Int,
    val orbDiff: Double,
    val polarity: Int,
    val coeff: Double,
    val dynWeight: Double,
    val orbFactor: Double,   // 1 - orb/limit when orb falloff is on, otherwise 1
    val pairMult: Double = 1.0,      // 1.5 when the planet aspects its own natal position (pair boost on)
    val triggerMult: Double = 1.0,   // 1 + 0.5 per active Ascendant / Midheaven trigger (trigger boost on)
    val ascTrigger: Boolean = false, // the transiting planet is on the Active Ascendant list
    val mcTrigger: Boolean = false   // the natal point is on the Active Midheaven list
) {
    /** Total boost = pair boost x trigger boost. */
    val boost: Double
        get() = pairMult * triggerMult

    val value: Double
        get() = polarity * dynWeight * coeff * orbFactor * boost

    /** v4.2: the same hit without the Asc / MC trigger boost (the slow base field). */
    val baseValue: Double
        get() = polarity * dynWeight * coeff * orbFactor * pairMult

    val isHardPluto: Boolean
        get() = transitName == "Pluto" && aspectAngle in PLUTO_HARD_ANGLES
}

data class SentimentBar(
    val timeMs: Long,
    val ascDegree: Double,
    val planetaryLongitudes: DoubleArray,
    val aspects: List<AspectInfo>,
    val sentimentScore: Double,
    // v3.2: Midheaven vs the fixed birth chart points
    val mcDegree: Double = 0.0,
    val mcAspects: List<AspectInfo> = emptyList(),
    val mcScore: Double = 0.0,
    // v4.0: transit vs natal resonance (bars): bullish sum, bearish sum (as a positive number),
    // and the part of the bearish sum that comes from hard Pluto aspects
    val resBull: Double = 0.0,
    val resBear: Double = 0.0,
    val resBearPluto: Double = 0.0,
    val resHits: List<ResonanceHit> = emptyList(),
    // v4.2: net of the base field (no trigger boost); resNet - resBase is the Asc / MC pulse
    val resBase: Double = 0.0
) {
    /** v4.3: bullish share of the total resonance, 0 to 1 (0.5 when the bar has no resonance at all). */
    val resShare: Double
        get() = if (resBull + resBear < 1e-9) 0.5 else resBull / (resBull + resBear)

    /** v4.2: the part of the net that comes from Asc / MC trigger boosts. */
    val resPulse: Double
        get() = resNet - resBase

    /** v4.0: net resonance = bullish minus bearish. */
    val resNet: Double
        get() = resBull - resBear

    /** v3.4: any hard Pluto aspect to the Ascendant on this bar. */
    val hasHardPluto: Boolean
        get() = aspects.any { it.isHardPluto }

    /** v3.4: any hard Pluto aspect between the Midheaven and natal Pluto on this bar. */
    val mcHasHardPluto: Boolean
        get() = mcAspects.any { it.isHardPluto }
}

data class HitEvent(
    val timeMs: Long,
    val planetName: String,
    val glyph: String,
    val weight: Int,
    val aspectAngle: Int,
    val polarity: Int,
    val coeff: Double
)

data class GeoLocation(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val market: String
)

/** v4.3: MACD of the net resonance: macd = fast EMA - slow EMA, signal = EMA of macd, hist = macd - signal. */
class MacdSeries(
    val macd: DoubleArray,
    val signal: DoubleArray,
    val hist: DoubleArray
)

/**
 * v5.0: one Moon aspect that feeds the mood.
 * cls: 1 = hot (Mars, Sun, Jupiter), -1 = cold (Saturn, Pluto), 2 = electric (Uranus, Mercury), 0 = neutral (Venus, Moon, Neptune).
 * strength = Wp x Cj x orbFactor of the aspecting planet.
 */
class MoodHit(
    val planetName: String,
    val glyph: String,
    val fromNatal: Boolean,   // true = Moon aspects a birth chart point, false = Moon aspects a transiting planet
    val aspectAngle: Int,
    val orbDiff: Double,
    val cls: Int,
    val strength: Double
)

/** v5.0: the Moon's mood at one moment: temperature -100 (freezing) to +100 (scorching), electric 0 to 100. */
class MoodPoint(
    val timeMs: Long,
    val temp: Double,
    val electric: Double,
    val hits: List<MoodHit>
) {
    /** No Moon aspect in orb at all. */
    val quiet: Boolean
        get() = hits.isEmpty()
}

/** v6.0: a stretch where the Moon is void of course (no more exact major aspects before it leaves its sign). */
class VocSpan(val startMs: Long, val endMs: Long)

/** v6.0: a planet close to standing still. strength 0..1 (1 = exactly stationary). */
class StationItem(
    val planetName: String,
    val glyph: String,
    val strength: Double,
    val turningRetro: Boolean   // true = about to turn retrograde, false = about to turn direct
)

/** v6.0: station proximity at one moment. index 0..100 (planet weights x closeness to standing still). */
class StationPoint(
    val timeMs: Long,
    val index: Double,
    val items: List<StationItem>
)

/** v6.0: one planet-to-planet aspect in the sky field. value > 0 bullish, < 0 bearish. */
class SkyHit(
    val actorName: String,
    val actorGlyph: String,
    val targetName: String,
    val targetGlyph: String,
    val aspectAngle: Int,
    val orbDiff: Double,
    val value: Double
)

/** v6.1: the turning-point layer, one entry per bar of the main series. */
class TurningData(
    val voc: BooleanArray,                   // Moon void of course on this bar
    val stationIndex: DoubleArray,           // 0..100, how close the heavy planets are to a station
    val stations: List<List<StationItem>>,   // planets in their station zone on this bar
    val sky: DoubleArray,                    // sky field value on this bar (planet to planet, Moon left out)
    val vocSpans: List<VocSpan>,
    val flagged: BooleanArray                // voc or station index at or above STATION_CONTEXT, for the context markers
)

/** v6.1: one 6-hour bar of the sky field week (each value is the average of six hourly readings). */
class SkyBar(
    val startMs: Long,
    val tt: Double,                          // transit-to-transit field, Moon left out
    val tn: Double,                          // transit-to-natal field, no Asc / MC trigger boost
    val vocShare: Double,                    // 0..1, share of the bar the Moon is void of course
    val stationIndex: Double,                // highest station index inside the bar
    val stations: List<StationItem>          // planets in their station zone at that peak
) {
    val total: Double
        get() = tt + tn
}

/** v6.1: the week view: 6-hour bars plus the MACD of the total (macd, signal, hist per bar). */
class SkyWeek(
    val bars: List<SkyBar>,
    val macd: DoubleArray,
    val signal: DoubleArray,
    val hist: DoubleArray,
    val vocSpans: List<VocSpan>,
    val barMs: Long
)

/** v6.3: one 6-hour bar of the Ascendant / Midheaven week (each value is the average of the readings inside the bar). */
class AxisBar(
    val startMs: Long,
    val asc: Double,     // Ascendant to transiting planets, Smarket index (-1..+1)
    val mc: Double       // Midheaven to natal planets, Smarket index (-1..+1)
)

class AxisWeek(val bars: List<AxisBar>, val barMs: Long)
