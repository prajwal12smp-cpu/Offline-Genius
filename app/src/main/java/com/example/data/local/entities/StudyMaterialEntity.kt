package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "study_materials",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("subjectId")]
)
data class StudyMaterialEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val subjectId: Int,
    val title: String,
    val fileName: String,
    val fileType: String = "PDF", // "PDF", "DOCUMENT", "TEXTBOOK_CHAPTER"
    val rawText: String,
    val chunkCount: Int = 0,
    val totalWords: Int = 0,
    val fileSize: Long = 0L,
    val localFilePath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
