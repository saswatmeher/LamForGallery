package com.example.lamforgallery

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.data.AgentRequest
import com.example.lamforgallery.data.ToolCall
import com.example.lamforgallery.data.ToolResult
import com.example.lamforgallery.network.AgentApiService
import com.example.lamforgallery.network.NetworkModule
import com.example.lamforgallery.tools.GalleryTools
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// --- NEW STATE DEFINITIONS ---

// Represents one message in the chat
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender
)

enum class Sender {
    USER, AGENT, ERROR
}

// Represents the current *status* of the agent
sealed class AgentStatus {
    object Idle : AgentStatus()
    data class Loading(val message: String) : AgentStatus()
    data class RequiresPermission(
        val intentSender: IntentSender,
        val type: PermissionType,
        val message: String
    ) : AgentStatus()
}

// The single, combined UI state
data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentStatus: AgentStatus = AgentStatus.Idle
)

enum class PermissionType { DELETE, WRITE }

// --- END NEW STATE ---


class AgentViewModel(
    private val apiService: AgentApiService,
    private val galleryTools: GalleryTools,
    private val gson: Gson
) : ViewModel() {

    private val TAG = "AgentViewModel"
    private var currentSessionId: String? = null

    private var pendingToolCallId: String? = null
    private var pendingToolArgs: Map<String, Any>? = null

    // The ViewModel now holds our new, combined UI state
    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    fun sendUserInput(input: String) {
        if (input.isBlank() || _uiState.value.currentStatus !is AgentStatus.Idle) return

        viewModelScope.launch {
            // 1. Add the user's message to the chat list
            addMessage(ChatMessage(text = input, sender = Sender.USER))

            // 2. Set the status to Loading
            setStatus(AgentStatus.Loading("Thinking..."))

            // 3. Send to agent
            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = input,
                toolResult = null
            )
            Log.d(TAG, "Sending user input: $input")
            handleAgentRequest(request)
        }
    }

    fun onPermissionResult(wasSuccessful: Boolean, type: PermissionType) {
        val toolCallId = pendingToolCallId
        if (toolCallId == null) {
            Log.e(TAG, "onPermissionResult called but no pendingToolCallId")
            return
        }

        val args = pendingToolArgs
        pendingToolCallId = null
        pendingToolArgs = null

        viewModelScope.launch {
            if (!wasSuccessful) {
                Log.w(TAG, "User denied permission for $type")
                // --- FIX ---
                // Added named arguments "text =" and "sender ="
                addMessage(ChatMessage(text = "User denied permission.", sender = Sender.ERROR))
                sendToolResult(gson.toJson(false), toolCallId)
                return@launch
            }

            Log.d(TAG, "User granted permission for $type")
            when (type) {
                PermissionType.DELETE -> {
                    // One-step: just send the success.
                    sendToolResult(gson.toJson(true), toolCallId)
                }
                PermissionType.WRITE -> {
                    // Two-step: perform the move.
                    if (args == null) {
                        Log.e(TAG, "Write permission granted but no pending args!")
                        // --- FIX ---
                        addMessage(ChatMessage(text = "Error: Missing context for move operation.", sender = Sender.ERROR))
                        sendToolResult(gson.toJson(false), toolCallId)
                        return@launch
                    }

                    setStatus(AgentStatus.Loading("Moving files..."))

                    val uris = args["photo_uris"] as? List<String> ?: emptyList()
                    val album = args["album_name"] as? String ?: "New Album"

                    val moveResult = galleryTools.performMoveOperation(uris, album)
                    sendToolResult(gson.toJson(moveResult), toolCallId)
                }
            }
        }
    }

    private suspend fun handleAgentRequest(request: AgentRequest) {
        try {
            val response = apiService.invokeAgent(request)
            Log.d(TAG, "Received response: $response")
            currentSessionId = response.sessionId

            when (response.status) {
                "complete" -> {
                    val message = response.agentMessage ?: "Done."
                    // Add the agent's final message and set status to Idle
                    addMessage(ChatMessage(text = message, sender = Sender.AGENT))
                    setStatus(AgentStatus.Idle)
                }
                "requires_action" -> {
                    val action = response.nextActions?.firstOrNull()
                    if (action == null) {
                        Log.e(TAG, "Agent required action but sent none.")
                        // --- FIX ---
                        addMessage(ChatMessage(text = "Agent error: No action provided.", sender = Sender.ERROR))
                        setStatus(AgentStatus.Idle) // Stop the loop
                        return
                    }

                    // Set status to "Loading" with the tool name
                    setStatus(AgentStatus.Loading("Working on it: ${action.name}..."))
                    val toolResultJson = executeLocalTool(action)

                    if (toolResultJson != null) {
                        // Tool executed synchronously
                        sendToolResult(toolResultJson, action.id)
                    } else {
                        // Tool paused for permission.
                        Log.d(TAG, "Loop paused, waiting for permission result.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in agent loop", e)
            // --- FIX ---
            addMessage(ChatMessage(text = e.message ?: "Unknown network error", sender = Sender.ERROR))
            setStatus(AgentStatus.Idle)
        }
    }

    private suspend fun executeLocalTool(toolCall: ToolCall): String? {
        Log.d(TAG, "Executing local tool: ${toolCall.name} with args: ${toolCall.args}")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && toolCall.name != "search_photos") {
            Log.w(TAG, "Modify operations only supported on Android 11+")
            return gson.toJson(mapOf("error" to "Modify/Delete operations require Android 11+"))
        }

        val result: Any? = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    galleryTools.searchPhotos(query)
                }
                "delete_photos" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val intentSender = galleryTools.createDeleteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = null
                        // Set the status to RequiresPermission
                        setStatus(AgentStatus.RequiresPermission(
                            intentSender,
                            PermissionType.DELETE,
                            "Waiting for user permission to delete..."
                        ))
                        null // Pause the loop
                    } else {
                        mapOf("error" to "Could not create delete request.")
                    }
                }
                "move_photos_to_album" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val intentSender = galleryTools.createWriteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = toolCall.args
                        // Set the status to RequiresPermission
                        setStatus(AgentStatus.RequiresPermission(
                            intentSender,
                            PermissionType.WRITE,
                            "Waiting for user permission to move files..."
                        ))
                        null // Pause the loop
                    } else {
                        mapOf("error" to "Could not create write/move request.")
                    }
                }
                "create_collage" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    galleryTools.createCollage(uris, title)
                }
                else -> {
                    mapOf("error" to "Tool '${toolCall.name}' is not implemented on this client.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing local tool: ${toolCall.name}", e)
            // --- FIX ---
            addMessage(ChatMessage(text = "Error: ${e.message}", sender = Sender.ERROR))
            mapOf("error" to "Failed to execute ${toolCall.name}: ${e.message}")
        }

        return result?.let {
            val jsonResult = gson.toJson(it)
            Log.d(TAG, "Tool ${toolCall.name} result (JSON): $jsonResult")
            jsonResult
        }
    }

    private fun sendToolResult(resultJsonString: String, toolCallId: String) {
        viewModelScope.launch {
            setStatus(AgentStatus.Loading("Sending result to agent..."))

            val toolResult = ToolResult(
                toolCallId = toolCallId,
                content = resultJsonString
            )
            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = null,
                toolResult = toolResult
            )
            Log.d(TAG, "Sending tool result for $toolCallId")
            handleAgentRequest(request)
        }
    }

    // --- HELPER FUNCTIONS ---

    // Adds a new message to the chat list
    private fun addMessage(message: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + message) }
    }

    // Updates the current agent status
    private fun setStatus(status: AgentStatus) {
        _uiState.update { it.copy(currentStatus = status) }
    }
}

// Factory is unchanged
class AgentViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
            val apiService = NetworkModule.apiService
            val galleryTools = GalleryTools(application.applicationContext)
            val gson = NetworkModule.gson

            @Suppress("UNCHECKED_CAST")
            return AgentViewModel(apiService, galleryTools, gson) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}