package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.StudyMaterialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyMaterialDao {
    @Query("SELECT * FROM study_materials WHERE subjectId = :subjectId ORDER BY createdAt DESC")
    fun getMaterialsBySubjectFlow(subjectId: Int): Flow<List<StudyMaterialEntity>>

    @Query("SELECT * FROM study_materials ORDER BY createdAt DESC")
    fun getAllMaterialsFlow(): Flow<List<StudyMaterialEntity>>

    @Query("SELECT * FROM study_materials")
    suspend fun getAllMaterialsList(): List<StudyMaterialEntity>

    @Query("SELECT * FROM study_materials WHERE id = :id LIMIT 1")
    suspend fun getMaterialById(id: Int): StudyMaterialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMaterial(material: StudyMaterialEntity): Long

    @androidx.room.Update
    suspend fun updateMaterial(material: StudyMaterialEntity)

    @Query("UPDATE study_materials SET title = :newTitle, fileName = :newFileName WHERE id = :id")
    suspend fun renameMaterial(id: Int, newTitle: String, newFileName: String)

    @Query("UPDATE study_materials SET localFilePath = :filePath, fileSize = :fileSize WHERE id = :id")
    suspend fun updateFilePathAndSize(id: Int, filePath: String, fileSize: Long)

    @Query("DELETE FROM study_materials WHERE id = :id")
    suspend fun deleteMaterialById(id: Int)

    @Query("SELECT COUNT(*) FROM study_materials WHERE subjectId = :subjectId")
    fun getMaterialCountForSubject(subjectId: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM study_materials")
    fun getTotalMaterialsCount(): Flow<Int>
}
