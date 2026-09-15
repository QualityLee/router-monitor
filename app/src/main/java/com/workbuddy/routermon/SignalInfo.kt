package com.workbuddy.routermon

/**
 * 路由器抓取到的实时信息
 */
data class SignalInfo(
    var rssi: String = "--",       // 信号强度(原始字符串,带单位)
    var rsrp: String = "--",       // 暂未在 G805 webUI 中暴露,留空
    var rsrq: String = "--",
    var sinr: String = "--",
    var networkMode: String = "--",
    var operator: String = "--",
    var iccid: String = "--",
    var imei: String = "--",
    var imsi: String = "--",
    var runTime: String = "--",
    var connected: Boolean = false,
    var rawHtml: String = ""       // 给调试用
)