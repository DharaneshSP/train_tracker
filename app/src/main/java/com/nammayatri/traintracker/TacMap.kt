package com.nammayatri.traintracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * TAC-keyed station map (assets/tacmap.json). TAC was identical per station across
 * three real rides; cellId repeated only 9%, so cellId is not used here at all.
 *
 * Leg times are measured, not derived from distance: on this line an 800m leg took
 * 135s and a 2111m leg 126s, so distance/speed would be off by 60-90s. Coordinates
 * only feed the fallback for legs nobody has ridden yet.
 */
class TacMap(json: String) {
    /** App path: the bundled asset. The String constructor lets JVM tests replay rides. */
    constructor(ctx: Context) : this(
        try { ctx.assets.open("tacmap.json").bufferedReader().use { it.readText() } } catch (_: Exception) { "{}" })

    data class Stop(val name: String, val lat: Double, val lon: Double, val tacs: Set<Int>)
    private data class Seg(val distM: Int,
                           val tacFwd: List<Double>, val tacRev: List<Double>,
                           val depFwd: List<Double>, val depRev: List<Double>)

    var line = ""; private set
    /** Operators (MCC+MNC) the TACs were surveyed on. TAC numbering is per operator, so on any
     *  other operator the tracker must not look TACs up here - it runs map-free instead. */
    val operators = HashSet<String>()
    val stops = ArrayList<Stop>()
    private val segs = ArrayList<Seg>()
    private val tacToIdx = HashMap<Int, Int>()
    private var tacToDepartureS = 30.0
    private var fallbackBaseS = 116.0
    private var fallbackPerKmS = 16.0

    init {
        try {
            val root = JSONObject(json)
            line = root.optString("line")
            root.optJSONArray("operators")?.let { a -> for (i in 0 until a.length()) operators.add(a.getString(i)) }
            val st = root.getJSONArray("stations")
            for (i in 0 until st.length()) {
                val s = st.getJSONObject(i)
                val tacs = HashSet<Int>()
                val ta = s.getJSONArray("tacs")
                for (j in 0 until ta.length()) { tacs.add(ta.getInt(j)); tacToIdx[ta.getInt(j)] = i }
                stops.add(Stop(s.getString("name"), s.getDouble("lat"), s.getDouble("lon"), tacs))
            }
            val sg = root.getJSONArray("segments")
            for (i in 0 until sg.length()) {
                val s = sg.getJSONObject(i)
                val t = s.optJSONObject("tac_to_tac_s") ?: JSONObject()
                val d = s.optJSONObject("dep_to_tac_s") ?: JSONObject()
                segs.add(Seg(s.optInt("dist_m"), nums(t.optJSONArray("fwd")), nums(t.optJSONArray("rev")),
                             nums(d.optJSONArray("fwd")), nums(d.optJSONArray("rev"))))
            }
            root.optJSONObject("defaults")?.let {
                tacToDepartureS = it.optDouble("tac_to_departure_s", tacToDepartureS)
                fallbackBaseS = it.optDouble("fallback_base_s", fallbackBaseS)
                fallbackPerKmS = it.optDouble("fallback_per_km_s", fallbackPerKmS)
            }
        } catch (_: Exception) { }
    }

    fun indexOfTac(tac: Int): Int? = tacToIdx[tac]

    /** Unknown operator ("") keeps the old behaviour: assume the map applies. */
    fun covers(plmn: String): Boolean = plmn.isEmpty() || operators.isEmpty() || plmn in operators
    fun name(i: Int): String = stops.getOrNull(i)?.name ?: "?"

    /** Expected seconds from entering station a's TAC to entering adjacent station b's TAC. */
    fun legSec(a: Int, b: Int): Double {
        val s = segs.getOrNull(minOf(a, b)) ?: return fallbackBaseS
        val fwd = b > a
        // TAC boundaries are direction-asymmetric, so same-direction timings win.
        val own = if (fwd) s.tacFwd else s.tacRev
        val other = if (fwd) s.tacRev else s.tacFwd
        return when {
            own.isNotEmpty() -> median(own)
            other.isNotEmpty() -> median(other)
            else -> fallbackBaseS + fallbackPerKmS * s.distM / 1000.0
        }
    }

    /** Expected seconds from the train leaving station a to entering station b's TAC. */
    fun depToTacSec(a: Int, b: Int): Double {
        val s = segs.getOrNull(minOf(a, b))
        val own = if (s == null) emptyList() else if (b > a) s.depFwd else s.depRev
        return if (own.isNotEmpty()) median(own) else legSec(a, b) - tacToDepartureS
    }

    private fun nums(a: JSONArray?): List<Double> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getDouble(it) }

    private fun median(v: List<Double>): Double {
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
