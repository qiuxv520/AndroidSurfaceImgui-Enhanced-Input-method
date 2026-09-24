#pragma once

#include <string>

// Extracts the embedded APK and manages the temporary clipboard/IME helper lifetime.
namespace InputBridge {
bool Start();
void Stop();
void NewFrame();
void SetTextInputWanted(bool wanted);
bool Ready();
bool ImeAvailable();
const char* GetClipboardText(void*);
void SetClipboardText(void*, const char* text);
}
