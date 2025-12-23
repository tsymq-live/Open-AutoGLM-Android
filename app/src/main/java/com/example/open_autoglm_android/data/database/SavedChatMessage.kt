package com.example.open_autoglm_android.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 保存的聊天消息
 */
@Entity(tableName = "chat_messages", foreignKeys = [
    androidx.room.ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = androidx.room.ForeignKey.CASCADE
    )
])
data class SavedChatMessage(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "USER" or "ASSISTANT"
    val content: String,
    val thinking: String? = null,
    val action: String? = null,
    val imagePath: String? = null, // 保存标记过动作的截图路径
    val timestamp: Long = System.currentTimeMillis(),
    // 性能指标和尺寸
    val screenshotMs: Long = 0,
    val networkMs: Long = 0,
    val executionMs: Long = 0,
    val totalMs: Long = 0,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val scaledWidth: Int = 0,
    val scaledHeight: Int = 0
)