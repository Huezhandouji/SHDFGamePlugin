# t28/t29 verification evidence collector (read-only on sources; writes only build/ output)
# Usage (any shell, from anywhere):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-freeze.ps1          # evidence only
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-freeze.ps1 -Build   # + contract build
# NOTE: ASCII-only on purpose -- Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI and
#       would mangle non-ASCII literals.
param([switch]$Build)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot

function Fingerprint([string]$rel) {
  $p = Join-Path $root $rel
  if (-not (Test-Path $p)) { "{0,-62} <missing>" -f $rel; return }
  $sha   = (Get-FileHash $p -Algorithm SHA256).Hash
  $lines = [System.IO.File]::ReadAllLines($p).Length
  $mtime = (Get-Item $p).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
  "{0,-62} lines={1,-5} mtime={2}  sha256={3}" -f $rel, $lines, $mtime, $sha
}

Write-Output "=== [1] freeze fingerprints (line count = ReadAllLines) ==="
$targets = @(
  'src\main\java\com\sHDFGamePlugin\phase\PlayingPhase.java',
  'src\main\java\com\sHDFGamePlugin\phase\FinishedPhase.java',
  'src\main\java\com\sHDFGamePlugin\phase\WaitingPhase.java',
  'src\main\java\com\sHDFGamePlugin\phase\RoleSelectingPhase.java',
  'src\main\java\com\sHDFGamePlugin\phase\playing\SectorProgressController.java',
  'src\main\java\com\sHDFGamePlugin\phase\playing\IntermissionController.java',
  'src\main\java\com\sHDFGamePlugin\phase\playing\MatchDisplayBridge.java',
  'src\main\java\com\sHDFGamePlugin\phase\playing\MatchSessionState.java',
  'src\main\java\com\sHDFGamePlugin\phase\playing\PlayingItemFactory.java',
  'src\main\java\com\sHDFGamePlugin\phase\playing\BombInteractionController.java',
  'src\main\java\com\sHDFGamePlugin\phase\playing\DeathHandler.java',
  'src\main\java\com\sHDFGamePlugin\phase\playing\DeploymentController.java',
  'src\main\java\com\sHDFGamePlugin\domain\sector\SectorManager.java',
  'src\main\java\com\sHDFGamePlugin\domain\spawn\SpawnManager.java',
  'src\main\java\com\sHDFGamePlugin\infrastructure\RoleBridge.java',
  'src\main\java\com\sHDFGamePlugin\infrastructure\config\ConfigManager.java',
  'src\main\java\com\sHDFGamePlugin\infrastructure\config\MapConfig.java',
  'src\main\java\com\sHDFGamePlugin\command\DebugCommand.java',
  'src\main\java\com\sHDFGamePlugin\command\ShdfGameCommand.java',
  'src\main\resources\config.yml',
  'src\main\resources\maps.yml',
  'src\main\resources\plugin.yml'
)
$targets | ForEach-Object { Fingerprint $_ }

Write-Output ""
Write-Output "=== [2] in-flight write check (10 newest under src + build.gradle.kts) ==="
Get-ChildItem (Join-Path $root 'src'),(Join-Path $root 'build.gradle.kts') -Recurse -File -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending | Select-Object -First 10 |
  ForEach-Object { "{0}  {1}" -f $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), $_.FullName.Substring($root.Length+1) }

Write-Output ""
Write-Output "=== [3] new keys in source resources ==="
foreach ($f in 'src\main\resources\maps.yml','src\main\resources\config.yml') {
  $hits = Select-String -Path (Join-Path $root $f) -Pattern 'sector_advance_interval|finish_display_time'
  if ($hits) { $hits | ForEach-Object { "{0}:{1}: {2}" -f $f, $_.LineNumber, $_.Line.Trim() } }
  else { "{0}: no hit" -f $f }
}

Write-Output ""
Write-Output "=== [4] server side (read-only) ==="
$srv = 'C:\Users\ROG\Desktop\paper1.21.11\plugins\SHDFGamePlugin'
foreach ($f in 'config.yml','maps.yml') {
  $p = Join-Path $srv $f
  if (Test-Path $p) {
    $hits = Select-String -Path $p -Pattern 'sector_advance_interval|finish_display_time'
    "{0}: {1} bytes mtime={2} new-key-hits={3}" -f $f, (Get-Item $p).Length, (Get-Item $p).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), (($hits|Measure-Object).Count)
  }
}
$dep = 'C:\Users\ROG\Desktop\paper1.21.11\plugins\SHDFGamePlugin-1.0-SNAPSHOT.jar'
if (Test-Path $dep) { "deployed jar: {0} bytes mtime={1} sha256={2}" -f (Get-Item $dep).Length, (Get-Item $dep).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), (Get-FileHash $dep -Algorithm SHA256).Hash }

if ($Build) {
  Write-Output ""
  Write-Output "=== [5] contract build ==="
  $env:GRADLE_USER_HOME    = Join-Path $root '.gradle-work'
  $env:GRADLE_RO_DEP_CACHE = "$env:USERPROFILE\.gradle\caches"
  $env:GRADLE_OPTS         = "-Dorg.gradle.vfs.watch=false"
  Push-Location $root
  foreach ($argSet in @(@('jar','--console=plain'), @('jar','--rerun-tasks','--console=plain'))) {
    $out = & .\gradlew @argSet 2>&1
    "--- gradlew $($argSet -join ' ') : EXIT CODE = $LASTEXITCODE ---"
    $out | Select-Object -Last 8
  }
  Pop-Location

  Write-Output ""
  Write-Output "=== [6] artifact identity and entries ==="
  $jar = Join-Path $root 'build\libs\SHDFGamePlugin-1.0-SNAPSHOT.jar'
  $ji = Get-Item $jar
  "jar: {0} bytes  mtime={1}  sha256={2}" -f $ji.Length, $ji.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), (Get-FileHash $jar -Algorithm SHA256).Hash
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $z = [System.IO.Compression.ZipFile]::OpenRead($jar)
  $names = $z.Entries | ForEach-Object { $_.FullName }
  "total entries: $($names.Count)  |  phase/playing: $(($names | Where-Object {$_ -like '*phase/playing/*'}).Count)  |  display: $(($names | Where-Object {$_ -like '*infrastructure/display/*'}).Count)"
  $names | Where-Object { $_ -match 'SectorProgressController|IntermissionController|MatchDisplayBridge|DebugCommand|FinishedPhase|PlayingItemFactory' } | Sort-Object
  "resources: " + (($names | Where-Object { $_ -match '^(plugin|config|maps)\.yml$' }) -join ', ')
  $z.Dispose()
}
