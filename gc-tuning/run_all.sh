#!/usr/bin/env bash
# Issue #9 — orchestrator. Runs the benchmark under all four GCs, then
# generates the cross-GC comparison report. Run from the project root.
#
# Usage:
#   bash gc-tuning/run_all.sh
#   HEAP_MB=4096 bash gc-tuning/run_all.sh
set -euo pipefail

HEAP_MB="${HEAP_MB:-2048}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "── Recompiling ──"
mvn -q compile

HEAP_MB="$HEAP_MB" bash "$SCRIPT_DIR/run_serial_gc.sh"
HEAP_MB="$HEAP_MB" bash "$SCRIPT_DIR/run_parallel_gc.sh"
HEAP_MB="$HEAP_MB" bash "$SCRIPT_DIR/run_g1_gc.sh"
HEAP_MB="$HEAP_MB" bash "$SCRIPT_DIR/run_zgc.sh"

echo "── Aggregating cross-GC comparison ──"
java -cp target/classes pt.isep.sismd.histogram.GcComparisonReport \
    results gc-tuning/comparison.md

echo
echo "Done. Inspect:"
echo "  gc-tuning/comparison.md"
echo "  gc-tuning/logs/*.log"
echo "  results/<gc>/*.csv"
