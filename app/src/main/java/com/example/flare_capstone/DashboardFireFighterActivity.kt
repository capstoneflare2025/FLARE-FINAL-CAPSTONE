package com.example.flare_capstone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RawRes
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.flare_capstone.FireFighterResponseActivity
import com.example.flare_capstone.databinding.ActivityDashboardFireFighterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class DashboardFireFighterActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "FF-Notif"
        const val NOTIF_REQ_CODE = 9001

        // Channels
        const val CH_FIRE  = "ff_fire"
        const val CH_OTHER = "ff_other"
        const val CH_EMS   = "ff_ems"
        const val CH_SMS   = "ff_sms"
        const val CH_MSG   = "ff_admin_msg"
        const val OLD_CHANNEL_ID = "ff_incidents"

        // DB
        const val DB_ROOT = "CapstoneFlare"
    }


    private lateinit var binding: ActivityDashboardFireFighterBinding
    private lateinit var database: FirebaseDatabase
    private lateinit var prefs: SharedPreferences

    // Discovered at runtime after matching email → station
    private var stationId: String? = null                                    // e.g. "CanocotanFireStation"
    private var accountBase: String? = null                                  // CapstoneFlare/<Station>/FireFighter/FireFighterAccount
    private var reportsBase: String? = null                                  // .../AllReport

    // Dedupe + lifecycle
    private val shownKeys = mutableSetOf<String>()
    private val liveListeners = mutableListOf<Pair<Query, ChildEventListener>>()
    private val liveValueListeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()

    private var unreadAdminCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDashboardFireFighterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_firefighter) as NavHostFragment
        binding.bottomNavigationFirefighter.setupWithNavController(navHostFragment.navController)

        database = FirebaseDatabase.getInstance()
        prefs = getSharedPreferences("ff_notifs", MODE_PRIVATE)


        /* ADD THESE TWO LINES */
        createNotificationChannels()
        maybeRequestPostNotifPermission()

        // Resolve which station owns this email, then attach listeners ONLY there
        val email = FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase()
        Log.d(TAG, "signed-in email=$email")
        resolveStationByEmail(email) { found ->
            if (!found) {
                Log.w(TAG, "No matching station for this email; not attaching listeners.")
                return@resolveStationByEmail
            }

            // Incidents
            reportsBase?.let { base ->
                listenOne("$base/FireReport", "New FIRE report")
                listenOne("$base/OtherEmergencyReport", "New OTHER emergency")
                listenOne("$base/EmergencyMedicalServicesReport", "New EMS report")
                listenOne("$base/SmsReport", "New SMS emergency")
            }

            // Admin messages
            watchAdminUnreadCount()
            listenAdminMessagesForNotifications()

            // Handle cold-start notification deep link (after bases are ready)
            handleIntent(intent)
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun resolveStationByEmail(emailLc: String?, done: (Boolean) -> Unit) {
        if (emailLc.isNullOrBlank()) { done(false); return }

        val rootRef = database.getReference(DB_ROOT)
        rootRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(rootSnap: DataSnapshot) {
                val stations = rootSnap.children.toList()

                fun tryIdx(i: Int) {
                    if (i >= stations.size) { done(false); return }
                    val stKey = stations[i].key ?: run { tryIdx(i + 1); return }

                    val acctRef = database.getReference("$DB_ROOT/$stKey/FireFighter/FireFighterAccount")
                    acctRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(acctSnap: DataSnapshot) {
                            val dbEmail = acctSnap.child("email").getValue(String::class.java)?.trim()?.lowercase()
                            if (dbEmail != emailLc) { tryIdx(i + 1); return }

                            // email matched → decide where AllReport lives
                            stationId = stKey
                            accountBase = acctRef.ref.path.toString().removePrefix("/")

                            // Prefer .../FireFighter/FireFighterAccount/AllReport
                            val candidateA = acctSnap.child("AllReport")
                            if (candidateA.exists()) {
                                reportsBase = "$accountBase/AllReport"
                                Log.d(TAG, "Using AllReport under FireFighterAccount")
                                done(true); return
                            }

                            // Fallback: .../FireFighter/AllReport
                            val ffRef = database.getReference("$DB_ROOT/$stKey/FireFighter/AllReport")
                            ffRef.addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(ffSnap: DataSnapshot) {
                                    if (ffSnap.exists()) {
                                        reportsBase = ffRef.path.toString().removePrefix("/")
                                        Log.d(TAG, "Using AllReport under FireFighter")
                                        done(true)
                                    } else {
                                        Log.w(TAG, "No AllReport found for $stKey")
                                        done(false)
                                    }
                                }
                                override fun onCancelled(error: DatabaseError) {
                                    Log.w(TAG, "Probe AllReport fallback cancelled: ${error.message}")
                                    done(false)
                                }
                            })
                        }
                        override fun onCancelled(error: DatabaseError) {
                            Log.w(TAG, "Email read cancelled at $stKey: ${error.message}")
                            tryIdx(i + 1)
                        }
                    })
                }
                tryIdx(0)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Root read cancelled: ${error.message}")
                done(false)
            }
        })
    }


    private fun watchAdminUnreadCount() {
        val base = accountBase ?: return
        val ref = database.getReference("$base/AdminMessages")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var cnt = 0
                snapshot.children.forEach { msgSnap ->
                    val sender = msgSnap.child("sender").getValue(String::class.java) ?: ""
                    val isRead = msgSnap.child("isRead").getValue(Boolean::class.java) ?: true
                    if (sender.equals("admin", ignoreCase = true) && !isRead) cnt++
                }
                unreadAdminCount = cnt
                updateInboxBadge(cnt)
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        ref.addValueEventListener(listener)
        liveValueListeners += (ref to listener)
    }

    private fun listenAdminMessagesForNotifications() {
        val base = accountBase ?: return
        val ref  = database.getReference("$base/AdminMessages")

        // First pass to avoid historical spam
        ref.orderByChild("timestamp").limitToLast(200)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var baseTs = 0L
                    snapshot.children.forEach { c ->
                        val ts = c.child("timestamp").getValue(Long::class.java) ?: 0L
                        if (ts > baseTs) baseTs = ts
                    }
                    attachAdminRealtime(ref, baseTs)
                }
                override fun onCancelled(error: DatabaseError) {
                    attachAdminRealtime(ref, 0L)
                }
            })
    }

    private fun attachAdminRealtime(ref: DatabaseReference, baseTsMs: Long) {
        val l = object : ChildEventListener {
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildAdded(snap: DataSnapshot, prev: String?) {
                handleAdminMessageSnap(snap, baseTsMs)
            }
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildChanged(snap: DataSnapshot, prev: String?) {
                handleAdminMessageSnap(snap, baseTsMs)
            }
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(l)
        liveListeners += (ref to l)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun handleAdminMessageSnap(snap: DataSnapshot, baseTsMs: Long) {
        val id = snap.key ?: return
        val sender = snap.child("sender").getValue(String::class.java) ?: ""
        val isRead = snap.child("isRead").getValue(Boolean::class.java) ?: false
        val ts     = snap.child("timestamp").getValue(Long::class.java) ?: 0L

        if (!sender.equals("admin", ignoreCase = true)) return
        if (isRead) return
        if (ts <= baseTsMs) return

        val key = "adminmsg::$id"
        if (alreadyShown(key)) return

        showAdminMessageNotification(snap)
        markShown(key)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showAdminMessageNotification(snap: DataSnapshot) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val messageText = snap.child("text").getValue(String::class.java)
        val hasImage    = snap.hasChild("imageBase64")
        val hasAudio    = snap.hasChild("audioBase64")

        val preview = when {
            !messageText.isNullOrBlank() -> messageText
            hasImage -> "Admin sent a photo"
            hasAudio -> "Admin sent a voice message"
            else -> "New message from Admin"
        }

        val intent = Intent(this, FireFighterResponseActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_notification_admin", true)
        }
        val reqCode = ("adminmsg::${snap.key}").hashCode()
        val pending = PendingIntent.getActivity(
            this, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CH_MSG)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("New message from Admin")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)

        try {
            val notifId = ("adminmsg::${snap.key}").hashCode()
            NotificationManagerCompat.from(this).notify(notifId, builder.build())
            Log.d(TAG, "NOTIFY(admin) id=$notifId msg=$preview")
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted: ${e.message}")
        }
    }

    private fun updateInboxBadge(count: Int) {
        val menu = binding.bottomNavigationFirefighter.menu
        val inboxItem = menu.findItem(R.id.inboxFragmentFireFighter)
        if (inboxItem != null) {
            if (count > 0) {
                val badge = binding.bottomNavigationFirefighter.getOrCreateBadge(R.id.inboxFragmentFireFighter)
                badge.isVisible = true
                badge.number = count
            } else {
                binding.bottomNavigationFirefighter.removeBadge(R.id.inboxFragmentFireFighter)
            }
        }
    }

    // When the chat is opened, mark unread admin messages as read
    fun markStationAdminRead() {
        val base = accountBase ?: return
        val ref = database.getReference("$base/AdminMessages")
        ref.orderByChild("sender").equalTo("admin")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    for (msg in snap.children) {
                        val isRead = msg.child("isRead").getValue(Boolean::class.java) ?: true
                        if (!isRead) msg.ref.child("isRead").setValue(true)
                    }
                }
                override fun onCancelled(err: DatabaseError) {}
            })
    }


    /* -------------------- Firebase → Notification (once-per-id) -------------------- */

    private fun listenOne(path: String, title: String) {
        val ref = database.getReference(path)
        Log.d(TAG, "listenOne attach path=$path")

        ref.limitToLast(200).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var baseTsMs = 0L
                var existingOngoing = 0
                for (c in snapshot.children) {
                    if (statusIsOngoing(c)) {
                        existingOngoing++
                        val ts = readTimestampMillis(c) ?: 0L
                        if (ts > baseTsMs) baseTsMs = ts
                    }
                }
                Log.d(TAG, "[$path] initial done; ongoing=$existingOngoing; baseTs=$baseTsMs")
                attachRealtime(ref, path, title, baseTsMs)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "[$path] initial cancelled: ${error.message} (fallback baseTs=0)")
                attachRealtime(ref, path, title, 0L)
            }
        })
    }

    private fun attachRealtime(ref: DatabaseReference, path: String, title: String, baseTsMs: Long) {
        val q = ref.limitToLast(200)
        val l = object : ChildEventListener {
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildAdded(snap: DataSnapshot, prev: String?) {
                val id = snap.key ?: return
                val key = "$path::$id"
                val st = snap.child("status").getValue(String::class.java)
                val ts = readTimestampMillis(snap) ?: 0L
                Log.d(TAG, "ADD $key status=$st ts=$ts base=$baseTsMs shown=${alreadyShown(key)}")

                if (!statusIsOngoing(snap)) return
                if (ts <= baseTsMs) { Log.d(TAG, "→ skip ADD (old vs baseTs)"); return }
                if (alreadyShown(key)) { Log.d(TAG, "→ skip ADD (already shown)"); return }

                showIncidentNotification(title, snap, path)
                markShown(key)
            }

            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onChildChanged(snap: DataSnapshot, prev: String?) {
                val id = snap.key ?: return
                val key = "$path::$id"
                val st  = snap.child("status").getValue(String::class.java)
                Log.d(TAG, "CHG $key status=$st shown=${alreadyShown(key)}")

                if (!statusIsOngoing(snap)) return
                if (alreadyShown(key)) { Log.d(TAG, "→ skip CHG (already shown)"); return }

                showIncidentNotification(title, snap, path)
                markShown(key)
            }

            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "[$path] realtime cancelled: ${error.message}")
            }
        }

        q.addChildEventListener(l)
        liveListeners += (q to l)
        Log.d(TAG, "[$path] realtime attached (limitToLast=200)")
    }

    /* -------------------- Notification builder -------------------- */

    private fun channelForPath(path: String): String = when {
        path.endsWith("FireReport")                          -> CH_FIRE
        path.endsWith("OtherEmergencyReport")                -> CH_OTHER
        path.endsWith("EmergencyMedicalServicesReport")      -> CH_EMS
        path.endsWith("SmsReport")                           -> CH_SMS
        else                                                 -> CH_OTHER
    }

    private fun sourceForPath(path: String): String = when {
        path.endsWith("FireReport")                          -> "FIRE"
        path.endsWith("OtherEmergencyReport")                -> "OTHER"
        path.endsWith("EmergencyMedicalServicesReport")      -> "EMS"
        path.endsWith("SmsReport")                           -> "SMS"
        else                                                 -> "OTHER"
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showIncidentNotification(title: String, snap: DataSnapshot, path: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled at OS level")
            return
        }

        val id = snap.key ?: return
        val exactLocation = snap.child("exactLocation").getValue(String::class.java)
            ?: snap.child("location").getValue(String::class.java)
            ?: "Unknown location"

        val label = when (stationId) {
            "MabiniFireStation"      -> "Mabini"
            "LaFilipinaFireStation"  -> "La Filipina"
            "CanocotanFireStation"   -> "Canocotan"
            else                     -> "Unknown"
        }
        val message = "Station: $label • $exactLocation"

        val channelId = channelForPath(path)
        val srcStr = sourceForPath(path)

        val intent = Intent(this, DashboardFireFighterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_notification", true)
            putExtra("select_source", srcStr)
            putExtra("select_id", id)
        }

        val reqCode = ("$path::$id").hashCode()
        val pending = PendingIntent.getActivity(
            this, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            when (channelId) {
                CH_FIRE  -> { builder.setSound(rawSoundUri(R.raw.fire_report));  builder.setVibrate(longArrayOf(0, 600, 200, 600, 200, 600)) }
                CH_OTHER -> { builder.setSound(rawSoundUri(R.raw.other_emergency_report)); builder.setVibrate(longArrayOf(0, 400, 150, 400)) }
                CH_EMS   -> { builder.setSound(rawSoundUri(R.raw.emergecy_medical_services_report)); builder.setVibrate(longArrayOf(0, 400, 150, 400)) }
                CH_SMS   -> { builder.setSound(rawSoundUri(R.raw.sms_report)); builder.setVibrate(longArrayOf(0, 400, 150, 400)) }
            }
        }

        try {
            val notifId = ("$path::$id").hashCode()
            NotificationManagerCompat.from(this).notify(notifId, builder.build())
            Log.d(TAG, "NOTIFY id=$notifId ch=$channelId title=$title msg=$message")
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted: ${e.message}")
        }
    }

    /* -------------------- Channels -------------------- */

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL_ID) }

        recreateChannel(CH_FIRE,  "Firefighter • FIRE",  rawSoundUri(R.raw.fire_report), false)
        recreateChannel(CH_OTHER, "Firefighter • OTHER", rawSoundUri(R.raw.other_emergency_report), false)
        recreateChannel(CH_EMS,   "Firefighter • EMS",   rawSoundUri(R.raw.emergecy_medical_services_report), false)
        recreateChannel(CH_SMS,   "Firefighter • SMS",   rawSoundUri(R.raw.sms_report), true)
        recreateChannel(CH_MSG,   "Firefighter • Admin Messages", rawSoundUri(R.raw.message_notif), true)
    }

    private fun recreateChannel(id: String, name: String, soundUri: Uri?, useDefault: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.deleteNotificationChannel(id) }

        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        ch.enableVibration(true)

        val aa = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (useDefault) {
            val defaultUri = soundUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ch.setSound(defaultUri, aa)
        } else if (soundUri != null) {
            ch.setSound(soundUri, aa)
        }

        nm.createNotificationChannel(ch)
        Log.d(TAG, "channel created ($id) sound=${soundUri?.toString() ?: "default"}")
    }

    private fun rawSoundUri(@RawRes res: Int): Uri =
        Uri.parse("android.resource://$packageName/$res")


    /* -------------------- Timestamp helpers -------------------- */

    private fun statusIsOngoing(snap: DataSnapshot): Boolean {
        val raw = snap.child("status").getValue(String::class.java)?.trim() ?: return false
        val norm = raw.replace("-", "").lowercase()
        return norm == "ongoing"
    }

    private fun getLongRelaxed(node: DataSnapshot, key: String): Long? {
        val v = node.child(key).value
        return when (v) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }

    private fun getEpochFromDateTime(node: DataSnapshot): Long? {
        val dateStr = node.child("date").getValue(String::class.java)?.trim()
        val timeStr = node.child("time").getValue(String::class.java)?.trim()
        if (dateStr.isNullOrEmpty() || timeStr.isNullOrEmpty()) return null
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            fmt.timeZone = java.util.TimeZone.getDefault()
            fmt.parse("$dateStr $timeStr")?.time
        } catch (_: Exception) { null }
    }

    private fun readTimestampMillis(node: DataSnapshot): Long? {
        val raw = getLongRelaxed(node, "acceptedAt")
            ?: getLongRelaxed(node, "timeStamp")
            ?: getLongRelaxed(node, "timestamp")
            ?: getLongRelaxed(node, "time")
            ?: getEpochFromDateTime(node)
            ?: return null
        val ms = if (raw in 1..9_999_999_999L) raw * 1000 else raw
        return if (ms > 0) ms else null
    }

    /* -------------------- Dedupe & permissions -------------------- */

    private fun alreadyShown(key: String): Boolean =
        shownKeys.contains(key) || prefs.getBoolean(key, false)

    private fun markShown(key: String) {
        shownKeys.add(key)
        prefs.edit().putBoolean(key, true).apply()
        Log.d(TAG, "markShown $key")
    }

    private fun maybeRequestPostNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "notif permission granted=$granted")
            if (!granted) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQ_CODE)
        }
    }

    /* -------------------- Intent handoff to fragment -------------------- */

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("from_notification", false) == true) {
            val srcStr = intent.getStringExtra("select_source")
            val id     = intent.getStringExtra("select_id")
            if (!srcStr.isNullOrBlank() && !id.isNullOrBlank()) {
                Log.d(TAG, "deliverSelectionToHome src=$srcStr id=$id")
                deliverSelectionToHome(srcStr, id)
            }
        }
    }

    private fun deliverSelectionToHome(srcStr: String, id: String) {
        supportFragmentManager.setFragmentResult(
            "select_incident",
            Bundle().apply {
                putString("source", srcStr) // "FIRE" | "OTHER" | "EMS" | "SMS"
                putString("id", id)
            }
        )
    }

    /* -------------------- Cleanup -------------------- */

    override fun onDestroy() {
        super.onDestroy()
        liveListeners.forEach { (q, l) -> q.removeEventListener(l) }
        liveValueListeners.forEach { (ref, l) -> ref.removeEventListener(l) }
        liveListeners.clear()
        liveValueListeners.clear()
    }

}
