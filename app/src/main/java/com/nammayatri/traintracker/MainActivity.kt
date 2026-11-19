package com.nammayatri.traintracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    // Physical events the rider taps as they happen. These are the labels - the only
    // thing that cannot be reconstructed after the ride.
    private val marks = listOf(
        "ENTERED_STATION", "ON_PLATFORM", "DOORS_CLOSED",
        "TRAIN_MOVED", "PASSED_STATION", "DOORS_OPEN", "STEPPED_OFF"
    )

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var feedView: TextView
    private lateinit var cellPanel: TextView
    private var panelAtMs = 0L
    private var panelReadMs = -1L
    private lateinit var toggle: Button
    private lateinit var originSpinner: Spinner
    private lateinit var destSpinner: Spinner
    private val h = Handler(Looper.getMainLooper())
    private var visible = false
    /** Exactly ONE refresh loop, 1/s, only while this screen is visible. The old code
     *  started a new self-repeating loop on every service update (~1/s), so after 23 min
     *  on screen ~1400 loops ran at once: 22 min of CPU, 51 mAh (batterystats, 11 Sep). */
    private val uiLoop = object : Runnable {
        override fun run() {
            tickUi()
            h.removeCallbacks(this)
            if (visible && TrackingService.running) h.postDelayed(this, 1000)
        }
    }
    private fun refreshSoon() { h.removeCallbacks(uiLoop); h.post(uiLoop) }
    private var map: StationMap? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        map = StationMap(this)
        if (TrackingService.tacMap == null) TrackingService.tacMap = TacMap(this)
        val tm = TrackingService.tacMap!!
        val lineName = tm.line.ifEmpty { map?.firstLine() ?: "" }
        val stations = tm.stops

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#101418"))
        }

        root.addView(TextView(this).apply {
            text = if (stations.isEmpty()) "no station map bundled" else lineName
            setTextColor(Color.parseColor("#9AA6B2")); textSize = 12f
        })

        val names = if (stations.isEmpty()) listOf("—") else stations.map { it.name }
        fun spinner() = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, names)
        }
        val legRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val half = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val fromCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "FROM"; textSize = 10f; setTextColor(Color.parseColor("#6B7681")) })
        }
        val toCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "TO"; textSize = 10f; setTextColor(Color.parseColor("#6B7681")) })
        }
        originSpinner = spinner(); destSpinner = spinner()
        destSpinner.setSelection((names.size - 1).coerceAtLeast(0))
        fromCol.addView(originSpinner); toCol.addView(destSpinner)
        legRow.addView(fromCol, half); legRow.addView(toCol, half)
        root.addView(legRow)

        toggle = Button(this).apply {
            text = "START RECORDING"; textSize = 18f
            setOnClickListener { onToggle(lineName) }
        }
        root.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply {
            textSize = 30f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#7CE38B"))
            gravity = Gravity.CENTER
            minLines = 1; maxLines = 1
            setPadding(0, 12, 0, 4)
        }
        root.addView(status)

        detail = TextView(this).apply {
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#9AA6B2"))
            // Fixed height so the ground-truth buttons never move: tapping without
            // looking on a moving train must always land on the same button.
            minLines = 5; maxLines = 5
            setPadding(0, 0, 0, 8)
        }
        root.addView(detail)

        val grid = GridLayout(this).apply { columnCount = 2 }
        for (m in marks) {
            val btn = Button(this).apply {
                text = m.replace('_', '\n'); textSize = 14f
                setOnClickListener {
                    if (!TrackingService.running) { toast("not recording"); return@setOnClickListener }
                    TrackingService.mark(m); toast("✓ $m")
                }
            }
            grid.addView(btn, GridLayout.LayoutParams().apply {
                width = 0; height = 165
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(6, 6, 6, 6)
            })
        }
        root.addView(grid)

        root.addView(TextView(this).apply {
            text = "cell towers  (live, 5 s)"
            setTextColor(Color.parseColor("#6B7681")); textSize = 11f
            setPadding(0, 14, 0, 4)
        })
        cellPanel = TextView(this).apply {
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#C7D1DB"))
            setBackgroundColor(Color.parseColor("#0A0D10"))
            setPadding(12, 10, 12, 10)
            minLines = 8   // grows to list every cell; the tap buttons sit above, so they never move
        }
        root.addView(cellPanel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "live log  (also written to file)"
            setTextColor(Color.parseColor("#6B7681")); textSize = 11f
            setPadding(0, 14, 0, 4)
        })
        feedView = TextView(this).apply {
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#C7D1DB"))
            setBackgroundColor(Color.parseColor("#0A0D10"))
            setPadding(12, 12, 12, 12)
        }
        root.addView(feedView, LinearLayout.LayoutParams(MATCH_PARENT, 620))

        setContentView(ScrollView(this).apply { addView(root) })
        requestPerms()
        tickUi()
    }

    private fun onToggle(lineName: String) {
        val i = Intent(this, TrackingService::class.java)
        if (TrackingService.running) stopService(i)
        else {
            val o = originSpinner.selectedItemPosition.coerceAtLeast(0)
            val d = destSpinner.selectedItemPosition.coerceAtLeast(0)
            if (o == d) { toast("pick different from/to stations"); return }
            TrackingService.line = lineName
            TrackingService.originIndex = o
            TrackingService.destIndex = d
            TrackingService.meta = "$lineName ${originSpinner.selectedItem}->${destSpinner.selectedItem}"
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
                // No longer fatal (the service is no longer type "location"), but the
                // session would record zero cell/TAC data - worth knowing before a ride,
                // not after pulling the trace.
                toast("Location permission off: recording accelerometer only, no cell data")
                requestPerms()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }
        h.postDelayed({ refreshSoon() }, 400)
    }

    private fun tickUi() {
        val on = TrackingService.running
        toggle.setIfChanged(if (on) "STOP RECORDING" else "START RECORDING")
        val t = TrackingService.lastTick
        val c = TrackingService.lastCell

        val tr = TrackingService.tracker
        val tm = TrackingService.tacMap
        fun mmss(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)
        val eta = tr?.eta(System.currentTimeMillis())

        val statusText: String = when {
            !on -> "IDLE"
            tr == null -> "WAITING FOR CELL"
            tr.wrongDirection -> "WRONG DIRECTION"
            tr.done && tr.cur == tr.dest -> "ARRIVING ${tm?.name(tr.dest)}"
            tr.done -> "MISSED STOP"
            tr.checkIn == TacTracker.CheckIn.NONE -> "AT ${tm?.name(tr.origin)}"
            tr.checkIn == TacTracker.CheckIn.PROVISIONAL -> "BOARDED?"
            tr.nextIndex == tr.dest -> "NEXT: YOUR STOP"
            else -> "ON TRAIN ${kotlin.math.abs(tr.cur - tr.origin)}/${tr.totalStops}"
        }
        status.setIfChanged(statusText)
        status.setTextColor(when {
            !on || tr == null -> Color.GRAY
            tr.wrongDirection || (tr.done && tr.cur != tr.dest) -> Color.parseColor("#FF6B6B")
            tr.done -> Color.parseColor("#FF6B6B")
            tr.nextIndex == tr.dest && tr.checkIn == TacTracker.CheckIn.CONFIRMED -> Color.parseColor("#FFA94D")
            tr.checkIn == TacTracker.CheckIn.CONFIRMED -> Color.parseColor("#7CE38B")
            else -> Color.parseColor("#E3C57C")
        })

        val detailText: String = if (tr == null || tm == null) "no samples yet\n${cellLine(c)}\n\n\n" else buildString {
            val ci = when (tr.checkIn) {
                TacTracker.CheckIn.NONE -> "not yet"
                TacTracker.CheckIn.PROVISIONAL -> "provisional"
                TacTracker.CheckIn.CONFIRMED -> if (tr.boardedAtMs > 0) "confirmed, boarded " +
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(tr.boardedAtMs)) else "confirmed"
            }
            append((if (tr.mapFree) "MAP-FREE" else "MAP") + "  op ${TrackingService.trackPlmn.ifEmpty { "?" }}" +
                   (if (TrackingService.registeredPlmns.size > 1) "  (SIMs: ${TrackingService.registeredPlmns.joinToString("+")})" else "") + "\n")
            append("at ${tm.name(tr.cur)}  tac=${tr.lastTac}  check-in: $ci\n")
            val nx = tr.nextIndex
            append(if (nx == null || tr.done) "next: -\n"
                   else "next: ${tm.name(nx)}" + (if (eta != null) "  in ${mmss(eta.first)}" else "  (waiting to depart)") + "\n")
            val left = kotlin.math.abs(tr.dest - tr.cur)
            append("dest: ${tm.name(tr.dest)}" +
                   (if (eta != null) "  in ${mmss(eta.second)}" else "") + "  ($left stop${if (left == 1) "" else "s"} left)\n")
            append(if (t == null) "accel: -" else
                   "accel: %s floor=%.3f steps=%d".format(t.state, t.floor, t.stepsThisSec))
        }

        detail.setIfChanged(detailText)
        feedView.setIfChanged(synchronized(TrackingService.feed) { TrackingService.feed.joinToString("\n") })

        val now = System.currentTimeMillis()
        if (now - panelAtMs >= 5_000 || TrackingService.lastCellsAtMs != panelReadMs) {
            panelAtMs = now; panelReadMs = TrackingService.lastCellsAtMs
            cellPanel.setIfChanged(cellPanelText(now))
        }
    }

    private fun TextView.setIfChanged(v: CharSequence) { if (text.toString() != v.toString()) text = v }

    /** Everything the phone reports right now: SIMs, every registered cell, every neighbour, all fields.
     *  "?" marks values seen to be placeholders on this phone (RSRP -44, timing advance 1282). */
    private fun cellPanelText(now: Long): String {
        val cells = TrackingService.lastCells
        val sims = TrackingService.lastSims
        if (cells.isEmpty()) return "no cell reading yet" +
            (if (!TrackingService.running) "  (not recording)" else "  (location permission?)")
        fun v(x: Int) = if (x == NA) "-" else x.toString()
        fun rsrpS(x: Int) = if (x == -44) "-44?" else v(x)
        fun taS(x: Int) = if (x == 1282) "1282?" else v(x)
        fun ageS(ms: Long) = if (ms < 0) "-" else if (ms < 1000) "<1s" else "${ms / 1000}s"
        val tm = TrackingService.tacMap
        val tracked = TrackingService.servingOf(cells)
        val sb = StringBuilder()
        val age = (now - TrackingService.lastCellsAtMs) / 1000
        sb.append("updated ${age}s ago (${TrackingService.lastCellsSrc})   ${cells.size} cells, " +
                  "${cells.count { it.registered }} registered\n")
        if (sims.isEmpty()) sb.append("SIMs: -\n")
        for (sim in sims) sb.append("SIM${sim.slot} ${sim.carrier.ifEmpty { "?" }}  op ${sim.plmn.ifEmpty { "-" }}  " +
            "${sim.netType}" + (if (sim.dataSim) "  DATA" else "") + (if (sim.roaming) "  ROAMING" else "") +
            (if (sim.plmn == TrackingService.trackPlmn && sim.plmn.isNotEmpty()) "  [tracked]" else "") + "\n")

        fun block(c: CellSample, tag: String) {
            val onMap = if (c.registered && tm != null && tm.covers(c.plmn)) tm.indexOfTac(c.tac)?.let { " -> ${tm.name(it)}" } ?: "" else ""
            val ids = if (c.idKnown) {
                val enb = if (c.type == "LTE") "  enb ${c.cellId shr 8}  site ${(c.cellId shr 8) % 100000}" else ""
                "cid ${c.cellId}$enb  tac ${v(c.tac)}$onMap"
            } else "id hidden"
            sb.append("$tag ${c.type} ${if (c.plmn.isEmpty()) "" else "op ${c.plmn}  "}$ids\n")
            sb.append("    pci ${v(c.pci)}  ch ${v(c.arfcn)}" + (if (c.bands.isNotEmpty()) "  band ${c.bands}" else "") +
                      (if (c.bwKhz != NA && c.bwKhz > 0) "  bw ${c.bwKhz / 1000}MHz" else "") + "  age ${ageS(c.ageMs)}\n")
            sb.append("    rsrp ${rsrpS(c.rsrp)}  rsrq ${v(c.rsrq)}  rssi ${v(c.rssi)}  snr ${v(c.snr)}" +
                      "  cqi ${v(c.cqi)}  ta ${taS(c.ta)}" + (if (c.extra.isNotEmpty()) "  ${c.extra}" else "") + "\n")
        }
        val registered = cells.filter { it.registered }
        val neighbours = cells.filter { !it.registered }
            .sortedByDescending { if (it.rsrp == NA) Int.MIN_VALUE else it.rsrp }
        for (c in registered) block(c, if (c === tracked) "* SERVING" else "+ REGISTERED")
        if (neighbours.isNotEmpty()) sb.append("-- ${neighbours.size} neighbour${if (neighbours.size == 1) "" else "s"} --\n")
        for (c in neighbours) block(c, " ")
        return sb.toString().trimEnd()
    }

    private fun cellLine(c: CellSample?) =
        if (c == null) "cell: -" else "cell ${c.cellId} tac=${c.tac} ${c.rsrp}dBm"

    private fun requestPerms() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) need.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onResume() { super.onResume(); visible = true; TrackingService.onUpdate = null; refreshSoon() }
    override fun onPause() { super.onPause(); visible = false; h.removeCallbacks(uiLoop) }
}
