package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "quiz_attempts",
    foreignKeys = [
        ForeignKey(
            entity = QuizEntity::class,
            parentColumns = ["id"],
            childColumns = ["quizId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["studentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("quizId"),
        Index("studentId")
    ]
)
data class QuizAttemptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val quizId: Int,
    val studentId: Int,
    val studentName: String,
    val quizTitle: String,
    val subjectName: String,
    val score: Int,
    val totalQuestions: Int,
    val percentage: Int,
    val timeSpentSeconds: Int = 0,
    val answersJson: String = "{}", // Map of questionId -> selectedOptionIndex
    val completedAt: Long = System.currentTimeMillis()
)
