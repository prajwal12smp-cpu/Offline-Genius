package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.SubjectEntity
import com.example.data.repository.ClassroomRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SubjectViewModel(private val classroomRepository: ClassroomRepository) : ViewModel() {

    val subjects: StateFlow<List<SubjectEntity>> = classroomRepository.allSubjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSubject = MutableStateFlow<SubjectEntity?>(null)
    val selectedSubject: StateFlow<SubjectEntity?> = _selectedSubject.asStateFlow()

    fun selectSubject(subject: SubjectEntity) {
        _selectedSubject.value = subject
    }

    fun selectSubjectById(subjectId: Int) {
        viewModelScope.launch {
            val s = classroomRepository.getSubjectById(subjectId)
            _selectedSubject.value = s
        }
    }

    fun createSubject(name: String, code: String, description: String, colorHex: String, teacherName: String) {
        viewModelScope.launch {
            val entity = SubjectEntity(
                name = name.trim(),
                code = code.trim().uppercase(),
                description = description.trim(),
                colorHex = colorHex,
                teacherName = teacherName.ifBlank { "Classroom Teacher" }
            )
            classroomRepository.createSubject(entity)
        }
    }

    fun deleteSubject(subjectId: Int) {
        viewModelScope.launch {
            classroomRepository.deleteSubject(subjectId)
            if (_selectedSubject.value?.id == subjectId) {
                _selectedSubject.value = null
            }
        }
    }
}
