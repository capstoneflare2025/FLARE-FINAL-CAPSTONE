package com.example.flare_capstone

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.flare_capstone.FireFighterResponseActivity
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class FireFighterResponseMessageAdapter(
    private val stationList: MutableList<FireFighterStation>,
    private val onItemClick: (FireFighterStation) -> Unit
) : RecyclerView.Adapter<FireFighterResponseMessageAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val profileIcon: ShapeableImageView = itemView.findViewById(R.id.profileIcon)
        val fireStationName: TextView = itemView.findViewById(R.id.fireStationName)
        val uid: TextView = itemView.findViewById(R.id.uid)
        val timestamp: TextView = itemView.findViewById(R.id.timestamp)
        val unreadDot: View = itemView.findViewById(R.id.unreadDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fire_fighter_fire_station, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SimpleDateFormat")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stationList[position]

        // Name from data (no hard-coding)
        holder.fireStationName.text = station.name

        // Message preview
        val baseSummary = when {
            station.hasAudio -> "Sent a voice message."
            station.hasImage -> "Sent a photo."
            station.lastMessage.isNotBlank() -> station.lastMessage
            else -> "No recent message"
        }
        val preview = when {
            station.lastSender.equals("admin", ignoreCase = true) -> "Reply: $baseSummary"
            station.lastSender.equals(station.name, ignoreCase = true) -> "You: $baseSummary"
            else -> baseSummary
        }
        holder.uid.text = preview

        // Timestamp
        holder.timestamp.text = if (station.timestamp > 0L) {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(station.timestamp))
        } else ""


        // Profile image: Always use the default image
        holder.profileIcon.setImageResource(R.drawable.station_logo)  // Set default profile image


        // Unread style (admin replies only)
        val isUnreadFromAdmin = station.hasUnreadAdminReply
        holder.unreadDot.visibility = if (isUnreadFromAdmin) View.VISIBLE else View.GONE
        holder.fireStationName.setTypeface(null, if (isUnreadFromAdmin) Typeface.BOLD else Typeface.NORMAL)
        holder.uid.setTypeface(null, if (isUnreadFromAdmin) Typeface.BOLD else Typeface.NORMAL)

        // Click → open chat, then mark admin msgs read using the exact path
        holder.itemView.setOnClickListener {
            val ctx = holder.itemView.context

            // Launch chat with data we actually use
            ctx.startActivity(
                Intent(ctx, FireFighterResponseActivity::class.java).apply {
                    putExtra("STATION_NAME", station.name)
                    putExtra("ADMIN_MESSAGES_PATH", station.adminMessagesPath)
                }
            )
            onItemClick(station)

            // Mark admin replies as read at this row's AdminMessages path
            try {
                val dbRef = FirebaseDatabase.getInstance().getReference(station.adminMessagesPath)
                dbRef.orderByChild("sender").equalTo("admin")
                    .get()
                    .addOnSuccessListener { snap ->
                        for (msg in snap.children) {
                            val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false
                            if (!isRead) msg.ref.child("isRead").setValue(true)
                        }
                    }
            } catch (_: Throwable) {
                // ignore; UI will refresh from live listeners
            }

            // Local UI update so it un-bolds right away
            try { station.isRead = true } catch (_: Throwable) {}
            notifyItemChanged(position)
        }
    }

    override fun getItemCount(): Int = stationList.size

    fun updateData(newList: List<FireFighterStation>) {
        stationList.clear()
        stationList.addAll(newList)
        notifyDataSetChanged()
    }
}
