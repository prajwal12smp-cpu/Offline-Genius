package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "users",
    indices = [Index(value = ["username"], unique = true)]
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val username: String,
    val passwordHash: String,
    val salt: String,
    val role: String, // "STUDENT" or "TEACHER"
    val fullName: String,
    val gradeOrDept: String = "",
    val avatarColorHex: String = "#0284C7",
    val createdAt: Long = System.currentTimeMillis()
)
