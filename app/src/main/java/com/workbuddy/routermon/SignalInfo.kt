package com.workbuddy.routermon

/**
 * 从路由器 webUI 抓取到的实时信息
 */
data class SignalInfo(
    var rssi: String = "--",        // 信号强度 (dBm)
    var rsrp: String = "--",        // RSRP (dBm)
    var rsrq: String = "--",        // RSRQ (dB)
    var sinr: String = "--",        // SINR (dB)
    var networkMode: String = "--", // 网络制式
    var operator: String = "--",    // 运营商
    var iccid: String = "--",       // SIM 卡卡号
    var imei: String = "--",
    var imsi: String = "--",
    var runTime: String = "--",
    var connected: Boolean = false,
    var rawHtml: String = "",       // 抓到的原始页面(诊断用)
    var sourceUrl: String = ""      // 命中的页面地址
) {
    fun hasData(): Boolean =
        isReal(iccid) || isReal(imei) || isReal(imsi) || isReal(rssi) || isReal(rsrp)

    fun hasKeyData(): Boolean = isReal(iccid) || isReal(rssi) || isReal(rsrp)

    fun summary(): String {
        val sb = StringBuilder()
        if (isReal(rssi)) sb.append("信号=").append(rssi).append("  ")
        if (isReal(rsrp)) sb.append("RSRP=").append(rsrp).append("  ")
        if (isReal(rsrq)) sb.append("RSRQ=").append(rsrq).append("  ")
        if (isReal(sinr)) sb.append("SINR=").append(sinr).append("  ")
        if (isReal(networkMode)) sb.append("制式=").append(networkMode).append("  ")
        if (isReal(operator)) sb.append("运营商=").append(operator).append("  ")
        if (isReal(iccid)) sb.append("ICCID=").append(iccid).append("  ")
        if (isReal(imei)) sb.append("IMEI=").append(imei).append("  ")
        if (isReal(imsi)) sb.append("IMSI=").append(imsi)
        return sb.toString().trim().ifEmpty { "未解析到任何字段" }
    }

    companion object {
        fun isReal(v: String): Boolean {
            val s = v.trim()
            return s.isNotEmpty() && s != "--" && s != "-"
        }
    }
}
