#!/usr/bin/env bash
set -euo pipefail

# Set PROJECT_FILE for a local run without an example name.
# Paths may be absolute or relative to this repository.
# Set RAILIX_CLI_JAR to skip building the CLI. Use JDK 25 for builds.
PROJECT_FILE=${PROJECT_FILE:-examples/auth-http-app/railix.project.json}
RAILIX_CLI_JAR=${RAILIX_CLI_JAR:-}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
KEYTOOL=${JAVA_HOME:+$JAVA_HOME/bin/}keytool

# Local demo settings. Give the replicas distinct application listener ports
# through the project's settings. auth-http-app uses HTTP next-free-port fallback.
DEMO_MESH_HOST=127.0.0.1
DEMO_SEED_PORT=${DEMO_SEED_PORT:-7401}
DEMO_PEER_PORT=${DEMO_PEER_PORT:-7402}
DEMO_FLOW_CAPACITY=${DEMO_FLOW_CAPACITY:-2}
DEMO_OFFLOAD_FLOW=${DEMO_OFFLOAD_FLOW:-}
DEMO_USER_HOME=${DEMO_USER_HOME:-}

# Deployment settings. Copy the same build output to each host.
# Set RAILIX_MESH_CONFIG per replica. Its JSON carries the shared meshId,
# listenEndpoint, advertisedEndpoint, bootstrapEndpoints, and flowCapacity.
# Set offloadFlow to the compiled Trigger ID on replicas that participate in offload.
# In identityStore and trustStore, set each PKCS12 path and passwordFile.
APPLICATION_JAR=${APPLICATION_JAR:-}
RAILIX_MESH_CONFIG=${RAILIX_MESH_CONFIG:-}
MESH_MAIN_CLASS=dev.nanonative.railix.core.project.RailixMeshApplication

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

usage() {
    printf 'Usage\n  %q [example-name]\n  %q join /path/to/run-directory mesh-port\n' "$0" "$0"
    printf '  APPLICATION_JAR=/path/to/application.jar RAILIX_MESH_CONFIG=/path/to/mesh.json %q deploy\n\n' "$0"
    cat <<'USAGE'
example-name  Start two loopback replicas of an app under examples/ with temporary certificates.
              Defaults to auth-http-app. Select an app with a long-running source.
join          Start one more replica in a running local demo on the given free mesh port.
deploy        Check the JAR with unzip and start one replica with provisioned credentials.
              Accepts application arguments.

Use RAILIX_CLI_JAR to reuse a packaged Railix CLI. For a custom demo project
that uses installed Steps, set DEMO_USER_HOME to the account's existing home.
Set DEMO_OFFLOAD_FLOW to a Trigger node ID to enable offload in the local demo.
Set DEMO_FLOW_CAPACITY to the execution slots per replica (default: 2).
Replicas started with join inherit both settings from the running demo.
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

valid_port() {
    [[ $1 =~ ^[1-9][0-9]{0,4}$ ]] && (($1 <= 65535))
}

uuid() {
    uuidgen | tr '[:upper:]' '[:lower:]'
}

mesh_keytool() (
    local directory=$1 store=$2
    shift 2
    cd "$directory"
    "$KEYTOOL" -storetype PKCS12 -keystore "$store.p12" -storepass:file "$store.password" "$@"
)

issue_replica() {
    local directory=$1
    local mesh_id=$2
    local port=$3
    local name=$4
    local store="$port/identity"
    local uri="https://github.com/NanoNative/nano_railix/mesh/v1/$mesh_id/replicas/$(uuid)"
    printf '%s\n' "$(uuid)" > "$directory/$store.password"
    mesh_keytool "$directory" "$store" -genkeypair -alias peer -dname "CN=Railix local $name" -keyalg EC
    mesh_keytool "$directory" "$store" -certreq -alias peer -file "$store.csr"
    mesh_keytool "$directory" issuer -gencert -alias root -infile "$store.csr" -outfile "$store.cer" \
        -validity 7 -ext "san=uri:$uri" -ext 'ku=digitalSignature' -ext 'eku=serverAuth,clientAuth'
    mesh_keytool "$directory" "$store" -importcert -noprompt -alias root -file root.cer
    mesh_keytool "$directory" "$store" -importcert -noprompt -alias peer -file "$store.cer"
}

bootstrap_endpoints() {
    local mesh_dir=$1 own_port=$2
    local directory port separator=
    printf '['
    for directory in "$mesh_dir"/*/; do
        [[ -d $directory ]] || continue
        directory=${directory%/}
        port=${directory##*/}
        if [[ $port != "$own_port" ]] && valid_port "$port"; then
            printf '%s{"host":"%s","port":%s}' "$separator" "$DEMO_MESH_HOST" "$port"
            separator=,
        fi
    done
    printf ']'
}

write_config() {
    local config=$1
    local mesh_id=$2
    local port=$3
    local bootstrap=$4
    local flow_capacity=$5
    local offload_flow=$6
    local offload_setting=
    if [[ -n $offload_flow ]]; then
        printf -v offload_setting '\n  "offloadFlow": "%s",' "$offload_flow"
    fi
    cat > "$config" <<JSON
{
  "meshId": "$mesh_id",
  "listenEndpoint": {"host": "$DEMO_MESH_HOST", "port": $port},
  "advertisedEndpoint": {"host": "$DEMO_MESH_HOST", "port": $port},
  "bootstrapEndpoints": $bootstrap,
  "flowCapacity": $flow_capacity,$offload_setting
  "identityStore": {"path": "identity.p12", "passwordFile": "identity.password"},
  "trustStore": {"path": "../trust.p12", "passwordFile": "../trust.password"}
}
JSON
}

# Store one value per line so paths can contain spaces and are read as data.
write_demo_settings() {
    local settings=$1
    local mesh_id=$2
    local home=$3
    local jar=$4
    local flow_capacity=$5
    local offload_flow=$6
    printf '%s\n' "$mesh_id" "$home" "$jar" "$flow_capacity" "$offload_flow" > "$settings"
}

read_demo_settings() {
    {
        IFS= read -r mesh_id &&
        IFS= read -r demo_home &&
        IFS= read -r app &&
        IFS= read -r DEMO_FLOW_CAPACITY || return 1
        IFS= read -r DEMO_OFFLOAD_FLOW || DEMO_OFFLOAD_FLOW=
    } < "$1"
}

# Run in a background job so exec makes $! the JVM's PID.
run_replica() {
    local config=$1
    local jar=$2
    local home=$3
    RAILIX_MESH_CONFIG="$config" exec "$JAVA" "-Duser.home=$home" -jar "$jar"
}

join_demo() {
    [[ $# -eq 2 ]] || fail 'Pass the demo run directory and a free mesh port to join.'
    valid_port "$2" || fail 'Use a mesh port from 1 to 65535.'
    run_dir=$(CDPATH= cd -- "$1" && pwd) || fail "Cannot read demo run directory $1."
    mesh_dir="$run_dir/mesh"
    port=$2
    [[ -f $mesh_dir/demo && -f $mesh_dir/issuer.p12 ]] || fail "No running local mesh in $run_dir."
    read_demo_settings "$mesh_dir/demo" || fail "Cannot read demo settings in $run_dir."

    umask 077
    replica_dir="$mesh_dir/$port"
    mkdir "$replica_dir" 2>/dev/null || fail "Cannot reserve mesh port $port in this demo. Choose another free port."
    # This join owns its port directory and has a PID once its JVM starts.
    replica_pid=
    cleanup_join() {
        if [[ -n $replica_pid ]]; then
            kill "$replica_pid" 2>/dev/null || true
            wait "$replica_pid" || true
        fi
        rm -rf -- "$replica_dir"
    }
    trap cleanup_join EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM

    issue_replica "$mesh_dir" "$mesh_id" "$port" "$port"
    replica_config="$replica_dir/mesh.json"
    bootstrap=$(bootstrap_endpoints "$mesh_dir" "$port")
    write_config "$replica_config" "$mesh_id" "$port" "$bootstrap" "$DEMO_FLOW_CAPACITY" "$DEMO_OFFLOAD_FLOW"
    printf 'Starting mesh replica on %s:%s. Ctrl+C stops this replica.\n' "$DEMO_MESH_HOST" "$port"
    run_replica "$replica_config" "$app" "$demo_home" &
    replica_pid=$!
    wait "$replica_pid"
}

mode=${1:-}
if [[ $# -gt 0 ]]; then
    shift
fi
case "$mode" in
    join)
        join_demo "$@"
        exit
        ;;
    deploy)
        [[ -n $APPLICATION_JAR && -f $APPLICATION_JAR && -r $APPLICATION_JAR ]] ||
            fail 'Set APPLICATION_JAR to a readable JAR produced by railix build --mesh.'
        [[ -n $RAILIX_MESH_CONFIG && -f $RAILIX_MESH_CONFIG && -r $RAILIX_MESH_CONFIG ]] ||
            fail 'Set RAILIX_MESH_CONFIG to the readable mesh JSON file for this replica.'
        require_mesh_jar "$APPLICATION_JAR"
        export RAILIX_MESH_CONFIG
        exec "$JAVA" -jar "$APPLICATION_JAR" "$@"
        ;;
    '')
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

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
[[ -f $PROJECT_FILE && -r $PROJECT_FILE ]] || fail "Cannot read project file $PROJECT_FILE."
project_dir=$(CDPATH= cd -- "$(dirname -- "$PROJECT_FILE")" && pwd)

[[ $# -eq 0 ]] || fail 'Pass one example name to start a local mesh.'
for port in "$DEMO_SEED_PORT" "$DEMO_PEER_PORT"; do
    valid_port "$port" || fail 'Use mesh ports from 1 to 65535.'
done
[[ $DEMO_SEED_PORT != "$DEMO_PEER_PORT" ]] || fail 'The seed and peer need distinct mesh ports.'
[[ $DEMO_FLOW_CAPACITY =~ ^[1-9][0-9]*$ ]] || fail 'DEMO_FLOW_CAPACITY must be a positive integer.'
[[ -z $DEMO_OFFLOAD_FLOW || $DEMO_OFFLOAD_FLOW =~ ^[a-z][a-z0-9-]{0,63}$ ]] ||
    fail 'DEMO_OFFLOAD_FLOW must be a Trigger node ID.'

if [[ -z $RAILIX_CLI_JAR ]]; then
    ./mvnw -pl modules/railix-creator -am -DskipTests package >&2
    RAILIX_CLI_JAR="$root/modules/railix-creator/target/railix.jar"
elif [[ $RAILIX_CLI_JAR != /* ]]; then
    RAILIX_CLI_JAR="$root/$RAILIX_CLI_JAR"
fi
[[ -f $RAILIX_CLI_JAR && -r $RAILIX_CLI_JAR ]] || fail "Cannot read Railix CLI JAR $RAILIX_CLI_JAR."

umask 077
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/railix-local-mesh.XXXXXX")
mesh_dir="$run_dir/mesh"
# The demo launcher owns its two JVMs, the log follower, and the shared credentials.
# A PID stays empty until its job starts so cleanup can handle a partial launch.
seed_pid=
peer_pid=
logs_pid=
cleanup_demo() {
    for pid in "$seed_pid" "$peer_pid" "$logs_pid"; do
        if [[ -n $pid ]]; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    wait || true
    rm -rf -- "$mesh_dir"
}
trap cleanup_demo EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

replica_stopped() {
    local name=$1 port=$2 pid=$3 status=0
    local log="$run_dir/$name.log"
    wait "$pid" || status=$?
    # The launcher emits these diagnostics before mesh peer processing starts.
    if grep -Eq '^(MESH_STARTUP_FAILED:|SOURCE_START_FAILED |STEP_PRE_(FAILED|RESULT_INVALID) )' "$log"; then
        fail "The $name replica failed to start (exit $status). Logs are in $run_dir."
    fi
    printf 'The %s replica stopped (exit %s). See %s.\n' "$name" "$status" "$log" >&2
    rm -rf -- "$mesh_dir/$port"
}

demo_home=${DEMO_USER_HOME:-$run_dir/home}
mkdir -p "$mesh_dir" "$demo_home"
demo_home=$(CDPATH= cd -- "$demo_home" && pwd)
demo_project="$run_dir/railix.project.json"

cp -- "$PROJECT_FILE" "$demo_project"
for document in railix.creator.json railix.examples.json railix.custom-steps.json railix.dependencies.lock.json; do
    if [[ -f $project_dir/$document ]]; then
        cp "$project_dir/$document" "$run_dir/"
    fi
done

mesh_id=$(uuid)
for store in issuer trust; do
    printf '%s\n' "$(uuid)" > "$mesh_dir/$store.password"
done
mesh_keytool "$mesh_dir" issuer -genkeypair -alias root -dname 'CN=Railix local mesh CA' \
    -keyalg EC -validity 14 -ext 'bc:c=ca:true' -ext 'ku:c=keyCertSign,cRLSign'
mesh_keytool "$mesh_dir" issuer -exportcert -alias root -file root.cer
mesh_keytool "$mesh_dir" trust -importcert -noprompt -alias root -file root.cer

mkdir "$mesh_dir/$DEMO_SEED_PORT" "$mesh_dir/$DEMO_PEER_PORT"
issue_replica "$mesh_dir" "$mesh_id" "$DEMO_SEED_PORT" seed
issue_replica "$mesh_dir" "$mesh_id" "$DEMO_PEER_PORT" peer
seed_config="$mesh_dir/$DEMO_SEED_PORT/mesh.json"
peer_config="$mesh_dir/$DEMO_PEER_PORT/mesh.json"
peer_bootstrap=$(bootstrap_endpoints "$mesh_dir" "$DEMO_PEER_PORT")
write_config "$seed_config" "$mesh_id" "$DEMO_SEED_PORT" '[]' "$DEMO_FLOW_CAPACITY" "$DEMO_OFFLOAD_FLOW"
write_config "$peer_config" "$mesh_id" "$DEMO_PEER_PORT" "$peer_bootstrap" "$DEMO_FLOW_CAPACITY" "$DEMO_OFFLOAD_FLOW"
app=$("$JAVA" "-Duser.home=$demo_home" -jar "$RAILIX_CLI_JAR" build "$demo_project" --mesh)
write_demo_settings "$mesh_dir/demo" "$mesh_id" "$demo_home" "$app" "$DEMO_FLOW_CAPACITY" "$DEMO_OFFLOAD_FLOW"

touch "$run_dir/seed.log" "$run_dir/peer.log"
run_replica "$seed_config" "$app" "$demo_home" > "$run_dir/seed.log" 2>&1 &
seed_pid=$!
run_replica "$peer_config" "$app" "$demo_home" > "$run_dir/peer.log" 2>&1 &
peer_pid=$!

join_port=7403
while [[ $join_port == "$DEMO_SEED_PORT" || $join_port == "$DEMO_PEER_PORT" ]]; do
    join_port=$((join_port + 1))
done
printf '\nRun directory %s\nSeed PID %s | Peer PID %s\n' "$run_dir" "$seed_pid" "$peer_pid"
printf 'After both replicas start, use this command to add another replica\n'
printf '%q join %q %q\n' "$root/scripts/run-mesh.sh" "$run_dir" "$join_port"
printf 'Use a different free mesh port for each replica.\n\n'
printf 'To open Creator, paste this command into another terminal\n'
printf '%q %q -jar %q creator %q\n' "$JAVA" "-Duser.home=$demo_home" "$RAILIX_CLI_JAR" "$demo_project"
printf 'Choose Project actions > Connect live app, select a replica,\n'
printf 'and look for Mesh joined in the runtime details. Ctrl+C stops the replicas started here.\n\n'

tail -n +1 -f "$run_dir/seed.log" "$run_dir/peer.log" &
logs_pid=$!
while [[ -n $seed_pid || -n $peer_pid ]]; do
    sleep 1
    if [[ -n $seed_pid ]] && ! kill -0 "$seed_pid" 2>/dev/null; then
        stopped_pid=$seed_pid
        seed_pid=
        replica_stopped seed "$DEMO_SEED_PORT" "$stopped_pid"
    fi
    if [[ -n $peer_pid ]] && ! kill -0 "$peer_pid" 2>/dev/null; then
        stopped_pid=$peer_pid
        peer_pid=
        replica_stopped peer "$DEMO_PEER_PORT" "$stopped_pid"
    fi
done
fail "Both initial demo replicas stopped. Logs are in $run_dir."
