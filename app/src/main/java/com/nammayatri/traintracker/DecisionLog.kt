package com.nammayatri.traintracker

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A lightweight, decision-events-only JSONL log - one per detector algorithm, so two
 * algorithms running in parallel on the same ride each produce their own file and can
 * be diffed afterward without filtering a merged stream.
 */
class DecisionLog(ctx: Context, algoName: String, stamp: String) {
    private val file: File
    private var writer: FileWriter?

    init {
        val dir = File(ctx.getExternalFilesDir(null), "traces")
        dir.mkdirs()
        file = File(dir, "decisions-$algoName-$stamp.jsonl")
        writer = FileWriter(file, true)
    }

    val name: String get() = file.name

    @Synchronized fun event(tsMs: Long, label: String, extraJson: String = "") {
        val extra = if (extraJson.isEmpty()) "" else ",$extraJson"
        try {
            writer?.append("""{"ts":$tsMs,"label":"$label"$extra}""")?.append('\n')?.flush()
        } catch (_: Exception) {}
    }

    fun close() { try { writer?.close() } catch (_: Exception) {}; writer = null }
}
