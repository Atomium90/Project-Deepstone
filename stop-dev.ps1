<#
.SYNOPSIS
  Stops the Deepstone backend and frontend dev servers started by run-dev.ps1.

.DESCRIPTION
  Finds whatever process is actually listening on the backend (8080) and frontend
  (5173) ports and kills it directly, instead of switching to each terminal tab and
  stopping it by hand. This is needed because sbt/npm are wrapper processes that don't
  reliably forward Ctrl+C to the JVM/Vite process they spawn - killing the port's
  listener is what actually frees it.
#>

$ports = @(8080, 5173)
$stoppedAny = $false

foreach ($port in $ports) {
    $processIds = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique

    foreach ($processId in $processIds) {
        $proc = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "Stopping process on port $port : $($proc.ProcessName) (PID $processId)"
            Stop-Process -Id $processId -Force
            $stoppedAny = $true
        }
    }
}

if ($stoppedAny) {
    Write-Host "Done."
} else {
    Write-Host "Nothing was listening on ports $($ports -join ', ') - nothing to stop."
}
