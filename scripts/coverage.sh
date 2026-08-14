#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "${0%/*}/.." && pwd)
cd "$ROOT"

./mvnw clean verify

REPORT="$ROOT/modules/railix-creator/target/site/jacoco-aggregate/jacoco.csv"
awk -F, '
NR > 1 { lm += $8; lc += $9; bm += $6; bc += $7 }
END {
    printf "Authored Java coverage: lines %.2f%% (%d/%d), branches %.2f%% (%d/%d)\n", \
        100 * lc / (lm + lc), lc, lm + lc, 100 * bc / (bm + bc), bc, bm + bc
}
' "$REPORT"
printf 'Report: %s\n' "$ROOT/modules/railix-creator/target/site/jacoco-aggregate/index.html"
