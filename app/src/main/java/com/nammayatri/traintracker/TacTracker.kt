package com.nammayatri.traintracker

/**
 * Check-in, station tracking and ETA from serving-cell TAC plus the accelerometer
 * state machine. Live twin of tools/tac_tracker.py - keep the two in sync; the
 * Python one replays recorded rides to validate every change before it ships.
 *
 * What the rides showed, and what each rule below is for:
 *  - Station = TAC. Same TAC per station on all three rides; it changed 6-43s
 *    (mean 25s) BEFORE the doors opened, so it doubles as an "arriving" signal.
 *  - Position only ever moves forward in the direction of travel, after a new TAC
 *    holds for PERSIST_MS. That absorbed every TAC ping-pong seen so far.
 *  - Check-in is PROVISIONAL on sustained motion that began after the rider's last
 *    step (walk -> stand -> the train moves you), CONFIRMED at the first forward
 *    TAC change. Boarding time is motion onset, not the last step: on ride C the
 *    rider stood on the platform for ~165s before the train left.
 *  - mapFree: for an operator the TAC map was not surveyed on. TAC values are
 *    meaningless there, but each underground station still has its own area, so a TAC
 *    not yet seen on this ride, held PERSIST_MS, is the next station in planned order.
 *    Station names and leg times still come from the map (they are operator-independent).
 *    Lost vs map mode: cold start mid-ride and wrong-direction detection.
 */
class TacTracker(private val m: TacMap, val origin: Int, val dest: Int, val mapFree: Boolean = false) {

    enum class CheckIn { NONE, PROVISIONAL, CONFIRMED }

    companion object {
        const val PERSIST_MS = 5_000L        // new TAC must hold this long to advance
        const val CONFIRM_MS = 10_000L       // ...and this long with no IMU provisional
        const val PROV_MOVE_MS = 15_000L     // sustained fresh motion for provisional
        const val MOVING_LAG_MS = 13_000L    // MOVING is entered ~13s after real onset
        const val PROV_TIMEOUT_MS = 360_000L // provisional with no TAC change -> cancelled
    }

    val dir = if (dest >= origin) 1 else -1
    val totalStops get() = kotlin.math.abs(dest - origin)

    var cur = origin; private set
    var curAtMs = 0L; private set
    var checkIn = CheckIn.NONE; private set
    var boardedAtMs = 0L; private set     // 0 = unknown (e.g. cold start)
    var done = false; private set
    var wrongDirection = false; private set
    var lastTac = -1; private set

    /** (timeMs, label, json fields, human text) - logged to file and shown in the feed. */
    var onEvent: ((Long, String, String, String) -> Unit)? = null

    private var cand: Int? = null
    private var candSinceMs = 0L
    private var provAtMs = 0L
    private var provBoardedMs = 0L
    private var predNextMs = 0L           // predicted time of entering station cur+dir's TAC
    private var firstTac = true
    private var lastStepMs = 0L
    private var movingSinceMs = 0L
    private var prevState: MotionState? = null
    private var lastOnsetMs = 0L
    private var lastMovingMs = 0L
    private val seenTacs = HashSet<Int>()     // map-free: every TAC met on this ride
    private var candTac: Int? = null
    private val missReported = HashSet<Int>() // map mode: unknown TACs already logged

    val nextIndex: Int? get() = if (done || wrongDirection) null else cur + dir

    // ---- accelerometer: provisional check-in ---------------------------------
    fun onTick(tMs: Long, state: MotionState, steps: Int) {
        if (steps > 0) lastStepMs = tMs
        if (state == MotionState.MOVING && prevState != MotionState.MOVING) {
            movingSinceMs = tMs
            lastOnsetMs = maxOf(tMs - MOVING_LAG_MS, lastStepMs)
        }
        if (state != MotionState.MOVING) movingSinceMs = 0L else lastMovingMs = tMs
        prevState = state

        if (checkIn == CheckIn.NONE && cur == origin && !wrongDirection) {
            // Motion must have STARTED after the last step. MOVING carried over from
            // walking (the state machine needs 10 still seconds to flip) is the rider's
            // own momentum fading - it fired a false provisional on ride C.
            val fresh = movingSinceMs > 0L && (lastStepMs == 0L || movingSinceMs > lastStepMs)
            if (fresh && tMs - movingSinceMs >= PROV_MOVE_MS) {
                checkIn = CheckIn.PROVISIONAL
                provAtMs = tMs; provBoardedMs = lastOnsetMs
                predNextMs = provBoardedMs + (m.depToTacSec(origin, origin + dir) * 1000).toLong()
                emit(tMs, "CHECKIN_PROVISIONAL", "\"boardedAtMs\":$provBoardedMs",
                    "Train moving at ${m.name(origin)} - boarded? (departed ${(tMs - provBoardedMs) / 1000}s ago)")
            }
        }
        if (checkIn == CheckIn.PROVISIONAL && tMs - provAtMs > PROV_TIMEOUT_MS) {
            checkIn = CheckIn.NONE; provAtMs = 0L; provBoardedMs = 0L; predNextMs = 0L
            emit(tMs, "CHECKIN_PROVISIONAL_CANCELLED", "\"reason\":\"no TAC change in 6 min\"",
                "Provisional check-in cancelled - never left ${m.name(origin)}")
        }
    }

    // ---- TAC: position, confirmation, cold start ------------------------------
    fun onTac(tMs: Long, tac: Int) {
        lastTac = tac
        if (mapFree) { onTacMapFree(tMs, tac); return }
        val i = m.indexOfTac(tac)
        if (firstTac) {
            firstTac = false
            if (i != null && (i - origin) * dir > 0) {
                // App opened mid-journey: one reading places the rider.
                checkIn = CheckIn.CONFIRMED; boardedAtMs = 0L
                emit(tMs, "CHECKIN_COLDSTART", "\"station\":\"${m.name(i)}\"",
                    "Opened mid-journey - already at ${m.name(i)}, ${kotlin.math.abs(i - origin)} stop(s) in")
                arrive(i, tMs, cold = true)
                return
            }
        }
        if (i == null && !done && checkIn == CheckIn.CONFIRMED && missReported.add(tac)) {
            // Evidence for staleness: a TAC the map does not know, mid-ride on a mapped operator.
            emit(tMs, "MAP_MISS", "\"tac\":$tac,\"lastStation\":\"${m.name(cur)}\"",
                "map miss: tac $tac not on map (after ${m.name(cur)})")
        }
        if (i == null || done || wrongDirection) return
        val ahead = (i - cur) * dir
        if (ahead > 0) {
            if (cand != i) { cand = i; candSinceMs = tMs }
            val need = if (checkIn != CheckIn.NONE) PERSIST_MS else CONFIRM_MS
            if (tMs - candSinceMs >= need) arrive(i, candSinceMs)
        } else if (ahead < 0 && checkIn != CheckIn.CONFIRMED && cur == origin) {
            if (cand != i) { cand = i; candSinceMs = tMs }
            if (tMs - candSinceMs >= CONFIRM_MS) {
                wrongDirection = true
                emit(tMs, "WRONG_DIRECTION", "\"towards\":\"${m.name(i)}\"",
                    "!!! WRONG DIRECTION - heading to ${m.name(i)}, away from ${m.name(dest)}")
            }
        } else {
            cand = null   // behind us or same station: ping-pong, ignore
        }
    }

    private fun onTacMapFree(tMs: Long, tac: Int) {
        if (done) return
        if (seenTacs.isEmpty()) { seenTacs.add(tac); return }      // first reading = origin's area
        if (tac in seenTacs) { candTac = null; return }             // ping-pong back to a known area
        if (checkIn == CheckIn.NONE && cur == origin && platformLike(tMs)) {
            // A second area while standing or walking at the origin (Guindy has two TACs):
            // part of the origin, not the next station.
            seenTacs.add(tac); candTac = null
            emit(tMs, "ORIGIN_AREA_ADDED", "\"tac\":$tac", "origin ${m.name(origin)} also uses tac $tac")
            return
        }
        if (candTac != tac) { candTac = tac; candSinceMs = tMs }
        val need = if (checkIn != CheckIn.NONE) PERSIST_MS else CONFIRM_MS
        if (tMs - candSinceMs < need) return
        seenTacs.add(tac); candTac = null
        // No real station follows the previous one this fast (shortest measured leg ~101s).
        // A new area within half the expected leg is the same station's second area - ride B
        // met Guindy's 42134 only 28s before Little Mount's own TAC.
        val sinceArrival = candSinceMs - curAtMs
        val minGapMs = (0.5 * m.legSec(cur, cur + dir) * 1000).toLong()
        if (cur != origin && sinceArrival < minGapMs) {
            curAtMs = candSinceMs
            predNextMs = candSinceMs + (m.legSec(cur, cur + dir) * 1000).toLong()
            emit(tMs, "AREA_MERGED", "\"tac\":$tac,\"station\":\"${m.name(cur)}\",\"afterS\":${sinceArrival / 1000}",
                "tac $tac is still ${m.name(cur)} (only ${sinceArrival / 1000}s after it)")
            return
        }
        arrive(cur + dir, candSinceMs)
    }

    /** Standing still or on foot recently - not a moving train. Only trusted before check-in. */
    private fun platformLike(tMs: Long) =
        (lastMovingMs == 0L || tMs - lastMovingMs > 60_000L) || (lastStepMs > 0L && tMs - lastStepMs < 30_000L)

    private fun arrive(i: Int, tMs: Long, cold: Boolean = false) {
        if (checkIn != CheckIn.CONFIRMED) {
            boardedAtMs = if (provBoardedMs > 0L) provBoardedMs else lastOnsetMs
            checkIn = CheckIn.CONFIRMED
            emit(tMs, "CHECKIN_CONFIRMED",
                "\"boardedAtMs\":$boardedAtMs,\"provisionalAtMs\":$provAtMs",
                "CHECK-IN confirmed - boarded at ${m.name(origin)} ~${(tMs - boardedAtMs) / 1000}s ago")
        }
        val errS = if (predNextMs == 0L || cold) null else (tMs - predNextMs) / 1000
        cur = i; curAtMs = tMs; cand = null
        val stopNo = kotlin.math.abs(i - origin)
        emit(tMs, "ARRIVING",
            "\"station\":\"${m.name(i)}\",\"stops\":$stopNo" + (if (errS != null) ",\"etaErrS\":$errS" else ""),
            "Arriving ${m.name(i)}  ($stopNo/$totalStops)" + (if (errS != null) "  eta err ${"%+d".format(errS)}s" else ""))
        when {
            i == dest -> {
                done = true; predNextMs = 0L
                emit(tMs, "ARRIVING_DESTINATION", "\"station\":\"${m.name(i)}\"",
                    "*** ARRIVING ${m.name(i)} - your stop, get ready ***")
                return
            }
            (i - dest) * dir > 0 -> {
                done = true
                emit(tMs, "PASSED_DESTINATION", "", "!!! Passed ${m.name(dest)} - missed your stop")
                return
            }
            i + dir == dest -> emit(tMs, "NEXT_STOP_IS_DESTINATION", "\"station\":\"${m.name(dest)}\"",
                "*** Next stop is yours: ${m.name(dest)} ***")
        }
        predNextMs = tMs + (m.legSec(i, i + dir) * 1000).toLong()
    }

    /** Seconds until the next station's TAC and until the destination's, or null. */
    fun eta(nowMs: Long): Pair<Long, Long>? {
        if (predNextMs == 0L || done || wrongDirection) return null
        val next = maxOf(0L, (predNextMs - nowMs) / 1000)
        var dst = next.toDouble(); var k = cur + dir
        while (k != dest) { dst += m.legSec(k, k + dir); k += dir }
        return Pair(next, dst.toLong())
    }

    private fun emit(tMs: Long, label: String, json: String, text: String) =
        onEvent?.invoke(tMs, label, json, text)
}
