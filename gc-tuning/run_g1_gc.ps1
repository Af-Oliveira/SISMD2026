<#
.SYNOPSIS
    Runs the BenchmarkRunner under G1 GC (Java 17 default).
.DESCRIPTION
    Issue #9 — captures metrics under -XX:+UseG1GC and writes a
    `-Xlog:gc*` log file. Run from the project root.
#>
param(
    [int]$HeapMB     = 2048,
    [string]$ResultsDir = "results\g1",
    [string]$LogFile    = "gc-tuning\logs\g1.log"
)
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $ResultsDir         | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

Write-Host "── G1 GC sweep (heap=${HeapMB}M) → $ResultsDir ──" -ForegroundColor Cyan
java `
    "-XX:+UseG1GC" `
    "-Xms${HeapMB}m" "-Xmx${HeapMB}m" `
    "-Xlog:gc*:file=${LogFile}:utctime,level,tags" `
    -cp target\classes `
    pt.isep.sismd.histogram.BenchmarkRunner $ResultsDir
