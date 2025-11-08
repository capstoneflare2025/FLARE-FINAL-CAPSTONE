package com.example.flare_capstone

data class ReportNotif(
    val reportType: String? =null,
    val mapLink: String? = null,
    val location: String? = null,
    val emergencyType: String? = null,// Pending | Ongoing | Completed | Received
    val date: String? = null,         // may be DD/MM/YYYY or MM/DD/YYYY
    val time: String? = null,       // may be 12h/24h
)
