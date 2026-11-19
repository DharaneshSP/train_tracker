package com.nammayatri.traintracker

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** JSONL trace writer. One file per session, in app external files dir. */
class TraceLogger(ctx: Context, meta: String) {
    private val file: File
    private var writer: FileWriter? = null

    init {
        val dir = File(ctx.getExternalFilesDir(null), "traces")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "trace-$stamp.jsonl")
        writer = FileWriter(file, true)
        line("""{"type":"meta","ts":${System.currentTimeMillis()},"info":${q(meta)},"device":${q(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)},"sdk":${android.os.Build.VERSION.SDK_INT}}""")
    }

    val path: String get() = file.absolutePath
    val name: String get() = file.name

    @Synchronized fun line(s: String) {
        try { writer?.append(s)?.append('\n')?.flush() } catch (_: Exception) {}
    }

    fun tick(t: Tick) = line(
        """{"type":"tick","ts":${t.tMs},"n":${t.samples},"energy":${f(t.energy)},"floor":${f(t.floor)},""" +
        """"thr":${f(t.threshold)},"ref":${f(t.movingRef)},"state":"${t.state}","stops":${t.stopCount},""" +
        """"checkedIn":${t.checkedIn},"dwell":${t.dwellSec},"moveRun":${t.movingRunSec},""" +
        """"steps":${t.stepsThisSec},"sinceStep":${t.secSinceStep},"alighting":${t.alighting},"exited":${t.exited},"corrob":${t.corroboration},"stopsRejected":${t.stopsRejected}}"""
    )

    fun cells(ts: Long, cells: List<CellSample>) {
        val arr = cells.joinToString(",") { it.toJson() }
        line("""{"type":"cells","ts":$ts,"towers":[$arr]}""")
    }

    fun event(ts: Long, label: String, extra: String = "") =
        line("""{"type":"event","ts":$ts,"label":${q(label)}${if (extra.isEmpty()) "" else ",$extra"}}""")

    fun close() { try { writer?.close() } catch (_: Exception) {}; writer = null }

    private fun f(d: Double) = if (d.isNaN() || d.isInfinite()) "0" else String.format(Locale.US, "%.6f", d)
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
