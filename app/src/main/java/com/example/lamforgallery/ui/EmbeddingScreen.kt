package com.example.lamforgallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The main UI for the "Generate Embeddings" screen.
 * This composable is stateless and simply displays the UiState
 * provided by the [EmbeddingViewModel].
 */
@Composable
fun EmbeddingScreen(
    viewModel: EmbeddingViewModel
) {
    // Observe the UI state from the ViewModel
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Title Text
        Text(
            text = "Image Indexing",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(16.dp))

        // Status Message
        Text(
            text = uiState.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))

        // Stats Text
        Text(
            text = "${uiState.indexedImages} / ${uiState.totalImages} images indexed",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(16.dp))

        // Progress Bar
        if (uiState.isIndexing) {
            LinearProgressIndicator(
                progress = { uiState.progress },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // Show a "full" progress bar if not indexing
            LinearProgressIndicator(
                progress = {
                    if (uiState.totalImages > 0) {
                        uiState.indexedImages.toFloat() / uiState.totalImages.toFloat()
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(24.dp))

        // Start Button
        Button(
            onClick = { viewModel.startIndexing() },
            // Disable the button while indexing is in progress
            enabled = !uiState.isIndexing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isIndexing) "Indexing..." else "Start Indexing New Images")
        }
    }
}