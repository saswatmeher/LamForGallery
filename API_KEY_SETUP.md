# API Key Setup for LamForGallery

The app now reads the Gemini API key from a file on the device instead of hardcoding it.

## Quick Setup

### Option 1: Using the setup script (Recommended)

```bash
./setup_api_key.sh YOUR_GEMINI_API_KEY
```

Example:
```bash
./setup_api_key.sh AIzaSyDUCGceaG8AQFKRw5QO2GAW2TI5JWuLhY0
```

### Option 2: Manual setup

1. **Create the directory:**
   ```bash
   adb shell "mkdir -p /sdcard/Android/data/com.example.lamforgallery/files"
   ```

2. **Create the API key file:**
   ```bash
   adb shell "echo 'YOUR_API_KEY' > /sdcard/Android/data/com.example.lamforgallery/files/gemini_api_key.txt"
   ```

3. **Verify the file:**
   ```bash
   adb shell "cat /sdcard/Android/data/com.example.lamforgallery/files/gemini_api_key.txt"
   ```

## File Location

The app reads the API key from:
```
/sdcard/Android/data/com.example.lamforgallery/files/gemini_api_key.txt
```

## Installing & Running

1. **Install the app:**
   ```bash
   ./gradlew installDebug
   ```
   Or if the emulator is unresponsive:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Launch the app:**
   ```bash
   adb shell am start -n com.example.lamforgallery/.ui.MainActivity
   ```

## Troubleshooting

### "API key not found" error

The app will display the exact file path where it expects the API key. Check the error message in the Agent tab.

You can also check if the file exists:
```bash
adb shell "ls -la /sdcard/Android/data/com.example.lamforgallery/files/"
```

### Read the API key from the file:
```bash
adb shell "cat /sdcard/Android/data/com.example.lamforgallery/files/gemini_api_key.txt"
```

### Update the API key:
Just run the setup script again with the new key:
```bash
./setup_api_key.sh NEW_API_KEY
```

### Check app logs:
```bash
adb logcat | grep -E "AgentViewModel|API"
```

## Getting a Gemini API Key

1. Visit: https://makersuite.google.com/app/apikey
2. Sign in with your Google account
3. Click "Create API Key"
4. Copy the generated key
5. Use it with the setup script

## Security Note

⚠️ The API key file is stored in the app's external files directory. On Android 11+, this directory is scoped to the app and not accessible by other apps. However, it's still readable if you have ADB access or root.

For production apps, consider using:
- Android Keystore for encrypted storage
- SharedPreferences with encryption
- Backend API proxy to avoid exposing keys

## How It Works

When the app starts, the `AgentViewModel` automatically:
1. Reads the API key from the file
2. Initializes the Koog agent with Gemini
3. Shows an error if the file is missing or unreadable

The code is in:
```
app/src/main/java/com/example/lamforgallery/ui/AgentViewModel.kt
```

See the `readApiKeyFromFile()` and `init` block for implementation details.
