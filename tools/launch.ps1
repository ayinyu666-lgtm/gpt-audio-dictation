# GPT Auto Dictation - launcher
#
# Why this exists: a desktop shortcut that only opens ChatGPT looks broken when the
# extension has not been loaded yet. So this checks whether the extension is really
# present, and if it is not, it puts the one-time install step in front of the user
# instead of pretending the launch succeeded.
#
# This file is deliberately PURE ASCII. Windows PowerShell 5.1 reads .ps1 files as
# ANSI unless they carry a UTF-8 BOM, and non-ASCII comments silently corrupt the
# parse. All user-facing text therefore lives in launch-messages.txt (UTF-8), and
# that file is read with -Encoding UTF8 explicitly.
#
# Read-only with respect to the browser: it inspects preferences, never edits them.

param(
  [string]$UserDataDir = (Join-Path $env:LOCALAPPDATA 'Microsoft\Edge\User Data'),
  [switch]$CheckOnly,
  [switch]$NoOpen,
  [switch]$NoDialog,
  [switch]$Install,
  [switch]$ChooseRootOnly,
  [switch]$NoWriter
)

$ErrorActionPreference = 'SilentlyContinue'

$root      = Split-Path -Parent $PSScriptRoot
$extDir    = Join-Path $root 'extension'
$msgFile   = Join-Path $PSScriptRoot 'launch-messages.txt'
$writerDir = Join-Path $root 'writer'
$writerBat = Join-Path $writerDir 'start-writer.bat'
$writerCfg = Join-Path $writerDir 'config.json'
$url       = 'https://chatgpt.com/'
$extName   = 'gpt-auto-dictation'

# The local save service is optional, but starting it here means the panel shows
# "online" the moment the user ticks the save box, instead of them having to remember
# a second file. It is idempotent: writer.mjs exits immediately if the port is taken.
function Get-WriterPort {
  $port = 8799
  if (Test-Path $writerCfg) {
    try { $port = [int]((Get-Content $writerCfg -Raw -Encoding UTF8 | ConvertFrom-Json).port) } catch { }
  }
  if (-not $port -or $port -lt 1) { $port = 8799 }
  return $port
}

function Test-Writer([int]$port) {
  try {
    $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri ("http://127.0.0.1:$port/ping")
    return ($r.StatusCode -eq 200)
  } catch { return $false }
}

function Ensure-WriterRoot([bool]$Force = $false) {
  if (-not (Test-Path $writerCfg)) { return '' }
  try { $cfg = Get-Content $writerCfg -Raw -Encoding UTF8 | ConvertFrom-Json } catch { return '' }
  if (-not $Force -and $cfg.rootDir -and [string]$cfg.rootDir.Trim()) { return [string]$cfg.rootDir }
  try {
    Add-Type -AssemblyName System.Windows.Forms | Out-Null
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    $dialog.Description = "Choose a folder for GPT auto-dictation transcripts"
    $dialog.ShowNewFolderButton = $true
    if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return '' }
    $cfg.rootDir = $dialog.SelectedPath
    $cfg | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $writerCfg -Encoding UTF8
    return [string]$dialog.SelectedPath
  } catch { return '' }
}


function Get-Messages {
  $map = @{}
  if (-not (Test-Path $msgFile)) { return $map }
  $key = ''
  $buf = New-Object System.Collections.ArrayList
  foreach ($line in (Get-Content $msgFile -Encoding UTF8)) {
    if ($line -match '^\[(.+)\]\s*$') {
      if ($key) { $map[$key] = ($buf -join "`n").Trim() }
      $key = $Matches[1]
      $buf.Clear() | Out-Null
    } elseif ($key) {
      [void]$buf.Add($line)
    }
  }
  if ($key) { $map[$key] = ($buf -join "`n").Trim() }
  return $map
}

function Find-Edge {
  $cands = @(
    (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe'),
    (Join-Path $env:ProgramFiles 'Microsoft\Edge\Application\msedge.exe'),
    (Join-Path $env:LOCALAPPDATA 'Microsoft\Edge\Application\msedge.exe')
  )
  foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return $c } }
  $k = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe'
  $ap = Get-ItemProperty $k -ErrorAction SilentlyContinue
  if ($ap -and $ap.'(default)' -and (Test-Path $ap.'(default)')) { return $ap.'(default)' }
  return $null
}

# Installed in ANY profile counts as installed. Prefer the parsed JSON so the
# enabled/disabled state can be reported; fall back to a literal match when the file
# cannot be parsed.
function Get-ExtensionState {
  $found = @{ Installed = $false; Enabled = $false; Profile = ''; Id = '' }
  if (-not $UserDataDir -or -not (Test-Path $UserDataDir)) { return $found }

  $profiles = New-Object System.Collections.ArrayList
  [void]$profiles.Add((Join-Path $UserDataDir 'Default'))
  Get-ChildItem $UserDataDir -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'Profile*' } |
    ForEach-Object { [void]$profiles.Add($_.FullName) }

  foreach ($p in ($profiles | Select-Object -Unique)) {
    foreach ($file in @('Secure Preferences', 'Preferences')) {
      $fp = Join-Path $p $file
      if (-not (Test-Path $fp)) { continue }
      $raw = Get-Content $fp -Raw -Encoding UTF8
      if (-not $raw -or $raw -notmatch $extName) { continue }

      $parsed = $null
      try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $null }
      if ($parsed -and $parsed.extensions -and $parsed.extensions.settings) {
        foreach ($prop in $parsed.extensions.settings.PSObject.Properties) {
          $path = [string]$prop.Value.path
          if ($path -and ($path -like "*$extName*")) {
            $found.Installed = $true
            $found.Profile = (Split-Path $p -Leaf)
            $found.Id = $prop.Name
            $found.Enabled = ($null -eq $prop.Value.state) -or ([int]$prop.Value.state -eq 1)
            return $found
          }
        }
      }
      $found.Installed = $true
      $found.Enabled = $true
      $found.Profile = (Split-Path $p -Leaf)
      return $found
    }
  }
  return $found
}

function Show-Message([string]$text, [string]$title) {
  if ($NoDialog) {
    Write-Host ''
    Write-Host ('  ' + $title)
    Write-Host ('  ' + ('-' * [Math]::Max(20, $title.Length)))
    foreach ($l in ($text -split "`n")) { Write-Host ('  ' + $l) }
    Write-Host ''
    return
  }
  try {
    Add-Type -AssemblyName System.Windows.Forms | Out-Null
    [System.Windows.Forms.MessageBox]::Show($text, $title, 'OK', 'Warning') | Out-Null
  } catch {
    Write-Host $text
  }
}

$edge    = Find-Edge
$state   = Get-ExtensionState
$msgs    = Get-Messages

$needInstall = $Install -or (-not $state.Installed)
$needEnable  = (-not $needInstall) -and (-not $state.Enabled)

$writerPort = Get-WriterPort
$writerUp   = Test-Writer $writerPort

if ($ChooseRootOnly) {
  $selectedRoot = Ensure-WriterRoot $true
  if ($selectedRoot -and $writerUp) {
    try {
      $body = @{ rootDir = $selectedRoot } | ConvertTo-Json
      Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Method Post -ContentType 'application/json' -Body $body -Uri ("http://127.0.0.1:$writerPort/set-root") | Out-Null
    } catch { }
  }
  if ($selectedRoot) {
    Write-Host ('Save folder: ' + $selectedRoot)
  } else {
    Write-Host 'Folder selection canceled.'
  }
  exit 0
}

if ($CheckOnly) {
  [pscustomobject]@{
    edge        = $edge
    userDataDir = $UserDataDir
    installed   = [bool]$state.Installed
    enabled     = [bool]$state.Enabled
    profile     = $state.Profile
    id          = $state.Id
    extDir      = $extDir
    needInstall = [bool]$needInstall
    needEnable  = [bool]$needEnable
    writerPort  = $writerPort
    writerUp    = [bool]$writerUp
    writerBat   = $writerBat
  } | ConvertTo-Json
  exit 0
}

if (-not $NoWriter -and -not $CheckOnly) {
  $selectedRoot = Ensure-WriterRoot
  if ($writerUp -and $selectedRoot) {
    try {
      $body = @{ rootDir = $selectedRoot } | ConvertTo-Json
      Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Method Post -ContentType 'application/json' -Body $body -Uri ("http://127.0.0.1:$writerPort/set-root") | Out-Null
    } catch { }
  }
}

if ($needInstall) {
  $text = $msgs['need_install']
  if (-not $text) { $text = $msgs['need_install_console'] -replace '\{EXTDIR\}', $extDir }
  $text = $text -replace '\{EXTDIR\}', $extDir
  Show-Message -text $text -title 'GPT Auto Dictation - one step left'
  try { Set-Clipboard -Value $extDir } catch { }
  if (-not $NoOpen -and $edge) { Start-Process -FilePath $edge -ArgumentList 'edge://extensions' }
} elseif ($needEnable) {
  $text = $msgs['disabled']
  if (-not $text) { $text = $msgs['disabled_console'] }
  Show-Message -text $text -title 'GPT Auto Dictation - extension is disabled'
  if (-not $NoOpen -and $edge) { Start-Process -FilePath $edge -ArgumentList 'edge://extensions' }
}

# Bring the local save service up if it is not already listening. Minimized, not
# hidden: its console is the only place its log is visible, and the user asked for
# this to be a thing they can see and close.
if (-not $NoWriter -and -not $writerUp -and (Test-Path $writerBat)) {
  Start-Process -FilePath $writerBat -WindowStyle Minimized
}

if (-not $NoOpen -and $edge) { Start-Process -FilePath $edge -ArgumentList $url }
