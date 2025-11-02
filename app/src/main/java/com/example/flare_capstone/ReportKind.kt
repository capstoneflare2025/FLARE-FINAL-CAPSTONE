package com.example.flare_capstone

enum class ReportKind { FIRE, OTHER, EMS, SMS, ALL }

data class Report(
    val id: String = "",
    val kind: ReportKind = ReportKind.FIRE,
    val location: String = "N/A",
    val status: String = "Unknown",   // Pending | Ongoing | Completed | Received
    val date: String? = "",           // may be DD/MM/YYYY or MM/DD/YYYY
    val time: String? = "",           // may be 12h/24h
    val timestamp: Long = 0L,         // prefer numeric if present
    val raw: Map<String, Any?> = emptyMap()
)
