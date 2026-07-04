#!/usr/bin/env bash
#
# Wire-compatibility smoke test: stock Jython Nodel release vs the GraalVM host.
#
# Runs both hosts side-by-side on this machine (real multicast discovery and the
# Nodel TCP binding protocol — nothing mocked), each hosting a "peer" node bound
# to the other, then asserts:
#
#   1. Jython event  -> GraalVM remote event handler   (A -> B)
#   2. GraalVM event -> Jython remote event handler    (B -> A)
#   3. Jython remote action -> GraalVM local action    (A -> B)
#   4. GraalVM remote action -> Jython local action    (B -> A)
#
# This is the regression gate for POLYGLOT goals 2 & 3 (see the goal spec /
# POLYGLOT_INTEGRATION.md).
#
# Environment overrides:
#   STOCK_NODEL_VERSION  release tag to test against (default v2.2.1.542)
#   STOCK_NODEL_JAR      path to an existing stock jar (skips download)
#   GRAAL_NODEL_JAR      path to the GraalVM host jar (default: build output)
#   SMOKE_JAVA           java executable to run both hosts (needs 21+)
#   JY_PORT / GR_PORT    HTTP ports (defaults 8195 / 8196)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/compat-smoke"
STOCK_NODEL_VERSION="${STOCK_NODEL_VERSION:-v2.2.1.542}"
JY_PORT="${JY_PORT:-8195}"
GR_PORT="${GR_PORT:-8196}"

JY_PID=""
GR_PID=""

log()  { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

cleanup() {
    [ -n "$JY_PID" ] && kill "$JY_PID" 2>/dev/null || true
    [ -n "$GR_PID" ] && kill "$GR_PID" 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------- java (21+)
find_java() {
    if [ -n "${SMOKE_JAVA:-}" ]; then echo "$SMOKE_JAVA"; return; fi
    # a JDK 21 the Gradle toolchain provisioned earlier
    local candidate
    while IFS= read -r candidate; do
        [ -x "$candidate" ] || continue
        if "$candidate" -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
            echo "$candidate"; return
        fi
    done < <(find "$HOME/.gradle/jdks" -name java -type f -path '*/bin/java' 2>/dev/null)
    # a system JDK 21
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        if home=$(/usr/libexec/java_home -v 21+ 2>/dev/null); then
            echo "$home/bin/java"; return
        fi
    fi
    command -v java || true
}

JAVA="$(find_java)"
[ -n "$JAVA" ] || fail "no java found; set SMOKE_JAVA"
"$JAVA" -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])' \
    || fail "need Java 21+ to run the GraalVM host; found: $("$JAVA" -version 2>&1 | head -1). Set SMOKE_JAVA."
log "using java: $JAVA"

# ---------------------------------------------------------------------- jars
mkdir -p "$WORK"

if [ -z "${STOCK_NODEL_JAR:-}" ]; then
    # v2.2.1.542 -> nodelhost-release-2.2.1-rev542.jar
    base="${STOCK_NODEL_VERSION#v}"                 # 2.2.1.542
    rev="${base##*.}"                               # 542
    version="${base%.*}"                            # 2.2.1
    asset="nodelhost-release-${version}-rev${rev}.jar"
    STOCK_NODEL_JAR="$WORK/$asset"
    if [ ! -f "$STOCK_NODEL_JAR" ]; then
        log "downloading stock release $STOCK_NODEL_VERSION"
        curl -fsSL -o "$STOCK_NODEL_JAR" \
            "https://github.com/museumsvictoria/nodel/releases/download/${STOCK_NODEL_VERSION}/${asset}" \
            || fail "could not download $asset"
    fi
fi
log "stock jar: $STOCK_NODEL_JAR"

if [ -z "${GRAAL_NODEL_JAR:-}" ]; then
    GRAAL_NODEL_JAR="$(ls -t "$ROOT"/nodel-jyhost/build/distributions/standalone/nodelhost-*.jar 2>/dev/null | head -1 || true)"
    if [ -z "$GRAAL_NODEL_JAR" ]; then
        log "building GraalVM host jar"
        (cd "$ROOT" && ./gradlew -q :nodel-jyhost:shadowJar)
        GRAAL_NODEL_JAR="$(ls -t "$ROOT"/nodel-jyhost/build/distributions/standalone/nodelhost-*.jar | head -1)"
    fi
fi
log "graal jar: $GRAAL_NODEL_JAR"

# ------------------------------------------------------------------ recipes
JY_HOME="$WORK/jython-host"
GR_HOME="$WORK/graal-host"
rm -rf "$JY_HOME" "$GR_HOME"
mkdir -p "$JY_HOME/nodes/Jython Peer" "$GR_HOME/nodes/Graal Peer"

# --- stock host node: legacy Jython 2.5 recipe
cat > "$JY_HOME/nodes/Jython Peer/script.py" <<'EOF'
local_event_Ping = LocalEvent({'title': 'Ping', 'schema': {'type': 'string'}})
remote_action_RemotePoke = RemoteAction({'title': 'Remote Poke', 'schema': {'type': 'string'}})

def local_action_SendPing(arg):
    console.info('ping sent: %s' % arg)
    local_event_Ping.emit(arg)

def local_action_Poke(arg):
    console.info('poked: %s' % arg)

def local_action_PokePeer(arg):
    console.info('poking peer: %s' % arg)
    remote_action_RemotePoke.call(arg)

def remote_event_PeerPing(arg):
    console.info('peer ping received: %s' % arg)

def main():
    console.info('jython peer started')
EOF

cat > "$JY_HOME/nodes/Jython Peer/nodeConfig.json" <<'EOF'
{
    "remoteBindingValues": {
        "actions": {"RemotePoke": {"node": "Graal Peer", "action": "Poke"}},
        "events": {"PeerPing": {"node": "Graal Peer", "event": "Ping"}}
    },
    "paramValues": {}
}
EOF

# --- GraalVM host node: modern Python 3 recipe (f-strings, decorators)
cat > "$GR_HOME/nodes/Graal Peer/script.py" <<'EOF'
local_event_Ping = LocalEvent({'title': 'Ping', 'schema': {'type': 'string'}})
remote_action_RemotePoke = RemoteAction({'title': 'Remote Poke', 'schema': {'type': 'string'}})

@local_action({'schema': {'type': 'string'}})
def SendPing(arg):
    print(f'ping sent: {arg}')
    local_event_Ping.emit(arg)

@local_action({'schema': {'type': 'string'}})
def Poke(arg):
    print(f'poked: {arg}')

@local_action({'schema': {'type': 'string'}})
def PokePeer(arg):
    print(f'poking peer: {arg}')
    remote_action_RemotePoke.call(arg)

def remote_event_PeerPing(arg):
    print(f'peer ping received: {arg}')

def main():
    print('graal peer started')
EOF

cat > "$GR_HOME/nodes/Graal Peer/nodeConfig.json" <<'EOF'
{
    "remoteBindingValues": {
        "actions": {"RemotePoke": {"node": "Jython Peer", "action": "Poke"}},
        "events": {"PeerPing": {"node": "Jython Peer", "event": "Ping"}}
    },
    "paramValues": {}
}
EOF

# -------------------------------------------------------------------- hosts
# The host shuts down when stdin reaches EOF, so each host reads from a named
# FIFO whose write end this script holds open (fd 8 / fd 9) for its lifetime.
start_host() { # <home> <jar> <port> <stdin-fd>; sets STARTED_PID
    local home="$1" jar="$2" port="$3" fd="$4"
    mkfifo "$home/.stdin"
    ( cd "$home" && exec "$JAVA" -jar "$jar" -p "$port" <.stdin >output.log 2>error.log ) &
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
    fail "$label did not come up on :$port (check logs under $WORK)"
}

log "starting stock Jython host on :$JY_PORT"
start_host "$JY_HOME" "$STOCK_NODEL_JAR" "$JY_PORT" 8
JY_PID=$STARTED_PID

log "starting GraalVM host on :$GR_PORT"
start_host "$GR_HOME" "$GRAAL_NODEL_JAR" "$GR_PORT" 9
GR_PID=$STARTED_PID

wait_http "$JY_PORT" "Jython host"
wait_http "$GR_PORT" "GraalVM host"

# --------------------------------------------------------------- assertions
invoke() { # <port> <node> <action> <arg>
    curl -sf -X POST -H 'Content-Type: application/json' -d "{\"arg\": \"$4\"}" \
        "http://127.0.0.1:$1/REST/nodes/$2/actions/$3/call" >/dev/null
}

console_contains() { # <port> <node> <text>
    curl -sf "http://127.0.0.1:$1/REST/nodes/$2/console?from=0&max=500" | grep -qF "$3"
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

STAMP=$$-$(date +%s)
RESULT=0

log "checking event propagation Jython -> GraalVM"
check_roundtrip "event Jython->GraalVM" \
    "$JY_PORT" JythonPeer SendPing "ev-jy2gr-$STAMP" \
    "$GR_PORT" GraalPeer "peer ping received: ev-jy2gr-$STAMP" || RESULT=1

log "checking event propagation GraalVM -> Jython"
check_roundtrip "event GraalVM->Jython" \
    "$GR_PORT" GraalPeer SendPing "ev-gr2jy-$STAMP" \
    "$JY_PORT" JythonPeer "peer ping received: ev-gr2jy-$STAMP" || RESULT=1

log "checking remote action Jython -> GraalVM"
check_roundtrip "action Jython->GraalVM" \
    "$JY_PORT" JythonPeer PokePeer "ac-jy2gr-$STAMP" \
    "$GR_PORT" GraalPeer "poked: ac-jy2gr-$STAMP" || RESULT=1

log "checking remote action GraalVM -> Jython"
check_roundtrip "action GraalVM->Jython" \
    "$GR_PORT" GraalPeer PokePeer "ac-gr2jy-$STAMP" \
    "$JY_PORT" JythonPeer "poked: ac-gr2jy-$STAMP" || RESULT=1

# mutual discovery is implied by the bindings above wiring up at all, but assert
# the advertised-node views cross-registered too (nodeURLs is the same endpoint
# the web UI's node browser uses)
log "checking mutual discovery (nodeURLs)"
discovered() { # <port> <own-node> <peer-name>
    curl -sf "http://127.0.0.1:$1/REST/nodes/$2/nodeURLs" | grep -qiE "$(echo "$3" | tr -d ' ')|$3"
}
if discovered "$JY_PORT" JythonPeer "Graal Peer"; then
    echo "PASS: Jython host discovered 'Graal Peer'"
else
    echo "FAIL: Jython host never discovered 'Graal Peer'" >&2; RESULT=1
fi
if discovered "$GR_PORT" GraalPeer "Jython Peer"; then
    echo "PASS: GraalVM host discovered 'Jython Peer'"
else
    echo "FAIL: GraalVM host never discovered 'Jython Peer'" >&2; RESULT=1
fi

if [ "$RESULT" -eq 0 ]; then
    log "ALL WIRE-COMPATIBILITY CHECKS PASSED"
else
    log "WIRE-COMPATIBILITY CHECKS FAILED (host logs: $JY_HOME/, $GR_HOME/)"
fi
exit "$RESULT"
