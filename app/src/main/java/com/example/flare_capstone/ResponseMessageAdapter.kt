package com.example.flare_capstone

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.flare_capstone.databinding.ItemFireStationBinding
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class ResponseMessageAdapter(
    private val responseMessageList: MutableList<ResponseMessage>,
    private val onMarkedRead: (() -> Unit)? = null
) : RecyclerView.Adapter<ResponseMessageAdapter.ResponseMessageViewHolder>() {

    private val livePreviewListeners = mutableMapOf<String, Pair<Query, ValueEventListener>>()
    private val lastPreviewByThread = mutableMapOf<String, String>() // cache latest message text

    private val ROOT_NODE = "CapstoneFlare"
    private val FIRE_NODE = "AllReport/FireReport"
    private val OTHER_NODE = "AllReport/OtherEmergencyReport"
    private val EMS_NODE = "AllReport/EmergencyMedicalServicesReport"
    private val SMS_NODE = "AllReport/SmsReport"

    inner class ResponseMessageViewHolder(val binding: ItemFireStationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResponseMessageViewHolder {
        val binding = ItemFireStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ResponseMessageViewHolder(binding)
    }

    // ✅ Always show 24-hour format
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun formatTime(ts: Long?): String {
        if (ts == null || ts <= 0L) return ""
        val millis = if (ts < 1_000_000_000_000L) ts * 1000 else ts
        return timeFmt.format(Date(millis))
    }

    // Fallback prettifier for node names
    private fun prettyStation(s: String?): String {
        if (s.isNullOrBlank()) return "Unknown Fire Station"
        return if (s.contains(" ", true)) s.trim()
        else s.replace(Regex("([a-z])([A-Z])"), "$1 $2").trim()
    }

    override fun onBindViewHolder(holder: ResponseMessageViewHolder, position: Int) {
        val item = responseMessageList[position]

        // ✅ Prefer the readable fireStationName
        val displayName = item.fireStationName?.takeIf { it.isNotBlank() }
            ?: prettyStation(item.stationNode)

        val isUnread = !item.isRead

        holder.binding.fireStationName.apply {
            text = displayName
            setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isUnread) Color.BLACK else Color.parseColor("#1A1A1A"))
        }
        holder.binding.timestamp.text = formatTime(item.timestamp)

        val threadId = item.incidentId ?: item.uid ?: return
        val stationNode = item.stationNode ?: "CanocotanFireStation"
        val category = item.category?.lowercase(Locale.getDefault())
        val reportNode = when (category) {
            "fire" -> FIRE_NODE
            "other" -> OTHER_NODE
            "ems" -> EMS_NODE
            "sms" -> SMS_NODE
            else -> FIRE_NODE
        }


        // 🔹 Fetch the date from the report node
        val dateRef = FirebaseDatabase.getInstance().reference
            .child(ROOT_NODE).child(stationNode).child(reportNode)
            .child(threadId).child("date")

        dateRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val dateValue = snapshot.getValue(String::class.java)
                holder.binding.dateText.visibility = if (!dateValue.isNullOrBlank()) View.VISIBLE else View.GONE
                holder.binding.dateText.text = dateValue ?: ""
            }

            override fun onCancelled(error: DatabaseError) {}
        })


        // Detach old listeners if any
        (holder.itemView.tag as? String)?.let { detachPreviewListener(it) }
        val previewKey = "$stationNode::$threadId"
        holder.itemView.tag = previewKey

        // Use cached preview if available
        val cachedText = lastPreviewByThread[previewKey]
        applyPreview(holder, cachedText ?: "Loading…", isUnread)

        // ✅ Listen to the latest message in this thread
        val q = FirebaseDatabase.getInstance().reference
            .child(ROOT_NODE).child(stationNode).child(reportNode)
            .child(threadId).child("messages")
            .orderByChild("timestamp").limitToLast(1)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) return
                if (holder.itemView.tag != previewKey) return

                val msgSnap = snapshot.children.firstOrNull() ?: return

                var text = msgSnap.child("text").getValue(String::class.java)?.trim().orEmpty()
                val type = msgSnap.child("type").getValue(String::class.java)?.trim()?.lowercase(Locale.getDefault())
                val readFlag = msgSnap.child("isRead").getValue(Boolean::class.java) ?: false

                // fallback text if blank
                if (text.isBlank()) {
                    val hasImage = !msgSnap.child("imageBase64").getValue(String::class.java).isNullOrEmpty()
                    val hasAudio = !msgSnap.child("audioBase64").getValue(String::class.java).isNullOrEmpty()
                    text = when {
                        hasImage -> "📷 Photo"
                        hasAudio -> "🎙️ Voice message"
                        else -> "(no text)"
                    }
                }

                // Prefix your replies
                if (type == "reply") text = "You: $text"

                // ✅ Only bold/unread if type == station and isRead == false
                val isUnread = (type == "station" && !readFlag)

                lastPreviewByThread[previewKey] = text
                applyPreview(holder, text, isUnread)

                holder.binding.fireStationName.setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
                holder.binding.unreadDot.visibility = if (isUnread) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {}
        }


        // Replace any old listener
        livePreviewListeners[previewKey]?.first?.removeEventListener(livePreviewListeners[previewKey]!!.second)
        q.addValueEventListener(listener)
        livePreviewListeners[previewKey] = q to listener

        // ✅ Click handler → open chat + mark read
        holder.binding.root.setOnClickListener {
            if (threadId.isBlank()) {
                Toast.makeText(holder.itemView.context, "Thread ID missing.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Mark messages as read
            val path = FirebaseDatabase.getInstance().reference
                .child(ROOT_NODE).child(stationNode).child(reportNode)
                .child(threadId).child("messages")

            path.get().addOnSuccessListener { snap ->
                val updates = mutableMapOf<String, Any?>()
                snap.children.forEach { msg -> updates["${msg.key}/isRead"] = true }
                if (updates.isNotEmpty()) path.updateChildren(updates)
            }

            // Update UI immediately
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) {
                responseMessageList[idx].isRead = true
                notifyItemChanged(idx)
                onMarkedRead?.invoke()
            }

            // Navigate to chat screen
            val context = holder.itemView.context
            val intent = Intent(context, FireReportResponseActivity::class.java).apply {
                putExtra("UID", item.uid)
                putExtra("FIRE_STATION_NAME", displayName)
                putExtra("CONTACT", item.contact)
                putExtra("NAME", item.reporterName)
                putExtra("INCIDENT_ID", threadId)
                putExtra("STATION_NODE", stationNode)
                putExtra("REPORT_NODE", reportNode)
                putExtra("CATEGORY", item.category)
            }
            context.startActivity(intent)
        }
    }

    private fun applyPreview(
        holder: ResponseMessageViewHolder,
        text: String,
        isUnread: Boolean
    ) {
        // Limit to ~40 characters for messenger-style preview
        val displayText = if (text.length > 40) text.take(37) + "..." else text

        holder.binding.uid.apply {
            this.text = displayText
            setTypeface(null, if (isUnread) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isUnread) Color.BLACK else Color.parseColor("#757575"))
            // ✅ Add ellipsis behavior if TextView width restricted by layout
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        holder.binding.unreadDot.visibility = if (isUnread) View.VISIBLE else View.GONE
    }


    override fun getItemCount(): Int = responseMessageList.size

    private fun detachPreviewListener(key: String) {
        livePreviewListeners.remove(key)?.let { (q, l) -> q.removeEventListener(l) }
    }

    override fun onViewRecycled(holder: ResponseMessageViewHolder) {
        super.onViewRecycled(holder)
        (holder.itemView.tag as? String)?.let { detachPreviewListener(it) }
        holder.itemView.tag = null
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        livePreviewListeners.values.forEach { (q, l) -> q.removeEventListener(l) }
        livePreviewListeners.clear()
    }
}
