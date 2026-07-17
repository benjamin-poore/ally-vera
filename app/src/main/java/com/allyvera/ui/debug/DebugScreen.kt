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
import com.allyvera.processing.NsfwClassification
import com.allyvera.processing.NsfwResult
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

                            // Full screenshot + the real 224x224 model input
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
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }

                            // Performance stats (timing + battery + two-stage)
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
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Whole-frame NSFW: %.2f  |  Tiled: %s".format(item.wholeFrameNsfw, item.tiled),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp)
                                    )
                                }
                            }

                            // Tiles (only shown when stage-2 fired)
                            if (item.tileFiles.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Tiles (2×2, max-aggregated)", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(120.dp)
                                ) {
                                    items(item.tileFiles) { tileFile ->
                                        val idx = item.tileFiles.indexOf(tileFile)
                                        Column {
                                            ScreenshotThumbnail(
                                                filePath = tileFile.absolutePath,
                                                modifier = Modifier.size(110.dp),
                                                contentScale = ContentScale.Crop
                                            )
                                            Text(
                                                "%.2f".format(item.tileScores.getOrElse(idx) { 0f }),
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp)
                                            )
                                        }
                                    }
                                }
                            }

                            // ----- NSFW RESULT -----
                            Spacer(modifier = Modifier.height(8.dp))
                            item.scores?.let { scores ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Verdict: ", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        text = scores.classification.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (scores.classification) {
                                            NsfwClassification.NSFW -> Color.Red
                                            NsfwClassification.QUESTIONABLE -> Color(0xFFFF9800) // orange
                                            NsfwClassification.SAFE -> Color(0xFF4CAF50) // green
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
fun NsfwScoresView(scores: NsfwResult) {
    val items = listOf(
        "safe" to scores.safe,
        "nsfw" to scores.nsfw
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

    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "isNsfw=${scores.isNsfw}",
        style = MaterialTheme.typography.bodySmall
    )
}

fun scoreColor(category: String, score: Float): Color {
    return when (category) {
        "safe" -> Color(0xFF4CAF50) // green for safe
        "nsfw" -> if (score > 0.3f) Color(0xFFF44336) else Color(0xFF9E9E9E) // red when high
        else -> Color(0xFF9E9E9E)
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
