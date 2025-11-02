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
    private var currentCategoryFilter: CategoryFilter = CategoryFilter.FIRE

    private var selectedStation: String = "Tagum City Central Fire Station"

    private lateinit var database: DatabaseReference
    private val stationNodes = listOf("TagumCityCentralFireStation")
    private val liveListeners = mutableListOf<Pair<Query, ValueEventListener>>()

    // ✅ Exact category paths
    private val FIRE_NODE  = "AllReport/FireReport"
    private val OTHER_NODE = "AllReport/OtherEmergencyReport"
    private val EMS_NODE   = "AllReport/EmergencyMedicalServicesReport"
    private val SMS_NODE   = "AllReport/SmsReport"

    private var unreadMessageCount: Int = 0

    private enum class FilterMode { ALL, READ, UNREAD }
    private var currentFilter: FilterMode = FilterMode.ALL

    private fun normStation(s: String?): String =
        (s ?: "").lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]"), "")


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        database = FirebaseDatabase.getInstance().reference

        responseMessageAdapter = ResponseMessageAdapter(visibleMessages) {
            applyFilter()
            unreadMessageCount = allMessages.count { !it.isRead }
            updateInboxBadge(unreadMessageCount)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = responseMessageAdapter

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


        loadUserAndAttach()
    }


    private fun loadUserAndAttach() {
        val userEmail = FirebaseAuth.getInstance().currentUser?.email
        if (userEmail == null) {
            Toast.makeText(context, "User is not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        database.child("Users").orderByChild("email").equalTo(userEmail)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!snap.exists()) {
                        Toast.makeText(context, "User not found.", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val userSnap = snap.children.first()
                    val userName = userSnap.child("name").getValue(String::class.java) ?: ""
                    val userContact = userSnap.child("contact").getValue(String::class.java) ?: ""
                    attachStationInboxListeners(userName, userContact)
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("Inbox", "User lookup cancelled: ${error.message}")
                }
            })
    }

    private fun attachStationInboxListeners(userName: String, userContact: String) {
        val b = _binding ?: return
        if (!isAdded) return

        detachAllListeners()
        allMessages.clear()
        visibleMessages.clear()
        responseMessageAdapter.notifyDataSetChanged()
        b.noMessagesText.visibility = View.VISIBLE
        updateInboxBadge(0)

        stationNodes.forEach { station ->
            // ✅ Inbox list: TagumCityCentralFireStation/AllReport/ResponseMessage
            val q: Query = database.child(station)
                .child("AllReport")
                .child("ResponseMessage")

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val vb = _binding ?: return
                    var changed = false

                    // 1) Remove prior rows for this station
                    allMessages.removeAll { it.stationNode == station }

                    // 2) Build incidentId -> newest message
                    val latestByIncident = mutableMapOf<String, ResponseMessage>()

                    snapshot.children.forEach { node ->
                        val msg = node.getValue(ResponseMessage::class.java) ?: return@forEach
                        msg.uid = node.key ?: msg.uid.orEmpty()
                        msg.stationNode = station

                        val incident = msg.incidentId?.trim().orEmpty()
                        if (incident.isEmpty()) return@forEach

                        // only the current user's rows
                        val contactMatch = (msg.contact ?: "").trim() == userContact.trim()
                        val nameMatch = (msg.reporterName ?: "").trim() == userName.trim()
                        if (!contactMatch && !nameMatch) return@forEach

                        val cur = latestByIncident[incident]
                        if (cur == null || (msg.timestamp ?: 0L) > (cur.timestamp ?: 0L)) {
                            latestByIncident[incident] = msg
                        }
                    }

                    // 3) Append one row per incident
                    if (latestByIncident.isNotEmpty()) {
                        allMessages.addAll(latestByIncident.values)
                        changed = true
                    }

                    // 4) Resolve category only if unknown/not set
                    latestByIncident.values.forEach { m ->
                        if (m.category.isNullOrBlank() || m.category == "unknown") {
                            resolveCategoryForMessage(station, m)
                        }
                    }

                    if (changed) applyFilter()

                    vb.noMessagesText.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
                    unreadMessageCount = allMessages.count { !it.isRead }
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

    private fun resolveCategoryForMessage(stationNode: String, msg: ResponseMessage) {
        // Always attempt to resolve if we're not already one of the four known buckets
        if (msg.category in listOf("fire","other","ems","sms")) return

        val threadId = msg.incidentId ?: return   // use only the real incident id for lookup
        msg.category = "unknown"                  // start unknown; UI will update when we set the real one

        database.child(stationNode).child(FIRE_NODE).child(threadId).child("messages")
            .limitToFirst(1).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (snap.exists() && snap.childrenCount > 0) {
                        msg.category = "fire"
                        applyFilter()
                    } else {
                        database.child(stationNode).child(OTHER_NODE).child(threadId).child("messages")
                            .limitToFirst(1).addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(s2: DataSnapshot) {
                                    if (s2.exists() && s2.childrenCount > 0) {
                                        msg.category = "other"
                                        applyFilter()
                                    } else {
                                        database.child(stationNode).child(EMS_NODE).child(threadId).child("messages")
                                            .limitToFirst(1).addListenerForSingleValueEvent(object : ValueEventListener {
                                                override fun onDataChange(s3: DataSnapshot) {
                                                    if (s3.exists() && s3.childrenCount > 0) {
                                                        msg.category = "ems"
                                                        applyFilter()
                                                    } else {
                                                        database.child(stationNode).child(SMS_NODE).child(threadId).child("messages")
                                                            .limitToFirst(1).addListenerForSingleValueEvent(object : ValueEventListener {
                                                                override fun onDataChange(s4: DataSnapshot) {
                                                                    msg.category = if (s4.exists() && s4.childrenCount > 0) "sms" else "unknown"
                                                                    applyFilter()
                                                                }
                                                                override fun onCancelled(error: DatabaseError) {
                                                                    msg.category = "unknown"; applyFilter()
                                                                }
                                                            })
                                                    }
                                                }
                                                override fun onCancelled(error: DatabaseError) {
                                                    msg.category = "unknown"; applyFilter()
                                                }
                                            })
                                    }
                                }
                                override fun onCancelled(error: DatabaseError) {
                                    msg.category = "unknown"; applyFilter()
                                }
                            })
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    msg.category = "unknown"; applyFilter()
                }
            })
    }


    private fun applyFilter() {
        if (_binding == null) return
        visibleMessages.clear()

        val base = when (currentFilter) {
            FilterMode.ALL    -> allMessages
            FilterMode.READ   -> allMessages.filter { it.isRead }
            FilterMode.UNREAD -> allMessages.filter { !it.isRead }
        }

        val filteredByCategory = base.filter { msg ->
            when (currentCategoryFilter) {
                CategoryFilter.ALL   -> true
                CategoryFilter.FIRE  -> msg.category == "fire"
                CategoryFilter.OTHER -> msg.category == "other"
                CategoryFilter.EMS   -> msg.category == "ems"
                CategoryFilter.SMS   -> msg.category == "sms"
            }
        }

        val filteredByStation = filteredByCategory.filter { msg ->
            normStation(msg.fireStationName) == normStation(selectedStation)
        }

        visibleMessages.addAll(filteredByStation.sortedByDescending { it.timestamp ?: 0L })
        responseMessageAdapter.notifyDataSetChanged()
        _binding?.noMessagesText?.visibility = if (visibleMessages.isEmpty()) View.VISIBLE else View.GONE
    }


    private fun updateInboxBadge(count: Int) {
        if (!isAdded) return
        (activity as? DashboardActivity)?.let { act ->
            val badge = act.binding.bottomNavigation.getOrCreateBadge(com.example.flare_capstone.R.id.inboxFragment)
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
