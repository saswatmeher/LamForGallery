#!/bin/bash

# Script to set up Gemini API key on Android device/emulator
# Usage: ./setup_api_key.sh YOUR_API_KEY

if [ -z "$1" ]; then
    echo "Usage: ./setup_api_key.sh YOUR_API_KEY"
    echo ""
    echo "Example:"
    echo "  ./setup_api_key.sh YOUR_GEMINI_API_KEY"
    echo ""
    echo "This will create the API key file at:"
    echo "  /data/data/com.example.lamforgallery/files/gemini_api_key.txt"
    exit 1
fi

API_KEY="$1"
APP_PACKAGE="com.example.lamforgallery"
FILE_PATH="/data/data/${APP_PACKAGE}/files/gemini_api_key.txt"

echo "Setting up Gemini API key..."
echo ""

# Write the API key using run-as (works for debuggable apps)
echo "Writing API key to internal storage..."
adb shell "run-as ${APP_PACKAGE} sh -c 'echo \"${API_KEY}\" > files/gemini_api_key.txt'"

# Verify the file was created
echo ""
echo "Verifying..."
FILE_CONTENT=$(adb shell "run-as ${APP_PACKAGE} cat files/gemini_api_key.txt" 2>/dev/null)

if [ -n "$FILE_CONTENT" ]; then
    echo "✅ Success! API key file created at:"
    echo "   ${FILE_PATH}"
    echo ""
    echo "API key (first 20 chars): ${FILE_CONTENT:0:20}..."
    echo ""
    echo "You can now launch the app!"
else
    echo "❌ Failed to create API key file"
    exit 1
fi
