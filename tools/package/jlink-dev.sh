#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
IMAGE_DIR=${1:-${IMAGE_DIR:-"$PROJECT_ROOT/build/image-dev"}}
APP_MODULES=${APP_MODULES:-railix.kernel,railix.std.data,railix.std.store,railix.std.trigger}
MAIN_MODULE=${MAIN_MODULE:-railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain}
LAUNCHER_NAME=${LAUNCHER_NAME:-railix-app}
APP_SETTINGS_DIR=${APP_SETTINGS_DIR:-}
JLINK_BIN=${JLINK_BIN:-$(command -v jlink || true)}

if [ -z "$JLINK_BIN" ]; then
  echo "jlink not found on PATH" >&2
  exit 1
fi

JAVA_HOME_DIR=$(CDPATH= cd -- "$(dirname -- "$JLINK_BIN")/.." && pwd)
JMODS_DIR=$JAVA_HOME_DIR/jmods
if [ ! -d "$JMODS_DIR" ]; then
  echo "jmods directory not found: $JMODS_DIR" >&2
  exit 1
fi

STAGING_ROOT=
cleanup() {
  if [ -n "${STAGING_ROOT:-}" ] && [ -d "$STAGING_ROOT" ]; then
    rm -rf "$STAGING_ROOT"
  fi
}
trap cleanup EXIT

if [ -n "$APP_SETTINGS_DIR" ] && [ ! -d "$APP_SETTINGS_DIR" ]; then
  echo "APP_SETTINGS_DIR not found: $APP_SETTINGS_DIR" >&2
  exit 1
fi

if [ -n "$APP_SETTINGS_DIR" ]; then
  HAS_PACKAGED_SETTINGS=0
  if [ -f "$APP_SETTINGS_DIR/defaults.json" ]; then
    HAS_PACKAGED_SETTINGS=1
  fi
  if [ "$HAS_PACKAGED_SETTINGS" -eq 0 ] && [ -d "$APP_SETTINGS_DIR/profiles" ]; then
    for profile_file in "$APP_SETTINGS_DIR"/profiles/*.json; do
      if [ -f "$profile_file" ]; then
        HAS_PACKAGED_SETTINGS=1
        break
      fi
    done
  fi
  if [ "$HAS_PACKAGED_SETTINGS" -eq 0 ]; then
    echo "APP_SETTINGS_DIR must contain defaults.json and/or profiles/*.json: $APP_SETTINGS_DIR" >&2
    exit 1
  fi
fi

stage_kernel_artifact() {
  artifact_path=$1
  STAGING_ROOT=${STAGING_ROOT:-$(mktemp -d)}
  staged_kernel_dir=$STAGING_ROOT/railix-kernel
  rm -rf "$staged_kernel_dir"
  mkdir -p "$staged_kernel_dir"

  if [ -d "$artifact_path" ]; then
    cp -R "$artifact_path"/. "$staged_kernel_dir"/
  else
    (cd "$staged_kernel_dir" && "$JAVA_HOME_DIR/bin/jar" xf "$artifact_path")
  fi

  rm -rf "$staged_kernel_dir/railix/settings"
  if [ -f "$APP_SETTINGS_DIR/defaults.json" ]; then
    mkdir -p "$staged_kernel_dir/railix/settings"
    cp "$APP_SETTINGS_DIR/defaults.json" "$staged_kernel_dir/railix/settings/defaults.json"
  fi
  if [ -d "$APP_SETTINGS_DIR/profiles" ]; then
    mkdir -p "$staged_kernel_dir/railix/settings/profiles"
    cp -R "$APP_SETTINGS_DIR/profiles"/. "$staged_kernel_dir/railix/settings/profiles"/
  fi

  printf '%s\n' "$staged_kernel_dir"
}

MODULE_PATH=$JMODS_DIR
FOUND_MODULE=0
for module_dir in "$PROJECT_ROOT"/modules/*; do
  [ -d "$module_dir" ] || continue
  artifact_path=
  if [ -f "$module_dir/target/classes/module-info.class" ]; then
    artifact_path=$module_dir/target/classes
  fi
  if [ -z "$artifact_path" ]; then
    for jar in "$module_dir"/target/*.jar; do
      if [ -f "$jar" ]; then
        artifact_path=$jar
        break
      fi
    done
  fi
  if [ -n "$artifact_path" ]; then
    if [ -n "$APP_SETTINGS_DIR" ] && [ "$(basename -- "$module_dir")" = "railix-kernel" ]; then
      artifact_path=$(stage_kernel_artifact "$artifact_path")
    fi
    MODULE_PATH=$MODULE_PATH:$artifact_path
    FOUND_MODULE=1
  fi
done

if [ "$FOUND_MODULE" -eq 0 ]; then
  echo "No built Railix modules found under $PROJECT_ROOT/modules" >&2
  exit 1
fi

rm -rf "$IMAGE_DIR"
mkdir -p "$(dirname -- "$IMAGE_DIR")"

"$JLINK_BIN" \
  --module-path "$MODULE_PATH" \
  --add-modules "$APP_MODULES" \
  --bind-services \
  --launcher "$LAUNCHER_NAME=$MAIN_MODULE" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --output "$IMAGE_DIR"

echo "Created $IMAGE_DIR"
