#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "${0%/*}/.." && pwd)
cd "$ROOT"

./mvnw "$@" clean verify

REPORT="$ROOT/modules/railix-creator/target/site/jacoco-aggregate/jacoco.csv"
COVERAGE_SUMMARY=$(awk -F, '
NR > 1 { lm += $8; lc += $9; bm += $6; bc += $7 }
END {
    printf "Authored Java coverage: lines %.2f%% (%d/%d), branches %.2f%% (%d/%d)\n", \
        100 * lc / (lm + lc), lc, lm + lc, 100 * bc / (bm + bc), bc, bm + bc
}
' "$REPORT")
printf '%s\n' "$COVERAGE_SUMMARY"
printf 'Report: %s\n' "$ROOT/modules/railix-creator/target/site/jacoco-aggregate/index.html"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        printf '## Coverage (advisory)\n\n%s\n\n' "$COVERAGE_SUMMARY"
        printf '%s\n\n' 'Standard-library coverage is underreported because runtime-pruned provider classes differ from the originals analysed by JaCoCo.'
        printf '%s\n' 'Download the `coverage-report` artifact for the full HTML report.'
    } >> "$GITHUB_STEP_SUMMARY"
fi
