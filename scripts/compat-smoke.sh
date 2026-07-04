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
# The GraalVM host additionally runs a JavaScript node ('script.js' under
# GraalJS — goal 2) cross-bound to the stock Jython peer, asserting:
#
#   5. GraalJS event -> Jython remote event handler    (JS -> A)
#   6. Jython event  -> GraalJS remote event handler   (A -> JS)
#   7. GraalJS remote action -> Jython local action    (JS -> A)
#   8. Jython remote action -> GraalJS local action    (A -> JS)
#
# This is the regression gate for POLYGLOT goals 2 & 3 (see the goal spec /
# POLYGLOT_INTEGRATION.md). See also polyglot-smoke.sh (JS <-> Python nodes
# within one host).
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

. "$ROOT/scripts/smoke-lib.sh"

cleanup() {
    [ -n "$JY_PID" ] && kill "$JY_PID" 2>/dev/null || true
    [ -n "$GR_PID" ] && kill "$GR_PID" 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------- java (21+)
require_java21

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

resolve_graal_jar "$ROOT"

# ------------------------------------------------------------------ recipes
JY_HOME="$WORK/jython-host"
GR_HOME="$WORK/graal-host"
rm -rf "$JY_HOME" "$GR_HOME"
mkdir -p "$JY_HOME/nodes/Jython Peer" "$GR_HOME/nodes/Graal Peer"

# --- stock host node: legacy Jython 2.5 recipe
# (bound to BOTH GraalVM-host peers: the Python one and the JavaScript one)
cat > "$JY_HOME/nodes/Jython Peer/script.py" <<'EOF'
local_event_Ping = LocalEvent({'title': 'Ping', 'schema': {'type': 'string'}})
remote_action_RemotePoke = RemoteAction({'title': 'Remote Poke', 'schema': {'type': 'string'}})
remote_action_RemotePokeJs = RemoteAction({'title': 'Remote Poke JS', 'schema': {'type': 'string'}})

def local_action_SendPing(arg):
    console.info('ping sent: %s' % arg)
    local_event_Ping.emit(arg)

def local_action_Poke(arg):
    console.info('poked: %s' % arg)

def local_action_PokePeer(arg):
    console.info('poking peer: %s' % arg)
    remote_action_RemotePoke.call(arg)

def local_action_PokeJsPeer(arg):
    console.info('poking js peer: %s' % arg)
    remote_action_RemotePokeJs.call(arg)

def remote_event_PeerPing(arg):
    console.info('peer ping received: %s' % arg)

def remote_event_JsPeerPing(arg):
    console.info('js peer ping received: %s' % arg)

def main():
    console.info('jython peer started')
EOF

cat > "$JY_HOME/nodes/Jython Peer/nodeConfig.json" <<'EOF'
{
    "remoteBindingValues": {
        "actions": {
            "RemotePoke": {"node": "Graal Peer", "action": "Poke"},
            "RemotePokeJs": {"node": "Graal JS Peer", "action": "Poke"}
        },
        "events": {
            "PeerPing": {"node": "Graal Peer", "event": "Ping"},
            "JsPeerPing": {"node": "Graal JS Peer", "event": "Ping"}
        }
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

# --- GraalVM host node: JavaScript recipe (GraalJS, goal 2) — bound to the
# stock Jython peer over the same wire protocols
mkdir -p "$GR_HOME/nodes/Graal JS Peer"
write_js_peer_script "$GR_HOME/nodes/Graal JS Peer/script.js" "graal js peer started"

cat > "$GR_HOME/nodes/Graal JS Peer/nodeConfig.json" <<'EOF'
{
    "remoteBindingValues": {
        "actions": {"RemotePoke": {"node": "Jython Peer", "action": "Poke"}},
        "events": {"PeerPing": {"node": "Jython Peer", "event": "Ping"}}
    },
    "paramValues": {}
}
EOF

# -------------------------------------------------------------------- hosts
# (start_host / wait_http come from smoke-lib.sh; fd 8 / fd 9 hold the FIFOs open)

log "starting stock Jython host on :$JY_PORT"
start_host "$JY_HOME" "$STOCK_NODEL_JAR" "$JY_PORT" 8
JY_PID=$STARTED_PID

log "starting GraalVM host on :$GR_PORT"
start_host "$GR_HOME" "$GRAAL_NODEL_JAR" "$GR_PORT" 9
GR_PID=$STARTED_PID

wait_http "$JY_PORT" "Jython host"
wait_http "$GR_PORT" "GraalVM host"

# --------------------------------------------------------------- assertions
# (invoke / console_contains / check_roundtrip come from smoke-lib.sh)

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

# ------------------------- GraalJS node vs the stock Jython host (goal 2)
log "checking event propagation GraalJS -> Jython"
check_roundtrip "event GraalJS->Jython" \
    "$GR_PORT" GraalJSPeer SendPing "ev-js2jy-$STAMP" \
    "$JY_PORT" JythonPeer "js peer ping received: ev-js2jy-$STAMP" || RESULT=1

log "checking event propagation Jython -> GraalJS"
check_roundtrip "event Jython->GraalJS" \
    "$JY_PORT" JythonPeer SendPing "ev-jy2js-$STAMP" \
    "$GR_PORT" GraalJSPeer "peer ping received: ev-jy2js-$STAMP" || RESULT=1

log "checking remote action GraalJS -> Jython"
check_roundtrip "action GraalJS->Jython" \
    "$GR_PORT" GraalJSPeer PokePeer "ac-js2jy-$STAMP" \
    "$JY_PORT" JythonPeer "poked: ac-js2jy-$STAMP" || RESULT=1

log "checking remote action Jython -> GraalJS"
check_roundtrip "action Jython->GraalJS" \
    "$JY_PORT" JythonPeer PokeJsPeer "ac-jy2js-$STAMP" \
    "$GR_PORT" GraalJSPeer "poked: ac-jy2js-$STAMP" || RESULT=1

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
