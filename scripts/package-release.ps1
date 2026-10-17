param(
  [string]$Version = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $root "extension\manifest.json"
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $Version) { $Version = [string]$manifest.version }
if (-not $Version) { throw "Version is missing." }

$dist = Join-Path $root "dist"
$stage = Join-Path $dist (".stage-" + $Version)
$fullName = "gpt-audio-dictation-" + $Version
$fullRoot = Join-Path $stage $fullName
$fullZip = Join-Path $dist ($fullName + "-windows.zip")
$extensionZip = Join-Path $dist ("gpt-audio-dictation-extension-" + $Version + ".zip")

New-Item -ItemType Directory -Path $dist -Force | Out-Null
$distFull = [System.IO.Path]::GetFullPath($dist)
$stageFull = [System.IO.Path]::GetFullPath($stage)
if (-not $stageFull.StartsWith($distFull + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Unsafe staging path."
}
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $fullRoot -Force | Out-Null

$rootFiles = @(
  "README.md",
  "LICENSE",
  "CHANGELOG.md",
  "CONTRIBUTING.md",
  "SECURITY.md",
  "package.json",
  ".gitignore",
  ".gitattributes",
  "install.bat",
  "open-chatgpt.bat",
  "choose-save-folder.bat",
  "create-desktop-shortcut.bat"
)
foreach ($relative in $rootFiles) {
  $source = Join-Path $root $relative
  if (Test-Path -LiteralPath $source) {
    Copy-Item -LiteralPath $source -Destination (Join-Path $fullRoot $relative) -Force
  }
}

foreach ($directory in @("extension", "tools", "docs", "scripts", ".github")) {
  $source = Join-Path $root $directory
  if (Test-Path -LiteralPath $source) {
    Copy-Item -LiteralPath $source -Destination (Join-Path $fullRoot $directory) -Recurse -Force
  }
}

$writerStage = Join-Path $fullRoot "writer"
New-Item -ItemType Directory -Path $writerStage -Force | Out-Null
foreach ($file in @("writer.mjs", "start-writer.bat", "install-autostart.bat", "remove-autostart.bat")) {
  Copy-Item -LiteralPath (Join-Path $root ("writer\" + $file)) -Destination (Join-Path $writerStage $file) -Force
}
Copy-Item -LiteralPath (Join-Path $root "writer\config.example.json") -Destination (Join-Path $writerStage "config.json") -Force
Copy-Item -LiteralPath (Join-Path $root "writer\config.example.json") -Destination (Join-Path $writerStage "config.example.json") -Force

if (Test-Path -LiteralPath $fullZip) { Remove-Item -LiteralPath $fullZip -Force }
if (Test-Path -LiteralPath $extensionZip) { Remove-Item -LiteralPath $extensionZip -Force }

Compress-Archive -Path $fullRoot -DestinationPath $fullZip -CompressionLevel Optimal
Compress-Archive -Path (Join-Path $root "extension\*") -DestinationPath $extensionZip -CompressionLevel Optimal

$fullResult = Get-Item -LiteralPath $fullZip
$extensionResult = Get-Item -LiteralPath $extensionZip
[pscustomobject]@{
  version = $Version
  fullPackage = $fullResult.FullName
  fullBytes = $fullResult.Length
  extensionPackage = $extensionResult.FullName
  extensionBytes = $extensionResult.Length
} | ConvertTo-Json

Remove-Item -LiteralPath $stage -Recurse -Force
