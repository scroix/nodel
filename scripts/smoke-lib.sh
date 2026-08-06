# Shared plumbing for the smoke suites (compat-smoke.sh, polyglot-smoke.sh):
# logging, GraalVM 25 resolution, host-jar discovery, the FIFO-backed host
# launch and the REST poll/round-trip helpers. Source this file — it defines
# functions only (no side effects).

log()  { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# Prints a GraalVM 25 executable: the $SMOKE_JAVA override, a Gradle-provisioned
# toolchain, the system JDK, or bare 'java' as a last resort.
find_java() {
    if [ -n "${SMOKE_JAVA:-}" ]; then echo "$SMOKE_JAVA"; return; fi
    # a GraalVM 25 toolchain Gradle provisioned earlier
    local candidate
    while IFS= read -r candidate; do
        [ -x "$candidate" ] || continue
        if "$candidate" -version 2>&1 | grep -qE '^(openjdk|java)( version)? "?25' \
                && "$candidate" -version 2>&1 | grep -q 'GraalVM CE 25\.2\.4'; then
            echo "$candidate"; return
        fi
    done < <(find "$HOME/.gradle/jdks" -name java -type f -path '*/bin/java' 2>/dev/null)
    # a system GraalVM 25
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        if home=$(/usr/libexec/java_home -v 25+ 2>/dev/null); then
            if "$home/bin/java" -version 2>&1 | grep -q 'GraalVM CE 25\.2\.4'; then
                echo "$home/bin/java"; return
            fi
        fi
    fi
    command -v java || true
}

# Resolves and validates $JAVA (the supported host runtime is GraalVM CE 25.2.4).
require_graalvm25() {
    JAVA="$(find_java)"
    [ -n "$JAVA" ] || fail "no java found; set SMOKE_JAVA"
    "$JAVA" -version 2>&1 | grep -qE '^(openjdk|java)( version)? "?25' \
        || fail "need GraalVM CE 25.2.4 to run the host; found: $("$JAVA" -version 2>&1 | head -1). Set SMOKE_JAVA."
    "$JAVA" -version 2>&1 | grep -q 'GraalVM CE 25\.2\.4' \
        || fail "need GraalVM CE 25.2.4 to run the host; found a different Java distribution. Set SMOKE_JAVA."
    log "using java: $JAVA"
}

# Resolves $GRAAL_NODEL_JAR (newest build output), building it if necessary.
resolve_graal_jar() { # <repo-root>
    local root="$1"
    if [ -z "${GRAAL_NODEL_JAR:-}" ]; then
        GRAAL_NODEL_JAR="$(ls -t "$root"/nodel-jyhost/build/distributions/standalone/nodelhost-*.jar 2>/dev/null | head -1 || true)"
        if [ -z "$GRAAL_NODEL_JAR" ]; then
            log "building GraalVM host jar"
            (cd "$root" && ./gradlew -q :nodel-jyhost:shadowJar)
            GRAAL_NODEL_JAR="$(ls -t "$root"/nodel-jyhost/build/distributions/standalone/nodelhost-*.jar | head -1)"
        fi
    fi
    log "graal jar: $GRAAL_NODEL_JAR"
}

# The host shuts down when stdin reaches EOF, so each host reads from a named
# FIFO whose write end the calling script holds open (the given fd) for its
# lifetime. Sets STARTED_PID.
#
# <jar-or-launcher> is normally a jar (run via $JAVA -jar); anything not ending
# in .jar is treated as a self-contained launcher (e.g. a jpackage app-image
# binary) and executed directly — lets the suites gate a packaged artifact.
start_host() { # <home> <jar-or-launcher> <port> <stdin-fd>
    local home="$1" jar="$2" port="$3" fd="$4"
    mkfifo "$home/.stdin"
    case "$jar" in
        *.jar) set -- "$JAVA" -jar "$jar" ;;
        *)     set -- "$jar" ;;
    esac
    ( cd "$home" && exec "$@" -p "$port" <.stdin >output.log 2>error.log ) &
    STARTED_PID=$!
    eval "exec $fd>'$home/.stdin'"
}

wait_http() { # <port> <label>
    local port="$1" label="$2" i
    for i in $(seq 1 60); do
        if curl -sf -o /dev/null "http://127.0.0.1:$port/"; then
            log "$label is up on :$port"
            return 0
        fi
        sleep 1
    done
    fail "$label did not come up on :$port (check output.log/error.log in its home dir)"
}

invoke() { # <port> <node> <action> <arg>
    curl -sf -X POST -H 'Content-Type: application/json' -d "{\"arg\": \"$4\"}" \
        "http://127.0.0.1:$1/REST/nodes/$2/actions/$3/call" >/dev/null
}

console_contains() { # <port> <node> <text>
    curl -sf "http://127.0.0.1:$1/REST/nodes/$2/console?from=0&max=500" | grep -F "$3" >/dev/null
}

# poll until a node's console shows a marker (e.g. a boot message)
wait_console_marker() { # <port> <node> <marker> <label> [tries]
    local port="$1" node="$2" marker="$3" label="$4" tries="${5:-45}" i
    for i in $(seq 1 "$tries"); do
        if console_contains "$port" "$node" "$marker"; then
            printf 'PASS: %s\n' "$label"
            return 0
        fi
        sleep 1
    done
    printf 'FAIL: %s\n' "$label" >&2
    return 1
}

# poll: invoke <src> repeatedly until <dst> console shows the marker
check_roundtrip() { # <label> <src-port> <src-node> <action> <marker> <dst-port> <dst-node> <expected>
    local label="$1" sport="$2" snode="$3" action="$4" marker="$5" dport="$6" dnode="$7" expected="$8" i
    for i in $(seq 1 45); do
        invoke "$sport" "$snode" "$action" "$marker" || true
        sleep 2
        if console_contains "$dport" "$dnode" "$expected"; then
            printf 'PASS: %s\n' "$label"
            return 0
        fi
    done
    printf 'FAIL: %s (marker %s never arrived)\n' "$label" "$marker" >&2
    return 1
}

# Writes the shared JavaScript peer fixture recipe (GraalJS, goal 2) — the
# single source for both suites' JS peers.
write_js_peer_script() { # <file> <started-message>
    local file="$1" started="$2"
    cat > "$file" <<EOF
var local_event_Ping = LocalEvent({ title: 'Ping', schema: { type: 'string' } });
var remote_action_RemotePoke = RemoteAction({ title: 'Remote Poke', schema: { type: 'string' } });
var param_Prefix = Parameter({ title: 'Prefix', schema: { type: 'string' } });

function local_action_SendPing(arg) {
    console.info('ping sent: ' + arg);
    local_event_Ping.emit(arg);
}

function local_action_Poke(arg) {
    console.info('poked: ' + arg);
}

function local_action_PokePeer(arg) {
    console.info('poking peer: ' + arg);
    remote_action_RemotePoke.call(arg);
}

function local_action_ShowPrefix() {
    console.info('prefix is: ' + param_Prefix);
}

function remote_event_PeerPing(arg) {
    console.info('peer ping received: ' + arg);
}

function main() {
    console.info('${started}');
}
EOF
}
