package com.example.flare_capstone

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.flare_capstone.databinding.ActivityOnboard1Binding
import com.google.firebase.auth.FirebaseAuth

class Onboard1Activity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboard1Binding
    private lateinit var auth: FirebaseAuth
    private val logoutTimeLimit: Long = 30 * 60 * 1000 // 30 minutes in milliseconds
    private var lastInteractionTime: Long = System.currentTimeMillis()
    private val handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityOnboard1Binding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Check if the user is already logged in
        val currentUser = auth.currentUser
        if (currentUser != null) {
            val email = currentUser.email
            if (email == "tccfsff123@gmail.com" ||
                email == "lffssff123@gmail.com" ||
                email == "tcwfssff123@gmail.com") {

                Toast.makeText(this, "Welcome back, Firefighter", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, DashboardFireFighterActivity::class.java))
                finish()
                return
            } else {
                Toast.makeText(this, "Welcome back", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
                return
            }
        }


        // Set listeners for buttons
        binding.getStartedButton.setOnClickListener {
            resetInactivityTimer() // Reset timer on user interaction
            startActivity(Intent(this, Onboard2Activity::class.java))
            finish()
        }

        binding.skipButton.setOnClickListener {
            resetInactivityTimer() // Reset timer on user interaction
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // Set a Runnable to log out the user automatically if they are inactive for too long
        handler.postDelayed(logoutRunnable, logoutTimeLimit)
    }

    // This function resets the inactivity timer whenever the user interacts with the app
    private fun resetInactivityTimer() {
        lastInteractionTime = System.currentTimeMillis()
        handler.removeCallbacks(logoutRunnable)
        handler.postDelayed(logoutRunnable, logoutTimeLimit)
    }

    // Runnable that will be triggered after the inactivity timeout
    private val logoutRunnable = Runnable {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            auth.signOut() // Sign out the user if inactive for too long
            Toast.makeText(this, "You have been logged out due to inactivity", Toast.LENGTH_SHORT).show()

            // Redirect to login screen
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        resetInactivityTimer() // Reset inactivity timer whenever the activity is resumed
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(logoutRunnable) // Remove the logout callback when the activity is paused
    }
}