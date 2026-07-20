<#
.SYNOPSIS
  Launches the Deepstone backend and frontend dev servers, each in its own window.

.DESCRIPTION
  Convenience wrapper around the two commands from the README's "Getting started"
  section (sbt run / npm run dev), so you don't need to open two terminals by hand.
  Each server keeps its own window so you can read its logs and stop it (Ctrl+C)
  independently of the other.
#>

$root = $PSScriptRoot

Write-Host "Starting backend (sbt run) - server will listen on ws://localhost:8080/ws"
Start-Process powershell -ArgumentList @(
    "-NoExit", "-Command",
    "Set-Location '$root\deepstone-backend'; sbt run"
)

Write-Host "Starting frontend (npm run dev) - Vite will listen on http://localhost:5173"
Start-Process powershell -ArgumentList @(
    "-NoExit", "-Command",
    "Set-Location '$root\frontend'; npm run dev"
)

Write-Host "Both dev servers are launching in separate windows. Close either window (or Ctrl+C inside it) to stop it."
