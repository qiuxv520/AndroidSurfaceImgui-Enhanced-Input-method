param([string]$Compiler = 'g++')

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$testOutput = Join-Path $project 'input-helper/build/ime_text_sync_test.exe'
$sources = @('tests/ime_text_sync.cpp', 'jni/src/ImGui/imgui.cpp',
    'jni/src/ImGui/imgui_draw.cpp', 'jni/src/ImGui/imgui_tables.cpp',
    'jni/src/ImGui/imgui_widgets.cpp')

Push-Location $project
try {
    New-Item -ItemType Directory -Force (Split-Path $testOutput -Parent) | Out-Null
    & $Compiler -std=c++17 -DIMGUI_DISABLE_WIN32_FUNCTIONS -I jni/include/ImGui $sources -o $testOutput
    if ($LASTEXITCODE -ne 0) { throw 'Text sync test build failed' }
    & $testOutput
    if ($LASTEXITCODE -ne 0) { throw 'Text sync test failed' }
} finally { Pop-Location }
