package com.example.ui.screens.student

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.UserEntity
import com.example.ui.components.OfflineStatusBadge
import com.example.ui.components.StatCard
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.PrimaryBlueLight
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodels.QuizViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentProgressScreen(
    currentUser: UserEntity?,
    quizViewModel: QuizViewModel,
    modifier: Modifier = Modifier
) {
    val attempts by quizViewModel.studentAttempts.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()) }

    val totalAttempts = attempts.size
    val averageScore = if (attempts.isNotEmpty()) {
        attempts.map { it.percentage }.average().toInt()
    } else 0
    val perfectScores = attempts.count { it.percentage == 100 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Learning Performance",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Offline Quiz Progress & Mastery",
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            // Stats Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        title = "Avg. Score",
                        value = "$averageScore%",
                        subtitle = if (averageScore >= 70) "Mastery Level" else "Progressing",
                        icon = Icons.Default.TrendingUp,
                        iconColor = SuccessGreen,
                        modifier = Modifier.weight(1f)
                    )

                    StatCard(
                        title = "Completed",
                        value = "$totalAttempts",
                        subtitle = "Checkpoints",
                        icon = Icons.Default.CheckCircleOutline,
                        iconColor = PrimaryBlueLight,
                        modifier = Modifier.weight(1f)
                    )

                    StatCard(
                        title = "Perfect",
                        value = "$perfectScores",
                        subtitle = "100% Score",
                        icon = Icons.Default.EmojiEvents,
                        iconColor = WarningOrange,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Score History Chart (Canvas visualization)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth().testTag("chart_quiz_progress")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Score Trend Over Time",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Recent ${attempts.take(6).size} attempts",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (attempts.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Take quizzes to generate your progress timeline!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            // Reverse so oldest to newest left to right
                            val chartData = attempts.take(8).reversed()
                            val primaryColor = MaterialTheme.colorScheme.primary
                            val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                val w = size.width
                                val h = size.height
                                val maxScore = 100f
                                val spacing = if (chartData.size > 1) w / (chartData.size - 1) else w / 2

                                // Draw horizontal grid lines (0%, 50%, 100%)
                                drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
                                drawLine(gridColor, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)
                                drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1f)

                                val points = chartData.mapIndexed { idx, attempt ->
                                    val x = if (chartData.size == 1) w / 2 else idx * spacing
                                    val y = h - ((attempt.percentage.toFloat() / maxScore) * h)
                                    Offset(x, y)
                                }

                                // Draw line connecting points
                                if (points.size > 1) {
                                    val path = Path().apply {
                                        moveTo(points.first().x, points.first().y)
                                        for (i in 1 until points.size) {
                                            lineTo(points[i].x, points[i].y)
                                        }
                                    }
                                    drawPath(path, color = primaryColor, style = Stroke(width = 4.dp.toPx()))
                                }

                                // Draw circles on points
                                for (pt in points) {
                                    drawCircle(color = Color.White, radius = 6.dp.toPx(), center = pt)
                                    drawCircle(color = primaryColor, radius = 4.dp.toPx(), center = pt)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Earlier", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Latest Checkpoint", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Recent attempts list
            item {
                Text(
                    text = "QUIZ ATTEMPTS HISTORY (${attempts.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (attempts.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.AssignmentLate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No quiz attempts yet",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Complete checkpoint quizzes in the Quizzes tab to track your scores, performance, and curriculum mastery here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(attempts, key = { it.id }) { attempt ->
                val isHigh = attempt.percentage >= 70
                val badgeColor = if (isHigh) SuccessGreen else WarningOrange

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = badgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(50.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${attempt.percentage}%",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                                    color = badgeColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attempt.quizTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${attempt.subjectName} • ${attempt.score}/${attempt.totalQuestions} Correct",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = dateFormat.format(Date(attempt.completedAt)),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}
}
