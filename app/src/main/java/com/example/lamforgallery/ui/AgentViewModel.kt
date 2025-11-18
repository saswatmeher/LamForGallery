package com.example.lamforgallery.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.example.lamforgallery.tools.GalleryTools
import com.example.lamforgallery.tools.GalleryToolSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// --- STATE DEFINITIONS ---

/**
 * Represents one message in the chat.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val isStreaming: Boolean = false // True when message is being streamed
)

enum class Sender {
    USER, AGENT, ERROR
}

/**
 * Represents the current status of the agent
 */
sealed class AgentStatus {
    data class Loading(val message: String) : AgentStatus()
    object Idle : AgentStatus()
}

/**
 * The UI state for the agent chat
 */
data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentStatus: AgentStatus = AgentStatus.Idle,
    val searchResults: List<String> = emptyList() // Photo URIs from search
)

class AgentViewModel(
    private val application: Application,
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val TAG = "AgentViewModel"
    
    // Koog AI Agent
    private var agent: AIAgent<String, String>? = null
    
    // Track streaming message ID
    private var streamingMessageId: String? = null
    
    // Gallery toolset for photo operations
    private val galleryToolSet: GalleryToolSet by lazy {
        GalleryToolSet(
            galleryTools = galleryTools,
            onSearchResults = { photoUris ->
                // Callback to update search results from tool
                updateSearchResults(photoUris)
            },
            onFunctionCall = { functionName, argsJson ->
                // Callback to display function calls in chat
                displayFunctionCall(functionName, argsJson)
            }
        )
    }

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    init {
        // Initialize with Ollama - no API key needed
        viewModelScope.launch {
            try {
                initializeAgent()
                addMessage(ChatMessage(
                    text = "Connected to local Ollama model. How can I help you manage your photos?\n\nI can handle complex operations like:\n• \"Delete all pictures of boys\" (searches then deletes)\n• \"Find sunset photos and apply sepia filter\"\n• \"Search for beaches and move them to Vacation album\"",
                    sender = Sender.AGENT
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize agent on startup", e)
                addMessage(ChatMessage(
                    text = "Failed to initialize agent: ${e.message}\n\nPlease ensure Ollama is running at http://192.168.1.8:11436",
                    sender = Sender.ERROR
                ))
            }
        }
    }

    // --- USER INPUT ---

    fun sendMessage(input: String) {
        sendUserInput(input)
    }

    fun sendUserInput(input: String) {
        val currentState = _uiState.value
        if (currentState.currentStatus !is AgentStatus.Idle) {
            Log.w(TAG, "Agent is busy, ignoring input. Current status: ${currentState.currentStatus}")
            addMessage(ChatMessage(
                text = "Please wait for the current message to complete.",
                sender = Sender.ERROR
            ))
            return
        }

        viewModelScope.launch {
            addMessage(ChatMessage(text = input, sender = Sender.USER))
            setStatus(AgentStatus.Loading("Thinking..."))

            try {
                Log.d(TAG, "Processing query: $input")
                setStatus(AgentStatus.Loading("Processing..."))
                
                // Create initial message that will be updated with LLM output
                val messageId = UUID.randomUUID().toString()
                streamingMessageId = messageId
                addMessage(ChatMessage(
                    id = messageId,
                    text = "🤔 Thinking...",
                    sender = Sender.AGENT,
                    isStreaming = true
                ))
                
                // Create tool registry
                val toolRegistry = ToolRegistry {
                    tools(galleryToolSet.asTools())
                }
                
                // Create agent
                val freshAgent = AIAgent(
                    promptExecutor = simpleOllamaAIExecutor(baseUrl = "http://192.168.1.8:11436"),
                    llmModel = LLModel(
                        provider = LLMProvider.Ollama,
                        id = "qwen3:4b",
                        capabilities = emptyList(),
                        contextLength = 262144
                    ),
                    systemPrompt = """You are a helpful AI assistant for managing photo galleries.
                        |You have access to tools for searching, organizing, and editing photos.
                        |
                        |IMPORTANT: You can chain multiple tool calls together for complex operations:
                        |
                        |For "delete all pictures of X":
                        |1. First call searchPhotos(query="X") to find matching photos
                        |2. The response contains a "photoUris" array: {"photoUris": ["uri1", "uri2", ...], "count": N}
                        |3. Extract the photoUris array from the JSON response
                        |4. Then call deletePhotos(photoUris=["uri1", "uri2", "uri3"]) with the extracted URIs
                        |
                        |For "move all X photos to Y album":
                        |1. First call searchPhotos(query="X")
                        |2. Extract the "photoUris" array from the JSON response
                        |3. Then call movePhotosToAlbum(photoUris=[...], albumName="Y")
                        |
                        |For "apply filter to X photos":
                        |1. First call searchPhotos(query="X")
                        |2. Extract the "photoUris" array from the response
                        |3. Then call applyFilter(photoUris=[...], filterName="filter")
                        |
                        |The searchPhotos tool returns JSON with this structure:
                        |{"photoUris": ["content://media/...", "content://media/..."], "count": 5, "message": "..."}
                        |
                        |Always extract the "photoUris" array and pass it directly to the next tool.
                        |Be friendly, concise, and provide updates about each step.""".trimMargin(),
                    temperature = 0.7,
                    maxIterations = 20,  // Increased for multi-step operations
                    toolRegistry = toolRegistry
                )
                
                Log.d(TAG, "\n" + "=".repeat(60))
                Log.d(TAG, "🚀 AGENT EXECUTION START")
                Log.d(TAG, "📝 User Input: $input")
                Log.d(TAG, "=".repeat(60))
                
                val response = freshAgent.run(input)
                
                Log.d(TAG, "\n" + "=".repeat(60))
                Log.d(TAG, "✅ AGENT EXECUTION COMPLETE")
                Log.d(TAG, "💬 Final Response: $response")
                Log.d(TAG, "=".repeat(60) + "\n")
                
                // Update message with final response
                streamingMessageId = null
                if (response != null && response.isNotEmpty()) {
                    updateMessage(messageId, response, Sender.AGENT)
                } else {
                    Log.w(TAG, "Agent returned null or empty response")
                    updateMessage(messageId, "No response from agent.", Sender.ERROR)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in agent execution", e)
                val errorMessage = e.message ?: "Unknown error"
                addMessage(ChatMessage(text = "Error: $errorMessage", sender = Sender.ERROR))
            } finally {
                // Always reset to idle state
                setStatus(AgentStatus.Idle)
            }
        }
    }

    private fun updateSearchResults(photoUris: List<String>) {
        _uiState.update { it.copy(searchResults = photoUris) }
    }

    fun clearSearchResults() {
        _uiState.update { it.copy(searchResults = emptyList()) }
    }

    private suspend fun initializeAgent() {
        Log.d(TAG, "Initializing agent with Ollama...")
        
        val executor = simpleOllamaAIExecutor(baseUrl = "http://192.168.1.8:11436")
        val model = LLModel(
            provider = LLMProvider.Ollama,
            id = "qwen2.5:3b",
            capabilities = emptyList(),
            contextLength = 262144
        )
        
        agent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            systemPrompt = """You are a helpful AI assistant for managing photo galleries.
                |Be friendly, concise, and helpful in your responses.""".trimMargin(),
            temperature = 0.7,
            maxIterations = 20
        )
        
        Log.d(TAG, "Agent initialized successfully")
    }

    private fun addMessage(message: ChatMessage) {
        _uiState.update { currentState ->
            currentState.copy(messages = currentState.messages + message)
        }
    }
    
    private fun displayFunctionCall(functionName: String, argsJson: String) {
        addMessage(ChatMessage(
            text = "🔧 Function Call: $functionName\nArguments: $argsJson",
            sender = Sender.AGENT
        ))
    }
    
    private fun appendToStreamingMessage(messageId: String, text: String) {
        _uiState.update { currentState ->
            val updatedMessages = currentState.messages.map { msg ->
                if (msg.id == messageId && msg.isStreaming) {
                    msg.copy(text = msg.text + text)
                } else {
                    msg
                }
            }
            currentState.copy(messages = updatedMessages)
        }
    }
    
    private fun finalizeStreamingMessage(messageId: String) {
        _uiState.update { currentState ->
            val updatedMessages = currentState.messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(isStreaming = false)
                } else {
                    msg
                }
            }
            currentState.copy(messages = updatedMessages)
        }
    }
    
    private fun updateMessage(messageId: String, text: String, sender: Sender) {
        _uiState.update { currentState ->
            val updatedMessages = currentState.messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(text = text, sender = sender, isStreaming = false)
                } else {
                    msg
                }
            }
            currentState.copy(messages = updatedMessages)
        }
    }

    private fun setStatus(newStatus: AgentStatus) {
        _uiState.update { it.copy(currentStatus = newStatus) }
    }

    fun clearHistory() {
        agent = null
        _uiState.update { 
            AgentUiState(
                messages = listOf(ChatMessage(text = "Conversation cleared. Send a new message to start.", sender = Sender.AGENT))
            )
        }
    }
}
