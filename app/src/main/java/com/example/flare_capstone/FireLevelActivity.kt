package com.example.flare_capstone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
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
import com.example.flare_capstone.databinding.ActivityFireLevelBinding
import com.google.android.gms.location.*
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.telephony.SmsManager


class FireLevelActivity : AppCompatActivity() {

    /* ---------------- View / Firebase / Location ---------------- */
    private lateinit var binding: ActivityFireLevelBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    /* ---------------- CameraX ---------------- */
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var capturedFile: File? = null
    private var capturedOnce = false
    private val CAMERA_PERMISSION_REQUEST_CODE = 1002

    /* ---------------- Location State ---------------- */
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    /* ---------------- Connectivity ---------------- */
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    /* ---------------- Tagum fence ---------------- */
    private val TAGUM_CENTER_LAT = 7.447725
    private val TAGUM_CENTER_LON = 125.804150
    private val TAGUM_RADIUS_METERS = 11_000f

    private var isResolvingLocation = false
    private var locationConfirmed = false
    private var readableAddress: String? = null
    private var lastReportTime: Long = 0

    /* -------- Location-confirmation dialog (non-dismissible) --- */
    private var locatingDialog: AlertDialog? = null
    private var locatingDialogMessage: TextView? = null

    /* ---------------- SMS ---------------- */
    private val SMS_PERMISSION_REQUEST_CODE = 101
    private val SENT = "FLARE_SMS_SENT"
    private val DELIVERED = "FLARE_SMS_DELIVERED"

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val msg = when (resultCode) {
                RESULT_OK -> "SMS sent."
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SMS error: generic failure."
                SmsManager.RESULT_ERROR_NO_SERVICE -> "SMS error: no service."
                SmsManager.RESULT_ERROR_NULL_PDU -> "SMS error: null PDU."
                SmsManager.RESULT_ERROR_RADIO_OFF -> "SMS error: radio off."
                else -> "SMS send result: $resultCode"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
    private val deliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val msg = if (resultCode == RESULT_OK) "SMS delivered." else "SMS not delivered."
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /* ========= Station profiles (for nearest station resolution) ========= */
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

    /* ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityFireLevelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Connectivity
        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        registerReceiver(sentReceiver, IntentFilter(SENT), RECEIVER_NOT_EXPORTED)
        registerReceiver(deliveredReceiver, IntentFilter(DELIVERED), RECEIVER_NOT_EXPORTED)

// Ask for SMS permission early (optional but nice UX)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
        }

        // Location
        beginLocationConfirmation()
        checkPermissionsAndGetLocation()

        // Camera
        checkCameraPermissionAndStart()

        // Dropdown from DB (TagumCityCentralFireStation/ManageApplication/FireReport/Option)
        populateDropdownFromDB()

        // Buttons
        binding.cancelButton.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
        binding.btnCapture.setOnClickListener {
            if (capturedOnce) retakePhoto() else captureOnce()
        }
        binding.sendButton.setOnClickListener { showSendConfirmationDialog() }

        // FCM
        FirebaseMessaging.getInstance().subscribeToTopic("all")
    }

    override fun onDestroy() {
        super.onDestroy()
        kotlin.runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        cameraExecutor.shutdown()
        locatingDialog?.dismiss()
        locatingDialog = null
        locatingDialogMessage = null
        runCatching { unregisterReceiver(sentReceiver) }
        runCatching { unregisterReceiver(deliveredReceiver) }

    }

    /* ======================== Connectivity ===================== */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                hideLoadingDialog()
                if (isResolvingLocation && !locationConfirmed) {
                    updateLocatingDialog("Confirming location…")
                }
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                showLoadingDialog("No internet connection")
                if (isResolvingLocation && !locationConfirmed) {
                    updateLocatingDialog("Waiting for internet…")
                }
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
            val view = layoutInflater.inflate(com.example.flare_capstone.R.layout.custom_loading_dialog, null)
            builder.setView(view).setCancelable(false)
            loadingDialog = builder.create()

        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(com.example.flare_capstone.R.id.loading_message)?.text = message
    }
    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    /* ======================== Permissions ====================== */
    private fun checkPermissionsAndGetLocation() {
        val fineOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk && coarseOk) requestLocationUpdates()
        else ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE)
    }
    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        when (code) {
            LOCATION_PERMISSION_REQUEST_CODE ->
                if (r.all { it == PackageManager.PERMISSION_GRANTED }) requestLocationUpdates()
                else Toast.makeText(this, "Location permission needed.", Toast.LENGTH_SHORT).show()
            CAMERA_PERMISSION_REQUEST_CODE ->
                if (r.all { it == PackageManager.PERMISSION_GRANTED }) startCameraPreview()
                else Toast.makeText(this, "Camera permission needed.", Toast.LENGTH_SHORT).show()
        }
    }

    /* =================== Dropdown from Realtime DB ============== */
    private fun populateDropdownFromDB() {  
        val db = FirebaseDatabase.getInstance().reference
            .child("TagumCityCentralFireStation")
            .child("ManageApplication")
            .child("FireReport")
            .child("Option")

        db.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Case 1: single comma-separated string
                val asString = snapshot.getValue(String::class.java)
                if (!asString.isNullOrBlank()) {
                    val items = asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val adapter = ArrayAdapter(this@FireLevelActivity, android.R.layout.simple_list_item_1, items)
                    binding.emergencyDropdown.setAdapter(adapter)
                    binding.emergencyDropdown.setOnClickListener { binding.emergencyDropdown.showDropDown() }
                    return
                }
                // Case 2: list/map children
                if (snapshot.exists() && snapshot.childrenCount > 0) {
                    val list = mutableListOf<String>()
                    snapshot.children.forEach { child ->
                        child.getValue(String::class.java)?.trim()?.takeIf { it.isNotEmpty() }?.let(list::add)
                    }
                    if (list.isNotEmpty()) {
                        val adapter = ArrayAdapter(this@FireLevelActivity, android.R.layout.simple_list_item_1, list)
                        binding.emergencyDropdown.setAdapter(adapter)
                        binding.emergencyDropdown.setOnClickListener { binding.emergencyDropdown.showDropDown() }
                    } else {
                        Toast.makeText(this@FireLevelActivity, "No options found.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@FireLevelActivity, "Option node is empty.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@FireLevelActivity, "Failed to load options: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /* ========================= CameraX ========================= */
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
        val file = File.createTempFile("fire_", ".jpg", cacheDir)
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()

        ic.takePicture(opts, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { Toast.makeText(this@FireLevelActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                capturedFile = file
                capturedOnce = true
                runOnUiThread {
                    binding.cameraPreview.visibility = View.GONE
                    binding.capturedPhoto.visibility = View.VISIBLE
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
        binding.capturedPhoto.visibility = View.GONE
        binding.cameraPreview.visibility = View.VISIBLE
        startCameraPreview()
        binding.btnCapture.text = "Capture"
    }

    /* ========================= Location ======================== */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                latitude = it.latitude
                longitude = it.longitude
                fusedLocationClient.removeLocationUpdates(this)
                updateLocatingDialog("Getting Exact Location…")
                FetchBarangayAddressTask(this@FireLevelActivity, latitude, longitude).execute()
            }
        }
    }

    private fun isWithinTagumByDistance(lat: Double, lon: Double): Boolean {
        val res = FloatArray(1)
        Location.distanceBetween(lat, lon, TAGUM_CENTER_LAT, TAGUM_CENTER_LON, res)
        return res[0] <= TAGUM_RADIUS_METERS
    }
    private fun looksLikeTagum(text: String?) = !text.isNullOrBlank() && text.contains("tagum", ignoreCase = true)

    fun handleFetchedAddress(address: String?) {
        val cleaned = address?.trim().orEmpty()
        val ok = looksLikeTagum(cleaned) || isWithinTagumByDistance(latitude, longitude)
        readableAddress = when {
            cleaned.isNotBlank() -> cleaned
            ok -> "Within Tagum vicinity – https://www.google.com/maps?q=$latitude,$longitude"
            else -> ""
        }
        if (ok) endLocationConfirmation(true, "Location confirmed${if (!readableAddress.isNullOrBlank()) ": $readableAddress" else ""}")
        else endLocationConfirmation(false, "Outside Tagum area. You can't submit a report.")
    }

    private fun beginLocationConfirmation(hint: String = "Confirming location…") {
        isResolvingLocation = true
        locationConfirmed = false
        showLocatingDialog(hint) // non-dismissible modal
    }

    private fun endLocationConfirmation(success: Boolean, message: String) {
        isResolvingLocation = false
        locationConfirmed = success
        hideLocatingDialog()
        if (message.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /* ============ Location dialog helpers (non-dismissible) ==== */
    private fun showLocatingDialog(initialMessage: String) {
        if (locatingDialog?.isShowing == true) {
            updateLocatingDialog(initialMessage)
            return
        }
        val v = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
        locatingDialogMessage = v.findViewById(R.id.loading_message)
        locatingDialogMessage?.text = initialMessage

        // CLOSE -> stop location flow and go Dashboard (or just dismiss)
        v.findViewById<TextView>(R.id.closeButton)?.setOnClickListener {
            hideLocatingDialog()
            // Optional: let user continue elsewhere
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }

        locatingDialog = AlertDialog.Builder(this@FireLevelActivity)
            .setView(v)
            .setCancelable(false)
            .create().apply {
                setCanceledOnTouchOutside(false)
                show()
            }
    }


    private fun updateLocatingDialog(message: String) {
        locatingDialogMessage?.text = message
    }

    private fun hideLocatingDialog() {
        locatingDialogMessage = null
        locatingDialog?.dismiss()
        locatingDialog = null
    }

    /* =========================== Send ========================== */
    private fun showSendConfirmationDialog() {
        if (!locationConfirmed) {
            if (!isResolvingLocation) beginLocationConfirmation()
            Toast.makeText(this, "Please wait — confirming your location…", Toast.LENGTH_SHORT).show()
            return
        }

        val type = binding.emergencyDropdown.text?.toString()?.trim().orEmpty()
        if (type.isEmpty()) {
            Toast.makeText(this, "Please choose an emergency type.", Toast.LENGTH_SHORT).show()
            return
        }

        val addr = when {
            !readableAddress.isNullOrBlank() -> readableAddress!!.trim()
            latitude != 0.0 || longitude != 0.0 -> "GPS: $latitude, $longitude"
            else -> "Not available yet"
        }

        val now = Date()
        val hasPhoto = capturedFile != null
        val msg = """
            Please confirm the details below:

            • Date: ${SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(now)}
            • Time: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)}
            • Type: $type
            • Location: $addr
            • Photo: ${if (hasPhoto) "1 attached (Base64)" else "No photo attached"}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Confirm Fire Report")
            .setMessage(msg)
            .setPositiveButton("Proceed") { _, _ -> checkAndSendAlertReport() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndSendAlertReport() {
        val now = System.currentTimeMillis()
        if (now - lastReportTime >= 5 * 60 * 1000) {
            binding.sendButton.isEnabled = false
            binding.progressIcon.visibility = View.VISIBLE
            binding.progressBar.visibility = View.VISIBLE
            binding.progressText.visibility = View.VISIBLE
            sendReportRecord(now)
        } else {
            val waitMs = 5 * 60 * 1000 - (now - lastReportTime)
            val original = binding.sendButton.text.toString()
            Toast.makeText(this, "Please wait ${waitMs / 1000} seconds before submitting again.", Toast.LENGTH_LONG).show()
            binding.sendButton.isEnabled = false
            object : CountDownTimer(waitMs, 1000) {
                override fun onTick(ms: Long) { binding.sendButton.text = "Wait (${ms / 1000})" }
                override fun onFinish() { binding.sendButton.text = original; binding.sendButton.isEnabled = true }
            }.start()
        }
    }

    /** ======= STORE: central AND nearest station (same structure) ======= */
    private fun sendReportRecord(currentTime: Long) {
        val photoBase64 = if (capturedFile != null && capturedFile!!.exists()) {
            compressAndEncodeBase64(capturedFile!!)
        } else {
            "" // Photo optional
        }

        val userId = auth.currentUser?.uid ?: run {
            resetOverlay()
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseDatabase.getInstance().getReference("Users").child(userId).get()
            .addOnSuccessListener { userSnap ->
                val user = userSnap.getValue(User::class.java) ?: run {
                    resetOverlay()
                    Toast.makeText(this, "User data not found", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val formattedDate = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date(currentTime))
                val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTime))
                val type = binding.emergencyDropdown.text?.toString()?.trim().orEmpty()

                val baseReport = mutableMapOf<String, Any?>(
                    "name" to (user.name?.toString() ?: ""),
                    "contact" to (user.contact?.toString() ?: ""),
                    "type" to type,
                    "date" to formattedDate,
                    "reportTime" to formattedTime,
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "exactLocation" to (readableAddress.orEmpty()),
                    "mapLink" to "https://www.google.com/maps?q=$latitude,$longitude",
                    "photoBase64" to photoBase64,
                    "timeStamp" to currentTime,
                    "status" to "Pending",
                    "read" to false
                )

                val db = FirebaseDatabase.getInstance().reference

                // Resolve nearest station and store ONLY there + SMS ONLY to nearest
                readAllStationProfiles { stations ->
                    if (stations.isEmpty()) {
                        resetOverlay()
                        Toast.makeText(this, "No station profiles configured. Cannot submit report.", Toast.LENGTH_LONG).show()
                        return@readAllStationProfiles
                    }

                    val nearest = stations.minByOrNull {
                        distanceMeters(latitude, longitude, it.lat, it.lon)
                    }!!

                    val stationReport = baseReport.toMutableMap().apply {
                        this["fireStationName"] = nearest.name
                    }

                    val nearestRef = db.child(nearest.node)
                        .child("AllReport")
                        .child("FireReport")
                        .push()

                    nearestRef.setValue(stationReport)
                        .addOnSuccessListener {
                            // SMS ONLY to nearest station
                            val addressText = if (!readableAddress.isNullOrBlank())
                                readableAddress!!.trim()
                            else
                                "https://www.google.com/maps?q=$latitude,$longitude"

                            val userName = user.name?.toString().orEmpty()

                            if (nearest.contact.isNotBlank()) {
                                sendStationSMS(
                                    stationContact = nearest.contact,
                                    userName = userName,
                                    type = type,
                                    date = formattedDate,
                                    time = formattedTime,
                                    addressOrMap = addressText
                                )
                            }

                            onReportStoredSuccess(
                                currentTime,
                                "Report submitted to ${nearest.name}. Please wait for responder."
                            )
                        }
                        .addOnFailureListener {
                            resetOverlay()
                            Toast.makeText(this, "Failed to submit: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .addOnFailureListener {
                resetOverlay()
                Toast.makeText(this, "Failed to fetch user data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }



    private fun onReportStoredSuccess(currentTime: Long, toastMsg: String) {
        lastReportTime = currentTime
        val originalText = binding.sendButton.text.toString()
        val waitMs = 5 * 60 * 1000
        object : CountDownTimer(waitMs.toLong(), 1000) {
            override fun onTick(ms: Long) { binding.sendButton.text = "Wait (${ms / 1000})"; binding.sendButton.isEnabled = false }
            override fun onFinish() { binding.sendButton.text = originalText; binding.sendButton.isEnabled = true }
        }.start()
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        resetOverlay()
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    /* ===== Station profiles & distance helpers ===== */

    private fun readAllStationProfiles(onDone: (List<StationInfo>) -> Unit) {
        val db = FirebaseDatabase.getInstance().reference
        val stations = listOf(
            "CapstoneFlare/LaFilipinaFireStation",
            "CapstoneFlare/CanocotanFireStation",
            "CapstoneFlare/MabiniFireStation"
        )
        val results = mutableListOf<StationInfo>()
        var pending = stations.size
        stations.forEach { node ->
            val profileKey = profileKeyByStation[node]
            if (profileKey == null) {
                if (--pending == 0) onDone(results)
                return@forEach
            }
            db.child(node).child(profileKey).get()
                .addOnSuccessListener { s ->
                    if (s.exists()) {
                        val name = s.child("name").value?.toString()?.ifBlank { node } ?: node
                        val contact = s.child("contact").value?.toString().orEmpty()
                        val lat = s.child("latitude").value?.toString()?.toDoubleOrNull() ?: 0.0
                        val lon = s.child("longitude").value?.toString()?.toDoubleOrNull() ?: 0.0
                        results.add(StationInfo(node = node, name = name, contact = contact, lat = lat, lon = lon))
                    }
                    if (--pending == 0) onDone(results)
                }
                .addOnFailureListener {
                    // ignore one failure; continue
                    if (--pending == 0) onDone(results)
                }
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val arr = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, arr)
        return arr[0]
    }

    /* =============== Image compression → Base64 (lighter) ====== */
    private fun compressAndEncodeBase64(
        file: File,
        maxDim: Int = 1024,
        initialQuality: Int = 75,
        targetBytes: Int = 400 * 1024
    ): String {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }

        fun computeSampleSize(w: Int, h: Int, maxDim: Int): Int {
            var sample = 1
            var width = w
            var height = h
            while (width / 2 >= maxDim || height / 2 >= maxDim) {
                width /= 2; height /= 2; sample *= 2
            }
            return sample
        }
        val inSample = computeSampleSize(opts.outWidth, opts.outHeight, maxDim)
        val decodeOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val decoded = FileInputStream(file).use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return ""

        val w = decoded.width
        val h = decoded.height
        val scale = maxOf(1f, maxOf(w, h) / maxDim.toFloat())
        val outW = (w / scale).toInt().coerceAtLeast(1)
        val outH = (h / scale).toInt().coerceAtLeast(1)
        val scaled = if (w > maxDim || h > maxDim) {
            android.graphics.Bitmap.createScaledBitmap(decoded, outW, outH, true)
        } else decoded
        if (scaled !== decoded) decoded.recycle()

        val baos = java.io.ByteArrayOutputStream()
        var q = initialQuality
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, baos)
        var data = baos.toByteArray()
        while (data.size > targetBytes && q > 40) {
            baos.reset()
            q -= 10
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, baos)
            data = baos.toByteArray()
        }
        if (!scaled.isRecycled) scaled.recycle()
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /* ======================= Send overlay ====================== */
    private fun resetOverlay() {
        binding.progressIcon.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.progressText.visibility = View.GONE
        binding.sendButton.isEnabled = true
    }


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

    private fun pickSmsManager(): SmsManager = SmsManager.getDefault()

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
        FLARE FIRE REPORT
        Full Name: $userName
        Type: $type
        Date-Time: $date - $time
        Location: $addressOrMap
    """.trimIndent()

        try {
            val sms = pickSmsManager()
            val sentPI = PendingIntent.getBroadcast(
                this, 0, Intent(SENT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val deliveredPI = PendingIntent.getBroadcast(
                this, 0, Intent(DELIVERED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (msg.length > 160) {
                val parts = sms.divideMessage(msg)
                val sList = ArrayList(parts.map { sentPI })
                val dList = ArrayList(parts.map { deliveredPI })
                sms.sendMultipartTextMessage(number, null, parts, sList, dList)
            } else {
                sms.sendTextMessage(number, null, msg, sentPI, deliveredPI)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


}
