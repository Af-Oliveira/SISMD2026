<#
.SYNOPSIS
    Runs the BenchmarkRunner under the Parallel GC (throughput collector).
.DESCRIPTION
    Issue #9 — captures metrics under -XX:+UseParallelGC and writes a
    `-Xlog:gc*` log file. Run from the project root.
#>
param(
    [int]$HeapMB     = 2048,
    [string]$ResultsDir = "results\parallel",
    [string]$LogFile    = "gc-tuning\logs\parallel.log"
)
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $ResultsDir         | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

Write-Host "── Parallel GC sweep (heap=${HeapMB}M) → $ResultsDir ──" -ForegroundColor Cyan
java `
    "-XX:+UseParallelGC" `
    "-Xms${HeapMB}m" "-Xmx${HeapMB}m" `
    "-Xlog:gc*:file=${LogFile}:utctime,level,tags" `
    -cp target\classes `
    pt.isep.sismd.histogram.BenchmarkRunner $ResultsDir
