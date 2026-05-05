#!/usr/bin/env bash
# Issue #9 — Serial GC sweep. Run from the project root:
#   bash gc-tuning/run_serial_gc.sh
set -euo pipefail

HEAP_MB="${HEAP_MB:-2048}"
RESULTS_DIR="${RESULTS_DIR:-results/serial}"
LOG_FILE="${LOG_FILE:-gc-tuning/logs/serial.log}"

mkdir -p "$RESULTS_DIR" "$(dirname "$LOG_FILE")"

echo "── Serial GC sweep (heap=${HEAP_MB}M) → $RESULTS_DIR ──"
java \
  -XX:+UseSerialGC \
  -Xms"${HEAP_MB}m" -Xmx"${HEAP_MB}m" \
  "-Xlog:gc*:file=${LOG_FILE}:utctime,level,tags" \
  -cp target/classes \
  pt.isep.sismd.histogram.BenchmarkRunner "$RESULTS_DIR"
