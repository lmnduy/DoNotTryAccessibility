<#
.SYNOPSIS
    Builds the DoNotTryAccessibility Zygisk module (no manager APK).

.DESCRIPTION
    Requirements:
      * JDK 17+ (javac, jar)
      * Android SDK with build-tools, platform android-34 and NDK 26.x
      * CMake + Ninja shipped with the SDK

    The resulting zip is a Magisk/KernelSU/APatch module.  The configuration
    UI is the module WebUI in webroot/ (KernelSU / APatch).
#>
param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$NdkVersion = "26.0.10792818",
    [string]$BuildToolsVersion = "35.0.0",
    [string]$Platform = "android-34"
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

$AndroidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
$BuildTools = Join-Path $Sdk "build-tools\$BuildToolsVersion"
$Ndk = Join-Path $Sdk "ndk\$NdkVersion"
$CMakeDir = Join-Path $Sdk "cmake\3.22.1\bin"
$CMake = Join-Path $CMakeDir "cmake.exe"
$Ninja = Join-Path $CMakeDir "ninja.exe"

foreach ($path in @($AndroidJar, $BuildTools, $Ndk, $CMake, $Ninja)) {
    if (-not (Test-Path $path)) {
        throw "Missing required tool: $path"
    }
}

$Build = Join-Path $Root "build"
$Dist = Join-Path $Root "dist"
$Version = "2.0.0"
$VersionCode = 20000

New-Item -ItemType Directory -Force -Path $Build, $Dist | Out-Null

function Remove-Dir([string]$Path) {
    if (Test-Path $Path) {
        Remove-Item -Recurse -Force $Path
    }
}

function Assert-Exit([string]$What) {
    if ($LASTEXITCODE -ne 0) {
        throw "$What failed with exit code $LASTEXITCODE"
    }
}

Write-Host "==> 1/3 compile hook dex" -ForegroundColor Cyan
$HookClasses = Join-Path $Build "hook-classes"
$HookDexDir = Join-Path $Build "hook-dex"
Remove-Dir $HookClasses
Remove-Dir $HookDexDir
New-Item -ItemType Directory -Force -Path $HookClasses, $HookDexDir | Out-Null

$HookSources = Get-ChildItem (Join-Path $Root "hook\src") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -source 8 -target 8 -Xlint:-options -bootclasspath $AndroidJar -encoding UTF-8 `
    -d $HookClasses @HookSources
Assert-Exit "javac (hook)"

& jar --create --file (Join-Path $Build "hook.jar") -C $HookClasses .
Assert-Exit "jar (hook)"

& (Join-Path $BuildTools "d8.bat") --min-api 27 --lib $AndroidJar `
    --output $HookDexDir (Join-Path $Build "hook.jar")
Assert-Exit "d8 (hook)"

$HookDex = Join-Path $HookDexDir "classes.dex"
if (-not (Test-Path $HookDex)) {
    throw "d8 did not produce classes.dex"
}

Write-Host "==> 2/3 embed hook dex" -ForegroundColor Cyan
$bytes = [IO.File]::ReadAllBytes($HookDex)
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('extern "C" {')
[void]$sb.AppendLine('extern const unsigned char dnta_dex[] = {')
$line = New-Object System.Text.StringBuilder
for ($i = 0; $i -lt $bytes.Length; $i++) {
    if ($i % 16 -eq 0 -and $i -gt 0) {
        [void]$sb.AppendLine($line.ToString())
        [void]$line.Clear()
    }
    [void]$line.Append(('0x{0:x2},' -f $bytes[$i]))
}
if ($line.Length -gt 0) {
    [void]$sb.AppendLine($line.ToString())
}
[void]$sb.AppendLine('};')
[void]$sb.AppendLine("extern const unsigned int dnta_dex_len = $($bytes.Length)u;")
[void]$sb.AppendLine('}')
[IO.File]::WriteAllText((Join-Path $Root "native\src\dex_data.cpp"), $sb.ToString(),
    (New-Object System.Text.UTF8Encoding($false)))

Write-Host "==> 3/3 build native Zygisk libraries and package the module" -ForegroundColor Cyan
$ZygiskStage = Join-Path $Build "module"
Remove-Dir $ZygiskStage
New-Item -ItemType Directory -Force -Path (Join-Path $ZygiskStage "zygisk") | Out-Null

foreach ($abi in @("arm64-v8a", "armeabi-v7a")) {
    $nativeBuild = Join-Path $Build "native-$abi"
    Remove-Dir $nativeBuild
    & $CMake -S (Join-Path $Root "native") -B $nativeBuild -G Ninja `
        "-DCMAKE_TOOLCHAIN_FILE=$Ndk\build\cmake\android.toolchain.cmake" `
        "-DANDROID_ABI=$abi" "-DANDROID_PLATFORM=android-27" `
        "-DANDROID_STL=c++_static" "-DCMAKE_MAKE_PROGRAM=$Ninja" `
        "-DCMAKE_BUILD_TYPE=Release" | Out-Host
    Assert-Exit "cmake configure ($abi)"
    & $CMake --build $nativeBuild | Out-Host
    Assert-Exit "cmake build ($abi)"
    Copy-Item (Join-Path $nativeBuild "libdnta.so") (Join-Path $ZygiskStage "zygisk\$abi.so") -Force
}

Copy-Item (Join-Path $Root "module\*") $ZygiskStage -Recurse -Force

$ModuleZip = Join-Path $Dist "DoNotTryAccessibility-zygisk-v$Version.zip"
if (Test-Path $ModuleZip) {
    Remove-Item $ModuleZip -Force
}

# Compress-Archive writes backslashes on Windows PowerShell 5.1, which breaks
# the module installer's unzip.  Build the archive manually.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipStream = [IO.File]::Create($ModuleZip)
$archive = New-Object IO.Compression.ZipArchive($zipStream, [IO.Compression.ZipArchiveMode]::Create)
try {
    $baseLength = $ZygiskStage.Length + 1
    foreach ($file in Get-ChildItem $ZygiskStage -Recurse -File) {
        $relative = $file.FullName.Substring($baseLength).Replace('\', '/')
        $entry = $archive.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
        $entryStream = $entry.Open()
        try {
            $input = [IO.File]::OpenRead($file.FullName)
            try {
                $input.CopyTo($entryStream)
            } finally {
                $input.Dispose()
            }
        } finally {
            $entryStream.Dispose()
        }
    }
} finally {
    $archive.Dispose()
    $zipStream.Dispose()
}

Write-Host ""
Write-Host "Build finished:" -ForegroundColor Green
Get-ChildItem $Dist | ForEach-Object { Write-Host ("  " + $_.FullName) }
