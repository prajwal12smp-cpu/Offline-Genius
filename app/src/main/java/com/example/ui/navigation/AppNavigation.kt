package com.example.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entities.StudyMaterialEntity
import com.example.ui.components.RoleBadge
import com.example.ui.screens.auth.LoginRegisterScreen
import com.example.ui.screens.common.PdfViewerScreen
import com.example.ui.screens.student.*
import com.example.ui.screens.teacher.*
import com.example.ui.viewmodels.*

sealed class StudentNavDestination(val route: String, val title: String, val icon: ImageVector) {
    object Subjects : StudentNavDestination("student_subjects", "Subjects", Icons.Default.School)
    object Materials : StudentNavDestination("student_materials", "Materials", Icons.Default.MenuBook)
    object Chat : StudentNavDestination("student_chat", "AI Chat", Icons.Default.AutoAwesome)
    object Quizzes : StudentNavDestination("student_quizzes", "Quizzes", Icons.Default.Quiz)
    object Progress : StudentNavDestination("student_progress", "Progress", Icons.Default.TrendingUp)
}

sealed class TeacherNavDestination(val route: String, val title: String, val icon: ImageVector) {
    object Materials : TeacherNavDestination("teacher_materials", "Materials & RAG", Icons.Default.MenuBook)
    object Subjects : TeacherNavDestination("teacher_subjects", "Subjects", Icons.Default.School)
    object Quizzes : TeacherNavDestination("teacher_quizzes", "Create Quiz", Icons.Default.AddBox)
    object Activity : TeacherNavDestination("teacher_activity", "Activity", Icons.Default.Analytics)
    object Model : TeacherNavDestination("teacher_model", "Model Setup", Icons.Default.Memory)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModelFactory: AppViewModelFactory,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authViewModel: AuthViewModel = viewModel(factory = viewModelFactory)
    val subjectViewModel: SubjectViewModel = viewModel(factory = viewModelFactory)
    val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory)
    val quizViewModel: QuizViewModel = viewModel(factory = viewModelFactory)
    val teacherViewModel: TeacherViewModel = viewModel(factory = viewModelFactory)
    val studentMaterialViewModel: StudentMaterialViewModel = viewModel(factory = viewModelFactory)

    val currentUser by authViewModel.currentUser.collectAsState()
    val allSubjects by subjectViewModel.subjects.collectAsState()
    val selectedSubject by subjectViewModel.selectedSubject.collectAsState()

    var studentCurrentTab by remember { mutableStateOf<StudentNavDestination>(StudentNavDestination.Subjects) }
    var teacherCurrentTab by remember { mutableStateOf<TeacherNavDestination>(TeacherNavDestination.Materials) }
    var isViewingChatHistory by remember { mutableStateOf(false) }
    var viewingPdfMaterial by remember { mutableStateOf<StudyMaterialEntity?>(null) }

    // If no user is logged in, show local login/register screen
    if (currentUser == null) {
        LoginRegisterScreen(authViewModel = authViewModel, modifier = modifier)
        return
    }

    val user = currentUser!!
    val isTeacher = user.role.equals("TEACHER", ignoreCase = true)

    // Full screen PDF viewer overlay when user selects a document
    viewingPdfMaterial?.let { material ->
        PdfViewerScreen(
            material = material,
            onBack = { viewingPdfMaterial = null },
            onDownload = {
                if (isTeacher) {
                    teacherViewModel.downloadMaterial(context, material)
                } else {
                    studentMaterialViewModel.downloadMaterial(context, material)
                }
            },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isTeacher) Icons.Default.School else Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = user.fullName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                RoleBadge(role = user.role)
                            }
                            Text(
                                text = "100% Offline • Local RAG & Storage",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isTeacher) {
                            IconButton(
                                onClick = { teacherCurrentTab = TeacherNavDestination.Model },
                                modifier = Modifier.testTag("btn_top_settings")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Model Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = { authViewModel.logout() },
                            modifier = Modifier.testTag("btn_logout")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Logout",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTeacher) {
                    val teacherTabs = listOf(
                        TeacherNavDestination.Materials,
                        TeacherNavDestination.Subjects,
                        TeacherNavDestination.Quizzes,
                        TeacherNavDestination.Activity,
                        TeacherNavDestination.Model
                    )
                    teacherTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = teacherCurrentTab == tab,
                            onClick = { teacherCurrentTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title, fontSize = 10.sp, maxLines = 1) },
                            modifier = Modifier.testTag("nav_teacher_${tab.route}")
                        )
                    }
                } else {
                    val studentTabs = listOf(
                        StudentNavDestination.Subjects,
                        StudentNavDestination.Materials,
                        StudentNavDestination.Chat,
                        StudentNavDestination.Quizzes,
                        StudentNavDestination.Progress
                    )
                    studentTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = studentCurrentTab == tab,
                            onClick = {
                                isViewingChatHistory = false
                                studentCurrentTab = tab
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title, fontSize = 10.sp, maxLines = 1) },
                            modifier = Modifier.testTag("nav_student_${tab.route}")
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.navigationBars,
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (isTeacher) {
                when (teacherCurrentTab) {
                    TeacherNavDestination.Materials -> TeacherMaterialScreen(
                        teacherViewModel = teacherViewModel,
                        onOpenPdfViewer = { mat -> viewingPdfMaterial = mat }
                    )
                    TeacherNavDestination.Subjects -> TeacherSubjectScreen(
                        currentUser = user,
                        subjectViewModel = subjectViewModel
                    )
                    TeacherNavDestination.Quizzes -> TeacherQuizCreateScreen(
                        teacherViewModel = teacherViewModel
                    )
                    TeacherNavDestination.Activity -> TeacherActivityScreen(
                        teacherViewModel = teacherViewModel
                    )
                    TeacherNavDestination.Model -> TeacherModelScreen(
                        teacherViewModel = teacherViewModel
                    )
                }
            } else {
                if (isViewingChatHistory) {
                    BackHandler { isViewingChatHistory = false }
                    StudentChatHistoryScreen(
                        chatViewModel = chatViewModel,
                        onBack = { isViewingChatHistory = false }
                    )
                } else {
                    when (studentCurrentTab) {
                        StudentNavDestination.Subjects -> {
                            StudentSubjectScreen(
                                currentUser = user,
                                subjectViewModel = subjectViewModel,
                                onSelectSubjectForMaterials = { sub ->
                                    studentMaterialViewModel.selectSubject(sub.id)
                                    studentCurrentTab = StudentNavDestination.Materials
                                },
                                onSelectSubjectForChat = { sub ->
                                    chatViewModel.bindSubject(sub)
                                    studentCurrentTab = StudentNavDestination.Chat
                                },
                                onSelectSubjectForQuizzes = { sub ->
                                    subjectViewModel.selectSubject(sub)
                                    studentCurrentTab = StudentNavDestination.Quizzes
                                }
                            )
                        }
                        StudentNavDestination.Materials -> {
                            StudentMaterialsScreen(
                                materialViewModel = studentMaterialViewModel,
                                onOpenPdfViewer = { mat -> viewingPdfMaterial = mat }
                            )
                        }
                        StudentNavDestination.Chat -> {
                            StudentChatScreen(
                                chatViewModel = chatViewModel,
                                availableSubjects = allSubjects,
                                onOpenHistory = { isViewingChatHistory = true }
                            )
                        }
                        StudentNavDestination.Quizzes -> {
                            StudentQuizScreen(
                                quizViewModel = quizViewModel,
                                availableSubjects = allSubjects,
                                selectedSubject = selectedSubject,
                                onSelectSubject = { sub -> subjectViewModel.selectSubject(sub) }
                            )
                        }
                        StudentNavDestination.Progress -> {
                            StudentProgressScreen(
                                currentUser = user,
                                quizViewModel = quizViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}
