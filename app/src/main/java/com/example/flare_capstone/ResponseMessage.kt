package com.example.flare_capstone

data class ResponseMessage(
    var uid: String = "",
    var stationNode: String? = null,              // e.g., "LaFilipinaFireStation"
    var fireStationName: String? = null,
    var incidentId: String? = null,
    var reporterName: String? = null,
    var contact: String? = null,
    var responseMessage: String? = null,
    var responseDate: String = "1970-01-01",
    var responseTime: String = "00:00:00",
    var imageBase64: String? = null,
    var timestamp: Long? = 0L,

    // 🔹 Determines who sent the message: "station" or "reply"
    var type: String? = null,

    // 🔹 Firebase read flag
    var isRead: Boolean = false,

    // 🔹 Report category (fire / other / ems / sms)
    var category: String? = null
) {
    fun getIsRead(): Boolean = isRead
    fun setIsRead(isRead: Boolean) { this.isRead = isRead }
}
