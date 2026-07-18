#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
DEST_DIR=${1:-${DEST_DIR:-"$PROJECT_ROOT/build/installer"}}
APP_IMAGE_DIR=${2:-${APP_IMAGE_DIR:-"$PROJECT_ROOT/build/package"}}
APP_NAME=${APP_NAME:-RailixII}
JPACKAGE_BIN=${JPACKAGE_BIN:-$(command -v jpackage || true)}

if [ -z "$JPACKAGE_BIN" ]; then
  echo "jpackage not found on PATH" >&2
  exit 1
fi

APP_IMAGE_PATH="$APP_IMAGE_DIR/$APP_NAME.app"
if [ -n "${APP_SETTINGS_DIR:-}" ] || [ ! -d "$APP_IMAGE_PATH" ]; then
  "$PROJECT_ROOT/tools/package/jpackage-app-image.sh" "$APP_IMAGE_DIR"
fi

rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"

"$JPACKAGE_BIN" \
  --type pkg \
  --name "$APP_NAME" \
  --app-image "$APP_IMAGE_PATH" \
  --dest "$DEST_DIR"

echo "Created $DEST_DIR"
