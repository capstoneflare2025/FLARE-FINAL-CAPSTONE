package com.example.flare_capstone

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flare_capstone.FireLevelActivity
import com.example.flare_capstone.databinding.ActivityOtherEmergencyBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class OtherEmergencyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOtherEmergencyBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var connectivityManager: ConnectivityManager

    // dropdown selection
    private var selectedEmergency: String? = null

    // location
    private var latitude = 0.0
    private var longitude = 0.0
    private var exactLocation: String = ""

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val CAMERA_PERMISSION_REQUEST_CODE = 1002
    private val SMS_PERMISSION_REQUEST_CODE = 2003

    // Tagum geofence
    private val TAGUM_CENTER_LAT = 7.447725
    private val TAGUM_CENTER_LON = 125.804150
    private val TAGUM_RADIUS_METERS = 11_000f
    private var tagumOk = false
    private var locationConfirmed = false
    private var isResolvingLocation = false

    // dialogs
    private var loadingDialog: AlertDialog? = null
    private var locatingDialog: AlertDialog? = null
    private var locatingDialogMsg: TextView? = null

    // CameraX (optional photo)
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var capturedFile: File? = null
    private var capturedOnce = false

    private val SENT = "FLARE_SMS_SENT"
    private val DELIVERED = "FLARE_SMS_DELIVERED"

    // Station profile mapping (same as FireLevel)
    private val profileKeyByStation = mapOf(
        "CapstoneFlare/LaFilipinaFireStation" to "Profile",
        "CapstoneFlare/CanocotanFireStation"  to "Profile",
        "CapstoneFlare/MabiniFireStation"     to "Profile"
    )

    private data class StationInfo(
        val node: String,
        val name: String,
        val contact: String,
        val lat: Double,
        val lon: Double
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityOtherEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ask for SMS permission early
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
        }

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // network watcher
        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // location gate
        beginLocationConfirmation()
        checkPermissionsAndGetLocation()

        // dropdown options from RTDB
        populateDropdownFromDB()

        // camera (optional)
        checkCameraPermissionAndStart()
        binding.btnCapture.setOnClickListener {
            if (capturedOnce) retakePhoto() else captureOnce()
        }

        binding.cancelButton.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
        binding.sendButton.setOnClickListener { onSendClicked() }

        updateSendEnabled()
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onDestroy() {
        super.onDestroy()
        kotlin.runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        locatingDialog?.dismiss()
        locatingDialog = null
        locatingDialogMsg = null
        cameraExecutor.shutdown()
    }

    /* ---------------- Connectivity ---------------- */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                hideLoadingDialog()
                if (isResolvingLocation && !locationConfirmed) updateLocatingDialog("Getting Exact Location…")
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                showLoadingDialog("No internet connection")
                if (isResolvingLocation && !locationConfirmed) updateLocatingDialog("Waiting for internet…")
            }
        }
    }

    private fun isConnected(): Boolean {
        val n = connectivityManager.activeNetwork ?: return false
        val c = connectivityManager.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String) {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val view = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
            builder.setView(view).setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(R.id.loading_message)?.text = message
    }
    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    /* ---------------- Permissions & Location ---------------- */
    private fun checkPermissionsAndGetLocation() {
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk && coarseOk) getLastLocation()
        else ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getLastLocation() {
        updateLocatingDialog("Getting GPS fix…")
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                latitude = loc.latitude
                longitude = loc.longitude
                FetchBarangayAddressTask(this, latitude, longitude).execute()
                evaluateTagumGateWith(null)
                updateLocatingDialog("Getting Exact Location…")
            } else {
                val req = LocationRequest.create().apply {
                    interval = 10_000L; fastestInterval = 5_000L
                    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                    numUpdates = 1
                }
                fusedLocationClient.requestLocationUpdates(req, object : com.google.android.gms.location.LocationCallback() {
                    override fun onLocationResult(res: com.google.android.gms.location.LocationResult) {
                        val l = res.lastLocation ?: return
                        latitude = l.latitude; longitude = l.longitude
                        fusedLocationClient.removeLocationUpdates(this)
                        FetchBarangayAddressTask(this@OtherEmergencyActivity, latitude, longitude).execute()
                        evaluateTagumGateWith(null)
                        updateLocatingDialog("Getting Exact Location…")
                    }
                }, mainLooper)

                updateLocatingDialog("Waiting for GPS…")
            }
        }.addOnFailureListener {
            updateLocatingDialog("Location error — retrying…")
            evaluateTagumGateWith(null)
        }
    }

    /** Called by reverse-geocoding task */
    fun handleFetchedAddress(address: String?) {
        exactLocation = address?.trim().orEmpty().ifEmpty { "Unknown Location" }
        evaluateTagumGateWith(address)

        if (tagumOk) {
            val msg = if (exactLocation.isNotBlank() && exactLocation != "Unknown Location")
                "Location confirmed: $exactLocation"
            else
                "Location confirmed within Tagum vicinity"
            endLocationConfirmation(true, msg)
        } else {
            endLocationConfirmation(false, "Outside Tagum area. You can't submit a report.")
        }
    }

    private fun evaluateTagumGateWith(address: String?) {
        val textOk = !address.isNullOrBlank() && address.contains("tagum", ignoreCase = true)
        val geoOk  = isWithinTagumByDistance(latitude, longitude)
        tagumOk = textOk || geoOk

        if (tagumOk && (address.isNullOrBlank() || address == "Unknown Location") && geoOk) {
            exactLocation = "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"
        }
        updateSendEnabled()
    }

    private fun isWithinTagumByDistance(lat: Double, lon: Double): Boolean {
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, TAGUM_CENTER_LAT, TAGUM_CENTER_LON, out)
        return out[0] <= TAGUM_RADIUS_METERS
    }

    /* ---------------- Non-dismissible locating dialog ---------------- */
    private fun beginLocationConfirmation(hint: String = "Confirming location…") {
        isResolvingLocation = true
        locationConfirmed = false
        showLocatingDialog(hint)
    }

    private fun endLocationConfirmation(success: Boolean, toast: String = "") {
        isResolvingLocation = false
        locationConfirmed = success
        hideLocatingDialog()
        if (toast.isNotBlank()) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
        updateSendEnabled()
    }

    private fun showLocatingDialog(initialText: String) {
        if (locatingDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
        locatingDialogMsg = view.findViewById(R.id.loading_message)
        locatingDialogMsg?.text = initialText

        view.findViewById<TextView>(R.id.closeButton)?.setOnClickListener {
            hideLocatingDialog()
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }

        locatingDialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        locatingDialog?.show()
    }
    private fun updateLocatingDialog(text: String) { locatingDialogMsg?.text = text }
    private fun hideLocatingDialog() { locatingDialog?.dismiss(); locatingDialog = null; locatingDialogMsg = null }



    /* Send SMS to Users Nearest*/

    // --- Location string normalization & matching ---
    private fun normLoc(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.lowercase()
            .replace("ñ", "n")
            .replace(Regex("""\b(brgy\.?|barangay)\b"""), "")   // drop "Barangay/Brgy"
            .replace(Regex("""\bcity\b"""), "")                  // drop "City"
            .replace(Regex("""[^a-z0-9]+"""), " ")               // keep alnum, collapse others
            .trim()
    }


    // Simple token-based match, forgiving about punctuation/order.
// e.g. "Barangay San Miguel, Tagum City" ≈ "San Miguel Tagum"
    private fun locationsMatch(a: String?, b: String?): Boolean {
        val A = normLoc(a)
        val B = normLoc(b)
        if (A.isBlank() || B.isBlank()) return false
        if (A == B) return true
        // require at least two shared tokens to reduce false positives
        val toksA = A.split(' ').filter { it.length >= 3 }.toSet()
        val toksB = B.split(' ').filter { it.length >= 3 }.toSet()
        val overlap = toksA.intersect(toksB).size
        return overlap >= 2
    }

    // Optional: distance-based fallback when both sides have lat/lon
    private fun closeBy(lat1: Double?, lon1: Double?, lat2: Double?, lon2: Double?, meters: Float = 3000f): Boolean {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return false
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0] <= meters
    }

    private fun sendUserSMS(numberRaw: String, msg: String) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
            return
        }
        val number = normalizePhNumber(numberRaw)
        if (!looksLikePhone(number)) return

        try {
            val sms = pickSmsManager()
            val sentPI = PendingIntent.getBroadcast(this, 0, Intent(SENT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val deliveredPI = PendingIntent.getBroadcast(this, 0, Intent(DELIVERED), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (msg.length > 160) {
                val parts = sms.divideMessage(msg)
                val sList = ArrayList(parts.map { sentPI })
                val dList = ArrayList(parts.map { deliveredPI })
                sms.sendMultipartTextMessage(number, null, parts, sList, dList)
            } else {
                sms.sendTextMessage(number, null, msg, sentPI, deliveredPI)
            }
        } catch (_: Exception) { /* already toasts via receivers */ }
    }

    private fun notifyNearbyUsers(
        reporterUid: String,
        incidentType: String,
        date: String,
        time: String,
        incidentExactLoc: String,
        incidentLat: Double,
        incidentLon: Double
    ) {
        val usersRef = FirebaseDatabase.getInstance().getReference("Users")

        usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sentTo = mutableSetOf<String>()   // dedupe by phone
                var sentCount = 0
                val cap = 100  // safety cap

                for (u in snapshot.children) {
                    if (sentCount >= cap) break

                    val uid = u.key ?: continue
                    if (uid == reporterUid) continue

                    val contact = u.child("contact").getValue(String::class.java)?.trim().orEmpty()
                    if (contact.isBlank()) continue

                    val userExact = u.child("exactLocation").getValue(String::class.java)
                    val userLat   = u.child("latitude").getValue(Double::class.java)
                    val userLon   = u.child("longitude").getValue(Double::class.java)

                    val match = locationsMatch(incidentExactLoc, userExact) ||
                            closeBy(incidentLat, incidentLon, userLat, userLon, 3000f)

                    if (!match) continue

                    val normalized = normalizePhNumber(contact)
                    if (!looksLikePhone(normalized)) continue
                    if (!sentTo.add(normalized)) continue   // skip duplicates

                    val msg = """
                    FLARE OTHER EMERGENCY ALERT
                    Type: $incidentType
                    When: $date $time
                    Location: $incidentExactLoc
                 
                """.trimIndent()

                    sendUserSMS(normalized, msg)
                    sentCount++
                }

                if (sentCount > 0) {
                    Toast.makeText(this@OtherEmergencyActivity, "Alert sent to $sentCount nearby users.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@OtherEmergencyActivity, "User scan cancelled: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /* ---------------- Dropdown from RTDB ---------------- */
    private fun populateDropdownFromDB() {
        val ref = FirebaseDatabase.getInstance().reference
            .child("TagumCityCentralFireStation")
            .child("ManageApplication")
            .child("OtherEmergencyReport")
            .child("Option")

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val asString = s.getValue(String::class.java)
                if (!asString.isNullOrBlank()) {
                    val items = asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    setDropdown(items); return
                }
                if (s.exists() && s.childrenCount > 0) {
                    val list = mutableListOf<String>()
                    s.children.forEach { c ->
                        c.getValue(String::class.java)?.trim()?.takeIf { it.isNotEmpty() }?.let(list::add)
                    }
                    setDropdown(list)
                } else {
                    Toast.makeText(this@OtherEmergencyActivity, "No options found.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(e: DatabaseError) {
                Toast.makeText(this@OtherEmergencyActivity, "Failed to load options: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setDropdown(items: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        binding.otherEmergencyDropdown.setAdapter(adapter)
        binding.otherEmergencyDropdown.setOnClickListener { binding.otherEmergencyDropdown.showDropDown() }
        binding.otherEmergencyDropdown.setOnItemClickListener { _, _, pos, _ ->
            selectedEmergency = items[pos]
            binding.toolbar.title = "Other Emergency"
            updateSendEnabled()
        }
    }

    /* ---------------- CameraX (optional) ---------------- */
    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    private fun startCameraPreview() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera start failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureOnce() {
        val ic = imageCapture ?: return
        val file = File.createTempFile("other_", ".jpg", cacheDir)
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(opts, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { Toast.makeText(this@OtherEmergencyActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                capturedFile = file
                capturedOnce = true
                runOnUiThread {
                    binding.cameraPreview.visibility = android.view.View.GONE
                    binding.capturedPhoto.visibility = android.view.View.VISIBLE
                    binding.capturedPhoto.setImageURI(Uri.fromFile(file))
                    binding.btnCapture.text = "Retake"
                }
            }
        })
    }

    private fun retakePhoto() {
        try { capturedFile?.delete() } catch (_: Exception) {}
        capturedFile = null
        capturedOnce = false
        binding.capturedPhoto.setImageDrawable(null)
        binding.capturedPhoto.visibility = android.view.View.GONE
        binding.cameraPreview.visibility = android.view.View.VISIBLE
        startCameraPreview()
        binding.btnCapture.text = "Capture"
    }

    /* ---------------- Send flow: CENTRAL + NEAREST ---------------- */
    private fun onSendClicked() {
        if (!locationConfirmed || !tagumOk) {
            Toast.makeText(this, "Please wait — confirming your location…", Toast.LENGTH_SHORT).show()
            if (!isResolvingLocation) beginLocationConfirmation()
            return
        }
        val type = selectedEmergency?.trim().orEmpty()
        if (type.isEmpty()) {
            Toast.makeText(this, "Please choose an emergency type.", Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date(now))
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        val addr = exactLocation.ifBlank { "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude" }
        val hasPhoto = capturedFile != null

        val msg = """
            Please confirm the details below:

            • Type: $type
            • Date: $dateStr
            • Time: $timeStr
            • Location: $addr
            • Photo: ${if (hasPhoto) "1 attached (Base64)" else "No photo attached"}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Confirm Emergency Report")
            .setMessage(msg)
            .setPositiveButton("Proceed") { _, _ -> sendReportRecord(now, type) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ======================= Send overlay ====================== */
    private fun resetOverlay() {
        binding.progressIcon.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.progressText.visibility = View.GONE
        binding.sendButton.isEnabled = true
    }


    private fun sendReportRecord(currentTime: Long, type: String) {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        val userId = auth.currentUser?.uid ?: run {
            resetOverlay()
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseDatabase.getInstance().getReference("Users").child(uid).get()
            .addOnSuccessListener { snap ->
                val user = snap.getValue(User::class.java) ?: run {
                    Toast.makeText(this, "User data not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val dateFmt = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                val photoB64 = capturedFile?.takeIf { it.exists() }?.let { compressAndEncodeBase64(it) } ?: ""
                val dateStr = dateFmt.format(Date(currentTime))
                val timeStr = timeFmt.format(Date(currentTime))
                val addressText = if (exactLocation.isNotBlank())
                    exactLocation
                else
                    "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"


                val formattedDate = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date(currentTime))
                val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTime))
                val type = binding.otherEmergencyDropdown.text?.toString()?.trim().orEmpty()

                // Keep your current schema (strings for lat/lon)
                val base = mutableMapOf<String, Any?>(
                    "emergencyType" to type,
                    "name" to user.name.orEmpty(),
                    "contact" to user.contact.orEmpty(),
                    "date" to dateStr,
                    "reportTime" to timeStr,
                    "latitude" to latitude.toString(),
                    "longitude" to longitude.toString(),
                    "location" to "https://www.google.com/maps?q=$latitude,$longitude",
                    "exactLocation" to addressText,
                    "timestamp" to currentTime,
                    "status" to "Pending",
                    "read" to false,
                    "photoBase64" to photoB64
                )

                val db = FirebaseDatabase.getInstance().reference

                // Resolve nearest station and write ONLY there
                readAllStationProfiles { stations ->
                    if (stations.isEmpty()) {
                        Toast.makeText(this, "No station profiles configured. Cannot submit report.", Toast.LENGTH_LONG).show()
                        return@readAllStationProfiles
                    }

                    val nearest = stations.minByOrNull {
                        distanceMeters(latitude, longitude, it.lat, it.lon)
                    }!!

                    val payload = base.toMutableMap().apply {
                        this["fireStationName"] = nearest.name
                    }

                    db.child(nearest.node)
                        .child("AllReport")
                        .child("OtherEmergencyReport")
                        .push()
                        .setValue(payload)
                        .addOnSuccessListener {
                            // SMS ONLY to nearest station (if contact present)
                            val userName = user.name.orEmpty()
                            if (nearest.contact.isNotBlank()) {
                                sendStationSMS(
                                    stationContact = nearest.contact,
                                    userName = userName,
                                    type = type,
                                    date = dateStr,
                                    time = timeStr,
                                    addressOrMap = addressText
                                )
                            }


                            notifyNearbyUsers(
                                reporterUid = userId,
                                incidentType = type,
                                date = formattedDate,
                                time = formattedTime,
                                incidentExactLoc = addressText,         // use the same text you SMS’d to the station
                                incidentLat = latitude,
                                incidentLon = longitude
                            )

                            Toast.makeText(this, "Report submitted to ${nearest.name}. Please wait for responder.", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, DashboardActivity::class.java))
                            finish()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Failed to submit: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to fetch user data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }


    /* ===== Station profiles & distance helpers ===== */
    private fun readAllStationProfiles(onDone: (List<StationInfo>) -> Unit) {
        val db = FirebaseDatabase.getInstance().reference
        val stations = listOf("CapstoneFlare/LaFilipinaFireStation", "CapstoneFlare/CanocotanFireStation", "CapstoneFlare/MabiniFireStation")
        val results = mutableListOf<StationInfo>()
        var pending = stations.size
        stations.forEach { node ->
            val key = profileKeyByStation[node]
            if (key == null) { if (--pending == 0) onDone(results); return@forEach }
            db.child(node).child(key!!).get()
                .addOnSuccessListener { s ->
                    if (s.exists()) {
                        val name = s.child("name").value?.toString()?.ifBlank { node } ?: node
                        val contact = s.child("contact").value?.toString().orEmpty()
                        val lat = s.child("latitude").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val lon = s.child("longitude").value?.toString()?.toDoubleOrNull() ?: 0.0
                        results.add(StationInfo(node, name, contact, lat, lon))
                    }
                    if (--pending == 0) onDone(results)
                }
                .addOnFailureListener { if (--pending == 0) onDone(results) }
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val arr = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, arr)
        return arr[0]
    }

    /* ---------------- Utils ---------------- */
    private fun compressAndEncodeBase64(
        file: File,
        maxDim: Int = 1024,
        initialQuality: Int = 75,
        targetBytes: Int = 400 * 1024
    ): String {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }

        fun sampleSize(w: Int, h: Int, maxD: Int): Int {
            var s = 1; var W = w; var H = h
            while (W / 2 >= maxD || H / 2 >= maxD) { W /= 2; H /= 2; s *= 2 }
            return s
        }
        val decode = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bmp = FileInputStream(file).use { android.graphics.BitmapFactory.decodeStream(it, null, decode) } ?: return ""
        val (w, h) = bmp.width to bmp.height
        val scale = maxOf(1f, maxOf(w, h) / maxDim.toFloat())
        val outW = (w / scale).toInt().coerceAtLeast(1)
        val outH = (h / scale).toInt().coerceAtLeast(1)
        val scaled = if (w > maxDim || h > maxDim) android.graphics.Bitmap.createScaledBitmap(bmp, outW, outH, true) else bmp
        if (scaled !== bmp) bmp.recycle()
        val baos = java.io.ByteArrayOutputStream()
        var q = initialQuality
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, baos)
        var data = baos.toByteArray()
        while (data.size > targetBytes && q > 40) {
            baos.reset(); q -= 10
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, baos)
            data = baos.toByteArray()
        }
        if (!scaled.isRecycled) scaled.recycle()
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /* ---------------- Permission result ---------------- */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    getLastLocation()
                } else {
                    Toast.makeText(this, "Location permission needed.", Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    startCameraPreview()
                } else {
                    Toast.makeText(this, "Camera permission needed.", Toast.LENGTH_SHORT).show()
                }
            }
            SMS_PERMISSION_REQUEST_CODE -> {
                // no-op; we re-check before sending
            }
        }
    }

    private fun updateSendEnabled() {
        val hasCoords = !(latitude == 0.0 && longitude == 0.0)
        binding.sendButton.isEnabled = (selectedEmergency != null) && tagumOk && hasCoords && locationConfirmed
    }

    // ---------- SMS + Contacts helpers ----------
    private fun normalizePhNumber(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var n = raw.filter { it.isDigit() || it == '+' }
        n = when {
            n.startsWith("+63") && n.length == 13 -> n
            n.startsWith("0") && n.length == 11   -> "+63" + n.drop(1)
            n.length == 10 && n.first() == '9'    -> "+63$n"
            else -> n
        }
        return n
    }
    private fun looksLikePhone(n: String): Boolean =
        n.startsWith("+") && n.count { it.isDigit() } in 10..15

    private fun pickSmsManager(): android.telephony.SmsManager =
        android.telephony.SmsManager.getDefault()

    private fun sendStationSMS(
        stationContact: String,
        userName: String,
        type: String,
        date: String,
        time: String,
        addressOrMap: String
    ) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(this, "SMS permission not granted.", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
            return
        }

        val number = normalizePhNumber(stationContact)
        if (!looksLikePhone(number)) {
            Toast.makeText(this, "Station contact invalid: $stationContact", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = """
        FLARE OTHER EMERGENCY
        Name: $userName
        Type: $type
        Date-Time: $date $time
        Location: $addressOrMap
    """.trimIndent()

        try {
            val sms = pickSmsManager()
            val parts = sms.divideMessage(msg)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sms.sendTextMessage(number, null, msg, null, null)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

}
