package com.example.flare_capstone.BFP.BFP_FRAGMENT

import android.app.AlertDialog
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
import com.example.flare_capstone.EditFirefighterProfileActivity
import com.example.flare_capstone.FireFighterReportActivity
import com.example.flare_capstone.MainActivity
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.FragmentProfileFireFighterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ProfileFireFighterFragment : Fragment() {

    private var _binding: FragmentProfileFireFighterBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseDatabase.getInstance().reference }

    // Example result:
    // "CapstoneFlare/CanocotanFireStation/FireFighter/FireFighterAccount/CanocotanFireFighterAccount"
    private var matchedPath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileFireFighterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // placeholders while loading
        binding.fullyName.text = "Loading..."
        binding.fullName.text = auth.currentUser?.email ?: "Unknown"
        binding.contact.text = "—"
        binding.profileIcon.setImageResource(R.drawable.ic_default_profile)

        val email = auth.currentUser?.email?.trim()?.lowercase()
        if (email.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No signed-in user.", Toast.LENGTH_SHORT).show()
        } else {
            loadAndBindProfile(email)
        }

        // Edit Profile → launch firefighter editor with the matched path
        binding.editProfile.setOnClickListener {
            val path = matchedPath
            if (path == null) {
                Toast.makeText(requireContext(), "Profile path not resolved yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(requireActivity(), EditFirefighterProfileActivity::class.java)
            intent.putExtra(EditFirefighterProfileActivity.Companion.EXTRA_DB_PATH, path)
            startActivity(intent)
        }

        // Reports screen
        binding.editReport.setOnClickListener {
            val intent = Intent(requireActivity(), FireFighterReportActivity::class.java)
            startActivity(intent)
        }

        // Logout
        binding.logout.setOnClickListener {
            val dialogView = layoutInflater.inflate(R.layout.dialog_logout, null)
            dialogView.findViewById<ImageView>(R.id.logoImageView)?.setImageResource(R.drawable.ic_logo)

            AlertDialog.Builder(requireActivity())
                .setView(dialogView)
                .setPositiveButton("Yes") { _, _ ->
                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(requireActivity(), MainActivity::class.java))
                    Toast.makeText(requireActivity(), "You have been logged out.", Toast.LENGTH_SHORT).show()
                    requireActivity().finish()
                }
                .setNegativeButton("No") { dlg, _ -> dlg.dismiss() }
                .create()
                .show()
        }
    }

    /**
     * Map the email → (stationRoot, accountKey), then read:
     * <StationRoot>/FireFighter/FireFighterAccount/<AccountKey>
     * Expected keys: email, name, contact, profile(Base64)
     */
    private fun loadAndBindProfile(emailLc: String) {
        val info = stationInfoForEmail(emailLc)
        if (info == null) {
            bindUnknown(emailLc, reason = "No station mapping for $emailLc")
            return
        }

        val (stationRoot, accountKey) = info
        val path = "$stationRoot/FireFighter/FireFighterAccount/$accountKey"
        matchedPath = path

        db.child(path).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!isAdded || _binding == null) return

                if (!snap.exists()) {
                    bindUnknown(emailLc, reason = "Profile node missing at $path")
                    return
                }

                val stationEmail = snap.child("email").getValue(String::class.java)?.trim()?.lowercase()
                val name = snap.child("name").getValue(String::class.java) ?: "(No name)"
                val contact = snap.child("contact").getValue(String::class.java) ?: "—"
                val profileBase64 = snap.child("profile").getValue(String::class.java)

                // If DB email is present and doesn't match, still show but warn.
                if (!stationEmail.isNullOrBlank() && stationEmail != emailLc) {
                    Toast.makeText(requireContext(), "Warning: profile email differs from signed-in email.", Toast.LENGTH_SHORT).show()
                }

                binding.fullyName.text = name
                binding.fullName.text = stationEmail ?: emailLc
                binding.contact.text = contact

                convertBase64ToBitmap(profileBase64)?.let {
                    binding.profileIcon.setImageBitmap(it)
                } ?: binding.profileIcon.setImageResource(R.drawable.ic_default_profile)
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded || _binding == null) return
                bindUnknown(emailLc, reason = "DB error: ${error.message}")
            }
        })
    }

    /**
     * Returns Pair(stationRoot, accountKey) for a given firefighter email.
     * Update mappings here as needed.
     */
    private fun stationInfoForEmail(email: String?): Pair<String, String>? {
        val e = email ?: return null
        return when (e.lowercase()) {
            // Mabini
            "tcwfssff123@gmail.com" -> "CapstoneFlare/MabiniFireStation" to "MabiniFireFighterAccount"
            // La Filipina
            "lffssff123@gmail.com"  -> "CapstoneFlare/LaFilipinaFireStation" to "LaFilipinaFireFighterAccount"
            // Canocotan
            "tccfsff123@gmail.com"  -> "CapstoneFlare/CanocotanFireStation" to "CanocotanFireFighterAccount"
            else -> null
        }
    }

    private fun bindUnknown(emailLc: String, reason: String) {
        binding.fullyName.text = "Unknown Firefighter"
        binding.fullName.text = emailLc
        binding.contact.text = "—"
        binding.profileIcon.setImageResource(R.drawable.ic_default_profile)
        matchedPath = null
        Toast.makeText(requireContext(), reason, Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
