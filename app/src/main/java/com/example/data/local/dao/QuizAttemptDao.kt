package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.QuizAttemptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuizAttemptDao {
    @Query("SELECT * FROM quiz_attempts WHERE studentId = :studentId ORDER BY completedAt DESC")
    fun getAttemptsForStudentFlow(studentId: Int): Flow<List<QuizAttemptEntity>>

    @Query("SELECT * FROM quiz_attempts ORDER BY completedAt DESC")
    fun getAllAttemptsFlow(): Flow<List<QuizAttemptEntity>>

    @Query("SELECT * FROM quiz_attempts WHERE quizId = :quizId ORDER BY completedAt DESC")
    fun getAttemptsForQuizFlow(quizId: Int): Flow<List<QuizAttemptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttempt(attempt: QuizAttemptEntity): Long

    @Query("SELECT AVG(percentage) FROM quiz_attempts WHERE studentId = :studentId")
    fun getAverageScoreForStudentFlow(studentId: Int): Flow<Double?>

    @Query("SELECT COUNT(*) FROM quiz_attempts")
    fun getTotalAttemptsCountFlow(): Flow<Int>
}
