<#
.SYNOPSIS
    Runs the benchmark under all four GCs, then generates the comparison report.
.DESCRIPTION
    Issue #9 — orchestrator. Run from the project root:
        gc-tuning\run_all.ps1
    Optional:
        gc-tuning\run_all.ps1 -HeapMB 4096
#>
param([int]$HeapMB = 2048)
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path "$ScriptDir\.."
Set-Location $ProjectRoot

Write-Host "── Recompiling ──" -ForegroundColor Cyan
mvn -q compile

& "$ScriptDir\run_serial_gc.ps1"   -HeapMB $HeapMB
& "$ScriptDir\run_parallel_gc.ps1" -HeapMB $HeapMB
& "$ScriptDir\run_g1_gc.ps1"       -HeapMB $HeapMB
& "$ScriptDir\run_zgc.ps1"         -HeapMB $HeapMB

Write-Host "── Aggregating cross-GC comparison ──" -ForegroundColor Cyan
java -cp target\classes pt.isep.sismd.histogram.GcComparisonReport `
    results gc-tuning\comparison.md

Write-Host ""
Write-Host "Done. Inspect:" -ForegroundColor Green
Write-Host "  gc-tuning\comparison.md"
Write-Host "  gc-tuning\logs\*.log"
Write-Host "  results\<gc>\*.csv"
