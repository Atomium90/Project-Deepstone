<#
.SYNOPSIS
  Builds a distributable Deepstone package: the frontend's static build baked into the
  backend, packaged as a single runnable zip via sbt-native-packager.

.DESCRIPTION
  1. Builds the frontend (npm run build -> frontend/dist/).
  2. Copies frontend/dist/* into deepstone-backend/src/main/resources/static/ (regenerated
     every run - never hand-edited, gitignored).
  3. Packages the backend (sbt Universal/packageBin), which bundles those static files onto
     the classpath alongside the server JAR.
  4. Prints the path to the resulting zip.

  The output zip contains bin/deepstone-backend(.bat) launcher scripts - extract it anywhere
  and run that script to start the server, then open http://localhost:8080 in a browser.

  Whatever is currently in frontend/public/ gets built in as-is, including the two licensed
  assets that are gitignored from the repo (player sprites, boss music) - see CREDITS.md.
#>

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$staticDir = Join-Path $root "deepstone-backend\src\main\resources\static"

Write-Host "Building frontend..."
Push-Location (Join-Path $root "frontend")
try {
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

Write-Host "Staging frontend build into backend resources..."
if (Test-Path $staticDir) {
    Remove-Item -Recurse -Force $staticDir
}
New-Item -ItemType Directory -Force -Path $staticDir | Out-Null
Copy-Item -Recurse -Path (Join-Path $root "frontend\dist\*") -Destination $staticDir

Write-Host "Packaging backend..."
Push-Location (Join-Path $root "deepstone-backend")
try {
    sbt "Universal/packageBin"
    if ($LASTEXITCODE -ne 0) { throw "sbt Universal/packageBin failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

$zip = Get-ChildItem -Path (Join-Path $root "deepstone-backend\target\universal") -Filter "*.zip" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($zip) {
    Write-Host "`nBuild complete: $($zip.FullName)"
} else {
    Write-Warning "Packaging finished but no zip was found under target/universal - check the sbt output above."
}
