#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
DEST_DIR=${1:-${DEST_DIR:-"$PROJECT_ROOT/build/package"}}
APP_NAME=${APP_NAME:-RailixII}
MAIN_MODULE=${MAIN_MODULE:-railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain}
JPACKAGE_BIN=${JPACKAGE_BIN:-$(command -v jpackage || true)}

if [ "${2:-}" ]; then
  RUNTIME_IMAGE=$2
elif [ -n "${RUNTIME_IMAGE:-}" ]; then
  RUNTIME_IMAGE=$RUNTIME_IMAGE
elif [ "$DEST_DIR" = "$PROJECT_ROOT/build/package" ]; then
  RUNTIME_IMAGE=$PROJECT_ROOT/build/image-dev
else
  RUNTIME_IMAGE=${DEST_DIR}.runtime-image
fi

if [ -z "$JPACKAGE_BIN" ]; then
  echo "jpackage not found on PATH" >&2
  exit 1
fi

if [ -n "${APP_SETTINGS_DIR:-}" ] || [ ! -d "$RUNTIME_IMAGE" ]; then
  "$PROJECT_ROOT/tools/package/jlink-dev.sh" "$RUNTIME_IMAGE"
fi

rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"

"$JPACKAGE_BIN" \
  --type app-image \
  --name "$APP_NAME" \
  --runtime-image "$RUNTIME_IMAGE" \
  --dest "$DEST_DIR" \
  --module "$MAIN_MODULE"

echo "Created $DEST_DIR"
