[CmdletBinding()]
param(
    [switch]$DeviceTests
)

$ErrorActionPreference = "Stop"

function Invoke-RequiredCommand {
    param(
        [string]$Heading,
        [scriptblock]$Command
    )

    Write-Host "`n== $Heading =="
    & $Command
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

$insideGitWorkTree = (git rev-parse --is-inside-work-tree 2>$null) -eq 'true'
if ($insideGitWorkTree) {
    Invoke-RequiredCommand "Checking diff whitespace" { git diff --check }
}
Invoke-RequiredCommand "Running JVM tests" { .\gradlew.bat test }
Invoke-RequiredCommand "Building debug APK" { .\gradlew.bat assembleDebug }

if ($DeviceTests) {
    Write-Host "`n== Connected instrumentation tests =="
    Write-Warning "connected instrumentation tests may reinstall/uninstall the app and erase local app data on the target device."

    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    $adbPath = if ($adbCommand) {
        $adbCommand.Source
    } else {
        $sdkAdbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (Test-Path $sdkAdbPath) { $sdkAdbPath } else { $null }
    }

    if (-not $adbPath) {
        Write-Error "Android Debug Bridge (adb) was not found."
        exit 1
    } else {
        $deviceLines = & $adbPath devices
        if ($LASTEXITCODE -ne 0) {
            Write-Error "adb could not list connected devices."
            exit $LASTEXITCODE
        } elseif ($deviceLines | Where-Object { $_ -match "^\S+\s+device$" }) {
            Invoke-RequiredCommand "Running connected Android tests" { .\gradlew.bat connectedDebugAndroidTest }
        } else {
            Write-Error "No Android device or emulator is connected."
            exit 1
        }
    }
} else {
    Write-Host "`n== Connected instrumentation tests =="
    Write-Host "SKIPPED: -DeviceTests was not specified."
}

if ($insideGitWorkTree) {
    Invoke-RequiredCommand "Final Git status" { git status --short --branch }
}

Write-Host "`nVerification completed successfully."
