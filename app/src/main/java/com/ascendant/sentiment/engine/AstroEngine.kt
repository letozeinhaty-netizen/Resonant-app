package com.ascendant.sentiment.engine

import com.ascendant.sentiment.model.*
import kotlin.math.*

object AstroEngine {
    private const val R = Math.PI / 180.0
    private fun rev(x: Double): Double = ((x % 360.0) + 360.0) % 360.0
    private fun sn(x: Double): Double = sin(x * R)
    private fun cs(x: Double): Double = cos(x * R)

    // Wp: planet weights from the weights table.
    // 3rd value = Wp, 4th value = class: 1 green (Sp +1), 0 neutral (Sp +/-1), -1 red (Sp -1)
    val PLANETS = listOf(
        PlanetDef("Pluto", "♇", 10, -1),
        PlanetDef("Mercury", "☿", 9, 0),
        PlanetDef("Saturn", "♄", 8, -1),
        PlanetDef("Mars", "♂", 7, 0),
        PlanetDef("Venus", "♀", 6, 1),
        PlanetDef("Moon", "☽", 5, 0),
        PlanetDef("Jupiter", "♃", 4, 1),
        PlanetDef("Sun", "☉", 3, 0),
        PlanetDef("Uranus", "♅", 2, 0),
        PlanetDef("Neptune", "♆", 1, 1)
    )

    val ANGLES = listOf(0, 30, 45, 60, 72, 90, 120, 135, 144, 150, 180)

    // Hard aspects (neutral-planet taint rule and original per-angle polarity)
    val HARD_ANGLES = setOf(45, 90, 135, 150, 180)

    // v3.1: neutral planets whose taint flips ALL their Ascendant aspects (when the switch is on)
    private val TAINT_ALL_PLANETS = setOf("Mercury", "Mars")

    // v3.2: birth chart fixed points (tropical longitude = sign*30 + degrees + minutes/60; Aries = 0 ... Pisces = 11)
    private fun natalLon(sign: Int, deg: Int, min: Int): Double = sign * 30.0 + deg + min / 60.0

    val NATAL_LONGS: DoubleArray = DoubleArray(PLANETS.size) { i ->
        when (PLANETS[i].name) {
            "Mercury" -> natalLon(0, 2, 37)     // 2 Aries 37
            "Moon" -> natalLon(0, 5, 48)        // 5 Aries 48
            "Sun" -> natalLon(0, 18, 9)         // 18 Aries 09
            "Venus" -> natalLon(0, 20, 7)       // 20 Aries 07
            "Saturn" -> natalLon(3, 20, 39)     // 20 Cancer 39
            "Jupiter" -> natalLon(6, 13, 25)    // 13 Libra 25
            "Pluto" -> natalLon(8, 24, 26)      // 24 Sagittarius 26
            "Mars" -> natalLon(10, 13, 8)       // 13 Aquarius 08
            "Neptune" -> natalLon(10, 17, 7)    // 17 Aquarius 07
            "Uranus" -> natalLon(11, 9, 0)      // 9 Pisces 00
            else -> 0.0
        }
    }

    // Pm divisor in the dynamic weight, and the floor for the dynamic weight
    private const val PM_DIVISOR = 10.0
    private const val MIN_DYNAMIC_WEIGHT = 0.0

    // Original per-angle polarity rules for the neutral planets' aspects
    private val CONJ_POS = setOf("Moon", "Mercury", "Uranus")          // neutral planets that are +1 on conjunction
    private val POS_45 = setOf("Moon", "Mercury", "Sun", "Mars", "Uranus") // +1 on 45°

    // Cj: aspect values, one column per planet class (from the Cj table)
    val GREEN_CJ = mapOf(
        0 to 0.5, 30 to 0.5, 45 to 0.625, 60 to 0.75, 72 to 0.25, 90 to 0.375, 120 to 0.5, 135 to 0.25, 144 to 0.125, 150 to 0.25, 180 to 0.125
    )

    val NEUTRAL_CJ = mapOf(
        0 to 0.5, 30 to 0.5, 45 to 0.625, 60 to 0.75, 72 to 0.25, 90 to 0.875, 120 to 0.5, 135 to 0.375, 144 to 0.125, 150 to 0.25, 180 to 0.125
    )

    val RED_CJ = mapOf(
        0 to 0.5, 30 to 0.625, 45 to 0.75, 60 to 0.375, 72 to 0.25, 90 to 0.875, 120 to 0.25, 135 to 0.5, 144 to 0.125, 150 to 0.375, 180 to 0.25
    )

    // Sd: sign dignity values. Listed = fall/detriment or domicile/exaltation; everything else is neutral.
    private const val SD_FALL = 0.875
    private const val SD_NEUTRAL = 1.0
    private const val SD_DOM = 1.125

    private val SD_FALL_BY_SIGN: List<Set<String>> = listOf(
        setOf("Moon", "Saturn", "Venus"),  // Aries
        setOf("Mars", "Pluto", "Uranus"),  // Taurus
        setOf("Jupiter"),  // Gemini
        setOf("Mars", "Mercury", "Saturn"),  // Cancer
        setOf("Mercury", "Saturn", "Uranus"),  // Leo
        setOf("Jupiter", "Neptune"),  // Virgo
        setOf("Mars", "Pluto", "Sun"),  // Libra
        setOf("Moon", "Venus"),  // Scorpio
        setOf("Mercury", "Uranus"),  // Sagittarius
        setOf("Jupiter", "Moon", "Neptune", "Venus"),  // Capricorn
        setOf("Pluto", "Sun"),  // Aquarius
        setOf("Mercury")  // Pisces
    )

    private val SD_DOM_BY_SIGN: List<Set<String>> = listOf(
        setOf("Mars", "Pluto", "Sun"),  // Aries
        setOf("Moon", "Venus"),  // Taurus
        setOf("Mercury", "Saturn", "Uranus"),  // Gemini
        setOf("Jupiter", "Moon", "Neptune", "Venus"),  // Cancer
        setOf("Pluto", "Sun"),  // Leo
        setOf("Mercury"),  // Virgo
        setOf("Moon", "Saturn", "Venus"),  // Libra
        setOf("Mars", "Pluto", "Uranus"),  // Scorpio
        setOf("Jupiter"),  // Sagittarius
        setOf("Mars", "Mercury", "Saturn"),  // Capricorn
        setOf("Jupiter", "Mercury", "Saturn", "Uranus"),  // Aquarius
        setOf("Jupiter", "Moon", "Neptune", "Venus")  // Pisces
    )

    private val EL = mapOf(
        "Mercury" to doubleArrayOf(48.3313, 3.24587e-5, 7.0047, 5e-8, 29.1241, 1.01444e-5, 0.387098, 0.0, 0.205635, 5.59e-10, 168.6562, 4.0923344368),
        "Venus" to doubleArrayOf(76.6799, 2.4659e-5, 3.3946, 2.75e-8, 54.891, 1.38374e-5, 0.72333, 0.0, 0.006773, -1.302e-9, 48.0052, 1.6021302244),
        "Mars" to doubleArrayOf(49.5574, 2.11081e-5, 1.8497, -1.78e-8, 286.5016, 2.92961e-5, 1.523688, 0.0, 0.093405, 2.516e-9, 18.6021, 0.5240207766),
        "Jupiter" to doubleArrayOf(100.4542, 2.76854e-5, 1.303, -1.557e-7, 273.8777, 1.64505e-5, 5.20256, 0.0, 0.048498, 4.469e-9, 19.895, 0.0830853001),
        "Saturn" to doubleArrayOf(113.6634, 2.3898e-5, 2.4886, -1.081e-7, 339.3939, 2.97661e-5, 9.55475, 0.0, 0.055546, -9.499e-9, 316.967, 0.0334442282),
        "Uranus" to doubleArrayOf(74.0005, 1.3978e-5, 0.7733, 1.9e-8, 96.6612, 3.0565e-5, 19.18171, -1.55e-8, 0.047318, 7.45e-9, 142.5905, 0.011725806),
        "Neptune" to doubleArrayOf(131.7806, 3.0173e-5, 1.77, -2.55e-7, 272.8461, -6.027e-6, 30.05826, 3.313e-8, 0.008606, 2.15e-9, 260.2471, 0.005995147),
        "Moon" to doubleArrayOf(125.1228, -0.0529538083, 5.1454, 0.0, 318.0634, 0.1643573223, 60.2666, 0.0, 0.0549, 0.0, 115.3654, 13.0649929509)
    )

    private data class OrbitResult(
        val x: Double, val y: Double, val z: Double,
        val r: Double, val M: Double, val w: Double, val N: Double
    )

    private fun orb(name: String, d: Double): OrbitResult {
        val e = EL[name] ?: error("Missing elements for $name")
        val N = e[0] + e[1] * d
        val i = e[2] + e[3] * d
        val w = e[4] + e[5] * d
        val a = e[6] + e[7] * d
        val ec = e[8] + e[9] * d
        val M = rev(e[10] + e[11] * d)
        var E = M + ec / R * sn(M) * (1.0 + ec * cs(M))
        for (k in 0..3) {
            E -= (E - ec / R * sn(E) - M) / (1.0 - ec * cs(E))
        }
        val xv = a * (cs(E) - ec)
        val yv = a * sqrt(1.0 - ec * ec) * sn(E)
        val v = atan2(yv, xv) / R
        val r = hypot(xv, yv)
        val u = v + w
        return OrbitResult(
            x = r * (cs(N) * cs(u) - sn(N) * sn(u) * cs(i)),
            y = r * (sn(N) * cs(u) + cs(N) * sn(u) * cs(i)),
            z = r * sn(u) * sn(i),
            r = r,
            M = M,
            w = w,
            N = N
        )
    }

    private val cache = mutableMapOf<Long, DoubleArray>()

    fun calculatePlanetaryPositions(ms: Long): DoubleArray {
        cache[ms]?.let { return it }
        if (cache.size > 4000) cache.clear()

        val d = ms / 864e5 + 2440587.5 - 2451543.5
        val L = mutableMapOf<String, Double>()

        val sw = 282.9404 + 4.70935e-5 * d
        val se = 0.016709 - 1.151e-9 * d
        val sM = rev(356.047 + 0.9856002585 * d)
        val sE = sM + se / R * sn(sM) * (1.0 + se * cs(sM))
        val sx = cs(sE) - se
        val sy = sqrt(1.0 - se * se) * sn(sE)
        val sl = atan2(sy, sx) / R + sw
        val sr = hypot(sx, sy)
        val xs = sr * cs(sl)
        val ys = sr * sn(sl)
        L["Sun"] = rev(sl)

        val m = orb("Moon", d)
        val Lm = m.M + m.w + m.N
        val Ls = sM + sw
        val D = Lm - Ls
        val F = Lm - m.N
        val Mm = m.M
        L["Moon"] = rev(
            atan2(m.y, m.x) / R - 1.274 * sn(Mm - 2 * D) + 0.658 * sn(2 * D) -
                    0.186 * sn(sM) - 0.059 * sn(2 * Mm - 2 * D) - 0.057 * sn(Mm - 2 * D + sM) +
                    0.053 * sn(Mm + 2 * D) + 0.046 * sn(2 * D - sM) + 0.041 * sn(Mm - sM) -
                    0.035 * sn(D) - 0.031 * sn(Mm + sM) - 0.015 * sn(2 * F - 2 * D) + 0.011 * sn(Mm - 4 * D)
        )

        val O = mutableMapOf<String, OrbitResult>()
        listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune").forEach { n ->
            O[n] = orb(n, d)
        }

        val Mj = O["Jupiter"]!!.M
        val Ms = O["Saturn"]!!.M
        val Mu = O["Uranus"]!!.M

        val P = mapOf(
            "Jupiter" to -0.332 * sn(2 * Mj - 5 * Ms - 67.6) - 0.056 * sn(2 * Mj - 2 * Ms + 21) +
                    0.042 * sn(3 * Mj - 5 * Ms + 21) - 0.036 * sn(Mj - 2 * Ms) + 0.022 * cs(Mj - Ms) +
                    0.023 * sn(2 * Mj - 3 * Ms + 52) - 0.016 * sn(Mj - 5 * Ms - 69),
            "Saturn" to 0.812 * sn(2 * Mj - 5 * Ms - 67.6) - 0.229 * cs(2 * Mj - 4 * Ms - 2) +
                    0.119 * sn(Mj - 2 * Ms - 3) + 0.046 * sn(2 * Mj - 6 * Ms - 69) + 0.014 * sn(Mj - 3 * Ms + 32),
            "Uranus" to 0.04 * sn(Ms - 2 * Mu + 6) + 0.035 * sn(Ms - 3 * Mu + 33) - 0.015 * sn(Mj - Mu + 20)
        )

        for ((n, o) in O) {
            val lon = atan2(o.y, o.x) / R + (P[n] ?: 0.0)
            val lat = atan2(o.z, hypot(o.x, o.y)) / R
            L[n] = rev(atan2(o.r * cs(lat) * sn(lon) + ys, o.r * cs(lat) * cs(lon) + xs) / R)
        }

        val Sx = 50.03 + 0.033459652 * d
        val Pp = 238.95 + 0.003968789 * d
        val pl = 238.9508 + 0.00400703 * d - 19.799 * sn(Pp) + 19.848 * cs(Pp) + 0.897 * sn(2 * Pp) -
                4.956 * cs(2 * Pp) + 0.61 * sn(3 * Pp) + 1.211 * cs(3 * Pp) - 0.341 * sn(4 * Pp) -
                0.19 * cs(4 * Pp) + 0.128 * sn(5 * Pp) - 0.034 * cs(5 * Pp) - 0.038 * sn(6 * Pp) +
                0.031 * cs(6 * Pp) + 0.02 * sn(Sx - Pp) - 0.01 * cs(Sx - Pp)
        val pb = -5.452 * sn(Pp) - 14.974 * cs(Pp) + 3.527 * sn(2 * Pp) + 1.673 * cs(2 * Pp) -
                1.051 * sn(3 * Pp) + 0.328 * cs(3 * Pp) + 0.179 * sn(4 * Pp) - 0.292 * cs(4 * Pp) +
                0.019 * sn(5 * Pp) + 0.1 * cs(5 * Pp) - 0.031 * sn(6 * Pp) - 0.026 * cs(6 * Pp) + 0.011 * cs(Sx - Pp)
        val pr = 40.72 + 6.68 * sn(Pp) + 6.9 * cs(Pp) - 1.18 * sn(2 * Pp) - 0.03 * cs(2 * Pp) +
                0.15 * sn(3 * Pp) - 0.14 * cs(3 * Pp)
        L["Pluto"] = rev(atan2(pr * cs(pb) * sn(pl) + ys, pr * cs(pb) * cs(pl) + xs) / R)

        val result = DoubleArray(PLANETS.size) { i -> L[PLANETS[i].name] ?: 0.0 }
        cache[ms] = result
        return result
    }

    fun calculateAscendant(ms: Long, lat: Double, lon: Double): Double {
        val jd = ms / 864e5 + 2440587.5
        val dd = jd - 2451545.0
        val T = dd / 36525.0
        val th = rev(280.46061837 + 360.98564736629 * dd + 3.87933e-4 * T * T + lon)
        val eps = 23.4393 - 3.563e-7 * (jd - 2451543.5)
        return rev(atan2(cs(th), -(sn(th) * cs(eps) + tan(lat * R) * sn(eps))) / R)
    }

    /** v3.2: Midheaven (MC) ecliptic longitude, from the same sidereal time and obliquity as the Ascendant. */
    fun calculateMidheaven(ms: Long, lon: Double): Double {
        val jd = ms / 864e5 + 2440587.5
        val dd = jd - 2451545.0
        val T = dd / 36525.0
        val th = rev(280.46061837 + 360.98564736629 * dd + 3.87933e-4 * T * T + lon)
        val eps = 23.4393 - 3.563e-7 * (jd - 2451543.5)
        return rev(atan2(sn(th), cs(th) * cs(eps)) / R)
    }

    fun sep(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    /** Cj value for a planet's class (green / neutral / red column) at a given aspect angle. */
    fun cjFor(planet: PlanetDef, angle: Int): Double {
        val table = when (planet.defaultPolarity) {
            1 -> GREEN_CJ
            -1 -> RED_CJ
            else -> NEUTRAL_CJ
        }
        return table[angle] ?: 0.0
    }

    /** Sd: dignity multiplier of a planet at the given ecliptic longitude. */
    fun signDignity(name: String, lon: Double): Double {
        val sign = ((floor(lon / 30.0).toInt() % 12) + 12) % 12
        return when {
            name in SD_DOM_BY_SIGN[sign] -> SD_DOM
            name in SD_FALL_BY_SIGN[sign] -> SD_FALL
            else -> SD_NEUTRAL
        }
    }

    /**
     * A neutral planet is tainted if it is aspected by a Sp -1 planet (any aspect),
     * or by a hard aspect from another neutral planet. The taint only has an effect for
     * Mercury and Mars, and only while the Mercury & Mars taint switch is on.
     */
    private fun isTainted(i: Int, longs: DoubleArray, orbLimit: Double): Boolean {
        for (j in PLANETS.indices) {
            if (j == i) continue
            val angles: Collection<Int> = when (PLANETS[j].defaultPolarity) {
                -1 -> ANGLES
                0 -> HARD_ANGLES
                else -> emptyList()
            }
            val s = sep(longs[i], longs[j])
            if (angles.any { abs(s - it) <= orbLimit }) return true
        }
        return false
    }

    /** Tainted flag for every planet (only ever true for neutral planets). */
    fun taintedFlags(longs: DoubleArray, orbLimit: Double): BooleanArray =
        BooleanArray(PLANETS.size) { i -> PLANETS[i].defaultPolarity == 0 && isTainted(i, longs, orbLimit) }

    /**
     * Sp of a planet's aspect (to the Ascendant, the Midheaven or another planet).
     * Base polarity: green and red planets keep their class polarity. Neutral planets: soft +1,
     * hard -1, 45° stays +1, conjunction by the original rule (+1 Moon, Mercury, Uranus; -1 Mars, Sun).
     * With taintAllMm on, a tainted Mercury or Mars has base -1 on EVERY aspect.
     * Then the 72° and 144° aspects flip the base polarity, for every planet
     * (so a tainted Mercury or Mars flips back to +1 on 72° and 144°).
     */
    fun aspectPolarity(planet: PlanetDef, angle: Int, tainted: Boolean, taintAllMm: Boolean = false): Int {
        val base = when {
            planet.defaultPolarity != 0 -> planet.defaultPolarity
            tainted && taintAllMm && planet.name in TAINT_ALL_PLANETS -> -1
            angle == 0 -> if (planet.name in CONJ_POS) 1 else -1
            angle == 45 && planet.name in POS_45 -> 1
            angle in HARD_ANGLES -> -1
            else -> 1
        }
        return if (angle == 72 || angle == 144) -base else base
    }

    /**
     * Sum of Pm for every planet, where for each planet aspecting it:
     * Pm = Wp(aspecting) x Cj(aspect, column of aspecting planet) x Sp(aspecting, for that aspect).
     */
    fun planetModifiers(longs: DoubleArray, orbLimit: Double): DoubleArray {
        val pm = DoubleArray(PLANETS.size)
        for (i in PLANETS.indices) {
            for (j in PLANETS.indices) {
                if (i == j) continue
                val s = sep(longs[i], longs[j])
                val q = PLANETS[j]
                for (A in ANGLES) {
                    if (abs(s - A) <= orbLimit) {
                        pm[i] += q.weight * cjFor(q, A) * aspectPolarity(q, A, false)
                    }
                }
            }
        }
        return pm
    }

    /**
     * Dynamic weight = Wp(target) x Sd + (Sum of Pm / 10), with the Sd and Pm parts switchable.
     * Floored at 0 so a negative weight can never flip a planet's contribution.
     */
    fun dynamicWeights(
        longs: DoubleArray,
        orbLimit: Double,
        useSd: Boolean,
        usePm: Boolean
    ): DoubleArray {
        val pm = if (usePm) planetModifiers(longs, orbLimit) else null
        return DoubleArray(PLANETS.size) { i ->
            val p = PLANETS[i]
            val sd = if (useSd) signDignity(p.name, longs[i]) else 1.0
            max(MIN_DYNAMIC_WEIGHT, p.weight * sd + (pm?.get(i) ?: 0.0) / PM_DIVISOR)
        }
    }

    fun calculateAspects(
        asDeg: Double,
        planetLongs: DoubleArray,
        orbLimit: Double = 3.0,
        useSd: Boolean = true,
        usePm: Boolean = true,
        taintAllMm: Boolean = true
    ): List<AspectInfo> {
        val list = mutableListOf<AspectInfo>()
        val tainted = taintedFlags(planetLongs, orbLimit)
        val dw = dynamicWeights(planetLongs, orbLimit, useSd, usePm)
        PLANETS.forEachIndexed { k, planet ->
            val sp = sep(asDeg, planetLongs[k])
            ANGLES.forEach { A ->
                val d = abs(sp - A)
                if (d <= orbLimit) {
                    list.add(
                        AspectInfo(
                            planetName = planet.name,
                            glyph = planet.glyph,
                            weight = planet.weight,
                            aspectAngle = A,
                            orbDiff = d,
                            polarity = aspectPolarity(planet, A, tainted[k], taintAllMm),
                            coeff = cjFor(planet, A),
                            dynWeight = dw[k]
                        )
                    )
                }
            }
        }
        return list
    }

    /** v3.2: a neutral natal point is tainted by the TRANSITING planets (same rule as before). */
    fun natalTaintedFlags(transitLongs: DoubleArray, orbLimit: Double): BooleanArray =
        BooleanArray(PLANETS.size) { i ->
            PLANETS[i].defaultPolarity == 0 && PLANETS.indices.any { j ->
                val angles: Collection<Int> = when (PLANETS[j].defaultPolarity) {
                    -1 -> ANGLES
                    0 -> HARD_ANGLES
                    else -> emptyList()
                }
                val s = sep(NATAL_LONGS[i], transitLongs[j])
                angles.any { abs(s - it) <= orbLimit }
            }
        }

    /** v3.2: sum of Pm for each natal point, from the transiting planets aspecting it. */
    fun natalModifiers(transitLongs: DoubleArray, orbLimit: Double): DoubleArray {
        val pm = DoubleArray(PLANETS.size)
        for (i in PLANETS.indices) {
            for (j in PLANETS.indices) {
                val s = sep(NATAL_LONGS[i], transitLongs[j])
                val q = PLANETS[j]
                for (A in ANGLES) {
                    if (abs(s - A) <= orbLimit) {
                        pm[i] += q.weight * cjFor(q, A) * aspectPolarity(q, A, false)
                    }
                }
            }
        }
        return pm
    }

    /** v3.2: dynamic weight of each natal point = Wp x Sd(natal sign) + (sum of Pm / 10). */
    fun natalDynamicWeights(
        transitLongs: DoubleArray,
        orbLimit: Double,
        useSd: Boolean,
        usePm: Boolean
    ): DoubleArray {
        val pm = if (usePm) natalModifiers(transitLongs, orbLimit) else null
        return DoubleArray(PLANETS.size) { i ->
            val p = PLANETS[i]
            val sd = if (useSd) signDignity(p.name, NATAL_LONGS[i]) else 1.0
            max(MIN_DYNAMIC_WEIGHT, p.weight * sd + (pm?.get(i) ?: 0.0) / PM_DIVISOR)
        }
    }

    /** v3.2: aspects from the Midheaven to the fixed birth chart points, same formulas as the Ascendant. */
    fun calculateMidheavenAspects(
        mcDeg: Double,
        transitLongs: DoubleArray,
        orbLimit: Double = 3.0,
        useSd: Boolean = true,
        usePm: Boolean = true,
        taintAllMm: Boolean = true
    ): List<AspectInfo> {
        val list = mutableListOf<AspectInfo>()
        val tainted = natalTaintedFlags(transitLongs, orbLimit)
        val dw = natalDynamicWeights(transitLongs, orbLimit, useSd, usePm)
        PLANETS.forEachIndexed { k, planet ->
            val sp = sep(mcDeg, NATAL_LONGS[k])
            ANGLES.forEach { A ->
                val d = abs(sp - A)
                if (d <= orbLimit) {
                    list.add(
                        AspectInfo(
                            planetName = planet.name,
                            glyph = planet.glyph,
                            weight = planet.weight,
                            aspectAngle = A,
                            orbDiff = d,
                            polarity = aspectPolarity(planet, A, tainted[k], taintAllMm),
                            coeff = cjFor(planet, A),
                            dynWeight = dw[k]
                        )
                    )
                }
            }
        }
        return list
    }

    // v4.0: resonance boosts
    private const val PAIR_BOOST = 1.5      // transiting planet aspects its own natal position
    private const val TRIGGER_BOOST = 0.5   // added per trigger: transiting planet active on the Ascendant, natal point active on the Midheaven

    /**
     * v4.0: transit vs natal resonance. Every transiting planet j is checked against every birth chart point i.
     * Each aspect within orb is a hit: Sp x Wp(dynamic of j) x Cj(j's column) x orbFactor x boost.
     *  - orbFactor (switch): 1 - orb/limit, so a hit builds toward exact and fades out instead of popping in at the orb edge.
     *  - pair boost (switch): x1.5 when i == j (transit Venus on natal Venus).
     *  - trigger boost (switch): +0.5 if j is on the Ascendant list and +0.5 if i is on the Midheaven list (max x2),
     *    so the slow transit-natal field is pulsed by the fast Ascendant / Midheaven.
     */
    fun calculateResonance(
        transitLongs: DoubleArray,
        ascAspects: List<AspectInfo>,
        mcAspects: List<AspectInfo>,
        orbLimit: Double = 3.0,
        useSd: Boolean = true,
        usePm: Boolean = true,
        taintAllMm: Boolean = true,
        orbFalloff: Boolean = true,
        pairBoost: Boolean = true,
        trigger: Boolean = true
    ): List<ResonanceHit> {
        val hits = mutableListOf<ResonanceHit>()
        val tainted = taintedFlags(transitLongs, orbLimit)
        val dw = dynamicWeights(transitLongs, orbLimit, useSd, usePm)
        val ascPlanets = ascAspects.map { it.planetName }.toSet()
        val mcPoints = mcAspects.map { it.planetName }.toSet()
        PLANETS.forEachIndexed { j, tp ->
            PLANETS.forEachIndexed { i, np ->
                val s = sep(NATAL_LONGS[i], transitLongs[j])
                ANGLES.forEach { A ->
                    val d = abs(s - A)
                    if (d <= orbLimit) {
                        val falloff = if (orbFalloff) 1.0 - d / orbLimit else 1.0
                        val pairMult = if (pairBoost && i == j) PAIR_BOOST else 1.0
                        val ascTrig = trigger && tp.name in ascPlanets
                        val mcTrig = trigger && np.name in mcPoints
                        val triggerMult = 1.0 + TRIGGER_BOOST * ((if (ascTrig) 1 else 0) + (if (mcTrig) 1 else 0))
                        hits.add(
                            ResonanceHit(
                                transitName = tp.name,
                                transitGlyph = tp.glyph,
                                natalName = np.name,
                                natalGlyph = np.glyph,
                                aspectAngle = A,
                                orbDiff = d,
                                polarity = aspectPolarity(tp, A, tainted[j], taintAllMm),
                                coeff = cjFor(tp, A),
                                dynWeight = dw[j],
                                orbFactor = falloff,
                                pairMult = pairMult,
                                triggerMult = triggerMult,
                                ascTrigger = ascTrig,
                                mcTrigger = mcTrig
                            )
                        )
                    }
                }
            }
        }
        return hits
    }

    // v4.1: slow stochastic settings (classic 14 / 3)
    private const val STOCH_LOOKBACK = 14
    private const val STOCH_SMOOTH = 3

    /**
     * v4.1: slow stochastic oscillator of the net resonance (0 to 100, 50 = middle of the recent range).
     * Raw %K = position of the net inside its last 14-bar high/low range; slow %K = 3-bar average of raw %K.
     * The range is never allowed to shrink below 10% of the biggest bar in the series, so tiny wobbles on a
     * flat stretch stay near 50 instead of being stretched to the full 0 to 100.
     */
    fun stochasticOfNet(
        series: List<SentimentBar>,
        lookback: Int = STOCH_LOOKBACK,
        smooth: Int = STOCH_SMOOTH
    ): DoubleArray {
        val n = series.size
        if (n == 0) return DoubleArray(0)
        val minRange = 0.1 * max(1.0, series.maxOf { max(it.resBull, it.resBear) })
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            var hi = -Double.MAX_VALUE
            var lo = Double.MAX_VALUE
            for (k in max(0, i - lookback + 1)..i) {
                val v = series[k].resNet
                if (v > hi) hi = v
                if (v < lo) lo = v
            }
            val rng = max(hi - lo, minRange)
            raw[i] = 50.0 + 100.0 * (series[i].resNet - (hi + lo) / 2.0) / rng
        }
        return DoubleArray(n) { i ->
            val from = max(0, i - smooth + 1)
            var sum = 0.0
            for (k in from..i) sum += raw[k]
            sum / (i - from + 1)
        }
    }

    // v4.2: detrending window, 12 bars of 5 minutes = 1 hour
    private const val DETREND_BARS = 12

    /**
     * v4.2: detrended net = net minus its trailing 1-hour average (the bar itself included).
     * The slow plateaus cancel out and what is left oscillates around zero.
     * The first 11 bars of the window use however many bars exist so far.
     */
    fun detrendedNet(series: List<SentimentBar>, window: Int = DETREND_BARS): DoubleArray {
        val n = series.size
        return DoubleArray(n) { i ->
            val from = max(0, i - window + 1)
            var sum = 0.0
            for (k in from..i) sum += series[k].resNet
            series[i].resNet - sum / (i - from + 1)
        }
    }

    // v4.3: MACD settings in 5-minute bars (classic 12 / 26 / 9 = 1h / 2h10m / 45m)
    private const val MACD_FAST = 12
    private const val MACD_SLOW = 26
    private const val MACD_SIGNAL = 9

    private fun ema(x: DoubleArray, n: Int): DoubleArray {
        val a = 2.0 / (n + 1)
        val out = DoubleArray(x.size)
        for (i in x.indices) out[i] = if (i == 0) x[0] else a * x[i] + (1.0 - a) * out[i - 1]
        return out
    }

    /** v4.3: MACD of the net. The EMAs start from the first bar, so the first ~26 bars are still settling. */
    fun macdOfNet(series: List<SentimentBar>): MacdSeries {
        val net = DoubleArray(series.size) { series[it].resNet }
        val fast = ema(net, MACD_FAST)
        val slow = ema(net, MACD_SLOW)
        val macd = DoubleArray(net.size) { fast[it] - slow[it] }
        val signal = ema(macd, MACD_SIGNAL)
        return MacdSeries(macd, signal, DoubleArray(net.size) { macd[it] - signal[it] })
    }

    /**
     * v4.3: zero-cross flags. +1 on the bar where the net turns bullish, -1 where it turns bearish, 0 otherwise.
     * A flip only counts once the net passes a dead band (5% of the biggest bar in the series) on the other side,
     * so small wobbles around zero do not spray flags.
     */
    fun zeroCrossFlags(series: List<SentimentBar>): IntArray {
        val n = series.size
        val flags = IntArray(n)
        if (n == 0) return flags
        val band = 0.05 * max(1.0, series.maxOf { max(it.resBull, it.resBear) })
        var state = 0
        for (i in 0 until n) {
            val v = series[i].resNet
            val s = if (v > band) 1 else if (v < -band) -1 else 0
            if (s != 0) {
                if (state != 0 && s != state) flags[i] = s
                state = s
            }
        }
        return flags
    }

    // ---------------- v5.0: Moon mood (temperature of the day) ----------------
    private val MOOD_HOT = setOf("Mars", "Sun", "Jupiter")
    private val MOOD_COLD = setOf("Saturn", "Pluto")
    private val MOOD_ELECTRIC = setOf("Uranus", "Mercury")

    // Scales for the smooth squash onto -100..+100 and 0..100
    // Calibrated on 90 days of 15-minute readings: |temp| >= 60 about 9% of the time, >= 25 about 54%
    private const val MOOD_TEMP_SCALE = 8.0
    private const val MOOD_NEUTRAL_DAMP = 10.0    // neutral aspects (Venus, Moon, Neptune) dilute the temperature
    private const val MOOD_ELECTRIC_SCALE = 6.0

    // Thresholds the display uses
    const val MOOD_EXTREME = 60.0      // scorching / freezing
    const val MOOD_WARM = 25.0         // hot / cold
    const val MOOD_ELECTRIC_ON = 50.0  // adds "ELECTRIC" to the label

    fun moodClass(name: String): Int = when (name) {
        in MOOD_HOT -> 1
        in MOOD_COLD -> -1
        in MOOD_ELECTRIC -> 2
        else -> 0
    }

    /**
     * Mood of the transiting Moon at one moment. The Moon is checked against the transiting planets and against the
     * birth chart points (including the natal Moon), same angles and 3 degree orb as the rest of the app.
     * Each aspect: strength = Wp x Cj x (1 - orb/limit), then it goes to its planet's class.
     * temp = 100 x tanh((hot - cold) / (8 x (1 + neutral/10)))   electric = 100 x tanh(electric / 6)
     */
    fun calculateMood(ms: Long, orbLimit: Double = 3.0): MoodPoint {
        val longs = calculatePlanetaryPositions(ms)
        val moonIdx = PLANETS.indexOfFirst { it.name == "Moon" }
        val moon = longs[moonIdx]
        val hits = mutableListOf<MoodHit>()
        var heat = 0.0
        var cold = 0.0
        var elec = 0.0
        var neut = 0.0

        fun addAspects(i: Int, fromNatal: Boolean, point: Double) {
            val p = PLANETS[i]
            val s = sep(moon, point)
            for (A in ANGLES) {
                val d = abs(s - A)
                if (d <= orbLimit) {
                    val strength = p.weight * cjFor(p, A) * (1.0 - d / orbLimit)
                    if (strength < 1e-9) continue
                    val cls = moodClass(p.name)
                    when (cls) {
                        1 -> heat += strength
                        -1 -> cold += strength
                        2 -> elec += strength
                        else -> neut += strength
                    }
                    hits.add(MoodHit(p.name, p.glyph, fromNatal, A, d, cls, strength))
                }
            }
        }

        for (i in PLANETS.indices) {
            if (i != moonIdx) addAspects(i, false, longs[i])   // transiting planets (not the Moon itself)
            addAspects(i, true, NATAL_LONGS[i])                // birth chart points, natal Moon included
        }

        val dilution = 1.0 + neut / MOOD_NEUTRAL_DAMP
        val temp = 100.0 * tanh((heat - cold) / (MOOD_TEMP_SCALE * dilution))
        val electric = 100.0 * tanh(elec / MOOD_ELECTRIC_SCALE)
        return MoodPoint(ms, temp, electric, hits.sortedByDescending { it.strength })
    }

    /** Mood every 5 minutes from startMs (inclusive) to endMs (exclusive). */
    fun calculateMoodSeries(startMs: Long, endMs: Long, stepMs: Long = 300_000L, orbLimit: Double = 3.0): List<MoodPoint> {
        val out = ArrayList<MoodPoint>()
        var t = startMs
        while (t < endMs) {
            out.add(calculateMood(t, orbLimit))
            t += stepMs
        }
        return out
    }

    /** Start (inclusive) and end (exclusive) of the local calendar day that contains ms, in the given time zone. */
    fun dayBoundsMs(ms: Long, timeZoneId: String): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(timeZoneId))
        cal.timeInMillis = ms
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return start to cal.timeInMillis
    }

    /** Word for a mood: SCORCHING / HOT / NEUTRAL / COLD / FREEZING, plus ELECTRIC, or QUIET when the Moon has no aspect. */
    fun moodLabel(temp: Double, electric: Double, quiet: Boolean = false): String {
        if (quiet) return "QUIET"
        val base = when {
            temp >= MOOD_EXTREME -> "SCORCHING"
            temp >= MOOD_WARM -> "HOT"
            temp <= -MOOD_EXTREME -> "FREEZING"
            temp <= -MOOD_WARM -> "COLD"
            else -> "NEUTRAL"
        }
        return if (electric >= MOOD_ELECTRIC_ON) "$base · ELECTRIC" else base
    }

    // ---------------- v6.0: turning points (stations, void-of-course Moon) and the sky field ----------------

    private fun signedDiff(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0

    // Typical maximum daily speed (degrees per day) of the planets that can station
    private val STATION_VMAX = mapOf(
        "Mercury" to 2.2, "Venus" to 1.25, "Mars" to 0.78, "Jupiter" to 0.24,
        "Saturn" to 0.13, "Uranus" to 0.06, "Neptune" to 0.04, "Pluto" to 0.035
    )
    private const val STATION_ZONE = 0.10      // stationary zone: |speed| below 10% of the typical maximum speed
    private const val STATION_REF = 10.0       // index 100 = a fully stationary planet of weight 10
    const val STATION_CONTEXT = 25.0           // index at or above this counts as "near a station" for the context markers

    /**
     * Station proximity at one moment. Speeds come from the app's own positions: two 6-hour-baseline speeds,
     * centred 12 hours before and 12 hours after ms. Their average is the speed now; their difference tells
     * whether the planet is slowing toward a retrograde turn or speeding up away from one.
     */
    fun calculateStation(ms: Long): StationPoint {
        val h = 3_600_000L
        val bm = calculatePlanetaryPositions(ms - 15 * h)
        val bp = calculatePlanetaryPositions(ms - 9 * h)
        val am = calculatePlanetaryPositions(ms + 9 * h)
        val ap = calculatePlanetaryPositions(ms + 15 * h)
        val items = mutableListOf<StationItem>()
        var raw = 0.0
        PLANETS.forEachIndexed { i, p ->
            val vmax = STATION_VMAX[p.name] ?: return@forEachIndexed
            val vBefore = signedDiff(bp[i], bm[i]) / 0.25
            val vAfter = signedDiff(ap[i], am[i]) / 0.25
            val v = 0.5 * (vBefore + vAfter)
            val s = 1.0 - abs(v) / (STATION_ZONE * vmax)
            if (s > 0.0) {
                raw += p.weight * s
                items.add(StationItem(p.name, p.glyph, s, (vAfter - vBefore) < 0))
            }
        }
        return StationPoint(ms, min(100.0, 100.0 * raw / STATION_REF), items.sortedByDescending { it.strength })
    }

    // Classic void of course: exact conjunction, sextile, square, trine, opposition (both sides) to the other planets
    private val VOC_ANGLES = intArrayOf(0, 60, 90, 120, 180, 240, 270, 300)

    /**
     * Void-of-course Moon spans overlapping [fromMs, toMs]. Classic definition: from the Moon's last exact major
     * aspect to another planet until it leaves its sign (a stay with no such aspect is void from the ingress).
     * Scans in 15-minute steps with linear interpolation, padded 3 days each side so every stay is complete.
     */
    fun vocSpans(fromMs: Long, toMs: Long): List<VocSpan> {
        val step = 900_000L
        val pad = 3 * 86_400_000L
        val t0 = ((fromMs - pad) / step) * step
        val t1 = toMs + pad
        val n = ((t1 - t0) / step).toInt() + 1
        val moonIdx = PLANETS.indexOfFirst { it.name == "Moon" }
        val ingress = ArrayList<Long>()
        val events = ArrayList<Long>()
        val prevX = Array(PLANETS.size) { DoubleArray(VOC_ANGLES.size) }
        var prevMoon = 0.0
        for (k in 0 until n) {
            val t = t0 + k * step
            val longs = calculatePlanetaryPositions(t)
            val moon = longs[moonIdx]
            for (j in PLANETS.indices) {
                if (j == moonIdx) continue
                for (a in VOC_ANGLES.indices) {
                    val x = signedDiff(moon, longs[j] + VOC_ANGLES[a])
                    if (k > 0) {
                        val px = prevX[j][a]
                        if (px < 0.0 && x >= 0.0 && (x - px) < 90.0) {
                            events.add(t - step + (step * (-px / (x - px))).toLong())
                        }
                    }
                    prevX[j][a] = x
                }
            }
            if (k > 0) {
                val signNow = floor(moon / 30.0).toInt()
                val signPrev = floor(prevMoon / 30.0).toInt()
                if (signNow != signPrev) {
                    val boundary = signNow * 30.0
                    val total = signedDiff(moon, prevMoon)
                    val frac = if (total > 1e-9) (signedDiff(boundary, prevMoon) / total).coerceIn(0.0, 1.0) else 1.0
                    ingress.add(t - step + (step * frac).toLong())
                }
            }
            prevMoon = moon
        }
        events.sort()
        val spans = mutableListOf<VocSpan>()
        // every stay that starts at an ingress and ends at the next one is complete
        for (q in 0 until ingress.size - 1) {
            val a = ingress[q]
            val b = ingress[q + 1]
            val last = events.lastOrNull { it >= a && it < b } ?: a
            if (last < b && b > fromMs && last < toMs) spans.add(VocSpan(last, b))
        }
        return spans
    }

    /** Name of the sign the Moon is in. */
    fun moonSignName(ms: Long): String {
        val moonIdx = PLANETS.indexOfFirst { it.name == "Moon" }
        val lon = calculatePlanetaryPositions(ms)[moonIdx]
        return ZODIAC[((floor(lon / 30.0).toInt() % 12) + 12) % 12]
    }

    // Sky field: planet-to-planet aspects, Moon left out (the Moon is the mood card's job)
    const val SKY_SCALE = 6.0   // |field| of this size reads as a clearly tilted backdrop (see skyTint)

    /**
     * Sky field at one moment: sum over every aspecting planet j (not the Moon) on every other planet i (not the Moon)
     * of Sp(j) x Wp(j) x Cj(j) x orbFactor. Same polarity rules as the rest of the app (taint included).
     */
    fun skyFieldValue(longs: DoubleArray, orbLimit: Double = 3.0, taintAllMm: Boolean = true): Double {
        val moonIdx = PLANETS.indexOfFirst { it.name == "Moon" }
        val tainted = taintedFlags(longs, orbLimit)
        var sum = 0.0
        for (j in PLANETS.indices) {
            if (j == moonIdx) continue
            for (i in PLANETS.indices) {
                if (i == j || i == moonIdx) continue
                val s = sep(longs[i], longs[j])
                for (A in ANGLES) {
                    val d = abs(s - A)
                    if (d <= orbLimit) {
                        sum += aspectPolarity(PLANETS[j], A, tainted[j], taintAllMm) *
                            PLANETS[j].weight * cjFor(PLANETS[j], A) * (1.0 - d / orbLimit)
                    }
                }
            }
        }
        return sum
    }

    fun skyFieldSeries(timesMs: LongArray, taintAllMm: Boolean = true, orbLimit: Double = 3.0): DoubleArray =
        DoubleArray(timesMs.size) { skyFieldValue(calculatePlanetaryPositions(timesMs[it]), orbLimit, taintAllMm) }

    /** Tint strength -1..+1 from a sky field value (smooth, fixed scale). */
    fun skyTint(value: Double): Double = tanh(value / SKY_SCALE)

    /** The aspects behind the sky field at one moment, biggest first. */
    fun skyHits(ms: Long, taintAllMm: Boolean = true, orbLimit: Double = 3.0): List<SkyHit> {
        val longs = calculatePlanetaryPositions(ms)
        val moonIdx = PLANETS.indexOfFirst { it.name == "Moon" }
        val tainted = taintedFlags(longs, orbLimit)
        val out = mutableListOf<SkyHit>()
        for (j in PLANETS.indices) {
            if (j == moonIdx) continue
            for (i in PLANETS.indices) {
                if (i == j || i == moonIdx) continue
                val s = sep(longs[i], longs[j])
                for (A in ANGLES) {
                    val d = abs(s - A)
                    if (d <= orbLimit) {
                        val v = aspectPolarity(PLANETS[j], A, tainted[j], taintAllMm) *
                            PLANETS[j].weight * cjFor(PLANETS[j], A) * (1.0 - d / orbLimit)
                        if (abs(v) > 1e-9) {
                            out.add(SkyHit(PLANETS[j].name, PLANETS[j].glyph, PLANETS[i].name, PLANETS[i].glyph, A, d, v))
                        }
                    }
                }
            }
        }
        return out.sortedByDescending { abs(it.value) }
    }

    // ---------------- v6.1: per-bar turning data and the week view of the sky field ----------------

    // Void-of-course spans are expensive to scan (15-minute steps), so the last scan is kept and reused
    // while the requested range stays inside it.
    private var vocCacheFrom = 0L
    private var vocCacheTo = -1L
    private var vocCache: List<VocSpan> = emptyList()

    fun vocSpansCached(fromMs: Long, toMs: Long): List<VocSpan> {
        val day = 86_400_000L
        if (fromMs < vocCacheFrom || toMs > vocCacheTo) {
            vocCacheFrom = fromMs - 2 * day
            vocCacheTo = toMs + 6 * day
            vocCache = vocSpans(vocCacheFrom, vocCacheTo)
        }
        return vocCache.filter { it.endMs > fromMs && it.startMs < toMs }
    }

    /**
     * Turning-point layer for the main series, one entry per bar: void-of-course flag, station index and planets,
     * and the sky field value. Stations change slowly, so they are worked out every 30 minutes and shared.
     */
    fun turningData(series: List<SentimentBar>, orbLimit: Double = 3.0, taintAllMm: Boolean = true): TurningData {
        val n = series.size
        if (n == 0) {
            return TurningData(BooleanArray(0), DoubleArray(0), emptyList(), DoubleArray(0), emptyList(), BooleanArray(0))
        }
        val spans = vocSpansCached(series.first().timeMs, series.last().timeMs + 300_000L)
        val voc = BooleanArray(n) { i -> spans.any { series[i].timeMs >= it.startMs && series[i].timeMs < it.endMs } }
        val bucket = HashMap<Long, StationPoint>()
        val stations = ArrayList<List<StationItem>>(n)
        val idx = DoubleArray(n)
        for (i in 0 until n) {
            val key = (series[i].timeMs / 1_800_000L) * 1_800_000L
            val sp = bucket.getOrPut(key) { calculateStation(key) }
            stations.add(sp.items)
            idx[i] = sp.index
        }
        val sky = DoubleArray(n) { skyFieldValue(series[it].planetaryLongitudes, orbLimit, taintAllMm) }
        val flagged = BooleanArray(n) { voc[it] || idx[it] >= STATION_CONTEXT }
        return TurningData(voc, idx, stations, sky, spans, flagged)
    }

    // MACD of the week total, in 6-hour bars: 4 / 9 / 3 bars = 24h / 54h / 18h (the classic 12 / 26 / 9 proportions)
    private const val SKY_MACD_FAST = 4
    private const val SKY_MACD_SLOW = 9
    private const val SKY_MACD_SIGNAL = 3

    /**
     * v6.1: the week view. 6-hour bars, each the average of six hourly readings, of
     *  - tt = transit-to-transit field (the sky itself, Moon left out),
     *  - tn = transit-to-natal field (every transiting planet on every birth chart point, no Asc / MC trigger boost),
     *  - total = tt + tn,
     * plus how much of the bar the Moon is void of course, the highest station index in the bar, and the MACD of the
     * total. The MACD is warmed up on bars before startMs, which are not part of the result.
     */
    fun calculateSkyWeek(
        startMs: Long,
        bars: Int = 28,
        barMs: Long = 6 * 3_600_000L,
        warmBars: Int = 12,
        orbLimit: Double = 3.0,
        useSd: Boolean = true,
        usePm: Boolean = true,
        taintAllMm: Boolean = true,
        orbFalloff: Boolean = true,
        pairBoost: Boolean = true,
        moonInNatal: Boolean = true   // false = leave the transiting Moon out of the transit-to-natal line
    ): SkyWeek {
        val hour = 3_600_000L
        val perBar = max(1, (barMs / hour).toInt())
        val all = warmBars + bars
        val first = startMs - warmBars * barMs
        val tt = DoubleArray(all)
        val tn = DoubleArray(all)
        for (k in 0 until all) {
            var a = 0.0
            var b = 0.0
            for (h in 0 until perBar) {
                val t = first + k * barMs + h * hour + hour / 2
                val longs = calculatePlanetaryPositions(t)
                a += skyFieldValue(longs, orbLimit, taintAllMm)
                b += calculateResonance(
                    longs, emptyList(), emptyList(), orbLimit, useSd, usePm, taintAllMm, orbFalloff, pairBoost, false
                ).filter { moonInNatal || it.transitName != "Moon" }.sumOf { it.value }
            }
            tt[k] = a / perBar
            tn[k] = b / perBar
        }
        val total = DoubleArray(all) { tt[it] + tn[it] }
        val macdAll = DoubleArray(all).also { out ->
            val f = ema(total, SKY_MACD_FAST)
            val s = ema(total, SKY_MACD_SLOW)
            for (i in 0 until all) out[i] = f[i] - s[i]
        }
        val signalAll = ema(macdAll, SKY_MACD_SIGNAL)

        val endMs = startMs + bars * barMs
        val spans = vocSpansCached(startMs, endMs)
        val out = ArrayList<SkyBar>(bars)
        for (i in 0 until bars) {
            val k = warmBars + i
            val bStart = startMs + i * barMs
            val bEnd = bStart + barMs
            var vocMs = 0L
            for (sp in spans) {
                val o = min(bEnd, sp.endMs) - max(bStart, sp.startMs)
                if (o > 0) vocMs += o
            }
            var bestIdx = -1.0
            var bestItems: List<StationItem> = emptyList()
            for (h in 0 until perBar) {
                val sp = calculateStation(bStart + h * hour + hour / 2)
                if (sp.index > bestIdx) {
                    bestIdx = sp.index
                    bestItems = sp.items
                }
            }
            out.add(SkyBar(bStart, tt[k], tn[k], vocMs.toDouble() / barMs, max(0.0, bestIdx), bestItems))
        }
        return SkyWeek(
            bars = out,
            macd = DoubleArray(bars) { macdAll[warmBars + it] },
            signal = DoubleArray(bars) { signalAll[warmBars + it] },
            hist = DoubleArray(bars) { macdAll[warmBars + it] - signalAll[warmBars + it] },
            vocSpans = spans,
            barMs = barMs
        )
    }

    /**
     * v6.3: the Ascendant line and the Midheaven line for the whole week, 6-hour bars. The Ascendant turns through the
     * whole zodiac in a day, so every bar is the average of many readings (every 15 minutes by default).
     *  - asc = Smarket of the Ascendant against the transiting planets (the original Ascendant index),
     *  - mc  = Smarket of the Midheaven against the natal planets (the original Midheaven index).
     */
    fun calculateAxisWeek(
        startMs: Long,
        lat: Double,
        lon: Double,
        bars: Int = 28,
        barMs: Long = 6 * 3_600_000L,
        stepMs: Long = 900_000L,
        orbLimit: Double = 3.0,
        useSd: Boolean = true,
        usePm: Boolean = true,
        taintAllMm: Boolean = true
    ): AxisWeek {
        val per = max(1, (barMs / stepMs).toInt())
        val out = ArrayList<AxisBar>(bars)
        for (i in 0 until bars) {
            val b0 = startMs + i * barMs
            var a = 0.0
            var m = 0.0
            for (k in 0 until per) {
                val t = b0 + k * stepMs + stepMs / 2
                val longs = calculatePlanetaryPositions(t)
                a += calculateScore(calculateAspects(calculateAscendant(t, lat, lon), longs, orbLimit, useSd, usePm, taintAllMm))
                m += calculateScore(calculateMidheavenAspects(calculateMidheaven(t, lon), longs, orbLimit, useSd, usePm, taintAllMm))
            }
            out.add(AxisBar(b0, a / per, m / per))
        }
        return AxisWeek(out, barMs)
    }

    /** Start and end (exclusive) of the week shown by the sky card: 2 days before to 5 days after the local day of ms. */
    fun weekBoundsMs(ms: Long, timeZoneId: String, daysBack: Int = 2, daysForward: Int = 5): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(timeZoneId))
        cal.timeInMillis = ms
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysBack)
        val start = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_YEAR, daysBack + daysForward)
        return start to cal.timeInMillis
    }

    /** Smarket = Sum(Sp x Wp x Cj) / Sum(Wp), with Wp the dynamic weight. */
    fun calculateScore(aspects: List<AspectInfo>): Double {
        if (aspects.isEmpty()) return 0.0
        var num = 0.0
        var den = 0.0
        aspects.forEach {
            num += it.polarity * it.dynWeight * it.coeff
            den += it.dynWeight
        }
        return if (den <= 1e-9) 0.0 else num / den
    }

    fun generateSeries(
        centerMs: Long,
        windowHours: Int,
        lat: Double,
        lon: Double,
        orbLimit: Double = 3.0,
        useSd: Boolean = true,
        usePm: Boolean = true,
        taintAllMm: Boolean = true,
        orbFalloff: Boolean = true,
        pairBoost: Boolean = true,
        trigger: Boolean = true
    ): List<SentimentBar> {
        val st = 300_000L // 5 minutes
        val halfWindow = windowHours * 1_800_000L
        val t0 = ((centerMs - halfWindow + st / 2) / st) * st
        val count = windowHours * 12
        val list = ArrayList<SentimentBar>(count + 1)

        for (i in 0..count) {
            val t = t0 + i * st
            val longs = calculatePlanetaryPositions(t)
            val asc = calculateAscendant(t, lat, lon)
            val h = calculateAspects(asc, longs, orbLimit, useSd, usePm, taintAllMm)
            val s = calculateScore(h)
            val mc = calculateMidheaven(t, lon)
            val mcAsp = calculateMidheavenAspects(mc, longs, orbLimit, useSd, usePm, taintAllMm)
            val hits = calculateResonance(longs, h, mcAsp, orbLimit, useSd, usePm, taintAllMm, orbFalloff, pairBoost, trigger)
            val bull = hits.filter { it.value > 0 }.sumOf { it.value }
            val bear = hits.filter { it.value < 0 }.sumOf { -it.value }
            val bearPluto = hits.filter { it.value < 0 && it.isHardPluto }.sumOf { -it.value }
            val base = hits.sumOf { it.baseValue }
            list.add(SentimentBar(t, asc, longs, h, s, mc, mcAsp, calculateScore(mcAsp), bull, bear, bearPluto, hits, base))
        }
        return list
    }

    fun calculateExactHits(series: List<SentimentBar>, orbLimit: Double = 3.0, taintAllMm: Boolean = true): List<HitEvent> {
        val ev = mutableListOf<HitEvent>()
        for (i in 0 until series.size - 1) {
            val a = series[i]
            val b = series[i + 1]
            val tainted = taintedFlags(a.planetaryLongitudes, orbLimit)
            PLANETS.forEachIndexed { k, planet ->
                val sa = sep(a.ascDegree, a.planetaryLongitudes[k])
                val sb = sep(b.ascDegree, b.planetaryLongitudes[k])
                ANGLES.forEach { A ->
                    val fa = sa - A
                    val fb = sb - A
                    if (fa * fb < 0 && abs(fa) < 8 && abs(fb) < 8) {
                        val exactT = (a.timeMs + (fa / (fa - fb)) * 300_000).toLong()
                        ev.add(HitEvent(exactT, planet.name, planet.glyph, planet.weight, A, aspectPolarity(planet, A, tainted[k], taintAllMm), cjFor(planet, A)))
                    }
                }
            }
        }
        return ev.sortedBy { it.timeMs }
    }

    val ZODIAC = listOf("Aries", "Taurus", "Gemini", "Cancer", "Leo", "Virgo", "Libra", "Scorpio", "Sagittarius", "Capricorn", "Aquarius", "Pisces")
    val ZODIAC_GLYPHS = listOf("♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓")

    fun formatDMS(deg: Double): String {
        val signIdx = (floor(deg / 30.0).toInt()) % 12
        val inSign = deg % 30.0
        val d = floor(inSign).toInt()
        val m = floor((inSign - d) * 60.0).toInt()
        return "%d°%02d′ %s %s".format(d, m, ZODIAC[signIdx], ZODIAC_GLYPHS[signIdx])
    }

    val PRESET_LOCATIONS = listOf(
        GeoLocation("london", "London (Brent Hub)", 51.5074, -0.1278, "ICE Brent"),
        GeoLocation("newyork", "New York (WTI Hub)", 40.7128, -74.006, "NYMEX WTI"),
        GeoLocation("harare", "Harare (CAT Reference)", -17.8292, 31.0522, "Southern Africa"),
        GeoLocation("dubai", "Dubai (DME Oman)", 25.2048, 55.2708, "Middle East"),
        GeoLocation("singapore", "Singapore (Asia Brent)", 1.3521, 103.8198, "Asia Pacific"),
        GeoLocation("tokyo", "Tokyo (TOCOM)", 35.6762, 139.6503, "East Asia")
    )
}
