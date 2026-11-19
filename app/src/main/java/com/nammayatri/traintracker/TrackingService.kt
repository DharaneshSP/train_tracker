package com.nammayatri.traintracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackingService : Service(), SensorEventListener {

    companion object {
        const val CH = "tracking"
        /** A phone hops bands/carriers while standing still, so the SET of cells over a
         *  window fingerprints a place far better than whichever one is serving now. */
        const val FINGERPRINT_WINDOW_MS = 60_000L
        const val CELL_POLL_MS = 5_000L      // heartbeat; push callback catches the rest

        var running = false; private set
        var lastTick: Tick? = null; private set
        var lastCell: CellSample? = null; private set
        /** Latest full reading (serving + neighbours) for the live cell panel. */
        var lastCells: List<CellSample> = emptyList(); private set
        var lastCellsAtMs = 0L; private set
        var lastCellsSrc = ""; private set
        /** Active SIMs (carrier, operator, network type, data SIM), refreshed with the 5 s poll. */
        var lastSims: List<TelephonyReader.SimInfo> = emptyList(); private set
        var logger: TraceLogger? = null; private set
        var onUpdate: (() -> Unit)? = null
        var meta: String = ""
        var line: String = ""
        var originIndex: Int = 0
        var destIndex: Int = 0
        var map: StationMap? = null
        /** TAC-keyed map + tracker: the primary check-in / station / ETA logic. */
        var tacMap: TacMap? = null
        var tracker: TacTracker? = null; private set
        /** Operator the tracker follows (MCC+MNC) and whether the TAC map applies to it. */
        var trackPlmn: String = ""; private set
        /** Every operator with a registered cell in the latest reading - 2 on a dual-SIM phone. */
        var registeredPlmns: List<String> = emptyList(); private set

        /** The serving cell of the tracked operator. With two SIMs both report a registered cell. */
        fun servingOf(cells: List<CellSample>): CellSample? =
            cells.firstOrNull { it.registered && it.idKnown && (trackPlmn.isEmpty() || it.plmn.isEmpty() || it.plmn == trackPlmn) }

        /** Most recent station named by a serving cell, and when. */
        var stationHint: String? = null; private set
        var stationHintMs = 0L; private set
        /** Did we see a cell belonging to the ORIGIN station before departing? */
        var originSeenMs = 0L; private set
        /** Does the bundled map cover this operator at all? If not, cells anchor nothing
         *  and we must fall back to IMU-only rather than blocking detection outright. */
        var mapCoverage = false; private set
        var checkInCellConfirmed = false; private set
        var arrivedByCell = false; private set
        /** Have we matched ANY cell in the map this session? Proves the map covers this
         *  operator, which is what makes "origin never seen" meaningful rather than just
         *  "we have no data". */
        var matchedAnyMapCell = false; private set
        /** Were we on the origin station's cells and have since moved off them? That is
         *  physical departure evidence, independent of the accelerometer. */
        var departedOriginCell = false; private set
        private var engineRef: SensorEngine? = null

        /** Arrived at the destination: by counted stops, or by a destination cell match. */
        fun arrivedAt(stops: Int) = (expectedStops > 0 && stops >= expectedStops) || arrivedByCell

        /** +1 travelling down the surveyed list, -1 travelling back up it. */
        val direction: Int get() = if (destIndex >= originIndex) 1 else -1
        /** How many station stops this leg should contain. */
        val expectedStops: Int get() = kotlin.math.abs(destIndex - originIndex)

        fun originName(): String = map?.stations(line)?.getOrNull(originIndex)?.name ?: "?"
        fun destName(): String = map?.stations(line)?.getOrNull(destIndex)?.name ?: "?"

        /** True once the counted stops reach the destination. */
        fun arrived(stops: Int) = arrivedAt(stops)
        /** True on the leg between the second-to-last and last stop - time to stand up. */
        fun alightNext(stops: Int) = expectedStops > 0 && stops == expectedStops - 1

        /** Rolling on-screen feed. Everything here also goes to the file. */
        val feed = ArrayDeque<String>()
        private const val FEED_MAX = 200
        private val hhmmss = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun say(s: String) {
            synchronized(feed) {
                feed.addFirst("${hhmmss.format(Date())}  $s")
                while (feed.size > FEED_MAX) feed.removeLast()
            }
            onUpdate?.invoke()
        }

        /** Name a stop index using the bundled survey, respecting travel direction. */
        fun stationFor(stopNo: Int): String {
            val st = map?.stations(line) ?: return ""
            val i = originIndex + stopNo * direction
            return if (i in st.indices) st[i].name else ""
        }

        private val recentCells = LinkedHashMap<Long, CellSample>()
        private val recentSeenMs = HashMap<Long, Long>()

        fun noteCells(cells: List<CellSample>, now: Long) {
            for (c in cells) {
                if (c.cellId <= 0 || c.cellId == 268435455L) continue
                recentCells[c.cellId] = c; recentSeenMs[c.cellId] = now
            }
            val stale = recentSeenMs.filterValues { now - it > FINGERPRINT_WINDOW_MS }.keys.toList()
            for (k in stale) { recentCells.remove(k); recentSeenMs.remove(k) }
        }

        private fun fingerprint(): String = recentCells.values.joinToString(",") {
            """{"cid":${it.cellId},"tac":${it.tac},"rsrp":${it.rsrp},"reg":${it.registered}}"""
        }

        fun fingerprintSize(): Int = recentCells.size

        fun mark(label: String) {
            logger?.event(System.currentTimeMillis(), label,
                """"stops":${lastTick?.stopCount ?: -1},"state":"${lastTick?.state ?: "NA"}",""" +
                """"checkedIn":${lastTick?.checkedIn ?: false},"cid":${lastCell?.cellId ?: -1},""" +
                """"floor":${lastTick?.floor ?: -1.0},"fingerprint":[${fingerprint()}]""")
            say("TAP  $label   (cells in window: ${recentCells.size})")
        }
    }

    private lateinit var sm: SensorManager
    private var accel: Sensor? = null
    private var stepDet: Sensor? = null
    private val engine = SensorEngine()                     // approach 1: accelerometer-threshold
    private val tacEngine = ZoneCheckinEngine("TAC")         // approach 2: TAC-set, verified 100% repeat
    private val cellIdEngine = ZoneCheckinEngine("cellId")   // approach 3: cellId-set, negative control (24% repeat)
    init { engineRef = engine }
    private var oldLog: DecisionLog? = null
    private var newLog: DecisionLog? = null
    private var cellIdLog: DecisionLog? = null
    private var towerLog: DecisionLog? = null   // raw cell-tower readings, isolated for audit
    private var trackerLog: DecisionLog? = null // TacTracker: check-in, arrivals, ETA errors
    private var prevOldCheckedIn = false
    private var prevStopsRejected = 0
    private lateinit var tel: TelephonyReader
    private lateinit var tm: TelephonyManager
    private val h = Handler(Looper.getMainLooper())
    private var bootMs = 0L
    private var prevStops = 0
    private var prevCheckedIn = false
    private var prevExited = false
    private var alertedNext = false
    private var alertedArrived = false
    private var prevServing = -1L
    private var telCallback: Any? = null
    private var trackerDecided = false

    private val pollCells = object : Runnable {
        override fun run() { readCells("poll"); h.postDelayed(this, CELL_POLL_MS) }
    }

    /** Cells CORRECT the IMU; they never gate it. The map is one ride on one operator,
     *  so a strict gate would simply fail on a different SIM. Match -> high confidence
     *  and a snapped count; no match -> IMU carries on alone, flagged lower confidence. */
    private fun anchor(cid: Long, now: Long) {
        val raw = map?.cellToStation?.get(cid) ?: return
        mapCoverage = true
        val name = raw.removePrefix("~")          // "~X" = in the run into station X
        val approaching = raw.startsWith("~")
        stationHint = name; stationHintMs = now

        matchedAnyMapCell = true
        if (name == originName() && !approaching) {
            originSeenMs = now
        } else if (originSeenMs > 0L) {
            // We were on the origin's cells and are now on someone else's: we moved.
            departedOriginCell = true
        }
        if (name == destName() && !approaching) arrivedByCell = true

        recomputeCorroboration(now)

        val st = map?.stations(line) ?: return
        val idx = st.indexOfFirst { it.name == name }
        if (idx < 0 || approaching) return
        val n = (idx - originIndex) * direction
        if (n in 0..expectedStops && lastTick?.checkedIn == true) {
            if (engineRef?.snapStopCount(n) == true)
                say("ANCHOR  cell $cid = $name  -> stop count corrected to $n")
        }
    }

    /**
     * Being demonstrably AT the origin station is the evidence that separates
     * "my train pulled out of Guindy" from "I am sitting in an auto". Departure
     * (moving off the origin's cells) reinforces it but is not required - inside a
     * station the same cell often covers you well past the moment you start moving.
     *
     * Positive corroboration decays: an origin sighting from an hour ago says nothing
     * about motion happening now.
     */
    private fun recomputeCorroboration(now: Long) {
        val originRecent = originSeenMs > 0L && now - originSeenMs < 600_000
        engineRef?.cellCorroboration = when {
            departedOriginCell || originRecent -> 1
            matchedAnyMapCell && originSeenMs == 0L -> -1
            else -> 0
        }
    }

    private fun readCells(src: String) {
        if (src == "poll" || lastSims.isEmpty()) {
            val sims = tel.sims()
            if (sims != lastSims) {
                lastSims = sims
                towerLog?.event(System.currentTimeMillis(), "SIMS", "\"sims\":[" + sims.joinToString(",") {
                    "{\"slot\":${it.slot},\"carrier\":\"${it.carrier.replace("\"", "'")}\",\"plmn\":\"${it.plmn}\"," +
                    "\"net\":\"${it.netType}\",\"data\":${it.dataSim},\"roaming\":${it.roaming}}" } + "]")
            }
        }
        val cells = tel.read()
        if (cells.isEmpty()) return
        val now = System.currentTimeMillis()
        logger?.cells(now, cells)
        noteCells(cells, now)
        // Dedicated parallel log: raw tower readings only, no tick/decision noise mixed
        // in, so the TAC-verification gate's evidence is easy to audit on its own.
        towerLog?.event(now, "TOWER_READ", cells.joinToString(",", prefix = "\"towers\":[", postfix = "]") { it.toJson() })
        lastCells = cells; lastCellsAtMs = now; lastCellsSrc = src
        registeredPlmns = cells.filter { it.registered && it.idKnown && it.plmn.isNotEmpty() }.map { it.plmn }.distinct()
        if (trackPlmn.isEmpty() && registeredPlmns.isNotEmpty()) chooseOperator(now)
        ensureTracker(now, cells.any { it.registered && it.idKnown })
        val s = servingOf(cells) ?: return
        lastCell = s
        tacEngine.onKey(s.tac.toLong(), now)
        cellIdEngine.onKey(s.cellId, now)
        engine.onTacObserved(s.tac)   // feeds the stop-count TAC-verification gate
        tracker?.onTac(now, s.tac)    // primary: check-in, station, ETA
        anchor(s.cellId, now)
        if (s.cellId != prevServing) {
            prevServing = s.cellId
            val known = map?.cellToStation?.get(s.cellId)
            say("CELL ${s.cellId}  tac=${s.tac}  ${s.rsrp}dBm" +
                (if (known != null) "   [$known]" else "") + "   ($src)")
        }
        onUpdate?.invoke()
    }

    /** Prefer an operator the TAC map was surveyed on; else the mobile-data SIM; else the first. */
    private fun chooseOperator(now: Long) {
        val tmap = tacMap
        val mapped = registeredPlmns.firstOrNull { tmap != null && it in tmap.operators }
        val dataSim = try {
            val id = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
            tm.createForSubscriptionId(id).networkOperator ?: ""
        } catch (_: Exception) { "" }
        trackPlmn = mapped ?: registeredPlmns.firstOrNull { it == dataSim } ?: registeredPlmns.first()
        val why = when { mapped != null -> "on map"; trackPlmn == dataSim -> "data SIM"; else -> "first registered" }
        trackerLog?.event(now, "OPERATOR_CHOSEN",
            "\"plmn\":\"$trackPlmn\",\"why\":\"$why\",\"registered\":[${registeredPlmns.joinToString(",") { "\"$it\"" }}],\"dataSim\":\"$dataSim\"")
        say("operator $trackPlmn ($why)  registered: ${registeredPlmns.joinToString(" + ")}")
    }

    /** The tracker waits for the first reading: map vs map-free depends on the operator. */
    private fun ensureTracker(now: Long, hasServing: Boolean) {
        if (tracker != null || trackerDecided) return
        val tmap = tacMap ?: return
        if (!hasServing) return   // no operator known yet (older Android without MCC/MNC -> "" = map mode)
        trackerDecided = true
        if (originIndex !in tmap.stops.indices || destIndex !in tmap.stops.indices || originIndex == destIndex) return
        val free = !tmap.covers(trackPlmn)
        tracker = TacTracker(tmap, originIndex, destIndex, mapFree = free).also { tr ->
            tr.onEvent = { t, label, json, text ->
                trackerLog?.event(t, label, json)
                say("TRACK: $text")
            }
        }
        trackerLog?.event(now, "TRACKER_MODE", "\"mode\":\"${if (free) "MAP_FREE" else "MAP"}\",\"plmn\":\"$trackPlmn\"")
        say("TRACK: mode ${if (free) "MAP-FREE (operator not surveyed)" else "MAP"}  op $trackPlmn")
    }

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepDet = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        tel = TelephonyReader(this)
        tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (map == null) map = StationMap(this)
        if (tacMap == null) tacMap = TacMap(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        // Defensive re-check even though MainActivity already gated the launch - an OEM
        // quirk or a permission revoked between the check and this call must not crash
        // the whole process, only skip cell data for this session.
        val hasLoc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        running = true
        logger = TraceLogger(this, meta)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        oldLog = DecisionLog(this, "old-accel", stamp)
        newLog = DecisionLog(this, "tac-set", stamp)
        cellIdLog = DecisionLog(this, "cellid-set", stamp)
        towerLog = DecisionLog(this, "cell-towers", stamp)
        trackerLog = DecisionLog(this, "tac-tracker", stamp)
        tracker = null; trackPlmn = ""; registeredPlmns = emptyList()   // created on the first cell reading
        try {
            startForeground(1, notif())
        } catch (e: Exception) {
            // Backstop: this must never take the whole process down, whatever the type.
            logger?.event(System.currentTimeMillis(), "START_FOREGROUND_FAILED",
                "\"error\":\"${(e.message ?: "").replace("\"", "'")}\"")
            logger?.close(); oldLog?.close(); newLog?.close(); cellIdLog?.close(); towerLog?.close(); trackerLog?.close()
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLoc) say("WARNING: location permission not granted - no cell/TAC data this session")

        tacEngine.onOriginFrozen = { set, ts ->
            newLog?.event(ts, "ORIGIN_SET_FROZEN", "\"tacs\":[${set.joinToString(",")}]")
            say("TAC: origin set frozen -> $set")
        }
        tacEngine.onCheckIn = { checkInAt, boardedAt ->
            newLog?.event(checkInAt, "CHECK_IN",
                "\"boardedAtMs\":$boardedAt,\"backdateMs\":${checkInAt - boardedAt}")
            say("TAC: CHECK-IN (boarded ${(checkInAt - boardedAt) / 1000}s before detection)")
        }
        cellIdEngine.onOriginFrozen = { set, ts ->
            cellIdLog?.event(ts, "ORIGIN_SET_FROZEN", "\"cellIds\":[${set.joinToString(",")}]")
            say("cellId: origin set frozen -> $set")
        }
        cellIdEngine.onCheckIn = { checkInAt, boardedAt ->
            cellIdLog?.event(checkInAt, "CHECK_IN",
                "\"boardedAtMs\":$boardedAt,\"backdateMs\":${checkInAt - boardedAt}")
            say("cellId: CHECK-IN (boarded ${(checkInAt - boardedAt) / 1000}s before detection)")
        }

        // 20 Hz is ample - we measure an energy floor, not a spectrum. maxReportLatency
        // lets the sensor hub FIFO batch samples so the CPU wakes rarely.
        accel?.let { sm.registerListener(this, it, 50_000, 5_000_000) }
        stepDet?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        oldLog?.event(System.currentTimeMillis(), "SESSION_START")
        newLog?.event(System.currentTimeMillis(), "SESSION_START")
        cellIdLog?.event(System.currentTimeMillis(), "SESSION_START")
        towerLog?.event(System.currentTimeMillis(), "SESSION_START")
        trackerLog?.event(System.currentTimeMillis(), "SESSION_START",
            "\"origin\":\"${originName()}\",\"dest\":\"${destName()}\"")

        // Half the cell visits in real traces are shorter than a 10s poll, so polling
        // alone loses them. Subscribe for push updates and keep the poll as a heartbeat.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val cb = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = readCells("push")
                }
                telCallback = cb
                tm.registerTelephonyCallback(mainExecutor, cb)
            } catch (_: Exception) { }
        }
        h.post(pollCells)

        feed.clear()
        say("SESSION START  accel=${accel != null} stepDetector=${stepDet != null} " +
            "push=${telCallback != null}")
        if (line.isNotEmpty())
            say("leg: ${originName()} -> ${destName()}  ($expectedStops stops, " +
                (if (direction > 0) "forward" else "reverse") + ")")
        logger?.event(System.currentTimeMillis(), "SESSION_START",
            """"hasAccel":${accel != null},"hasStepDetector":${stepDet != null},""" +
            """"push":${telCallback != null},"line":"$line","originIndex":$originIndex,""" +
            """"destIndex":$destIndex,"expectedStops":$expectedStops,"origin":"${originName()}",""" +
            """"dest":"${destName()}"""")
        // Nothing may run once the rider closes the app: no restart after a kill.
        return START_NOT_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (bootMs == 0L) bootMs = System.currentTimeMillis() - e.timestamp / 1_000_000L
        val tMs = bootMs + e.timestamp / 1_000_000L
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                engine.onAccel(e.values[0], e.values[1], e.values[2], tMs)?.let { t ->
                    recomputeCorroboration(System.currentTimeMillis())
                    tracker?.onTick(t.tMs, t.state, t.stepsThisSec)
                    lastTick = t
                    logger?.tick(t)
                    if (t.checkedIn && !prevOldCheckedIn) {
                        prevOldCheckedIn = true
                        oldLog?.event(t.tMs, "CHECK_IN",
                            "\"boardedAtMs\":${engine.boardedAtMs},\"backdateMs\":${t.tMs - engine.boardedAtMs}")
                    }
                    if (t.checkedIn && !prevCheckedIn) {
                        prevCheckedIn = true
                        // Did a cell belonging to the origin station appear in the 10 min
                        // before departure? That is what separates "boarded at Guindy"
                        // from "sat in an auto for a while".
                        val nearOrigin = originSeenMs > 0 && t.tMs - originSeenMs < 600_000
                        checkInCellConfirmed = nearOrigin
                        val how = when {
                            departedOriginCell -> "cells corroborate: departed ${originName()}"
                            nearOrigin -> "cells corroborate: at ${originName()}"
                            matchedAnyMapCell -> "CONTRADICTED - map covers this operator but " +
                                "${originName()} never seen (needed 60s)"
                            else -> "IMU only - operator not in map"
                        }
                        say("accel: CHECK-IN  (boarded ~${(t.tMs - engine.boardedAtMs) / 1000}s ago)  [$how]")
                        logger?.event(System.currentTimeMillis(), "DETECTED_CHECKIN",
                            """"cellConfirmed":$nearOrigin,"corroboration":${t.corroboration},""" +
                            """"departedOriginCell":$departedOriginCell,""" +
                            """"mapCoverage":$matchedAnyMapCell,""" +
                            """"origin":"${originName()}","boardedAtMs":${engine.boardedAtMs}""")
                    }
                    if (t.stopsRejected != prevStopsRejected) {
                        prevStopsRejected = t.stopsRejected
                        say("dwell REJECTED (no TAC change) - not counted as a stop")
                        towerLog?.event(t.tMs, "STOP_REJECTED", "\"stopsRejected\":${t.stopsRejected}")
                    }
                    if (t.stopCount != prevStops) {
                        prevStops = t.stopCount
                        val nm = stationFor(t.stopCount)
                        val of = if (expectedStops > 0) "/$expectedStops" else ""
                        say("accel: stop #${t.stopCount}$of  (comparison only)")
                        towerLog?.event(t.tMs, "STOP_CONFIRMED", "\"stopCount\":${t.stopCount}")
                        // The alert fires one stop early, giving a full inter-station run
                        // of warning. When confidence is low, early beats missed.
                        if (alightNext(t.stopCount) && !alertedNext) {
                            alertedNext = true
                        }
                        if (arrived(t.stopCount) && !alertedArrived) {
                            alertedArrived = true
                            logger?.event(System.currentTimeMillis(), "DETECTED_ARRIVAL",
                                """"dest":"${destName()}","stops":${t.stopCount}""")
                        }
                    }
                    if (t.exited && !prevExited) {
                        prevExited = true
                        val atDest = stationHint == destName() || arrivedByCell
                        val how = when {
                            atDest -> "at ${destName()} - confirmed"
                            matchedAnyMapCell -> "cells say ${stationHint ?: "unknown"}, NOT ${destName()}"
                            else -> "no map coverage - unverified"
                        }
                        say("DEBOARDED  [$how]")
                        logger?.event(System.currentTimeMillis(), "DETECTED_DEBOARD",
                            """"atDestination":$atDest,"stationHint":"${stationHint ?: ""}",""" +
                            """"dest":"${destName()}","stops":${t.stopCount}""")
                    }
                    onUpdate?.invoke()
                }
            Sensor.TYPE_STEP_DETECTOR -> {
                engine.onStep(tMs); tacEngine.onStep(tMs); cellIdEngine.onStep(tMs)
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        running = false
        h.removeCallbacks(pollCells)
        sm.unregisterListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telCallback != null) {
            try { tm.unregisterTelephonyCallback(telCallback as TelephonyCallback) } catch (_: Exception) {}
        }
        say("SESSION END  -> ${logger?.name}")
        logger?.event(System.currentTimeMillis(), "SESSION_END")
        logger?.close()
        oldLog?.event(System.currentTimeMillis(), "SESSION_END"); oldLog?.close()
        newLog?.event(System.currentTimeMillis(), "SESSION_END"); newLog?.close()
        cellIdLog?.event(System.currentTimeMillis(), "SESSION_END"); cellIdLog?.close()
        towerLog?.event(System.currentTimeMillis(), "SESSION_END"); towerLog?.close()
        trackerLog?.event(System.currentTimeMillis(), "SESSION_END"); trackerLog?.close()
        onUpdate?.invoke()
        super.onDestroy()
    }

    /** Swiped from recents = app closed: stop everything (manifest also sets stopWithTask). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        logger?.event(System.currentTimeMillis(), "APP_CLOSED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CH, "Tracking", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setContentTitle("Train tracker recording")
            .setContentText("Tracking stops if you close the app")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
