package com.allyvera.ui.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allyvera.processing.ClassScores
import com.allyvera.processing.ContentSeverity
import com.allyvera.processing.NsfwResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DebugScreen() {
    val screenshots by DebugManager.screenshots.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Captured Screenshots (${screenshots.size})", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Classifier: Int8 (model_dynamic_range.tflite) on CPU/XNNPack",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (screenshots.isEmpty()) {
            Text("No screenshots captured yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(screenshots) { item ->
                    ScreenshotCard(item)
                }
            }
        }
    }
}

@Composable
private fun ScreenshotCard(item: DebugScreenshotItem) {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = item.name, style = MaterialTheme.typography.titleSmall)
            if (item.modelName.isNotEmpty()) {
                Text(
                    text = buildString {
                        append("Model: ${item.modelName}")
                        if (item.accelerator.isNotEmpty()) append("  ·  ${item.accelerator}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Text("Captured", style = MaterialTheme.typography.labelSmall)
            ScreenshotThumbnail(
                filePath = item.file.absolutePath,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Performance", style = MaterialTheme.typography.labelMedium)
                    Row {
                        Text("Total: ", style = MaterialTheme.typography.bodySmall)
                        Text("%.1f ms".format(item.totalTimeMs), style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Battery: ", style = MaterialTheme.typography.bodySmall)
                        Text("%.3f mAh".format(item.estimatedBatteryMah), style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.views.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.views.joinToString("  |  ") {
                                "%s %.0fms".format(it.label, it.timeMs)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp)
                        )
                    }
                }
            }

            if (item.views.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Views (full + center square + strips)",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(item.views) { view ->
                        ViewResultColumn(view)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            item.scores?.let { scores ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aggregate verdict: ", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = scores.classification.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = severityColor(scores.classification)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${scores.scores.topLabel} ${(scores.scores.topScore * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                ClassScoresView(scores.scores)
            } ?: Text("No scores", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ViewResultColumn(view: DebugViewResult) {
    Column(modifier = Modifier.width(148.dp)) {
        Text(view.label, style = MaterialTheme.typography.labelSmall)
        Text(
            "%.0f ms".format(view.timeMs),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ScreenshotThumbnail(
            filePath = view.file.absolutePath,
            modifier = Modifier.size(140.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            text = "${view.scores.topLabel} ${(view.scores.topScore * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = severityColor(
                when (view.scores.topLabel) {
                    "Hentai", "Pornography" -> ContentSeverity.EXPLICIT
                    "Enticing or Sensual" -> ContentSeverity.SEXY
                    else -> ContentSeverity.SAFE
                }
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        ClassScoresView(view.scores, compact = true)
    }
}

@Composable
fun ClassScoresView(scores: ClassScores, compact: Boolean = false) {
    val fontSize = if (compact) 9.sp else 12.sp
    val barHeight = if (compact) 5.dp else 8.dp
    val labelWidth = if (compact) 56.dp else 120.dp

    scores.asLabeledList().forEach { (label, score) ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (compact) shortLabel(label) else label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = fontSize),
                modifier = Modifier.width(labelWidth)
            )
            Spacer(modifier = Modifier.width(4.dp))
            LinearProgressIndicator(
                progress = { score },
                modifier = Modifier.weight(1f).height(barHeight),
                color = classScoreColor(label, score),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${(score * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = fontSize),
                modifier = Modifier.width(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(1.dp))
    }
}

@Composable
fun NsfwScoresView(scores: NsfwResult) {
    ClassScoresView(scores.scores)
}

private fun shortLabel(label: String): String = when (label) {
    "Anime Picture" -> "Anime"
    "Hentai" -> "Hentai"
    "Normal" -> "Normal"
    "Pornography" -> "Porn"
    "Enticing or Sensual" -> "Enticing"
    else -> label
}

fun severityColor(severity: ContentSeverity): Color = when (severity) {
    ContentSeverity.EXPLICIT -> Color(0xFFF44336)
    ContentSeverity.SEXY -> Color(0xFFFF9800)
    ContentSeverity.SAFE -> Color(0xFF4CAF50)
}

fun classScoreColor(category: String, score: Float): Color = when (category) {
    "Normal", "Anime Picture" -> Color(0xFF4CAF50)
    "Enticing or Sensual" -> if (score > 0.3f) Color(0xFFFF9800) else Color(0xFF9E9E9E)
    "Hentai", "Pornography" -> if (score > 0.3f) Color(0xFFF44336) else Color(0xFF9E9E9E)
    else -> Color(0xFF9E9E9E)
}

@Composable
fun ScreenshotThumbnail(
    filePath: String,
    modifier: Modifier,
    contentScale: ContentScale
) {
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 8
            }
            thumbnail = BitmapFactory.decodeFile(filePath, opts)
        }
    }

    DisposableEffect(filePath) {
        onDispose {
            thumbnail?.recycle()
            thumbnail = null
        }
    }

    thumbnail?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = filePath,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}
