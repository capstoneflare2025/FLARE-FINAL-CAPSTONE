package com.example.flare_capstone.BFP.BFP_FRAGMENT

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.FragmentHomeFireFighterBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class HomeFireFighterFragment : Fragment(), OnMapReadyCallback {

    private val TAG = "HomeFF"

    private var _binding: FragmentHomeFireFighterBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocation: FusedLocationProviderClient

    // Google Maps
    private var gMap: GoogleMap? = null
    private var mapReady = false

    // Pins
    private var myMarker: Marker? = null
    private val incidentMarkers = mutableMapOf<String, Marker>() // key -> marker

    // Current selection
    private var selectedIncidentKey: String? = null
    private var currentReportPoint: LatLng? = null
    private var lastMyPoint: LatLng? = null

    // Stable numbering
    private val numberMap = mutableMapOf<String, Int>()
    private var nextNumber = 1

    // Pending external selection
    private var pendingSelect: Pair<Source, String>? = null

    // Firebase base (per-station structure)
    private var stationRoot: String? = null
    private var stationAccountKey: String? = null
    private var reportsBase: String? = null

    // child listeners
    private val liveListeners = mutableListOf<Pair<Query, ChildEventListener>>()

    // All incidents in memory
    private data class Incident(
        val key: String,
        val id: String,
        val source: Source,
        val latLng: LatLng,
        val status: String,
        val timestamp: Long
    )
    private val incidents = mutableMapOf<String, Incident>() // key -> incident

    // Route polylines
    private data class OsrmRoute(val points: List<LatLng>, val durationSec: Long, val distanceMeters: Long)
    private data class DrawnRoute(
        val polyline: Polyline,
        val route: OsrmRoute,
        var isPrimary: Boolean,
        val isShortest: Boolean
    )
    private val drawnRoutes = mutableListOf<DrawnRoute>()

    // Camera recenter thresholds
    private var lastCameraMy: LatLng? = null
    private var lastCameraIncident: LatLng? = null
    private val recenterMeters = 25f

    // Routing throttle
    private val bg = Executors.newSingleThreadExecutor()
    private var lastRoutedOrigin: LatLng? = null
    private var lastRoutedDest: LatLng? = null
    private val routeRecomputeMeters = 25f

    private enum class Source { FIRE, OTHER, EMS, SMS }

    private val locationPerms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grant ->
        val ok = (grant[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (grant[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        if (ok) startLocationUpdates() else Log.w(TAG, "Location permission denied; navigation limited")
        enableMyLocationUiIfPermitted()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeFireFighterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        auth = FirebaseAuth.getInstance()
        fusedLocation = LocationServices.getFusedLocationProviderClient(requireContext())

        // Attach a SupportMapFragment into mapContainer
        val existing = childFragmentManager.findFragmentById(binding.mapContainer.id) as? SupportMapFragment
        val mapFragment = existing ?: SupportMapFragment.newInstance().also {
            childFragmentManager.beginTransaction().replace(binding.mapContainer.id, it).commit()
            childFragmentManager.executePendingTransactions()
        }
        mapFragment.getMapAsync(this)

        // Resolve station root + account from email
        val info = stationInfoForEmail(auth.currentUser?.email?.lowercase())
        if (info == null) {
            Log.w(TAG, "Unknown firefighter email; abort")
            return
        }
        stationRoot = info.first
        stationAccountKey = info.second

        // Base path for all 4 report types under new structure:
        // <StationRoot>/FireFighter/FireFighterAccount/<AccountKey>/AllReport
        // AllReport sits directly under FireFighterAccount (no account key)
        reportsBase = "${stationRoot}/FireFighter/FireFighterAccount/AllReport"


        binding.completed.setOnClickListener { markCompleted() }

        attachReportListeners()
        ensureLocationPermission()

        // Tap the status bar to open the current incident details
        binding.selectedInfo.isClickable = true
        binding.selectedInfo.setOnClickListener {
            selectedIncidentKey?.let { key -> incidents[key]?.let { showIncidentDetails(it) } }
        }

        // Optional: receive selection from an Activity
        requireActivity().supportFragmentManager.setFragmentResultListener("select_incident", viewLifecycleOwner) { _, b ->
            val srcStr = b.getString("source") ?: return@setFragmentResultListener
            val id = b.getString("id") ?: return@setFragmentResultListener
            val src = runCatching { Source.valueOf(srcStr) }.getOrNull() ?: return@setFragmentResultListener
            pendingSelect = src to id
            trySelectPendingSelection(showDetails = true)
        }
    }

    private fun stationInfoForEmail(email: String?): Pair<String, String>? {
        val e = email ?: return null
        return when (e) {
            "tccfsff123@gmail.com" -> "CapstoneFlare/CanocotanFireStation" to "CanocotanFireFighterAccount"
            "lffssff123@gmail.com" -> "CapstoneFlare/LaFilipinaFireStation" to "LaFilipinaFireFighterAccount"
            "tcwfssff123@gmail.com" -> "CapstoneFlare/MabiniFireStation" to "MabiniFireFighterAccount"
            else -> null
        }
    }

    private fun trySelectPendingSelection(showDetails: Boolean = false): Boolean {
        val p = pendingSelect ?: return false
        val (src, id) = p
        val key = "${src.name}/$id"
        val inc = incidents[key] ?: return false
        selectIncident(key, animateCamera = true)
        if (showDetails) showIncidentDetails(inc)
        pendingSelect = null
        return true
    }

    // ---------- Lifecycle cleanup ----------
    override fun onDestroyView() {
        detachReportListeners()
        stopLocationUpdates()
        _binding = null
        super.onDestroyView()
    }

    // ---------- Permissions / MyLocation ----------
    private fun hasLocationPermission(): Boolean {
        val ctx = context ?: return false
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            startLocationUpdates()
            enableMyLocationUiIfPermitted()
        } else {
            reqPerms.launch(locationPerms)
        }
    }

    private fun enableMyLocationUiIfPermitted() {
        try {
            if (hasLocationPermission()) {
                gMap?.isMyLocationEnabled = true
                gMap?.uiSettings?.isMyLocationButtonEnabled = true
            }
        } catch (_: SecurityException) { }
    }

    // ---------- Map + Markers ----------
    override fun onMapReady(map: GoogleMap) {
        gMap = map
        mapReady = true

        gMap?.uiSettings?.isZoomControlsEnabled = true
        gMap?.uiSettings?.isCompassEnabled = true
        gMap?.isTrafficEnabled = true
        gMap?.moveCamera(CameraUpdateFactory.zoomTo(15f))
        enableMyLocationUiIfPermitted()

        gMap?.setOnPolylineClickListener { tapped ->
            val dr = drawnRoutes.find { it.polyline == tapped } ?: return@setOnPolylineClickListener
            val mins = max(1, (dr.route.durationSec / 60).toInt())
            val km = (dr.route.distanceMeters / 100.0).roundToInt() / 10.0
            highlightRoute(dr)
            selectedIncidentKey?.let { key ->
                incidentMarkers[key]?.let { mk ->
                    mk.snippet = "${mins}m • ${km}km"
                    mk.showInfoWindow()
                }
                updateSelectedInfo(etaMins = mins, distKm = km)
            }
            Toast.makeText(requireContext(), if (dr.isShortest) "Shortest route" else "Alternative route", Toast.LENGTH_SHORT).show()
        }

        gMap?.setOnMarkerClickListener { marker ->
            if (marker == myMarker) return@setOnMarkerClickListener false
            val key = marker.tag as? String ?: return@setOnMarkerClickListener false
            selectIncident(key, animateCamera = true)
            marker.showInfoWindow()
            true
        }

        gMap?.setOnInfoWindowClickListener { marker ->
            if (marker != myMarker) {
                val key = marker.tag as? String ?: return@setOnInfoWindowClickListener
                incidents[key]?.let { showIncidentDetails(it) }
            }
        }

        renderAllIncidentsIfNeeded()
        trySelectPendingSelection()
        updatePins(lastMyPoint, currentReportPoint)
    }

    private fun renderAllIncidentsIfNeeded() {
        if (!mapReady) return
        incidents.values.forEach { if (!incidentMarkers.containsKey(it.key)) addOrUpdateMarker(it) }
        val toRemove = incidentMarkers.keys - incidents.keys
        toRemove.forEach { k -> incidentMarkers.remove(k)?.remove() }
    }

    private fun updatePins(myLoc: LatLng?, reportLoc: LatLng?) {
        val map = gMap ?: return

        if (myLoc != null) {
            lastMyPoint = myLoc
            if (myMarker == null) {
                myMarker = gMap?.addMarker(
                    MarkerOptions()
                        .position(myLoc)
                        .title("You")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                )
            } else {
                myMarker?.position = myLoc
            }
        }

        val needRecenter =
            (lastCameraMy == null || (myLoc != null && distanceMeters(lastCameraMy!!, myLoc) > recenterMeters)) ||
                    (lastCameraIncident == null || (reportLoc != null && distanceMeters(lastCameraIncident!!, reportLoc) > recenterMeters))

        if (needRecenter) {
            when {
                myLoc != null && reportLoc != null -> {
                    val bounds = LatLngBounds.builder().include(myLoc).include(reportLoc).build()
                    try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
                    catch (_: Exception) { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
                    lastCameraMy = myLoc
                    lastCameraIncident = reportLoc
                }
                myLoc != null -> {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(myLoc, 15f))
                    lastCameraMy = myLoc
                }
                reportLoc != null -> {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(reportLoc, 15f))
                    lastCameraIncident = reportLoc
                }
            }
        }

        val origin = lastMyPoint
        val dest = currentReportPoint
        if (origin != null && dest != null && shouldRecomputeRoutes(origin, dest)) {
            fetchAndDrawOsrmRoutes(origin, dest)
        }
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Float {
        val res = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, res)
        return res[0]
    }

    // Live location
    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val last: Location = result.lastLocation ?: return
            val lat = last.latitude
            val lng = last.longitude

            updatePins(LatLng(lat, lng), currentReportPoint)
            updateLiveLocation(lat, lng)
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1_000L
        )
            .setMinUpdateIntervalMillis(1_000L)
            .setMaxUpdateDelayMillis(0L)
            .setMinUpdateDistanceMeters(1f)
            .build()

        try {
            fusedLocation.requestLocationUpdates(req, locCallback, requireActivity().mainLooper)
        } catch (_: SecurityException) {
            Log.w(TAG, "requestLocationUpdates SecurityException")
        }
    }

    private fun stopLocationUpdates() {
        try { fusedLocation.removeLocationUpdates(locCallback) } catch (_: Exception) {}
    }

    // ---------- Firebase listeners for 4 types ----------
    private fun attachReportListeners() {
        val base = reportsBase ?: return
        listenLive("$base/FireReport", Source.FIRE)
        listenLive("$base/OtherEmergencyReport", Source.OTHER)
        listenLive("$base/EmergencyMedicalServicesReport", Source.EMS)
        listenLive("$base/SmsReport", Source.SMS)
    }

    private fun detachReportListeners() {
        liveListeners.forEach { (q, l) -> q.removeEventListener(l) }
        liveListeners.clear()
    }

    private fun listenLive(path: String, src: Source) {
        val q = FirebaseDatabase.getInstance().getReference(path).limitToLast(200)
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?)   = applyChild(s, src)
            override fun onChildChanged(s: DataSnapshot, prev: String?) = applyChild(s, src)
            override fun onChildRemoved(s: DataSnapshot)                = removeChild(s.key, src)
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { Log.e(TAG, "listenLive[$path] cancelled: ${e.message}") }
        }
        q.addChildEventListener(l)
        liveListeners += q to l
    }

    private fun isOngoing(status: String?): Boolean =
        status?.trim()?.replace("-", "")?.equals("ongoing", ignoreCase = true) == true

    private fun applyChild(c: DataSnapshot, src: Source) {
        val id = c.key ?: return
        val key = "${src.name}/$id"

        val status = c.child("status").getValue(String::class.java)
        if (!isOngoing(status)) {
            removeChild(id, src)
            return
        }

        val lat = getDoubleRelaxed(c, "latitude") ?: getDoubleRelaxed(c, "lat")
        val lon = getDoubleRelaxed(c, "longitude") ?: getDoubleRelaxed(c, "lng")
        if (lat == null || lon == null) {
            Log.w(TAG, "applyChild: missing lat/lon for $key")
            return
        }

        val ts = readTimestampMillis(c) ?: System.currentTimeMillis()
        val inc = Incident(key, id, src, LatLng(lat, lon), "Ongoing", ts)

        incidents[key] = inc
        if (!numberMap.containsKey(key)) numberMap[key] = nextNumber++

        if (mapReady) addOrUpdateMarker(inc)
        if (selectedIncidentKey == null || !incidents.containsKey(selectedIncidentKey)) ensureSelection()

        pendingSelect?.let { (ps, pid) ->
            if (ps == src && pid == id) trySelectPendingSelection(showDetails = true)
        }
    }

    private fun removeChild(id: String?, src: Source) {
        val key = id?.let { "${src.name}/$it" } ?: return
        incidents.remove(key)
        incidentMarkers.remove(key)?.remove()

        if (selectedIncidentKey == key) {
            selectedIncidentKey = null
            currentReportPoint = null
            clearAllRoutes()
            ensureSelection()
        }
        if (incidents.isEmpty()) {
            numberMap.clear()
            nextNumber = 1
            updateSelectedInfo()
        }
    }

    private fun addOrUpdateMarker(inc: Incident) {
        val number = numberMap[inc.key] ?: 0
        val title = when (inc.source) {
            Source.FIRE  -> "[FIRE REPORT #$number]"
            Source.OTHER -> "[OTHER EMERGENCY #$number]"
            Source.EMS   -> "[EMS REPORT #$number]"
            Source.SMS   -> "[SMS REPORT #$number]"
        }
        @DrawableRes val iconRes = when (inc.source) {
            Source.FIRE  -> R.drawable.ic_pin_fire
            Source.OTHER -> R.drawable.ic_pin_other
            Source.EMS   -> R.drawable.ic_pin_ems
            Source.SMS   -> R.drawable.ic_pin_sms
        }
        val icon = bitmapFromVector(iconRes)

        val existing = incidentMarkers[inc.key]
        if (existing == null) {
            val mk = gMap?.addMarker(
                MarkerOptions()
                    .position(inc.latLng)
                    .title(title)
                    .snippet("Status: ${inc.status}")
                    .icon(icon)
            )
            mk?.tag = inc.key
            if (mk != null) incidentMarkers[inc.key] = mk
            Log.d(TAG, "marker+ ${inc.key} @ ${inc.latLng}")
        } else {
            existing.position = inc.latLng
            existing.title = title
            existing.setIcon(icon)
            if (existing.tag == null) existing.tag = inc.key
            Log.d(TAG, "marker↑ ${inc.key} @ ${inc.latLng}")
        }
    }

    // ---------- Selection ----------
    private fun ensureSelection() {
        selectedIncidentKey?.let {
            if (incidents.containsKey(it)) {
                selectIncident(it, animateCamera = false)
                return
            }
        }
        val next = incidents.values.minByOrNull { numberMap[it.key] ?: Int.MAX_VALUE } ?: return
        selectIncident(next.key, animateCamera = true)
    }

    private fun selectIncident(key: String, animateCamera: Boolean) {
        val inc = incidents[key] ?: return
        selectedIncidentKey = key
        currentReportPoint = inc.latLng

        incidentMarkers[key]?.snippet = "Status: ${inc.status}"
        updateSelectedInfo()

        if (animateCamera) {
            try { gMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(inc.latLng, 15f)) } catch (_: Exception) {}
        }
        updatePins(lastMyPoint, currentReportPoint)
    }

    // ---------- Firebase value helpers ----------
    private fun getDoubleRelaxed(node: DataSnapshot, key: String): Double? {
        val v = node.child(key).value
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
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
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            fmt.timeZone = TimeZone.getDefault()
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

    // ---------- Complete ----------
    // ---------- Complete ----------
    private fun markCompleted() {
        val selKey = selectedIncidentKey ?: return
        val inc = incidents[selKey] ?: return

        val db = FirebaseDatabase.getInstance().reference

        val typeNode = when (inc.source) {
            Source.FIRE  -> "FireReport"
            Source.OTHER -> "OtherEmergencyReport"
            Source.EMS   -> "EmergencyMedicalServicesReport"
            Source.SMS   -> "SmsReport"
        }

        // All station roots you support
        val stationRoots = listOf(
            "CapstoneFlare/CanocotanFireStation",
            "CapstoneFlare/LaFilipinaFireStation",
            "CapstoneFlare/MabiniFireStation"
        )

        // We’ll check TWO shapes per station:
        // A) <root>/FireFighter/FireFighterAccount/AllReport/<Type>/<id>
        // B) <root>/AllReport/<Type>/<id>
        val updates = hashMapOf<String, Any>()
        var pendingChecks = stationRoots.size * 2
        var anyFound = false

        fun maybeFlush() {
            if (pendingChecks != 0) return
            if (!anyFound) {
                Toast.makeText(requireContext(), "Nothing to update", Toast.LENGTH_SHORT).show()
                return
            }
            db.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Marked as Completed", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Completed id=${inc.id} src=${inc.source} across stations")
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "Complete failed: ${e.message}")
                }
        }

        stationRoots.forEach { root ->
            // A) Firefighter account bucket
            val ffPath = "$root/FireFighter/FireFighterAccount/AllReport/$typeNode/${inc.id}"
            db.child(ffPath).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    if (s.exists()) {
                        anyFound = true
                        updates["$ffPath/status"] = "Completed"
                        updates["$ffPath/completedAt"] = ServerValue.TIMESTAMP
                    }
                    if (--pendingChecks == 0) maybeFlush()
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "FF check failed: ${error.message}")
                    if (--pendingChecks == 0) maybeFlush()
                }
            })

            // B) Station rollup bucket
            val rollupPath = "$root/AllReport/$typeNode/${inc.id}"
            db.child(rollupPath).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    if (s.exists()) {
                        anyFound = true
                        updates["$rollupPath/status"] = "Completed"
                        updates["$rollupPath/completedAt"] = ServerValue.TIMESTAMP
                    }
                    if (--pendingChecks == 0) maybeFlush()
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Rollup check failed: ${error.message}")
                    if (--pendingChecks == 0) maybeFlush()
                }
            })
        }
    }


    // ---------- OSRM routing ----------
    private fun shouldRecomputeRoutes(origin: LatLng, dest: LatLng): Boolean {
        val prevO = lastRoutedOrigin
        val prevD = lastRoutedDest
        if (prevO == null || prevD == null) {
            lastRoutedOrigin = origin
            lastRoutedDest = dest
            return true
        }
        val movedO = distanceMeters(prevO, origin)
        val movedD = distanceMeters(prevD, dest)
        return if (movedO > routeRecomputeMeters || movedD > routeRecomputeMeters) {
            lastRoutedOrigin = origin
            lastRoutedDest = dest
            true
        } else false
    }

    private fun fetchAndDrawOsrmRoutes(origin: LatLng, dest: LatLng) {
        bg.execute {
            val all = fetchOsrmRoutes(origin, dest)
            requireActivity().runOnUiThread {
                val map = gMap ?: return@runOnUiThread
                clearAllRoutes()

                if (all.isEmpty()) {
                    Toast.makeText(requireContext(), "No route found", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val sorted = all.sortedBy { it.distanceMeters }
                sorted.forEachIndexed { index, r ->
                    val isPrimary = index == 0
                    val polyOpts = PolylineOptions()
                        .addAll(r.points)
                        .width(if (isPrimary) 12f else 8f)
                        .color(if (isPrimary) 0xFF2962FF.toInt() else 0x802962FF.toInt())
                        .zIndex(if (isPrimary) 2f else 1f)
                        .clickable(true)

                    if (!isPrimary) polyOpts.pattern(listOf(Dot(), Gap(14f)))

                    val pl = map.addPolyline(polyOpts)
                    drawnRoutes += DrawnRoute(pl, r, isPrimary, isShortest = index == 0)
                }

                drawnRoutes.firstOrNull { it.isPrimary }?.route?.let { r ->
                    val mins = max(1, (r.durationSec / 60).toInt())
                    val km = (r.distanceMeters / 100.0).roundToInt() / 10.0
                    selectedIncidentKey?.let { key ->
                        incidentMarkers[key]?.let { mk ->
                            mk.snippet = "${mins}m • ${km}km"
                            mk.showInfoWindow()
                        }
                    }
                    updateSelectedInfo(etaMins = mins, distKm = km)
                }
            }
        }
    }

    private fun fetchOsrmRoutes(origin: LatLng, dest: LatLng): List<OsrmRoute> {
        val servers = listOf("https://router.project-osrm.org", "https://routing.openstreetmap.de/routed-car")
        val extras = listOf("&exclude=ferry", "")
        for (base in servers) for (extra in extras) {
            val urlStr = "$base/route/v1/driving/${origin.longitude},${origin.latitude};${dest.longitude},${dest.latitude}" +
                    "?overview=full&geometries=polyline&steps=false&alternatives=true&continue_straight=true$extra"
            val res = runCatching { requestOsrmAll(urlStr) }.getOrNull()
            if (!res.isNullOrEmpty()) return res
        }
        return emptyList()
    }

    private fun requestOsrmAll(urlStr: String): List<OsrmRoute> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            }
            val code = conn.responseCode
            val reader = (if (code in 200..299) conn.inputStream else conn.errorStream)
            val resp = BufferedReader(InputStreamReader(reader)).use { it.readText() }
            Log.d(TAG, "OSRM code=$code body=${resp.take(160)}")
            if (code !in 200..299) return emptyList()

            val root = JSONObject(resp)
            if (root.optString("code") != "Ok") return emptyList()
            val arr = root.optJSONArray("routes") ?: JSONArray()
            if (arr.length() == 0) return emptyList()

            buildList {
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val poly = r.optString("geometry", "")
                    val durSec = (r.optDouble("duration", 0.0)).toLong()
                    val dist = (r.optDouble("distance", 0.0)).toLong()
                    val pts = decodePolylineE5ToLatLng(poly)
                    if (pts.isNotEmpty()) add(OsrmRoute(pts, durSec, dist))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OSRM error: ${e.message}")
            emptyList()
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun highlightRoute(target: DrawnRoute) {
        val map = gMap ?: return
        drawnRoutes.forEach {
            if (it.isPrimary) {
                it.isPrimary = false
                it.polyline.width = 8f
                it.polyline.color = 0x802962FF.toInt()
                it.polyline.pattern = listOf(Dot(), Gap(14f))
                it.polyline.zIndex = 1f
            }
        }
        target.isPrimary = true
        target.polyline.width = 12f
        target.polyline.color = 0xFF2962FF.toInt()
        target.polyline.pattern = null
        target.polyline.zIndex = 2f

        try {
            val b = LatLngBounds.builder().apply { target.route.points.forEach { include(it) } }.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120))
        } catch (_: Exception) {}
    }

    private fun clearAllRoutes() {
        drawnRoutes.forEach { it.polyline.remove() }
        drawnRoutes.clear()
    }

    private fun decodePolylineE5ToLatLng(encoded: String): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val path = ArrayList<LatLng>()
        var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            var result = 0; var shift = 0; var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            result = 0; shift = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            path.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return path
    }

    // ---------- UI helpers ----------
    private fun sourceLabel(src: Source): String = when (src) {
        Source.FIRE  -> "FIRE"
        Source.OTHER -> "OTHER"
        Source.EMS   -> "EMS"
        Source.SMS   -> "SMS"
    }

    private fun updateSelectedInfo(etaMins: Int? = null, distKm: Double? = null) {
        val key = selectedIncidentKey
        if (key == null || !incidents.containsKey(key)) {
            binding.selectedInfo.text = "No active incidents"
            return
        }
        val inc = incidents[key]!!
        val no = numberMap[key] ?: 0
        val base = "${sourceLabel(inc.source)} #$no • ${inc.status}"
        binding.selectedInfo.text = if (etaMins != null && distKm != null) "$base • ${etaMins}m • ${distKm}km" else base
    }

    // ---------- Bitmap helpers ----------
    private fun bitmapFromVector(@DrawableRes id: Int): BitmapDescriptor {
        val d = ContextCompat.getDrawable(requireContext(), id)!!.mutate()
        val bmp = Bitmap.createBitmap(
            d.intrinsicWidth.coerceAtLeast(48),
            d.intrinsicHeight.coerceAtLeast(48),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        d.setBounds(0, 0, c.width, c.height)
        d.draw(c)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun showIncidentDetails(inc: Incident) {
        val base = reportsBase ?: run {
            Toast.makeText(requireContext(), "Missing DB base", Toast.LENGTH_SHORT).show()
            return
        }
        val typeNode = when (inc.source) {
            Source.FIRE  -> "FireReport"
            Source.OTHER -> "OtherEmergencyReport"
            Source.EMS   -> "EmergencyMedicalServicesReport"
            Source.SMS   -> "SmsReport"
        }
        val ref = FirebaseDatabase.getInstance().getReference("$base/$typeNode/${inc.id}")

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val exactLocation = snapshot.child("exactLocation").getValue(String::class.java)
                    ?: snapshot.child("location").getValue(String::class.java) ?: "-"

                val date = snapshot.child("date").getValue(String::class.java) ?: "-"

                val contact = snapshot.child("contact").getValue(String::class.java)
                    ?: snapshot.child("contactNumber").getValue(String::class.java)
                    ?: snapshot.child("phone").getValue(String::class.java) ?: "-"

                val name = snapshot.child("name").getValue(String::class.java)
                    ?: snapshot.child("reporterName").getValue(String::class.java) ?: "-"

                val emergencyType: String? = snapshot.child("emergencyType").getValue(String::class.java)
                    ?: snapshot.child("type").getValue(String::class.java)

                val reportTime = snapshot.child("reportTime").getValue(String::class.java)
                    ?: snapshot.child("time").getValue(String::class.java)
                    ?: run {
                        val tsMs = readTimestampMillis(snapshot)
                        tsMs?.let { formatLocalTime(it) } ?: "-"
                    }

                val number = numberMap[inc.key] ?: 0
                val title = "${sourceLabel(inc.source)} #$number"

                val body = buildString {
                    appendLine("Exact location: $exactLocation")
                    appendLine("Date: $date")
                    emergencyType?.let { appendLine("Emergency type: $it") }
                    appendLine("Contact: $contact")
                    appendLine("Name: $name")
                    append("Report time: $reportTime")
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton("OK", null)
                    .show()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to load details: ${error.message}")
                Toast.makeText(requireContext(), "Failed to load details", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun formatLocalTime(epochMs: Long): String {
        return try {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            sdf.timeZone = TimeZone.getDefault()
            sdf.format(Date(epochMs))
        } catch (_: Exception) { "-" }
    }

    private fun updateLiveLocation(lat: Double, lng: Double) {
        val root = stationRoot ?: return
        val path = "$root/FireFighter/FireFighterAccount/LiveLocation"
        val ref = FirebaseDatabase.getInstance().getReference(path)

        val data = mapOf(
            "latitude" to lat,
            "longitude" to lng,
            "updatedAt" to com.google.firebase.database.ServerValue.TIMESTAMP
        )

        ref.setValue(data)
            .addOnSuccessListener {
                Log.d(TAG, "LiveLocation updated for FireFighterAccount @ $path: ($lat, $lng)")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to update LiveLocation: ${e.message}")
            }
    }

}
