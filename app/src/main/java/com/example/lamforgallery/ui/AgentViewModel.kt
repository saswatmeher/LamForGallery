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
    val sender: Sender
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
    
    // Gallery toolset for photo operations
    private val galleryToolSet: GalleryToolSet by lazy {
        GalleryToolSet(galleryTools) { photoUris ->
            // Callback to update search results from tool
            updateSearchResults(photoUris)
        }
    }

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    init {
        // Initialize with Ollama - no API key needed
        viewModelScope.launch {
            try {
                initializeAgent()
                addMessage(ChatMessage(
                    text = "Connected to local Ollama model. How can I help you manage your photos?",
                    sender = Sender.AGENT
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize agent on startup", e)
                addMessage(ChatMessage(
                    text = "Failed to initialize agent: ${e.message}\n\nPlease ensure Ollama is running on localhost:11434",
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
                Log.d(TAG, "Creating fresh agent instance for new message")
                
                // Create tool registry with gallery tools
                val toolRegistry = ToolRegistry {
                    tools(galleryToolSet.asTools())
                }
                
                // Create a fresh agent instance for each message to avoid "already started" errors
                val freshAgent = AIAgent(
                    promptExecutor = simpleOllamaAIExecutor(
                        baseUrl = "http://192.168.1.8:11436"
                    ),
                    llmModel = LLModel(
                        provider = LLMProvider.Ollama,
                        id = "qwen3:4b",
                        capabilities = emptyList(),
                        contextLength = 262144
                    ),
                    systemPrompt = """You are a helpful AI assistant for managing photo galleries.
                        |You have access to tools for searching, organizing, and editing photos.
                        |
                        |When the user asks to search, find, show, or look for photos, use the searchPhotos tool.
                        |When you receive photo URIs from the tool, display them clearly to the user.
                        |
                        |Be friendly, concise, and helpful in your responses.""".trimMargin(),
                    temperature = 0.7,
                    maxIterations = 10,
                    toolRegistry = toolRegistry
                )

                Log.d(TAG, "Sending to agent: $input")
                
                // Run the agent
                val response = freshAgent.run(input)
                
                if (response != null && response.isNotEmpty()) {
                    Log.d(TAG, "Agent response: $response")
                    addMessage(ChatMessage(text = response, sender = Sender.AGENT))
                } else {
                    Log.w(TAG, "Agent returned null or empty response")
                    addMessage(ChatMessage(text = "No response from agent.", sender = Sender.ERROR))
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
        Log.d(TAG, "Initializing new AI Agent with Ollama...")
        
        agent = AIAgent(
            promptExecutor = simpleOllamaAIExecutor(
                baseUrl = "http://192.168.1.8:11436"
            ),
            llmModel = LLModel(
                provider = LLMProvider.Ollama,
                id = "qwen2.5:3b",
                capabilities = emptyList(),
                contextLength = 262144
            ),
            systemPrompt = """You are a helpful AI assistant. 
                |Be friendly, concise, and helpful in your responses.""".trimMargin(),
            temperature = 0.7,
            maxIterations = 10
        )
        
        Log.d(TAG, "Agent initialized successfully with Ollama")
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
        _uiState.update { 
            AgentUiState(
                messages = listOf(ChatMessage(text = "Conversation cleared. Send a new message to start.", sender = Sender.AGENT))
            )
        }
    }
}
