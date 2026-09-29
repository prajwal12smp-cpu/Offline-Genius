package com.example.ui.screens.common

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.StudyMaterialEntity
import com.example.util.PdfStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    material: StudyMaterialEntity,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }

    // Load and render PDF pages asynchronously on-device
    LaunchedEffect(material.id) {
        isLoading = true
        errorMessage = null
        try {
            val pdfFile = PdfStorageManager.ensureMaterialPdfFile(context, material)
            val renderResult = PdfStorageManager.renderPdfPages(pdfFile, scale = 1.8f)
            renderResult.onSuccess { renderedPages ->
                pages = renderedPages
                isLoading = false
            }.onFailure { err ->
                errorMessage = err.localizedMessage ?: "Failed to render PDF pages."
                isLoading = false
            }
        } catch (e: Exception) {
            errorMessage = e.localizedMessage ?: "Failed to load document file."
            isLoading = false
        }
    }

    val currentPageIndex by remember {
        derivedStateOf {
            if (pages.isEmpty()) 0
            else minOf(listState.firstVisibleItemIndex, pages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = material.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (pages.isNotEmpty()) {
                                "Page ${currentPageIndex + 1} of ${pages.size} • ${PdfStorageManager.formatFileSize(material.fileSize)}"
                            } else {
                                "${material.fileName} • ${PdfStorageManager.formatFileSize(material.fileSize)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("btn_pdf_back")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Zoom Out
                    IconButton(
                        onClick = { zoomScale = maxOf(0.8f, zoomScale - 0.2f) },
                        enabled = !isLoading && pages.isNotEmpty()
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out")
                    }

                    // Zoom In
                    IconButton(
                        onClick = { zoomScale = minOf(2.2f, zoomScale + 0.2f) },
                        enabled = !isLoading && pages.isNotEmpty()
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In")
                    }

                    // Download Button
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier.testTag("btn_pdf_download_top")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download to Device",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (pages.size > 1) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous Page
                        OutlinedButton(
                            onClick = {
                                if (currentPageIndex > 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(currentPageIndex - 1)
                                    }
                                }
                            },
                            enabled = currentPageIndex > 0,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.NavigateBefore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Prev")
                        }

                        // Page indicator badge
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Page ${currentPageIndex + 1} of ${pages.size}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }

                        // Next Page
                        OutlinedButton(
                            onClick = {
                                if (currentPageIndex < pages.size - 1) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(currentPageIndex + 1)
                                    }
                                }
                            },
                            enabled = currentPageIndex < pages.size - 1,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Next")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.NavigateNext, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Rendering PDF pages locally...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Could not display document",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                val pdfFile = PdfStorageManager.ensureMaterialPdfFile(context, material)
                                val renderRes = PdfStorageManager.renderPdfPages(pdfFile)
                                renderRes.onSuccess {
                                    pages = it
                                    isLoading = false
                                    errorMessage = null
                                }.onFailure {
                                    errorMessage = it.localizedMessage
                                    isLoading = false
                                }
                            }
                        }) {
                            Text("Regenerate & Retry")
                        }
                    }
                }

                pages.isEmpty() -> {
                    Text(
                        text = "No pages found in this document.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp)
                    ) {
                        itemsIndexed(pages) { index, bitmap ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = zoomScale
                                        scaleY = zoomScale
                                    }
                                    .testTag("pdf_page_$index")
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // Page header tag
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFF8FAFC))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "PAGE ${index + 1}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF64748B)
                                        )
                                    }

                                    // Rendered Page Image
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Document Page ${index + 1}",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .wrapContentHeight()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
