package com.example.flare_capstone

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flare_capstone.databinding.ActivitySmsBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

class ReportSmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var db: AppDatabase

    // CapstoneFlare stations (for nearest computation ONLY)
    private val capstoneStations = listOf(
        FireStation("Canocotan Fire Station", "09368646953", 7.4217617292640785, 125.79018416901866),
        FireStation("Mabini Fire Station", "09388931442", 7.450150854535532, 125.79529166335233),
        FireStation("La Filipina Fire Station", "09663041569", 7.4768350720999655, 125.8054726056261)
    )

    // Maps the human-readable station name to its RTDB node
    private val stationNodeByName = mapOf(
        "La Filipina Fire Station" to "CapstoneFlare/LaFilipinaFireStation",
        "Canocotan Fire Station"   to "CapstoneFlare/CanocotanFireStation",
        "Mabini Fire Station"      to "CapstoneFlare/MabiniFireStation"
    )

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val SMS_PERMISSION_REQUEST_CODE = 101

    companion object {
        const val SMS_SENT_ACTION = "SMS_SENT_ACTION"
        const val EXTRA_TO = "extra_to"
        const val EXTRA_STATION = "extra_station"
    }

    private var tagumRings: List<List<LatLng>>? = null
    private var tagumLoaded = false

    // Dropdown selections
    private var selectedCategory: String? = null
    private var selectedDetails: String? = null

    val nowTimestamp = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Load Tagum boundary
        loadTagumBoundaryFromRaw()

        // Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)

        // SMS sent receiver
        registerReceiver(smsSentReceiver, IntentFilter(SMS_SENT_ACTION), RECEIVER_NOT_EXPORTED)

        binding.logo.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        if (!isSimAvailable()) {
            Toast.makeText(this, "No SIM card detected. Cannot send SMS.", Toast.LENGTH_LONG).show()
        }

        // ------------------ DROPDOWNS ------------------
        val categories = resources.getStringArray(R.array.category_options)
        val categoryAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, categories)
        binding.categoryDropdown.setAdapter(categoryAdapter)

        binding.categoryDropdown.setOnItemClickListener { _, _, pos, _ ->
            selectedCategory = categories[pos]
            val detailsArray = when (selectedCategory) {
                "Fire Report" -> resources.getStringArray(R.array.fire_report_options)
                "Emergency Medical Services" -> resources.getStringArray(R.array.ems_options)
                "Other Emergency" -> resources.getStringArray(R.array.other_emergency_options)
                else -> emptyArray()
            }
            val detailsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, detailsArray)
            binding.detailsDropdown.setAdapter(detailsAdapter)
            binding.detailsDropdown.setText("")
            selectedDetails = null
        }

        binding.detailsDropdown.setOnItemClickListener { _, _, pos, _ ->
            selectedDetails = binding.detailsDropdown.adapter.getItem(pos).toString()
        }

        binding.sendReport.setOnClickListener {
            val name = binding.name.text.toString().trim()
            val location = binding.location.text.toString().trim()
            val category = selectedCategory
            val details = selectedDetails

            if (name.isEmpty() || location.isEmpty() || category.isNullOrEmpty() || details.isNullOrEmpty()) {
                Toast.makeText(this, "Complete all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            getCurrentLocation { userLocation ->
                if (userLocation == null) {
                    Toast.makeText(this, "Failed to get location.", Toast.LENGTH_LONG).show()
                    return@getCurrentLocation
                }

                if (!isInsideTagum(userLocation)) {
                    Toast.makeText(this, "Reporting restricted to Tagum City only.", Toast.LENGTH_LONG).show()
                    return@getCurrentLocation
                }

                val (nearest, distMeters) = findNearestCapstoneStation(userLocation.latitude, userLocation.longitude)
                val combinedDetails = "$category - $details"

                val fullMessage = buildReportMessage(
                    name = name,
                    location = location,
                    fireReport = combinedDetails,
                    stationName = nearest.name,
                    nearestName = nearest.name,
                    nearestMeters = distMeters
                )

                confirmSendSms(
                    phoneNumber = nearest.contact,
                    message = fullMessage,
                    userLocation = userLocation,
                    stationName = nearest.name,
                    nearestStationForDb = nearest,
                    nearestDistanceMetersForDb = distMeters,
                    combinedDetails = combinedDetails
                )
            }
        }
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (resultCode) {
                AppCompatActivity.RESULT_OK -> Toast.makeText(applicationContext, "Report SMS sent.", Toast.LENGTH_SHORT).show()
                SmsManager.RESULT_ERROR_GENERIC_FAILURE,
                SmsManager.RESULT_ERROR_NO_SERVICE,
                SmsManager.RESULT_ERROR_NULL_PDU,
                SmsManager.RESULT_ERROR_RADIO_OFF -> {
                    Toast.makeText(applicationContext, "Failed to send SMS. Check load/signal.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    data class FireStation(val name: String, val contact: String, val latitude: Double, val longitude: Double)

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Long {
        val R = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val s1 = Math.sin(dLat / 2)
        val s2 = Math.sin(dLon / 2)
        val aa = s1 * s1 +
                Math.cos(Math.toRadians(aLat)) *
                Math.cos(Math.toRadians(bLat)) *
                s2 * s2
        val c = 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa))
        return (R * c).toLong()
    }

    private fun findNearestCapstoneStation(lat: Double, lon: Double): Pair<FireStation, Long> {
        return capstoneStations
            .map { it to distanceMeters(lat, lon, it.latitude, it.longitude) }
            .minBy { it.second } // Find the nearest fire station
    }

    private fun buildReportMessage(
        name: String,
        location: String,
        fireReport: String,
        stationName: String,
        nearestName: String?,
        nearestMeters: Long?
    ): String {
        val (date, time) = getCurrentDateTime()
        val nearestLine = if (nearestName != null && nearestMeters != null)
            "\nNEAREST STATION SUGGESTION:\n$nearestName (${String.format(Locale.getDefault(), "%.1f", nearestMeters / 1000.0)} km)"
        else ""
        return """
        FIRE REPORT SUBMITTED

        FIRE STATION: $stationName

        NAME: $name
        
        LOCATION: $location
       
        REPORT DETAILS: $fireReport
        
        DATE: $date
       
        TIME: $time
    """.trimIndent()
    }

    private fun uploadPendingReports(db: AppDatabase) {
        val dao = db.reportDao()

        CoroutineScope(Dispatchers.IO).launch {
            val pendingReports = dao.getPendingReports()
            for (report in pendingReports) {
                val nearestStation = findNearestCapstoneStation(report.latitude, report.longitude).first

                // Store report under the nearest fire station
                val reportMap = mutableMapOf(
                    "name" to report.name,
                    "location" to report.location,
                    "fireReport" to report.fireReport,
                    "date" to report.date,
                    "time" to report.time,
                    "latitude" to report.latitude,
                    "longitude" to report.longitude,
                    "fireStationName" to nearestStation.name,
                    "contact" to nearestStation.contact,
                    "status" to "Pending",
                    "timestamp" to nowTimestamp
                )

                // Reference the nearest station node
                val stationNode = stationNodeByName[nearestStation.name]
                if (stationNode != null) {
                    FirebaseDatabase.getInstance().reference
                        .child(stationNode)
                        .child("AllReport")
                        .child("SmsReport")
                        .push()
                        .setValue(reportMap)
                        .addOnSuccessListener {
                            CoroutineScope(Dispatchers.IO).launch { dao.deleteReport(report.id) }
                        }
                        .addOnFailureListener {
                            // Keep the local pending report in case of failure
                        }
                }
            }
        }
    }

    private fun getCurrentDateTime(): Pair<String, String> {
        val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val now = Date()
        return Pair(dateFormat.format(now), timeFormat.format(now))
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = cm.activeNetworkInfo
        return info != null && info.isConnected
    }

    private fun isSimAvailable(): Boolean {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.simState == TelephonyManager.SIM_STATE_READY
    }

    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) callback(location)
                else requestLocationUpdates(callback)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestLocationUpdates(callback: (Location?) -> Unit) {
        val req = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { callback(it) }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }, null)
    }

    private fun confirmSendSms(
        phoneNumber: String,
        message: String,
        userLocation: Location,
        stationName: String,
        nearestStationForDb: FireStation,
        nearestDistanceMetersForDb: Long,
        combinedDetails: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("Send Report")
            .setMessage("Send this report via SMS?")
            .setPositiveButton("Yes") { _, _ ->
                val name = binding.name.text.toString().trim()
                val locationText = binding.location.text.toString().trim()
                val fireReport = combinedDetails
                val (date, time) = getCurrentDateTime()

                val report = SmsReport(
                    name = name,
                    location = locationText,
                    fireReport = fireReport,
                    date = date,
                    time = time,
                    latitude = userLocation.latitude,
                    longitude = userLocation.longitude,
                    fireStationName = nearestStationForDb.name // Save the nearest station's name
                )

                CoroutineScope(Dispatchers.IO).launch {
                    db.reportDao().insertReport(report)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ReportSmsActivity, "Report saved locally (pending).", Toast.LENGTH_SHORT).show()
                        if (isInternetAvailable()) uploadPendingReports(db)
                        sendSms(phoneNumber, message, nearestStationForDb.name) // Send to nearest station
                    }
                }
            }
            .setNegativeButton("No") { d, _ -> d.dismiss() }
            .show()
    }

    private fun sendSms(phoneNumber: String, message: String, stationName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST_CODE)
            return
        }
        try {
            val smsManager = SmsManager.getDefault()
            val sentIntent = Intent(SMS_SENT_ACTION).apply {
                putExtra(EXTRA_TO, phoneNumber)
                putExtra(EXTRA_STATION, stationName)
            }
            val flags = if (Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val sentPI = PendingIntent.getBroadcast(this, 0, sentIntent, flags)

            if (message.length > 160) {
                val parts = smsManager.divideMessage(message)
                val sentIntents = MutableList(parts.size) { sentPI }
                smsManager.sendMultipartTextMessage(
                    phoneNumber, null, parts,
                    sentIntents as ArrayList<PendingIntent?>?, null
                )
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, sentPI, null)
            }

            Toast.makeText(this, "SMS sending…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- Tagum City Polygon Geofence ---
    private fun loadTagumBoundaryFromRaw() {
        try {
            val ins = resources.openRawResource(R.raw.tagum_boundary)
            val text = BufferedReader(InputStreamReader(ins)).use { it.readText() }
            val root = JSONObject(text)

            fun arrToRing(arr: JSONArray): List<LatLng> {
                val out = ArrayList<LatLng>()
                for (i in 0 until arr.length()) {
                    val pt = arr.getJSONArray(i)
                    val lon = pt.getDouble(0)
                    val lat = pt.getDouble(1)
                    out.add(LatLng(lat, lon))
                }
                return out
            }

            val rings = mutableListOf<List<LatLng>>()
            when (root.optString("type")) {
                "Polygon" -> {
                    val coords = root.getJSONArray("coordinates")
                    if (coords.length() > 0) rings.add(arrToRing(coords.getJSONArray(0)))
                }
                "MultiPolygon" -> {
                    val mcoords = root.getJSONArray("coordinates")
                    for (i in 0 until mcoords.length()) {
                        val poly = mcoords.getJSONArray(i)
                        if (poly.length() > 0) rings.add(arrToRing(poly.getJSONArray(0)))
                    }
                }
                "FeatureCollection" -> {
                    val feats = root.getJSONArray("features")
                    for (i in 0 until feats.length()) {
                        val geom = feats.getJSONObject(i).getJSONObject("geometry")
                        val type = geom.getString("type")
                        if (type == "Polygon") {
                            val coords = geom.getJSONArray("coordinates")
                            if (coords.length() > 0) rings.add(arrToRing(coords.getJSONArray(0)))
                        }
                    }
                }
            }
            tagumRings = rings
            tagumLoaded = rings.isNotEmpty()
        } catch (_: Exception) {
            tagumRings = null
            tagumLoaded = false
        }
    }

    private fun isInsideTagum(loc: Location): Boolean {
        if (!tagumLoaded) return false
        val rings = tagumRings ?: return false
        val pt = LatLng(loc.latitude, loc.longitude)
        return rings.any { ring -> pointInRing(pt, ring) }
    }

    private fun pointInRing(pt: LatLng, ring: List<LatLng>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i].longitude
            val yi = ring[i].latitude
            val xj = ring[j].longitude
            val yj = ring[j].latitude
            val intersects = ((yi > pt.latitude) != (yj > pt.latitude)) &&
                    (pt.longitude < (xj - xi) * (pt.latitude - yi) / ((yj - yi) + 0.0) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }
}
