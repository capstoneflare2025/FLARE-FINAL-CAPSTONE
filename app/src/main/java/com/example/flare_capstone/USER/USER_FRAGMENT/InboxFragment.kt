package com.example.flare_capstone.USER.USER_FRAGMENT

import android.R
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.flare_capstone.DashboardActivity
import com.example.flare_capstone.ResponseMessage
import com.example.flare_capstone.ResponseMessageAdapter
import com.example.flare_capstone.databinding.FragmentInboxBinding
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.Locale

class InboxFragment : Fragment() {
    private var _binding: FragmentInboxBinding? = null
    private val binding get() = _binding!!

    private lateinit var responseMessageAdapter: ResponseMessageAdapter
    private val allMessages = mutableListOf<ResponseMessage>()
    private val visibleMessages = mutableListOf<ResponseMessage>()

    private enum class CategoryFilter { ALL, FIRE, OTHER, EMS, SMS }
    private var currentCategoryFilter: CategoryFilter = CategoryFilter.ALL

    private var selectedStation: String = "All Fire Stations"
    private val stationDisplayNames = mutableMapOf<String, String>()

    private lateinit var database: DatabaseReference
    private val rootNode = "CapstoneFlare"
    private val stationNodes = listOf(
        "CanocotanFireStation",
        "LaFilipinaFireStation",
        "MabiniFireStation"
    )
    private val liveListeners = mutableListOf<Pair<Query, ValueEventListener>>()

    private var unreadMessageCount: Int = 0

    private enum class FilterMode { ALL, READ, UNREAD }
    private var currentFilter: FilterMode = FilterMode.ALL

    private fun normStation(s: String?): String =
        (s ?: "").lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        database = FirebaseDatabase.getInstance().reference.child(rootNode)

        responseMessageAdapter = ResponseMessageAdapter(visibleMessages) {
            applyFilter()
            unreadMessageCount = allMessages.count { !it.isRead }
            updateInboxBadge(unreadMessageCount)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = responseMessageAdapter

        // Tabs for ALL / READ / UNREAD
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentFilter = when (tab.position) {
                    1 -> FilterMode.READ
                    2 -> FilterMode.UNREAD
                    else -> FilterMode.ALL
                }
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Category dropdown
        val categories = listOf(
            "All Report",
            "Fire Report",
            "Other Emergency Report",
            "Emergency Medical Services Report",
            "Sms Report"
        )
        binding.categoryDropdown.setAdapter(
            ArrayAdapter(requireContext(), R.layout.simple_list_item_1, categories)
        )
        binding.categoryDropdown.setText("All Report", false)
        currentCategoryFilter = CategoryFilter.ALL
        binding.categoryDropdown.setOnItemClickListener { _, _, pos, _ ->
            currentCategoryFilter = when (pos) {
                1 -> CategoryFilter.FIRE
                2 -> CategoryFilter.OTHER
                3 -> CategoryFilter.EMS
                4 -> CategoryFilter.SMS
                else -> CategoryFilter.ALL
            }
            applyFilter()
        }

        // ✅ Load station names first, then user messages
        loadStationNames {
            setupStationDropdown()
            loadUserAndAttach()
        }
    }

    // ✅ Fetch display names for all stations from /Profile/name
    private fun loadStationNames(onComplete: () -> Unit) {
        val ref = FirebaseDatabase.getInstance().getReference(rootNode)
        var remaining = stationNodes.size
        stationNodes.forEach { station ->
            ref.child(station).child("Profile").child("name")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val displayName = snapshot.getValue(String::class.java)
                        if (!displayName.isNullOrBlank()) {
                            stationDisplayNames[station] = displayName.trim()
                        } else {
                            stationDisplayNames[station] = station
                        }
                        if (--remaining == 0) onComplete()
                    }

                    override fun onCancelled(error: DatabaseError) {
                        stationDisplayNames[station] = station
                        if (--remaining == 0) onComplete()
                    }
                })
        }
    }

    // ✅ Dynamically populate station dropdown with names
    private fun setupStationDropdown() {
        val stationOptions = mutableListOf("All Fire Stations")
        stationOptions.addAll(stationDisplayNames.values)
        binding.stationDropdown.setAdapter(
            ArrayAdapter(requireContext(), R.layout.simple_list_item_1, stationOptions)
        )
        binding.stationDropdown.setText("All Fire Stations", false)
        binding.stationDropdown.setOnItemClickListener { _, _, pos, _ ->
            selectedStation = stationOptions[pos]
            applyFilter()
        }
    }

    private fun loadUserAndAttach() {
        val userEmail = FirebaseAuth.getInstance().currentUser?.email
        if (userEmail == null) {
            Toast.makeText(context, "User is not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        database.root.child("Users").orderByChild("email").equalTo(userEmail)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!snap.exists()) {
                        Toast.makeText(context, "User not found.", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val userSnap = snap.children.first()
                    val userName = userSnap.child("name").getValue(String::class.java) ?: ""
                    val userContact = userSnap.child("contact").getValue(String::class.java) ?: ""
                    attachAllStationListeners(userName, userContact)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Inbox", "User lookup cancelled: ${error.message}")
                }
            })
    }

    private fun attachAllStationListeners(userName: String, userContact: String) {
        val b = _binding ?: return
        detachAllListeners()
        allMessages.clear()
        visibleMessages.clear()
        responseMessageAdapter.notifyDataSetChanged()
        b.noMessagesText.visibility = View.VISIBLE
        updateInboxBadge(0)

        val reportTypes = listOf(
            "FireReport" to "fire",
            "OtherEmergencyReport" to "other",
            "EmergencyMedicalServicesReport" to "ems",
            "SmsReport" to "sms"
        )

        stationNodes.forEach { station ->
            reportTypes.forEach { (reportType, category) ->
                val q: Query = database.child(station)
                    .child("AllReport")
                    .child(reportType)

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val vb = _binding ?: return
                        var changed = false
                        allMessages.removeAll { it.stationNode == station && it.category == category }

                        val latestByIncident = mutableMapOf<String, ResponseMessage>()

                        snapshot.children.forEach { incidentSnap ->
                            val incidentId = incidentSnap.key ?: return@forEach
                            val messagesSnap = incidentSnap.child("messages")

                            var latestMessage: ResponseMessage? = null
                            for (msgSnap in messagesSnap.children) {
                                val msg = msgSnap.getValue(ResponseMessage::class.java) ?: continue
                                val contactMatch = (msg.contact ?: "").trim() == userContact.trim()
                                val nameMatch = (msg.reporterName ?: "").trim() == userName.trim()
                                if (!contactMatch && !nameMatch) continue

                                msg.uid = msgSnap.key.toString()
                                msg.stationNode = station
                                msg.fireStationName = stationDisplayNames[station] ?: station
                                msg.category = category
                                msg.incidentId = incidentId

                                if (latestMessage == null || (msg.timestamp ?: 0L) > (latestMessage.timestamp ?: 0L)) {
                                    latestMessage = msg
                                }
                            }
                            if (latestMessage != null) {
                                latestByIncident[incidentId] = latestMessage
                            }
                        }

                        if (latestByIncident.isNotEmpty()) {
                            allMessages.addAll(latestByIncident.values)
                            changed = true
                        }

                        if (changed) applyFilter()
                        vb.noMessagesText.visibility =
                            if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
                        unreadMessageCount = allMessages.count { !it.isRead && it.type?.lowercase() == "station" }
                        updateInboxBadge(unreadMessageCount)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("Inbox", "Listener cancelled: ${error.message}")
                    }
                }

                q.addValueEventListener(listener)
                liveListeners.add(q to listener)
            }
        }
    }

    private fun applyFilter() {
        if (_binding == null) return
        visibleMessages.clear()

        val base = when (currentFilter) {
            FilterMode.ALL -> allMessages
            FilterMode.READ -> allMessages.filter { it.isRead }
            FilterMode.UNREAD -> allMessages.filter { !it.isRead }
        }

        val filteredByCategory = base.filter { msg ->
            when (currentCategoryFilter) {
                CategoryFilter.ALL -> true
                CategoryFilter.FIRE -> msg.category == "fire"
                CategoryFilter.OTHER -> msg.category == "other"
                CategoryFilter.EMS -> msg.category == "ems"
                CategoryFilter.SMS -> msg.category == "sms"
            }
        }

        val filteredByStation = when (selectedStation) {
            "All Fire Stations" -> filteredByCategory
            else -> filteredByCategory.filter {
                normStation(it.fireStationName) == normStation(selectedStation)
            }
        }

        visibleMessages.addAll(filteredByStation.sortedByDescending { it.timestamp ?: 0L })
        responseMessageAdapter.notifyDataSetChanged()
        _binding?.noMessagesText?.visibility =
            if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateInboxBadge(count: Int) {
        if (!isAdded) return
        (activity as? DashboardActivity)?.let { act ->
            val badge = act.binding.bottomNavigation.getOrCreateBadge(
                com.example.flare_capstone.R.id.inboxFragment
            )
            badge.isVisible = count > 0
            badge.number = count
            badge.maxCharacterCount = 3
        } ?: Log.e("InboxFragment", "Parent activity is not DashboardActivity")
    }

    private fun detachAllListeners() {
        liveListeners.forEach { (q, l) -> runCatching { q.removeEventListener(l) } }
        liveListeners.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detachAllListeners()
        _binding = null
    }
}
