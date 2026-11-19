package com.nammayatri.traintracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays real recorded rides through the SAME TacTracker the app runs, and checks
 * it makes the decisions tools/tac_tracker.py made on those rides. Events are fed in
 * time order with no lookahead, exactly as they arrive live.
 */
class TacTrackerReplayTest {
    private val root = File("..").canonicalFile
    private val map = TacMap(File(root, "tacmap.json").readText())

    private abstract class Ev(val tMs: Long, val order: Int)
    private class TickEv(t: Long, val state: MotionState, val steps: Int) : Ev(t, 1)
    private class TacEv(t: Long, val tac: Int) : Ev(t, 0)
    private data class E(val s: Long, val label: String, val text: String)

    private fun replay(trace: String, origin: String, dest: String, fromS: Long = 0, mapFree: Boolean = false): List<E> {
        val lines = File(root, trace).readLines().filter { it.isNotBlank() }
        val t0 = JSONObject(lines[0]).getLong("ts")
        val evs = ArrayList<Ev>()
        for (l in lines) {
            val r = JSONObject(l)
            when (r.optString("type", "event")) {
                "tick" -> evs.add(TickEv(r.getLong("ts"), MotionState.valueOf(r.getString("state")), r.optInt("steps")))
                "cells", "event" -> {
                    if (!r.has("towers")) continue
                    val tw = r.getJSONArray("towers")
                    for (i in 0 until tw.length()) {
                        val c = tw.getJSONObject(i); val cid = c.optLong("cid")
                        if (c.optBoolean("reg") && cid > 0 && cid != 268435455L && !c.isNull("tac")) {
                            evs.add(TacEv(r.getLong("ts"), c.getInt("tac"))); break
                        }
                    }
                }
            }
        }
        evs.sortWith(compareBy<Ev>({ it.tMs }, { it.order }))
        val tr = TacTracker(map, map.stops.indexOfFirst { it.name == origin },
                                 map.stops.indexOfFirst { it.name == dest }, mapFree)
        val out = ArrayList<E>()
        tr.onEvent = { t, label, _, text -> out.add(E((t - t0) / 1000, label, text)) }
        for (e in evs) {
            if ((e.tMs - t0) / 1000 < fromS) continue
            when (e) { is TickEv -> tr.onTick(e.tMs, e.state, e.steps); is TacEv -> tr.onTac(e.tMs, e.tac) }
        }
        println("--- $trace  $origin -> $dest" + (if (mapFree) "  [MAP-FREE]" else "") + if (fromS > 0) "  (opened at t+$fromS)" else "")
        out.forEach { println("  t+%4d  %-26s %s".format(it.s, it.label, it.text)) }
        return out
    }

    private fun arrivals(ev: List<E>) =
        ev.filter { it.label == "ARRIVING" }.map { it.text.substringAfter("Arriving ").substringBefore("  (") to it.s }

    private fun near(expected: Long, actual: Long) =
        assertTrue("expected t+$expected, got t+$actual", kotlin.math.abs(expected - actual) <= 1)

    @Test fun rideC_teynampetToGuindy_withTaps() {
        val ev = replay("traces/real2/traces/trace-20260910-202819.jsonl", "Teynampet", "Guindy")
        near(391, ev.first { it.label == "CHECKIN_PROVISIONAL" }.s)   // train left ~t+360
        near(495, ev.first { it.label == "CHECKIN_CONFIRMED" }.s)
        val a = arrivals(ev)
        assertEquals(listOf("Nandanam", "Saidapet", "Little Mount", "Guindy"), a.map { it.first })
        listOf(495L, 621L, 746L, 894L).zip(a.map { it.second }).forEach { (e, x) -> near(e, x) }
        assertEquals(1, ev.count { it.label == "ARRIVING_DESTINATION" })
        assertEquals(0, ev.count { it.label == "WRONG_DIRECTION" })
    }

    @Test fun rideB_guindyToTeynampet_reverse() {
        val ev = replay("traces/real/trace-20260910-115936.jsonl", "Guindy", "Teynampet")
        near(249, ev.first { it.label == "CHECKIN_CONFIRMED" }.s)
        val a = arrivals(ev)
        assertEquals(listOf("Little Mount", "Saidapet", "Nandanam", "Teynampet"), a.map { it.first })
        listOf(249L, 417L, 595L, 699L).zip(a.map { it.second }).forEach { (e, x) -> near(e, x) }
    }

    @Test fun coldStart_openedMidJourney() {
        val ev = replay("traces/real2/traces/trace-20260910-202819.jsonl", "Teynampet", "Guindy", fromS = 650)
        assertEquals("CHECKIN_COLDSTART", ev.first().label)
        assertEquals(listOf("Saidapet", "Little Mount", "Guindy"), arrivals(ev).map { it.first })
    }

    @Test fun wrongDirection_alertsOnce() {
        // Ride C went towards Guindy; pretend the rider selected Saidapet -> Nandanam.
        val ev = replay("traces/real2/traces/trace-20260910-202819.jsonl", "Saidapet", "Nandanam", fromS = 700)
        assertEquals(1, ev.count { it.label == "WRONG_DIRECTION" })
        near(756, ev.first { it.label == "WRONG_DIRECTION" }.s)
        assertEquals(0, ev.count { it.label == "ARRIVING" })
    }

    // ---- map-free: operator the TAC map was not surveyed on ----------------------
    // Same rides, TAC values ignored: only "a TAC not yet seen on this ride = next station".

    @Test fun mapFree_rideC_matchesMapMode() {
        val ev = replay("traces/real2/traces/trace-20260910-202819.jsonl", "Teynampet", "Guindy", mapFree = true)
        val a = arrivals(ev)
        assertEquals(listOf("Nandanam", "Saidapet", "Little Mount", "Guindy"), a.map { it.first })
        listOf(495L, 621L, 746L, 894L).zip(a.map { it.second }).forEach { (e, x) -> near(e, x) }
        assertEquals(1, ev.count { it.label == "ARRIVING_DESTINATION" })
    }

    @Test fun mapFree_rideB_guindySecondTacIsNotAStation() {
        val ev = replay("traces/real/trace-20260910-115936.jsonl", "Guindy", "Teynampet", mapFree = true)
        val a = arrivals(ev)
        assertEquals(listOf("Little Mount", "Saidapet", "Nandanam", "Teynampet"), a.map { it.first })
        // Little Mount is announced when Guindy's second TAC holds (t+221), 28s before LM's own
        // TAC, which is then merged into it rather than counted as Saidapet.
        assertTrue("LM at t+${a[0].second}", a[0].second in 220L..250L)
        listOf(417L, 595L, 699L).zip(a.drop(1).map { it.second }).forEach { (e, x) -> near(e, x) }
        assertEquals(1, ev.count { it.label == "ARRIVING_DESTINATION" })
    }

    @Test fun rideD_northbound_bothModes() {
        val trace = "traces/real3/trace-20260911-121524.jsonl"
        for (free in listOf(false, true)) {
            val a = arrivals(replay(trace, "Guindy", "Teynampet", mapFree = free))
            assertEquals("mapFree=$free", listOf("Little Mount", "Saidapet", "Nandanam", "Teynampet"), a.map { it.first })
            listOf(585L, 722L, 930L, 1031L).zip(a.map { it.second }).forEach { (e, x) -> near(e, x) }
        }
    }

    @Test fun mapMode_otherOperatorIsNotCovered() {
        assertTrue(map.covers("40440"))
        assertTrue(map.covers(""))
        assertTrue(!map.covers("405869"))
    }

    @Test fun rideE_v011_live_bothModes() {
        // 15 Sep, first ride on v0.11. Long wait at Guindy with its second TAC (42134) flapping.
        val trace = "traces/real4/trace-20260915-120347.jsonl"
        for (free in listOf(false, true)) {
            val ev = replay(trace, "Guindy", "Teynampet", mapFree = free)
            val a = arrivals(ev)
            assertEquals("mapFree=$free", listOf("Little Mount", "Saidapet", "Nandanam", "Teynampet"), a.map { it.first })
            listOf(883L, 1053L, 1219L, 1319L).zip(a.map { it.second }).forEach { (e, x) -> near(e, x) }
            assertEquals(1, ev.count { it.label == "ARRIVING_DESTINATION" })
        }
    }
}
