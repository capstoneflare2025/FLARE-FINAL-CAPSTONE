package com.example.flare_capstone

import android.Manifest
import android.animation.LayoutTransition
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import com.example.flare_capstone.databinding.ActivityFireFighterResponseBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FireFighterResponseActivity (Base64-only media)
 *
 * - Reads/writes to:
 *   TagumCityCentralFireStation/FireFighter/AllFireFighterAccount/{accountKey}/AdminMessages
 * - Message schema (mutually exclusive payload):
 *   Text:  { sender, timestamp, text }
 *   Image: { sender, timestamp, imageBase64 }
 *   Audio: { sender, timestamp, audioBase64 }
 *
 * UX:
 *  • Tap record -> record bar with live timer, pause/cancel/send
 *  • Typing -> expand input + show back icon to collapse
 *  • Pick/take photo -> confirmation dialog before send
 *  • Bubbles: text / image / audio (playable)
 */
class FireFighterResponseActivity : AppCompatActivity() {

    // ----- Firebase paths -----
    private val ROOT = "TagumCityCentralFireStation"
    private val ACCOUNTS = "FireFighter/AllFireFighterAccount"
    private val ADMIN_MESSAGES = "AdminMessages"


    // ----- View / Firebase -----
    private lateinit var binding: ActivityFireFighterResponseBinding
    private lateinit var db: DatabaseReference
    private var accountKey: String? = null
    private var accountName: String = ""

    // ----- Stream -----
    private var msgListener: ValueEventListener? = null
    private var msgRef: Query? = null
    private var lastTimestamp: Long = 0L

    // ----- Recording -----
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var isRecording = false
    private var isPaused = false
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private var recordStartMs = 0L
    private var pauseStartMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = computeElapsedMs()
            binding.recordTimer.text = formatDuration(elapsed)
            timerHandler.postDelayed(this, 500)
        }
    }

    // For audio playback
    private val activePlayers = mutableListOf<MediaPlayer>()

    // ----- Pending image (Base64) -----
    private var pendingImageBase64: String = ""

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun maxBubbleWidthPx(): Int =
        (resources.displayMetrics.widthPixels * 0.7f).toInt() // ~70% like Messenger

    private fun maxImageHeightPx(): Int =
        (resources.displayMetrics.heightPixels * 0.35f).toInt() // cap tall images to 35% screen


    // ----- Activity Result contracts -----
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val input = contentResolver.openInputStream(uri)
        val bmp = BitmapFactory.decodeStream(input)
        input?.close()
        if (bmp != null) {
            pendingImageBase64 = bitmapToBase64(bmp)
            confirmSendImage(bmp)
        }
    }

    private val captureImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val bmp = res.data?.extras?.get("data") as? Bitmap
            bmp?.let {
                pendingImageBase64 = bitmapToBase64(it)
                confirmSendImage(it)
            }
        }
    }

    // Permissions for microphone (and legacy external storage read for gallery pre-33)
    private val reqPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grant ->
            val mic = grant[Manifest.permission.RECORD_AUDIO] == true
            val storageOk = if (Build.VERSION.SDK_INT >= 33) true
            else grant[Manifest.permission.READ_EXTERNAL_STORAGE] != false
            if (mic && storageOk) startRecordingMessengerStyle()
            else Toast.makeText(this, "Recording permission denied.", Toast.LENGTH_SHORT).show()
        }

    // ----- Data model -----
    data class ChatMessage(
        val sender: String = "",
        val text: String? = null,          // only for text
        val imageBase64: String? = null,   // only for image
        val audioBase64: String? = null,   // only for audio
        val timestamp: Long = 0L,
        val date: String? = null,          // optional, kept if you already write them
        val time: String? = null,          // optional
        val isRead: Boolean? = null        // optional
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFireFighterResponseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseDatabase.getInstance().reference

        // Back
        binding.back.setOnClickListener { finish() }

        // Resolve account
        resolveLoggedInAccountAndAttach()

        // Typing expansion
        binding.chatInputArea.layoutTransition?.enableTransitionType(LayoutTransition.CHANGING)

        binding.messageInput.setOnFocusChangeListener { _, hasFocus ->
            val expanded = hasFocus || binding.messageInput.text?.isNotBlank() == true
            setExpandedUi(expanded)
        }


        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val expanded = !s.isNullOrBlank() || binding.messageInput.hasFocus()
                setExpandedUi(expanded)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.chatInputArea) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val expanded = imeVisible || binding.messageInput.text?.isNotBlank() == true
            setExpandedUi(expanded)
            insets
        }
        binding.arrowBackIcon.setOnClickListener {
            binding.messageInput.clearFocus()
            binding.chatInputArea.hideKeyboard()
            setExpandedUi(false)
        }

        // Camera
        binding.cameraIcon.setOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(packageManager) != null) {
                captureImage.launch(intent)
            } else {
                Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
            }
        }
        // Gallery
        binding.galleryIcon.setOnClickListener {
            if (Build.VERSION.SDK_INT < 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                reqPerms.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            } else {
                pickImage.launch("image/*")
            }
        }

        // Record button
        binding.voiceRecordIcon.setOnClickListener {
            val needs = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT < 33) {
                needs += Manifest.permission.READ_EXTERNAL_STORAGE   // ✅ operator form
            }
            reqPerms.launch(needs.toTypedArray())

        }
        binding.recordPause.setOnClickListener { togglePauseResume() }
        binding.recordCancel.setOnClickListener { cancelRecording() }
        binding.recordSend.setOnClickListener { finishRecordingAndSend() }

        // Send text (or text+pending image / image only)
        binding.sendButton.setOnClickListener {
            val txt = binding.messageInput.text?.toString()?.trim().orEmpty()
            when {
                txt.isNotEmpty() && pendingImageBase64.isNotEmpty() -> {
                    pushMessage(text = txt) // text only as requested (mutually exclusive)
                    // If you want to send image as a separate message right after:
                    pushMessage(imageBase64 = pendingImageBase64)
                    pendingImageBase64 = ""
                    binding.messageInput.setText("")
                }
                txt.isNotEmpty() -> {
                    pushMessage(text = txt)
                    binding.messageInput.setText("")
                }
                pendingImageBase64.isNotEmpty() -> {
                    pushMessage(imageBase64 = pendingImageBase64)
                    pendingImageBase64 = ""
                }
                else -> Toast.makeText(this, "Type a message or attach a photo.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Call again whenever the screen comes to foreground to be extra sure.
    override fun onResume() {
        super.onResume()
        markUnreadAdminAsRead()
    }
    private fun attachMessages() {
        msgRef = adminMessagesPath().orderByChild("timestamp")
        msgListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<ChatMessage>()
                snapshot.children.forEach { ds ->
                    val msg = ds.getValue(ChatMessage::class.java) ?: return@forEach
                    list.add(msg)
                }
                list.sortBy { it.timestamp }
                renderMessages(list)

                // 👇 whenever we received/updated the thread, mark any unread admin msgs as read
                markUnreadAdminAsRead()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        msgRef!!.addValueEventListener(msgListener!!)
    }

    // 🔹 Batch set isRead=true for all admin messages still marked unread
    private fun markUnreadAdminAsRead() {
        val key = accountKey ?: return
        val adminRef = db.child(ROOT).child(ACCOUNTS).child(key).child(ADMIN_MESSAGES)

        adminRef.orderByChild("sender").equalTo("admin")
            .addListenerForSingleValueEvent(object :
                ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!snap.hasChildren()) return
                    val updates = hashMapOf<String, Any>()
                    for (msg in snap.children) {
                        val isReadNow = msg.child("isRead").getValue(Boolean::class.java) ?: false
                        if (!isReadNow) {
                            // update path: <messageKey>/isRead = true
                            val childKey = msg.key ?: continue
                            updates["$childKey/isRead"] = true
                        }
                    }
                    if (updates.isNotEmpty()) {
                        adminRef.updateChildren(updates)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // ----- Posting (mutually exclusive fields) -----
    private fun pushMessage(text: String? = null, imageBase64: String? = null, audioBase64: String? = null) {
        val key = accountKey ?: return
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(now))
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(now))

        // 🔸 IMPORTANT: messages sent by the firefighter are always read on their side
        val msg = HashMap<String, Any?>().apply {
            put("sender", accountName)
            put("timestamp", now)
            put("date", date)
            put("time", time)
            put("isRead", true)  // ✅ self-sent messages should not count as unread
            when {
                !text.isNullOrBlank() -> put("text", text)
                !imageBase64.isNullOrBlank() -> put("imageBase64", imageBase64)
                !audioBase64.isNullOrBlank() -> put("audioBase64", audioBase64)
            }
        }

        val payloadHasField =
            msg.containsKey("text") || msg.containsKey("imageBase64") || msg.containsKey("audioBase64")
        if (!payloadHasField) {
            Toast.makeText(this, "Nothing to send.", Toast.LENGTH_SHORT).show()
            return
        }

        db.child(ROOT).child(ACCOUNTS).child(key).child(ADMIN_MESSAGES)
            .push().setValue(msg)
            .addOnCompleteListener { t ->
                if (!t.isSuccessful) {
                    Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show()
                }
            }
    }


    // ----- UI helpers -----

    private fun View.hideKeyboard() {
        val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun setExpandedUi(expanded: Boolean) {
        binding.cameraIcon.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.galleryIcon.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.voiceRecordIcon.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.arrowBackIcon.visibility = if (expanded) View.VISIBLE else View.GONE

        val hasText = binding.messageInput.text?.isNotBlank() == true
        binding.sendButton.isEnabled = hasText || pendingImageBase64.isNotEmpty()
        binding.sendButton.alpha = if (binding.sendButton.isEnabled) 1f else 0.4f

        binding.messageInput.maxLines = if (expanded) 5 else 3
    }

    private fun showRecordBar(show: Boolean) {
        binding.recordBar.visibility = if (show) View.VISIBLE else View.GONE

        val dim = if (show) 0.3f else 1f
        binding.messageInput.isEnabled = !show
        binding.cameraIcon.isEnabled = !show
        binding.galleryIcon.isEnabled = !show
        binding.voiceRecordIcon.isEnabled = !show
        binding.sendButton.isEnabled = !show

        binding.messageInput.alpha = dim
        binding.cameraIcon.alpha = dim
        binding.galleryIcon.alpha = dim
        binding.voiceRecordIcon.alpha = dim
        binding.sendButton.alpha = if (show) 0.4f else 1f

        binding.voiceRecordIcon.setImageResource(
            if (show) R.drawable.ic_recording else R.drawable.ic_record
        )
        if (show) {
            binding.recordPause.setImageResource(
                if (isPaused) R.drawable.ic_resume else R.drawable.ic_pause
            )
        }
    }

    // ----- Account + messages -----

    private fun resolveLoggedInAccountAndAttach() {
        val email = FirebaseAuth.getInstance().currentUser?.email?.lowercase()
        if (email.isNullOrBlank()) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show()
            return
        }
        val accountsQuery = db.child(ROOT).child(ACCOUNTS)
            .orderByChild("email").equalTo(email)

        accountsQuery.addListenerForSingleValueEvent(object :
            ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.hasChildren()) {
                    Toast.makeText(this@FireFighterResponseActivity, "Account not found for $email", Toast.LENGTH_SHORT).show()
                    return
                }
                val first = snapshot.children.first()
                accountKey = first.key
                accountName = first.child("name").getValue(String::class.java) ?: (first.key ?: "You")
                binding.fireStationName.text = "Tagum City Central Fire Station"
                attachMessages()
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@FireFighterResponseActivity, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun adminMessagesPath(): DatabaseReference {
        val key = accountKey ?: error("Account not resolved yet")
        return db.child(ROOT).child(ACCOUNTS).child(key).child(ADMIN_MESSAGES)
    }


    // ----- Render -----

    private fun renderMessages(items: List<ChatMessage>) {
        binding.scrollContent.removeAllViews()
        lastTimestamp = 0L
        items.forEach { msg ->
            val isYou = msg.sender.equals(accountName, ignoreCase = true)
            addMessageBubble(msg, isYou)
        }
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addMessageBubble(msg: ChatMessage, isYou: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isYou) Gravity.END else Gravity.START
            setPadding(20, 16, 20, 8)
        }

        // TEXT
        msg.text?.let { text ->
            val tv = TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.WHITE)
                setPadding(20, 14, 20, 14)
                background = if (isYou)
                    resources.getDrawable(R.drawable.received_message_bg, null)
                else
                    resources.getDrawable(R.drawable.sent_message_bg, null)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(tv)
        }

        // IMAGE (Base64)
        msg.imageBase64?.let { b64 ->
            val bmp = base64ToBitmap(b64)
            if (bmp != null) {
                val wrap = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = if (isYou)
                        resources.getDrawable(R.drawable.received_message_bg, null)
                    else
                        resources.getDrawable(R.drawable.sent_message_bg, null)
                    setPadding(8, 8, 8, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        (resources.displayMetrics.widthPixels * 0.65f).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (container.childCount == 0) 0 else 8 }
                }
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                wrap.addView(iv)
                container.addView(wrap)
            }
        }

        // AUDIO (Base64 -> temp file -> player) — compact like Messenger
        msg.audioBase64?.let { b64 ->
            // Make a temp file once, so we can also read duration
            val temp = try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                File.createTempFile("audio_", ".m4a", cacheDir).apply { writeBytes(bytes) }
            } catch (_: Exception) { null }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = if (isYou)
                    resources.getDrawable(R.drawable.received_message_bg, null)
                else
                    resources.getDrawable(R.drawable.sent_message_bg, null)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (container.childCount == 0) 0 else dp(8f) }
                gravity = Gravity.CENTER_VERTICAL
            }

            // Small, fixed-size play button so it can't blow up the bubble
            val playBtn = ImageView(this).apply {
                setImageResource(R.drawable.ic_resume) // use a 24dp/vector if possible
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                adjustViewBounds = false
            }

            // Duration label
            val timeView = TextView(this).apply {
                text = "00:00"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.WHITE)
                setPadding(dp(12f), 0, 0, 0)
            }

            row.addView(playBtn)
            row.addView(timeView)
            container.addView(row)

            // If we have a file, read duration and wire the player
            temp?.let { file ->
                // Read duration once
                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(file.absolutePath)
                    val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    mmr.release()
                    timeView.text = formatDuration(durMs)
                } catch (_: Exception) { /* keep default */ }

                val player = MediaPlayer()
                activePlayers += player

                playBtn.setOnClickListener {
                    try {
                        if (!player.isPlaying) {
                            player.reset()
                            player.setDataSource(file.absolutePath)
                            player.prepare()
                            player.start()
                            playBtn.setImageResource(R.drawable.ic_pause)

                            player.setOnCompletionListener {
                                playBtn.setImageResource(R.drawable.ic_resume)
                            }
                        } else {
                            player.pause()
                            playBtn.setImageResource(R.drawable.ic_resume)
                        }
                    } catch (_: Exception) {
                        Toast.makeText(this, "Cannot play audio", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }


        // TIMESTAMP grouping (6h or new day)
        val showFull = shouldShowTimestamp(lastTimestamp, msg.timestamp)
        val stamp = if (showFull)
            SimpleDateFormat("MMM d, yyyy - HH:mm", Locale.getDefault())
                .format(Date(msg.timestamp))
        else
            SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(msg.timestamp))

        val tsView = TextView(this).apply {
            text = stamp
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.LTGRAY)
            setPadding(8, 4, 8, 0)
            gravity = if (showFull) Gravity.CENTER else (if (isYou) Gravity.END else Gravity.START)
        }
        container.addView(tsView)
        if (showFull) lastTimestamp = msg.timestamp

        binding.scrollContent.addView(container)
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }


    // ----- Recording flow -----

    private fun startRecordingMessengerStyle() {
        try {
            recordFile = java.io.File.createTempFile("voice_", ".m4a", cacheDir)
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(recordFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            isPaused = false
            recordStartMs = System.currentTimeMillis()
            pauseStartMs = 0L
            binding.recordTimer.text = "00:00"
            binding.recordPause.setImageResource(R.drawable.ic_pause)
            timerHandler.post(timerRunnable)
            showRecordBar(true)
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            cleanupRecorder()
            showRecordBar(false)
        }
    }

    private fun togglePauseResume() {
        if (!isRecording) return
        if (Build.VERSION.SDK_INT < 24) {
            Toast.makeText(this, "Pause requires Android 7.0+", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (!isPaused) {
                recorder?.pause()
                isPaused = true
                pauseStartMs = System.currentTimeMillis()
                timerHandler.removeCallbacks(timerRunnable)
                binding.recordPause.setImageResource(R.drawable.ic_resume)
            } else {
                recorder?.resume()
                isPaused = false
                val pausedDuration = System.currentTimeMillis() - pauseStartMs
                recordStartMs += pausedDuration
                pauseStartMs = 0L
                binding.recordPause.setImageResource(R.drawable.ic_pause)
                timerHandler.post(timerRunnable)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not toggle pause", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        cleanupRecorder()
        recordFile?.delete()
        recordFile = null
        isRecording = false
        isPaused = false
        pauseStartMs = 0L
        timerHandler.removeCallbacks(timerRunnable)
        showRecordBar(false)
        Toast.makeText(this, "Recording discarded", Toast.LENGTH_SHORT).show()
    }

    private fun finishRecordingAndSend() {
        val file = recordFile
        try { recorder?.stop() } catch (_: Exception) {}
        cleanupRecorder()
        isRecording = false
        isPaused = false
        pauseStartMs = 0L
        timerHandler.removeCallbacks(timerRunnable)
        showRecordBar(false)

        if (file == null || !file.exists()) {
            Toast.makeText(this, "No recording captured", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bytes = file.readBytes()
            val audioB64 = android.util.Base64.encodeToString(bytes, Base64.DEFAULT)
            pushMessage(audioBase64 = audioB64)   // audio-only message
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to send recording", Toast.LENGTH_SHORT).show()
        } finally {
            file.delete()
        }
    }

    private fun cleanupRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private fun computeElapsedMs(): Long {
        val now = System.currentTimeMillis()
        val base = recordStartMs
        return if (!isPaused) now - base else pauseStartMs - base
    }

    // ----- Image helpers -----

    private fun confirmSendImage(bitmap: Bitmap) {
        AlertDialog.Builder(this)
            .setTitle("Send Picture")
            .setMessage("Do you want to send this picture?")
            .setPositiveButton("Send") { _, _ ->
                // send image-only message
                pushMessage(imageBase64 = pendingImageBase64)
                pendingImageBase64 = ""
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingImageBase64 = ""
            }
            .show()
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        return android.util.Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
    }

    private fun base64ToBitmap(b64: String?): Bitmap? {
        if (b64.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    // ----- Timestamp helpers -----

    private fun shouldShowTimestamp(lastTs: Long, currentTs: Long): Boolean {
        if (lastTs == 0L) return true
        val sixHours = 6 * 60 * 60 * 1000L
        return (currentTs - lastTs) >= sixHours || isNewDay(currentTs, lastTs)
    }

    private fun isNewDay(currentTs: Long, lastTs: Long): Boolean {
        if (lastTs == 0L) return true
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return sdf.format(Date(currentTs)) != sdf.format(Date(lastTs))
    }

    private fun formatDuration(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        val mm = sec / 60
        val ss = sec % 60
        return "%02d:%02d".format(mm, ss)
    }

    // ----- Lifecycle -----

    override fun onDestroy() {
        super.onDestroy()
        msgListener?.let { listener -> msgRef?.removeEventListener(listener) }
        msgListener = null
        msgRef = null

        try { recorder?.stop() } catch (_: Exception) {}
        cleanupRecorder()
        recordFile?.delete()

        activePlayers.forEach {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        activePlayers.clear()
        timerHandler.removeCallbacks(timerRunnable)
    }
}