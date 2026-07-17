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
import com.allyvera.screenshot.NsfwScores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DebugScreen() {
    val screenshots by DebugManager.screenshots.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Captured Screenshots (${screenshots.size})", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        if (screenshots.isEmpty()) {
            Text("No screenshots captured yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(screenshots) { item ->
                    Card {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = item.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))

                            // Full screenshot + model input preview
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Captured", style = MaterialTheme.typography.labelSmall)
                                    ScreenshotThumbnail(
                                        filePath = item.file.absolutePath,
                                        modifier = Modifier.fillMaxWidth().height(140.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                Column {
                                    Text("AI input (224×224)", style = MaterialTheme.typography.labelSmall)
                                    ScreenshotThumbnail(
                                        filePath = item.modelInputFile.absolutePath,
                                        modifier = Modifier.size(140.dp),
                                        contentScale = ContentScale.FillBounds
                                    )
                                }
                            }

                            // Patches (scrollable row)
                            if (item.patchFiles.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Patches (${item.patchFiles.size})", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(130.dp)
                                ) {
                                    items(item.patchFiles) { patchFile ->
                                        ScreenshotThumbnail(
                                            filePath = patchFile.absolutePath,
                                            modifier = Modifier.size(120.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }

                            // Performance stats (timing + battery)
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
                                        Text("Avg/patch: ", style = MaterialTheme.typography.bodySmall)
                                        val avg = if (item.perPatchTimesMs.isNotEmpty()) item.perPatchTimesMs.average() else 0.0
                                        Text("%.1f ms".format(avg), style = MaterialTheme.typography.bodySmall)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text("Battery: ", style = MaterialTheme.typography.bodySmall)
                                        Text("%.3f mAh".format(item.estimatedBatteryMah), style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (item.perPatchTimesMs.isNotEmpty()) {
                                        Text(
                                            text = "Per-patch (ms): ${item.perPatchTimesMs.joinToString(", ") { "%.1f".format(it) }}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp)
                                        )
                                    }
                                }
                            }

                            // ----- NSFW SCORES (with dominant category) -----
                            Spacer(modifier = Modifier.height(8.dp))
                            item.scores?.let { scores ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Dominant: ", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        text = scores.dominantCategory,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (scores.dominantCategory) {
                                            "hentai", "porn" -> Color.Red
                                            "sexy" -> Color(0xFFFF9800) // orange
                                            "neutral" -> Color(0xFF4CAF50) // green
                                            else -> Color.Gray
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                NsfwScoresView(scores)
                            } ?: Text("No NSFW scores", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NsfwScoresView(scores: NsfwScores) {
    val items = listOf(
        "drawings" to scores.drawings,
        "hentai" to scores.hentai,
        "neutral" to scores.neutral,
        "porn" to scores.porn,
        "sexy" to scores.sexy
    )

    items.forEach { (label, score) ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(72.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { score },
                modifier = Modifier.weight(1f).height(8.dp),
                color = scoreColor(label, score),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${(score * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

fun scoreColor(category: String, score: Float): Color {
    return when {
        category == "neutral" && score > 0.5f -> Color(0xFF4CAF50) // green for safe
        category == "drawings" && score > 0.5f -> Color(0xFF2196F3) // blue for drawings
        (category == "hentai" || category == "porn") && score > 0.3f -> Color(0xFFF44336) // red for NSFW
        category == "sexy" && score > 0.3f -> Color(0xFFFF9800) // orange for sexy
        else -> Color(0xFF9E9E9E) // gray default
    }
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
                inSampleSize = 4   // thumbnails
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