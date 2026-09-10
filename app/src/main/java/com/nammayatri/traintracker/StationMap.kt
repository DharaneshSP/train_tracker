package com.nammayatri.traintracker

import android.content.Context
import org.json.JSONObject

data class Station(val name: String, val lat: Double, val lon: Double, val cells: Set<Long>)

/**
 * Bundled station map, surveyed from real rides. Cells that GPS could never locate
 * (underground) are recovered by time-bracketing between two GPS-anchored stations,
 * so the map covers tunnel segments without any GPS at runtime.
 */
class StationMap(ctx: Context) {
    val lines = LinkedHashMap<String, List<Station>>()
    /** cellId -> station name, for re-anchoring a drifting stop count. */
    val cellToStation = HashMap<Long, String>()

    init {
        try {
            val raw = ctx.assets.open("stationmap.json").bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            for (line in root.keys()) {
                val obj = root.getJSONObject(line)
                val arr = obj.getJSONArray("stations")
                val list = ArrayList<Station>()
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val cells = HashSet<Long>()
                    val ca = s.optJSONArray("cells")
                    if (ca != null) for (j in 0 until ca.length()) cells.add(ca.getLong(j))
                    val name = s.getString("name")
                    cells.forEach { cellToStation[it] = name }
                    list.add(Station(name, s.getDouble("lat"), s.getDouble("lon"), cells))
                }
                lines[line] = list
                // segment cells belong to the run *into* the following station
                val segs = obj.optJSONObject("segments")
                if (segs != null) for (k in segs.keys()) {
                    val to = k.substringAfter("->")
                    val ca = segs.getJSONObject(k).optJSONArray("cells") ?: continue
                    for (j in 0 until ca.length())
                        cellToStation.putIfAbsent(ca.getJSONObject(j).getLong("cid"), "~$to")
                }
            }
        } catch (_: Exception) { }
    }

    fun stations(line: String): List<Station> = lines[line] ?: emptyList()
    fun firstLine(): String = lines.keys.firstOrNull() ?: ""
}
