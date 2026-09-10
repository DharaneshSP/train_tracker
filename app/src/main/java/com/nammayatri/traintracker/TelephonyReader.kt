package com.nammayatri.traintracker

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.*

data class CellSample(
    val type: String, val cellId: Long, val tac: Int, val rsrp: Int, val registered: Boolean
)

/**
 * Polls the serving cell. Note: on Android 10+ getAllCellInfo() returns CACHED data -
 * we log CellInfo.timeStamp staleness implicitly by polling and de-duping downstream.
 * Cell identity is the anchor that says WHICH station; the IMU says WHAT happened.
 */
class TelephonyReader(ctx: Context) {
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @SuppressLint("MissingPermission")
    fun read(): List<CellSample> {
        val out = ArrayList<CellSample>()
        val infos = try { tm.allCellInfo } catch (_: SecurityException) { null } catch (_: Exception) { null }
            ?: return out
        for (info in infos) {
            val reg = info.isRegistered
            when (info) {
                is CellInfoLte -> {
                    val id = info.cellIdentity
                    out.add(CellSample("LTE", id.ci.toLong(), id.tac, info.cellSignalStrength.dbm, reg))
                }
                is CellInfoNr -> {
                    val id = info.cellIdentity as? CellIdentityNr
                    out.add(CellSample("NR", id?.nci ?: -1L, id?.tac ?: -1, info.cellSignalStrength.dbm, reg))
                }
                is CellInfoGsm -> {
                    val id = info.cellIdentity
                    out.add(CellSample("GSM", id.cid.toLong(), id.lac, info.cellSignalStrength.dbm, reg))
                }
                is CellInfoWcdma -> {
                    val id = info.cellIdentity
                    out.add(CellSample("WCDMA", id.cid.toLong(), id.lac, info.cellSignalStrength.dbm, reg))
                }
            }
        }
        return out
    }

    fun serving(cells: List<CellSample>): CellSample? =
        cells.firstOrNull { it.registered && it.cellId > 0 && it.cellId != 268435455L }
}
