package com.nammayatri.traintracker

/**
 * Generic "zone key" check-in detector: walking stops -> freeze whatever key values
 * (TAC, cellId, ...) were seen nearby as "the origin" -> watch for a value outside
 * that set to persist -> check-in. No accelerometer floor/threshold involved.
 *
 * Instantiated once per candidate key field so they run in parallel, off the same
 * live step/cell events, each producing its own independent result for comparison:
 *
 *   ZoneCheckinEngine("TAC")     fed s.tac.toLong()  - measured 100% cross-ride repeat
 *   ZoneCheckinEngine("cellId")  fed s.cellId         - measured 24% cross-ride repeat,
 *                                                        included as a field-measured
 *                                                        negative control, not a real
 *                                                        candidate
 */
class ZoneCheckinEngine(
    val label: String,
    private val lookbackMs: Long = 60_000,
    private val freezeDelayMs: Long = 20_000,
    private val confirmMs: Long = 15_000
) {
    private data class Sample(val tMs: Long, val key: Long)
    private val recent = ArrayDeque<Sample>()

    private var lastStepMs = 0L
    var originFrozen = false; private set
    private var originSet: Set<Long> = emptySet()
    private var newKeyValue: Long? = null
    private var newKeySinceMs = 0L

    var checkedIn = false; private set
    var checkInAtMs = 0L; private set
    var boardedAtMs = 0L; private set

    var onOriginFrozen: ((Set<Long>, Long) -> Unit)? = null
    var onCheckIn: ((Long, Long) -> Unit)? = null   // (checkInAtMs, boardedAtMs)

    fun onStep(tsMs: Long) {
        if (checkedIn) return
        lastStepMs = tsMs
        // Walking resumed: invalidate any pending freeze/watch cycle - still moving
        // around the station, not settled aboard yet.
        originFrozen = false
        originSet = emptySet()
        newKeyValue = null
    }

    fun onKey(key: Long, tsMs: Long) {
        recent.addLast(Sample(tsMs, key))
        while (recent.isNotEmpty() && tsMs - recent.first().tMs > lookbackMs) recent.removeFirst()
        if (checkedIn || lastStepMs == 0L) return

        if (!originFrozen && tsMs - lastStepMs >= freezeDelayMs) {
            originSet = recent.map { it.key }.toSet()
            originFrozen = true
            onOriginFrozen?.invoke(originSet, tsMs)
        }
        if (!originFrozen) return

        if (key !in originSet) {
            if (newKeyValue != key) { newKeyValue = key; newKeySinceMs = tsMs }
            if (tsMs - newKeySinceMs >= confirmMs) {
                checkedIn = true
                checkInAtMs = tsMs
                boardedAtMs = lastStepMs
                onCheckIn?.invoke(checkInAtMs, boardedAtMs)
            }
        } else {
            newKeyValue = null   // back inside the origin set - reset the new-key timer
        }
    }
}
