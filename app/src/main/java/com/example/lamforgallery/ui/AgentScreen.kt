package com.example.lamforgallery.ui

import android.content.IntentSender
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Enhanced Agent Screen with split layout:
 * - Top: Image gallery with selection support
 * - Bottom: Chat interface with human-in-the-loop prompts
 */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Watch for permission requests
    LaunchedEffect(uiState.currentStatus) {
        val status = uiState.currentStatus
        if (status is AgentStatus.RequiresPermission) {
            Log.d("AgentScreen", "Detected RequiresPermission state: ${status.type}")
            onLaunchPermissionRequest(status.intentSender, status.type)
        }
    }

    // Auto-scroll chat to bottom on new messages
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Section: Image Gallery (40% of screen)
        ImageGallerySection(
            images = uiState.displayedImages,
            selectedImages = uiState.selectedImageUris,
            isSelectionMode = uiState.isInSelectionMode,
            onImageClick = { uri ->
                if (uiState.isInSelectionMode) {
                    viewModel.toggleImageSelection(uri)
                }
            },
            onImageLongClick = { uri ->
                if (!uiState.isInSelectionMode && uiState.displayedImages.isNotEmpty()) {
                    viewModel.enterSelectionMode(uri)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f)
        )

        Divider()

        // Bottom Section: Chat Interface (60% of screen)
        ChatSection(
            messages = uiState.messages,
            currentStatus = uiState.currentStatus,
            listState = listState,
            onSendMessage = { message -> viewModel.sendMessage(message) },
            onConfirmAction = { viewModel.confirmPendingAction() },
            onCancelAction = { viewModel.cancelPendingAction() },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f)
        )
    }
}

/**
 * Renders a single chat message with different styling
 * for USER, AGENT, and ERROR.
 * NEW: Now handles selection.
 */
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    isSelected: (String) -> Boolean,
    onToggleSelection: (String) -> Unit,
    onOpenSelectionSheet: (List<String>) -> Unit // <-- NEW
) {
    val horizontalAlignment = when (message.sender) {
        Sender.USER -> Alignment.End
        Sender.AGENT, Sender.ERROR -> Alignment.Start
    }

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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment
    ) {
        // --- MODIFIED: The chat bubble is now clickable if it's a prompt ---
        val bubbleModifier = if (message.hasSelectionPrompt) {
            Modifier
                .fillMaxWidth(0.8f)
                .background(backgroundColor, RoundedCornerShape(12.dp))
                .clickable {
                    // This is the new logic:
                    // When tapped, open the selection sheet with this message's URIs
                    onOpenSelectionSheet(message.imageUris ?: emptyList())
                }
        } else {
            Modifier
                .fillMaxWidth(0.8f)
                .background(backgroundColor, RoundedCornerShape(12.dp))
        }

        Box(modifier = bubbleModifier) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                )

                // --- MODIFIED IMAGE DISPLAY LOGIC ---
                // We only show the LazyRow carousel if it's *NOT* a selection prompt
                // (e.g., for a collage or filter result)
                if (message.imageUris != null && !message.hasSelectionPrompt) {
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(message.imageUris) { uri ->
                            val isSelected = isSelected(uri)
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleSelection(uri) }
                                    .then(
                                        if (isSelected) Modifier.border(
                                            BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                                            RoundedCornerShape(8.dp)
                                        ) else Modifier
                                    )
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Image from agent",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .align(Alignment.TopEnd)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // --- END MODIFIED IMAGE DISPLAY LOGIC ---
            }
        }
    }
}


// --- NEW COMPOSABLE FOR THE BOTTOM SHEET CONTENT ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBottomSheet(
    uris: List<String>,
    onCancel: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    // This sheet manages its *own* temporary selection
    var localSelection by remember { mutableStateOf(emptySet<String>()) }

    val selectionCount = localSelection.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Photos") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { onConfirm(localSelection) },
                    enabled = selectionCount > 0
                ) {
                    Text("Confirm ($selectionCount)")
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(uris, key = { it }) { uri ->
                SelectablePhotoItem(
                    uri = uri,
                    isSelected = localSelection.contains(uri),
                    onToggle = {
                        localSelection = localSelection.toMutableSet().apply {
                            if (contains(uri)) remove(uri) else add(uri)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SelectablePhotoItem(
    uri: String,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .then(
                if (isSelected) Modifier.border(
                    BorderStroke(4.dp, MaterialTheme.colorScheme.primary),
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Selectable photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// --- END NEW COMPOSABLES ---


/**
 * The bottom bar with the text field and send button.
 * NEW: Shows selection count.
 */
@Composable
fun ChatInputBar(
    status: AgentStatus,
    selectionCount: Int,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var showApiKeyDialog by remember { mutableStateOf(false) }  // Don't show automatically - API key loaded from file
    val isEnabled = status is AgentStatus.Idle

    // API Key Configuration Dialog
    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("Gemini API Key") },
            text = {
                Column {
                    Text("Please enter your Gemini API key to use the AI agent.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showApiKeyDialog = false },
                    enabled = apiKey.isNotEmpty()
                ) {
                    Text("OK")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // --- STATUS INDICATOR ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            when (status) {
                is AgentStatus.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is AgentStatus.RequiresPermission -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is AgentStatus.Idle -> {
                    if (selectionCount > 0) {
                        // --- SHOW SELECTION COUNT ---
                        Text(
                            "$selectionCount image(s) selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        // Show API key status
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showApiKeyDialog = true }
                        ) {
                            Text(
                                text = if (apiKey.isEmpty()) "⚠️ API Key not set" else "✓ API Key configured",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (apiKey.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        // --- END STATUS ---

        Spacer(modifier = Modifier.height(8.dp))

        // --- INPUT ROW ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Your command...") },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                singleLine = true,
                enabled = isEnabled && apiKey.isNotEmpty()
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    onSend(inputText)
                    inputText = ""
                },
                // --- UPDATE ENABLED LOGIC ---
                // Can send if text OR selection is present AND API key is set
                enabled = isEnabled && apiKey.isNotEmpty() && (inputText.isNotBlank() || selectionCount > 0),
                modifier = Modifier.height(56.dp)
            ) {
                Text("Send")
            }
        }
    }
}


@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Permission Required",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This app needs permission to read your photos to work.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}

// ===== NEW COMPOSABLES FOR SPLIT LAYOUT =====

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGallerySection(
    images: List<String>,
    selectedImages: Set<String>,
    isSelectionMode: Boolean,
    onImageClick: (String) -> Unit,
    onImageLongClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (images.isEmpty()) {
            Text(
                text = "Images will appear here",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(images, key = { it }) { uri ->
                    SelectableImageItem(
                        uri = uri,
                        isSelected = selectedImages.contains(uri),
                        isSelectionMode = isSelectionMode,
                        onImageClick = { onImageClick(uri) },
                        onImageLongClick = { onImageLongClick(uri) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableImageItem(
    uri: String,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onImageClick: () -> Unit,
    onImageLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(
                onClick = onImageClick,
                onLongClick = onImageLongClick
            )
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Gallery Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Selection indicator
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isSelected) Color.Black.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
            )
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Circle,
                contentDescription = if (isSelected) "Selected" else "Not Selected",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSection(
    messages: List<ChatMessage>,
    currentStatus: AgentStatus,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSendMessage: (String) -> Unit,
    onConfirmAction: () -> Unit,
    onCancelAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Chat messages area
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

            // Human-in-the-loop confirmation prompt
            item {
                val uiState by remember { mutableStateOf(currentStatus) }
                if (uiState is AgentStatus.Loading) {
                    val loadingStatus = uiState as AgentStatus.Loading
                    if (loadingStatus.message.contains("confirm", ignoreCase = true)) {
                        HumanInTheLoopPrompt(
                            message = loadingStatus.message,
                            onConfirm = onConfirmAction,
                            onCancel = onCancelAction
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        // Input bar at the bottom
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
fun HumanInTheLoopPrompt(
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onConfirm) {
                    Text("Confirm")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputField(
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Ask me anything about your photos...") },
            modifier = Modifier.weight(1f),
            maxLines = 3
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                if (text.isNotBlank()) {
                    onSendMessage(text)
                    text = ""
                }
            },
            enabled = text.isNotBlank()
        ) {
            Text("Send")
        }
    }
}
