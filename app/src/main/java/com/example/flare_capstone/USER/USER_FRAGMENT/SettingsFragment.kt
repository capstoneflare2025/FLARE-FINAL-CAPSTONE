package com.example.flare_capstone.USER.USER_FRAGMENT

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.flare_capstone.AboutAppActivity
import com.example.flare_capstone.FireStationInfoActivity
import com.example.flare_capstone.MainActivity
import com.example.flare_capstone.R
import com.example.flare_capstone.ThemeManager
import com.example.flare_capstone.UserGuideActivity
import com.example.flare_capstone.databinding.FragmentSettingsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("Users")

        val userId = auth.currentUser?.uid
        if (userId != null) {
            database.child(userId).get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val name = snapshot.child("name").getValue(String::class.java)
                    val profileBase64 = snapshot.child("profile").getValue(String::class.java)
                    _binding?.apply {
                        fullName.text = name
                    }
                    if (!profileBase64.isNullOrEmpty()) {
                        convertBase64ToBitmap(profileBase64)?.let { _binding?.profileIcon?.setImageBitmap(it) }
                    }
                }
            }.addOnFailureListener {
                Toast.makeText(requireActivity(), "Failed to load user data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireActivity(), "User not authenticated", Toast.LENGTH_SHORT).show()
        }

        val sharedPrefs = requireActivity().getSharedPreferences(ThemeManager.PREF_NAME, Context.MODE_PRIVATE)
        val darkModeEnabled = sharedPrefs.getBoolean(ThemeManager.DARK_MODE_KEY, false)
        _binding?.darkModeSwitch?.isChecked = darkModeEnabled
        _binding?.darkModeSwitch?.setOnCheckedChangeListener { _, isChecked ->
            ThemeManager.setDarkModeEnabled(requireContext(), isChecked)
            requireActivity().recreate()
        }

        _binding?.aboutApp?.setOnClickListener {
            startActivity(Intent(requireActivity(), AboutAppActivity::class.java))
        }
        _binding?.userGuide?.setOnClickListener {
            startActivity(Intent(requireActivity(), UserGuideActivity::class.java))
        }
        _binding?.fireStationInfo?.setOnClickListener {
            startActivity(Intent(requireActivity(), FireStationInfoActivity::class.java))
        }

        _binding?.logout?.setOnClickListener {
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
    }

    private fun convertBase64ToBitmap(base64String: String): Bitmap? {
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
