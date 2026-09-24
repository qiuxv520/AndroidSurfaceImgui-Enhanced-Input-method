#include "imgui.h"
#include "imgui_internal.h"
#include <cassert>
#include <cstdio>
#include <cstring>

static char text[64] = "abc\xe4\xb8\xad\xe6\x96\x87";
static ImGuiID field;
static int edits;
static int Callback(ImGuiInputTextCallbackData*) { ++edits; return 0; }

static void Frame(bool focus = false, int capacity = sizeof(text)) {
    ImGui::NewFrame();
    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(ImVec2(500, 300));
    ImGui::Begin("test");
    if (focus) ImGui::SetKeyboardFocusHere();
    ImGui::InputText("edit", text, capacity, ImGuiInputTextFlags_CallbackEdit, Callback);
    field = ImGui::GetItemID();
    ImGui::End();
    ImGui::Render();
}

int main() {
    ImGui::CreateContext();
    auto& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.DisplaySize = ImVec2(640, 480);
    unsigned char* pixels;
    int width, height;
    io.Fonts->GetTexDataAsRGBA32(&pixels, &width, &height);
    Frame(true); Frame(); Frame();
    auto* state = ImGui::GetInputTextState(field);
    assert(state && ImGui::GetActiveID() == field);
    // Reopening starts from the current buffer, and deletion must remove old text.
    ImGui::ClearActiveID();
    Frame(); Frame(true); Frame();
    state = ImGui::GetInputTextState(field);
    assert(std::strcmp(state->TextA.Data, text) == 0);
    state->ApplyExternalText("abc\xe4\xb8\xad", 6, 6);
    Frame();
    assert(std::strcmp(text, "abc\xe4\xb8\xad") == 0 && edits > 0);
    // Replace a middle selection without deleting the unchanged suffix.
    state->ApplyExternalText("a\xe6\x96\x87" "c", 4, 4);
    Frame();
    assert(std::strcmp(text, "a\xe6\x96\x87" "c") == 0);
    assert(state->GetCursorPos() == 4);
    state->ApplyExternalText(text, 1, 4);
    Frame();
    assert(state->GetSelectionStart() == 1 && state->GetSelectionEnd() == 4);
    // Coalesced multi-character deletion, including the last edit before closing.
    state->ApplyExternalText("", 0, 0);
    Frame();
    assert(text[0] == 0);
    Frame(false, 8);
    state->ApplyExternalText("a\xf0\x9f\x98\x80\xe4\xb8\xad\xe6\x96\x87", 11, 11);
    Frame(false, 8);
    assert(std::strcmp(text, "a\xf0\x9f\x98\x80") == 0);
    assert(state->GetCursorPos() == 5);
    ImGui::DestroyContext();
    std::puts("PASS: reopen, old-text deletion, middle edit, selection, empty text, UTF-8 capacity");
}
