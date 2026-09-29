package com.example.ui.screens.teacher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.SubjectEntity
import com.example.ui.components.OfflineStatusBadge
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodels.DraftQuizQuestion
import com.example.ui.viewmodels.TeacherViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherQuizCreateScreen(
    teacherViewModel: TeacherViewModel,
    modifier: Modifier = Modifier
) {
    val subjects by teacherViewModel.allSubjects.collectAsState()

    var selectedSubjectId by remember { mutableStateOf(subjects.firstOrNull()?.id ?: 1) }
    var quizTitle by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var timeLimitMinutes by remember { mutableStateOf(10) }
    var difficulty by remember { mutableStateOf("Medium") }
    var showSubjectPicker by remember { mutableStateOf(false) }
    var successNotification by remember { mutableStateOf<String?>(null) }

    val questions = remember {
        mutableStateListOf(
            DraftQuizQuestion(
                questionText = "",
                optionA = "",
                optionB = "",
                optionC = "",
                optionD = "",
                correctOptionIndex = 0,
                explanation = ""
            )
        )
    }

    LaunchedEffect(subjects) {
        if (selectedSubjectId == 0 && subjects.isNotEmpty()) {
            selectedSubjectId = subjects.first().id
        }
    }

    val selectedSubject = subjects.find { it.id == selectedSubjectId }

    if (subjects.isEmpty()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Create Checkpoint Quiz",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Build multiple-choice questions for students",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        OfflineStatusBadge(modifier = Modifier.padding(end = 12.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.AddBox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No subjects available",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Please create a course subject in the 'Subjects' tab first before building multiple-choice quizzes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Create Checkpoint Quiz",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Build multiple-choice questions for students",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    OfflineStatusBadge(modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            questions.add(
                                DraftQuizQuestion(
                                    questionText = "",
                                    optionA = "",
                                    optionB = "",
                                    optionC = "",
                                    optionD = "",
                                    correctOptionIndex = 0,
                                    explanation = ""
                                )
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("btn_add_another_question")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Question")
                    }

                    Button(
                        onClick = {
                            val valid = questions.filter { it.questionText.isNotBlank() && it.optionA.isNotBlank() && it.optionB.isNotBlank() }
                            if (quizTitle.isNotBlank() && valid.isNotEmpty()) {
                                teacherViewModel.createQuiz(
                                    subjectId = selectedSubjectId,
                                    title = quizTitle,
                                    description = description,
                                    timeLimitMinutes = timeLimitMinutes,
                                    difficulty = difficulty,
                                    questions = valid,
                                    onSuccess = {
                                        successNotification = "Quiz '$quizTitle' published with ${valid.size} questions!"
                                        quizTitle = ""
                                        description = ""
                                        questions.clear()
                                        questions.add(DraftQuizQuestion())
                                    }
                                )
                            }
                        },
                        enabled = quizTitle.isNotBlank() && questions.any { it.questionText.isNotBlank() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("btn_publish_quiz")
                    ) {
                        Icon(Icons.Default.Publish, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save & Publish")
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            // Success notice
            successNotification?.let { msg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            // Quiz Config Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "QUIZ DETAILS",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Subject Picker Row
                        Surface(
                            onClick = { showSubjectPicker = true },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Assigned Subject", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = selectedSubject?.let { "${it.code} — ${it.name}" } ?: "Select Subject",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = quizTitle,
                            onValueChange = { quizTitle = it },
                            label = { Text("Quiz Title") },
                            placeholder = { Text("e.g. Unit 3 Exam: Thermodynamics") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("input_quiz_title")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description / Scope") },
                            placeholder = { Text("Covers heat transfer, entropy, and PV diagrams...") },
                            maxLines = 2,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("input_quiz_description")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Time limit
                            OutlinedTextField(
                                value = timeLimitMinutes.toString(),
                                onValueChange = {
                                    timeLimitMinutes = it.toIntOrNull() ?: 10
                                },
                                label = { Text("Time (Minutes)") },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            )

                            // Difficulty selector
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Difficulty", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("Easy", "Medium", "Hard").forEach { diff ->
                                        FilterChip(
                                            selected = difficulty == diff,
                                            onClick = { difficulty = diff },
                                            label = { Text(diff, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "QUESTIONS (${questions.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Question Cards
            itemsIndexed(questions) { index, draft ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Question ${index + 1}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (questions.size > 1) {
                                IconButton(onClick = { questions.removeAt(index) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Remove Question",
                                        tint = ErrorRed
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = draft.questionText,
                            onValueChange = { questions[index] = draft.copy(questionText = it) },
                            label = { Text("Question Prompt") },
                            placeholder = { Text("What is the definition of...") },
                            maxLines = 3,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("input_q_text_$index")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Options (Select the radio button for the correct answer):",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Option A
                        OptionInputField(
                            label = "A",
                            text = draft.optionA,
                            isCorrect = draft.correctOptionIndex == 0,
                            onTextChange = { questions[index] = draft.copy(optionA = it) },
                            onSelectCorrect = { questions[index] = draft.copy(correctOptionIndex = 0) }
                        )

                        // Option B
                        OptionInputField(
                            label = "B",
                            text = draft.optionB,
                            isCorrect = draft.correctOptionIndex == 1,
                            onTextChange = { questions[index] = draft.copy(optionB = it) },
                            onSelectCorrect = { questions[index] = draft.copy(correctOptionIndex = 1) }
                        )

                        // Option C
                        OptionInputField(
                            label = "C",
                            text = draft.optionC,
                            isCorrect = draft.correctOptionIndex == 2,
                            onTextChange = { questions[index] = draft.copy(optionC = it) },
                            onSelectCorrect = { questions[index] = draft.copy(correctOptionIndex = 2) }
                        )

                        // Option D
                        OptionInputField(
                            label = "D",
                            text = draft.optionD,
                            isCorrect = draft.correctOptionIndex == 3,
                            onTextChange = { questions[index] = draft.copy(optionD = it) },
                            onSelectCorrect = { questions[index] = draft.copy(correctOptionIndex = 3) }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = draft.explanation,
                            onValueChange = { questions[index] = draft.copy(explanation = it) },
                            label = { Text("Explanation (Shown after grading)") },
                            placeholder = { Text("Why this option is correct...") },
                            maxLines = 2,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    // Subject picker
    if (showSubjectPicker) {
        AlertDialog(
            onDismissRequest = { showSubjectPicker = false },
            title = { Text("Select Course Subject") },
            text = {
                Column {
                    subjects.forEach { s ->
                        ListItem(
                            headlineContent = { Text(s.name, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text(s.code) },
                            modifier = Modifier
                                .clickable {
                                    selectedSubjectId = s.id
                                    showSubjectPicker = false
                                }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubjectPicker = false }) { Text("Close") }
            }
        )
    }
}

@Composable
fun OptionInputField(
    label: String,
    text: String,
    isCorrect: Boolean,
    onTextChange: (String) -> Unit,
    onSelectCorrect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isCorrect,
            onClick = onSelectCorrect,
            colors = RadioButtonDefaults.colors(selectedColor = SuccessGreen)
        )
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Option $label") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
        )
    }
}
