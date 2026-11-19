package com.nammayatri.traintracker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.*

/** Android reports "not available" as Int.MAX_VALUE (CellInfo.UNAVAILABLE). */
const val NA = Int.MAX_VALUE

data class CellSample(
    val type: String, val cellId: Long, val tac: Int, val rsrp: Int, val registered: Boolean,
    val pci: Int = NA,      // physical cell id (LTE/NR), PSC (WCDMA), BSIC (GSM)
    val arfcn: Int = NA,    // channel: EARFCN / NR-ARFCN / UARFCN / ARFCN - tells the band apart
    val rsrq: Int = NA,     // signal quality, dB
    val snr: Int = NA,      // RSSNR (LTE) / SS-SINR (NR), dB
    val ta: Int = NA,       // timing advance: LTE units of ~78 m to the serving tower
    val plmn: String = "",  // operator, e.g. "40440" = Airtel TN. The TAC map is per operator.
    val rssi: Int = NA,     // received power incl. noise, dBm
    val cqi: Int = NA,      // channel quality indicator (LTE)
    val bands: String = "", // frequency bands the modem reports, e.g. "40"
    val bwKhz: Int = NA,    // LTE channel bandwidth
    val ageMs: Long = -1L,  // how old the modem's measurement is (API 30+); cached readings show up here
    val extra: String = ""  // type-specific extras (NR CSI, GSM/WCDMA quality) as "k=v k=v"
) {
    /** Neighbours on this phone come back as 268435455 / TAC 65535: identity withheld. */
    val idKnown get() = cellId > 0 && cellId != 268435455L && cellId != Long.MAX_VALUE
    val tacKnown get() = tac in 1..65534
}

/**
 * Reads serving + neighbour cells. On Android 10+ getAllCellInfo() can return CACHED
 * data, so the push callback in TrackingService is the primary trigger and this read
 * is also used as a 5 s heartbeat. TAC is the field the tracker runs on; the rest are
 * logged and shown live so they can be evaluated on real rides.
 */
class TelephonyReader(private val ctx: Context) {
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    /** One active SIM: who it is, what network it is on, whether it carries mobile data. */
    data class SimInfo(val slot: Int, val subId: Int, val carrier: String, val plmn: String,
                       val netType: String, val dataSim: Boolean, val roaming: Boolean)

    @SuppressLint("MissingPermission")
    fun sims(): List<SimInfo> = try {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val dataId = SubscriptionManager.getDefaultDataSubscriptionId()
        (sm?.activeSubscriptionInfoList ?: emptyList()).map { si ->
            val t = tm.createForSubscriptionId(si.subscriptionId)
            SimInfo(si.simSlotIndex + 1, si.subscriptionId,
                (t.networkOperatorName ?: "").ifEmpty { si.carrierName?.toString() ?: "" },
                t.networkOperator ?: "",
                netTypeName(try { t.dataNetworkType } catch (_: Exception) { TelephonyManager.NETWORK_TYPE_UNKNOWN }),
                si.subscriptionId == dataId, t.isNetworkRoaming)
        }
    } catch (_: Exception) { emptyList() }

    @SuppressLint("MissingPermission")
    fun read(): List<CellSample> {
        val out = ArrayList<CellSample>()
        val infos = try { tm.allCellInfo } catch (_: SecurityException) { null } catch (_: Exception) { null }
            ?: return out
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        for (info in infos) {
            val reg = info.isRegistered
            val age = if (Build.VERSION.SDK_INT >= 30) nowElapsed - info.timestampMillis else -1L
            try {
                when (info) {
                    is CellInfoLte -> {
                        val id = info.cellIdentity; val ss = info.cellSignalStrength
                        out.add(CellSample("LTE", id.ci.toLong(), id.tac, ss.dbm, reg,
                            pci = id.pci, arfcn = id.earfcn, rsrq = ss.rsrq, snr = ss.rssnr,
                            ta = ss.timingAdvance, plmn = plmn(id.mccStr(), id.mncStr()),
                            rssi = if (Build.VERSION.SDK_INT >= 29) ss.rssi else NA, cqi = ss.cqi,
                            bands = if (Build.VERSION.SDK_INT >= 30) id.bands.joinToString(",") else "",
                            bwKhz = if (Build.VERSION.SDK_INT >= 28) id.bandwidth else NA, ageMs = age))
                    }
                    is CellInfoNr -> {
                        val id = info.cellIdentity as? CellIdentityNr
                        val ss = info.cellSignalStrength as? CellSignalStrengthNr
                        out.add(CellSample("NR", id?.nci ?: -1L, id?.tac ?: NA, ss?.dbm ?: NA, reg,
                            pci = id?.pci ?: NA, arfcn = id?.nrarfcn ?: NA,
                            rsrq = ss?.ssRsrq ?: NA, snr = ss?.ssSinr ?: NA,
                            plmn = plmn(id?.mccString, id?.mncString),
                            bands = if (Build.VERSION.SDK_INT >= 30) id?.bands?.joinToString(",") ?: "" else "",
                            ageMs = age,
                            extra = if (ss == null) "" else "csiRsrp=${nv(ss.csiRsrp)} csiRsrq=${nv(ss.csiRsrq)} csiSinr=${nv(ss.csiSinr)}"))
                    }
                    is CellInfoGsm -> {
                        val id = info.cellIdentity; val ss = info.cellSignalStrength
                        out.add(CellSample("GSM", id.cid.toLong(), id.lac, ss.dbm, reg,
                            pci = id.bsic, arfcn = id.arfcn,
                            ta = if (Build.VERSION.SDK_INT >= 26) ss.timingAdvance else NA,
                            plmn = plmn(id.mccStr(), id.mncStr()),
                            rssi = if (Build.VERSION.SDK_INT >= 30) ss.rssi else NA, ageMs = age,
                            extra = "ber=${nv(ss.bitErrorRate)}"))
                    }
                    is CellInfoWcdma -> {
                        val id = info.cellIdentity
                        val ss = info.cellSignalStrength
                        out.add(CellSample("WCDMA", id.cid.toLong(), id.lac, ss.dbm, reg,
                            pci = id.psc, arfcn = id.uarfcn, plmn = plmn(id.mccStr(), id.mncStr()), ageMs = age,
                            extra = if (Build.VERSION.SDK_INT >= 30) "ecNo=${nv(ss.ecNo)}" else ""))
                    }
                }
            } catch (_: Exception) { /* one odd cell must not drop the whole reading */ }
        }
        return out
    }

    fun serving(cells: List<CellSample>): CellSample? = cells.firstOrNull { it.registered && it.idKnown }

    private fun nv(v: Int) = if (v == NA) "-" else v.toString()

    private fun netTypeName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "WiFi-calling"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "none"
        else -> "type$t"
    }

    private fun plmn(mcc: String?, mnc: String?) = if (mcc.isNullOrEmpty() || mnc.isNullOrEmpty()) "" else mcc + mnc
    private fun CellIdentityLte.mccStr() = if (Build.VERSION.SDK_INT >= 28) mccString else null
    private fun CellIdentityLte.mncStr() = if (Build.VERSION.SDK_INT >= 28) mncString else null
    private fun CellIdentityGsm.mccStr() = if (Build.VERSION.SDK_INT >= 28) mccString else null
    private fun CellIdentityGsm.mncStr() = if (Build.VERSION.SDK_INT >= 28) mncString else null
    private fun CellIdentityWcdma.mccStr() = if (Build.VERSION.SDK_INT >= 28) mccString else null
    private fun CellIdentityWcdma.mncStr() = if (Build.VERSION.SDK_INT >= 28) mncString else null
}

/** JSON for one tower, "null" for anything Android reported as unavailable. */
fun CellSample.toJson(): String {
    fun n(v: Int) = if (v == NA) "null" else v.toString()
    // LTE cell id = 20-bit base station (eNodeB) id + 8-bit sector. Airtel's site id looked
    // stable per station across rides ((enb % 100000): 98% lookup vs 31% for cell id).
    val enb = if (type == "LTE" && idKnown) ",\"enb\":${cellId shr 8}" else ""
    return "{\"type\":\"$type\",\"cid\":$cellId$enb,\"tac\":${n(tac)},\"rsrp\":${n(rsrp)},\"reg\":$registered," +
        "\"pci\":${n(pci)},\"arfcn\":${n(arfcn)},\"rsrq\":${n(rsrq)},\"snr\":${n(snr)},\"ta\":${n(ta)}," +
        "\"plmn\":\"$plmn\",\"rssi\":${n(rssi)},\"cqi\":${n(cqi)},\"bands\":\"$bands\",\"bwKhz\":${n(bwKhz)}," +
        "\"ageMs\":$ageMs,\"extra\":\"$extra\"}"
}
