#!/bin/sh
set -eu

usage() {
    echo "Usage: $0 PORT -- CMD..." >&2
    exit 2
}

[ "$#" -ge 3 ] || usage
port=$1
[ "$2" = "--" ] || usage
shift 2

case "$port" in
    ''|*[!0-9]*) usage ;;
esac

start_ns=$(python3 -c 'import time; print(time.monotonic_ns())')
"$@" &
child=$!

cleanup() {
    kill "$child" 2>/dev/null || true
}
trap cleanup HUP INT TERM

if ! python3 - "$port" "$start_ns" "${STARTUP_TIMEOUT_S:-300}" <<'PY'
import sys
import time
import urllib.error
import urllib.request

port = int(sys.argv[1])
start_ns = int(sys.argv[2])
deadline = time.monotonic() + float(sys.argv[3])
url = "http://127.0.0.1:{}/".format(port)

while time.monotonic() < deadline:
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            if response.status == 200:
                print((time.monotonic_ns() - start_ns) // 1_000_000)
                sys.exit(0)
    except (OSError, urllib.error.URLError):
        pass
    time.sleep(0.05)

print("Timed out waiting for HTTP 200 from {}".format(url), file=sys.stderr)
sys.exit(1)
PY
then
    cleanup
    wait "$child" 2>/dev/null || true
    exit 1
fi

trap - HUP INT TERM
