#!/usr/bin/env bash
#
# Polyglot smoke test: JavaScript ('script.js' / GraalJS) and Python
# ('script.py' / GraalPy) nodes running side-by-side in ONE GraalVM host.
#
# Extends the goal-1 wire-compatibility suite (compat-smoke.sh) for POLYGLOT
# goal 2 — asserts:
#
#   1. Both node types boot in the same host (language dispatch)
#   2. The JS node's actions / events / parameters are discovered and
#      visible over REST (BindingsExtractor on the JS global object)
#   3. A saved parameter survives the reload and reaches the JS script
#   4. JS event  -> Python remote event handler   (JS -> Py)
#   5. Py event  -> JS remote event handler       (Py -> JS)
#   6. JS remote action -> Python local action    (JS -> Py)
#   7. Py remote action -> JS local action        (Py -> JS)
#
# Environment overrides:
#   GRAAL_NODEL_JAR      path to the GraalVM host jar (default: build output)
#   SMOKE_JAVA           java executable (needs 21+)
#   PG_PORT              HTTP port (default 8197)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/polyglot-smoke"
PG_PORT="${PG_PORT:-8197}"

HOST_PID=""

log()  { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

cleanup() {
    [ -n "$HOST_PID" ] && kill "$HOST_PID" 2>/dev/null || true
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

# ---------------------------------------------------------------------- jar
mkdir -p "$WORK"

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
PG_HOME="$WORK/polyglot-host"
rm -rf "$PG_HOME"
mkdir -p "$PG_HOME/nodes/JS Peer" "$PG_HOME/nodes/Py Peer"

# --- the JavaScript node (GraalJS)
cat > "$PG_HOME/nodes/JS Peer/script.js" <<'EOF'
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
    console.info('js peer started');
}
EOF

cat > "$PG_HOME/nodes/JS Peer/nodeConfig.json" <<'EOF'
{
    "remoteBindingValues": {
        "actions": {"RemotePoke": {"node": "Py Peer", "action": "Poke"}},
        "events": {"PeerPing": {"node": "Py Peer", "event": "Ping"}}
    },
    "paramValues": {}
}
EOF

# --- the Python node (GraalPy)
cat > "$PG_HOME/nodes/Py Peer/script.py" <<'EOF'
local_event_Ping = LocalEvent({'title': 'Ping', 'schema': {'type': 'string'}})
remote_action_RemotePoke = RemoteAction({'title': 'Remote Poke', 'schema': {'type': 'string'}})

def local_action_SendPing(arg):
    console.info(f'ping sent: {arg}')
    local_event_Ping.emit(arg)

def local_action_Poke(arg):
    console.info(f'poked: {arg}')

def local_action_PokePeer(arg):
    console.info(f'poking peer: {arg}')
    remote_action_RemotePoke.call(arg)

def remote_event_PeerPing(arg):
    console.info(f'peer ping received: {arg}')

def main():
    console.info('py peer started')
EOF

cat > "$PG_HOME/nodes/Py Peer/nodeConfig.json" <<'EOF'
{
    "remoteBindingValues": {
        "actions": {"RemotePoke": {"node": "JS Peer", "action": "Poke"}},
        "events": {"PeerPing": {"node": "JS Peer", "event": "Ping"}}
    },
    "paramValues": {}
}
EOF

# --------------------------------------------------------------------- host
# The host shuts down when stdin reaches EOF, so it reads from a named FIFO
# whose write end this script holds open (fd 9) for its lifetime.
mkfifo "$PG_HOME/.stdin"
( cd "$PG_HOME" && exec "$JAVA" -jar "$GRAAL_NODEL_JAR" -p "$PG_PORT" <.stdin >output.log 2>error.log ) &
HOST_PID=$!
exec 9>"$PG_HOME/.stdin"

log "waiting for host on :$PG_PORT"
for i in $(seq 1 60); do
    if curl -sf -o /dev/null "http://127.0.0.1:$PG_PORT/"; then
        log "host is up on :$PG_PORT"
        break
    fi
    sleep 1
    [ "$i" -eq 60 ] && fail "host did not come up on :$PG_PORT (check logs under $PG_HOME)"
done

# --------------------------------------------------------------- assertions
invoke() { # <node> <action> <arg>
    curl -sf -X POST -H 'Content-Type: application/json' -d "{\"arg\": \"$3\"}" \
        "http://127.0.0.1:$PG_PORT/REST/nodes/$1/actions/$2/call" >/dev/null
}

console_contains() { # <node> <text>
    curl -sf "http://127.0.0.1:$PG_PORT/REST/nodes/$1/console?from=0&max=500" | grep -qF "$2"
}

# poll: invoke <src> repeatedly until <dst> console shows the marker
check_roundtrip() { # <label> <src-node> <action> <marker> <dst-node> <expected>
    local label="$1" snode="$2" action="$3" marker="$4" dnode="$5" expected="$6" i
    for i in $(seq 1 45); do
        invoke "$snode" "$action" "$marker" || true
        sleep 2
        if console_contains "$dnode" "$expected"; then
            printf 'PASS: %s\n' "$label"
            return 0
        fi
    done
    printf 'FAIL: %s (marker %s never arrived)\n' "$label" "$marker" >&2
    return 1
}

STAMP=$$-$(date +%s)
RESULT=0

log "checking both node types initialised in one host"
node_started() { # <node> <marker> <label>
    local i
    for i in $(seq 1 30); do
        if console_contains "$1" "$2"; then
            printf 'PASS: %s\n' "$3"
            return 0
        fi
        sleep 1
    done
    printf 'FAIL: %s\n' "$3" >&2
    return 1
}
node_started JSPeer "js peer started" "JavaScript node booted (script.js)" || RESULT=1
node_started PyPeer "py peer started" "Python node booted (script.py)" || RESULT=1

log "checking the JS node's bindings are visible over REST"
if curl -sf "http://127.0.0.1:$PG_PORT/REST/nodes/JSPeer/actions" | grep -q '"SendPing"'; then
    echo "PASS: JS actions extracted"
else
    echo "FAIL: JS actions not extracted" >&2; RESULT=1
fi
if curl -sf "http://127.0.0.1:$PG_PORT/REST/nodes/JSPeer/events" | grep -q '"Ping"'; then
    echo "PASS: JS events extracted"
else
    echo "FAIL: JS events not extracted" >&2; RESULT=1
fi
if curl -sf "http://127.0.0.1:$PG_PORT/REST/nodes/JSPeer/params/schema" | grep -q '"Prefix"'; then
    echo "PASS: JS parameters extracted"
else
    echo "FAIL: JS parameters not extracted" >&2; RESULT=1
fi

log "checking JS parameter save / reload round trip"
if curl -sf -X POST -H 'Content-Type: application/json' -d "{\"Prefix\": \"pfx-$STAMP\"}" \
        "http://127.0.0.1:$PG_PORT/REST/nodes/JSPeer/params/save" >/dev/null; then
    check_roundtrip "JS parameter round trip" \
        JSPeer ShowPrefix "" JSPeer "prefix is: pfx-$STAMP" || RESULT=1
else
    echo "FAIL: JS parameter save rejected" >&2; RESULT=1
fi

log "checking event propagation JS -> Python"
check_roundtrip "event JS->Python" \
    JSPeer SendPing "ev-js2py-$STAMP" PyPeer "peer ping received: ev-js2py-$STAMP" || RESULT=1

log "checking event propagation Python -> JS"
check_roundtrip "event Python->JS" \
    PyPeer SendPing "ev-py2js-$STAMP" JSPeer "peer ping received: ev-py2js-$STAMP" || RESULT=1

log "checking remote action JS -> Python"
check_roundtrip "action JS->Python" \
    JSPeer PokePeer "ac-js2py-$STAMP" PyPeer "poked: ac-js2py-$STAMP" || RESULT=1

log "checking remote action Python -> JS"
check_roundtrip "action Python->JS" \
    PyPeer PokePeer "ac-py2js-$STAMP" JSPeer "poked: ac-py2js-$STAMP" || RESULT=1

if [ "$RESULT" -eq 0 ]; then
    log "ALL POLYGLOT CHECKS PASSED"
else
    log "POLYGLOT CHECKS FAILED (host logs: $PG_HOME/)"
fi
exit "$RESULT"
