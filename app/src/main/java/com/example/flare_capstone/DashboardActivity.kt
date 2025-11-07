package com.example.flare_capstone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.flare_capstone.databinding.ActivityDashboardBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class DashboardActivity : AppCompatActivity() {

    lateinit var binding: ActivityDashboardBinding
    private lateinit var database: DatabaseReference
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    private var currentUserName: String? = null
    private var user: User? = null
    private var unreadMessageCount: Int = 0

    // DELETE this:
// private val stationNodes = listOf("CanocotanFireStation","LaFilipinaFireStation","MabiniFireStation")

    // ADD this:
    private val stationNodes = mutableListOf<String>()


    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val responseListeners = mutableListOf<Pair<Query, ChildEventListener>>()
    private val unreadCounterListeners = mutableListOf<Pair<Query, ValueEventListener>>()


    private var isNetworkValidated = false
    private var isNetworkSlow = true
    private var isInitialFirebaseReady = false

    private companion object {
        private const val CH_GENERAL = "default_channel_v3"
    }

    /* =========================================================
     * Network Callback
     * ========================================================= */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                isNetworkValidated = false
                isNetworkSlow = true
                showLoadingDialog("Connecting… (waiting for internet)")
            }
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread {
                isNetworkValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                isNetworkSlow = isSlow(caps)
                maybeHideLoading()
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                isNetworkValidated = false
                isNetworkSlow = true
                showLoadingDialog("No internet connection")
            }
        }
    }



    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        if (!isConnected()) showLoadingDialog("No internet connection") else showLoadingDialog("Connecting…")
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        sharedPreferences = getSharedPreferences("shown_notifications", MODE_PRIVATE)
        unreadMessageCount = sharedPreferences.getInt("unread_message_count", 0)
        updateInboxBadge(unreadMessageCount)

        database = FirebaseDatabase.getInstance().getReference("CapstoneFlare")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)

        createNotificationChannel()

// 1) Load stations under CapstoneFlare
        loadStations { stationsOk ->
            if (!stationsOk) {
                Log.w("Stations", "No stations discovered (yet). Listeners postponed.")
            }

            // 2) Resolve user, then attach listeners
            fetchCurrentUserName { name ->
                currentUserName = name
                if (name != null) {
                    startRealtimeUnreadCounter()
                    listenForResponseMessages()
                    listenForStatusChanges()
                } else {
                    Log.e("UserCheck", "Failed to get current user name.")
                }
                isInitialFirebaseReady = true
                runOnUiThread { maybeHideLoading() }
            }
        }


        binding.root.postDelayed({
            if (!isInitialFirebaseReady) showLoadingDialog("Still loading data… (slow internet)")
        }, 10_000)
    }

    override fun onResume() {
        super.onResume()

        // Update the unread message count when the activity is resumed
        updateUnreadMessageCount()

        if (!isInitialFirebaseReady) {
            loadStations { stationsOk ->
                if (stationsOk) {
                    fetchCurrentUserName { name ->
                        currentUserName = name
                        if (name != null) {
                            startRealtimeUnreadCounter()
                            listenForResponseMessages()
                            listenForStatusChanges()
                        }
                    }
                }
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        responseListeners.forEach { (q, l) -> runCatching { q.removeEventListener(l) } }
        unreadCounterListeners.forEach { (q, l) -> runCatching { q.removeEventListener(l) } }
    }

    /* =========================================================
     * Connectivity helpers
     * ========================================================= */
    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isSlow(caps: NetworkCapabilities): Boolean {
        val down = caps.linkDownstreamBandwidthKbps
        val up = caps.linkUpstreamBandwidthKbps
        return (down < 1500 || up < 512)
    }

    private fun maybeHideLoading() {
        if (isNetworkValidated && !isNetworkSlow && isInitialFirebaseReady) hideLoadingDialog()
        else showLoadingDialog("Loading…")
    }

    private fun showLoadingDialog(message: String = "Please wait") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val view = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
            view.findViewById<TextView>(R.id.closeButton)?.visibility = View.GONE
            builder.setView(view).setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.findViewById<TextView>(R.id.loading_message)?.text = message
        if (!loadingDialog!!.isShowing) loadingDialog!!.show()
    }

    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    /* =========================================================
     * Notification setup
     * ========================================================= */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(NotificationManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val soundUri = Uri.parse("android.resource://$packageName/${R.raw.message_notif}")
        val ch = NotificationChannel(CH_GENERAL, "General Notifications", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(soundUri, attrs)
            enableVibration(true)
            description = "Custom notification channel for Flare"
        }

        nm.createNotificationChannel(ch)

        // Log the creation of the notification channel
        Log.d("NotificationChannel", "Notification channel created with ID: $CH_GENERAL")
    }


    private fun loadStations(done: (Boolean) -> Unit) {
        // Reads CapstoneFlare/* and collects the first-level keys as station nodes
        database.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                stationNodes.clear()
                for (child in snap.children) {
                    val key = child.key ?: continue
                    stationNodes += key
                }
                Log.d("Stations", "Discovered stations: $stationNodes")
                done(stationNodes.isNotEmpty())
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w("Stations", "Discovery cancelled: ${error.message}")
                done(false)
            }
        })
    }
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun triggerNotification(
        fireStationName: String?,
        message: String?,
        messageId: String,
        incidentId: String?,
        reporterName: String?,
        title: String,
        stationNodeParam: String
    ) {
        val notificationId = (stationNodeParam + "::" + messageId).hashCode()

        // Show a toast to confirm the function is being called
        Toast.makeText(
            applicationContext,
            "Triggering notification: Title: $title, Message: $message",
            Toast.LENGTH_SHORT
        ).show()

        val reportNode = "AllReport/FireReport"

        val resultIntent = Intent(this, FireReportResponseActivity::class.java).apply {
            putExtra("INCIDENT_ID", incidentId)
            putExtra("FIRE_STATION_NAME", fireStationName)
            putExtra("NAME", reporterName)
            putExtra("fromNotification", true)
            putExtra("STATION_NODE", stationNodeParam)
            putExtra("REPORT_NODE", reportNode)
        }

        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@DashboardActivity, DashboardActivity::class.java))
            addNextIntent(resultIntent)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(this, CH_GENERAL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // Show toast before posting the notification
        Toast.makeText(
            applicationContext,
            "Posting notification with ID: $notificationId",
            Toast.LENGTH_SHORT
        ).show()

        // Posting the notification
        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    /* =========================================================
     * Firebase listeners
     * ========================================================= */
    private fun fetchCurrentUserName(callback: (String?) -> Unit) {
        val currentEmail = FirebaseAuth.getInstance().currentUser?.email ?: return callback(null)
        FirebaseDatabase.getInstance().getReference("Users")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (userSnap in snapshot.children) {
                        val email = userSnap.child("email").getValue(String::class.java)?.trim()
                        val name = userSnap.child("name").getValue(String::class.java)?.trim()
                        if (email.equals(currentEmail.trim(), ignoreCase = true)) {
                            user = userSnap.getValue(User::class.java)
                            callback(name)
                            return
                        }
                    }
                    callback(null)
                }
                override fun onCancelled(error: DatabaseError) = callback(null)
            })
    }
    private fun listenForResponseMessages() {
        val prefs = getSharedPreferences("user_preferences", MODE_PRIVATE)
        if (!prefs.getBoolean("notifications_enabled", true)) return

        val myName = currentUserName?.trim().orEmpty()
        val myContact = user?.contact?.trim().orEmpty()
        if (myName.isEmpty() && myContact.isEmpty()) return

        // Define all report types
        val reportTypes = listOf(
            "FireReport",
            "OtherEmergencyReport",
            "EmergencyMedicalServicesReport",
            "SmsReport"
        )

        // Iterate over all the fire stations
        for (station in stationNodes) {
            for (reportType in reportTypes) {
                // Get reference to the specific report type under AllReport for each station
                val messagesRef = database.child(station)
                    .child("AllReport")
                    .child(reportType)
                    .child("messages")

                val listener = object : ChildEventListener {
                    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
                    override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                        Toast.makeText(
                            applicationContext,
                            "New message added in $station/$reportType",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Get the incident ID and messages from the report
                        val incidentId = snapshot.key ?: return
                        val messages = snapshot.child("messages")

                        // If no messages exist, show a toast
                        if (!messages.exists()) {
                            Toast.makeText(applicationContext, "No messages found for $station/$reportType/$incidentId", Toast.LENGTH_SHORT).show()
                        }

                        // Iterate through each message in the messages node
                        for (msg in messages.children) {
                            val type = msg.child("type").getValue(String::class.java)?.lowercase() ?: ""
                            val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false

                            // Skip if the type is not 'station' or the message is marked as read
                            if (type != "station" || isRead) {
                                continue
                            }

                            // Extract message data
                            val contact = msg.child("contact").getValue(String::class.java)
                            val reporterName = msg.child("reporterName").getValue(String::class.java)
                            val text = msg.child("text").getValue(String::class.java)
                            val fireStationName = msg.child("fireStationName").getValue(String::class.java)
                            val messageId = msg.key ?: System.currentTimeMillis().toString()

                            // Generate a unique key for the notification
                            val uniqueKey = "$station::$incidentId::$messageId"
                            if (isNotificationShown(uniqueKey)) {
                                continue
                            }

                            // Check if the contact or reporter name matches the current user
                            if (contact == myContact || reporterName == myName) {
                                // Increment unread message count
                                unreadMessageCount++
                                sharedPreferences.edit()
                                    .putInt("unread_message_count", unreadMessageCount)
                                    .apply()
                                runOnUiThread { updateInboxBadge(unreadMessageCount) }

                                // Trigger the notification
                                triggerNotification(
                                    fireStationName = fireStationName ?: station,
                                    message = text,
                                    messageId = messageId,
                                    incidentId = incidentId,
                                    reporterName = reporterName,
                                    title = "New message from ${fireStationName ?: station}",
                                    stationNodeParam = station
                                )

                                // Mark the notification as shown
                                markNotificationAsShown(uniqueKey)
                            } else {
                                // Show a toast if contact or reporter name doesn't match
                                Toast.makeText(applicationContext, "Skipping message due to no match for contact or reporterName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, prev: String?) {}
                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, prev: String?) {}
                    override fun onCancelled(error: DatabaseError) {}
                }

                // Add the listener for the messages node
                messagesRef.addChildEventListener(listener)
                responseListeners += messagesRef to listener
            }
        }
    }



    private fun listenForStatusChanges() {
        val myName = currentUserName?.trim().orEmpty()
        if (myName.isEmpty()) return

        for (station in stationNodes) {
            val ref = database.child(station).child("AllReport").child("FireReport")
            ref.addChildEventListener(object : ChildEventListener {
                @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
                override fun onChildChanged(snapshot: DataSnapshot, prev: String?) {
                    val reportId = snapshot.key ?: return
                    val reporterName = snapshot.child("reporterName").getValue(String::class.java) ?: return
                    val status = snapshot.child("status").getValue(String::class.java)
                    if (reporterName == myName && status == "Ongoing") {
                        triggerNotification(
                            fireStationName = station,
                            message = "Your report is now Ongoing.",
                            messageId = reportId,
                            incidentId = reportId,
                            reporterName = reporterName,
                            title = "Status Update: Ongoing",
                            stationNodeParam = station
                        )
                    }
                }
                override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, prev: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    private fun updateUnreadMessageCount() {
        unreadMessageCount = sharedPreferences.getInt("unread_message_count", 0)
        runOnUiThread { updateInboxBadge(unreadMessageCount) }
    }

    private fun startRealtimeUnreadCounter() {
        val myContact = user?.contact?.trim().orEmpty()
        val myName = currentUserName?.trim().orEmpty()
        val reportTypes = listOf(
            "FireReport",
            "OtherEmergencyReport",
            "EmergencyMedicalServicesReport",
            "SmsReport"
        )

        for (station in stationNodes) {
            for (reportType in reportTypes) {
                val messagesRef = database.child(station)
                    .child("AllReport")
                    .child(reportType)

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        var total = 0
                        for (incidentSnap in snapshot.children) {
                            val messagesSnap = incidentSnap.child("messages")
                            for (msgSnap in messagesSnap.children) {
                                val isRead = msgSnap.child("isRead").getValue(Boolean::class.java) ?: false
                                val type = msgSnap.child("type").getValue(String::class.java)?.lowercase() ?: ""
                                val contact = msgSnap.child("contact").getValue(String::class.java)
                                val reporterName = msgSnap.child("reporterName").getValue(String::class.java)
                                if (!isRead && type == "station" &&
                                    (contact == myContact || reporterName == myName)) total++
                            }
                        }
                        unreadMessageCount = total
                        runOnUiThread { updateInboxBadge(unreadMessageCount) }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                }

                messagesRef.addValueEventListener(listener)
                unreadCounterListeners += messagesRef to listener
            }
        }
    }


    private fun isNotificationShown(key: String): Boolean =
        sharedPreferences.getBoolean(key, false)

    private fun markNotificationAsShown(key: String) {
        sharedPreferences.edit().putBoolean(key, true).apply()
    }

    private fun updateInboxBadge(count: Int) {
        val badge = binding.bottomNavigation.getOrCreateBadge(R.id.inboxFragment)
        badge.isVisible = count > 0
        badge.number = count
        badge.maxCharacterCount = 3
    }
}
