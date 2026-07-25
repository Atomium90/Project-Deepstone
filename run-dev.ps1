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
#>

$root = $PSScriptRoot
$backendPort = 8080
$frontendPort = 5173

# Passed as a single pre-quoted string, not a -ArgumentList array: Start-Process joins array
# elements with plain spaces and does NOT quote ones that contain spaces (e.g. the title, or
# this path - "Project DeepStone" itself has one), so wt's own parser would otherwise see
# "Deepstone backend" as two separate tokens and misread the second word as the program to
# launch. Quoting the whole command line ourselves avoids that.
Write-Host "Starting backend (sbt run) - server will listen on ws://localhost:$backendPort/ws"
Start-Process wt -ArgumentList "-w 0 new-tab --title `"Deepstone backend`" -d `"$root\deepstone-backend`" powershell -NoExit -Command `"sbt run`""

# Give the first tab a moment to create the window before targeting it again.
Start-Sleep -Seconds 1

Write-Host "Starting frontend (npm run dev) - Vite will listen on http://localhost:$frontendPort"
Start-Process wt -ArgumentList "-w 0 new-tab --title `"Deepstone frontend`" -d `"$root\frontend`" powershell -NoExit -Command `"npm run dev`""

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
