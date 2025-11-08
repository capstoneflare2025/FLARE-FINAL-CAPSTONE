package com.example.flare_capstone.USER.USER_FRAGMENT

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.example.flare_capstone.AboutAppActivity
import com.example.flare_capstone.EmergencyMedicalServicesActivity
import com.example.flare_capstone.FireLevelActivity
import com.example.flare_capstone.MainActivity
import com.example.flare_capstone.MyReportActivity
import com.example.flare_capstone.NOTIFICATION.NotificationAdapter
import com.example.flare_capstone.NOTIFICATION.UiNotification
import com.example.flare_capstone.OtherEmergencyActivity
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.FragmentHomeBinding
import com.example.flare_capstone.databinding.ViewNotificationsPanelBinding
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
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import okhttp3.internal.notify
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

class HomeFragment : Fragment(), OnMapReadyCallback {

    /* ---------------- View binding ---------------- */
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /* ---------------- Firebase / Auth ---------------- */
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private var userRef: DatabaseReference? = null
    private var userListener: ValueEventListener? = null
    private var navView: NavigationView? = null

    /* ---------------- Location / Map ---------------- */
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var map: GoogleMap
    private var mapReady = false
    private var locationUpdatesStarted = false

    // user + station coords
    private var userLatitude = 0.0
    private var userLongitude = 0.0
    private var canocotanLat = 0.0
    private var canocotanLng = 0.0
    private var laFilipinaLat = 0.0
    private var laFilipinaLng = 0.0
    private var mabiniLat = 0.0
    private var mabiniLng = 0.0

    private var canocotanFetched = false
    private var laFilipinaFetched = false
    private var mabiniFetched = false

    /* ---------------- Marker/icon cache ---------------- */
    private var iconNearest: BitmapDescriptor? = null
    private var iconOther: BitmapDescriptor? = null
    private var iconUser: BitmapDescriptor? = null

    private var canocotanName   = "Canocotan Fire Station"
    private var laFilipinaName  = "La Filipina Fire Station"
    private var mabiniName      = "Mabini Fire Station"


    private var userMarker: Marker? = null
    private val stationMarkers = mutableMapOf<String, Marker>() // key: station title
    private var cameraFittedOnce = false

    // Update throttle (markers)
    private var lastUpdateLat = 0.0
    private var lastUpdateLng = 0.0
    private var lastUpdateMs = 0L

    /* ---------------- Selected route (only one) ---------------- */
    private var selectedStationTitle: String? = null
    private var activePolyline: Polyline? = null
    private var activeDistanceMeters: Long? = null
    private var activeDurationSec: Long? = null
    private val COLOR_ACTIVE = Color.BLUE
    private val ROUTE_WIDTH_PX = 10f

    // Prevent stale/double drawings from in-flight OSRM calls
    private val routeRequestSeq = AtomicInteger(0)

    /* ---------------- Constants / Helpers ---------------- */
    private val RES_STATION_NEAREST = R.drawable.ic_station_shortest
    private val RES_STATION_LONGEST = R.drawable.ic_station_longest
    private val RES_USER_LOCATION  = R.drawable.ic_user_location

    private val TINT_NEAREST: Int? = null
    private val TINT_LONGEST_OR_OTHER: Int? = null
    private val TINT_USER: Int? = null

    private val STATION_W_DP: Int? = 25
    private val STATION_H_DP: Int? = 30
    private val USER_W_DP:   Int? = 30
    private val USER_H_DP:   Int? = 35

    // Philippines default view + zoom levels
    private val DEFAULT_CENTER_PH = LatLng(12.8797, 121.7740)
    private val DEFAULT_ZOOM_COUNTRY = 5.8f
    private val DEFAULT_ZOOM_CITY = 14f

    /* ---------------- Station gate (Tagum Central status) ---------------- */
    private var tagumCentralStatus: String? = null  // "Active" | "Inactive" | null (unknown yet)
    private var statusRef: DatabaseReference? = null
    private var statusListener: ValueEventListener? = null
    private var stationStatusLoaded = false

    // inside your Activity/Fragment
    private var notifPopup: PopupWindow? = null


    private fun isStationActive(): Boolean =
        tagumCentralStatus?.equals("Active", ignoreCase = true) == true

    private fun stationGateMessage(): String = when {
        !stationStatusLoaded -> "Fetching station status…"
        tagumCentralStatus.equals("Inactive", ignoreCase = true) ->
            "Reports are temporarily disabled: Tagum Central Fire Station is inactive."
        tagumCentralStatus.equals("Active", ignoreCase = true) -> ""
        else -> "Station status is missing. Please contact admin."
    }



    private val stationProfileKey = mapOf(
        "CapstoneFlare/CanocotanFireStation" to "Profile",
        "CapstoneFlare/LaFilipinaFireStation" to "Profile",
        "CapstoneFlare/MabiniFireStation" to "Profile"
    )

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /* ---------------- Tagum geofence (polygon only) ---------------- */
    // Polygon rings (outer rings only). No map overlay; used only for geofencing.
    private var tagumRings: List<List<LatLng>>? = null

    /* ---------------- Permissions ---------------- */
    private val locationPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            enableMyLocationSafely()
            startLocationUpdates()
            primeLocationOnce()
        } else {
            context?.let { Toast.makeText(it, "Location permission denied", Toast.LENGTH_SHORT).show() }
        }
    }

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance().reference
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        auth = FirebaseAuth.getInstance()

        startTagumCentralStatusRealtime()


        // Ensure a live map fragment is attached
        val mapFrag = (childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment)
            ?: SupportMapFragment.newInstance().also {
                childFragmentManager.beginTransaction()
                    .replace(R.id.map, it, "home_map")
                    .commitNow()
            }
        mapFrag.getMapAsync(this)

        // Load stations
        fetchFireStationLocations()

        // ---------------- Button handlers with Tagum gate ----------------
        binding.fireButton.setOnClickListener {
            if (!canSubmitReportWithToasts()) return@setOnClickListener
            startActivity(Intent(requireActivity(), FireLevelActivity::class.java))
        }

        binding.accidentButton.setOnClickListener {
            if (!canSubmitReportWithToasts()) return@setOnClickListener
            startActivity(Intent(requireActivity(), EmergencyMedicalServicesActivity::class.java))
        }

        binding.otherButton.setOnClickListener {
            if (!canSubmitReportWithToasts()) return@setOnClickListener
            startActivity(Intent(requireActivity(), OtherEmergencyActivity::class.java))
        }

//        binding.ivNotifications.setOnClickListener {
//            showNotificationsPopup(binding.ivNotifications)   // ✅ pass anchor
//        }



        // Toolbar + Drawer
        val topBar = view.findViewById<MaterialToolbar?>(R.id.topAppBar)
            ?: requireActivity().findViewById(R.id.topAppBar)
        val drawer: DrawerLayout? = view.findViewById(R.id.drawer_layout)
            ?: requireActivity().findViewById(R.id.drawer_layout)
        val nav: NavigationView? = view.findViewById(R.id.nav_view)
            ?: requireActivity().findViewById(R.id.nav_view)

        topBar?.setNavigationOnClickListener { drawer?.open() }
        nav?.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_report_fire -> {
                    if (!canSubmitReportWithToasts()) {
                        // gate already toasts
                    } else {
                        startActivity(Intent(requireContext(), FireLevelActivity::class.java))
                    }
                }
                R.id.nav_report_other -> {
                    if (!canSubmitReportWithToasts()) {
                        // gate already toasts
                    } else {
                        startActivity(Intent(requireContext(), OtherEmergencyActivity::class.java))
                    }
                }
                R.id.nav_report_ems -> {
                    if (!canSubmitReportWithToasts()) {
                        // gate already toasts
                    } else {
                        startActivity(
                            Intent(
                                requireContext(),
                                EmergencyMedicalServicesActivity::class.java
                            )
                        )
                    }
                }
                R.id.nav_my_reports -> startActivity(
                    Intent(
                        requireContext(),
                        MyReportActivity::class.java
                    )
                )
                R.id.nav_about -> startActivity(
                    Intent(
                        requireContext(),
                        AboutAppActivity::class.java
                    )
                )

            }
            drawer?.closeDrawers(); true
        }

        nav?.let { populateUserHeader(it) }
        navView = nav
        startUserHeaderListener()
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesStarted = false
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            enableMyLocationSafely()
            startLocationUpdates()
            primeLocationOnce()
        } else {
            requestLocationPerms()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        userListener?.let { userRef?.removeEventListener(it) }
        userListener = null
        userRef = null
        navView = null

        // Clean map items
        activePolyline?.remove(); activePolyline = null
        activeDistanceMeters = null
        activeDurationSec = null
        selectedStationTitle = null

        stationMarkers.values.forEach { it.remove() }
        stationMarkers.clear()
        userMarker = null

        // Reset state so returning works consistently
        mapReady = false
        cameraFittedOnce = false
        lastUpdateMs = 0L
        lastUpdateLat = 0.0
        lastUpdateLng = 0.0
        userLatitude = 0.0
        userLongitude = 0.0

        // cancel any in-flight OSRM response
        routeRequestSeq.incrementAndGet()

        _binding = null

        statusListener?.let { statusRef?.removeEventListener(it) }
        statusListener = null
        statusRef = null

    }

    private fun openSettings() {
        findNavController().navigate(R.id.settingsFragment)
    }



    private fun logout(){
        val inflater = requireActivity().layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_logout, null)
        dialogView.findViewById<ImageView>(R.id.logoImageView).setImageResource(R.drawable.ic_logo)

        AlertDialog.Builder(requireActivity())
            .setView(dialogView)
            .setPositiveButton("Yes") { _, _ ->
                // Clear cached notification flags + unread count so next login is fresh
                val shownPrefs = requireActivity().getSharedPreferences("shown_notifications", Context.MODE_PRIVATE)
                shownPrefs.edit().clear().apply() // removes all "shown" keys and unread_message_count
                // ensure unread_message_count is 0 explicitly
                shownPrefs.edit().putInt("unread_message_count", 0).apply()

                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(requireActivity(), MainActivity::class.java))
                Toast.makeText(requireActivity(), "You have been logged out.", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
            .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    /* =========================================================
     * Map callbacks and location
     * ========================================================= */
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        mapReady = true

        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = true

        // Default to Philippines until we have a fix
        map.setOnMapLoadedCallback {
            if (userLatitude == 0.0 && userLongitude == 0.0 && !cameraFittedOnce) {
                zoomToPhilippines(move = true)
            }
        }

        // Load Tagum polygon from res/raw (for geofence only; not drawn)
        loadTagumBoundaryFromRaw()

        // Tap a station to draw/hide its route
        map.setOnMarkerClickListener { marker ->
            val title = marker.title ?: return@setOnMarkerClickListener false
            if (title == "Your Location") return@setOnMarkerClickListener false

            if (selectedStationTitle == title && activePolyline != null) {
                clearActiveRoute(); selectedStationTitle = null
                routeRequestSeq.incrementAndGet()
                postToast("Route hidden")
                return@setOnMarkerClickListener true
            }

            if (userLatitude == 0.0 || userLongitude == 0.0) {
                postToast("Waiting for your location…"); return@setOnMarkerClickListener true
            }

            val dest = getStationLatLngByTitle(title) ?: run {
                postToast("Station location not available."); return@setOnMarkerClickListener true
            }

            selectedStationTitle = title
            drawSingleRouteOSRM(
                origin = LatLng(userLatitude, userLongitude),
                dest = dest,
                requestedTitle = title
            ) {
                val meters = activeDistanceMeters ?: return@drawSingleRouteOSRM
                val secs = activeDurationSec ?: return@drawSingleRouteOSRM
                val km = meters / 1000.0
                val mins = secs / 60.0
                postToast("$title • ${"%.1f".format(km)} km • ${"%.0f".format(mins)} min")
            }

            marker.showInfoWindow()
            true
        }

        map.setOnMapClickListener {
            if (activePolyline != null) {
                clearActiveRoute(); selectedStationTitle = null
                routeRequestSeq.incrementAndGet()
                postToast("Route cleared")
            }
        }

        if (hasLocationPermission()) {
            enableMyLocationSafely()
            startLocationUpdates()
            primeLocationOnce()
        } else {
            requestLocationPerms()
        }
    }

    private fun getStationLatLngByTitle(title: String): LatLng? = when (title) {
        canocotanName   -> LatLng(canocotanLat, canocotanLng)
        laFilipinaName  -> LatLng(laFilipinaLat, laFilipinaLng)
        mabiniName      -> LatLng(mabiniLat, mabiniLng)
        else -> null
    }


    private fun hasLocationPermission(): Boolean {
        val ctx = context ?: return false
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPerms() {
        locationPermsLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationSafely() {
        if (!::map.isInitialized || !hasLocationPermission()) return
        try { map.isMyLocationEnabled = true } catch (_: SecurityException) {}
    }

    private fun startLocationUpdates() {
        if (!isAdded || locationUpdatesStarted || !hasLocationPermission()) return
        locationUpdatesStarted = true

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10_000L
        ).setMinUpdateIntervalMillis(5_000L).build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            locationUpdatesStarted = false
            context?.let { Toast.makeText(it, "Location permission missing", Toast.LENGTH_SHORT).show() }
        }
    }

    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val loc = locationResult.lastLocation ?: return
            userLatitude = loc.latitude
            userLongitude = loc.longitude
            if (mapReady) updateMapIfReady()
        }
    }

    /* =========================================================
     * Firebase (Stations)
     * ========================================================= */
    private fun fetchFireStationLatLng(path: String, onDone: (Double, Double) -> Unit) {
        val profileKey = stationProfileKey[path] ?: ""
        database.child(path).child(profileKey).get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    onDone(snap.getDouble("latitude"), snap.getDouble("longitude"))
                } else {
                    database.child(path).get()
                        .addOnSuccessListener { oldSnap ->
                            if (oldSnap.exists()) {
                                onDone(oldSnap.getDouble("latitude"), oldSnap.getDouble("longitude"))
                            } else {
                                Toast.makeText(context, "$path not found in database", Toast.LENGTH_SHORT).show()
                                onDone(0.0, 0.0)
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Failed to read $path", Toast.LENGTH_SHORT).show()
                            onDone(0.0, 0.0)
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to read $path", Toast.LENGTH_SHORT).show()
                onDone(0.0, 0.0)
            }
    }

    private fun fetchFireStationLocations() {
        fetchFireStationLatLngAndName("CapstoneFlare/CanocotanFireStation") { name, lat, lng ->
            canocotanName = name; canocotanLat = lat; canocotanLng = lng; canocotanFetched = true; updateMapIfReady()
        }
        fetchFireStationLatLngAndName("CapstoneFlare/LaFilipinaFireStation") { name, lat, lng ->
            laFilipinaName = name; laFilipinaLat = lat; laFilipinaLng = lng; laFilipinaFetched = true; updateMapIfReady()
        }
        fetchFireStationLatLngAndName("CapstoneFlare/MabiniFireStation") { name, lat, lng ->
            mabiniName = name; mabiniLat = lat; mabiniLng = lng; mabiniFetched = true; updateMapIfReady()
        }
    }


    /* =========================================================
     * Drawer header (User profile)
     * ========================================================= */
    private fun populateUserHeader(nav: NavigationView) {
        val headerView = nav.getHeaderView(0)
        val nameView = headerView.findViewById<TextView>(R.id.headerName)
        val emailView = headerView.findViewById<TextView>(R.id.headerEmail)
        val avatarView = headerView.findViewById<ImageView>(R.id.headerAvatar)

        val user = auth.currentUser
        if (user == null) {
            nameView.text = "Guest"
            emailView.text = ""
            Glide.with(this).load(R.drawable.ic_profile).transform(CircleCrop()).into(avatarView)
            return
        }

        nameView.text = user.displayName ?: "User"
        emailView.text = user.email ?: ""
        Glide.with(this).load(R.drawable.ic_profile).transform(CircleCrop()).into(avatarView)

        val uid = user.uid
        database.child("Users").child(uid).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) return@addOnSuccessListener
                val fullName = snap.child("name").getValue(String::class.java)
                val email = snap.child("email").getValue(String::class.java)
                val profileB64 = snap.child("profile").getValue(String::class.java)

                if (!fullName.isNullOrBlank()) nameView.text = fullName
                if (!email.isNullOrBlank()) emailView.text = email
                loadAvatarFromBase64Into(profileB64, avatarView)
            }
    }

    private fun startUserHeaderListener() {
        val nav = navView ?: return
        val header = nav.getHeaderView(0)
        val nameView = header.findViewById<TextView>(R.id.headerName)
        val emailView = header.findViewById<TextView>(R.id.headerEmail)
        val avatarView = header.findViewById<ImageView>(R.id.headerAvatar)

        val user = auth.currentUser
        if (user == null) {
            nameView.text = "Guest"
            emailView.text = ""
            avatarView.setImageResource(R.drawable.ic_profile)
            return
        }

        userRef = FirebaseDatabase.getInstance().reference.child("Users").child(user.uid)
        userListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val name   = snapshot.child("name").getValue(String::class.java)
                val email  = snapshot.child("email").getValue(String::class.java)
                val avatar = snapshot.child("profile").getValue(String::class.java)

                if (!name.isNullOrBlank())  nameView.text = name
                if (!email.isNullOrBlank()) emailView.text = email
                loadAvatarFromBase64Into(avatar, avatarView)
            }
            override fun onCancelled(error: DatabaseError) { /* no-op */ }
        }
        userRef?.addValueEventListener(userListener as ValueEventListener)
    }

    /** Base64 -> ImageView (handles data-URL prefixes) */
    private fun loadAvatarFromBase64Into(avatarB64: String?, target: ImageView) {
        if (avatarB64.isNullOrBlank()) {
            target.setImageResource(R.drawable.ic_profile); return
        }
        val pureBase64 = avatarB64.substringAfter("base64,", avatarB64)
        try {
            val bytes = Base64.decode(pureBase64, Base64.DEFAULT)
            Glide.with(this)
                .load(bytes)
                .placeholder(R.drawable.ic_profile)
                .error(R.drawable.ic_profile)
                .transform(CircleCrop())
                .into(target)
        } catch (_: Exception) {
            target.setImageResource(R.drawable.ic_profile)
        }
    }

    /* =========================================================
     * Map markers & camera (optimized)
     * ========================================================= */
    private fun ensureIcons() {
        if (iconNearest == null) {
            iconNearest = bitmapFromDrawable(RES_STATION_NEAREST, TINT_NEAREST, STATION_W_DP, STATION_H_DP)
        }
        if (iconOther == null) {
            iconOther = bitmapFromDrawable(RES_STATION_LONGEST, TINT_LONGEST_OR_OTHER, STATION_W_DP, STATION_H_DP)
        }
        if (iconUser == null) {
            iconUser = bitmapFromDrawable(RES_USER_LOCATION, TINT_USER, USER_W_DP, USER_H_DP)
        }
    }

    private fun shouldUpdate(lat: Double, lng: Double): Boolean {
        val now = System.currentTimeMillis()
        val dt = now - lastUpdateMs
        val dMeters = distanceKm(lastUpdateLat, lastUpdateLng, lat, lng) * 1000.0
        return dt >= 3000L || dMeters >= 15.0
    }

    private fun updateMapIfReady() {
        if (!mapReady) return

        if (userLatitude != 0.0 || userLongitude != 0.0) {
            if (!cameraFittedOnce) {
                centerOnUser(animated = true, zoom = DEFAULT_ZOOM_CITY)
                cameraFittedOnce = true
            }
        }

        if (!(canocotanFetched && laFilipinaFetched && mabiniFetched)) return
        if (userLatitude == 0.0 || userLongitude == 0.0) return
        if (!shouldUpdate(userLatitude, userLongitude)) return

        lastUpdateLat = userLatitude
        lastUpdateLng = userLongitude
        lastUpdateMs = System.currentTimeMillis()

        ensureIcons()

        val user = LatLng(userLatitude, userLongitude)
        val stations = listOf(
            Triple(canocotanName,  canocotanLat,  canocotanLng),
            Triple(laFilipinaName, laFilipinaLat, laFilipinaLng),
            Triple(mabiniName,     mabiniLat,     mabiniLng)
        )


        if (userMarker == null) {
            userMarker = map.addMarker(
                MarkerOptions().position(user).title("Your Location").icon(iconUser).anchor(0.5f, 1f)
            )
        } else {
            userMarker!!.position = user
        }

        val dists = stations.mapIndexed { idx, t -> idx to distanceKm(userLatitude, userLongitude, t.second, t.third) }
        val nearestIdx = dists.minByOrNull { it.second }?.first ?: 0

        stations.forEachIndexed { idx, (title, lat, lng) ->
            val pos = LatLng(lat, lng)
            val existing = stationMarkers[title]
            val desiredIcon = if (idx == nearestIdx) iconNearest else iconOther
            if (existing == null) {
                stationMarkers[title] = map.addMarker(
                    MarkerOptions().position(pos).title(title).icon(desiredIcon).anchor(0.5f, 1f)
                )!!
            } else {
                existing.position = pos
                existing.setIcon(desiredIcon)
            }
        }

        selectedStationTitle?.let { title ->
            val dest = getStationLatLngByTitle(title)
            if (dest != null) {
                drawSingleRouteOSRM(
                    origin = LatLng(userLatitude, userLongitude),
                    dest = dest,
                    requestedTitle = title,
                    onDone = null
                )
            }
        }
    }

    // Great-circle distance (Haversine)
    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val lat1Rad = Math.toRadians(lat1)
        val lon1Rad = Math.toRadians(lon1)
        val lat2Rad = Math.toRadians(lat2)
        val lon2Rad = Math.toRadians(lon2)
        val dlat = lat2Rad - lat1Rad
        val dlon = lon2Rad - lon1Rad
        val a = Math.sin(dlat / 2).pow(2.0) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.sin(dlon / 2).pow(2.0)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    /* =========================================================
     * OSRM: fetch + draw ONE shortest route
     * ========================================================= */
    private fun drawSingleRouteOSRM(
        origin: LatLng,
        dest: LatLng,
        requestedTitle: String,
        onDone: (() -> Unit)? = null
    ) {
        val mySeq = routeRequestSeq.incrementAndGet()
        clearActiveRoute()

        Thread {
            var points: List<LatLng> = emptyList()
            var meters = Long.MAX_VALUE
            var seconds = Long.MAX_VALUE

            try {
                val urlStr =
                    "https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${dest.longitude},${dest.latitude}" +
                            "?overview=full&geometries=polyline&steps=false&alternatives=false"

                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                conn.disconnect()

                if (code in 200..299) {
                    val json = JSONObject(text)
                    val status = json.optString("code", "Error")
                    if (status == "Ok") {
                        val routes = json.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val r0 = routes.getJSONObject(0)
                            meters = r0.optDouble("distance", Double.MAX_VALUE).toLong()
                            seconds = r0.optDouble("duration", Double.MAX_VALUE).toLong()
                            val geom = r0.optString("geometry", "")
                            if (geom.isNotEmpty()) points = decodePolyline(geom)
                        }
                    } else {
                        val msg = json.optString("message")
                        postToast("OSRM: $status${if (msg.isNotBlank()) " – $msg" else ""}")
                    }
                } else postToast("OSRM error ($code)")
            } catch (e: Exception) {
                postToast("OSRM failed: ${e.message}")
            }

            requireActivity().runOnUiThread {
                if (routeRequestSeq.get() != mySeq) return@runOnUiThread
                if (selectedStationTitle != requestedTitle) return@runOnUiThread

                if (points.isNotEmpty()) {
                    activePolyline = map.addPolyline(
                        PolylineOptions().addAll(points).width(ROUTE_WIDTH_PX).color(COLOR_ACTIVE).geodesic(true)
                    )
                    activeDistanceMeters = meters
                    activeDurationSec = seconds
                } else {
                    activePolyline = map.addPolyline(
                        PolylineOptions()
                            .add(origin, dest)
                            .width(ROUTE_WIDTH_PX)
                            .color(COLOR_ACTIVE)
                            .geodesic(true)
                            .pattern(listOf(Dot(), Gap(10f)))
                    )
                    val m = (distanceKm(origin.latitude, origin.longitude, dest.latitude, dest.longitude) * 1000).toLong()
                    activeDistanceMeters = m
                    activeDurationSec = ((m / 1000.0) / 35.0 * 3600).toLong()
                }
                onDone?.invoke()
            }
        }.start()
    }

    private fun clearActiveRoute() {
        activePolyline?.remove()
        activePolyline = null
        activeDistanceMeters = null
        activeDurationSec = null
    }

    /** Polyline decoder (polyline5) */
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            val latD = lat / 1E5
            val lngD = lng / 1E5
            poly.add(LatLng(latD, lngD))
        }
        return poly
    }

    private fun postToast(msg: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    /* =========================================================
     * Drawable -> BitmapDescriptor helper
     * ========================================================= */
    private fun bitmapFromDrawable(
        @DrawableRes resId: Int,
        tint: Int?,
        widthDp: Int?,
        heightDp: Int?
    ): BitmapDescriptor {
        val ctx = requireContext()
        val drawable: Drawable = (AppCompatResources.getDrawable(ctx, resId)
            ?: error("Drawable not found: $resId")).mutate()

        val wrapped = if (tint != null) DrawableCompat.wrap(drawable) else drawable
        if (tint != null) DrawableCompat.setTint(wrapped, tint)

        val wPx = widthDp?.dp() ?: drawable.intrinsicWidth.takeIf { it > 0 } ?: 64
        val hPx = heightDp?.dp() ?: drawable.intrinsicHeight.takeIf { it > 0 } ?: 64
        wrapped.setBounds(0, 0, wPx, hPx)

        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        wrapped.draw(canvas)

        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /* =========================================================
     * Camera helpers
     * ========================================================= */
    private fun zoomToPhilippines(move: Boolean = true) {
        if (!mapReady) return
        val cu = CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER_PH, DEFAULT_ZOOM_COUNTRY)
        if (move) map.moveCamera(cu) else map.animateCamera(cu)
    }

    private fun centerOnUser(animated: Boolean = true, zoom: Float = DEFAULT_ZOOM_CITY) {
        if (!mapReady) return
        if (userLatitude == 0.0 && userLongitude == 0.0) return
        val cu = CameraUpdateFactory.newLatLngZoom(LatLng(userLatitude, userLongitude), zoom)
        if (animated) map.animateCamera(cu) else map.moveCamera(cu)
    }

    /* =========================================================
     * Location seeding
     * ========================================================= */
    @SuppressLint("MissingPermission")
    private fun primeLocationOnce() {
        if (!hasLocationPermission()) return
        fusedLocationClient.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    userLatitude = loc.latitude
                    userLongitude = loc.longitude
                    updateMapIfReady()
                    if (!cameraFittedOnce) {
                        centerOnUser(animated = true)
                        cameraFittedOnce = true
                    }
                } else {
                    val cts = CancellationTokenSource()
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { fresh ->
                            if (fresh != null) {
                                userLatitude = fresh.latitude
                                userLongitude = fresh.longitude
                                updateMapIfReady()
                                if (!cameraFittedOnce) {
                                    centerOnUser(animated = true)
                                    cameraFittedOnce = true
                                }
                            } else {
                                startLocationUpdates()
                            }
                        }
                }
            }
    }

    /* =========================================================
     * Tagum polygon loader + geofence (no drawing)
     * ========================================================= */
    private fun loadTagumBoundaryFromRaw() {
        val ctx = context ?: return
        try {
            val ins = ctx.resources.openRawResource(R.raw.tagum_boundary)
            val text = ins.bufferedReader().use { it.readText() }
            val root = JSONObject(text)

            fun arrToRing(arr: JSONArray): List<LatLng> {
                val out = ArrayList<LatLng>(arr.length())
                for (i in 0 until arr.length()) {
                    val pt = arr.getJSONArray(i)
                    val lon = pt.getDouble(0)
                    val lat = pt.getDouble(1)
                    out.add(LatLng(lat, lon))
                }
                out.trimToSize()
                return out
            }

            val rings = mutableListOf<List<LatLng>>()
            when (root.optString("type")) {
                "Polygon" -> {
                    val coords = root.getJSONArray("coordinates")
                    if (coords.length() > 0) rings.add(arrToRing(coords.getJSONArray(0))) // outer ring only
                }
                "MultiPolygon" -> {
                    val mcoords = root.getJSONArray("coordinates")
                    for (i in 0 until mcoords.length()) {
                        val poly = mcoords.getJSONArray(i)
                        if (poly.length() > 0) rings.add(arrToRing(poly.getJSONArray(0)))
                    }
                }
                "Feature" -> {
                    val geom = root.getJSONObject("geometry")
                    val t2 = geom.getString("type")
                    if (t2 == "Polygon") {
                        val coords = geom.getJSONArray("coordinates")
                        if (coords.length() > 0) rings.add(arrToRing(coords.getJSONArray(0)))
                    } else if (t2 == "MultiPolygon") {
                        val mcoords = geom.getJSONArray("coordinates")
                        for (i in 0 until mcoords.length()) {
                            val poly = mcoords.getJSONArray(i)
                            if (poly.length() > 0) rings.add(arrToRing(poly.getJSONArray(0)))
                        }
                    }
                }
                "FeatureCollection" -> {
                    val feats = root.getJSONArray("features")
                    for (i in 0 until feats.length()) {
                        val geom = feats.getJSONObject(i).getJSONObject("geometry")
                        val t2 = geom.getString("type")
                        if (t2 == "Polygon") {
                            val coords = geom.getJSONArray("coordinates")
                            if (coords.length() > 0) rings.add(arrToRing(coords.getJSONArray(0)))
                        } else if (t2 == "MultiPolygon") {
                            val mcoords = geom.getJSONArray("coordinates")
                            for (j in 0 until mcoords.length()) {
                                val poly = mcoords.getJSONArray(j)
                                if (poly.length() > 0) rings.add(arrToRing(poly.getJSONArray(0)))
                            }
                        }
                    }
                }
            }

            if (rings.isNotEmpty()) {
                tagumRings = rings
            }
        } catch (_: Exception) {
            tagumRings = null // no fallback; outside until loaded
        }
    }

    private fun pointInRing(pt: LatLng, ring: List<LatLng>): Boolean {
        // Ray casting
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i].longitude
            val yi = ring[i].latitude
            val xj = ring[j].longitude
            val yj = ring[j].latitude
            val intersects = ((yi > pt.latitude) != (yj > pt.latitude)) &&
                    (pt.longitude < (xj - xi) * (pt.latitude - yi) / (yj - yi + 0.0) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /** Polygon-only geofence. Returns false until polygon is loaded. */
    private fun isInsideTagum(): Boolean {
        if (userLatitude == 0.0 && userLongitude == 0.0) return false
        val rings = tagumRings ?: return false
        val pt = LatLng(userLatitude, userLongitude)
        return rings.any { ring -> pointInRing(pt, ring) }
    }


    /* =========================================================
 * DataSnapshot utils
 * ========================================================= */
    private fun DataSnapshot.getDouble(key: String): Double {
        val v = child(key).value ?: return 0.0
        return when (v) {
            is Double -> v
            is Float  -> v.toDouble()
            is Long   -> v.toDouble()
            is Int    -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else      -> 0.0
        }
    }


    private fun startTagumCentralStatusRealtime() {
        val ref = database.child("TagumCityCentralFireStation").child("Profile").child("status")
        statusRef = ref

        // 1) Prime once (fastest way to avoid the "Checking…" toast)
        ref.get().addOnSuccessListener { snap ->
            stationStatusLoaded = true
            tagumCentralStatus = snap.getValue(String::class.java)?.trim()
        }.addOnFailureListener {
            stationStatusLoaded = true   // loaded but failed -> treat as missing
            tagumCentralStatus = null
        }

        // 2) Keep it live
        statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val old = tagumCentralStatus
                stationStatusLoaded = true
                tagumCentralStatus = snapshot.getValue(String::class.java)?.trim()

                if (old != null && old != tagumCentralStatus) {
                    when (tagumCentralStatus?.lowercase()) {
                        "active"   -> postToast("Tagum Central: now Active. Reports enabled.")
                        "inactive" -> postToast("Tagum Central: now Inactive. Reports disabled.")
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                stationStatusLoaded = true
            }
        }
        ref.addValueEventListener(statusListener as ValueEventListener)
    }



    /** Returns true when all gates pass; shows a toast and returns false otherwise. */
    private fun canSubmitReportWithToasts(): Boolean {
        if (userLatitude == 0.0 && userLongitude == 0.0) {
            postToast("Getting your location…"); return false
        }
        if (!isInsideTagum()) {
            postToast("You can’t submit a report outside Tagum."); return false
        }
        if (!stationStatusLoaded) {
            postToast(stationGateMessage()); return false
        }
        if (!isStationActive()) {
            postToast(stationGateMessage()); return false
        }
        return true
    }


    private fun fetchFireStationLatLngAndName(path: String, onDone: (String, Double, Double) -> Unit) {
        val profileKey = stationProfileKey[path] ?: "Profile"
        database.child(path).child(profileKey).get().addOnSuccessListener { snap ->
            val lat = snap.getDouble("latitude")
            val lng = snap.getDouble("longitude")
            val name = snap.child("name").getValue(String::class.java)
                ?: path.substringAfterLast('/') // fallback to last path segment
            onDone(name, lat, lng)
        }.addOnFailureListener {
            // Fallback to legacy non-Profile shape
            database.child(path).get().addOnSuccessListener { old ->
                val lat = old.getDouble("latitude")
                val lng = old.getDouble("longitude")
                val name = old.child("name").getValue(String::class.java)
                    ?: path.substringAfterLast('/')
                onDone(name, lat, lng)
            }.addOnFailureListener {
                onDone(path.substringAfterLast('/'), 0.0, 0.0)
            }
        }
    }


    private fun showNotificationsPopup(anchor: View) {
        val panel = ViewNotificationsPanelBinding.inflate(layoutInflater)

        val adapter = NotificationAdapter(
            db = FirebaseDatabase.getInstance(),
            items = mutableListOf(),
            onClick = { item ->
                // open detail or navigate using item.station / item.typeKey / item.key
            },
            markReadOnClick = true
        )

        panel.rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        panel.rvNotifications.adapter = adapter

        var allItems: List<UiNotification> = emptyList()

        // --- Load from Firebase (same logic you already have) ---
        val db = FirebaseDatabase.getInstance()
        val authEmail = FirebaseAuth.getInstance().currentUser?.email

        fun applyFilter(selection: String) {
            val filtered = when (selection) {
                "Fire Report" -> allItems.filter { it.title == "Fire Report" }
                "Other Emergency Report" -> allItems.filter { it.title == "Other Emergency Report" }
                "Emergency Medical Services Report" -> allItems.filter { it.title == "Emergency Medical Services Report" }
                else -> allItems // "All Reports"
            }
            adapter.submit(filtered)
        }

         fun loadReportsFor(userName: String, userContact: String) {
            val stations = listOf("MabiniFireStation", "CanocotanFireStation", "LaFilipinaFireStation")
            val types = listOf(
                "FireReport" to R.drawable.ic_fire,
                "OtherEmergencyReport" to R.drawable.ic_warning_24,
                "EmergencyMedicalServicesReport" to R.drawable.ic_car_crash_24
            )

            val base = db.getReference("CapstoneFlare")
            val collected = mutableListOf<UiNotification>()
            var pending = stations.size * types.size
            if (pending == 0) {
                allItems = emptyList()
                adapter.submit(allItems)
                return
            }

            stations.forEach { station ->
                types.forEach { (typeKey, iconRes) ->
                    base.child(station).child("AllReport").child(typeKey)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(s: DataSnapshot) {
                                if (s.exists()) {
                                    for (report in s.children) {
                                        val name = report.child("name").getValue(String::class.java) ?: ""
                                        val contact = report.child("contact").getValue(String::class.java) ?: ""
                                        if (!name.equals(userName, ignoreCase = true)) continue
                                        if (contact != userContact) continue

                                        val date = report.child("date").getValue(String::class.java) ?: ""
                                        val time = report.child("reportTime").getValue(String::class.java) ?: ""
                                        val exactLocation = report.child("exactLocation").getValue(String::class.java)
                                            ?: report.child("fireStationName").getValue(String::class.java)
                                            ?: station

                                        // Default fields for type and mapLink/location
                                        var mapLink: String? = null
                                        var type: String? = null
                                        var title: String? = null

                                        when (typeKey) {
                                            "FireReport" -> {
                                                mapLink = report.child("mapLink").getValue(String::class.java) ?: ""
                                                type = report.child("type").getValue(String::class.java) ?: "Fire Report" // Default to Fire Report
                                                title = "Fire Report"
                                            }
                                            "OtherEmergencyReport" -> {
                                                mapLink = report.child("location").getValue(String::class.java) ?: ""
                                                type = report.child("emergencyType").getValue(String::class.java) ?: "Other Emergency Report"
                                                title = "Other Emergency Report"
                                            }
                                            "EmergencyMedicalServicesReport" -> {
                                                mapLink = report.child("location").getValue(String::class.java) ?: ""
                                                type = report.child("type").getValue(String::class.java) ?: "Emergency Medical Services Report"
                                                title = "Emergency Medical Report"
                                            }
                                        }

                                        val read = report.child("read").getValue(Boolean::class.java) ?: false
                                        val key = report.key ?: ""

                                        collected += UiNotification(
                                            title = title ?: "Unknown Report",
                                            type = type ?: "Unknown Type",
                                            whenText = listOf(date, time).filter { it.isNotBlank() }.joinToString(" "),
                                            locationText = exactLocation,
                                            mapLink = mapLink,
                                            unread = !read,
                                            iconRes = iconRes,
                                            station = station,
                                            key = key,
                                            typeKey = typeKey
                                        )
                                    }
                                }
                                if (--pending == 0) {
                                    allItems = collected
                                    applyFilter("All Reports")
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                if (--pending == 0) {
                                    allItems = collected
                                    applyFilter("All Reports")
                                }
                            }
                        })
                }
            }
        }


        fun loadUserAndReports() {
            if (authEmail.isNullOrBlank()) { adapter.submit(emptyList()); return }
            val usersRef = db.getReference("Users")

            usersRef.orderByChild("email").equalTo(authEmail)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        var name: String? = null
                        var contact: String? = null
                        if (snap.exists()) {
                            for (c in snap.children) {
                                name = c.child("name").getValue(String::class.java)
                                contact = c.child("contact").getValue(String::class.java)
                                break
                            }
                        }
                        if (name.isNullOrBlank() || contact.isNullOrBlank()) {
                            usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(all: DataSnapshot) {
                                    for (c in all.children) {
                                        val e = c.child("email").getValue(String::class.java) ?: continue
                                        if (e.equals(authEmail, ignoreCase = true)) {
                                            name = c.child("name").getValue(String::class.java)
                                            contact = c.child("contact").getValue(String::class.java)
                                            break
                                        }
                                    }
                                    if (name.isNullOrBlank() || contact.isNullOrBlank()) {
                                        adapter.submit(emptyList()); return
                                    }
                                    loadReportsFor(name!!, contact!!)
                                }
                                override fun onCancelled(error: DatabaseError) {
                                    adapter.submit(emptyList())
                                }
                            })
                        } else {
                            loadReportsFor(name!!, contact!!)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        adapter.submit(emptyList())
                    }
                })
        }

        loadUserAndReports()

        // --- Setup dropdown options and filter behavior ---
        val options = listOf("All Reports", "Fire Report", "Other Emergency Report", "Emergency Medical Services Report")
        val ddAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options)
        panel.actvReportFilter.setAdapter(ddAdapter)
        panel.actvReportFilter.setText("All Reports", false) // default

        panel.actvReportFilter.setOnItemClickListener { _, _, pos, _ ->
            applyFilter(options[pos])
        }

        // Popup window anchored to the bell
        val widthPx = (320 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(panel.root, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 16f
        }

        anchor.post { popup.showAsDropDown(anchor, 0, 0, Gravity.END) }
        notifPopup = popup
    }






}