package com.example.flare_capstone.BFP.BFP_FRAGMENT

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.FireFighterResponseMessageAdapter
import com.example.flare_capstone.FireFighterStation
import com.example.flare_capstone.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class InboxFireFighterFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FireFighterResponseMessageAdapter
    private var emptyText: TextView? = null
    private var searchInput: EditText? = null

    // listeners we must remove later
    private val accountQueryListeners = mutableListOf<Pair<Query, ValueEventListener>>()
    private val lastMsgListeners = mutableMapOf<String, Pair<Query, ValueEventListener>>()

    // rows by unique key (we’ll use adminMessagesPath as the id)
    private val stationMap = linkedMapOf<String, FireFighterStation>()

    // All station roots to search
    private val stationRoots = listOf(
        "CapstoneFlare/MabiniFireStation",
        "CapstoneFlare/CanocotanFireStation",
        "CapstoneFlare/LaFilipinaFireStation"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_inbox_fire_fighter, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        emptyText = view.findViewById(R.id.noMessagesText)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.setHasFixedSize(true)

        adapter = FireFighterResponseMessageAdapter(mutableListOf()) { /* open chat if needed */ }
        recyclerView.adapter = adapter

        attachForLoggedInAccount()
    }

    private fun attachForLoggedInAccount() {
        val email = FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase()
        if (email.isNullOrBlank()) { showEmpty("Not signed in."); return }

        clearAllListeners()
        stationMap.clear()
        pushListUpdate()

        var anyAccountFound = false
        var stationsProcessed = 0

        stationRoots.forEach { stationRoot ->
            val ref = FirebaseDatabase.getInstance().reference
                .child(stationRoot)
                .child("FireFighter")
                .child("FireFighterAccount")

            // detect shape once
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    stationsProcessed++

                    if (!snapshot.exists()) {
                        if (stationsProcessed == stationRoots.size && !anyAccountFound) {
                            showEmpty("No account found for $email")
                        }
                        return
                    }

                    val nodeEmail = snapshot.child("email").getValue(String::class.java)?.trim()?.lowercase()
                    val hasDirectFields = nodeEmail != null || snapshot.hasChild("name") || snapshot.hasChild("contact")

                    if (hasDirectFields) {
                        if (nodeEmail == email) {
                            anyAccountFound = true
                            addOrUpdateAccountRowDirect(stationRoot, snapshot)
                            attachLastMessageListenerDirect(stationRoot, snapshot)
                            pushListUpdate()
                        }
                    } else {
                        snapshot.children.forEach { accSnap ->
                            val childEmail = accSnap.child("email").getValue(String::class.java)?.trim()?.lowercase()
                            if (childEmail == email) {
                                anyAccountFound = true
                                addOrUpdateAccountRowChild(stationRoot, accSnap)
                                attachLastMessageListenerChild(stationRoot, accSnap)
                            }
                        }
                        pushListUpdate()
                    }

                    if (stationsProcessed == stationRoots.size && !anyAccountFound) {
                        showEmpty("No account found for $email")
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    stationsProcessed++
                    if (stationsProcessed == stationRoots.size && !anyAccountFound) {
                        showEmpty("Failed to load account.")
                    }
                }
            }

            ref.addListenerForSingleValueEvent(l)
            accountQueryListeners += ref to l
        }
    }

    private fun addOrUpdateAccountRowDirect(stationRoot: String, node: DataSnapshot) {

        var name = node.child("name").getValue(String::class.java) ?: "Unknown"
        val adminMessagesPath = node.ref.child("AdminMessages").path.toString()
        val rowId = adminMessagesPath // stable + unique

        // Trim the name if it contains extra words after "Station" or "Sub-Station"
        name = trimStationName(name)

        // Use the 'name' from Profile or fallback value
        val displayName = name  // Display name comes from Profile node or fallback name

        val existing = stationMap[rowId]
        stationMap[rowId] = (existing ?: FireFighterStation(
            id = rowId,
            name = displayName,
            lastMessage = "",
            timestamp = 0L,
            lastSender = "",
            isRead = true,
            hasAudio = false,
            hasImage = false,
            adminMessagesPath = adminMessagesPath
        )).copy(name = displayName)
    }

    /**
     * Trims the name to exclude any extra words after "Station" or "Sub-Station".
     */
    private fun trimStationName(name: String): String {
        // Check if the name contains "Sub-Station" or "Station"
        val nameParts = name.trim().split(" ")

        // Find the index of the last word "Station" or "Sub-Station"
        val stationIndex = nameParts.indexOfFirst { it.equals("Station", ignoreCase = true) || it.equals("Sub-Station", ignoreCase = true) }

        // If "Station" or "Sub-Station" is found and there are extra words after it, we trim the name
        return if (stationIndex != -1) {
            // Return only the part up to "Station" or "Sub-Station"
            nameParts.take(stationIndex + 1).joinToString(" ")
        } else {
            // Return the name as is if it doesn't contain "Station"
            name
        }
    }


    private fun attachLastMessageListenerDirect(stationRoot: String, node: DataSnapshot) {
        val adminMessagesPath = node.ref.child("AdminMessages").path.toString()
        val rowId = adminMessagesPath

        lastMsgListeners[rowId]?.let { (q, l) -> q.removeEventListener(l) }

        val lastMsgQuery = node.ref.child("AdminMessages")
            .orderByChild("timestamp")
            .limitToLast(1)

        val msgListener = object : ValueEventListener {
            override fun onDataChange(msgSnap: DataSnapshot) {
                var lastText = ""
                var lastTs = 0L
                var lastSender = ""
                var hasImage = false
                var hasAudio = false
                var isRead = true

                for (m in msgSnap.children) {
                    lastText = m.child("text").getValue(String::class.java) ?: ""
                    hasImage = !m.child("imageBase64").getValue(String::class.java).isNullOrBlank()
                    hasAudio = !m.child("audioBase64").getValue(String::class.java).isNullOrBlank()
                    lastTs = m.child("timestamp").getValue(Long::class.java) ?: 0L
                    lastSender = m.child("sender").getValue(String::class.java) ?: ""
                    isRead = m.child("isRead").getValue(Boolean::class.java) ?: true
                }

                stationMap[rowId]?.let { prev ->
                    stationMap[rowId] = prev.copy(
                        lastMessage = lastText,
                        timestamp = lastTs,
                        lastSender = lastSender,
                        isRead = isRead,
                        hasImage = hasImage,
                        hasAudio = hasAudio,
                        hasUnreadAdminReply = (!isRead && lastSender.equals("admin", true))
                    )
                    pushListUpdate()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        lastMsgQuery.addValueEventListener(msgListener)
        lastMsgListeners[rowId] = lastMsgQuery to msgListener
    }

    // ---------- Child accounts under FireFighterAccount/<accountKey> ----------
    private fun addOrUpdateAccountRowChild(stationRoot: String, accSnap: DataSnapshot) {
        val name = accSnap.child("name").getValue(String::class.java) ?: (accSnap.key ?: "Unknown")
        val profileBase64 = accSnap.child("profile").getValue(String::class.java) ?: ""
        val adminMessagesPath = accSnap.ref.child("AdminMessages").path.toString()
        val rowId = adminMessagesPath

        val displayName = stationDisplayName(stationRoot, name)

        val existing = stationMap[rowId]
        stationMap[rowId] = (existing ?: FireFighterStation(
            id = rowId,
            name = displayName,
            lastMessage = "",
            timestamp = 0L,
            profileUrl = profileBase64,
            lastSender = "",
            isRead = true,
            hasAudio = false,
            hasImage = false,
            adminMessagesPath = adminMessagesPath
        )).copy(name = displayName, profileUrl = profileBase64)
    }

    private fun attachLastMessageListenerChild(stationRoot: String, accSnap: DataSnapshot) {
        val adminMessagesPath = accSnap.ref.child("AdminMessages").path.toString()
        val rowId = adminMessagesPath

        lastMsgListeners[rowId]?.let { (q, l) -> q.removeEventListener(l) }

        val lastMsgQuery = accSnap.ref.child("AdminMessages")
            .orderByChild("timestamp")
            .limitToLast(1)

        val msgListener = object : ValueEventListener {
            override fun onDataChange(msgSnap: DataSnapshot) {
                var lastText = ""
                var lastTs = 0L
                var lastSender = ""
                var hasImage = false
                var hasAudio = false
                var isRead = true

                for (m in msgSnap.children) {
                    lastText = m.child("text").getValue(String::class.java) ?: ""
                    hasImage = !m.child("imageBase64").getValue(String::class.java).isNullOrBlank()
                    hasAudio = !m.child("audioBase64").getValue(String::class.java).isNullOrBlank()
                    lastTs = m.child("timestamp").getValue(Long::class.java) ?: 0L
                    lastSender = m.child("sender").getValue(String::class.java) ?: ""
                    isRead = m.child("isRead").getValue(Boolean::class.java) ?: true
                }

                stationMap[rowId]?.let { prev ->
                    stationMap[rowId] = prev.copy(
                        lastMessage = lastText,
                        timestamp = lastTs,
                        lastSender = lastSender,
                        isRead = isRead,
                        hasImage = hasImage,
                        hasAudio = hasAudio,
                        hasUnreadAdminReply = (!isRead && lastSender.equals("admin", true))
                    )
                    pushListUpdate()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        lastMsgQuery.addValueEventListener(msgListener)
        lastMsgListeners[rowId] = lastMsgQuery to msgListener
    }

    private fun stationDisplayName(stationRoot: String, fallbackName: String): String {
        // e.g., "CapstoneFlare/CanocotanFireStation" -> "Canocotan Fire Station"
        val tail = stationRoot.substringAfterLast('/')
        return when (tail) {
            "MabiniFireStation" -> "Mabini Fire Station"
            "CanocotanFireStation" -> "Canocotan Fire Station"
            "LaFilipinaFireStation" -> "La Filipina Fire Station"
            else -> fallbackName
        }
    }

    private fun pushListUpdate() {
        val list = stationMap.values.sortedByDescending { it.timestamp }
        adapter.updateData(list)
        emptyText?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) emptyText?.text = "No messages available"
    }

    private fun showEmpty(msg: String) {
        adapter.updateData(emptyList())
        emptyText?.visibility = View.VISIBLE
        emptyText?.text = msg
    }

    private fun clearAllListeners() {
        accountQueryListeners.forEach { (q, l) -> q.removeEventListener(l) }
        accountQueryListeners.clear()
        lastMsgListeners.forEach { (_, pair) -> pair.first.removeEventListener(pair.second) }
        lastMsgListeners.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearAllListeners()
        recyclerView.adapter = null
    }
}
