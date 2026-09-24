#include "Android_input/InputBridge.h"
#include "imgui.h"
#include "imgui_internal.h"
#include "TouchHelperA.h"
#include "DumInputApk.h"

#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sstream>
#include <sys/wait.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace InputBridge {
namespace {
std::string payload_dir;
std::string payload_apk;
int to_child = -1;
int from_child = -1;
pid_t child = -1;
bool ready = false;
bool ime_available = false;
bool wanted = false;
bool dismissed = false;
bool pending_dismiss = false;
bool dismiss_after_frame = false;
bool clipboard_response = false;
ImGuiID active_id = 0;
unsigned long long session = 0;
int edit_start = 0;
int edit_end = 0;
std::string incoming;
std::string clipboard;
std::string edit_text;
struct Edit { std::string text; int start; int end; };
std::vector<Edit> pending_text;

void CleanupPayload() {
    // These paths come only from our own mkdtemp. Never recursively delete shared directories.
    if (!payload_apk.empty()) unlink(payload_apk.c_str());
    if (!payload_dir.empty()) rmdir(payload_dir.c_str());
    payload_apk.clear();
    payload_dir.clear();
}

bool ExtractPayload() {
    if (geteuid() != 0) {
        std::fprintf(stderr, "dum input: root is required\n");
        return false;
    }
    char directory[] = "/data/adb/dum-input-XXXXXX";
    if (!mkdtemp(directory)) {
        std::fprintf(stderr, "dum input: cannot create private runtime directory: %s\n", std::strerror(errno));
        return false;
    }
    payload_dir = directory;
    payload_apk = payload_dir + "/dum_input.apk";
    int fd = open(payload_apk.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    bool success = fd >= 0;
    if (success) {
        const unsigned char* bytes = DumPayload::apk;
        size_t remaining = sizeof(DumPayload::apk);
        while (remaining > 0) {
            ssize_t count = write(fd, bytes, remaining);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) { success = false; break; }
            bytes += count;
            remaining -= static_cast<size_t>(count);
        }
        // A new inode for every run: never overwrite a file mapped by an existing ART process.
        if (success) success = fchmod(fd, 0400) == 0 && fsync(fd) == 0;
        if (close(fd) != 0) success = false;
    }
    if (!success) {
        std::fprintf(stderr, "dum input: embedded APK extraction failed\n");
        CleanupPayload();
    }
    return success;
}


std::string Encode(const std::string& input) {
    static constexpr char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    for (size_t i = 0; i < input.size(); i += 3) {
        unsigned a = static_cast<unsigned char>(input[i]);
        unsigned b = i + 1 < input.size() ? static_cast<unsigned char>(input[i + 1]) : 0;
        unsigned c = i + 2 < input.size() ? static_cast<unsigned char>(input[i + 2]) : 0;
        result.push_back(table[a >> 2]);
        result.push_back(table[((a & 3) << 4) | (b >> 4)]);
        result.push_back(i + 1 < input.size() ? table[((b & 15) << 2) | (c >> 6)] : '=');
        result.push_back(i + 2 < input.size() ? table[c & 63] : '=');
    }
    return result;
}

std::string Decode(const std::string& input) {
    std::string result;
    unsigned value = 0;
    int bits = -8;
    for (char ch : input) {
        if (ch == '=') break;
        unsigned n;
        if (ch >= 'A' && ch <= 'Z') n = ch - 'A';
        else if (ch >= 'a' && ch <= 'z') n = ch - 'a' + 26;
        else if (ch >= '0' && ch <= '9') n = ch - '0' + 52;
        else if (ch == '+') n = 62;
        else if (ch == '/') n = 63;
        else continue;
        value = (value << 6) | n;
        bits += 6;
        if (bits >= 0) {
            result.push_back(static_cast<char>((value >> bits) & 255));
            bits -= 8;
        }
    }
    return result;
}

size_t NextCodepoint(const std::string& value, size_t offset) {
    if (offset >= value.size()) return offset;
    unsigned char first = value[offset];
    size_t count = first < 0x80 ? 1 : first < 0xE0 ? 2 : first < 0xF0 ? 3 : 4;
    return std::min(value.size(), offset + count);
}

int Utf16Length(const std::string& value, size_t byte_end) {
    int length = 0;
    for (size_t i = 0; i < std::min(byte_end, value.size()); i = NextCodepoint(value, i))
        length += static_cast<unsigned char>(value[i]) >= 0xF0 ? 2 : 1;
    return length;
}

int Utf8Offset(const std::string& value, int utf16_offset) {
    size_t pos = 0;
    int units = 0;
    while (pos < value.size()) {
        int count = static_cast<unsigned char>(value[pos]) >= 0xF0 ? 2 : 1;
        if (units + count > utf16_offset) break;
        units += count;
        pos = NextCodepoint(value, pos);
    }
    return static_cast<int>(pos);
}

void Send(const std::string& line) {
    if (to_child < 0) return;
    const char* data = line.data();
    size_t remaining = line.size();
    while (remaining) {
        ssize_t count = write(to_child, data, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return;
        data += count;
        remaining -= count;
    }
}

void SyncEditor(ImGuiID id) {
    edit_text.clear();
    edit_start = edit_end = 0;
    int flags = 0, capacity = 1023;
    if (id != 0) {
        if (ImGuiInputTextState* state = ImGui::GetInputTextState(id)) {
            if (state->TextA.Data && state->TextLen > 0)
                edit_text.assign(state->TextA.Data, static_cast<size_t>(state->TextLen));
            edit_start = state->GetSelectionStart();
            edit_end = state->GetSelectionEnd();
            flags = ((state->Flags & ImGuiInputTextFlags_Multiline) ? 1 : 0)
                    | ((state->Flags & ImGuiInputTextFlags_Password) ? 2 : 0);
            capacity = (state->Flags & ImGuiInputTextFlags_CallbackResize) ? 1024 * 1024 : std::max(0, state->BufCapacity - 1);
        }
    }
    Send("SYNC " + std::to_string(session) + " " + std::to_string(Utf16Length(edit_text, edit_start)) + " "
         + std::to_string(Utf16Length(edit_text, edit_end)) + " " + std::to_string(flags) + " "
         + std::to_string(capacity) + " " + Encode(edit_text) + "\n");
}

void ApplyText(const Edit& next) {
    if (!ImGui::GetCurrentContext() || !wanted || ImGui::GetActiveID() != active_id) return;
    if (ImGuiInputTextState* state = ImGui::GetInputTextState(active_id)) {
        edit_text = next.text;
        edit_start = Utf8Offset(edit_text, next.start);
        edit_end = Utf8Offset(edit_text, next.end);
        state->ApplyExternalText(edit_text.c_str(), edit_start, edit_end);
    }
}

void Dispatch(const std::string& line) {
    if (line == "READY") {
        ready = true;
        return;
    }
    if (line == "IME_AVAILABLE") { ime_available = true; return; }
    if (line == "IME_UNAVAILABLE") {
        ime_available = false;
        if (wanted) pending_dismiss = true;
        return;
    }
    if (line.rfind("EDIT ", 0) == 0 || line.rfind("HIDDEN ", 0) == 0 || line.rfind("IME ", 0) == 0) {
        std::istringstream fields(line);
        std::string kind;
        unsigned long long id;
        if (!(fields >> kind >> id) || id != session || !wanted) return;
        if (kind == "HIDDEN") { pending_dismiss = true; return; }
        if (kind == "EDIT") {
            int start, end;
            std::string payload;
            if (!(fields >> start >> end) || start < 0 || end < 0) return;
            fields >> payload;
            pending_text.push_back({Decode(payload), start, end});
        } else {
            int visible, top, width, height;
            if (fields >> visible >> top >> width >> height) {
                if (visible && top >= 0 && top < height) Touch::setImeTop(static_cast<float>(top));
            }
        }
    } else if (line.rfind("C ", 0) == 0) { clipboard = Decode(line.substr(2)); clipboard_response = true; }
    else if (line.rfind("ERROR ", 0) == 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ImguiInput", "%s", line.c_str());
        if (wanted) pending_dismiss = true;
    }
}

void Drain() {
    if (from_child < 0) return;
    char buffer[4096];
    while (true) {
        ssize_t count = read(from_child, buffer, sizeof(buffer));
        if (count > 0) {
            incoming.append(buffer, static_cast<size_t>(count));
            if (incoming.size() > 1024 * 1024) incoming.clear();
            size_t end;
            while ((end = incoming.find('\n')) != std::string::npos) {
                Dispatch(incoming.substr(0, end));
                incoming.erase(0, end + 1);
            }
        } else {
            if (count == 0) { close(from_child); from_child = -1; ready = false; pending_dismiss = true; }
            break;
        }
    }
}
}

bool Start() {
    if (child > 0) return true;
    if (!ExtractPayload()) return false;
    const std::string class_path = "-Djava.class.path=" + payload_apk;
    int input_pipe[2], output_pipe[2];
    if (pipe(input_pipe) != 0) { CleanupPayload(); return false; }
    if (pipe(output_pipe) != 0) {
        close(input_pipe[0]); close(input_pipe[1]);
        CleanupPayload();
        return false;
    }
    for (int fd : {input_pipe[0], input_pipe[1], output_pipe[0], output_pipe[1]})
        fcntl(fd, F_SETFD, FD_CLOEXEC);
    child = fork();
    if (child == 0) {
        dup2(input_pipe[0], STDIN_FILENO);
        dup2(output_pipe[1], STDOUT_FILENO);
        close(input_pipe[0]); close(input_pipe[1]);
        close(output_pipe[0]); close(output_pipe[1]);
        execl("/system/bin/app_process", "app_process",
              class_path.c_str(), "/system/bin",
              "com.example.imguiinput.InputBridge", payload_apk.c_str(), static_cast<char*>(nullptr));
        _exit(127);
    }
    close(input_pipe[0]); close(output_pipe[1]);
    if (child < 0) { close(input_pipe[1]); close(output_pipe[0]); CleanupPayload(); return false; }
    to_child = input_pipe[1];
    from_child = output_pipe[0];
    signal(SIGPIPE, SIG_IGN);
    fcntl(from_child, F_SETFL, fcntl(from_child, F_GETFL) | O_NONBLOCK);
    return true;
}

void Stop() {
    if (to_child >= 0) { Send("QUIT\n"); close(to_child); to_child = -1; }
    // The child must finish pm uninstall. Killing it here would leave the APK installed.
    if (child > 0) {
        bool reaped = false;
        for (int i = 0; i < 300; ++i) {
            Drain();
            pid_t result = waitpid(child, nullptr, WNOHANG);
            if (result == child || (result < 0 && errno == ECHILD)) { reaped = true; break; }
            usleep(50000);
        }
        if (reaped) CleanupPayload();
        else {
            __android_log_print(ANDROID_LOG_WARN, "ImguiInput", "dum cleanup still running in child %d", child);
            // The Java lifetime owner still needs the APK and will remove it when cleanup finishes.
            payload_apk.clear();
            payload_dir.clear();
        }
        child = -1;
    }
    if (from_child >= 0) { close(from_child); from_child = -1; }
    ready = wanted = ime_available = false;
    dismissed = pending_dismiss = dismiss_after_frame = false;
    Touch::setInputSuppressed(false);
    active_id = 0;
    edit_text.clear();
    pending_text.clear();
}

void NewFrame() {
    Drain();
    for (const auto& value : pending_text) ApplyText(value);
    pending_text.clear();
    if (pending_dismiss) {
        pending_dismiss = false;
        dismiss_after_frame = true;
        Touch::setInputSuppressed(false);
    }
}
bool Ready() { return ready; }
bool ImeAvailable() { return ready && ime_available; }

void SetTextInputWanted(bool value) {
    Drain();
    if (dismiss_after_frame) {
        dismiss_after_frame = false;
        dismissed = true;
        wanted = false;
        active_id = 0;
        if (ImGui::GetCurrentContext()) ImGui::ClearActiveID();
        return;
    }
    if (!ime_available) return;
    if (dismissed) {
        if (value) return;
        dismissed = false;
    }
    ImGuiID id = value && ImGui::GetCurrentContext() ? ImGui::GetActiveID() : 0;
    if (value && (!wanted || id != active_id)) {
        pending_text.clear();
        ++session;
        SyncEditor(id);
    } else if (value && wanted && pending_text.empty()) {
        if (ImGuiInputTextState* state = ImGui::GetInputTextState(id)) {
            if (edit_text != state->TextA.Data || edit_start != state->GetSelectionStart() || edit_end != state->GetSelectionEnd())
                SyncEditor(id);
        }
    }
    active_id = id;
    if (wanted == value) return;
    wanted = value;
    if (!value) edit_text.clear();
    pending_text.clear();
    Touch::setInputSuppressed(value && ready);
    if (ready) Send(std::string(value ? "SHOW " : "HIDE ") + std::to_string(session) + "\n");
}

const char* GetClipboardText(void*) {
    if (ready) {
        clipboard.clear();
        clipboard_response = false;
        Send("GETCLIP\n");
        for (int i = 0; i < 20; ++i) {
            pollfd pfd{from_child, POLLIN, 0};
            if (poll(&pfd, 1, 25) <= 0) continue;
            Drain();
            if (clipboard_response) break;
        }
    }
    return clipboard.c_str();
}

void SetClipboardText(void*, const char* text) {
    clipboard = text ? text : "";
    if (ready) Send("SETCLIP " + Encode(clipboard) + "\n");
}
}
