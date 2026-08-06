#!/usr/bin/env bash
#
# Packaged-artifact smoke test (goal 3): proves a self-contained Nodel
# distributable (jpackage app-image launcher — or any host launcher / jar)
# boots and FUNCTIONS: web UI served, a Python 3 (GraalPy) node and a
# JavaScript (GraalJS) node initialise, their bindings are REST-visible and
# their local actions execute.
#
# Discovery-based checks (cross-host bindings, multicast) live in
# compat-smoke.sh / polyglot-smoke.sh — run those against the packaged
# launcher too (GRAAL_NODEL_JAR=<app-image binary>) on a machine where
# multicast works. This suite is deterministic and CI-safe.
#
# Usage:
#   packaged-smoke.sh run <launcher-or-jar> [port]
#       prepare a host home, start the launcher, verify, shut down
#   packaged-smoke.sh prepare <home-dir>
#       just write the fixture nodes (for a host started elsewhere, e.g. a
#       java-less Docker container in CI)
#   packaged-smoke.sh verify <port>
#       run the REST assertions against an already-running host
#   packaged-smoke.sh runtime <port> <optimized|fallback> [host-error-log]
#       assert the runtime warning state after the host has initialised
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/smoke-lib.sh"

HOST_PID=""
cleanup() {
    [ -n "$HOST_PID" ] && kill "$HOST_PID" 2>/dev/null || true
    wait 2>/dev/null || true
}

prepare_home() { # <home>
    local home="$1"
    mkdir -p "$home/nodes/JS Demo" "$home/nodes/Py Demo"

    write_js_peer_script "$home/nodes/JS Demo/script.js" "js demo started"

    cat > "$home/nodes/Py Demo/script.py" <<'EOF'
local_event_Ping = LocalEvent({'title': 'Ping', 'schema': {'type': 'string'}})

def local_action_SendPing(arg):
    console.info(f'ping sent: {arg}')
    local_event_Ping.emit(arg)

def local_action_Poke(arg):
    console.info(f'poked: {arg}')

def main():
    import ctypes
    ctypes.CDLL(None)
    console.info('ctypes ready')
    console.info('py demo started')
EOF
    log "fixture nodes written to $home/nodes"
}

verify_host() { # <port>
    local port="$1" RESULT=0
    local STAMP=$$-$(date +%s)

    wait_http "$port" "packaged host"

    log "checking web UI is served"
    if curl -sf "http://127.0.0.1:$port/" | grep -qi 'nodel'; then
        echo "PASS: web UI index served"
    else
        echo "FAIL: web UI index missing or empty" >&2; RESULT=1
    fi

    log "checking both node types initialised"
    wait_console_marker "$port" PyDemo "py demo started" "Python 3 node booted (GraalPy)" || RESULT=1
    wait_console_marker "$port" PyDemo "ctypes ready" "Python ctypes loaded the current process" || RESULT=1
    wait_console_marker "$port" JSDemo "js demo started" "JavaScript node booted (GraalJS)" || RESULT=1

    log "checking bindings are REST-visible"
    if curl -sf "http://127.0.0.1:$port/REST/nodes/PyDemo/actions" | grep -q '"SendPing"'; then
        echo "PASS: Python actions extracted"
    else
        echo "FAIL: Python actions not extracted" >&2; RESULT=1
    fi
    if curl -sf "http://127.0.0.1:$port/REST/nodes/JSDemo/events" | grep -q '"Ping"'; then
        echo "PASS: JS events extracted"
    else
        echo "FAIL: JS events not extracted" >&2; RESULT=1
    fi

    log "checking local actions execute (REST -> script -> console)"
    check_roundtrip "Python local action" \
        "$port" PyDemo Poke "py-$STAMP" "$port" PyDemo "poked: py-$STAMP" || RESULT=1
    check_roundtrip "JS local action" \
        "$port" JSDemo Poke "js-$STAMP" "$port" JSDemo "poked: js-$STAMP" || RESULT=1

    if [ "$RESULT" -eq 0 ]; then
        log "ALL PACKAGED-ARTIFACT CHECKS PASSED"
    else
        log "PACKAGED-ARTIFACT CHECKS FAILED"
    fi
    return "$RESULT"
}

verify_runtime_mode() { # <port> <optimized|fallback> [host-error-log]
    local port="$1" expected="$2" host_log="${3:-}" output
    output="$(curl -sf "http://127.0.0.1:$port/REST/nodes/PyDemo/console?from=0&max=500" || true)
$(curl -sf "http://127.0.0.1:$port/REST/nodes/JSDemo/console?from=0&max=500" || true)"
    if [ -n "$host_log" ] && [ -f "$host_log" ]; then
        output="$output
$(cat "$host_log")"
    fi

    if printf '%s' "$output" | grep -qF 'Use --enable-native-access'; then
        fail "launcher emitted a native-access warning"
    fi

    if printf '%s' "$output" | grep -qF 'fallback runtime'; then
        [ "$expected" = fallback ] || fail "packaged launcher used the interpreter-only fallback runtime"
        printf 'PASS: launcher reports interpreter-only fallback\n'
    else
        [ "$expected" = optimized ] || fail "launcher did not report interpreter-only fallback"
        printf 'PASS: packaged launcher uses the optimizing runtime\n'
    fi
}

case "${1:-}" in
    prepare)
        [ -n "${2:-}" ] || fail "usage: packaged-smoke.sh prepare <home-dir>"
        prepare_home "$2"
        ;;
    verify)
        [ -n "${2:-}" ] || fail "usage: packaged-smoke.sh verify <port>"
        verify_host "$2"
        if [ -n "${EXPECT_RUNTIME_MODE:-}" ]; then
            verify_runtime_mode "$2" "$EXPECT_RUNTIME_MODE"
        fi
        ;;
    runtime)
        [ -n "${2:-}" ] && [ -n "${3:-}" ] \
            || fail "usage: packaged-smoke.sh runtime <port> <optimized|fallback> [host-error-log]"
        verify_runtime_mode "$2" "$3" "${4:-}"
        ;;
    run)
        [ -n "${2:-}" ] || fail "usage: packaged-smoke.sh run <launcher-or-jar> [port]"
        LAUNCHER="$2"
        PORT="${3:-8198}"
        case "$LAUNCHER" in
            *.jar)
                # A jar needs GraalVM; a self-contained launcher brings its own runtime.
                LAUNCHER="$(cd "$(dirname "$LAUNCHER")" && pwd)/$(basename "$LAUNCHER")"
                require_graalvm25
                ;;
        esac
        trap cleanup EXIT
        HOME_DIR="$ROOT/build/packaged-smoke/host"
        rm -rf "$HOME_DIR"
        prepare_home "$HOME_DIR"
        log "starting packaged host on :$PORT"
        start_host "$HOME_DIR" "$LAUNCHER" "$PORT" 9
        HOST_PID=$STARTED_PID
        verify_host "$PORT"
        verify_runtime_mode "$PORT" optimized "$HOME_DIR/error.log"
        ;;
    *)
        fail "usage: packaged-smoke.sh run <launcher-or-jar> [port] | prepare <home-dir> | verify <port> | runtime <port> <optimized|fallback> [host-error-log]"
        ;;
esac
