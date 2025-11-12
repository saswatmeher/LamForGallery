package com.example.lamforgallery.ui

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import com.example.lamforgallery.tools.GalleryTools
import com.example.lamforgallery.tools.GalleryToolSet
import com.example.lamforgallery.tools.SemanticSearchToolSet
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.TextEncoder
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File

// --- STATE DEFINITIONS ---

/**
 * Represents one message in the chat.
 * Includes optional image URIs for display.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val imageUris: List<String>? = null,
    val hasSelectionPrompt: Boolean = false
)

enum class Sender {
    USER, AGENT, ERROR
}

/**
 * Represents the current *status* of the agent
 */
sealed class AgentStatus {
    data class Loading(val message: String) : AgentStatus()
    data class RequiresPermission(
        val intentSender: IntentSender,
        val type: PermissionType,
        val message: String
    ) : AgentStatus()
    object Idle : AgentStatus()
}

/**
 * The single, combined UI state, including the user's selection.
 */
data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentStatus: AgentStatus = AgentStatus.Idle,
    val displayedImages: List<String> = emptyList(),
    val selectedImageUris: Set<String> = emptySet(),
    val isInSelectionMode: Boolean = false,
    val pendingAction: PendingAction? = null,
    val isSelectionSheetOpen: Boolean = false,
    val selectionSheetUris: List<String> = emptyList(),
    val apiKey: String = ""
)

data class PendingAction(
    val type: ActionType,
    val itemCount: Int,
    val message: String
)

enum class ActionType {
    DELETE, MOVE, OTHER
}

enum class PermissionType { DELETE, WRITE }

class AgentViewModel(
    private val application: Application,
    private val galleryTools: GalleryTools,
    private val gson: Gson,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val clipTokenizer: ClipTokenizer,
    private val textEncoder: TextEncoder
) : ViewModel() {

    private val TAG = "AgentViewModel"
    
    // Koog AI Agent
    private var agent: AIAgent<String, String>? = null
    
    // API key will be read from file
    private var currentApiKey: String = ""
    
    // State for permission flow
    private var pendingPermissionData: PendingPermissionData? = null

    private val _uiState = MutableStateFlow(AgentUiState(apiKey = currentApiKey))
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private val _galleryDidChange = MutableSharedFlow<Unit>()
    val galleryDidChange: SharedFlow<Unit> = _galleryDidChange.asSharedFlow()

    data class PendingPermissionData(
        val photoUris: List<String>,
        val permissionType: PermissionType,
        val albumName: String? = null // For move operations
    )

    init {
        // Read API key from file and initialize agent
        viewModelScope.launch {
            try {
                val apiKey = readApiKeyFromFile()
                if (apiKey.isNotEmpty()) {
                    currentApiKey = apiKey
                    _uiState.update { it.copy(apiKey = apiKey) }
                    initializeAgent(apiKey)
                } else {
                    addMessage(ChatMessage(
                        text = """⚠️ API key not found. Please create a file at:
                            |${getApiKeyFilePath()}
                            |
                            |Add your Gemini API key as a single line in this file.""".trimMargin(),
                        sender = Sender.ERROR
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize agent on startup", e)
                addMessage(ChatMessage(
                    text = "Failed to initialize agent: ${e.message}\n\nPlease check API key file at: ${getApiKeyFilePath()}",
                    sender = Sender.ERROR
                ))
            }
        }
    }

    /**
     * Returns the path where the API key file should be located.
     * File: /data/data/com.example.lamforgallery/files/gemini_api_key.txt
     */
    private fun getApiKeyFilePath(): String {
        return File(application.filesDir, "gemini_api_key.txt").absolutePath
    }

    /**
     * Reads the API key from the internal file.
     * Returns empty string if file doesn't exist or can't be read.
     */
    private suspend fun readApiKeyFromFile(): String {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(application.filesDir, "gemini_api_key.txt")
                if (file.exists()) {
                    val content = file.readText().trim()
                    Log.d(TAG, "API key loaded from: ${file.absolutePath}")
                    content
                } else {
                    Log.w(TAG, "API key file not found at: ${file.absolutePath}")
                    ""
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading API key file", e)
                ""
            }
        }
    }

    // --- API KEY MANAGEMENT ---
    
    fun setApiKey(apiKey: String) {
        _uiState.update { it.copy(apiKey = apiKey) }
    }

    // --- SELECTION MANAGEMENT ---

    fun openSelectionSheet(uris: List<String>) {
        _uiState.update {
            it.copy(isSelectionSheetOpen = true, selectionSheetUris = uris)
        }
    }

    fun closeSelectionSheet() {
        _uiState.update {
            it.copy(isSelectionSheetOpen = false, selectionSheetUris = emptyList())
        }
    }

    fun confirmSelection(selectedUris: Set<String>) {
        _uiState.update {
            it.copy(
                selectedImageUris = selectedUris,
                isSelectionSheetOpen = false,
                selectionSheetUris = emptyList()
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedImageUris = emptySet()) }
    }

    fun toggleImageSelection(uri: String) {
        _uiState.update { currentState ->
            val currentSelection = currentState.selectedImageUris
            val newSelection = if (currentSelection.contains(uri)) {
                currentSelection - uri
            } else {
                currentSelection + uri
            }
            currentState.copy(
                selectedImageUris = newSelection,
                isInSelectionMode = newSelection.isNotEmpty()
            )
        }
    }

    fun enterSelectionMode(uri: String) {
        _uiState.update {
            it.copy(
                selectedImageUris = setOf(uri),
                isInSelectionMode = true
            )
        }
    }

    fun exitSelectionMode() {
        _uiState.update {
            it.copy(
                selectedImageUris = emptySet(),
                isInSelectionMode = false
            )
        }
    }

    private fun showImages(imageUris: List<String>, enterSelection: Boolean = false) {
        Log.d(TAG, "showImages called with ${imageUris.size} URIs, enterSelection=$enterSelection")
        
        _uiState.update { currentState ->
            val updatedState = currentState.copy(
                displayedImages = imageUris,
                isInSelectionMode = enterSelection,
                selectedImageUris = if (enterSelection) imageUris.toSet() else emptySet()
            )
            Log.d(TAG, "Updated state - displayedImages: ${updatedState.displayedImages.size}")
            updatedState
        }
    }

    fun clearDisplayedImages() {
        _uiState.update {
            it.copy(
                displayedImages = emptyList(),
                selectedImageUris = emptySet(),
                isInSelectionMode = false
            )
        }
    }

    fun setPendingAction(action: PendingAction) {
        _uiState.update { it.copy(pendingAction = action) }
    }

    fun confirmPendingAction() {
        val action = _uiState.value.pendingAction
        val selected = _uiState.value.selectedImageUris.toList()
        
        _uiState.update { it.copy(pendingAction = null) }
        
        when (action?.type) {
            ActionType.DELETE -> {
                viewModelScope.launch {
                    try {
                        galleryTools.moveToTrash(selected)
                        addMessage(ChatMessage(
                            text = "Successfully deleted ${selected.size} photos.",
                            sender = Sender.AGENT
                        ))
                        clearDisplayedImages()
                    } catch (e: Exception) {
                        addMessage(ChatMessage(
                            text = "Failed to delete photos: ${e.message}",
                            sender = Sender.ERROR
                        ))
                    }
                }
            }
            else -> {
                addMessage(ChatMessage(
                    text = "Action confirmed.",
                    sender = Sender.AGENT
                ))
            }
        }
    }

    fun cancelPendingAction() {
        _uiState.update { it.copy(pendingAction = null) }
        addMessage(ChatMessage(
            text = "Action cancelled.",
            sender = Sender.AGENT
        ))
        exitSelectionMode()
    }

    // --- PERMISSION HANDLING ---

    fun handlePermissionResult(granted: Boolean) {
        val pending = pendingPermissionData
        if (pending == null) {
            Log.w(TAG, "No pending permission data")
            setStatus(AgentStatus.Idle)
            return
        }

        viewModelScope.launch {
            if (!granted) {
                addMessage(ChatMessage(text = "Permission denied by user.", sender = Sender.ERROR))
                setStatus(AgentStatus.Idle)
                pendingPermissionData = null
                return@launch
            }

            // Execute the pending operation
            when (pending.permissionType) {
                PermissionType.DELETE -> {
                    addMessage(ChatMessage(text = "Deleting ${pending.photoUris.size} photos...", sender = Sender.AGENT))
                    // For delete, the system handles it automatically after permission
                    addMessage(ChatMessage(text = "Successfully deleted ${pending.photoUris.size} photos.", sender = Sender.AGENT))
                    
                    // Clean up database
                    withContext(Dispatchers.IO) {
                        try {
                            pending.photoUris.forEach { uri ->
                                imageEmbeddingDao.deleteByUri(uri)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete embeddings", e)
                        }
                    }
                    
                    _uiState.update { currentState ->
                        currentState.copy(selectedImageUris = currentState.selectedImageUris - pending.photoUris.toSet())
                    }
                    
                    _galleryDidChange.emit(Unit)
                }
                PermissionType.WRITE -> {
                    if (pending.albumName != null) {
                        addMessage(ChatMessage(text = "Moving ${pending.photoUris.size} photos to '${pending.albumName}'...", sender = Sender.AGENT))
                        val success = galleryTools.performMoveOperation(pending.photoUris, pending.albumName)
                        if (success) {
                            addMessage(ChatMessage(text = "Successfully moved ${pending.photoUris.size} photos to '${pending.albumName}'.", sender = Sender.AGENT))
                            _galleryDidChange.emit(Unit)
                        } else {
                            addMessage(ChatMessage(text = "Failed to move photos.", sender = Sender.ERROR))
                        }
                    }
                }
            }

            pendingPermissionData = null
            setStatus(AgentStatus.Idle)
        }
    }

    // --- USER INPUT ---

    fun sendMessage(input: String) {
        sendUserInput(input)
    }

    fun sendUserInput(input: String) {
        val currentState = _uiState.value
        if (currentState.currentStatus !is AgentStatus.Idle) {
            Log.w(TAG, "Agent is busy, ignoring input.")
            return
        }

        val apiKey = currentState.apiKey
        if (apiKey.isEmpty()) {
            addMessage(ChatMessage(text = "Please enter your Gemini API key first.", sender = Sender.ERROR))
            return
        }

        // Get the current selection and clear it
        val selectedUris = currentState.selectedImageUris.toList()
        _uiState.update { it.copy(selectedImageUris = emptySet()) }

        viewModelScope.launch {
            addMessage(ChatMessage(text = input, sender = Sender.USER))
            setStatus(AgentStatus.Loading("Thinking..."))

            try {
                // Initialize agent if needed
                if (agent == null || currentApiKey != apiKey) {
                    initializeAgent(apiKey)
                }

                // Build context with selected URIs if any
                val contextMessage = if (selectedUris.isNotEmpty()) {
                    "$input\n\nUser has selected these photos: ${selectedUris.joinToString(", ")}"
                } else {
                    input
                }

                Log.d(TAG, "Sending to agent: $contextMessage")
                
                // Run the agent
                val response = agent?.run(contextMessage)
                
                if (response != null) {
                    // Check if response requires permission
                    if (response.contains("\"requiresPermission\": true")) {
                        handlePermissionResponse(response)
                    } else {
                        // Log the full response for debugging
                        Log.d(TAG, "Agent response: $response")
                        
                        // Parse and display the response
                        
                        // Check if response contains photo URIs for display
                        val uris = try {
                            extractPhotoUris(response)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to extract photo URIs", e)
                            emptyList()
                        }
                        
                        Log.d(TAG, "Extracted ${uris.size} URIs from response")
                        
                        if (uris.isNotEmpty()) {
                            // Display images in the top gallery
                            showImages(uris, enterSelection = false)
                            
                            // Check if this is a delete/move request that needs confirmation
                            if (response.contains("delete", ignoreCase = true) || 
                                response.contains("remove", ignoreCase = true)) {
                                // Enter selection mode for delete operations
                                showImages(uris, enterSelection = true)
                                setPendingAction(PendingAction(
                                    type = ActionType.DELETE,
                                    itemCount = uris.size,
                                    message = "Are you sure you want to delete ${uris.size} photo(s)?"
                                ))
                            }
                            
                            addMessage(ChatMessage(text = "Found ${uris.size} photos", sender = Sender.AGENT))
                        } else {
                            addMessage(ChatMessage(text = response, sender = Sender.AGENT))
                        }
                        
                        setStatus(AgentStatus.Idle)
                    }
                } else {
                    addMessage(ChatMessage(text = "No response from agent.", sender = Sender.ERROR))
                    setStatus(AgentStatus.Idle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in agent execution", e)
                addMessage(ChatMessage(text = "Error: ${e.message}", sender = Sender.ERROR))
                setStatus(AgentStatus.Idle)
            }
        }
    }

    private suspend fun initializeAgent(apiKey: String) {
        currentApiKey = apiKey
        
        addMessage(ChatMessage(text = "Initializing Koog Agent with Gemini...", sender = Sender.AGENT))
        
        // Create tool registry
        val galleryToolSet = GalleryToolSet(galleryTools)
        val semanticSearchToolSet = SemanticSearchToolSet(imageEmbeddingDao, clipTokenizer, textEncoder)
        
        val toolRegistry = ToolRegistry {
            tools(galleryToolSet.asTools())
            tools(semanticSearchToolSet.asTools())
        }
        
        agent = AIAgent(
            promptExecutor = simpleGoogleAIExecutor(apiKey),
            systemPrompt = """You are a helpful AI assistant for managing photos in a gallery app.
                |You have access to powerful tools for:
                |1. Searching photos by filename or semantic content (using AI)
                |2. Organizing photos into albums
                |3. Deleting photos
                |4. Creating collages
                |5. Applying filters (grayscale, sepia)
                |
                |Before calling any tool, first explain what you're about to do in a friendly way.
                |For example: "Let me search for that...", "I'll delete those photos...", "Creating a collage..."
                |
                |The searchPhotos tool uses AI-powered semantic search to understand natural language queries.
                |Users can search with descriptions like "sunset", "cat", "people smiling", or filenames.
                |When you find photos, return the full JSON response so the user can see and select them.
                |For operations that modify photos (delete, move), make sure to use the tools correctly and inform the user of the results.
                |Be concise and helpful in your responses.""".trimMargin(),
            llmModel = GoogleModels.Gemini2_0Flash,
            temperature = 0.7,
            maxIterations = 30,
            toolRegistry = toolRegistry
        )
        
        addMessage(ChatMessage(text = "✅ Agent ready! You can now search, organize, and edit your photos.", sender = Sender.AGENT))
    }

    private fun handlePermissionResponse(response: String) {
        try {
            // Parse the JSON response to extract permission requirements
            val permissionTypeStr = extractJsonField(response, "permissionType")
            val urisJson = extractJsonArray(response, "photoUris")
            val albumName = extractJsonField(response, "albumName")
            
            val photoUris = urisJson.split(",").map { it.trim('"', ' ', '[', ']') }.filter { it.isNotEmpty() }
            
            val permissionType = when (permissionTypeStr) {
                "DELETE" -> PermissionType.DELETE
                "WRITE" -> PermissionType.WRITE
                else -> {
                    addMessage(ChatMessage(text = "Unknown permission type: $permissionTypeStr", sender = Sender.ERROR))
                    setStatus(AgentStatus.Idle)
                    return
                }
            }
            
            // Create intent sender
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                when (permissionType) {
                    PermissionType.DELETE -> galleryTools.createDeleteIntentSender(photoUris)
                    PermissionType.WRITE -> galleryTools.createWriteIntentSender(photoUris)
                }
            } else {
                null
            }
            
            if (intentSender == null) {
                addMessage(ChatMessage(text = "This operation requires Android 11 or higher.", sender = Sender.ERROR))
                setStatus(AgentStatus.Idle)
                return
            }
            
            // Store pending data
            pendingPermissionData = PendingPermissionData(
                photoUris = photoUris,
                permissionType = permissionType,
                albumName = albumName
            )
            
            val message = when (permissionType) {
                PermissionType.DELETE -> "Request permission to delete ${photoUris.size} photos"
                PermissionType.WRITE -> "Request permission to move ${photoUris.size} photos"
            }
            
            setStatus(AgentStatus.RequiresPermission(intentSender, permissionType, message))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling permission response", e)
            addMessage(ChatMessage(text = "Error processing permission request: ${e.message}", sender = Sender.ERROR))
            setStatus(AgentStatus.Idle)
        }
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractJsonArray(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*\[([^\]]*)\]""".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractPhotoUris(response: String): List<String> {
        Log.d(TAG, "Attempting to extract URIs from response")
        
        val urisJson = extractJsonArray(response, "photoUris")
        Log.d(TAG, "Extracted JSON array content: $urisJson")
        
        val uris = urisJson.split(",")
            .map { it.trim('"', ' ') }
            .filter { it.isNotEmpty() && it.startsWith("content://") }
        
        Log.d(TAG, "Parsed ${uris.size} URIs")
        return uris
    }

    private fun addMessage(message: ChatMessage) {
        _uiState.update { currentState ->
            currentState.copy(messages = currentState.messages + message)
        }
    }

    private fun setStatus(newStatus: AgentStatus) {
        _uiState.update { it.copy(currentStatus = newStatus) }
    }

    fun clearHistory() {
        agent = null
        currentApiKey = ""
        _uiState.update { 
            AgentUiState(
                messages = listOf(ChatMessage(text = "Conversation cleared. Send a new message to start.", sender = Sender.AGENT)),
                apiKey = _uiState.value.apiKey
            )
        }
    }
}
