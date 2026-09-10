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
    private lateinit var toggle: Button
    private lateinit var originSpinner: Spinner
    private lateinit var destSpinner: Spinner
    private val h = Handler(Looper.getMainLooper())
    private var map: StationMap? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        map = StationMap(this)
        val lineName = map?.firstLine() ?: ""
        val stations = map?.stations(lineName) ?: emptyList()

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
            minLines = 4; maxLines = 4
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }
        h.postDelayed({ tickUi() }, 400)
    }

    private fun tickUi() {
        val on = TrackingService.running
        toggle.text = if (on) "STOP RECORDING" else "START RECORDING"
        val t = TrackingService.lastTick
        val c = TrackingService.lastCell

        val nextName = t?.let { TrackingService.stationFor(it.stopCount + 1) } ?: ""
        val exp = TrackingService.expectedStops
        val arrived = t != null && TrackingService.arrived(t.stopCount)
        val alightNext = t != null && TrackingService.alightNext(t.stopCount)
        status.text = when {
            !on -> "IDLE"
            t == null -> "WARMING UP"
            t.exited -> "EXITED"
            arrived -> "ALIGHT NOW"
            alightNext -> "NEXT STOP: OFF"
            t.checkedIn -> "ON TRAIN · ${t.stopCount}/$exp"
            else -> t.state.name
        }
        status.setTextColor(when {
            !on -> Color.GRAY
            t?.exited == true -> Color.parseColor("#9AA6B2")
            arrived -> Color.parseColor("#FF6B6B")
            alightNext -> Color.parseColor("#FFA94D")
            t?.checkedIn == true -> Color.parseColor("#7CE38B")
            else -> Color.parseColor("#E3C57C")
        })

        detail.text = if (t == null) "no samples yet\n${cellLine(c)}\n\n" else buildString {
            append("floor=%.4f thr=%.4f  %s dwell=%ds\n".format(t.floor, t.threshold, t.state, t.dwellSec))
            append("steps=%d sinceStep=%ds  n=%d/s%s\n".format(
                t.stepsThisSec, t.secSinceStep, t.samples, if (t.alighting) "  ALIGHTING?" else ""))
            val cor = when (t.corroboration) { 1 -> "cells:+" ; -1 -> "cells:CONTRA" ; else -> "cells:?" }
            append(cellLine(c)).append("  fp=${TrackingService.fingerprintSize()}  $cor\n")
            val left = exp - t.stopCount
            append(when {
                !t.checkedIn -> "leg: ${TrackingService.originName()} -> ${TrackingService.destName()}"
                nextName.isNotEmpty() -> "next: $nextName" + if (left > 0) "   ($left to go)" else ""
                else -> "log: ${TrackingService.logger?.name ?: "-"}"
            })
        }

        feedView.text = synchronized(TrackingService.feed) { TrackingService.feed.joinToString("\n") }
        if (on) h.postDelayed({ tickUi() }, 1000)
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
    override fun onResume() { super.onResume(); TrackingService.onUpdate = { h.post { tickUi() } }; tickUi() }
    override fun onPause() { super.onPause(); TrackingService.onUpdate = null }
}
