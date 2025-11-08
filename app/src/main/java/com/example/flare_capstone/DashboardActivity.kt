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

import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.firebase.database.ServerValue
import com.google.firebase.auth.FirebaseUser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter


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


    // Other fields and code ...

    // Hardcoded station nodes
    private val stationReportNodes = listOf(
        "CanocotanFireStation",
        "LaFilipinaFireStation",
        "MabiniFireStation"
    )

    // top-level fields in DashboardActivity
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var dashLat: Double = 0.0
    private var dashLon: Double = 0.0
    private var dashReadableAddress: String? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val responseListeners = mutableListOf<Pair<Query, ChildEventListener>>()
    private val unreadCounterListeners = mutableListOf<Pair<Query, ValueEventListener>>()

    // Top-level fields in DashboardActivity
    private var locationCallback: LocationCallback? = null
    private var lastWriteMs = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0


    private var isNetworkValidated = false
    private var isNetworkSlow = true
    private var isInitialFirebaseReady = false

    private companion object {
        private const val CH_GENERAL = "default_channel_v3"
    }

    private object ReportNodes {
        const val FIRE  = "AllReport/FireReport"
        const val OTHER = "AllReport/OtherEmergencyReport"
        const val EMS   = "AllReport/EmergencyMedicalServicesReport"
        const val SMS   = "AllReport/SmsReport"

        // If you also need just the type names:
        const val FIRE_TYPE  = "FireReport"
        const val OTHER_TYPE = "OtherEmergencyReport"
        const val EMS_TYPE   = "EmergencyMedicalServicesReport"
        const val SMS_TYPE   = "SmsReport"
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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        checkPermissionsAndGetLocation() // <- add this line


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

    override fun onStart() {
        super.onStart()
        startLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        stopLocationUpdates()
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


    private fun checkPermissionsAndGetLocation() {
        val fineOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk && coarseOk) requestOneLocation()
        else requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == LOCATION_PERMISSION_REQUEST_CODE && res.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestOneLocation()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestOneLocation() {
        val req = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, /* interval */ 10_000L
        ).setMaxUpdates(1).build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                dashLat = loc.latitude
                dashLon = loc.longitude
                fusedLocationClient.removeLocationUpdates(this)

                // reuse your existing AsyncTask to derive barangay + city
                FetchBarangayAddressTask(this@DashboardActivity, dashLat, dashLon).execute()
            }
        }
        fusedLocationClient.requestLocationUpdates(req, cb, mainLooper)
    }

    // Called by FetchBarangayAddressTask.onPostExecute(...)
    fun handleFetchedAddress(address: String?) {
        dashReadableAddress = address?.takeIf { it.isNotBlank() }
            ?: "https://www.google.com/maps?q=$dashLat,$dashLon" // fallback

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w("Dashboard", "No authenticated user; cannot store location.")
            return
        }

        val updates = mapOf(
            "latitude" to dashLat,
            "longitude" to dashLon,
            "exactLocation" to (dashReadableAddress ?: ""),
            "mapLink" to "https://www.google.com/maps?q=$dashLat,$dashLon",
            // optional, handy to know when this snapshot was taken:
            "locationUpdatedAt" to ServerValue.TIMESTAMP
        )

        FirebaseDatabase.getInstance().getReference("Users")
            .child(uid)
            .updateChildren(updates)
            .addOnSuccessListener { Log.d("Dashboard", "User location stored.") }
            .addOnFailureListener { e -> Log.e("Dashboard", "Failed to store location: ${e.message}") }
    }



    private fun startLocationUpdates() {
        val fineOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineOk && !coarseOk) return

        // 15s interval, 10m distance filter; tweak as needed
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                dashLat = loc.latitude
                dashLon = loc.longitude

                // Throttle DB writes: only if moved ≥10m OR every 30s
                val now = System.currentTimeMillis()
                val movedEnough = distanceMeters(lastLat, lastLon, dashLat, dashLon) >= 10
                val timeEnough = (now - lastWriteMs) >= 30_000L
                if (movedEnough || timeEnough) {
                    lastLat = dashLat
                    lastLon = dashLon
                    lastWriteMs = now
                    // Update address each time (or every Nth time if you want to save geocoding)
                    FetchBarangayAddressTask(this@DashboardActivity, dashLat, dashLon).execute()
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(req, locationCallback!!, mainLooper)
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    // Small helper (haversine via Android's built-in)
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val res = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, res)
        return res[0]
    }

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
        stationNodeParam: String,
        reportNodeParam: String        // keep this
    ) {
        val notificationId = (stationNodeParam + "::" + messageId).hashCode()

        val resultIntent = Intent(this, FireReportResponseActivity::class.java).apply {
            putExtra("INCIDENT_ID", incidentId)
            putExtra("FIRE_STATION_NAME", fireStationName)
            putExtra("NAME", reporterName)
            putExtra("fromNotification", true)
            putExtra("STATION_NODE", stationNodeParam)
            putExtra("REPORT_NODE", reportNodeParam)  // pass through
        }

        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@DashboardActivity, DashboardActivity::class.java))
            addNextIntent(resultIntent)
            // UNIQUE requestCode so extras don’t get reused
            getPendingIntent(notificationId, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(this, CH_GENERAL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

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

        val reportTypes = listOf(
            ReportNodes.FIRE_TYPE,
            ReportNodes.OTHER_TYPE,
            ReportNodes.EMS_TYPE,   // ✅ not “EmergencyMedicalReport”
            ReportNodes.SMS_TYPE
        )

        for (station in stationNodes) {
            for (reportType in reportTypes) {
                val reportRef = database.child(station).child("AllReport").child(reportType)

                // 1) Find latest message timestamp to avoid historical spam
                reportRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        var baseTs = 0L
                        for (incident in snap.children) {
                            val messages = incident.child("messages")
                            for (msg in messages.children) {
                                val ts = msg.child("timestamp").getValue(Long::class.java) ?: 0L
                                if (ts > baseTs) baseTs = ts
                            }
                        }
                        attachIncidentListeners(reportRef, station, reportType, myName, myContact, baseTs)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        attachIncidentListeners(reportRef, station, reportType, myName, myContact, 0L)
                    }
                })
            }
        }
    }

    private fun attachIncidentListeners(
        reportRef: DatabaseReference,
        station: String,
        reportType: String,
        myName: String,
        myContact: String,
        baseTsMs: Long
    ) {
        // Listen for each incident; messages may change inside it
        val incidentListener = object : ChildEventListener {
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildAdded(incidentSnap: DataSnapshot, prev: String?) = scanMessages(incidentSnap, false)
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildChanged(incidentSnap: DataSnapshot, prev: String?) = scanMessages(incidentSnap, true)
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}


            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            private fun scanMessages(incidentSnap: DataSnapshot, changed: Boolean) {
                val incidentId = incidentSnap.key ?: return
                val messages = incidentSnap.child("messages")

                // Map reportType -> report node path

                val node = when (reportType) {
                    ReportNodes.FIRE_TYPE  -> ReportNodes.FIRE
                    ReportNodes.OTHER_TYPE -> ReportNodes.OTHER
                    ReportNodes.EMS_TYPE   -> ReportNodes.EMS
                    ReportNodes.SMS_TYPE   -> ReportNodes.SMS
                    else -> ReportNodes.FIRE
                }



                for (msg in messages.children) {
                    val messageId = msg.key ?: continue
                    val type = msg.child("type").getValue(String::class.java)?.lowercase() ?: ""
                    val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: false
                    val contact = msg.child("contact").getValue(String::class.java)
                    val reporterName = msg.child("reporterName").getValue(String::class.java)
                    val fireStationName = msg.child("fireStationName").getValue(String::class.java) ?: station
                    val text = msg.child("text").getValue(String::class.java)
                    val ts = msg.child("timestamp").getValue(Long::class.java) ?: 0L

                    // Ignore old messages from before we attached listeners
                    if (ts <= baseTsMs) continue

                    // Only for me + unread + sent by station
                    if (type != "station" || isRead) continue
                    if (!(contact == myContact || reporterName == myName)) continue

                    val uniqueKey = "$station::$reportType::$incidentId::$messageId"
                    if (isNotificationShown(uniqueKey)) continue

                    unreadMessageCount++
                    sharedPreferences.edit().putInt("unread_message_count", unreadMessageCount).apply()
                    runOnUiThread { updateInboxBadge(unreadMessageCount) }

                    triggerNotification(
                        fireStationName = fireStationName,
                        message = text,
                        messageId = messageId,
                        incidentId = incidentId,
                        reporterName = reporterName,
                        title = "New message from $fireStationName",
                        stationNodeParam = station,
                        reportNodeParam = node
                    )
                    markNotificationAsShown(uniqueKey)
                }
            }
        }

        val q: Query = reportRef.limitToLast(200)
        q.addChildEventListener(incidentListener)
        responseListeners += (q to incidentListener)
    }


    private fun listenForStatusChanges() {
        val myName = currentUserName?.trim().orEmpty()
        if (myName.isEmpty()) return

        // Which report types to watch (reuse your constants)
        val types = listOf(
            ReportNodes.FIRE_TYPE,
            ReportNodes.OTHER_TYPE,
            ReportNodes.EMS_TYPE,
            ReportNodes.SMS_TYPE
        )

        // Map type -> full report node path used by FireReportResponseActivity
        fun nodeFor(type: String) = when (type) {
            ReportNodes.FIRE_TYPE  -> ReportNodes.FIRE
            ReportNodes.OTHER_TYPE -> ReportNodes.OTHER
            ReportNodes.EMS_TYPE   -> ReportNodes.EMS
            ReportNodes.SMS_TYPE   -> ReportNodes.SMS
            else -> ReportNodes.FIRE
        }

        // Normalize status like "ongoing", "On-Going", "ON GOING" → "ongoing"
        fun normStatus(raw: String?): String {
            val s = raw?.trim()?.lowercase() ?: return ""
            return s.replace("-", "").replace(" ", "")
        }

        for (station in stationNodes) {
            for (type in types) {
                val ref = database.child(station).child("AllReport").child(type)

                ref.addChildEventListener(object : ChildEventListener {
                    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
                    override fun onChildChanged(snapshot: DataSnapshot, prev: String?) {
                        val reportId = snapshot.key ?: return
                        val reporterName = snapshot.child("reporterName").getValue(String::class.java) ?: return
                        val status = normStatus(snapshot.child("status").getValue(String::class.java))

                        // Only notify if it's my report AND status is ongoing
                        if (reporterName == myName && status == "ongoing") {
                            triggerNotification(
                                fireStationName = station,
                                message = "Your report is now Ongoing.",
                                messageId = reportId,
                                incidentId = reportId,
                                reporterName = reporterName,
                                title = "Status Update: Ongoing",
                                stationNodeParam = station,
                                reportNodeParam = nodeFor(type) // <- correct node for the detail screen
                            )
                        }
                    }

                    // You may also want to catch initial transitions posted as new children:
                    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
                    override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                        // Optional: same body as onChildChanged if you want first-write to trigger too
                        // (guard with a baseline timestamp if you fear historical spam)
                    }

                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, prev: String?) {}
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
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
            ReportNodes.FIRE_TYPE,
            ReportNodes.OTHER_TYPE,
            ReportNodes.EMS_TYPE,   // ✅ not “EmergencyMedicalReport”
            ReportNodes.SMS_TYPE
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
