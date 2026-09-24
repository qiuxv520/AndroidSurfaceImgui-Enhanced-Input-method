param(
    [string]$NdkRoot = 'D:\ASWJ\ndk\25.2.9519653',
    [string]$SdkRoot = 'D:\ASWJ',
    [string]$Platform = 'android-35',
    [string]$BuildToolsVersion = '35.0.1',
    [int]$Jobs = 4
)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$ndkBuild = Join-Path $NdkRoot 'ndk-build.cmd'
if (-not (Test-Path -LiteralPath $ndkBuild)) { throw "NDK not found: $ndkBuild" }
if ($Jobs -lt 1) { throw 'Jobs must be positive' }
Push-Location $project
try {
    & (Join-Path $project 'build-input-helper.ps1') -SdkRoot $SdkRoot -Platform $Platform -BuildToolsVersion $BuildToolsVersion -BuildApk
    & $ndkBuild "-j$Jobs" 'APP_ABI=arm64-v8a'
    if ($LASTEXITCODE -ne 0) { throw 'ndk-build failed' }

    # Remove only the three obsolete sidecars produced by the previous build workflow.
    $releaseDir = [System.IO.Path]::GetFullPath((Join-Path $project 'libs\arm64-v8a'))
    foreach ($sidecar in @('imgui_input.dex', 'dum_input.apk', 'dum_input.apk.idsig')) {
        $obsolete = Join-Path $releaseDir $sidecar
        if (Test-Path -LiteralPath $obsolete -PathType Leaf) { Remove-Item -LiteralPath $obsolete -Force }
    }
    Write-Host "Single-file output: $(Join-Path $releaseDir 'AndroidSurfaceImguiEnhanced')"
} finally { Pop-Location }
