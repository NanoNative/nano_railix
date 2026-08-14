#!/bin/sh
set -eu

if [ "$#" -ne 4 ]; then
  printf '%s\n' "Usage: package-creator-app.sh <java-home> <input-jar> <runtime-dir> <package-parent>" >&2
  exit 2
fi

JAVA_HOME_INPUT=$1
INPUT_JAR=$2
RUNTIME_DIR=$3
PACKAGE_PARENT=$4

if [ ! -f "$INPUT_JAR" ]; then
  printf '%s\n' "Creator package input is missing: $INPUT_JAR" >&2
  exit 2
fi

TOOL_JAVA_HOME=$JAVA_HOME_INPUT
JLINK=$TOOL_JAVA_HOME/bin/jlink
JPACKAGE=$TOOL_JAVA_HOME/bin/jpackage
JMODS=$TOOL_JAVA_HOME/jmods

if [ ! -x "$JLINK" ]; then
  printf '%s\n' "Required JDK tool is missing: $JLINK" >&2
  exit 2
fi
if [ ! -x "$JPACKAGE" ]; then
  printf '%s\n' "Required JDK tool is missing: $JPACKAGE" >&2
  exit 2
fi
if [ ! -d "$JMODS" ]; then
  printf '%s\n' "Required JDK modules are missing: $JMODS" >&2
  exit 2
fi

STAGE_DIR=$PACKAGE_PARENT/package-input
PACKAGE_NAME=railix

rm -rf "$RUNTIME_DIR" "$STAGE_DIR" "$PACKAGE_PARENT/$PACKAGE_NAME" "$PACKAGE_PARENT/$PACKAGE_NAME.app"
trap 'rm -rf "$RUNTIME_DIR" "$STAGE_DIR"' EXIT HUP INT TERM
mkdir -p "$PACKAGE_PARENT" "$STAGE_DIR"
cp "$INPUT_JAR" "$STAGE_DIR/"

MODULES=
for JMOD_FILE in "$JMODS"/*.jmod; do
  MODULE_NAME=${JMOD_FILE##*/}
  MODULE_NAME=${MODULE_NAME%.jmod}
  case "$MODULE_NAME" in
    jdk.incubator.*) continue ;;
  esac
  MODULES=${MODULES:+$MODULES,}$MODULE_NAME
done

"$JLINK" \
  --module-path "$JMODS" \
  --add-modules "$MODULES" \
  --output "$RUNTIME_DIR" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=zip-9

"$JPACKAGE" \
  --type app-image \
  --name "$PACKAGE_NAME" \
  --input "$STAGE_DIR" \
  --main-jar "$(basename "$INPUT_JAR")" \
  --main-class dev.nanonative.railix.creator.RailixMain \
  --runtime-image "$RUNTIME_DIR" \
  --dest "$PACKAGE_PARENT"
