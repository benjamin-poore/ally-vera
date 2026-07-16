package com.allyvera.ui.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
                        Row(modifier = Modifier.padding(8.dp)) {
                            ScreenshotThumbnail(filePath = item.file.absolutePath)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = item.name, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenshotThumbnail(filePath: String) {
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 4   // small factor for thumbnail
            }
            thumbnail = BitmapFactory.decodeFile(filePath, opts)
        }
    }

    thumbnail?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = filePath,
            modifier = Modifier.size(100.dp),
            contentScale = ContentScale.Fit
        )
    }
}