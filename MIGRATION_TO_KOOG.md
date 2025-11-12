# Migration to Koog Agentic Framework

This document describes the migration of LamForGallery from a remote REST API-based agentic framework to the on-device Koog agentic framework with Gemini AI.

## Overview

The LamForGallery app has been successfully migrated from using a remote agentic framework (communicating via REST API) to using the **Koog** agentic framework from JetBrains with **Google Gemini** as the LLM provider. This enables:

- **On-device AI processing** - No backend server required
- **Better privacy** - API key stays on device
- **Faster responses** - Direct communication with Gemini API
- **More control** - Full customization of agent behavior and tools

## Changes Made

### 1. Dependencies Updated (`app/build.gradle.kts`)

**Added:**
- `ai.koog:koog-agents:0.5.2` - Koog agentic framework
- `io.ktor:ktor-client-cio:2.3.5` - HTTP client for Koog
- `io.ktor:ktor-client-content-negotiation:2.3.5` - Content negotiation
- `io.ktor:ktor-serialization-kotlinx-json:2.3.5` - JSON serialization
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0` - Kotlin serialization
- `kotlin("plugin.serialization")` plugin

**Removed:**
- `com.squareup.retrofit2:retrofit:2.9.0`
- `com.squareup.retrofit2:converter-gson:2.9.0`

### 2. New Tool System

Created Koog-compatible tool sets using the `@Tool` annotation system:

#### `GalleryToolSet.kt`
Wraps gallery operations as Koog tools:
- `searchPhotos` - Search by filename
- `deletePhotos` - Delete photos (requires permission)
- `movePhotosToAlbum` - Move photos to album (requires permission)
- `createCollage` - Create photo collages
- `applyFilter` - Apply filters (grayscale, sepia)
- `getAlbums` - List all albums
- `getPhotos` - Get paginated photos
- `getPhotosForAlbum` - Get photos from specific album

#### `SemanticSearchToolSet.kt`
AI-powered semantic search tools:
- `searchPhotosBySemantic` - Natural language photo search using CLIP embeddings
- `getEmbeddingStats` - Get indexing statistics

All tools use `@LLMDescription` annotations to help the AI understand their purpose and parameters.

### 3. AgentViewModel Rewrite

**Old Implementation:**
- Used `AgentApiService` to communicate with remote backend
- Request/response cycle with session management
- Complex tool call/result handling

**New Implementation:**
- Direct integration with Koog `AIAgent`
- Uses `simpleGoogleAIExecutor` with Gemini 2.0 Flash
- Tool registry initialization with gallery and semantic search tools
- Simplified state management
- API key managed locally

**Key Changes:**
```kotlin
// Old: REST API based
private val agentApi: AgentApiService

// New: Koog-based
private var agent: AIAgent<String, String>?

// Initialize agent with tools
agent = AIAgent(
    promptExecutor = simpleGoogleAIExecutor(apiKey),
    systemPrompt = "...",
    llmModel = GoogleModels.Gemini2_0Flash,
    toolRegistry = toolRegistry
)
```

### 4. UI Updates

**API Key Configuration:**
- Added dialog for entering Gemini API key
- API key status indicator in chat input bar
- Visual feedback when API key is not configured
- Click to reconfigure API key

**Chat Input Bar Enhanced:**
- Shows API key status: "⚠️ API Key not set" or "✓ API Key configured"
- Disabled when API key is missing
- Clear visual state management

### 5. Files Removed

The following files related to the remote API infrastructure were removed:
- `network/NetworkModule.kt` - Retrofit configuration
- `network/AgentApiService.kt` - REST API interface
- `data/AndroidModels.kt` - Request/Response models

### 6. ViewModelFactory Updated

Updated to remove `AgentApiService` and `NetworkModule` dependencies:
```kotlin
// Old
private val agentApi: AgentApiService by lazy {
    NetworkModule.apiService
}

// New - removed (no longer needed)
```

## How to Use

### Setup

1. **Get a Gemini API Key:**
   - Visit [Google AI Studio](https://makersuite.google.com/app/apikey)
   - Create a new API key
   - Copy the key

2. **Configure in App:**
   - Open LamForGallery
   - Navigate to Agent tab
   - A dialog will prompt for your API key
   - Enter your Gemini API key
   - Click OK

3. **Start Using:**
   - Type natural language commands
   - Examples:
     - "Search for photos with 'sunset' in the name"
     - "Find photos of beaches using AI"
     - "Create a collage from the selected photos"
     - "Apply grayscale filter to these images"
     - "Move selected photos to 'Vacation' album"

### System Prompt

The agent is configured with the following system prompt:

```
You are a helpful AI assistant for managing photos in a gallery app.
You have access to powerful tools for:
1. Searching photos by filename or semantic content (using AI)
2. Organizing photos into albums
3. Deleting photos
4. Creating collages
5. Applying filters (grayscale, sepia)

When a user asks to search for photos, use searchPhotos for filename-based 
search or searchPhotosBySemantic for content-based search.
```

## Architecture

```
┌─────────────────────────────────────────┐
│        LamForGallery App                │
│                                          │
│  ┌────────────────────────────────────┐ │
│  │       AgentViewModel               │ │
│  │  - Manages Koog AIAgent            │ │
│  │  - Handles user input              │ │
│  │  - Processes tool responses        │ │
│  └───────────┬────────────────────────┘ │
│              │                            │
│              ▼                            │
│  ┌────────────────────────────────────┐ │
│  │      Koog AIAgent                  │ │
│  │  - Gemini 2.0 Flash                │ │
│  │  - Tool registry                   │ │
│  │  - Context management              │ │
│  └───────────┬────────────────────────┘ │
│              │                            │
│              ▼                            │
│  ┌────────────────────────────────────┐ │
│  │       Tool Sets                    │ │
│  │  - GalleryToolSet                  │ │
│  │  - SemanticSearchToolSet           │ │
│  └────────────────────────────────────┘ │
└──────────────┬───────────────────────────┘
               │
               │ HTTPS
               ▼
      ┌────────────────┐
      │  Gemini API    │
      │  (Google AI)   │
      └────────────────┘
```

## Benefits of Migration

1. **No Backend Required** - Eliminates need for remote server infrastructure
2. **Privacy** - API key and conversations stay on device
3. **Reliability** - No network dependencies beyond Gemini API
4. **Performance** - Direct API calls, no intermediary
5. **Cost** - No server hosting costs, only Gemini API usage
6. **Maintainability** - Simpler architecture, fewer moving parts
7. **Extensibility** - Easy to add new tools with `@Tool` annotations

## Testing

To test the migration:

1. **Build the app:**
   ```bash
   cd /home/saswat/projects/Agent/LamForGallery
   ./gradlew assembleDebug
   ```

2. **Install on device:**
   ```bash
   ./gradlew installDebug
   ```

3. **Test basic operations:**
   - Set API key
   - Search photos by name
   - Search photos semantically
   - Create collage
   - Apply filters
   - Move photos (requires Android 11+)
   - Delete photos (requires Android 11+)

4. **Verify tool responses:**
   - Check that photo URIs are returned
   - Verify permission dialogs appear
   - Confirm operations complete successfully

## Troubleshooting

### "API Key not set" error
- Click on the API key status text
- Enter your Gemini API key
- Ensure it's valid and has credits

### "This operation requires Android 11+" error
- Delete and move operations require Android 11 (API 30) or higher
- Use an emulator or device with Android 11+

### No photos found
- Ensure the device has photos in the gallery
- Check storage permissions are granted
- Try semantic search after embeddings are generated

### Tool execution errors
- Check LogCat for detailed error messages
- Verify tool parameters in the logs
- Ensure required permissions are granted

## Future Enhancements

- [ ] Add more filters (blur, sharpen, vintage)
- [ ] Support for video management
- [ ] Batch operations with progress tracking
- [ ] Export/import collage templates
- [ ] Custom tool marketplace
- [ ] Voice command support
- [ ] Multi-language support

## References

- [Koog Framework Documentation](https://github.com/JetBrains/koog)
- [Google Gemini API](https://ai.google.dev/)
- [Koog Examples](https://github.com/JetBrains/koog/tree/main/examples)

---

**Migration Date:** November 12, 2025  
**Koog Version:** 0.5.2  
**LLM Model:** Google Gemini 2.0 Flash
