#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)

TYPE=app-image
MODE=headless
DEST_DIR=
RUNTIME_IMAGE=
APP_IMAGE_DIR=
DEFAULT_APP_NAME=RailixII
CREATOR_APP_NAME=RailixCreator
APP_NAME=${APP_NAME:-$DEFAULT_APP_NAME}
APP_SPEC=
BUILD_PROFILE=
DEFAULT_MAIN_MODULE=railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain
CREATOR_MAIN_MODULE=railix.creator/dev.nanonative.railix.creator.runtime.CreatorShellMain
MAIN_MODULE=${MAIN_MODULE:-$DEFAULT_MAIN_MODULE}
DEFAULT_LAUNCHER_NAME=railix-app
CREATOR_LAUNCHER_NAME=railix-creator
LAUNCHER_NAME=${LAUNCHER_NAME:-$DEFAULT_LAUNCHER_NAME}
DEFAULT_APP_MODULES=railix.kernel,railix.std.data,railix.std.store,railix.std.trigger
CREATOR_APP_MODULES=railix.creator
APP_MODULES=${APP_MODULES:-$DEFAULT_APP_MODULES}
DEFAULTS_FILE=
PROFILE_ARGS=
PROFILE_SEPARATOR=$(printf '\034')
REPORT_FILE=
STAGING_ROOT=

cleanup() {
  if [ -n "${STAGING_ROOT:-}" ] && [ -d "$STAGING_ROOT" ]; then
    rm -rf "$STAGING_ROOT"
  fi
}
trap cleanup EXIT

print_usage() {
  cat <<'EOF'
Usage:
  railix-package.sh [--type <jlink|app-image|pkg>] --dest <path> [options]

Options:
  --type <jlink|app-image|pkg>          Packaging target. Default: app-image.
  --mode <headless|creator>             Launcher surface to package. Default: headless.
  --dest <path>                         Output directory for the selected target.
  --runtime-image <path>                Runtime image path to reuse or create for app-image builds.
  --app-image-dir <path>                App-image directory for pkg builds.
  --app <path>                          railix.app.yaml source for app-owned packaging defaults.
  --profile <name>                      Declared app profile to package from settings/profiles/<name>.json.
  --app-name <name>                     Native app name. Default: RailixII.
  --defaults <path>                     Packaged defaults.json source file.
  --packaged-profile <name=path>        Packaged profile settings file. Repeatable.
  --report <path>                       Write a JSON package report to this file.
  --help                                Show this help.

Notes:
  - This command owns packaged settings staging and forwards to the existing
    Railix packaging scripts.
  - Current app-spec ownership is intentionally narrow: app name, direct
    dependency module ids, declared profiles, app.settings.defaults, and
    settings/profiles/<name>.json only.
  - It does not yet perform dependency resolution, lockfile generation,
    signing, or notarization.
EOF
}

usage() {
  print_usage >&2
  exit 1
}

require_argument() {
  flag_name=$1
  flag_value=${2:-}
  if [ -z "$flag_value" ]; then
    echo "Missing value for $flag_name" >&2
    usage
  fi
}

append_profile_mapping() {
  mapping=$1
  if [ -z "$PROFILE_ARGS" ]; then
    PROFILE_ARGS=$mapping
  else
    PROFILE_ARGS=$PROFILE_ARGS$PROFILE_SEPARATOR$mapping
  fi
}

append_app_module() {
  module_name=$1
  case ",$APP_MODULES," in
    *,"$module_name",*)
      ;;
    *)
      APP_MODULES=$APP_MODULES,$module_name
      ;;
  esac
}

module_name_from_info() {
  module_info_file=$1
  sed -n 's/^module[[:space:]]\{1,\}\([^[:space:]{][^[:space:]{]*\)[[:space:]]*{.*/\1/p' "$module_info_file"
}

module_dir_for_name() {
  module_name=$1
  for module_info_file in "$PROJECT_ROOT"/modules/*/src/main/java/module-info.java; do
    [ -f "$module_info_file" ] || continue
    if [ "$(module_name_from_info "$module_info_file")" = "$module_name" ]; then
      dirname -- "$(dirname -- "$(dirname -- "$(dirname -- "$module_info_file")")")"
      return 0
    fi
  done
  return 1
}

module_has_built_artifact() {
  module_dir=$1
  if [ -f "$module_dir/target/classes/module-info.class" ]; then
    return 0
  fi
  for module_jar in "$module_dir"/target/*.jar; do
    if [ -f "$module_jar" ]; then
      return 0
    fi
  done
  return 1
}

validate_app_modules() {
  old_ifs=$IFS
  IFS=','
  set -- $APP_MODULES
  IFS=$old_ifs
  for module_name in "$@"; do
    if [ -z "$module_name" ]; then
      echo "Invalid empty app module in APP_MODULES" >&2
      exit 1
    fi
    if ! module_dir=$(module_dir_for_name "$module_name"); then
      echo "App dependency module not available for packaging: $module_name" >&2
      exit 1
    fi
    if ! module_has_built_artifact "$module_dir"; then
      echo "App dependency module is not built: $module_name" >&2
      exit 1
    fi
  done
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --type)
      require_argument "$1" "${2:-}"
      TYPE=$2
      shift 2
      ;;
    --mode)
      require_argument "$1" "${2:-}"
      MODE=$2
      shift 2
      ;;
    --dest)
      require_argument "$1" "${2:-}"
      DEST_DIR=$2
      shift 2
      ;;
    --runtime-image)
      require_argument "$1" "${2:-}"
      RUNTIME_IMAGE=$2
      shift 2
      ;;
    --app-image-dir)
      require_argument "$1" "${2:-}"
      APP_IMAGE_DIR=$2
      shift 2
      ;;
    --app)
      require_argument "$1" "${2:-}"
      APP_SPEC=$2
      shift 2
      ;;
    --profile)
      require_argument "$1" "${2:-}"
      BUILD_PROFILE=$2
      shift 2
      ;;
    --app-name)
      require_argument "$1" "${2:-}"
      APP_NAME=$2
      shift 2
      ;;
    --defaults)
      require_argument "$1" "${2:-}"
      DEFAULTS_FILE=$2
      shift 2
      ;;
    --packaged-profile)
      require_argument "$1" "${2:-}"
      append_profile_mapping "$2"
      shift 2
      ;;
    --report)
      require_argument "$1" "${2:-}"
      REPORT_FILE=$2
      shift 2
      ;;
    --help)
      print_usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      ;;
  esac
done

if [ -z "$DEST_DIR" ]; then
  usage
fi

case "$TYPE" in
  jlink|app-image|pkg)
    ;;
  *)
    echo "Unsupported packaging type: $TYPE" >&2
    usage
    ;;
esac

case "$MODE" in
  headless|creator)
    ;;
  *)
    echo "Unsupported packaging mode: $MODE" >&2
    usage
    ;;
esac

if [ "$MODE" = "creator" ]; then
  if [ -n "$APP_SPEC" ]; then
    echo "--app is not supported with --mode creator" >&2
    exit 1
  fi
  if [ "$APP_NAME" = "$DEFAULT_APP_NAME" ]; then
    APP_NAME=$CREATOR_APP_NAME
  fi
  if [ "$MAIN_MODULE" = "$DEFAULT_MAIN_MODULE" ]; then
    MAIN_MODULE=$CREATOR_MAIN_MODULE
  fi
  if [ "$LAUNCHER_NAME" = "$DEFAULT_LAUNCHER_NAME" ]; then
    LAUNCHER_NAME=$CREATOR_LAUNCHER_NAME
  fi
  if [ "$APP_MODULES" = "$DEFAULT_APP_MODULES" ]; then
    APP_MODULES=$CREATOR_APP_MODULES
  fi
fi

if [ -n "$DEFAULTS_FILE" ] && [ ! -f "$DEFAULTS_FILE" ]; then
  echo "Packaged defaults file not found: $DEFAULTS_FILE" >&2
  exit 1
fi

resolve_relative_to() {
  base_dir=$1
  relative_path=$2
  case "$relative_path" in
    /*)
      printf '%s\n' "$relative_path"
      ;;
    *)
      printf '%s/%s\n' "$base_dir" "$relative_path"
      ;;
  esac
}

spec_app_name() {
  awk '
    /^app:[[:space:]]*$/ { in_app=1; next }
    in_app && /^[^ ]/ { exit }
    in_app && /^  name:[[:space:]]*/ {
      sub(/^  name:[[:space:]]*/, "", $0)
      gsub(/^["'"'"'"'"'"'"'"'"']|["'"'"'"'"'"'"'"'"']$/, "", $0)
      print
      exit
    }
  ' "$APP_SPEC"
}

spec_defaults_path() {
  awk '
    /^app:[[:space:]]*$/ { in_app=1; next }
    in_app && /^[^ ]/ { exit }
    in_app && /^  settings:[[:space:]]*$/ { in_settings=1; next }
    in_app && in_settings && /^  [^ ]/ && $0 !~ /^  settings:[[:space:]]*$/ { in_settings=0 }
    in_app && in_settings && /^    defaults:[[:space:]]*/ {
      sub(/^    defaults:[[:space:]]*/, "", $0)
      gsub(/^["'"'"'"'"'"'"'"'"']|["'"'"'"'"'"'"'"'"']$/, "", $0)
      print
      exit
    }
  ' "$APP_SPEC"
}

spec_profile_names() {
  awk '
    /^app:[[:space:]]*$/ { in_app=1; next }
    in_app && /^[^ ]/ { exit }
    in_app && /^  profiles:[[:space:]]*$/ { in_profiles=1; next }
    in_app && in_profiles && /^  [^ ]/ && $0 !~ /^  profiles:[[:space:]]*$/ { in_profiles=0 }
    in_app && in_profiles && /^    -[[:space:]]*/ {
      sub(/^    -[[:space:]]*/, "", $0)
      gsub(/^["'"'"'"'"'"'"'"'"']|["'"'"'"'"'"'"'"'"']$/, "", $0)
      print
    }
  ' "$APP_SPEC"
}

spec_dependency_module_names() {
  awk '
    /^app:[[:space:]]*$/ { in_app=1; next }
    in_app && /^[^ ]/ { exit }
    in_app && /^  dependencies:[[:space:]]*$/ { in_dependencies=1; next }
    in_app && in_dependencies && /^  [^ ]/ && $0 !~ /^  dependencies:[[:space:]]*$/ { in_dependencies=0 }
    in_app && in_dependencies && /^    -[[:space:]]*\{[[:space:]]*id:[[:space:]]*/ {
      line=$0
      sub(/^    -[[:space:]]*\{[[:space:]]*id:[[:space:]]*/, "", line)
      sub(/[[:space:]]*,.*$/, "", line)
      gsub(/^["'"'"'"'"'"'"'"'"']|["'"'"'"'"'"'"'"'"']$/, "", line)
      print line
      next
    }
    in_app && in_dependencies && /^    -[[:space:]]*id:[[:space:]]*/ {
      line=$0
      sub(/^    -[[:space:]]*id:[[:space:]]*/, "", line)
      gsub(/^["'"'"'"'"'"'"'"'"']|["'"'"'"'"'"'"'"'"']$/, "", line)
      print line
      next
    }
    in_app && in_dependencies && /^      id:[[:space:]]*/ {
      line=$0
      sub(/^      id:[[:space:]]*/, "", line)
      gsub(/^["'"'"'"'"'"'"'"'"']|["'"'"'"'"'"'"'"'"']$/, "", line)
      print line
    }
  ' "$APP_SPEC"
}

if [ -n "$BUILD_PROFILE" ] && [ -z "$APP_SPEC" ]; then
  echo "--profile requires --app" >&2
  exit 1
fi

if [ -n "$BUILD_PROFILE" ] && [ -n "$PROFILE_ARGS" ]; then
  echo "--profile cannot be combined with --packaged-profile" >&2
  exit 1
fi

if [ -n "$APP_SPEC" ]; then
  if [ ! -f "$APP_SPEC" ]; then
    echo "App spec file not found: $APP_SPEC" >&2
    exit 1
  fi
  APP_SPEC_DIR=$(CDPATH= cd -- "$(dirname -- "$APP_SPEC")" && pwd)
  APP_SPEC=$APP_SPEC_DIR/$(basename -- "$APP_SPEC")

  SPEC_APP_NAME=$(spec_app_name)
  if [ "$APP_NAME" = "RailixII" ] && [ -n "$SPEC_APP_NAME" ]; then
    APP_NAME=$SPEC_APP_NAME
  fi

  if [ "$APP_MODULES" = "$DEFAULT_APP_MODULES" ]; then
    APP_MODULES=railix.kernel
    OLD_IFS=$IFS
    IFS='
'
    for dependency_module in $(spec_dependency_module_names); do
      append_app_module "$dependency_module"
    done
    IFS=$OLD_IFS
  fi

  if [ -z "$DEFAULTS_FILE" ]; then
    SPEC_DEFAULTS_PATH=$(spec_defaults_path)
    if [ -n "$SPEC_DEFAULTS_PATH" ]; then
      DEFAULTS_FILE=$(resolve_relative_to "$APP_SPEC_DIR" "$SPEC_DEFAULTS_PATH")
    fi
  fi

  if [ -n "$BUILD_PROFILE" ]; then
    DECLARED_PROFILE=0
    OLD_IFS=$IFS
    IFS='
'
    for profile_name in $(spec_profile_names); do
      if [ "$profile_name" = "$BUILD_PROFILE" ]; then
        DECLARED_PROFILE=1
        break
      fi
    done
    IFS=$OLD_IFS
    if [ "$DECLARED_PROFILE" -eq 0 ]; then
      echo "App profile not declared in $APP_SPEC: $BUILD_PROFILE" >&2
      exit 1
    fi
    PROFILE_ARGS=$BUILD_PROFILE=$(resolve_relative_to "$APP_SPEC_DIR" "settings/profiles/$BUILD_PROFILE.json")
  elif [ -z "$PROFILE_ARGS" ]; then
    OLD_IFS=$IFS
    IFS='
'
    for profile_name in $(spec_profile_names); do
      append_profile_mapping "$profile_name=$(resolve_relative_to "$APP_SPEC_DIR" "settings/profiles/$profile_name.json")"
    done
    IFS=$OLD_IFS
  fi
fi

if [ -n "$DEFAULTS_FILE" ] && [ ! -f "$DEFAULTS_FILE" ]; then
  echo "Packaged defaults file not found: $DEFAULTS_FILE" >&2
  exit 1
fi

validate_app_modules

validate_profile_file() {
  mapping=$1
  profile_name=${mapping%%=*}
  profile_path=${mapping#*=}
  if [ -z "$profile_name" ] || [ "$profile_name" = "$mapping" ]; then
    echo "Invalid --packaged-profile mapping: $mapping" >&2
    exit 1
  fi
  case "$profile_name" in
    *[!A-Za-z0-9._-]*)
      echo "Invalid packaged profile name: $profile_name" >&2
      exit 1
      ;;
  esac
  if [ ! -f "$profile_path" ]; then
    echo "Packaged profile file not found for $profile_name: $profile_path" >&2
    exit 1
  fi
}

if [ -n "$PROFILE_ARGS" ]; then
  OLD_IFS=$IFS
  IFS=$PROFILE_SEPARATOR
  set -- $PROFILE_ARGS
  IFS=$OLD_IFS
  for profile_mapping in "$@"; do
    validate_profile_file "$profile_mapping"
  done
fi

json_escape() {
  printf '%s' "$1" | awk 'BEGIN { ORS = "" } { gsub(/\\/, "\\\\"); gsub(/"/, "\\\""); gsub(/\t/, "\\t"); gsub(/\r/, "\\r"); print }'
}

json_string() {
  printf '"%s"' "$(json_escape "$1")"
}

json_or_null() {
  if [ -n "${1:-}" ]; then
    json_string "$1"
  else
    printf 'null'
  fi
}

host_app_image_path() {
  case "$(uname -s)" in
    Darwin)
      printf '%s\n' "$1/$APP_NAME.app"
      ;;
    MINGW*|MSYS*|CYGWIN*)
      printf '%s\n' "$1/$APP_NAME"
      ;;
    *)
      printf '%s\n' "$1/$APP_NAME"
      ;;
  esac
}

host_launcher_path() {
  base_path=$1
  case "$(uname -s)" in
    Darwin)
      printf '%s\n' "$base_path/Contents/MacOS/$APP_NAME"
      ;;
    MINGW*|MSYS*|CYGWIN*)
      printf '%s\n' "$base_path/$APP_NAME.exe"
      ;;
    *)
      printf '%s\n' "$base_path/bin/$APP_NAME"
      ;;
  esac
}

first_pkg_file() {
  installer_dir=$1
  for pkg_file in "$installer_dir"/*.pkg; do
    if [ -f "$pkg_file" ]; then
      printf '%s\n' "$pkg_file"
      return 0
    fi
  done
  echo "No .pkg file found in $installer_dir" >&2
  exit 1
}

absolute_path() {
  target_path=$1
  target_dir=$(dirname -- "$target_path")
  target_name=$(basename -- "$target_path")
  (
    CDPATH= cd -- "$target_dir"
    printf '%s/%s\n' "$(pwd)" "$target_name"
  )
}

write_report() {
  report_type=$1
  dest_dir=$2
  launcher_path=$3
  runtime_image_path=$4
  app_image_path=$5
  installer_path=$6
  report_parent=$(dirname -- "$REPORT_FILE")
  mkdir -p "$report_parent"
  {
    printf '{\n'
    printf '  "schemaVersion": 1,\n'
    printf '  "type": %s,\n' "$(json_string "$report_type")"
    printf '  "mode": %s,\n' "$(json_string "$MODE")"
    printf '  "appName": %s,\n' "$(json_string "$APP_NAME")"
    printf '  "destDir": %s,\n' "$(json_string "$(absolute_path "$dest_dir")")"
    printf '  "artifacts": {\n'
    artifact_fields_written=0
    if [ -n "$runtime_image_path" ]; then
      printf '    "runtimeImageDir": %s' "$(json_string "$(absolute_path "$runtime_image_path")")"
      artifact_fields_written=1
    fi
    if [ -n "$app_image_path" ]; then
      if [ "$artifact_fields_written" -eq 1 ]; then
        printf ',\n'
      fi
      printf '    "appImageDir": %s' "$(json_string "$(absolute_path "$app_image_path")")"
      artifact_fields_written=1
    fi
    if [ -n "$installer_path" ]; then
      if [ "$artifact_fields_written" -eq 1 ]; then
        printf ',\n'
      fi
      printf '    "pkgFile": %s' "$(json_string "$(absolute_path "$installer_path")")"
      artifact_fields_written=1
    fi
    if [ -n "$launcher_path" ]; then
      if [ "$artifact_fields_written" -eq 1 ]; then
        printf ',\n'
      fi
      printf '    "launcherPath": %s' "$(json_string "$(absolute_path "$launcher_path")")"
    fi
    printf '\n'
    printf '  },\n'
    printf '  "embeddedSettings": {\n'
    if [ -n "$DEFAULTS_FILE" ]; then
      printf '    "defaultsEmbedded": true,\n'
    else
      printf '    "defaultsEmbedded": false,\n'
    fi
    printf '    "profileNames": ['
    first_profile=1
    if [ -n "$PROFILE_ARGS" ]; then
      OLD_IFS=$IFS
      IFS=$PROFILE_SEPARATOR
      set -- $PROFILE_ARGS
      IFS=$OLD_IFS
      for profile_mapping in "$@"; do
        profile_name=${profile_mapping%%=*}
        if [ "$first_profile" -eq 0 ]; then
          printf ', '
        fi
        printf '%s' "$(json_string "$profile_name")"
        first_profile=0
      done
    fi
    printf ']\n'
    printf '  }\n'
    printf '}\n'
  } > "$REPORT_FILE"
}

APP_SETTINGS_DIR=
if [ -n "$DEFAULTS_FILE" ] || [ -n "$PROFILE_ARGS" ]; then
  STAGING_ROOT=$(mktemp -d)
  APP_SETTINGS_DIR=$STAGING_ROOT/packaged-settings
  mkdir -p "$APP_SETTINGS_DIR/profiles"
  if [ -n "$DEFAULTS_FILE" ]; then
    cp "$DEFAULTS_FILE" "$APP_SETTINGS_DIR/defaults.json"
  fi
  if [ -n "$PROFILE_ARGS" ]; then
    OLD_IFS=$IFS
    IFS=$PROFILE_SEPARATOR
    set -- $PROFILE_ARGS
    IFS=$OLD_IFS
    for profile_mapping in "$@"; do
      profile_name=${profile_mapping%%=*}
      profile_path=${profile_mapping#*=}
      cp "$profile_path" "$APP_SETTINGS_DIR/profiles/$profile_name.json"
    done
  fi
fi

case "$TYPE" in
  jlink)
    IMAGE_DIR=$DEST_DIR \
    APP_MODULES=$APP_MODULES \
    MAIN_MODULE=$MAIN_MODULE \
    LAUNCHER_NAME=$LAUNCHER_NAME \
    APP_SETTINGS_DIR=$APP_SETTINGS_DIR \
      sh "$PROJECT_ROOT/tools/package/jlink-dev.sh"
    if [ -n "$REPORT_FILE" ]; then
      write_report "jlink" "$DEST_DIR" "$DEST_DIR/bin/$LAUNCHER_NAME" "$DEST_DIR" "" ""
    fi
    ;;
  app-image)
    if [ -z "$RUNTIME_IMAGE" ]; then
      RUNTIME_IMAGE=$PROJECT_ROOT/build/image-dev
    fi
    DEST_DIR=$DEST_DIR \
    RUNTIME_IMAGE=$RUNTIME_IMAGE \
    APP_NAME=$APP_NAME \
    APP_MODULES=$APP_MODULES \
    MAIN_MODULE=$MAIN_MODULE \
    LAUNCHER_NAME=$LAUNCHER_NAME \
    APP_SETTINGS_DIR=$APP_SETTINGS_DIR \
      sh "$PROJECT_ROOT/tools/package/jpackage-app-image.sh"
    if [ -n "$REPORT_FILE" ]; then
      APP_IMAGE_PATH=$(host_app_image_path "$DEST_DIR")
      write_report "app-image" "$DEST_DIR" "$(host_launcher_path "$APP_IMAGE_PATH")" "$RUNTIME_IMAGE" "$APP_IMAGE_PATH" ""
    fi
    ;;
  pkg)
    if [ -z "$APP_IMAGE_DIR" ]; then
      APP_IMAGE_DIR=$PROJECT_ROOT/build/package
    fi
    DEST_DIR=$DEST_DIR \
    APP_IMAGE_DIR=$APP_IMAGE_DIR \
    APP_NAME=$APP_NAME \
    APP_MODULES=$APP_MODULES \
    MAIN_MODULE=$MAIN_MODULE \
    LAUNCHER_NAME=$LAUNCHER_NAME \
    APP_SETTINGS_DIR=$APP_SETTINGS_DIR \
      sh "$PROJECT_ROOT/tools/package/jpackage-pkg.sh"
    if [ -n "$REPORT_FILE" ]; then
      APP_IMAGE_PATH=$(host_app_image_path "$APP_IMAGE_DIR")
      PKG_PATH=$(first_pkg_file "$DEST_DIR")
      write_report "pkg" "$DEST_DIR" "$(host_launcher_path "$APP_IMAGE_PATH")" "" "$APP_IMAGE_PATH" "$PKG_PATH"
    fi
    ;;
esac

if [ -n "$REPORT_FILE" ]; then
  echo "Wrote $REPORT_FILE"
fi
