package com.example.ui.screens.student

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.ChatMessageEntity
import com.example.data.local.entities.SubjectEntity
import com.example.ui.components.CitationList
import com.example.ui.components.MarkdownText
import com.example.ui.components.OfflineStatusBadge
import com.example.ui.components.ThinkingIndicator
import com.example.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentChatScreen(
    chatViewModel: ChatViewModel,
    availableSubjects: List<SubjectEntity>,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by chatViewModel.uiState.collectAsState()
    val messages by chatViewModel.messages.collectAsState()
    val modelStatus by chatViewModel.modelStatus.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var showSubjectPicker by remember { mutableStateOf(false) }

    // Auto-scroll on new messages or stream
    LaunchedEffect(messages.size, uiState.streamedResponse, uiState.isThinking) {
        if (messages.isNotEmpty() || uiState.isThinking || uiState.isGenerating) {
            val count = messages.size + (if (uiState.isThinking || uiState.isGenerating) 1 else 0)
            if (count > 0) {
                listState.animateScrollToItem(count - 1)
            }
        }
    }

    // Default subject binding if none selected
    LaunchedEffect(availableSubjects) {
        if (uiState.currentSubject == null && availableSubjects.isNotEmpty()) {
            chatViewModel.bindSubject(availableSubjects.first())
        }
    }

    val suggestedQuestions = remember {
        listOf(
            "Summarize the main concepts in this chapter",
            "Explain the key principles from the curriculum",
            "Give me 3 practice checkpoint questions",
            "What are the most important definitions to remember?"
        )
    }

    if (availableSubjects.isEmpty()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI Tutor Chat", fontWeight = FontWeight.Bold) },
                    actions = { OfflineStatusBadge(modifier = Modifier.padding(end = 12.dp)) },
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
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No subjects available yet",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Ask your teacher to add a course subject first. Once created, you can chat with the on-device AI tutor grounded in your curriculum.",
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showSubjectPicker = true }
                        ) {
                            Text(
                                text = uiState.currentSubject?.name ?: "Select Subject",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Switch subject",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Gemma 3 1B • On-Device RAG Grounded",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { chatViewModel.toggleRagDebugMode() },
                        modifier = Modifier.testTag("btn_toggle_rag_debug")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Toggle RAG Debug",
                            tint = if (uiState.ragDebugMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onOpenHistory,
                        modifier = Modifier.testTag("btn_chat_history")
                    ) {
                        Icon(Icons.Default.History, contentDescription = "Past chats")
                    }
                    IconButton(
                        onClick = { chatViewModel.startNewConversation() },
                        modifier = Modifier.testTag("btn_new_chat")
                    ) {
                        Icon(Icons.Default.AddComment, contentDescription = "New conversation")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // RAG Debug Mode Active Banner
            if (uiState.ragDebugMode) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RAG Debug ON • Filtered strictly by subject: ${uiState.currentSubject?.name ?: "All"}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Tap bug icon to hide",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = modelStatus.displayLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                color = when (modelStatus) {
                                    is com.example.ai.llm.ModelStatus.Ready -> Color(0xFF16A34A)
                                    is com.example.ai.llm.ModelStatus.Loading -> MaterialTheme.colorScheme.primary
                                    is com.example.ai.llm.ModelStatus.NotInstalled -> Color(0xFFEA580C)
                                    is com.example.ai.llm.ModelStatus.Failed -> MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.testTag("txt_debug_model_status")
                            )
                            Text(
                                text = "Answer Source: ${uiState.lastGenerationPath}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("txt_debug_answer_source")
                            )
                        }
                    }
                }
            }

            // Model Status & Offline banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Chip: "Model: Not installed / Loading / Ready / Failed: ..."
                Surface(
                    color = when (modelStatus) {
                        is com.example.ai.llm.ModelStatus.Ready -> Color(0xFF16A34A).copy(alpha = 0.15f)
                        is com.example.ai.llm.ModelStatus.Loading -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        is com.example.ai.llm.ModelStatus.NotInstalled -> Color(0xFFEA580C).copy(alpha = 0.15f)
                        is com.example.ai.llm.ModelStatus.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when (modelStatus) {
                            is com.example.ai.llm.ModelStatus.Ready -> Color(0xFF16A34A)
                            is com.example.ai.llm.ModelStatus.Loading -> MaterialTheme.colorScheme.primary
                            is com.example.ai.llm.ModelStatus.NotInstalled -> Color(0xFFEA580C)
                            is com.example.ai.llm.ModelStatus.Failed -> MaterialTheme.colorScheme.error
                        }.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.testTag("chip_model_status")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (modelStatus is com.example.ai.llm.ModelStatus.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(
                                imageVector = when (modelStatus) {
                                    is com.example.ai.llm.ModelStatus.Ready -> Icons.Default.CheckCircle
                                    is com.example.ai.llm.ModelStatus.NotInstalled -> Icons.Default.Warning
                                    is com.example.ai.llm.ModelStatus.Failed -> Icons.Default.Error
                                    else -> Icons.Default.Info
                                },
                                contentDescription = null,
                                tint = when (modelStatus) {
                                    is com.example.ai.llm.ModelStatus.Ready -> Color(0xFF16A34A)
                                    is com.example.ai.llm.ModelStatus.NotInstalled -> Color(0xFFEA580C)
                                    is com.example.ai.llm.ModelStatus.Failed -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = modelStatus.displayLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = when (modelStatus) {
                                is com.example.ai.llm.ModelStatus.Ready -> Color(0xFF16A34A)
                                is com.example.ai.llm.ModelStatus.Loading -> MaterialTheme.colorScheme.primary
                                is com.example.ai.llm.ModelStatus.NotInstalled -> Color(0xFFEA580C)
                                is com.example.ai.llm.ModelStatus.Failed -> MaterialTheme.colorScheme.error
                            },
                            maxLines = 1
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = uiState.currentSubject?.code ?: "Offline",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Not Installed Banner
            if (modelStatus is com.example.ai.llm.ModelStatus.NotInstalled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEA580C).copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEA580C).copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("banner_model_not_installed")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFEA580C),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI model not installed - import it in Settings",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Answers are currently synthesized from source excerpts. Import Gemma 3 1B to enable real model inference.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (messages.isEmpty() && !uiState.isThinking && !uiState.isGenerating) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Ask your On-Device AI Tutor",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "All answers are strictly retrieved & synthesized from the ${uiState.currentSubject?.name ?: "course"} curriculum using Gemma 3 1B.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        subjectName = uiState.currentSubject?.name,
                        debugMode = uiState.ragDebugMode
                    )
                }

                // Thinking / Retrieving State
                if (uiState.isThinking) {
                    item {
                        ThinkingIndicator(stepText = uiState.thinkingStep)
                    }
                }

                // Active Streaming Response
                if (uiState.isGenerating) {
                    item {
                        GeneratingBubble(
                            streamedText = uiState.streamedResponse,
                            sources = uiState.activeRetrievedSources,
                            subjectName = uiState.currentSubject?.name,
                            isFallback = uiState.isFallbackGenerating,
                            generationPath = uiState.currentGenerationPath
                        )
                    }
                }

                // Error alert if any
                uiState.errorMessage?.let { error ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            // Quick Questions Chips
            if (suggestedQuestions.isNotEmpty() && !uiState.isThinking && !uiState.isGenerating) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestedQuestions.forEach { prompt ->
                        SuggestionChip(
                            onClick = {
                                inputText = prompt
                                chatViewModel.sendQuestion(prompt)
                                inputText = ""
                            },
                            label = {
                                Text(
                                    text = prompt,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            icon = {
                                Icon(
                                    Icons.Default.Lightbulb,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Input Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Ask a question about the curriculum...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_chat_message")
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val text = inputText
                                inputText = ""
                                chatViewModel.sendQuestion(text)
                            }
                        },
                        enabled = inputText.isNotBlank() && !uiState.isThinking && !uiState.isGenerating,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputText.isNotBlank() && !uiState.isThinking && !uiState.isGenerating)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                            .testTag("btn_send_chat")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send question",
                            tint = if (inputText.isNotBlank() && !uiState.isThinking && !uiState.isGenerating)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Subject switcher dialog
    if (showSubjectPicker) {
        AlertDialog(
            onDismissRequest = { showSubjectPicker = false },
            title = { Text("Switch Course Subject") },
            text = {
                Column {
                    availableSubjects.forEach { sub ->
                        ListItem(
                            headlineContent = { Text(sub.name, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text(sub.code) },
                            leadingContent = {
                                Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier
                                .clickable {
                                    chatViewModel.bindSubject(sub)
                                    showSubjectPicker = false
                                }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubjectPicker = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun ChatBubble(
    message: ChatMessageEntity,
    subjectName: String? = null,
    debugMode: Boolean = false
) {
    val isUser = message.role == "user"

    val parsedSources = remember(message.retrievedSourcesJson) {
        val list = mutableListOf<com.example.ai.rag.RetrievedContextChunk>()
        try {
            val jsonStr = message.retrievedSourcesJson
            if (!jsonStr.isNullOrBlank() && jsonStr != "[]") {
                val arr = org.json.JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val matchedList = mutableListOf<String>()
                    val termsArr = obj.optJSONArray("matchedTerms")
                    if (termsArr != null) {
                        for (t in 0 until termsArr.length()) {
                            matchedList.add(termsArr.getString(t))
                        }
                    }
                    list.add(
                        com.example.ai.rag.RetrievedContextChunk(
                            chunkId = obj.optInt("chunkId", i + 1),
                            materialId = obj.optInt("materialId", 1),
                            materialTitle = obj.optString("title", "Curriculum Document"),
                            chunkIndex = obj.optInt("section", 1) - 1,
                            text = obj.optString("text", "Curriculum content grounded for this subject."),
                            score = obj.optDouble("score", 1.0),
                            matchedTerms = matchedList
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        list
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            border = if (!isUser) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)) else null,
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (!isUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (message.isFallback) "Offline Genius • Fallback Excerpts" else "Offline Genius • Gemma 3 1B",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (message.isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        if (debugMode) {
                            Surface(
                                color = if (message.isFallback) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (message.isFallback) "FALLBACK" else "LLM",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    color = if (message.isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // If fallback, display notice with specific reason
                if (!isUser && message.isFallback) {
                    val specificReason = when {
                        message.generationPath.contains("not installed", ignoreCase = true) -> "AI model not installed - import it in Settings. Showing source excerpts."
                        message.generationPath.contains("failed", ignoreCase = true) -> message.generationPath.removePrefix("Fallback (").removeSuffix(")")
                        message.generationPath.contains("loading", ignoreCase = true) -> "AI model is loading - showing source excerpts."
                        else -> "AI model unavailable - showing source excerpts instead."
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = specificReason,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (isUser) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    MarkdownText(
                        content = message.content,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (!isUser && parsedSources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    CitationList(
                        sources = parsedSources,
                        subjectName = subjectName,
                        initiallyExpanded = false
                    )
                } else if (!isUser && debugMode && !message.content.startsWith("Thinking")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "ℹ️ No curriculum sources matched in '${subjectName ?: "subject"}' for this question.",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun GeneratingBubble(
    streamedText: String,
    sources: List<com.example.ai.rag.RetrievedContextChunk>,
    subjectName: String? = null,
    isFallback: Boolean = false,
    generationPath: String = "LLM"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isFallback) Icons.Default.Info else Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, (if (isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.5f)),
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isFallback) "Synthesizing fallback..." else "Generating response...",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = if (isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Surface(
                        color = (if (isFallback) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer).copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isFallback) "FALLBACK" else "LLM",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = if (isFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                if (isFallback) {
                    val specificReason = when {
                        generationPath.contains("not installed", ignoreCase = true) -> "AI model not installed - import it in Settings. Showing source excerpts."
                        generationPath.contains("failed", ignoreCase = true) -> generationPath.removePrefix("Fallback (").removeSuffix(")")
                        generationPath.contains("loading", ignoreCase = true) -> "AI model is loading - showing source excerpts."
                        else -> "AI model unavailable - showing source excerpts instead."
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = specificReason,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                MarkdownText(
                    content = streamedText + " ▌",
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (sources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    CitationList(
                        sources = sources,
                        subjectName = subjectName,
                        initiallyExpanded = false
                    )
                }
            }
        }
    }
}
