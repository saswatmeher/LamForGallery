package com.example.lamforgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Show image gallery if there are search results
        if (uiState.searchResults.isNotEmpty()) {
            ImageGallerySection(
                images = uiState.searchResults,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Takes top half
            )
            HorizontalDivider()
        }
        
        // Chat section
        ChatSection(
            messages = uiState.messages,
            currentStatus = uiState.currentStatus,
            listState = listState,
            onSendMessage = { message -> viewModel.sendMessage(message) },
            modifier = if (uiState.searchResults.isNotEmpty()) {
                Modifier
                    .fillMaxWidth()
                    .weight(1f) // Takes bottom half when images are shown
            } else {
                Modifier.fillMaxSize() // Takes full screen when no images
            }
        )
    }
}

@Composable
fun ChatSection(
    messages: List<ChatMessage>,
    currentStatus: AgentStatus,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            item { Spacer(modifier = Modifier.height(16.dp)) }

            items(messages, key = { it.id }) { message ->
                ChatBubble(message = message)
                Spacer(modifier = Modifier.height(12.dp))
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        ChatInputField(
            onSendMessage = onSendMessage,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.sender == Sender.USER) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = when (message.sender) {
        Sender.USER -> MaterialTheme.colorScheme.primaryContainer
        Sender.AGENT -> MaterialTheme.colorScheme.secondaryContainer
        Sender.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val textColor = when (message.sender) {
        Sender.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Sender.AGENT -> MaterialTheme.colorScheme.onSecondaryContainer
        Sender.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = backgroundColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }
    }
}

@Composable
fun ChatInputField(
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = { Text("Type a message...") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
            singleLine = false
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = {
                if (inputText.isNotBlank()) {
                    onSendMessage(inputText)
                    inputText = ""
                }
            },
            enabled = inputText.isNotBlank()
        ) {
            Text("Send")
        }
    }
}

@Composable
fun ImageGallerySection(
    images: List<String>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (images.isEmpty()) {
            Text(
                text = "No images to display",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header showing count
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${images.size} photos found",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                
                // Grid of images
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(images, key = { it }) { uri ->
                        PhotoGridItem(uri = uri)
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoGridItem(uri: String) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Search result photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
