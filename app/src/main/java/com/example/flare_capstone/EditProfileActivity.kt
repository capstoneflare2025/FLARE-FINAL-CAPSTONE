package com.example.flare_capstone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flare_capstone.databinding.ActivityEditProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import java.io.ByteArrayOutputStream

class EditProfileActivity : AppCompatActivity() {

    /* ---------------- View / Firebase ---------------- */
    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth
    private var isEditing = false

    /* ---------------- Request Codes ---------------- */
    companion object {
        private const val CAMERA_REQUEST_CODE = 101
        private const val CAMERA_PERMISSION_REQUEST_CODE = 102
        private const val GALLERY_REQUEST_CODE = 104
        private const val GALLERY_PERMISSION_REQUEST_CODE = 103
    }

    /* ---------------- Profile Image State ---------------- */
    private var base64ProfileImage: String? = null
    private var hasProfileImage: Boolean = false
    private var removeProfileImageRequested: Boolean = false

    // To store the initial values for comparison
    private var originalName: String = ""
    private var originalContact: String = ""

    /* ---------------- Connectivity ---------------- */
    private lateinit var connectivityManager: ConnectivityManager
    private var loadingDialog: AlertDialog? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { hideLoadingDialog() } }
        override fun onLost(network: Network) { runOnUiThread { showLoadingDialog("No internet connection") } }
    }

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("Users")
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Initial connectivity state
        if (!isConnected()) showLoadingDialog("No internet connection") else hideLoadingDialog()

        // Navigation
        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Profile image click
        binding.profileIcon.isClickable = true
        binding.profileIcon.setOnClickListener { showImageSourceSheet() }

        binding.changePhotoIcon.setOnClickListener { showImageSourceSheet() }

        // Network callback
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Load user data
        val userId = auth.currentUser?.uid
        if (userId != null) {
            database.child(userId).get().addOnSuccessListener { snapshot ->
                hideLoadingDialog()
                if (!snapshot.exists()) return@addOnSuccessListener

                val user = snapshot.getValue(User::class.java)
                if (user != null) {
                    binding.name.setText(user.name ?: "")
                    binding.email.setText(user.email ?: "")
                    binding.contact.setText(user.contact ?: "")

                    val profileBase64 = snapshot.child("profile").getValue(String::class.java)
                    val bmp = convertBase64ToBitmap(profileBase64)
                    if (bmp != null) {
                        binding.profileIcon.setImageBitmap(bmp)
                        hasProfileImage = true
                        base64ProfileImage = profileBase64
                    } else {
                        hasProfileImage = false
                        base64ProfileImage = null
                    }

                    val originalName = user.name ?: ""
                    val originalContact = user.contact ?: ""

                    // Initially make the fields non-editable
                    binding.name.isFocusable = false
                    binding.contact.isFocusable = false
                    binding.name.isFocusableInTouchMode = false
                    binding.contact.isFocusableInTouchMode = false

                    binding.currentPassword.visibility = View.GONE
                    binding.currentPasswordText.visibility = View.GONE

                    // Handle the "Edit" button click
                    binding.editButton.setOnClickListener {
                        isEditing = !isEditing
                        toggleEditMode()
                    }

                    // Add TextWatcher for real-time change detection
                    binding.name.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(charSequence: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(charSequence: CharSequence?, start: Int, before: Int, count: Int) {
                            checkForChanges(originalName, originalContact)
                        }

                        override fun afterTextChanged(editable: Editable?) {}
                    })

                    binding.contact.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(charSequence: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(charSequence: CharSequence?, start: Int, before: Int, count: Int) {
                            checkForChanges(originalName, originalContact)
                        }

                        override fun afterTextChanged(editable: Editable?) {}
                    })
                }
            }.addOnFailureListener { hideLoadingDialog() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    /* =========================================================
     * Connectivity
     * ========================================================= */
    private fun isConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showLoadingDialog(message: String = "Please wait if internet is slow") {
        if (loadingDialog == null) {
            val builder = AlertDialog.Builder(this)
            val dialogView = layoutInflater.inflate(R.layout.custom_loading_dialog, null)
            builder.setView(dialogView)
            builder.setCancelable(false)
            loadingDialog = builder.create()
        }
        loadingDialog?.show()
        loadingDialog?.findViewById<TextView>(R.id.loading_message)?.text = message
    }

    private fun hideLoadingDialog() { loadingDialog?.dismiss() }

    /* ---------------- Edit Mode Toggle ---------------- */
    private fun toggleEditMode() {
        if (isEditing) {
            // Change button text to "Cancel"
            binding.editButton.text = "Cancel"
            // Make fields editable
            binding.name.isFocusable = true
            binding.contact.isFocusable = true
            binding.name.isFocusableInTouchMode = true
            binding.contact.isFocusableInTouchMode = true

            // Show photo change options
            binding.profileIcon.isClickable = true
            binding.changePhotoIcon.visibility = View.VISIBLE
        } else {
            // Change button text back to "Edit"
            binding.editButton.text = "Edit"
            // Make fields non-editable
            binding.name.isFocusable = false
            binding.contact.isFocusable = false
            binding.name.isFocusableInTouchMode = false
            binding.contact.isFocusableInTouchMode = false

            // Hide photo change options
            binding.profileIcon.isClickable = false
            binding.changePhotoIcon.visibility = View.GONE

            // Save changes if any (if Edit is switched to Save)
            saveProfileChanges()
        }
    }

    /* ---------------- Detect Changes ---------------- */
    private fun checkForChanges(originalName: String, originalContact: String) {
        val newName = binding.name.text.toString().trim()
        val newContact = binding.contact.text.toString().trim()

        // Detect change if name, contact, or profile picture has changed
        if (newName != originalName || newContact != originalContact || removeProfileImageRequested) {
            binding.editButton.text = "Save"  // Change to Save if any change (including removing photo)
        } else {
            binding.editButton.text = "Cancel"  // Revert back to Cancel if no changes
        }
    }

    /* ---------------- Save Changes ---------------- */
    private fun saveProfileChanges() {
        val newName = binding.name.text.toString().trim()
        var newContact = binding.contact.text.toString().trim()

        // Validate Name
        if (newName.isEmpty()) {
            binding.name.error = "Required"; return
        }

        // Normalize 639xxxxxx → 09xxxxxx
        if (newContact.startsWith("639")) {
            newContact = newContact.replaceFirst("639", "09")
            binding.contact.setText(newContact)
        }

        if (newContact.isNotEmpty() && !newContact.matches(Regex("^09\\d{9}$"))) {
            binding.contact.error = "Invalid number. Must start with 09 and have 11 digits."
            return
        }

        val updates = mutableMapOf<String, Any>( "name" to newName, "contact" to newContact )
        // If the profile image has been removed (removeProfileImageRequested == true), we need to clear the profile field.
        if (removeProfileImageRequested) {
            updates["profile"] = ""  // Clear the profile image from the database
        } else if (base64ProfileImage != null) {
            // If a new profile image has been selected, update the profile image in the database
            updates["profile"] = base64ProfileImage!!
        }

        showLoadingDialog("Saving…")
        database.child(auth.currentUser?.uid ?: "").updateChildren(updates)
            .addOnSuccessListener {
                hideLoadingDialog()
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
                originalName = newName  // Update the original name
                originalContact = newContact  // Update the original contact
                hasProfileImage = base64ProfileImage != null
                removeProfileImageRequested = false
            }
            .addOnFailureListener { e ->
                hideLoadingDialog()
                Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }


    /* ---------------- Image Picker ---------------- */
    private fun showImageSourceSheet() {
        if (!isEditing) return  // Don't show options if not in edit mode

        val options = if (hasProfileImage)
            arrayOf("Take photo", "Choose from gallery", "Remove photo")
        else
            arrayOf("Take photo", "Choose from gallery")

        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Take photo" -> ensureCameraAndOpen()
                    "Choose from gallery" -> ensureGalleryAndOpen()
                    "Remove photo" -> {
                        binding.profileIcon.setImageResource(R.drawable.ic_profile)  // Reset the profile icon
                        base64ProfileImage = null  // Remove base64 data
                        hasProfileImage = false  // Update the flag indicating there's no profile image
                        removeProfileImageRequested = true  // Mark that a photo has been removed
                        Toast.makeText(this, "Photo removed (pending save)", Toast.LENGTH_SHORT).show()

                        // After removing the photo, we need to detect the change and set the button to "Save"
                        binding.editButton.text = "Save"  // Set the button text to Save, indicating the user has made a change
                    }
                }
            }.show()
    }


    /* ---------------- Permissions / Launchers ---------------- */
    private fun ensureCameraAndOpen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else openCamera()
    }
    private fun ensureGalleryAndOpen() {
        // Check permissions based on Android version
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            // For Android 13 and above, use READ_MEDIA_IMAGES
            Log.d("EditProfileActivity", "Requesting READ_MEDIA_IMAGES permission")
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            // For older versions, use READ_EXTERNAL_STORAGE
            Log.d("EditProfileActivity", "Requesting READ_EXTERNAL_STORAGE permission")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        // Check if we have the required permission
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            // Log if permission is not granted
            Log.d("EditProfileActivity", "Permission not granted, requesting permission.")
            // If permission is not granted, request it
            ActivityCompat.requestPermissions(this, arrayOf(perm), GALLERY_PERMISSION_REQUEST_CODE)
        } else {
            // If permission is already granted, open the gallery
            Log.d("EditProfileActivity", "Permission granted, opening gallery.")
            openGallery()
        }
    }





    private fun openCamera() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(cameraIntent, CAMERA_REQUEST_CODE)
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        // Log when opening the gallery
        Log.d("EditProfileActivity", "Opening gallery with Intent.ACTION_PICK")

        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"  // Ensure we're selecting images only

        // Log if the intent can be resolved
        if (intent.resolveActivity(packageManager) != null) {
            Log.d("EditProfileActivity", "Gallery Intent resolved, starting activity.")
            startActivityForResult(intent, GALLERY_REQUEST_CODE)
        } else {
            // Log if the intent cannot be resolved
            Log.e("EditProfileActivity", "No app found to open the gallery.")
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)


        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // Log permission denied
            Log.d("EditProfileActivity", "Permission denied to access the gallery/storage.")
            Toast.makeText(this, "Permission denied to read your external storage", Toast.LENGTH_SHORT).show()
            return
        }

        // Log if permission is granted
        Log.d("EditProfileActivity", "Permission granted to access the gallery/storage.")

        // If permission is granted, open the gallery
        when (requestCode) {
            GALLERY_PERMISSION_REQUEST_CODE -> {
                openGallery()
            }
        }
    }


    @Deprecated("Using startActivityForResult; migrate to Activity Result APIs when convenient")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Log the resultCode and requestCode for debugging
        Log.d("EditProfileActivity", "onActivityResult called with requestCode: $requestCode, resultCode: $resultCode")

        if (resultCode != RESULT_OK) {
            Log.d("EditProfileActivity", "Result was not OK, no image selected.")
            return
        }


        when (requestCode) {
            CAMERA_REQUEST_CODE -> {
                val imageBitmap = data?.extras?.get("data") as? Bitmap ?: return
                binding.profileIcon.setImageBitmap(imageBitmap)
                base64ProfileImage = convertBitmapToBase64(imageBitmap)
                hasProfileImage = true
                removeProfileImageRequested = false
                // Immediately update the "Save" button text to "Save"
                binding.editButton.text = "Save"
                Toast.makeText(this, "Profile picture updated (pending save)", Toast.LENGTH_SHORT).show()
            }
            GALLERY_REQUEST_CODE -> {
                val uri = data?.data ?: return
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                binding.profileIcon.setImageBitmap(bitmap)
                base64ProfileImage = convertBitmapToBase64(bitmap)
                hasProfileImage = true
                removeProfileImageRequested = false
                // Immediately update the "Save" button text to "Save"
                binding.editButton.text = "Save"
                Toast.makeText(this, "Profile picture updated (pending save)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ---------------- Bitmap <-> Base64 ---------------- */
    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun convertBase64ToBitmap(base64String: String?): Bitmap? {
        if (base64String.isNullOrEmpty()) return null
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
