package com.nammayatri.traintracker

import kotlin.math.sqrt

enum class MotionState { UNKNOWN, STOPPED, MOVING }

/**
 * One second of derived features plus the state machine's view of the world.
 * Everything here gets logged; the detector runs on exactly these numbers, so a
 * recorded trace can be replayed offline to retune thresholds without riding again.
 */
data class Tick(
    val tMs: Long,
    val samples: Int,
    val energy: Double,      // variance of |a| over this second
    val floor: Double,       // rolling minimum of energy - the robust signal
    val threshold: Double,
    val movingRef: Double,
    val state: MotionState,
    val stopCount: Int,
    val checkedIn: Boolean,
    val dwellSec: Int,
    val movingRunSec: Int,
    val stepsThisSec: Int,
    val secSinceStep: Long,
    val alighting: Boolean,
    val exited: Boolean,
    val corroboration: Int
)

/**
 * Stop / check-in detection from accelerometer magnitude alone.
 *
 * Key idea: |a| is a scalar, so it is invariant to device orientation. Its variance
 * over a 1s window therefore needs no gravity estimation and no axis projection.
 *
 * A moving train has a FLOOR of vibration that never drops. A stopped train's floor
 * falls to near zero. A person handling the phone adds spikes on top of the floor but
 * cannot remove it - so we track the rolling MINIMUM, not the mean.
 */
class SensorEngine(
    private val floorWindowSec: Int = 10,
    private val stopHoldSec: Int = 10,   // seconds below threshold to call STOPPED
    private val moveHoldSec: Int = 5,    // seconds above threshold to call MOVING
    private val minDwellSec: Int = 15,   // a dwell shorter than this is not a station
    private val checkInMoveSec: Int = 30,// sustained motion, no steps => on a train
    private val checkInFastSec: Int = 15,   // cells corroborate departure -> confirm sooner
    private val checkInStrictSec: Int = 60, // cells contradict -> demand much more
    private val absMinThreshold: Double = 0.02,
    private val movingRefFraction: Double = 0.15,
    private val refClamp: Double = 2.0,      // no train vibrates as hard as a human walks
    private val refDecay: Double = 0.995,
    private val stepGuardSec: Long = 5,
    private val boardLookbackSec: Long = 60,
    private val alightCancelSec: Int = 20,   // step-free MOVING => it's the train, not you
    private val exitConfirmSec: Int = 60
) {
    private val window = ArrayList<Float>(64)
    private var curSec = -1L
    private val floorBuf = ArrayDeque<Double>()

    private var movingRef = 0.0          // decayed running max of floor = "typical moving vibration"
    private var stoppedRun = 0
    private var movingRun = 0
    private var dwellSec = 0
    private var moveStateSec = 0
    private var stepsThisSec = 0
    private var lastStepMs = 0L

    var state = MotionState.UNKNOWN; private set
    var stopCount = 0; private set
    var checkedIn = false; private set
    var checkInAtMs = 0L; private set
    /** Best estimate of when the rider actually boarded: last step before motion onset. */
    var boardedAtMs = 0L; private set
    /** Provisional: a stop happened and the rider started walking. Cheap and reversible. */
    var alighting = false; private set
    var exited = false; private set
    var exitedAtMs = 0L; private set
    private var alightCandidateMs = 0L
    private var stepFreeMovingRun = 0
    private var movingStartedMs = 0L

    /** Correct the count from an external anchor (cell match). Never moves backwards. */
    fun snapStopCount(n: Int): Boolean {
        if (n > stopCount) { stopCount = n; return true }
        return false
    }

    /**
     * External corroboration from cell identity, set by the service each time the
     * serving cell changes:
     *   +1  we were on the origin station's cells and have since moved off them
     *       (physical departure evidence, independent of the accelerometer)
     *    0  the map does not cover this operator - cells say nothing either way
     *   -1  the map covers this operator but we never saw the origin station,
     *       so sustained motion here is more likely a road vehicle than our train
     *
     * This shifts how much sustained motion check-in demands. It never blocks
     * check-in outright, because the map is one operator on one line and a hard
     * gate would simply fail on a different SIM.
     */
    var cellCorroboration: Int = 0

    fun onStep(tsMs: Long) {
        stepsThisSec++
        lastStepMs = tsMs
    }

    /** Feed one accelerometer sample. Returns a Tick once per second, else null. */
    fun onAccel(x: Float, y: Float, z: Float, tsMs: Long): Tick? {
        val sec = tsMs / 1000L
        if (curSec < 0L) curSec = sec
        var tick: Tick? = null
        if (sec != curSec) {
            tick = closeWindow(curSec * 1000L)
            curSec = sec
            window.clear()
            stepsThisSec = 0
        }
        window.add(sqrt(x * x + y * y + z * z))
        return tick
    }

    private fun closeWindow(tMs: Long): Tick? {
        if (window.size < 3) return null

        var sum = 0.0
        for (v in window) sum += v
        val mean = sum / window.size
        var sq = 0.0
        for (v in window) { val d = v - mean; sq += d * d }
        val energy = sq / window.size

        floorBuf.addLast(energy)
        while (floorBuf.size > floorWindowSec) floorBuf.removeFirst()
        var floor = Double.MAX_VALUE
        for (e in floorBuf) if (e < floor) floor = e

        val secSinceStep = if (lastStepMs == 0L) Long.MAX_VALUE / 2 else (tMs - lastStepMs) / 1000L

        // movingRef must learn VEHICLE vibration, never human motion. Walking is ~25x
        // more energetic than a train; if a walking window trains the reference, the
        // threshold sits far above train vibration and the ride reads as stopped
        // throughout. Exclude windows with recent steps, and clamp for the case where
        // no step detector exists.
        movingRef = if (secSinceStep > stepGuardSec && floor < refClamp)
            maxOf(movingRef * refDecay, floor) else movingRef * refDecay
        val threshold = maxOf(absMinThreshold, movingRef * movingRefFraction)

        val raw = if (floor > threshold) MotionState.MOVING else MotionState.STOPPED
        if (raw == MotionState.MOVING) { movingRun++; stoppedRun = 0 }
        else { stoppedRun++; movingRun = 0 }

        when (state) {
            MotionState.UNKNOWN -> {
                if (movingRun >= moveHoldSec) { state = MotionState.MOVING; moveStateSec = 0; movingStartedMs = tMs }
                else if (stoppedRun >= stopHoldSec) { state = MotionState.STOPPED; dwellSec = stoppedRun }
            }
            MotionState.STOPPED -> {
                dwellSec++
                if (movingRun >= moveHoldSec) {
                    // Leaving a dwell. Count it as a station only once we know we're aboard -
                    // otherwise standing on the origin platform would count as stop #1.
                    if (checkedIn && dwellSec >= minDwellSec) stopCount++
                    state = MotionState.MOVING
                    moveStateSec = 0
                    movingStartedMs = tMs - movingRun * 1000L
                    dwellSec = 0
                }
            }
            MotionState.MOVING -> {
                moveStateSec++
                val need = when {
                    cellCorroboration > 0 -> checkInFastSec
                    cellCorroboration < 0 -> checkInStrictSec
                    else -> checkInMoveSec
                }
                if (!checkedIn && moveStateSec >= need && secSinceStep >= need) {
                    checkedIn = true
                    checkInAtMs = tMs
                    // Standing on a platform and standing on a stationary train are
                    // identical to the accelerometer, so the true boarding instant is
                    // not recoverable from the IMU. The last step is a good proxy only
                    // if it happened just before departure (walked onto a waiting
                    // train); otherwise it marks arrival on the platform. Fall back to
                    // motion onset, which is the physically meaningful anchor.
                    val gap = if (lastStepMs > 0L) movingStartedMs - lastStepMs else Long.MAX_VALUE
                    boardedAtMs = if (gap in 0..(boardLookbackSec * 1000L)) lastStepMs
                                  else movingStartedMs
                }
                if (stoppedRun >= stopHoldSec) { state = MotionState.STOPPED; dwellSec = stoppedRun }
            }
        }

        // ---- alight / exit ----------------------------------------------------
        // A train moves without you taking steps; you do not. That single asymmetry
        // separates "the train left and I'm still on it" from "I got off and walked".
        if (checkedIn && !exited) {
            val stepsRecent = secSinceStep <= 10
            if (!alighting) {
                if (state == MotionState.STOPPED && stepsRecent) {
                    alighting = true
                    alightCandidateMs = tMs
                    stepFreeMovingRun = 0
                }
            } else {
                if (state == MotionState.MOVING && !stepsRecent) {
                    stepFreeMovingRun++
                    // The train pulled away and we went with it - walking in the
                    // carriage during a dwell, not alighting. Discard.
                    if (stepFreeMovingRun >= alightCancelSec) alighting = false
                } else stepFreeMovingRun = 0

                if (alighting && tMs - alightCandidateMs >= exitConfirmSec * 1000L) {
                    exited = true
                    exitedAtMs = alightCandidateMs
                }
            }
        }

        return Tick(
            tMs = tMs, samples = window.size, energy = energy, floor = floor,
            threshold = threshold, movingRef = movingRef, state = state,
            stopCount = stopCount, checkedIn = checkedIn, dwellSec = dwellSec,
            movingRunSec = moveStateSec, stepsThisSec = stepsThisSec,
            secSinceStep = if (secSinceStep > 99999) -1 else secSinceStep,
            alighting = alighting, exited = exited, corroboration = cellCorroboration
        )
    }
}
