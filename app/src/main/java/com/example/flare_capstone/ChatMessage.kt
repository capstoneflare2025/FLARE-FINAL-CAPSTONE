package com.example.flare_capstone

import com.google.firebase.database.PropertyName

data class ChatMessage(
    var type: String? = null,
    var text: String? = null,
    var imageBase64: String? = null,
    var audioBase64: String? = null,
    var uid: String? = null,
    var incidentId: String? = null,
    var reporterName: String? = null,
    var date: String? = null,
    var time: String? = null,
    var timestamp: Long? = null,

    @get:PropertyName("isRead")
    @set:PropertyName("isRead")
    var isRead: Boolean? = false
)
