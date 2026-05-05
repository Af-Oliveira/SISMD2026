<#
.SYNOPSIS
    Runs the BenchmarkRunner under ZGC (low-pause concurrent collector).
.DESCRIPTION
    Issue #9 — captures metrics under -XX:+UseZGC and writes a
    `-Xlog:gc*` log file. Run from the project root.

    ZGC is production-ready in JDK 15+; in 17 LTS no
    -XX:+UnlockExperimentalVMOptions flag is needed.
#>
param(
    [int]$HeapMB     = 2048,
    [string]$ResultsDir = "results\zgc",
    [string]$LogFile    = "gc-tuning\logs\zgc.log"
)
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $ResultsDir         | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

Write-Host "── ZGC sweep (heap=${HeapMB}M) → $ResultsDir ──" -ForegroundColor Cyan
java `
    "-XX:+UseZGC" `
    "-Xms${HeapMB}m" "-Xmx${HeapMB}m" `
    "-Xlog:gc*:file=${LogFile}:utctime,level,tags" `
    -cp target\classes `
    pt.isep.sismd.histogram.BenchmarkRunner $ResultsDir
