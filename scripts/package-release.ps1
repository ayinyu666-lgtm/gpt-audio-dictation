param(
  [string]$Version = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $root "extension\manifest.json"
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $Version) { $Version = [string]$manifest.version }
if (-not $Version) { throw "Version is missing." }

$androidGradle = Get-Content -LiteralPath (Join-Path $root "mobile\app\build.gradle") -Raw -Encoding UTF8
$androidVersion = [regex]::Match($androidGradle, "versionName\s+'([^']+)'").Groups[1].Value
$androidVersionCode = [regex]::Match($androidGradle, "versionCode\s+(\d+)").Groups[1].Value
if (-not $androidVersion) { throw "Android versionName is missing." }
if (-not $androidVersionCode) { throw "Android versionCode is missing." }

$dist = Join-Path $root "dist"
$stage = Join-Path $dist (".stage-" + $Version)
$fullName = "gpt-audio-dictation-" + $Version
$fullRoot = Join-Path $stage $fullName
$fullZip = Join-Path $dist ($fullName + "-windows.zip")
$extensionZip = Join-Path $dist ("gpt-audio-dictation-extension-" + $Version + ".zip")
$androidZip = Join-Path $dist ("gpt-audio-dictation-mobile-source-" + $Version + ".zip")
$androidApk = Join-Path $dist ("gpt-audio-dictation-android-" + $androidVersion + ".apk")

New-Item -ItemType Directory -Path $dist -Force | Out-Null
$distFull = [System.IO.Path]::GetFullPath($dist)
$stageFull = [System.IO.Path]::GetFullPath($stage)
if (-not $stageFull.StartsWith($distFull + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Unsafe staging path."
}
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $fullRoot -Force | Out-Null

$rootFiles = @(
  "README.md", "LICENSE", "CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md",
  "package.json", ".gitignore", ".gitattributes", "install.bat",
  "open-chatgpt.bat", "choose-save-folder.bat", "create-desktop-shortcut.bat"
)
foreach ($relative in $rootFiles) {
  $source = Join-Path $root $relative
  if (Test-Path -LiteralPath $source) {
    Copy-Item -LiteralPath $source -Destination (Join-Path $fullRoot $relative) -Force
  }
}

foreach ($directory in @("extension", "scripts")) {
  $source = Join-Path $root $directory
  if (Test-Path -LiteralPath $source) {
    Copy-Item -LiteralPath $source -Destination (Join-Path $fullRoot $directory) -Recurse -Force
  }
}
$toolsStage = Join-Path $fullRoot "tools"
New-Item -ItemType Directory -Path $toolsStage -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root "tools\mobile-archive") -Destination (Join-Path $toolsStage "mobile-archive") -Recurse -Force

$publicDocs = Join-Path $fullRoot "docs"
New-Item -ItemType Directory -Path $publicDocs -Force | Out-Null
foreach ($file in @("ARCHITECTURE.md", "ARCHIVE-INSTALL-CARD.md")) {
  $source = Join-Path $root ("docs\" + $file)
  if (Test-Path -LiteralPath $source) {
    Copy-Item -LiteralPath $source -Destination (Join-Path $publicDocs $file) -Force
  }
}

$mobileStage = Join-Path $fullRoot "mobile"
New-Item -ItemType Directory -Path $mobileStage -Force | Out-Null
foreach ($file in @("README.md", "DESIGN-calibration-profile.md", "build.gradle", "settings.gradle",
                    "gradle.properties", "build.sh", "run-host-tests.sh", ".gitignore")) {
  $source = Join-Path $root ("mobile\" + $file)
  if (Test-Path -LiteralPath $source) {
    Copy-Item -LiteralPath $source -Destination (Join-Path $mobileStage $file) -Force
  }
}
Copy-Item -LiteralPath (Join-Path $root "mobile\app") -Destination (Join-Path $mobileStage "app") -Recurse -Force

$writerStage = Join-Path $fullRoot "writer"
New-Item -ItemType Directory -Path $writerStage -Force | Out-Null
foreach ($file in @("writer.mjs", "start-writer.bat", "install-autostart.bat", "remove-autostart.bat")) {
  Copy-Item -LiteralPath (Join-Path $root ("writer\" + $file)) -Destination (Join-Path $writerStage $file) -Force
}
Copy-Item -LiteralPath (Join-Path $root "writer\config.example.json") -Destination (Join-Path $writerStage "config.json") -Force
Copy-Item -LiteralPath (Join-Path $root "writer\config.example.json") -Destination (Join-Path $writerStage "config.example.json") -Force

foreach ($relative in @("mobile\.gradle", "mobile\build", "mobile\app\build", "mobile\dist", "writer\logs")) {
  $path = Join-Path $fullRoot $relative
  if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}

$forbidden = Get-ChildItem -LiteralPath $fullRoot -Recurse -File | Where-Object {
  $_.Name -in @("config.json.bak", "telemetry.jsonl") -or
  $_.FullName -match '(\.test-edge-profile|/tmp/|\\tmp\\|/analysis/|\\analysis\\|/live-|\\live-)'
}
if ($forbidden) { throw "Private artifact found in release staging: " + (($forbidden | ForEach-Object FullName) -join ", ") }

$textExtensions = @(".md", ".json", ".mjs", ".js", ".java", ".bat", ".cmd", ".ps1", ".sh", ".yml", ".yaml", ".xml", ".gradle", ".properties", ".txt", ".py")
$privateTextPatterns = @(
  '(?i)[A-Z]:[\\/]+Users[\\/]+(?!<|%)[^\\/]+[\\/]+(?:Desktop|Documents|Downloads|OneDrive|AppData)[\\/]',
  '(?i)[A-Z]:[\\/]+AI[\\/]',
  '(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b',
  '\bgh[pousr]_[A-Za-z0-9_]{20,}\b',
  '\bgithub_pat_[A-Za-z0-9_]{20,}\b'
)
$privateText = foreach ($item in (Get-ChildItem -LiteralPath $fullRoot -Recurse -File | Where-Object { $textExtensions -contains $_.Extension.ToLowerInvariant() })) {
  Select-String -LiteralPath $item.FullName -Pattern $privateTextPatterns -ErrorAction SilentlyContinue
}
if ($privateText) {
  throw "Personal path or credential-like text found in release staging: " + (($privateText | ForEach-Object { "$($_.Path):$($_.LineNumber)" }) -join ", ")
}

foreach ($file in @($fullZip, $extensionZip, $androidZip, $androidApk)) {
  if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
}

Compress-Archive -Path $fullRoot -DestinationPath $fullZip -CompressionLevel Optimal
Compress-Archive -Path (Join-Path $root "extension\*") -DestinationPath $extensionZip -CompressionLevel Optimal
Compress-Archive -Path $mobileStage -DestinationPath $androidZip -CompressionLevel Optimal

$apkSource = Join-Path $root "mobile\app\build\outputs\apk\debug\app-debug.apk"
$apkMetadataPath = Join-Path $root "mobile\app\build\outputs\apk\debug\output-metadata.json"
$apkBuilt = Test-Path -LiteralPath $apkSource
if (-not $apkBuilt) { throw "Android APK is missing. Build mobile/app/build/outputs/apk/debug/app-debug.apk before packaging." }
if (-not (Test-Path -LiteralPath $apkMetadataPath)) { throw "Android APK metadata is missing. Rebuild the Android app before packaging." }

$apkMetadata = Get-Content -LiteralPath $apkMetadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
$apkElement = @($apkMetadata.elements | Where-Object { $_.outputFile -eq "app-debug.apk" }) | Select-Object -First 1
if ($apkMetadata.applicationId -ne "org.gptdictation.assistant" -or -not $apkElement) {
  throw "Android build metadata does not match the mobile app."
}
if ([string]$apkElement.versionName -ne $androidVersion -or [string]$apkElement.versionCode -ne $androidVersionCode) {
  throw "APK version does not match mobile version $androidVersion ($androidVersionCode). Rebuild the Android app before packaging."
}
$androidInputs = @(
  Get-Item -LiteralPath (Join-Path $root "mobile\app\build.gradle")
  Get-Item -LiteralPath (Join-Path $root "mobile\build.gradle")
  Get-Item -LiteralPath (Join-Path $root "mobile\settings.gradle")
  Get-Item -LiteralPath (Join-Path $root "mobile\gradle.properties")
) + @(Get-ChildItem -LiteralPath (Join-Path $root "mobile\app\src") -Recurse -File)
$latestAndroidInput = $androidInputs | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
$apkArtifact = Get-Item -LiteralPath $apkSource
$metadataArtifact = Get-Item -LiteralPath $apkMetadataPath
if ($apkArtifact.LastWriteTimeUtc -lt $latestAndroidInput.LastWriteTimeUtc -or
    $metadataArtifact.LastWriteTimeUtc -lt $latestAndroidInput.LastWriteTimeUtc) {
  throw "Android APK is older than the mobile source. Rebuild before packaging."
}
Copy-Item -LiteralPath $apkSource -Destination $androidApk -Force

$fullResult = Get-Item -LiteralPath $fullZip
$extensionResult = Get-Item -LiteralPath $extensionZip
$androidResult = Get-Item -LiteralPath $androidZip
$apkResult = Get-Item -LiteralPath $androidApk
[pscustomobject]@{
  version = $Version
  androidVersion = $androidVersion
  fullPackage = $fullResult.FullName
  fullBytes = $fullResult.Length
  extensionPackage = $extensionResult.FullName
  extensionBytes = $extensionResult.Length
  androidSourcePackage = $androidResult.FullName
  androidSourceBytes = $androidResult.Length
  androidApk = if ($apkResult) { $apkResult.FullName } else { "" }
  androidApkBytes = if ($apkResult) { $apkResult.Length } else { 0 }
  androidApkBuilt = $apkBuilt
} | ConvertTo-Json

$checksumLines = foreach ($file in @($fullResult, $extensionResult, $androidResult, $apkResult | Where-Object { $_ })) {
  $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  "$hash  $($file.Name)"
}
$checksumPath = Join-Path $dist ("SHA256SUMS-" + $Version + ".txt")
[System.IO.File]::WriteAllText($checksumPath, (($checksumLines -join "`n") + "`n"), [System.Text.UTF8Encoding]::new($false))
Write-Output ("Checksums: " + $checksumPath)

Remove-Item -LiteralPath $stage -Recurse -Force
