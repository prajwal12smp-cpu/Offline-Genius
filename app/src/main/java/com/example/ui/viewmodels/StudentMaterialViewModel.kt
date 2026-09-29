package com.example.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.StudyMaterialEntity
import com.example.data.local.entities.SubjectEntity
import com.example.data.repository.ClassroomRepository
import com.example.util.PdfStorageManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class StudentMaterialViewModel(
    private val classroomRepository: ClassroomRepository
) : ViewModel() {

    val allSubjects: StateFlow<List<SubjectEntity>> = classroomRepository.allSubjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMaterials: StateFlow<List<StudyMaterialEntity>> = classroomRepository.getAllMaterials()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSubjectId = MutableStateFlow<Int?>(null)
    val selectedSubjectId: StateFlow<Int?> = _selectedSubjectId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _viewingMaterial = MutableStateFlow<StudyMaterialEntity?>(null)
    val viewingMaterial: StateFlow<StudyMaterialEntity?> = _viewingMaterial.asStateFlow()

    private val _downloadStatusMessage = MutableStateFlow<String?>(null)
    val downloadStatusMessage: StateFlow<String?> = _downloadStatusMessage.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    fun selectSubject(subjectId: Int?) {
        _selectedSubjectId.value = subjectId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun openMaterialViewer(material: StudyMaterialEntity) {
        _viewingMaterial.value = material
    }

    fun closeMaterialViewer() {
        _viewingMaterial.value = null
    }

    fun clearDownloadMessage() {
        _downloadStatusMessage.value = null
    }

    fun downloadMaterial(context: Context, material: StudyMaterialEntity) {
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadStatusMessage.value = null
            try {
                // Ensure PDF file exists in internal storage first
                val pdfFile = PdfStorageManager.ensureMaterialPdfFile(context, material)
                
                // If DB didn't have path or size recorded, update it
                if (material.localFilePath.isNullOrBlank() || material.fileSize <= 0L) {
                    classroomRepository.updateMaterialFile(material.id, pdfFile.absolutePath, pdfFile.length())
                }

                val result = PdfStorageManager.saveFileToPublicDownloads(context, pdfFile, material.fileName)
                result.onSuccess { msg ->
                    _downloadStatusMessage.value = msg
                }.onFailure { err ->
                    _downloadStatusMessage.value = "Download failed: ${err.message ?: "Unable to write to Downloads"}"
                }
            } catch (e: Exception) {
                _downloadStatusMessage.value = "Error: ${e.message ?: "Storage operation failed"}"
            } finally {
                _isDownloading.value = false
            }
        }
    }
}
