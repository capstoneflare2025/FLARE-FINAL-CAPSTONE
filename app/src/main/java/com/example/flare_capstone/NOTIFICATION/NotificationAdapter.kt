package com.example.flare_capstone.NOTIFICATION

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.ItemNotificationBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Locale

// ==== UI model for item_notification.xml ====
data class UiNotification(
    val title: String,          // "Fire Report" | "Other Emergency Report" | "Emergency Medical Services Report"
    val type: String,           // same as title (display under title)
    val whenText: String,       // e.g. "11/08/2025 22:26:16"
    val locationText: String,   // exactLocation or fireStationName
    val mapLink: String?,
    val unread: Boolean,        // read=false => unread dot
    val iconRes: Int,           // R.drawable.ic_fire, ...
    val station: String,        // MabiniFireStation | CanocotanFireStation | LaFilipinaFireStation
    val key: String,            // report push key
    val typeKey: String,
    // "FireReport" | "OtherEmergencyReport" | "EmergencyMedicalServicesReport"
)

class NotificationAdapter(
    private val db: FirebaseDatabase,
    private var items: MutableList<UiNotification>,
    private val onClick: (UiNotification) -> Unit,
    private val markReadOnClick: Boolean = true
) : RecyclerView.Adapter<NotificationAdapter.VH>() {

    inner class VH(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                    val item = items[pos]

                    // Optional: mark as read immediately in Firebase before callback
                    if (markReadOnClick && item.unread) {
                        db.getReference("CapstoneFlare")
                            .child(item.station)
                            .child("AllReport")
                            .child(item.typeKey)
                            .child(item.key)
                            .child("read")
                            .setValue(true)
                    }

                    onClick(item)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemNotificationBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            // Title + icon
            tvTitle.text = item.title
            ivAvatar.setImageResource(item.iconRes)

            // Display the type of report
            tvSubtitle.text = item.type

            // Display the date and time of the report
            tvSubtitle1.text = item.whenText

            // Display location (could be fire station or exact location)
            tvSubtitle2.text = item.locationText

            // Handle the map link vs location display
            tvSubtitle3.text = item.mapLink

            // Unread dot (visibility based on whether the report is read)
            dotUnread.visibility = if (item.unread) View.VISIBLE else View.INVISIBLE

            // Set click listener for the map link (tvSubtitle3)
            tvSubtitle3.setOnClickListener {
                // Check if the mapLink is not null and not empty
                val mapUrl = item.mapLink
                if (!mapUrl.isNullOrBlank()) {
                    // Try to open the map URL using an implicit intent
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl))
                    // Check if there's an app that can handle this intent
                    if (intent.resolveActivity(holder.itemView.context.packageManager) != null) {
                        holder.itemView.context.startActivity(intent)
                    } else {
                        // If no app can handle the map URL, you could show a toast or error
                        Toast.makeText(holder.itemView.context, "No app found to open the map", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // If mapLink is null or empty, you can show a message or ignore
                    Toast.makeText(holder.itemView.context, "No map link available", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }




    override fun getItemCount() = items.size

    fun submit(list: List<UiNotification>) {
        // de-dupe by station+typeKey+key, keep latest occurrence
        val map = LinkedHashMap<String, UiNotification>()
        list.forEach { n -> map["${n.station}/${n.typeKey}/${n.key}"] = n }
        items.clear()
        items.addAll(map.values)
        notifyDataSetChanged()
    }

    // ----------------- Firebase loader (call this from Fragment/Activity or popup) -----------------

    /**
     * Loads notifications that belong to the *currently signed-in* user:
     * 1) /Users (root) -> get name + contact by email
     * 2) For each station & report type under /CapstoneFlare, collect reports with same (name, contact)
     * 3) submit() results to the adapter
     */
    fun refreshFromFirebase() {
        val email = FirebaseAuth.getInstance().currentUser?.email ?: return
        val usersRef = db.getReference("Users")

        usersRef.orderByChild("email").equalTo(email)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    var name: String? = null
                    var contact: String? = null

                    if (snap.exists()) {
                        for (child in snap.children) {
                            name = child.child("name").getValue(String::class.java)
                            contact = child.child("contact").getValue(String::class.java)
                            break
                        }
                    }

                    if (name.isNullOrBlank() || contact.isNullOrBlank()) {
                        // case-insensitive fallback
                        usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(all: DataSnapshot) {
                                for (c in all.children) {
                                    val e = c.child("email").getValue(String::class.java) ?: continue
                                    if (e.equals(email, ignoreCase = true)) {
                                        name = c.child("name").getValue(String::class.java)
                                        contact = c.child("contact").getValue(String::class.java)
                                        break
                                    }
                                }
                                if (name.isNullOrBlank() || contact.isNullOrBlank()) {
                                    submit(emptyList()); return
                                }
                                loadReportsFor(name!!, contact!!)
                            }
                            override fun onCancelled(error: DatabaseError) {
                                submit(emptyList())
                            }
                        })
                    } else {
                        loadReportsFor(name!!, contact!!)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    submit(emptyList())
                }
            })
    }

    private fun loadReportsFor(userName: String, userContact: String) {
        val stations = listOf("MabiniFireStation", "CanocotanFireStation", "LaFilipinaFireStation")
        val types = listOf(
            "FireReport" to R.drawable.ic_fire,
            "OtherEmergencyReport" to R.drawable.ic_warning_24,
            "EmergencyMedicalServicesReport" to R.drawable.ic_car_crash_24
        )

        val base = db.getReference("CapstoneFlare")
        val collected = mutableListOf<UiNotification>()
        var pending = stations.size * types.size
        if (pending == 0) { submit(emptyList()); return }

        stations.forEach { station ->
            types.forEach { (typeKey, iconRes) ->
                base.child(station).child("AllReport").child(typeKey)
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(s: DataSnapshot) {
                            if (s.exists()) {
                                for (report in s.children) {
                                    val name = report.child("name").getValue(String::class.java) ?: ""
                                    val contact = report.child("contact").getValue(String::class.java) ?: ""
                                    if (!name.equals(userName, ignoreCase = true)) continue
                                    if (contact != userContact) continue

                                    val date = report.child("date").getValue(String::class.java) ?: ""
                                    val time = report.child("reportTime").getValue(String::class.java) ?: ""
                                    val exactLocation = report.child("exactLocation").getValue(String::class.java)
                                        ?: report.child("fireStationName").getValue(String::class.java)
                                        ?: station
                                    val mapLink = report.child("location").getValue(String::class.java)
                                    val read = report.child("read").getValue(Boolean::class.java) ?: false
                                    val key = report.key ?: ""

                                    val title = when (typeKey) {
                                        "FireReport" -> "Fire Report"
                                        "OtherEmergencyReport" -> "Other Emergency Report"
                                        "EmergencyMedicalServicesReport" -> "Emergency Medical Services Report"
                                        else -> typeKey
                                    }

                                    collected += UiNotification(
                                        title = title,
                                        type = title,
                                        whenText = listOf(date, time).filter { it.isNotBlank() }.joinToString(" "),
                                        locationText = exactLocation,
                                        mapLink = mapLink,
                                        unread = !read,
                                        iconRes = iconRes,
                                        station = station,
                                        key = key,
                                        typeKey = typeKey
                                    )
                                }
                            }
                            if (--pending == 0) {
                                // Optional: sort by date/time if format is consistent
                                submit(sortByDateTime(collected))
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {
                            if (--pending == 0) submit(sortByDateTime(collected))
                        }
                    })
            }
        }
    }

    // Try sorting "MM/dd/yyyy" + "HH:mm:ss"; falls back to as-is
    private fun sortByDateTime(list: List<UiNotification>): List<UiNotification> {
        val sdfDate = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)
        return list.sortedByDescending { n ->
            runCatching { sdfDate.parse(n.whenText)?.time ?: 0L }.getOrDefault(0L)
        }
    }
}
