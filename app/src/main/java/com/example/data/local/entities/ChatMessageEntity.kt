package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val conversationId: Int,
    val role: String, // "user" or "assistant"
    val content: String,
    val retrievedSourcesJson: String = "[]", // List of matched chunk excerpts and titles
    val isFallback: Boolean = false,
    val generationPath: String = "LLM",
    val timestamp: Long = System.currentTimeMillis()
)
