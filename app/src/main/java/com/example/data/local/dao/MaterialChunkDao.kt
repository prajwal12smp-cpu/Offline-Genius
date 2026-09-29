package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.MaterialChunkEntity

@Dao
interface MaterialChunkDao {
    @Query("SELECT * FROM material_chunks WHERE subjectId = :subjectId")
    suspend fun getChunksForSubject(subjectId: Int): List<MaterialChunkEntity>

    @Query("SELECT * FROM material_chunks WHERE materialId = :materialId ORDER BY chunkIndex ASC")
    suspend fun getChunksForMaterial(materialId: Int): List<MaterialChunkEntity>

    @Query("SELECT * FROM material_chunks")
    suspend fun getAllChunks(): List<MaterialChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<MaterialChunkEntity>)

    @Query("DELETE FROM material_chunks WHERE materialId = :materialId")
    suspend fun deleteChunksByMaterialId(materialId: Int)

    @Query("SELECT COUNT(*) FROM material_chunks WHERE subjectId = :subjectId")
    suspend fun getChunkCountForSubject(subjectId: Int): Int

    @Query("SELECT COUNT(*) FROM material_chunks")
    suspend fun getTotalChunkCount(): Int
}
