package com.example.flare_capstone

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.flare_capstone.databinding.ActivityFireReportResponseBinding
import com.google.firebase.database.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FireReportResponseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFireReportResponseBinding
    private lateinit var database: DatabaseReference

    private lateinit var uid: String
    private lateinit var fireStationName: String
    private lateinit var incidentId: String
    private var fromNotification: Boolean = false

    private var base64Image: String = ""
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var isRecording = false
    private var isPaused = false
    private var pauseStartMs = 0L
    private var recordStartMs = 0L

    private val RECORD_AUDIO_PERMISSION_CODE = 103
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recordStartMs
            val sec = (elapsed / 1000).toInt()
            val mm = sec / 60
            val ss = sec % 60
            binding.recordTimer.text = String.format("%02d:%02d", mm, ss)
            timerHandler.postDelayed(this, 500)
        }
    }

    companion object {
        const val CAMERA_REQUEST_CODE = 100
        const val CAMERA_PERMISSION_REQUEST_CODE = 101
        const val GALLERY_REQUEST_CODE = 102

        const val ROOT_NODE = "CapstoneFlare"
        const val FIRE_NODE = "AllReport/FireReport"
        const val OTHER_NODE = "AllReport/OtherEmergencyReport"
        const val EMS_NODE = "AllReport/EmergencyMedicalServicesReport"
        const val SMS_NODE = "AllReport/SmsReport"
    }

    private var stationNode: String = "CanocotanFireStation"
    private var reportNode: String = FIRE_NODE

    data class ChatMessage(
        var type: String? = null,
        var text: String? = null,
        var imageBase64: String? = null,
        var audioBase64: String? = null,
        var uid: String? = null,
        var reporterName: String? = null,
        var date: String? = null,
        var time: String? = null,
        var timestamp: Long? = null,
        var isRead: Boolean? = false
    )

    sealed class MessageItem(val key: String, val timestamp: Long) {
        data class AnyMsg(val keyId: String, val msg: ChatMessage, val time: Long) :
            MessageItem(keyId, time)
    }

    private lateinit var messagesListener: ValueEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFireReportResponseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance().reference.child(ROOT_NODE)

        uid = intent.getStringExtra("UID") ?: ""
        fireStationName = intent.getStringExtra("FIRE_STATION_NAME") ?: "Fire Station"
        incidentId = intent.getStringExtra("INCIDENT_ID") ?: ""
        fromNotification = intent.getBooleanExtra("fromNotification", false)
        stationNode = intent.getStringExtra("STATION_NODE") ?: "CanocotanFireStation"
        reportNode = intent.getStringExtra("REPORT_NODE") ?: FIRE_NODE

        binding.fireStationName.text = fireStationName

        if (incidentId.isEmpty()) {
            Toast.makeText(this, "No Incident ID provided.", Toast.LENGTH_SHORT).show()
            return
        }

        attachMessagesListener()
        setupUi()
    }

    private fun setupUi() {
        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.cameraIcon.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) openCamera()
            else ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.chatInputArea) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val expanded = imeVisible || binding.messageInput.text?.isNotBlank() == true
            setExpandedUi(expanded)
            insets
        }

        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                setExpandedUi(!s.isNullOrBlank())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.arrowBackIcon.setOnClickListener {
            binding.messageInput.clearFocus()
            binding.chatInputArea.hideKeyboard()
            setExpandedUi(false)
        }

        binding.galleryIcon.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, GALLERY_REQUEST_CODE)
        }

        binding.voiceRecordIcon.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) startRecordingMessengerStyle()
            else ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }

        binding.recordPause.setOnClickListener { togglePauseResume() }
        binding.recordCancel.setOnClickListener { cancelRecording() }
        binding.recordSend.setOnClickListener { finishRecordingAndSend() }

        binding.sendButton.setOnClickListener {
            val userMessage = binding.messageInput.text.toString().trim()
            when {
                userMessage.isNotEmpty() && base64Image.isNotEmpty() ->
                    pushChatMessage("reply", userMessage, base64Image)
                userMessage.isNotEmpty() ->
                    pushChatMessage("reply", userMessage, "")
                base64Image.isNotEmpty() ->
                    pushChatMessage("reply", "", base64Image)
                else ->
                    Toast.makeText(this, "Message or image required.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun View.hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun setExpandedUi(expanded: Boolean) {
        val icons = listOf(binding.cameraIcon, binding.galleryIcon, binding.voiceRecordIcon)
        val arrow = binding.arrowBackIcon
        icons.forEach { it.visibility = if (expanded) View.GONE else View.VISIBLE }
        arrow.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun startRecordingMessengerStyle() {
        try {
            recordFile = File.createTempFile("voice_", ".m4a", cacheDir)
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            recordStartMs = System.currentTimeMillis()
            timerHandler.post(timerRunnable)
            binding.recordBar.visibility = View.VISIBLE
        } catch (_: Exception) {
            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
            cleanupRecorder()
        }
    }

    private fun togglePauseResume() {
        if (!isRecording) return
        try {
            if (!isPaused) {
                recorder?.pause()
                isPaused = true
                pauseStartMs = System.currentTimeMillis()
                timerHandler.removeCallbacks(timerRunnable)
            } else {
                recorder?.resume()
                isPaused = false
                val pausedDuration = System.currentTimeMillis() - pauseStartMs
                recordStartMs += pausedDuration
                timerHandler.post(timerRunnable)
            }
        } catch (_: Exception) {}
    }

    private fun cancelRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        cleanupRecorder()
        recordFile?.delete()
        binding.recordBar.visibility = View.GONE
        Toast.makeText(this, "Recording discarded", Toast.LENGTH_SHORT).show()
    }

    private fun finishRecordingAndSend() {
        val file = recordFile ?: return
        try {
            recorder?.stop()
            cleanupRecorder()
            val bytes = file.readBytes()
            val audioB64 = Base64.encodeToString(bytes, Base64.DEFAULT)
            pushChatMessage("reply", "", "", audioB64)
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to send audio", Toast.LENGTH_SHORT).show()
        } finally {
            file.delete()
        }
    }

    private fun cleanupRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun messagesPath(): DatabaseReference =
        database.child(stationNode).child(reportNode).child(incidentId).child("messages")

    private fun attachMessagesListener() {
        messagesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val merged = mutableListOf<MessageItem.AnyMsg>()
                snapshot.children.forEach { ds ->
                    val key = ds.key ?: return@forEach
                    val msg = ds.getValue(ChatMessage::class.java) ?: return@forEach
                    merged.add(MessageItem.AnyMsg(key, msg, msg.timestamp ?: 0L))
                }
                merged.sortBy { it.timestamp }
                renderMerged(merged)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        messagesPath().addValueEventListener(messagesListener)
    }

    private fun pushChatMessage(
        type: String,
        text: String,
        imageBase64: String,
        audioBase64: String = ""
    ) {
        val now = System.currentTimeMillis()
        val msg = ChatMessage(
            type = type,
            text = text.ifBlank { null },
            imageBase64 = imageBase64.ifBlank { null },
            audioBase64 = audioBase64.ifBlank { null },
            uid = uid,
            incidentId = incidentId, // ✅ ADD THIS
            reporterName = intent.getStringExtra("NAME") ?: "",
            date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now)),
            time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now)),
            timestamp = now,
            isRead = false
        )

        messagesPath().push().setValue(msg).addOnCompleteListener {
            if (it.isSuccessful) {
                Toast.makeText(this, "Message sent!", Toast.LENGTH_SHORT).show()
                base64Image = ""
                binding.messageInput.text.clear()
            }
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
    }

    private fun convertBase64ToBitmap(base64String: String?): Bitmap? {
        if (base64String.isNullOrEmpty()) return null
        return try {
            val decoded = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun renderMerged(items: List<MessageItem.AnyMsg>) {
        binding.scrollContent.removeAllViews()
        for (item in items) {
            val isReply = item.msg.type == "reply"
            displayMessage(item.msg, isReply)

            // ✅ Only mark as read for messages NOT sent by current user
            if (item.msg.uid != uid && !(item.msg.isRead ?: false)) {
                markAsRead(item.key)
            }
        }
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }


    private fun displayMessage(msg: ChatMessage, isReply: Boolean) {
        val messageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8, 0, 8) // space between messages
            layoutParams = params
            gravity = if (isReply) Gravity.END else Gravity.START
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Messenger-like padding (left/right offset)
            params.setMargins(
                if (isReply) 80 else 20,
                4,
                if (isReply) 20 else 80,
                0
            )
            layoutParams = params
            setPadding(25, 15, 25, 15)
            background = if (isReply)
                resources.getDrawable(R.drawable.received_message_bg, null)
            else
                resources.getDrawable(R.drawable.sent_message_bg, null)
        }

        // TEXT MESSAGE
        msg.text?.takeIf { it.isNotBlank() }?.let {
            val tv = TextView(this).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(Color.WHITE)
            }
            bubble.addView(tv)
        }

        // IMAGE MESSAGE
        msg.imageBase64?.let {
            convertBase64ToBitmap(it)?.let { bmp ->
                val iv = ImageView(this)
                iv.setImageBitmap(bmp)
                iv.layoutParams = LinearLayout.LayoutParams(
                    (resources.displayMetrics.density * 230).toInt(),
                    (resources.displayMetrics.density * 180).toInt()
                )
                iv.setPadding(0, 10, 0, 5)
                bubble.addView(iv)
            }
        }

        // AUDIO MESSAGE
        msg.audioBase64?.let { audio ->
            val playBtn = TextView(this).apply {
                text = "▶ Play Audio"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.WHITE)
                setPadding(0, 10, 0, 0)
                setOnClickListener {
                    try {
                        val audioBytes = Base64.decode(audio, Base64.DEFAULT)
                        val tempFile = File.createTempFile("audio_", ".m4a", cacheDir)
                        tempFile.writeBytes(audioBytes)
                        val mediaPlayer = android.media.MediaPlayer()
                        mediaPlayer.setDataSource(tempFile.absolutePath)
                        mediaPlayer.prepare()
                        mediaPlayer.start()
                        Toast.makeText(this@FireReportResponseActivity, "Playing audio…", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(this@FireReportResponseActivity, "Error playing audio", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            bubble.addView(playBtn)
        }

        // TIME BELOW EACH MESSAGE
        val ts = msg.timestamp ?: 0L
        val formatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        val timeView = TextView(this).apply {
            text = formatted
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.LTGRAY)
            gravity = if (isReply) Gravity.END else Gravity.START
            setPadding(8, 4, 8, 0)
        }

        messageContainer.addView(bubble)
        messageContainer.addView(timeView)
        binding.scrollContent.addView(messageContainer)
    }


    private fun markAsRead(key: String) {
        messagesPath().child(key).child("isRead").setValue(true)
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null)
            startActivityForResult(intent, CAMERA_REQUEST_CODE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::messagesListener.isInitialized)
            messagesPath().removeEventListener(messagesListener)
    }

    override fun onBackPressed() {
        if (fromNotification) {
            val intent = Intent(this, DashboardActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as? Bitmap
            if (imageBitmap != null) {
                base64Image = convertBitmapToBase64(imageBitmap)
                AlertDialog.Builder(this)
                    .setTitle("Send Picture")
                    .setMessage("Do you want to send this picture?")
                    .setPositiveButton("Send") { _, _ ->
                        pushChatMessage("reply", "", base64Image)
                    }
                    .setNegativeButton("Cancel") { _, _ -> base64Image = "" }
                    .show()
            }
        } else if (requestCode == GALLERY_REQUEST_CODE && resultCode == RESULT_OK) {
            val selectedImageUri = data?.data
            if (selectedImageUri != null) {
                val inputStream = contentResolver.openInputStream(selectedImageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    base64Image = convertBitmapToBase64(bitmap)
                    AlertDialog.Builder(this)
                        .setTitle("Send Picture")
                        .setMessage("Do you want to send this picture?")
                        .setPositiveButton("Send") { _, _ ->
                            pushChatMessage("reply", "", base64Image)
                        }
                        .setNegativeButton("Cancel") { _, _ -> base64Image = "" }
                        .show()
                }
            }
        }
    }
}
