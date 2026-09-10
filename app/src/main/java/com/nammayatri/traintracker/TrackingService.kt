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
        var logger: TraceLogger? = null; private set
        var onUpdate: (() -> Unit)? = null
        var meta: String = ""
        var line: String = ""
        var originIndex: Int = 0
        var destIndex: Int = 0
        var map: StationMap? = null

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
    private val engine = SensorEngine()
    init { engineRef = engine }
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
        val cells = tel.read()
        if (cells.isEmpty()) return
        val now = System.currentTimeMillis()
        logger?.cells(now, cells)
        noteCells(cells, now)
        val s = tel.serving(cells) ?: return
        lastCell = s
        anchor(s.cellId, now)
        if (s.cellId != prevServing) {
            prevServing = s.cellId
            val known = map?.cellToStation?.get(s.cellId)
            say("CELL ${s.cellId}  tac=${s.tac}  ${s.rsrp}dBm" +
                (if (known != null) "   [$known]" else "") + "   ($src)")
        }
        onUpdate?.invoke()
    }

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepDet = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        tel = TelephonyReader(this)
        tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (map == null) map = StationMap(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        logger = TraceLogger(this, meta)
        startForeground(1, notif())

        // 20 Hz is ample - we measure an energy floor, not a spectrum. maxReportLatency
        // lets the sensor hub FIFO batch samples so the CPU wakes rarely.
        accel?.let { sm.registerListener(this, it, 50_000, 5_000_000) }
        stepDet?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

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
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (bootMs == 0L) bootMs = System.currentTimeMillis() - e.timestamp / 1_000_000L
        val tMs = bootMs + e.timestamp / 1_000_000L
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                engine.onAccel(e.values[0], e.values[1], e.values[2], tMs)?.let { t ->
                    recomputeCorroboration(System.currentTimeMillis())
                    lastTick = t
                    logger?.tick(t)
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
                        say("CHECK-IN  (boarded ~${(t.tMs - engine.boardedAtMs) / 1000}s ago)  [$how]")
                        logger?.event(System.currentTimeMillis(), "DETECTED_CHECKIN",
                            """"cellConfirmed":$nearOrigin,"corroboration":${t.corroboration},""" +
                            """"departedOriginCell":$departedOriginCell,""" +
                            """"mapCoverage":$matchedAnyMapCell,""" +
                            """"origin":"${originName()}","boardedAtMs":${engine.boardedAtMs}""")
                    }
                    if (t.stopCount != prevStops) {
                        prevStops = t.stopCount
                        val nm = stationFor(t.stopCount)
                        val of = if (expectedStops > 0) "/$expectedStops" else ""
                        say("STOP #${t.stopCount}$of" + if (nm.isNotEmpty()) "  ->  $nm" else "")
                        // The alert fires one stop early, giving a full inter-station run
                        // of warning. When confidence is low, early beats missed.
                        if (alightNext(t.stopCount) && !alertedNext) {
                            alertedNext = true
                            say("*** GET OFF NEXT STOP -> ${destName()} ***")
                        }
                        if (arrived(t.stopCount) && !alertedArrived) {
                            alertedArrived = true
                            say("*** ARRIVED: ${destName()} - alight now ***")
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
            Sensor.TYPE_STEP_DETECTOR -> engine.onStep(tMs)
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
        onUpdate?.invoke()
        super.onDestroy()
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
            .setContentText("accel 20Hz + cells")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
