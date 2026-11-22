#!/usr/bin/env bash
set -euo pipefail

# Set PROJECT_FILE for a build or a local run without an example name.
# Paths may be absolute or relative to this repository.
# Set RAILIX_CLI_JAR to skip building the CLI. Use JDK 25 for builds.
PROJECT_FILE=${PROJECT_FILE:-examples/auth-http-app/railix.project.json}
RAILIX_CLI_JAR=${RAILIX_CLI_JAR:-}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
KEYTOOL=${JAVA_HOME:+$JAVA_HOME/bin/}keytool

# Local demo settings. Give the two copies distinct application listener ports
# through the project's settings. auth-http-app uses HTTP next-free-port fallback.
DEMO_MESH_HOST=127.0.0.1
DEMO_SEED_PORT=${DEMO_SEED_PORT:-7401}
DEMO_PEER_PORT=${DEMO_PEER_PORT:-7402}
DEMO_FLOW_CAPACITY=${DEMO_FLOW_CAPACITY:-2}
DEMO_USER_HOME=${DEMO_USER_HOME:-}

# Deployment settings. Copy the same build output to each host.
# Set RAILIX_MESH_CONFIG per replica. Its JSON carries the shared meshId,
# listenEndpoint, advertisedEndpoint, bootstrapEndpoints, and flowCapacity.
# In identityStore and trustStore, set each PKCS12 path and passwordFile.
APPLICATION_JAR=${APPLICATION_JAR:-}
RAILIX_MESH_CONFIG=${RAILIX_MESH_CONFIG:-}
MESH_MAIN_CLASS=dev.nanonative.railix.core.project.RailixMeshApplication

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'USAGE'
Usage
  ./run-mesh.sh [example-name]
  PROJECT_FILE=path/to/railix.project.json ./run-mesh.sh build
  APPLICATION_JAR=/path/to/application.jar RAILIX_MESH_CONFIG=/path/to/mesh.json ./run-mesh.sh deploy

example-name  Start two loopback replicas of an app under examples/ with temporary certificates.
              Defaults to auth-http-app. Select an app with a long-running source.
build         Print the path to one mesh-enabled application JAR. Accepts build flags.
deploy        Check the JAR with unzip and start one replica with provisioned credentials.
              Accepts application arguments.

Use RAILIX_CLI_JAR to reuse a packaged Railix CLI. For a custom demo project
that uses installed Steps, set DEMO_USER_HOME to the account's existing home.
USAGE
}

require_mesh_jar() {
    command -v unzip >/dev/null 2>&1 || fail 'Install unzip to check the application JAR.'
    local archive=$1
    if [[ $archive != /* ]]; then
        archive="$PWD/$archive"
    fi
    if ! unzip -p "$archive" META-INF/MANIFEST.MF | awk -v launcher="$MESH_MAIN_CLASS" '
        { sub(/\r$/, "") }
        !main_section_ended && $0 == "Main-Class: " launcher { found = 1 }
        !NF { main_section_ended = 1 }
        END { exit !found }
    '; then
        fail 'APPLICATION_JAR must be a Railix application JAR built with --mesh.'
    fi
}

mode=${1:-}
if [[ $# -gt 0 ]]; then
    shift
fi
case "$mode" in
    deploy)
        [[ -n $APPLICATION_JAR && -f $APPLICATION_JAR && -r $APPLICATION_JAR ]] ||
            fail 'Set APPLICATION_JAR to a readable JAR produced by run-mesh.sh build.'
        [[ -n $RAILIX_MESH_CONFIG && -f $RAILIX_MESH_CONFIG && -r $RAILIX_MESH_CONFIG ]] ||
            fail 'Set RAILIX_MESH_CONFIG to the readable mesh JSON file for this replica.'
        require_mesh_jar "$APPLICATION_JAR"
        export RAILIX_MESH_CONFIG
        exec "$JAVA" -jar "$APPLICATION_JAR" "$@"
        ;;
    build|'')
        ;;
    -h|--help)
        usage
        exit
        ;;
    -*)
        usage >&2
        exit 2
        ;;
    *)
        [[ $mode =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] ||
            fail 'Select an example by directory name, such as auth-http-app.'
        PROJECT_FILE="examples/$mode/railix.project.json"
        ;;
esac

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$root"
[[ -f $PROJECT_FILE && -r $PROJECT_FILE ]] || fail "Cannot read project file $PROJECT_FILE."
project_dir=$(CDPATH= cd -- "$(dirname -- "$PROJECT_FILE")" && pwd)
project_file="$project_dir/$(basename -- "$PROJECT_FILE")"

if [[ $mode != build ]]; then
    [[ $# -eq 0 ]] || fail 'Pass one example name to start a local mesh.'
    for port in "$DEMO_SEED_PORT" "$DEMO_PEER_PORT"; do
        [[ $port =~ ^[1-9][0-9]{0,4}$ ]] && ((port <= 65535)) ||
            fail 'Use mesh ports from 1 to 65535.'
    done
    [[ $DEMO_SEED_PORT != "$DEMO_PEER_PORT" ]] || fail 'The seed and peer need distinct mesh ports.'
    [[ $DEMO_FLOW_CAPACITY =~ ^[1-9][0-9]*$ ]] || fail 'DEMO_FLOW_CAPACITY must be a positive integer.'
fi

if [[ -z $RAILIX_CLI_JAR ]]; then
    ./mvnw -pl modules/railix-creator -am -DskipTests package >&2
    RAILIX_CLI_JAR="$root/modules/railix-creator/target/railix.jar"
elif [[ $RAILIX_CLI_JAR != /* ]]; then
    RAILIX_CLI_JAR="$root/$RAILIX_CLI_JAR"
fi
[[ -f $RAILIX_CLI_JAR && -r $RAILIX_CLI_JAR ]] || fail "Cannot read Railix CLI JAR $RAILIX_CLI_JAR."

if [[ $mode == build ]]; then
    exec "$JAVA" -jar "$RAILIX_CLI_JAR" build "$project_file" --mesh "$@"
fi

umask 077
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/railix-local-mesh.XXXXXX")
mesh_dir="$run_dir/mesh"
seed_pid=
peer_pid=
logs_pid=
cleanup() {
    for pid in "$seed_pid" "$peer_pid" "$logs_pid"; do
        if [[ -n $pid ]]; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    wait || true
    rm -rf -- "$mesh_dir"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

demo_home=${DEMO_USER_HOME:-$run_dir/home}
mkdir -p "$mesh_dir" "$demo_home"
demo_home=$(CDPATH= cd -- "$demo_home" && pwd)
demo_project="$run_dir/railix.project.json"

cp "$project_file" "$demo_project"
for document in railix.creator.json railix.examples.json railix.custom-steps.json railix.dependencies.lock.json; do
    if [[ -f $project_dir/$document ]]; then
        cp "$project_dir/$document" "$run_dir/"
    fi
done

uuid() {
    uuidgen | tr '[:upper:]' '[:lower:]'
}

mesh_keytool() (
    cd "$mesh_dir"
    store=$1
    shift
    "$KEYTOOL" -storetype PKCS12 -keystore "$store.p12" -storepass:file "$store.password" "$@"
)

mesh_id=$(uuid)
for store in issuer trust seed peer; do
    printf '%s\n' "$(uuid)" > "$mesh_dir/$store.password"
done
mesh_keytool issuer -genkeypair -alias root -dname 'CN=Railix local mesh CA' \
    -keyalg EC -validity 14 -ext 'bc:c=ca:true' -ext 'ku:c=keyCertSign,cRLSign'
mesh_keytool issuer -exportcert -alias root -file root.cer
mesh_keytool trust -importcert -noprompt -alias root -file root.cer

for replica in seed peer; do
    uri="https://github.com/NanoNative/nano_railix/mesh/v1/$mesh_id/replicas/$(uuid)"
    mesh_keytool "$replica" -genkeypair -alias peer -dname "CN=Railix local $replica" -keyalg EC
    mesh_keytool "$replica" -certreq -alias peer -file "$replica.csr"
    mesh_keytool issuer -gencert -alias root -infile "$replica.csr" -outfile "$replica.cer" \
        -validity 7 -ext "san=uri:$uri" -ext 'ku=digitalSignature' -ext 'eku=serverAuth,clientAuth'
    mesh_keytool "$replica" -importcert -noprompt -alias root -file root.cer
    mesh_keytool "$replica" -importcert -noprompt -alias peer -file "$replica.cer"
done
rm -f -- "$mesh_dir/issuer.p12" "$mesh_dir/issuer.password"

write_config() {
    local name=$1 port=$2 bootstrap=$3
    cat > "$mesh_dir/$name.json" <<JSON
{
  "meshId": "$mesh_id",
  "listenEndpoint": {"host": "$DEMO_MESH_HOST", "port": $port},
  "advertisedEndpoint": {"host": "$DEMO_MESH_HOST", "port": $port},
  "bootstrapEndpoints": $bootstrap,
  "flowCapacity": $DEMO_FLOW_CAPACITY,
  "identityStore": {"path": "$name.p12", "passwordFile": "$name.password"},
  "trustStore": {"path": "trust.p12", "passwordFile": "trust.password"}
}
JSON
}

write_config seed "$DEMO_SEED_PORT" '[]'
write_config peer "$DEMO_PEER_PORT" "[{\"host\":\"$DEMO_MESH_HOST\",\"port\":$DEMO_SEED_PORT}]"
app=$("$JAVA" "-Duser.home=$demo_home" -jar "$RAILIX_CLI_JAR" build "$demo_project" --mesh)

touch "$run_dir/seed.log" "$run_dir/peer.log"
RAILIX_MESH_CONFIG="$mesh_dir/seed.json" \
    "$JAVA" "-Duser.home=$demo_home" -jar "$app" > "$run_dir/seed.log" 2>&1 &
seed_pid=$!
RAILIX_MESH_CONFIG="$mesh_dir/peer.json" \
    "$JAVA" "-Duser.home=$demo_home" -jar "$app" > "$run_dir/peer.log" 2>&1 &
peer_pid=$!

printf '\nRun directory %s\nSeed PID %s | Peer PID %s\n' "$run_dir" "$seed_pid" "$peer_pid"
printf 'To open Creator, paste this command into a second terminal\n'
printf '%q %q -jar %q creator %q\n' "$JAVA" "-Duser.home=$demo_home" "$RAILIX_CLI_JAR" "$demo_project"
printf 'Choose Project actions > Connect live app, select either PID,\n'
printf 'and look for Mesh joined in the runtime details. Ctrl+C stops both replicas.\n\n'

tail -n +1 -f "$run_dir/seed.log" "$run_dir/peer.log" &
logs_pid=$!
while kill -0 "$seed_pid" 2>/dev/null && kill -0 "$peer_pid" 2>/dev/null; do
    sleep 1
done
cat "$run_dir/seed.log" "$run_dir/peer.log" >&2
fail "A demo replica stopped. Logs are in $run_dir."
