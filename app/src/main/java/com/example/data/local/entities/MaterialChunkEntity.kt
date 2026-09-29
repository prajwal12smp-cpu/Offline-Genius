package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "material_chunks",
    foreignKeys = [
        ForeignKey(
            entity = StudyMaterialEntity::class,
            parentColumns = ["id"],
            childColumns = ["materialId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("materialId"),
        Index("subjectId")
    ]
)
data class MaterialChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val materialId: Int,
    val subjectId: Int,
    val chunkIndex: Int,
    val content: String,
    val tokenCount: Int,
    val termsFrequencyJson: String = "{}", // JSON string of term -> frequency for BM25/TF-IDF
    val isIndexOrSyllabus: Boolean = false
)
