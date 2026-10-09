<#
.SYNOPSIS
  Copies the third-party art and audio from the private assets repo into frontend/public.

.DESCRIPTION
  The art and audio the game uses are not stored in this repo (see CREDITS.md and README.md). They
  live in the private deepstone-licensed-assets repo, which mirrors the layout of frontend/public.
  Clone it next to this repo, then run this script from the repo root to put its sprites/ and
  audio/ folders in place before running the game, a build or the asset scripts.

  Only files that are missing or different are copied. Nothing is deleted unless -Prune is given,
  which also removes the files under frontend/public/sprites and audio that the private repo does
  not have (the game does not use them, see `npm run audit:assets` in frontend/).

  Use -WhatIf to see what would happen without changing anything.

.PARAMETER Source
  The folder of the private assets repo. Defaults to a deepstone-licensed-assets folder next to
  this repo.

.PARAMETER Destination
  Where the assets go. Defaults to frontend/public in this repo.

.PARAMETER Prune
  Also delete the files under Destination/sprites and Destination/audio that Source does not have.

.EXAMPLE
  .\sync-assets.ps1

  Copies what is missing or different.

.EXAMPLE
  .\sync-assets.ps1 -Prune -WhatIf

  Shows which files a prune would delete, and deletes nothing.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Source = (Join-Path (Split-Path $PSScriptRoot -Parent) "deepstone-licensed-assets"),
    [string]$Destination = (Join-Path $PSScriptRoot "frontend\public"),
    [switch]$Prune
)

$ErrorActionPreference = "Stop"
$folders = @("sprites", "audio")

foreach ($folder in $folders) {
    if (-not (Test-Path -LiteralPath (Join-Path $Source $folder))) {
        throw "No '$folder' folder in '$Source'. Clone the private deepstone-licensed-assets repo next to this one, or pass its location with -Source."
    }
}

function Get-RelativeFiles([string]$base, [string]$folder) {
    $root = Join-Path $base $folder
    if (-not (Test-Path -LiteralPath $root)) { return @() }
    Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
        $_.FullName.Substring($base.TrimEnd('\').Length + 1)
    }
}

function Test-SameContent([string]$a, [string]$b) {
    if (-not (Test-Path -LiteralPath $b)) { return $false }
    if ((Get-Item -LiteralPath $a).Length -ne (Get-Item -LiteralPath $b).Length) { return $false }
    (Get-FileHash -LiteralPath $a -Algorithm SHA256).Hash -eq (Get-FileHash -LiteralPath $b -Algorithm SHA256).Hash
}

$copied = 0
$unchanged = 0
$wanted = New-Object System.Collections.Generic.HashSet[string]

foreach ($folder in $folders) {
    foreach ($relative in Get-RelativeFiles $Source $folder) {
        [void]$wanted.Add($relative)
        $from = Join-Path $Source $relative
        $to = Join-Path $Destination $relative
        if (Test-SameContent $from $to) { $unchanged++; continue }
        if ($PSCmdlet.ShouldProcess($relative, "Copy")) {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $to) | Out-Null
            Copy-Item -LiteralPath $from -Destination $to -Force
        }
        $copied++
    }
}

$pruned = 0
if ($Prune) {
    foreach ($folder in $folders) {
        foreach ($relative in Get-RelativeFiles $Destination $folder) {
            if ($wanted.Contains($relative)) { continue }
            if ($PSCmdlet.ShouldProcess($relative, "Delete (not in the private repo)")) {
                Remove-Item -LiteralPath (Join-Path $Destination $relative)
            }
            $pruned++
        }
        if (-not $WhatIfPreference) {
            # Folders left empty by the prune.
            $root = Join-Path $Destination $folder
            Get-ChildItem -LiteralPath $root -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending | ForEach-Object {
                if (@(Get-ChildItem -LiteralPath $_.FullName -Force).Count -eq 0) { Remove-Item -LiteralPath $_.FullName }
            }
        }
    }
}

$verb = if ($WhatIfPreference) { "would copy" } else { "copied" }
$pruneVerb = if ($WhatIfPreference) { "would delete" } else { "deleted" }
Write-Host "Assets: $verb $copied, $unchanged already up to date$(if ($Prune) { ", $pruneVerb $pruned not in the private repo" })."
