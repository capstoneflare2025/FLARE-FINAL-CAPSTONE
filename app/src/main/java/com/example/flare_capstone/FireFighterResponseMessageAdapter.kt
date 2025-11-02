package com.example.flare_capstone

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
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

        // Name
        holder.fireStationName.text = "Tagum City Central Fire Station"


        // Build a summary using mutually-exclusive fields from the latest message
        // Assumes your FireFighterStation has fields:
        //   lastMessage: String, hasImage: Boolean, hasAudio: Boolean, lastSender: String, isRead: Boolean
        val baseSummary = when {
            station.hasAudio -> "Sent a voice message."
            station.hasImage -> "Sent a photo."
            station.lastMessage.isNotBlank() -> station.lastMessage
            else -> "No recent message"
        }

        // Prefix by sender role
        val preview = when {
            station.lastSender.equals("admin", ignoreCase = true) ->
                "Reply: $baseSummary"
            station.lastSender.equals(station.name, ignoreCase = true) ->
                "You: $baseSummary"
            else -> baseSummary
        }
        holder.uid.text = preview

        // Timestamp
        holder.timestamp.text = if (station.timestamp > 0L) {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(station.timestamp))
        } else {
            ""
        }

        // Profile icon
        if (station.profileUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(station.profileUrl)
                .placeholder(R.drawable.station_logo)
                .into(holder.profileIcon)
        } else {
            holder.profileIcon.setImageResource(R.drawable.station_logo)
        }

        // Unread style — ONLY for unread admin replies
        val isUnreadFromAdmin = !station.isRead &&
                station.lastSender.equals("admin", ignoreCase = true)

        holder.unreadDot.visibility = if (isUnreadFromAdmin) View.VISIBLE else View.GONE
        holder.fireStationName.setTypeface(null, if (isUnreadFromAdmin) Typeface.BOLD else Typeface.NORMAL)
        holder.uid.setTypeface(null, if (isUnreadFromAdmin) Typeface.BOLD else Typeface.NORMAL)

        // Click → open chat AND mark all admin messages as read for this account
        holder.itemView.setOnClickListener {
            val ctx = holder.itemView.context

            // Launch chat
            val intent = Intent(ctx, FireFighterResponseActivity::class.java).apply {
                val stationName = "Tagum City Central Fire Station"
                putExtra("STATION_NAME", stationName)
                putExtra("STATION_ID", station.id) // id == AllFireFighterAccount key
            }
            ctx.startActivity(intent)
            onItemClick(station)

            // Mark ALL unread admin messages for this station as read
            val dbRef = FirebaseDatabase.getInstance()
                .getReference("TagumCityCentralFireStation")
                .child("FireFighter")
                .child("AllFireFighterAccount")
                .child(station.id)
                .child("AdminMessages")

            // Only admin messages; set isRead = true where false
            dbRef.orderByChild("sender").equalTo("admin")
                .get()
                .addOnSuccessListener { snap ->
                    for (msg in snap.children) {
                        val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false
                        if (!isRead) {
                            msg.ref.child("isRead").setValue(true)
                        }
                    }
                }

            // Local UI update so it un-bolds right away
            // If your FireFighterStation.isRead is a 'var', this is fine; otherwise rebuild the item via copy in your list builder.
            try {
                station.isRead = true
            } catch (_: Throwable) {
                // If your model uses `val`, remove this and rely on your list refresh from Firebase.
            }
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
