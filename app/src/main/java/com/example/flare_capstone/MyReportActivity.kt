package com.example.flare_capstone

import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.flare_capstone.databinding.ActivityMyReportBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class MyReportActivity : AppCompatActivity(), ReportAdapter.OnItemClickListener {

    private lateinit var binding: ActivityMyReportBinding
    private lateinit var adapter: ReportAdapter

    private val allReports = mutableListOf<Any>()
    private val filteredReports = mutableListOf<Any>()

    // ----- Filters -----
    private enum class TypeFilter { ALL, FIRE, OTHER, EMS, SMS }
    private enum class StatusFilter { ALL, PENDING, RESPONDING, RESOLVED }

    // Internal normalized category for filtering
    private enum class Category { FIRE, OTHER, EMS, SMS }

    private var typeFilter: TypeFilter = TypeFilter.ALL
    private var statusFilter: StatusFilter = StatusFilter.ALL
    private var dateFromMillis: Long? = null
    private var dateToMillis: Long? = null
    private var searchQuery: String = ""

    // ----- DB paths -----
    private val STATION_ROOT = "CapstoneFlare" // Root node for the stations
    private val STATIONS = listOf("CanocotanFireStation", "LaFilipinaFireStation", "MabiniFireStation")

    private var FIRE_PATH = ""
    private var OTHER_PATH = ""
    private var EMS_PATH = ""
    private var SMS_PATH = ""

    // ----- Current user -----
    private var userName: String? = null
    private var userContact: String? = null
    private var userEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = ReportAdapter(filteredReports, this)
        binding.reportsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.reportsRecyclerView.adapter = adapter

        initFiltersUI()

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                applyFilters()
                return true
            }
        })

        val authUser = FirebaseAuth.getInstance().currentUser
        if (authUser == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        userEmail = authUser.email

        FirebaseDatabase.getInstance().getReference("Users")
            .child(authUser.uid)
            .get()
            .addOnSuccessListener { snap ->
                userName = snap.child("name").getValue(String::class.java)?.trim()
                userContact = snap.child("contact").getValue(String::class.java)?.trim()
                Log.d("MyReport", "Profile → name=[$userName], contact=[$userContact], email=[$userEmail]")
                loadOnlyCurrentUsersReports()
            }
            .addOnFailureListener {
                Log.w("MyReport", "Failed to load profile; continuing with email only.")
                loadOnlyCurrentUsersReports()
            }
    }

    private fun initFiltersUI() {
        val typeItems = listOf("All", "Fire Report", "Other Emergency", "EMS", "SMS")
        binding.typeDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, typeItems))
        binding.typeDropdown.setText("All", false)
        binding.typeDropdown.setOnItemClickListener { _, _, pos, _ ->
            typeFilter = when (pos) {
                1 -> TypeFilter.FIRE
                2 -> TypeFilter.OTHER
                3 -> TypeFilter.EMS
                4 -> TypeFilter.SMS
                else -> TypeFilter.ALL
            }
            applyFilters()
        }

        val statusItems = listOf("All", "Pending", "Ongoing", "Completed")
        binding.statusDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, statusItems))
        binding.statusDropdown.setText("All", false)
        binding.statusDropdown.setOnItemClickListener { _, _, pos, _ ->
            statusFilter = when (pos) {
                1 -> StatusFilter.PENDING
                2 -> StatusFilter.RESPONDING
                3 -> StatusFilter.RESOLVED
                else -> StatusFilter.ALL
            }
            applyFilters()
        }
    }

    private fun normalizePhone(s: String?): String = s?.filter { it.isDigit() } ?: ""

    private fun belongsToCurrentUser(name: String?, contact: String?): Boolean {
        val userContactN = normalizePhone(userContact)
        val reportContactN = normalizePhone(contact)
        val contactMatches = userContactN.isNotEmpty() && reportContactN.isNotEmpty() && userContactN == reportContactN

        val userNameN = (userName ?: "").trim().lowercase()
        val reportNameN = (name ?: "").trim().lowercase()
        val nameMatches = userNameN.isNotEmpty() && reportNameN.isNotEmpty() && userNameN == reportNameN

        return contactMatches || nameMatches
    }

    private fun loadOnlyCurrentUsersReports() {
        allReports.clear()
        filteredReports.clear()
        adapter.notifyDataSetChanged()

        val db = FirebaseDatabase.getInstance().reference
        var finished = 0

        // Loop through all stations
        STATIONS.forEach { station ->
            FIRE_PATH = "$STATION_ROOT/$station/AllReport/FireReport"
            OTHER_PATH = "$STATION_ROOT/$station/AllReport/OtherEmergencyReport"
            EMS_PATH = "$STATION_ROOT/$station/AllReport/EmergencyMedicalServicesReport"
            SMS_PATH = "$STATION_ROOT/$station/AllReport/SmsReport"

            val paths = listOf(FIRE_PATH, OTHER_PATH, EMS_PATH, SMS_PATH)
            paths.forEach { path ->
                db.child(path).get()
                    .addOnSuccessListener { snapshot ->
                        for (reportSnap in snapshot.children) {
                            try {
                                when {
                                    path.endsWith("FireReport") -> {
                                        val name = reportSnap.child("name").getValue(String::class.java)
                                        val contact = reportSnap.child("contact").getValue(String::class.java)
                                        if (!belongsToCurrentUser(name, contact)) continue

                                        val report = FireReport(
                                            name = name ?: "",
                                            contact = contact ?: "",
                                            date = reportSnap.child("date").getValue(String::class.java) ?: "",
                                            reportTime = reportSnap.child("reportTime").getValue(String::class.java) ?: "",
                                            latitude = reportSnap.child("latitude").getValue(Double::class.java) ?: 0.0,
                                            longitude = reportSnap.child("longitude").getValue(Double::class.java) ?: 0.0,
                                            exactLocation = reportSnap.child("exactLocation").getValue(String::class.java) ?: "",
                                            timeStamp = reportSnap.child("timeStamp").getValue(Long::class.java) ?: 0L,
                                            status = reportSnap.child("status").getValue(String::class.java) ?: "Pending",
                                            fireStationName = reportSnap.child("fireStationName").getValue(String::class.java) ?: "",
                                            type = reportSnap.child("type").getValue(String::class.java) ?: "",
                                            category = "FIRE"
                                        )
                                        allReports.add(report)
                                    }

                                    path.endsWith("OtherEmergencyReport") -> {
                                        val name = reportSnap.child("name").getValue(String::class.java)
                                        val contact = reportSnap.child("contact").getValue(String::class.java)
                                        if (!belongsToCurrentUser(name, contact)) continue

                                        val report = OtherEmergency(
                                            emergencyType = reportSnap.child("emergencyType").getValue(String::class.java) ?: "Other",
                                            name = name ?: "",
                                            contact = contact ?: "",
                                            date = reportSnap.child("date").getValue(String::class.java) ?: "",
                                            reportTime = reportSnap.child("reportTime").getValue(String::class.java) ?: "",
                                            latitude = reportSnap.child("latitude").getValue(String::class.java) ?: "",
                                            longitude = reportSnap.child("longitude").getValue(String::class.java) ?: "",
                                            location = reportSnap.child("location").getValue(String::class.java) ?: "",
                                            exactLocation = reportSnap.child("exactLocation").getValue(String::class.java) ?: "",
                                            lastReportedTime = reportSnap.child("lastReportedTime").getValue(Long::class.java) ?: 0L,
                                            timestamp = reportSnap.child("timestamp").getValue(Long::class.java) ?: 0L,
                                            read = reportSnap.child("read").getValue(Boolean::class.java) ?: false,
                                            fireStationName = reportSnap.child("fireStationName").getValue(String::class.java) ?: "",
                                            status = reportSnap.child("status").getValue(String::class.java) ?: "Pending",
                                            type = reportSnap.child("type").getValue(String::class.java) ?: "",
                                            category = "OTHER"
                                        )
                                        allReports.add(report)
                                    }
                                    // Add similar handling for EMS and SMS reports...
                                }
                            } catch (e: Exception) {
                                Log.e("ReportParseError", "Failed to parse: ${e.message}")
                            }
                        }
                    }
                    .addOnCompleteListener {
                        finished++
                        if (finished == STATIONS.size * paths.size) onAllLoaded()
                    }
            }
        }
    }

    private fun onAllLoaded() {
        allReports.sortByDescending {
            when (it) {
                is FireReport -> it.timeStamp
                is OtherEmergency -> it.timestamp
                else -> 0L
            }
        }
        applyFilters()

        if (allReports.isEmpty()) {
            Toast.makeText(this, "No reports found for your account.", Toast.LENGTH_SHORT).show()
        }
    }

    // This function is used to categorize each report based on its type.
    private fun categoryOf(r: Any): Category = when (r) {
        is FireReport -> Category.FIRE
        is OtherEmergency -> when {
            r.category.equals("EMS", true) -> Category.EMS
            r.category.equals("SMS", true) -> Category.SMS
            else -> Category.OTHER
        }
        else -> Category.OTHER
    }


    private fun applyFilters() {
        val q = searchQuery.trim()

        val filtered = allReports.asSequence()
            // Type filter
            .filter { r ->
                val cat = categoryOf(r)
                when (typeFilter) {
                    TypeFilter.ALL -> true
                    TypeFilter.FIRE -> cat == Category.FIRE
                    TypeFilter.OTHER -> cat == Category.OTHER
                    TypeFilter.EMS -> cat == Category.EMS
                    TypeFilter.SMS -> cat == Category.SMS
                }
            }
            .toList()

        filteredReports.clear()
        filteredReports.addAll(filtered)
        adapter.notifyDataSetChanged()
    }

    override fun onFireReportClick(report: FireReport) {
        ReportDetailsDialogFragment.newInstance(report)
            .show(supportFragmentManager, "detailsDialog")
    }

    override fun onOtherEmergencyClick(report: OtherEmergency) {
        ReportDetailsDialogFragment.newInstance(report)
            .show(supportFragmentManager, "detailsDialog")
    }
}
