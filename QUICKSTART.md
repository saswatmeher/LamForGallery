# Quick Start Guide - LamForGallery with Koog

## 🚀 Quick Setup (5 minutes)

### 1. Get Gemini API Key
```
Visit: https://makersuite.google.com/app/apikey
Click: Create API Key
Copy: Your new API key
```

### 2. Build & Install
```bash
cd /home/saswat/projects/Agent/LamForGallery
./gradlew installDebug
```

### 3. First Run
1. Open LamForGallery app
2. Grant storage permissions when prompted
3. Navigate to "Agent" tab
4. A dialog will appear - paste your Gemini API key
5. Click "OK"

### 4. Try It Out!

**Example Commands:**

```
"Search for photos with sunset in the name"
→ Searches filenames

"Find photos of beaches using AI"
→ Semantic search using CLIP embeddings

"Create a collage called vacation"
→ Creates collage from selected photos

"Apply grayscale filter to these images"
→ Applies filter to selected photos

"Move selected photos to Summer album"
→ Moves photos (requires Android 11+)

"Delete the selected photos"
→ Deletes photos (requires Android 11+)
```

## 💡 Tips

### Selecting Photos
- Agent responses with photos appear as "Tap to select"
- Click the message to open selection sheet
- Check/uncheck photos
- Click "Confirm Selection"

### Permission Operations
- Delete/Move operations require Android 11+
- System dialog will appear for confirmation
- Agent waits for your decision

### API Key
- Click the status text to change API key
- API key is stored in app state (not persisted)
- Re-enter after app restart

## 🛠️ Development

### Project Structure
```
app/src/main/java/com/example/lamforgallery/
├── tools/
│   ├── GalleryToolSet.kt         # 8 gallery operation tools
│   ├── SemanticSearchToolSet.kt  # AI semantic search tools
│   └── GalleryTools.kt           # Core gallery functions
├── ui/
│   ├── AgentViewModel.kt         # Koog agent management
│   ├── AgentScreen.kt            # Chat UI
│   └── MainActivity.kt           # App entry point
├── ml/
│   ├── ClipTokenizer.kt          # Text tokenization
│   ├── TextEncoder.kt            # Text embedding
│   └── ImageEncoder.kt           # Image embedding
└── database/
    ├── ImageEmbeddingDao.kt      # Database access
    └── ImageEmbedding.kt         # Embedding model
```

### Adding New Tools

```kotlin
@LLMDescription("Description of your tool")
class MyToolSet : ToolSet {
    
    @Serializable
    data class MyToolArgs(
        @property:LLMDescription("Parameter description")
        val param: String
    )
    
    @Tool
    @LLMDescription("What this tool does")
    suspend fun myTool(args: MyToolArgs): String {
        // Your logic here
        return """{"result": "success"}"""
    }
}

// Register in AgentViewModel:
val toolRegistry = ToolRegistry {
    tools(MyToolSet().asTools())
}
```

### Debugging

```bash
# Watch agent logs
adb logcat | grep -E "AgentViewModel|GalleryToolSet|SemanticSearch"

# Check Koog framework logs
adb logcat | grep "Koog"

# Full error logs
adb logcat *:E
```

## 📊 Tool Reference

### GalleryToolSet

| Tool | Description | Android Version |
|------|-------------|----------------|
| `searchPhotos` | Search by filename | All |
| `deletePhotos` | Delete photos | 11+ |
| `movePhotosToAlbum` | Move to album | 11+ |
| `createCollage` | Create collage | All |
| `applyFilter` | Apply filters | All |
| `getAlbums` | List albums | All |
| `getPhotos` | Get photos | All |
| `getPhotosForAlbum` | Album photos | All |

### SemanticSearchToolSet

| Tool | Description | Requires |
|------|-------------|----------|
| `searchPhotosBySemantic` | AI search | Embeddings indexed |
| `getEmbeddingStats` | Index stats | - |

## 🔧 Build Configuration

### Dependencies
```kotlin
// Koog Framework
implementation("ai.koog:koog-agents:0.5.2")

// Ktor Client
implementation("io.ktor:ktor-client-cio:2.3.5")
implementation("io.ktor:ktor-client-content-negotiation:2.3.5")
implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")

// Serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
```

### Versions
- Kotlin: `2.1.0`
- KSP: `2.1.0-1.0.29`
- Koog: `0.5.2`

## 🐛 Troubleshooting

### "API key not set" error
→ Click the status text and enter your API key

### "No photos found"
→ Ensure device has photos and storage permission granted

### "This operation requires Android 11+"
→ Delete/move only work on Android 11 (API 30)+

### Build fails with Kotlin version mismatch
→ Ensure using Kotlin 2.1.0 (check `gradle/libs.versions.toml`)

### App crashes on tool execution
→ Check Logcat for specific error
→ Verify all tools return valid JSON strings

## 📚 Resources

- **Koog Documentation:** https://github.com/JetBrains/koog
- **Gemini API:** https://ai.google.dev/
- **Full Migration Guide:** `MIGRATION_TO_KOOG.md`
- **Summary:** `MIGRATION_SUMMARY.md`

## ✅ Verification

Test these to verify everything works:

```
✓ API key can be configured
✓ Search by filename works
✓ Semantic search works (if embeddings exist)
✓ Photo selection works
✓ Collage creation works
✓ Filter application works
✓ Permission dialogs appear for delete/move
✓ Agent provides helpful responses
```

---

**Last Updated:** November 12, 2025  
**Version:** 1.0  
**Framework:** Koog 0.5.2  
**LLM:** Gemini 2.0 Flash
