#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

usage() {
  cat <<'EOF' >&2
Usage:
  railix.sh package [package-options]

Commands:
  package   Build a Railix packaging artifact through the current packaging driver.
EOF
}

if [ "$#" -eq 0 ]; then
  usage
  exit 1
fi

COMMAND=$1
shift

case "$COMMAND" in
  package)
    sh "$SCRIPT_DIR/package/railix-package.sh" "$@"
    ;;
  --help|-h|help)
    usage
    exit 0
    ;;
  *)
    echo "Unknown command: $COMMAND" >&2
    usage
    exit 1
    ;;
esac
