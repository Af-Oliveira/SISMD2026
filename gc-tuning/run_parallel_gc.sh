#!/usr/bin/env bash
# Issue #9 — Parallel GC sweep. Run from the project root.
set -euo pipefail

HEAP_MB="${HEAP_MB:-2048}"
RESULTS_DIR="${RESULTS_DIR:-results/parallel}"
LOG_FILE="${LOG_FILE:-gc-tuning/logs/parallel.log}"

mkdir -p "$RESULTS_DIR" "$(dirname "$LOG_FILE")"

echo "── Parallel GC sweep (heap=${HEAP_MB}M) → $RESULTS_DIR ──"
java \
  -XX:+UseParallelGC \
  -Xms"${HEAP_MB}m" -Xmx"${HEAP_MB}m" \
  "-Xlog:gc*:file=${LOG_FILE}:utctime,level,tags" \
  -cp target/classes \
  pt.isep.sismd.histogram.BenchmarkRunner "$RESULTS_DIR"
