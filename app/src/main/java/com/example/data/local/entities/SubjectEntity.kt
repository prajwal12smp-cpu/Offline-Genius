package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val code: String,
    val description: String,
    val iconName: String = "School",
    val colorHex: String = "#0284C7",
    val teacherName: String = "Classroom Teacher",
    val createdAt: Long = System.currentTimeMillis()
)
