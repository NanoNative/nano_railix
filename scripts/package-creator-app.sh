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
REQUIRED_JAVA_FEATURE=25

if [ ! -f "$INPUT_JAR" ]; then
  printf '%s\n' "Creator package input is missing: $INPUT_JAR" >&2
  exit 2
fi

complete_jdk() {
  [ -x "$1/bin/java" ] &&
    [ -x "$1/bin/jlink" ] &&
    [ -x "$1/bin/jpackage" ] &&
    [ -d "$1/jmods" ]
}

java_feature() {
  "$1/bin/java" -XshowSettings:properties -version 2>&1 |
    sed -n 's/^[[:space:]]*java\.specification\.version = \([0-9][0-9]*\).*$/\1/p'
}

compatible_jdk() {
  complete_jdk "$1" || return 1
  FEATURE=$(java_feature "$1")
  [ -n "$FEATURE" ] && [ "$FEATURE" -ge "$REQUIRED_JAVA_FEATURE" ] 2>/dev/null
}

TOOL_JAVA_HOME=$JAVA_HOME_INPUT
if ! compatible_jdk "$TOOL_JAVA_HOME"; then
  ENV_JAVA_HOME=${JAVA_HOME:-}
  if [ -n "$ENV_JAVA_HOME" ] && compatible_jdk "$ENV_JAVA_HOME"; then
    TOOL_JAVA_HOME=$ENV_JAVA_HOME
  else
    printf "Compatible JDK required: passed Java home '%s'; JAVA_HOME '%s'; %s\n" \
      "$JAVA_HOME_INPUT" "${ENV_JAVA_HOME:-<unset>}" \
      "expected Java $REQUIRED_JAVA_FEATURE or newer with executable bin/java, bin/jlink, bin/jpackage, and directory jmods." >&2
    exit 2
  fi
fi
JLINK=$TOOL_JAVA_HOME/bin/jlink
JPACKAGE=$TOOL_JAVA_HOME/bin/jpackage
JMODS=$TOOL_JAVA_HOME/jmods

STAGE_DIR=$PACKAGE_PARENT/package-input
PACKAGE_NAME=railix
PACKAGE_READY=false

cleanup() {
  if [ "$PACKAGE_READY" = true ]; then
    rm -rf "$RUNTIME_DIR" "$STAGE_DIR"
  else
    rm -rf "$RUNTIME_DIR" "$STAGE_DIR" "$PACKAGE_PARENT/$PACKAGE_NAME" "$PACKAGE_PARENT/$PACKAGE_NAME.app"
  fi
}

rm -rf "$RUNTIME_DIR" "$STAGE_DIR" "$PACKAGE_PARENT/$PACKAGE_NAME" "$PACKAGE_PARENT/$PACKAGE_NAME.app"
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
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

PACKAGE_READY=true
