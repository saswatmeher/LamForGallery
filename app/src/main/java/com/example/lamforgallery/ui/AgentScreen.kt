package com.example.lamforgallery.ui

import android.content.IntentSender
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * This file now contains our entire Chat UI, decoupled from MainActivity.
 * NEW: It now includes the ModalBottomSheet for photo selection.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // --- Bottom Sheet State ---
    // We use a separate state for the sheet to show/hide it
    val sheetState = rememberModalBottomSheetState()

    // This is the "controller" for the sheet.
    // When the VM state `isSelectionSheetOpen` changes to true, we `show()` the sheet.
    // When the user dismisses it, `onDismissRequest` tells the VM to set it back to false.
    if (uiState.isSelectionSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeSelectionSheet() },
            sheetState = sheetState
        ) {
            // This is the content *inside* the sheet
            SelectionBottomSheet(
                uris = uiState.selectionSheetUris,
                onCancel = {
                    coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                        viewModel.closeSelectionSheet()
                    }
                },
                onConfirm = { selection ->
                    coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                        viewModel.confirmSelection(selection)
                    }
                }
            )
        }
    }
    // --- End Bottom Sheet ---


    // This effect will watch for a new RequiresPermission state
    LaunchedEffect(uiState.currentStatus) {
        val status = uiState.currentStatus
        if (status is AgentStatus.RequiresPermission) {
            Log.d("AgentScreen", "Detected RequiresPermission state: ${status.type}")
            onLaunchPermissionRequest(status.intentSender, status.type)
        }
    }

    // This effect will auto-scroll to the bottom when a new message is added
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    Scaffold(
        bottomBar = {
            ChatInputBar(
                status = uiState.currentStatus,
                selectionCount = uiState.selectedImageUris.size,
                onSend = { inputText ->
                    viewModel.sendUserInput(inputText)
                }
            )
        }
    ) { paddingValues ->
        // This is our main chat content area
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply padding from the Scaffold
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Bottom // Stick to bottom
        ) {
            // Add a spacer to push content up when list is short
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Render each chat message
            items(uiState.messages, key = { it.id }) { message ->
                ChatMessageItem(
                    message = message,
                    isSelected = { uri -> uiState.selectedImageUris.contains(uri) },
                    onToggleSelection = { uri -> viewModel.toggleImageSelection(uri) },
                    // --- NEW: Pass the sheet-opening function ---
                    onOpenSelectionSheet = { uris ->
                        viewModel.openSelectionSheet(uris)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Add a final spacer for padding at the bottom
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
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
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val isEnabled = status is AgentStatus.Idle

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
                enabled = isEnabled
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    onSend(inputText)
                    inputText = ""
                },
                // --- UPDATE ENABLED LOGIC ---
                // Can send if text OR selection is present
                enabled = isEnabled && (inputText.isNotBlank() || selectionCount > 0),
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