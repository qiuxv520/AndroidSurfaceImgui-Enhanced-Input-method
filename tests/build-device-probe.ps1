param([string]$NdkRoot = 'D:\ASWJ\ndk\25.2.9519653')
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$compiler = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
$sources = @('tests/ime_device_probe.cpp', 'jni/src/Android_input/InputBridge.cpp',
    'jni/src/ImGui/imgui.cpp', 'jni/src/ImGui/imgui_draw.cpp',
    'jni/src/ImGui/imgui_tables.cpp', 'jni/src/ImGui/imgui_widgets.cpp')
Push-Location $project
try {
    New-Item -ItemType Directory -Force 'input-helper/build' | Out-Null
    if (-not (Test-Path 'input-helper/build/embedded/DumInputApk.h')) { & ./build-input-helper.ps1 -BuildApk }
    & $compiler --target=aarch64-linux-android22 -std=c++17 -static-libstdc++ -I input-helper/build/embedded -I jni/include -I jni/include/ImGui -I jni/include/Android_touch -I jni/include/My_Utils $sources -llog -o input-helper/build/ime_device_probe
    if ($LASTEXITCODE -ne 0) { throw 'device probe build failed' }
} finally { Pop-Location }
