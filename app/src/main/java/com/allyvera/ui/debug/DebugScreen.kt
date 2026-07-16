package com.allyvera.ui.debug

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DebugScreen(
    viewModel: DebugViewModel = viewModel()
) {
    val screenshots by viewModel.screenshots.collectAsState()

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
                            val bitmap = BitmapFactory.decodeFile(item.file.absolutePath)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = item.name,
                                    modifier = Modifier.size(100.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = item.name, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
                        }
                    }
                }
            }
        }
    }
}