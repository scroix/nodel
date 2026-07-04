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

. "$ROOT/scripts/smoke-lib.sh"

cleanup() {
    [ -n "$HOST_PID" ] && kill "$HOST_PID" 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------- java (21+)
require_java21

# ---------------------------------------------------------------------- jar
mkdir -p "$WORK"

resolve_graal_jar "$ROOT"

# ------------------------------------------------------------------ recipes
PG_HOME="$WORK/polyglot-host"
rm -rf "$PG_HOME"
mkdir -p "$PG_HOME/nodes/JS Peer" "$PG_HOME/nodes/Py Peer"

# --- the JavaScript node (GraalJS) — the shared fixture from smoke-lib.sh
write_js_peer_script "$PG_HOME/nodes/JS Peer/script.js" "js peer started"

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
# (start_host holds the FIFO write end open on fd 9 for the script's lifetime)
log "starting host on :$PG_PORT"
start_host "$PG_HOME" "$GRAAL_NODEL_JAR" "$PG_PORT" 9
HOST_PID=$STARTED_PID
wait_http "$PG_PORT" "polyglot host"

# --------------------------------------------------------------- assertions
# (invoke / console_contains / check_roundtrip come from smoke-lib.sh)

STAMP=$$-$(date +%s)
RESULT=0

log "checking both node types initialised in one host"
wait_console_marker "$PG_PORT" JSPeer "js peer started" "JavaScript node booted (script.js)" 30 || RESULT=1
wait_console_marker "$PG_PORT" PyPeer "py peer started" "Python node booted (script.py)" 30 || RESULT=1

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
        "$PG_PORT" JSPeer ShowPrefix "" "$PG_PORT" JSPeer "prefix is: pfx-$STAMP" || RESULT=1
else
    echo "FAIL: JS parameter save rejected" >&2; RESULT=1
fi

log "checking event propagation JS -> Python"
check_roundtrip "event JS->Python" \
    "$PG_PORT" JSPeer SendPing "ev-js2py-$STAMP" "$PG_PORT" PyPeer "peer ping received: ev-js2py-$STAMP" || RESULT=1

log "checking event propagation Python -> JS"
check_roundtrip "event Python->JS" \
    "$PG_PORT" PyPeer SendPing "ev-py2js-$STAMP" "$PG_PORT" JSPeer "peer ping received: ev-py2js-$STAMP" || RESULT=1

log "checking remote action JS -> Python"
check_roundtrip "action JS->Python" \
    "$PG_PORT" JSPeer PokePeer "ac-js2py-$STAMP" "$PG_PORT" PyPeer "poked: ac-js2py-$STAMP" || RESULT=1

log "checking remote action Python -> JS"
check_roundtrip "action Python->JS" \
    "$PG_PORT" PyPeer PokePeer "ac-py2js-$STAMP" "$PG_PORT" JSPeer "poked: ac-py2js-$STAMP" || RESULT=1

if [ "$RESULT" -eq 0 ]; then
    log "ALL POLYGLOT CHECKS PASSED"
else
    log "POLYGLOT CHECKS FAILED (host logs: $PG_HOME/)"
fi
exit "$RESULT"
