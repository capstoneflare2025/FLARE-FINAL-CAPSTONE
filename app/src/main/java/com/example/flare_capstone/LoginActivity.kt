package com.example.flare_capstone

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.example.flare_capstone.databinding.ActivityLoginBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.firebase.auth.FirebaseAuth
// at top of LoginActivity
import com.google.firebase.database.*

class LoginActivity : AppCompatActivity() {

    /* ---------------- State ---------------- */
    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    private var verifyDialog: androidx.appcompat.app.AlertDialog? = null
    private val verifyHandler = Handler(Looper.getMainLooper())
    private var verifyPoll: Runnable? = null

    // Cache (null = not loaded yet)
    private var firefighterEmailsCache: Set<String>? = null

    /* ---------------- Rules ---------------- */
    // Strong password: 8+ chars, upper, lower, digit, special, no spaces
    private val PASSWORD_REGEX =
        Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9])(?=\\S+$).{8,}$")

    /* =========================================================
     * Lifecycle
     * ========================================================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        setupPasswordToggle(binding.password)
        binding.password.compoundDrawablePadding = dp(14)

        // “Done” on keyboard triggers login
        binding.password.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                onLoginClicked()
                true
            } else false
        }

        /* -------- Navigation buttons -------- */
        binding.registerButton.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        binding.logo.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        /* -------- Login flow -------- */
        binding.loginButton.setOnClickListener { onLoginClicked() }

        /* -------- Forgot Password -------- */
        binding.forgotPassword.setOnClickListener { onForgotPassword() }

        /* -------- Handle password reset deep link -------- */
        handleResetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResetIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVerifyPolling()
        verifyDialog = null
    }

    /* =========================================================
     * Helpers
     * ========================================================= */
    private fun isFirefighter(emailLc: String): Boolean {
        return emailLc == "tccfsff123@gmail.com" ||
                emailLc == "tcwfssff123@gmail.com" ||
                emailLc == "lffssff123@gmail.com"
    }

    /* =========================================================
     * Login click → sign-in; SKIP verify for firefighter emails
     * ========================================================= */
    private fun onLoginClicked() {
        val email = binding.email.text.toString().trim().lowercase()
        val password = binding.password.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            if (email.isEmpty()) binding.email.error = "Required" else binding.email.error = null
            if (password.isEmpty()) binding.password.error = "Required" else binding.password.error = null
            Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
            return
        } else {
            binding.email.error = null
            binding.password.error = null
        }

        setLoginEnabled(false)

        // 1) Try sign-in first
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (!task.isSuccessful) {
                    // Re-enable button on failure
                    setLoginEnabled(true)

                    // 2) Diagnose: no account vs wrong provider vs wrong password
                    val ex = task.exception
                    android.util.Log.w("AuthDebug", "Sign-in failed: ${ex?.javaClass?.simpleName} - ${ex?.message}")

                    auth.fetchSignInMethodsForEmail(email)
                        .addOnSuccessListener { res ->
                            val methods = res.signInMethods ?: emptyList()
                            android.util.Log.d("AuthDebug", "providers for $email: $methods")

                            when {
                                methods.isEmpty() -> {
                                    showNoAccountDialog(email)
                                }
                                !methods.contains("password") -> {
                                    showDifferentProviderDialog(email, methods.joinToString())
                                }
                                else -> {
                                    Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Couldn’t check account: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    return@addOnCompleteListener
                }

                // 3) Sign-in succeeded
                val user = auth.currentUser
                if (user == null) {
                    setLoginEnabled(true)
                    Toast.makeText(this, "Login failed: no user session", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                val emailLc = (user.email ?: "").lowercase()

                // ---- SKIP verification for firefighter emails ----
                if (isFirefighter(emailLc)) {
                    setLoginEnabled(true)
                    routeToDashboard(emailLc)  // will send firefighters to their dashboard
                    return@addOnCompleteListener
                }

                // ---- For everyone else, enforce verification ----
                user.reload().addOnCompleteListener { reloadTask ->
                    if (!reloadTask.isSuccessful) {
                        setLoginEnabled(true)
                        Toast.makeText(this, "Couldn't refresh status. Try again.", Toast.LENGTH_SHORT).show()
                        return@addOnCompleteListener
                    }

                    if (!user.isEmailVerified) {
                        // Auto-resend once (best effort)
                        user.sendEmailVerification()
                            .addOnSuccessListener { Toast.makeText(this, "Verification link sent to ${user.email}.", Toast.LENGTH_SHORT).show() }
                            .addOnFailureListener { /* silence */ }

                        // BLOCK here: do not open any dashboard; start real-time watcher
                        showBlockingVerifyWatcher(user)
                        return@addOnCompleteListener
                    }

                    // Verified — route normally
                    setLoginEnabled(true)
                    routeToDashboard(emailLc)
                }
            }
    }

    private fun setLoginEnabled(enabled: Boolean) {
        binding.loginButton.isEnabled = enabled
        binding.loginButton.alpha = if (enabled) 1f else 0.6f
    }

    /* =========================================================
     * Blocking, real-time verification watcher (non-firefighter only)
     * ========================================================= */
    private fun showBlockingVerifyWatcher(user: com.google.firebase.auth.FirebaseUser) {
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Verify your email")
            .setMessage(
                "We sent a verification link to:\n${user.email}\n\n" +
                        "Please tap the link. This screen will update automatically once your email is verified."
            )
            .setCancelable(false)
            .setPositiveButton("Resend link", null)
            .setNeutralButton("Open email app", null)
            .setNegativeButton("Cancel", null)
            .create()

        dlg.setOnShowListener {
            // Resend
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                user.sendEmailVerification()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Verification link resent.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Resend failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }

            // Open email app
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_EMAIL)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")))
                    } catch (_: Exception) { /* ignore */ }
                }
            }

            // Cancel → sign out and return to login
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                stopVerifyPolling()
                auth.signOut()
                dlg.dismiss()
                setLoginEnabled(true)
                Toast.makeText(this, "Sign in again after verifying.", Toast.LENGTH_SHORT).show()
            }
        }

        verifyDialog = dlg
        dlg.show()

        // Poll every 4 seconds until verified
        verifyPoll = object : Runnable {
            override fun run() {
                user.reload().addOnCompleteListener { t ->
                    if (t.isSuccessful && user.isEmailVerified) {
                        stopVerifyPolling()
                        verifyDialog?.dismiss()
                        verifyDialog = null
                        setLoginEnabled(true)
                        routeToDashboard((user.email ?: "").lowercase())
                    } else {
                        verifyHandler.postDelayed(this, 4000)
                    }
                }
            }
        }
        verifyHandler.post(verifyPoll!!)
    }

    private fun stopVerifyPolling() {
        verifyPoll?.let { verifyHandler.removeCallbacks(it) }
        verifyPoll = null
    }

    /* =========================================================
     * Routing (keeps firefighter special-case)
     * ========================================================= */
    private fun routeToDashboard(emailLc: String) {
        val intent = if (isFirefighter(emailLc))
            Intent(this, DashboardFireFighterActivity::class.java)
        else
            Intent(this, DashboardActivity::class.java)

        startActivity(intent)
        finish()
    }

    /* =========================================================
     * Forgot Password (unchanged)
     * ========================================================= */
    private fun onForgotPassword() {
        val email = binding.email.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.email.error = "Enter valid email, e.g., example@gmail.com"
            Toast.makeText(this, "Invalid email format", Toast.LENGTH_SHORT).show()
            return
        }
        sendPasswordResetLink(email)
    }

    private fun sendPasswordResetLink(email: String) {
        android.util.Log.d("PasswordReset", "Attempting to send reset email to: $email")
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            android.util.Log.e("PasswordReset", "Invalid email format: $email")
            Toast.makeText(this, "Invalid email format", Toast.LENGTH_SHORT).show()
            return
        }

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    android.util.Log.i("PasswordReset", "Password reset link successfully sent to: $email")
                    Toast.makeText(this, "Password reset link sent to $email", Toast.LENGTH_SHORT).show()
                } else {
                    val exception = task.exception
                    android.util.Log.e("PasswordReset", "Failed to send reset email", exception)
                    Toast.makeText(this, "Reset failed: ${exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    /* =========================================================
     * Password reset deep link handler
     * ========================================================= */
    private fun handleResetIntent(intent: Intent) {
        val data: Uri? = intent.data ?: return
        val mode = data!!.getQueryParameter("mode")
        val oobCode = data.getQueryParameter("oobCode")
        if (mode != "resetPassword" || oobCode.isNullOrBlank()) return

        auth.verifyPasswordResetCode(oobCode)
            .addOnSuccessListener { showNewPasswordDialog(oobCode) }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Invalid or expired reset link: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showNewPasswordDialog(oobCode: String) {
        val newPass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "New password"
        }
        val confirmPass = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "Confirm password"
        }

        setupPasswordToggle(newPass)
        setupPasswordToggle(confirmPass)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
            addView(newPass)
            addView(confirmPass)
        }

        AlertDialog.Builder(this)
            .setTitle("Set New Password")
            .setView(container)
            .setPositiveButton("Update") { _, _ ->
                val p1 = newPass.text.toString()
                val p2 = confirmPass.text.toString()
                if (!PASSWORD_REGEX.matches(p1)) {
                    Toast.makeText(
                        this,
                        "Weak password. Use 8+ chars with upper, lower, number, special, no spaces.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                if (p1 != p2) {
                    Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                auth.confirmPasswordReset(oobCode, p1)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Password updated. You can log in now.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to update password: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* =========================================================
     * Small UI helpers
     * ========================================================= */
    private fun setupPasswordToggle(editText: EditText) {
        val visibleIcon = android.R.drawable.ic_menu_view
        val hiddenIcon = android.R.drawable.ic_secure
        setEndIcon(editText, hiddenIcon)
        editText.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableRight = 2
                val d = editText.compoundDrawables[drawableRight]
                if (d != null) {
                    val iconStart = editText.width - editText.paddingRight - d.intrinsicWidth
                    if (event.x >= iconStart) {
                        togglePasswordVisibility(editText, visibleIcon, hiddenIcon)
                        v.performClick()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private fun togglePasswordVisibility(editText: EditText, visibleIcon: Int, hiddenIcon: Int) {
        val sel = editText.selectionEnd
        val hidden = editText.transformationMethod is PasswordTransformationMethod
        if (hidden) {
            editText.transformationMethod = null
            setEndIcon(editText, visibleIcon)
        } else {
            editText.transformationMethod = PasswordTransformationMethod.getInstance()
            setEndIcon(editText, hiddenIcon)
        }
        editText.setSelection(sel)
    }

    private fun setEndIcon(editText: EditText, iconRes: Int) {
        editText.setCompoundDrawablesWithIntrinsicBounds(0, 0, iconRes, 0)
    }

    private fun dp(px: Int) = (px * resources.displayMetrics.density).toInt()

    /* =========================================================
     * Dialog helpers (custom, hard-coded views)
     * ========================================================= */
    private fun showNoAccountDialog(email: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_person_add_24)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                bottomMargin = dp(8)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(icon)

        val title = TextView(this).apply {
            text = "No account found"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        root.addSpace(8)

        val msg = TextView(this).apply {
            text = "We couldn't find an account for:\n$email\n\nWould you like to create one?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        root.addView(msg)

        MaterialAlertDialogBuilder(this)
            .setView(root)
            .setPositiveButton("Register") { _, _ ->
                startActivity(Intent(this, RegisterActivity::class.java))
                finish()
            }
            .setNegativeButton("Try another email", null)
            .show()
    }

    private fun showDifferentProviderDialog(email: String, providers: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_warning_24)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                bottomMargin = dp(8)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(icon)

        val title = TextView(this).apply {
            text = "Use a different sign-in"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        root.addSpace(8)

        val body = TextView(this).apply {
            text = "The email $email is registered with a different sign-in method ($providers).\nPlease use the same method you used to sign up."
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        root.addView(body)

        root.addSpace(10)

        val chip = TextView(this).apply {
            text = providers
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            background = MaterialShapeDrawable().apply {
                setCornerSize(dp(14).toFloat())
            }
        }
        root.addView(chip)

        MaterialAlertDialogBuilder(this)
            .setView(root)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun LinearLayout.addSpace(heightDp: Int) {
        addView(Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)
            )
        })
    }
}
