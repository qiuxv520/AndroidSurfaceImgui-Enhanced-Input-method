// Device integration probe. No extra test APK; uses the same temporary dum package.
#include "Android_input/InputBridge.h"
#include "imgui.h"
#include "imgui_internal.h"
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>

namespace Touch {
void setInputSuppressed(bool) {}
void setImeTop(float top) { std::printf("IME_TOP %.0f\n", top); }
}

int main(int argc, char**) {
    setvbuf(stdout, nullptr, _IONBF, 0);
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.DisplaySize = ImVec2(640, 480);
    unsigned char* pixels;
    int width, height;
    io.Fonts->GetTexDataAsRGBA32(&pixels, &width, &height);
    if (!InputBridge::Start()) return 2;
    char text[64] = "abc\xe4\xb8\xad\xe6\x96\x87";
    using Clock = std::chrono::steady_clock;
    const auto deadline = Clock::now() + std::chrono::seconds(60);
    auto reopen = Clock::now();
    int phase = 0;
    bool focusRequested = false, printed = false, passed = false;
    while (Clock::now() < deadline) {
        InputBridge::NewFrame();
        ImGui::NewFrame();
        ImGui::SetNextWindowSize(ImVec2(500, 300));
        ImGui::Begin("probe");
        bool focus = argc == 1 && InputBridge::ImeAvailable() && !focusRequested
                && (phase == 0 || (phase == 2 && Clock::now() >= reopen));
        if (focus) { ImGui::SetKeyboardFocusHere(); focusRequested = true; }
        ImGui::InputText("edit", text, sizeof(text));
        ImGuiID id = ImGui::GetItemID();
        ImGui::End();
        ImGui::Render();
        if (argc > 1 && InputBridge::ImeAvailable() && !printed) {
            std::puts("LIFETIME_READY"); printed = true;
        }
        if (argc == 1 && ImGui::GetActiveID() == id && (phase == 0 || phase == 2)) {
            auto* state = ImGui::GetInputTextState(id);
            state->ApplyExternalText(text, (int)std::strlen(text), (int)std::strlen(text));
            std::puts(phase == 0 ? "FIRST_READY" : "SECOND_READY");
            ++phase;
        }
        InputBridge::SetTextInputWanted(io.WantTextInput);
        if (phase == 1 && std::strcmp(text, "abc\xe4\xb8\xad") == 0) {
            std::puts("FIRST_DELETE_OK");
            ImGui::ClearActiveID();
            phase = 2;
            focusRequested = false;
            reopen = Clock::now() + std::chrono::milliseconds(1500);
        } else if (phase == 3 && std::strcmp(text, "abc") == 0) {
            std::puts("SECOND_DELETE_OK");
            passed = true;
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
    InputBridge::Stop();
    ImGui::DestroyContext();
    std::puts(passed ? "DEVICE_TEST_PASS" : "DEVICE_TEST_TIMEOUT");
    return passed ? 0 : 1;
}
