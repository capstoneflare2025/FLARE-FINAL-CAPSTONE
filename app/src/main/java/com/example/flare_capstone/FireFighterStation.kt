package com.example.flare_capstone

data class FireFighterStation(
    val id: String = "",
    val name: String = "",
    val lastMessage: String = "",     // text OR a derived preview like “Sent a voice message.”
    val timestamp: Long = 0L,
    val profileUrl: String = "",
    val lastSender: String = "",      // "admin" or the firefighter’s display name
    var isRead: Boolean = true,       // unread rows set this to false
    val hasAudio: Boolean = false,    // true if the latest payload had audioBase64
    val hasImage: Boolean = false,// true if the latest payload had imageBase64
    val hasUnreadAdminReply: Boolean = false,
    val adminMessagesPath: String  // NEW flag
)
