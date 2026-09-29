package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entities.QuizEntity
import com.example.data.local.entities.QuizQuestionEntity
import kotlinx.coroutines.flow.Flow

data class QuizWithQuestions(
    val quiz: QuizEntity,
    val questions: List<QuizQuestionEntity>
)

@Dao
interface QuizDao {
    @Query("SELECT * FROM quizzes WHERE subjectId = :subjectId ORDER BY createdAt DESC")
    fun getQuizzesBySubjectFlow(subjectId: Int): Flow<List<QuizEntity>>

    @Query("SELECT * FROM quizzes ORDER BY createdAt DESC")
    fun getAllQuizzesFlow(): Flow<List<QuizEntity>>

    @Query("SELECT * FROM quizzes WHERE id = :quizId LIMIT 1")
    suspend fun getQuizById(quizId: Int): QuizEntity?

    @Query("SELECT * FROM quiz_questions WHERE quizId = :quizId ORDER BY id ASC")
    suspend fun getQuestionsForQuiz(quizId: Int): List<QuizQuestionEntity>

    @Query("SELECT * FROM quiz_questions WHERE quizId = :quizId ORDER BY id ASC")
    fun getQuestionsForQuizFlow(quizId: Int): Flow<List<QuizQuestionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuiz(quiz: QuizEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<QuizQuestionEntity>)

    @Query("DELETE FROM quizzes WHERE id = :id")
    suspend fun deleteQuizById(id: Int)

    @Query("DELETE FROM quiz_questions WHERE quizId = :quizId")
    suspend fun deleteQuestionsByQuizId(quizId: Int)

    @Transaction
    suspend fun insertQuizWithQuestions(quiz: QuizEntity, questions: List<QuizQuestionEntity>): Long {
        val quizId = insertQuiz(quiz).toInt()
        val mappedQuestions = questions.map { it.copy(quizId = quizId) }
        insertQuestions(mappedQuestions)
        return quizId.toLong()
    }
}
