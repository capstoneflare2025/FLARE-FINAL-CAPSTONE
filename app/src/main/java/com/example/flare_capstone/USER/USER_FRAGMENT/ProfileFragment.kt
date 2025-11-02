package com.example.flare_capstone.USER.USER_FRAGMENT

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
import com.example.flare_capstone.ChangePasswordActivity
import com.example.flare_capstone.EditProfileActivity
import com.example.flare_capstone.MainActivity
import com.example.flare_capstone.MyReportActivity
import com.example.flare_capstone.R
import com.example.flare_capstone.databinding.FragmentProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase


class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
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
                    val user = snapshot.child("name").getValue(String::class.java)
                    user?.let {
                        _binding?.apply {
                            fullName.text = it
                        }
                    }

                    // Load and display profile image
                    val profileBase64 = snapshot.child("profile").getValue(String::class.java)
                    if (!profileBase64.isNullOrEmpty()) {
                        val bitmap = convertBase64ToBitmap(profileBase64)
                        if (bitmap != null) {
                            _binding?.profileIcon?.setImageBitmap(bitmap)
                        }
                    }
                }
            }.addOnFailureListener {
                Toast.makeText(requireActivity(), "Failed to load user data: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireActivity(), "User not authenticated", Toast.LENGTH_SHORT).show()
        }

        _binding?.editProfile?.setOnClickListener {
            startActivity(Intent(requireActivity(), EditProfileActivity::class.java))
        }

        _binding?.changePassword?.setOnClickListener {
            startActivity(Intent(requireActivity(), ChangePasswordActivity::class.java))
        }

        _binding?.myReport?.setOnClickListener {
            startActivity(Intent(requireActivity(), MyReportActivity::class.java))
        }

        _binding?.logout?.setOnClickListener {
            val inflater = requireActivity().layoutInflater
            val dialogView = inflater.inflate(R.layout.dialog_logout, null)
            val logoImageView = dialogView.findViewById<ImageView>(R.id.logoImageView)
            logoImageView.setImageResource(R.drawable.ic_logo)

            AlertDialog.Builder(requireActivity())
                .setView(dialogView)
                .setPositiveButton("Yes") { _, _ ->
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

