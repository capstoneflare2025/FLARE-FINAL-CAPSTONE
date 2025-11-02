package com.example.flare_capstone

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.flare_capstone.databinding.ActivityFireFighterReportBinding
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.time.LocalDateTime
import java.time.ZoneOffset

class FireFighterReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFireFighterReportBinding
    private val db by lazy { FirebaseDatabase.getInstance().reference }
    private val adapter = FireFighterReportAdapter { report -> openDetails(report) }

    private var allReports: MutableList<Report> = mutableListOf()
    private var currentTab: ReportKind = ReportKind.ALL

    // Keep database refs so we can remove listeners later
    private val attachedRefs = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()

    // Email → station key
    private val emailToStation = mapOf(
        "tcwestfiresubstation@gmail.com" to "MabiniFireFighterAccount",
        "lafilipinafire@gmail.com"       to "LaFilipinaFireFighterAccount",
        "bfp_tagumcity@yahoo.com"        to "CanocotanFireFighterAccount"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFireFighterReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        // Toolbar
        binding.topBar.setNavigationOnClickListener { finish() }

        // Tabs
        val tl = binding.tabType
        listOf("All", "Fire", "Other", "EMS", "SMS").forEach { tl.addTab(tl.newTab().setText(it)) }
        tl.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = when (tab.position) {
                    1 -> ReportKind.FIRE
                    2 -> ReportKind.OTHER
                    3 -> ReportKind.EMS
                    4 -> ReportKind.SMS
                    else -> ReportKind.ALL
                }
                applyFilters()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Status dropdown
        binding.dropStatus.setSimpleItems(arrayOf("Any", "Pending", "Ongoing", "Completed", "Received"))
        binding.dropStatus.setOnItemClickListener { _, _, _, _ -> applyFilters() }

        // Search
        binding.inputSearch.addTextChangedListener { applyFilters() }

        // Pull-to-refresh
        binding.swipe.setOnRefreshListener {
            attachRealtimeListeners(resolveStationKey(), forceReattach = true)
        }

        // Start
        attachRealtimeListeners(resolveStationKey())
    }

    override fun onDestroy() {
        super.onDestroy()
        detachAll()
    }

    private fun resolveStationKey(): String {
        val email = FirebaseAuth.getInstance().currentUser?.email?.lowercase().orEmpty()
        return emailToStation[email] ?: "CanocotanFireFighterAccount" // safe fallback
    }

    /** Attach live listeners for each collection under the station */
    private fun attachRealtimeListeners(stationKey: String, forceReattach: Boolean = false) {
        if (forceReattach) detachAll()

        allReports.clear()
        adapter.submitList(emptyList())
        binding.swipe.isRefreshing = true

        // Base: TagumCityCentralFireStation/FireFighter/AllFireFighterAccount/{station}/AllReport
        val base = "TagumCityCentralFireStation/FireFighter/AllFireFighterAccount/$stationKey/AllReport"

        fun hook(path: String, kind: ReportKind) {
            val ref = db.child(path)
            val l = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    // replace this kind with fresh set
                    allReports.removeAll { it.kind == kind }
                    for (c in s.children) {
                        val id = c.key ?: continue
                        @Suppress("UNCHECKED_CAST")
                        val m = (c.value as? Map<String, Any?>) ?: emptyMap()
                        allReports += mapToReport(id, kind, m)     // ✅ operator
                    }
                    // newest first
                    allReports.sortByDescending { it.timestamp }
                    applyFilters()
                    binding.swipe.isRefreshing = false
                }
                override fun onCancelled(e: DatabaseError) {
                    Toast.makeText(this@FireFighterReportActivity, e.message, Toast.LENGTH_SHORT).show()
                    binding.swipe.isRefreshing = false
                }
            }
            ref.addValueEventListener(l)
            attachedRefs += ref to l
        }

        hook("$base/FireReport", ReportKind.FIRE)
        hook("$base/OtherEmergencyReport", ReportKind.OTHER)
        hook("$base/EmergencyMedicalServicesReport", ReportKind.EMS)
        hook("$base/SmsReport", ReportKind.SMS)
    }

    private fun detachAll() {
        attachedRefs.forEach { (ref, l) ->
            try { ref.removeEventListener(l) } catch (_: Exception) {}
        }
        attachedRefs.clear()
    }

    private fun mapToReport(id: String, kind: ReportKind, raw: Map<String, Any?>): Report {
        fun s(key: String) = (raw[key] as? String).orEmpty()
        fun n(key: String) = (raw[key] as? Number)?.toLong()
        val location = s("exactLocation").ifBlank { s("location") }
        val status   = s("status").ifBlank { "Unknown" }
        val date     = s("date")
        val time     = s("reportTime").ifBlank { s("time") }
        val ts = n("timestamp") ?: n("createdAt") ?: n("updatedAt") ?: parseFallback(date, time)
        return Report(
            id = id,
            kind = kind,
            location = location.ifBlank { "N/A" },
            status = status,
            date = date,
            time = time,
            timestamp = ts,
            raw = raw
        )
    }

    /** Forgiving fallback (parses DD/MM/YYYY or MM/DD/YYYY + 12/24h) */
    private fun parseFallback(date: String?, time: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val t24 = to24h(time ?: "00:00")
        val parts = date.split("/")
        if (parts.size == 3) {
            val p1 = parts[0].padStart(2, '0')
            val p2 = parts[1].padStart(2, '0')
            val y  = if (parts[2].length == 2) "20${parts[2]}" else parts[2]
            // Try DMY then MDY
            return runCatching {
                LocalDateTime.parse("$y-$p2$t24:00")
            }.map { it.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrElse {
                runCatching {
                    LocalDateTime.parse("$y-$p1$t24:00")
                }.map { it.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0L)
            }
        }
        return 0L
    }

    private fun to24h(t: String): String {
        val re = Regex("""^(\d{1,2}):(\d{2})(?::\d{2})?\s*(AM|PM)?$""", RegexOption.IGNORE_CASE)
        val m = re.matchEntire(t.trim()) ?: return t
        var h = m.groupValues[1].toInt()
        val mm = m.groupValues[2]
        val ap = m.groupValues[3].uppercase()
        if (ap == "PM" && h != 12) h += 12
        if (ap == "AM" && h == 12) h = 0
        return "${h.toString().padStart(2, '0')}:$mm"
    }

    /** Filters: tab (type), status dropdown, and search text */
    private fun applyFilters() {
        val q = binding.inputSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val statusSel = binding.dropStatus.text?.toString().orEmpty() // Any | Pending | Ongoing | ...
        val filtered = allReports.asSequence()
            .filter { r -> currentTab == ReportKind.ALL || r.kind == currentTab }
            .filter { r -> statusSel.isBlank() || statusSel == "Any" || r.status.equals(statusSel, ignoreCase = true) }
            .filter { r -> q.isBlank() || r.location.lowercase().contains(q) }
            .toList()
        adapter.submitList(filtered)
    }

    private fun openDetails(r: Report) {
        // TODO: Launch a details screen/bottom sheet.
        Toast.makeText(this, "${r.kind}: ${r.location}\n${r.status} • ${r.date} ${r.time}", Toast.LENGTH_SHORT).show()
    }
}