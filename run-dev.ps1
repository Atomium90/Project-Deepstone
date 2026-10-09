<#
.SYNOPSIS
  Launches the Deepstone backend and frontend dev servers, then opens the game in your
  browser once both are ready.

.DESCRIPTION
  Convenience wrapper around the two commands from the README's "Getting started"
  section (sbt run / npm run dev), so you don't need to open two terminals by hand.
  Both servers open as tabs in the same Windows Terminal window (rather than two
  separate windows), so you don't lose track of either one.

  Once the backend (ws://localhost:8080) and frontend (http://localhost:5173) are both
  accepting connections, the game opens automatically in your default browser.

  To stop both servers at once, run stop-dev.ps1, rather than switching to each tab and
  stopping it individually.

  Pass --debug (-debug works too) to start the backend with its dev-only Debug Rooms tooling:
  the hub then lists every room converted into deepstone-backend/debug-rooms/ and loads one on
  click. Without it the game runs normally, with none of that tooling exposed.

.EXAMPLE
  .\run-dev.ps1

  Starts the game normally.

.EXAMPLE
  .\run-dev.ps1 --debug

  Starts the game with the Debug Rooms tooling, to test rooms converted from Tiled.
#>

$root = $PSScriptRoot
$backendPort = 8080
$frontendPort = 5173

# The only option is --debug. This script deliberately has no param block: PowerShell reads -debug
# on a script that declares parameters as its own common -Debug switch (and double-dash forms
# too), which would swallow the flag, whereas a plain script receives every spelling in $args.
$debugRooms = ($args -contains "--debug") -or ($args -contains "-debug")
$unknownArgs = @($args | Where-Object { $_ -and $_ -notin @("--debug", "-debug") })
if ($unknownArgs.Count -gt 0) {
    Write-Warning "Ignoring unknown arguments: $($unknownArgs -join ' ') (the only option is --debug)"
}
# The page is wired to the backend's port, so it is asked for explicitly: without --port the
# backend moves to the next free port when 8080 is taken, which the dev page would not follow.
$backendCommand = if ($debugRooms) { "sbt `"run --port $backendPort --debug`"" } else { "sbt `"run --port $backendPort`"" }

# Full path to whatever PowerShell host is running this script (Windows PowerShell 5.1's
# powershell.exe, or PowerShell 7+'s pwsh.exe) - not just the bare word "powershell". wt spawns
# tab commands in its own environment, and a bare "powershell" isn't guaranteed to resolve
# there (e.g. on a machine where only pwsh is installed) even though it resolves fine in an
# interactive shell - using the exact exe this script is already running under sidesteps that.
$shellExe = (Get-Process -Id $PID).Path

# On at least one dev machine, tabs spawned by wt don't end up with npm/sbt on PATH even
# though this script's own process sees them fine (likely a profile-loading quirk specific to
# how wt spawns child shells). Rather than fight three layers of command-line quoting trying
# to inject $env:PATH through wt's parser, write small launcher scripts to disk that set PATH
# explicitly and cd into place - a file path is one simple, unambiguous token to hand to wt,
# no nested escaping involved.
$launcherDir = Join-Path $env:TEMP "deepstone-dev-launchers"
New-Item -ItemType Directory -Force -Path $launcherDir | Out-Null
$escapedPath = $env:PATH -replace "'", "''"

$backendLauncher = Join-Path $launcherDir "backend.ps1"
@"
`$env:PATH = '$escapedPath'
Set-Location '$root\deepstone-backend'
$backendCommand
"@ | Set-Content -Path $backendLauncher -Encoding UTF8

$frontendLauncher = Join-Path $launcherDir "frontend.ps1"
@"
`$env:PATH = '$escapedPath'
Set-Location '$root\frontend'
npm run dev
"@ | Set-Content -Path $frontendLauncher -Encoding UTF8

Write-Host "Starting backend ($backendCommand) - server will listen on ws://localhost:$backendPort/ws"
if ($debugRooms) {
    Write-Host "Debug mode: the hub will list the rooms converted into deepstone-backend/debug-rooms/."
}
Start-Process wt -ArgumentList "-w 0 new-tab --title `"Deepstone backend`" `"$shellExe`" -NoExit -File `"$backendLauncher`""

# Give the first tab a moment to create the window before targeting it again.
Start-Sleep -Seconds 1

Write-Host "Starting frontend (npm run dev) - Vite will listen on http://localhost:$frontendPort"
Start-Process wt -ArgumentList "-w 0 new-tab --title `"Deepstone frontend`" `"$shellExe`" -NoExit -File `"$frontendLauncher`""

Write-Host "Both dev servers are starting as tabs in the same Windows Terminal window."
Write-Host "Waiting for both to come up..."

function Wait-ForPort {
    param([int]$Port, [int]$TimeoutSeconds = 120)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-NetConnection -ComputerName localhost -Port $Port -WarningAction SilentlyContinue -InformationLevel Quiet) {
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

$backendReady = Wait-ForPort -Port $backendPort
$frontendReady = Wait-ForPort -Port $frontendPort

if ($backendReady -and $frontendReady) {
    Write-Host "Both servers are up - opening http://localhost:$frontendPort"
    Start-Process "http://localhost:$frontendPort"
} else {
    Write-Warning "Timed out waiting for the servers to come up (backend ready: $backendReady, frontend ready: $frontendReady). Check the tabs for errors."
}
