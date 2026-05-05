<#
.SYNOPSIS
    Runs the BenchmarkRunner under the Serial GC.
.DESCRIPTION
    Issue #9 — captures execution time, heap, GC count and GC time under
    -XX:+UseSerialGC, plus a unified `-Xlog:gc*` log file for pause-time
    evidence.

    Run from the project root:
        gc-tuning\run_serial_gc.ps1
#>
param(
    [int]$HeapMB     = 2048,
    [string]$ResultsDir = "results\serial",
    [string]$LogFile    = "gc-tuning\logs\serial.log"
)
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $ResultsDir         | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

Write-Host "── Serial GC sweep (heap=${HeapMB}M) → $ResultsDir ──" -ForegroundColor Cyan
java `
    "-XX:+UseSerialGC" `
    "-Xms${HeapMB}m" "-Xmx${HeapMB}m" `
    "-Xlog:gc*:file=${LogFile}:utctime,level,tags" `
    -cp target\classes `
    pt.isep.sismd.histogram.BenchmarkRunner $ResultsDir
