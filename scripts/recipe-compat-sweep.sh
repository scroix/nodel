#!/usr/bin/env bash
#
# Load every non-retired official recipe in a fresh, isolated v3 GraalPy host.
# The audited recipe revision and inventory size are intentionally pinned.
#
# Usage: scripts/recipe-compat-sweep.sh [output.md]
#        scripts/recipe-compat-sweep.sh --self-test
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/smoke-lib.sh"

RECIPE_REPOSITORY=https://github.com/museumsvictoria/nodel-recipes.git
RECIPE_COMMIT=42cec03bdd0e43fc9252fe3dd6f6900f355f21a3
NODEL_RUNTIME_COMMIT=0517041cf6091d9a2449ec883ea60896385d8e32
EXPECTED_RECIPE_COUNT=91
SWEEP_TIMEOUT_SECONDS="${RECIPE_SWEEP_TIMEOUT_SECONDS:-30}"
STARTUP_TIMEOUT_SECONDS="${RECIPE_SWEEP_STARTUP_TIMEOUT_SECONDS:-30}"
SWEEP_PORT_OVERRIDE="${RECIPE_SWEEP_PORT:-}"
SWEEP_PORT=""
WORK_ROOT="$ROOT/build/recipe-compat-sweep"
OUTPUT="${1:-$WORK_ROOT/results.md}"

CURRENT_PID=""
LOCK_DIR=""

terminate_current() {
    (printf '\n' >&9) 2>/dev/null || true
    exec 9>&- 2>/dev/null || true

    if [ -n "$CURRENT_PID" ] && kill -0 "$CURRENT_PID" 2>/dev/null; then
        local attempt
        for attempt in $(seq 1 30); do
            kill -0 "$CURRENT_PID" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$CURRENT_PID" 2>/dev/null; then
            kill "$CURRENT_PID" 2>/dev/null || true
        fi
        for attempt in $(seq 1 30); do
            kill -0 "$CURRENT_PID" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$CURRENT_PID" 2>/dev/null; then
            kill -KILL "$CURRENT_PID" 2>/dev/null || true
        fi
        wait "$CURRENT_PID" 2>/dev/null || true
    fi
    CURRENT_PID=""
}

wait_for_port_close() {
    local attempt
    [ -n "$SWEEP_PORT" ] || return 0
    declare -F port_is_open >/dev/null || return 0
    for attempt in $(seq 1 10); do
        port_is_open "$SWEEP_PORT" || return 0
        sleep 0.1
    done
    return 1
}

cleanup_current() {
    local cleanup_safe=1
    terminate_current
    if ! wait_for_port_close; then
        printf 'FAIL: host port %s remained open; preserving sweep lock at %s\n' \
            "$SWEEP_PORT" "$LOCK_DIR" >&2
        cleanup_safe=0
    fi
    if [ -n "$LOCK_DIR" ]; then
        if [ "$cleanup_safe" -eq 1 ]; then
            rm -f "$LOCK_DIR/state"
            rmdir "$LOCK_DIR" 2>/dev/null || true
            LOCK_DIR=""
        fi
    fi
    [ "$cleanup_safe" -eq 1 ]
}

stop_on_signal() {
    cleanup_current || true
    trap - EXIT
    exit 130
}

trap cleanup_current EXIT
trap stop_on_signal INT TERM

classify_failure() { # <console.json> <script.py> <timed-out: 0|1>
    local console_file="$1" script_file="$2" timed_out="$3"
    if [ "$timed_out" -eq 1 ]; then
        echo "missing dependency/runtime assumptions"
    elif grep -Eq \
            'SyntaxError|TabError|IndentationError|(^|[^A-Za-z])(iteritems|iterkeys|itervalues|basestring|unicode|xrange|raw_input)([^A-Za-z]|$)|name .(long|reduce). is not defined' \
            "$console_file"; then
        echo "Python 2 syntax"
    elif grep -Eq \
            'Foreign(Abstract)?Class|HostObject|Unsupported(Message|Type)|JavaImport|java[.]type|No module named .(org|java|javax|com)|cannot import name' \
            "$console_file" \
            && grep -Eq \
            '(^|[[:space:]])(from|import)[[:space:]]+(org|java|javax|com)([.]|[[:space:]])|__tojava__|jarray' \
            "$script_file"; then
        echo "Jython-specific Java interop"
    else
        echo "missing dependency/runtime assumptions"
    fi
}

first_error() { # <console.json>
    local console_file="$1"
    sed 's/},{/}\
{/g' "$console_file" \
        | grep -E '"console":"(err|error)"' \
        | sed -n '1p' \
        | sed -E 's/^.*"comment":"//; s/","console":"(err|error)".*$//' \
        | sed -e 's/\\n/ /g' -e 's/\\r/ /g' -e 's/\\t/ /g' \
              -e 's/|/\&#124;/g' -e "s/\`/'/g" \
        | LC_ALL=C cut -c1-180
}

assert_classification() { # <expected> <console text> <script text> <timed-out>
    local expected="$1" console_text="$2" script_text="$3" timed_out="$4"
    local fixture_dir actual
    fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/recipe-sweep-test.XXXXXX")"
    printf '%s\n' "$console_text" > "$fixture_dir/console.json"
    printf '%s\n' "$script_text" > "$fixture_dir/script.py"
    actual="$(classify_failure "$fixture_dir/console.json" "$fixture_dir/script.py" "$timed_out")"
    rm -rf "$fixture_dir"
    [ "$actual" = "$expected" ] \
        || fail "classifier expected '$expected', got '$actual'"
}

self_test() {
    local fixture_dir
    assert_classification "Python 2 syntax" \
        'SyntaxError: Missing parentheses in call to print' "print 'legacy'" 0
    assert_classification "Python 2 syntax" \
        'TabError: inconsistent use of tabs and spaces in indentation' 'if True: pass' 0
    assert_classification "Jython-specific Java interop" \
        "ImportError: cannot import name 'File'" 'from java.io import File' 0
    assert_classification "missing dependency/runtime assumptions" \
        "ModuleNotFoundError: No module named 'serial'" 'import serial' 0
    assert_classification "missing dependency/runtime assumptions" '' '' 1
    fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/recipe-sweep-test.XXXXXX")"
    printf '%s\n' '[{"comment":"SyntaxError: legacy print","console":"err"}]' \
        > "$fixture_dir/console.json"
    [ "$(first_error "$fixture_dir/console.json")" = 'SyntaxError: legacy print' ] \
        || fail "could not extract the Nodel err-console evidence"
    rm -rf "$fixture_dir"
    echo "PASS: recipe compatibility classifier"
}

[ "$#" -le 1 ] || fail "usage: scripts/recipe-compat-sweep.sh [output.md]"
case "${1:-}" in
    -h|--help)
        echo "usage: scripts/recipe-compat-sweep.sh [output.md]"
        echo "       scripts/recipe-compat-sweep.sh --self-test"
        exit 0
        ;;
    --self-test)
        self_test
        exit 0
        ;;
    -*) fail "unknown option: $1" ;;
esac

case "$SWEEP_TIMEOUT_SECONDS" in
    ''|*[!0-9]*) fail "RECIPE_SWEEP_TIMEOUT_SECONDS must be a positive integer" ;;
esac
case "$STARTUP_TIMEOUT_SECONDS" in
    ''|*[!0-9]*) fail "RECIPE_SWEEP_STARTUP_TIMEOUT_SECONDS must be a positive integer" ;;
esac
case "$SWEEP_PORT_OVERRIDE" in
    '') ;;
    *[!0-9]*) fail "RECIPE_SWEEP_PORT must be an integer" ;;
esac
[ "$SWEEP_TIMEOUT_SECONDS" -gt 0 ] || fail "RECIPE_SWEEP_TIMEOUT_SECONDS must be positive"
[ "$STARTUP_TIMEOUT_SECONDS" -gt 0 ] || fail "RECIPE_SWEEP_STARTUP_TIMEOUT_SECONDS must be positive"
[ -z "$SWEEP_PORT_OVERRIDE" ] \
    || { [ "$SWEEP_PORT_OVERRIDE" -gt 0 ] && [ "$SWEEP_PORT_OVERRIDE" -le 65535 ]; } \
    || fail "RECIPE_SWEEP_PORT must be between 1 and 65535"

git -C "$ROOT" merge-base --is-ancestor "$NODEL_RUNTIME_COMMIT" HEAD \
    || fail "the audited v3 runtime commit $NODEL_RUNTIME_COMMIT is not an ancestor of HEAD"

mkdir -p "$WORK_ROOT"
lock_candidate="$WORK_ROOT/active.lock"
mkdir "$lock_candidate" 2>/dev/null \
    || fail "another sweep may be using $WORK_ROOT; remove $lock_candidate only if no sweep is running"
LOCK_DIR="$lock_candidate"
printf 'sweep_pid=%s\n' "$$" > "$LOCK_DIR/state"
SOURCE_ROOT="$WORK_ROOT/nodel-recipes"
RUN_ROOT="$WORK_ROOT/run"
INVENTORY="$WORK_ROOT/inventory.txt"
ROWS="$WORK_ROOT/rows.md"

if [ ! -d "$SOURCE_ROOT/.git" ]; then
    log "cloning official recipes"
    git clone --quiet --no-checkout --filter=blob:none "$RECIPE_REPOSITORY" "$SOURCE_ROOT"
fi
git -C "$SOURCE_ROOT" fetch --quiet origin "$RECIPE_COMMIT"
git -c core.hooksPath=/dev/null -C "$SOURCE_ROOT" checkout --quiet --detach "$RECIPE_COMMIT"
[ "$(git -C "$SOURCE_ROOT" rev-parse HEAD)" = "$RECIPE_COMMIT" ] \
    || fail "recipe checkout did not resolve to $RECIPE_COMMIT"
[ -z "$(git -C "$SOURCE_ROOT" status --porcelain --untracked-files=all)" ] \
    || fail "recipe checkout is not clean; remove $SOURCE_ROOT and rerun"

while IFS= read -r -d '' script_path; do
    printf '%s\n' "${script_path#"$SOURCE_ROOT"/}"
done < <(find "$SOURCE_ROOT" -type f -name script.py \
    ! -path "$SOURCE_ROOT/(retired)/*" -print0) \
    | LC_ALL=C sort > "$INVENTORY"
RECIPE_COUNT="$(wc -l < "$INVENTORY" | tr -d ' ')"
[ "$RECIPE_COUNT" -eq "$EXPECTED_RECIPE_COUNT" ] \
    || fail "expected $EXPECTED_RECIPE_COUNT non-retired recipes at $RECIPE_COMMIT, found $RECIPE_COUNT"

require_graalvm25
log "building GraalVM host jar"
SWEEP_JAVA_HOME="$("$JAVA" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/^[[:space:]]*java.home = //p' | sed -n '1p')"
[ -x "$SWEEP_JAVA_HOME/bin/java" ] \
    || fail "could not resolve JAVA_HOME from the validated GraalVM executable: $JAVA"
JAVA="$SWEEP_JAVA_HOME/bin/java"
(cd "$ROOT" && JAVA_HOME="$SWEEP_JAVA_HOME" ./gradlew :nodel-jyhost:clean :nodel-jyhost:shadowJar)
shopt -s nullglob
host_jars=("$ROOT"/nodel-jyhost/build/distributions/standalone/nodelhost-*.jar)
shopt -u nullglob
[ "${#host_jars[@]}" -eq 1 ] \
    || fail "expected one freshly built standalone host jar, found ${#host_jars[@]}"
GRAAL_NODEL_JAR="${host_jars[0]}"
GRAAL_NODEL_JAR="$(cd "$(dirname "$GRAAL_NODEL_JAR")" && pwd)/$(basename "$GRAAL_NODEL_JAR")"
log "graal jar: $GRAAL_NODEL_JAR"

ISOLATION_MODE=""
if [ "$(uname -s)" = Darwin ] && command -v sandbox-exec >/dev/null 2>&1; then
    ISOLATION_MODE=macos-sandbox
elif [ -n "${RECIPE_SWEEP_LAUNCH_WRAPPER:-}" ]; then
    [ -x "$RECIPE_SWEEP_LAUNCH_WRAPPER" ] \
        || fail "RECIPE_SWEEP_LAUNCH_WRAPPER is not executable"
    RECIPE_SWEEP_LAUNCH_WRAPPER="$(cd "$(dirname "$RECIPE_SWEEP_LAUNCH_WRAPPER")" && pwd -P)/$(basename "$RECIPE_SWEEP_LAUNCH_WRAPPER")"
    ISOLATION_MODE=external-wrapper
elif [ "${RECIPE_SWEEP_ALLOW_NETWORK:-0}" = 1 ]; then
    ISOLATION_MODE=unisolated
else
    fail "no load sandbox is available; set RECIPE_SWEEP_LAUNCH_WRAPPER to an equivalent wrapper, or explicitly set RECIPE_SWEEP_ALLOW_NETWORK=1"
fi
log "isolation: $ISOLATION_MODE"

port_is_open() { # <port>
    (exec 8<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

select_sweep_port() {
    local attempt random_value candidate
    if [ -n "$SWEEP_PORT_OVERRIDE" ]; then
        SWEEP_PORT="$SWEEP_PORT_OVERRIDE"
        port_is_open "$SWEEP_PORT" \
            && fail "port $SWEEP_PORT is already in use; choose another RECIPE_SWEEP_PORT"
        return
    fi

    for attempt in $(seq 1 100); do
        random_value="$(od -An -N2 -tu2 /dev/urandom | tr -d '[:space:]')"
        candidate=$((49152 + random_value % 16384))
        if ! port_is_open "$candidate"; then
            SWEEP_PORT="$candidate"
            return
        fi
    done
    fail "could not select an unused high loopback port"
}

launch_host() { # <host-home>
    local host_home="$1" canonical_home canonical_java profile
    case "$ISOLATION_MODE" in
        macos-sandbox)
            canonical_home="$(cd "$host_home" && pwd -P)"
            canonical_java="$(cd "$(dirname "$JAVA")" && pwd -P)/$(basename "$JAVA")"
            canonical_home="$(printf '%s' "$canonical_home" | sed 's/\\/\\\\/g; s/"/\\"/g')"
            canonical_java="$(printf '%s' "$canonical_java" | sed 's/\\/\\\\/g; s/"/\\"/g')"
            profile="(version 1) (allow default) (deny network*) (allow network-inbound (local tcp \"localhost:*\")) (deny process-exec) (allow process-exec (literal \"$canonical_java\")) (deny file-write*) (allow file-write* (subpath \"$canonical_home\") (literal \"/dev/null\") (literal \"/dev/dtracehelper\"))"
            exec sandbox-exec -p "$profile" "$JAVA" -Djava.net.preferIPv4Stack=true -jar "$GRAAL_NODEL_JAR" \
                -p "$SWEEP_PORT" --localInterfaceOnly
            ;;
        external-wrapper)
            exec "$RECIPE_SWEEP_LAUNCH_WRAPPER" "$JAVA" -Djava.net.preferIPv4Stack=true -jar "$GRAAL_NODEL_JAR" \
                -p "$SWEEP_PORT" --localInterfaceOnly
            ;;
        unisolated)
            exec "$JAVA" -Djava.net.preferIPv4Stack=true -jar "$GRAAL_NODEL_JAR" \
                -p "$SWEEP_PORT" --localInterfaceOnly
            ;;
    esac
}

stop_host() {
    terminate_current
    wait_for_port_close \
        || fail "host port $SWEEP_PORT remained open; preserving $LOCK_DIR because RECIPE_SWEEP_LAUNCH_WRAPPER did not contain its host process"
}

printf '| Recipe | Load | Failure class | Evidence |\n' > "$ROWS"
printf '|---|---:|---|---|\n' >> "$ROWS"

PASS_COUNT=0
PY2_COUNT=0
RUNTIME_COUNT=0
INTEROP_COUNT=0
DIRECT_INTEROP_COUNT=0
DIRECT_INTEROP_PASS_COUNT=0
INDEX=0

while IFS= read -r recipe_script; do
    INDEX=$((INDEX + 1))
    recipe_dir="${recipe_script%/script.py}"
    log "[$INDEX/$RECIPE_COUNT] $recipe_dir"

    HAS_DIRECT_INTEROP=0
    if grep -Eq \
            '(^|[[:space:]])(from|import)[[:space:]]+(org|java|javax|com)([.]|[[:space:]])' \
            "$SOURCE_ROOT/$recipe_script"; then
        HAS_DIRECT_INTEROP=1
        DIRECT_INTEROP_COUNT=$((DIRECT_INTEROP_COUNT + 1))
    fi

    rm -rf "$RUN_ROOT"
    HOST_ROOT="$RUN_ROOT/host"
    NODE_ROOT="$HOST_ROOT/nodes/Recipe Under Test"
    mkdir -p "$NODE_ROOT"
    cp -R "$SOURCE_ROOT/$recipe_dir/." "$NODE_ROOT/"
    mkfifo "$HOST_ROOT/.stdin"
    select_sweep_port

    (cd "$HOST_ROOT" && launch_host "$HOST_ROOT" \
        <.stdin >output.log 2>error.log) &
    CURRENT_PID=$!
    printf 'sweep_pid=%s\nhost_pid=%s\nport=%s\n' \
        "$$" "$CURRENT_PID" "$SWEEP_PORT" > "$LOCK_DIR/state"
    exec 9>"$HOST_ROOT/.stdin"

    HTTP_READY=0
    for attempt in $(seq 1 $((STARTUP_TIMEOUT_SECONDS * 4))); do
        kill -0 "$CURRENT_PID" 2>/dev/null || break
        if curl -sf -o /dev/null "http://127.0.0.1:$SWEEP_PORT/" \
                && kill -0 "$CURRENT_PID" 2>/dev/null; then
            HTTP_READY=1
            break
        fi
        sleep 0.25
    done

    if [ "$HTTP_READY" -eq 0 ]; then
        stop_host
        fail "v3 host did not start for $recipe_dir; inspect $HOST_ROOT/output.log and error.log"
    fi

    CONSOLE_FILE="$RUN_ROOT/console.json"
    : > "$CONSOLE_FILE"
    TIMED_OUT=0
    if [ "$HTTP_READY" -eq 1 ]; then
        for attempt in $(seq 1 $((SWEEP_TIMEOUT_SECONDS * 4))); do
            curl -sf "http://127.0.0.1:$SWEEP_PORT/REST/nodes/RecipeUnderTest/console?from=0&max=1000" \
                > "$CONSOLE_FILE" || true
            if grep -F 'Python node initialised.' "$CONSOLE_FILE" >/dev/null; then
                break
            fi
            kill -0 "$CURRENT_PID" 2>/dev/null || break
            sleep 0.25
        done
    fi

    if [ "$HTTP_READY" -eq 0 ] \
            || ! grep -F 'Python node initialised.' "$CONSOLE_FILE" >/dev/null; then
        TIMED_OUT=1
    fi

    if [ "$TIMED_OUT" -eq 0 ] \
            && ! grep -E '"console":"(err|error)"' "$CONSOLE_FILE" >/dev/null; then
        load_result=PASS
        failure_class='—'
        evidence='Reached Python node initialised; no startup error.'
        PASS_COUNT=$((PASS_COUNT + 1))
        if [ "$HAS_DIRECT_INTEROP" -eq 1 ]; then
            DIRECT_INTEROP_PASS_COUNT=$((DIRECT_INTEROP_PASS_COUNT + 1))
        fi
    else
        load_result=FAIL
        failure_class="$(classify_failure "$CONSOLE_FILE" "$SOURCE_ROOT/$recipe_script" "$TIMED_OUT")"
        if [ "$TIMED_OUT" -eq 1 ]; then
            if [ "$HTTP_READY" -eq 0 ]; then
                evidence="Host did not become reachable for this recipe."
            else
                evidence="No terminal load result within ${SWEEP_TIMEOUT_SECONDS}s."
            fi
        else
            evidence="$(first_error "$CONSOLE_FILE")"
            [ -n "$evidence" ] || evidence='Host reported a startup error.'
        fi
        case "$failure_class" in
            'Python 2 syntax') PY2_COUNT=$((PY2_COUNT + 1)) ;;
            'Jython-specific Java interop') INTEROP_COUNT=$((INTEROP_COUNT + 1)) ;;
            *) RUNTIME_COUNT=$((RUNTIME_COUNT + 1)) ;;
        esac
    fi

    printf '| `%s` | %s | %s | %s |\n' \
        "$recipe_dir" "$load_result" "$failure_class" "$evidence" >> "$ROWS"
    printf '%s: %s (%s)\n' "$load_result" "$recipe_dir" "$failure_class"
    stop_host
done < "$INVENTORY"

mkdir -p "$(dirname "$OUTPUT")"
{
    printf '# Nodel v3 official recipe compatibility sweep\n\n'
    printf 'This is the first bounded phase of [scroix/nodel#32](https://github.com/scroix/nodel/issues/32). '
    printf 'It records fresh-node load compatibility only; recipe conversion and physical-device behaviour testing are not part of this sweep.\n\n'
    printf '## Reproducing the sweep\n\n'
    printf 'Run `scripts/recipe-compat-sweep.sh`. The harness checks out the pinned recipe revision, copies each complete recipe into a fresh host home, and waits for the v3 GraalPy node lifecycle to finish. '
    printf 'The `(retired)` tree is excluded; active `Mk*`, `legacy`, and platform-specific variants remain in scope.\n\n'
    printf 'The harness binds its HTTP, REST, and native messaging listeners to loopback and suppresses multicast discovery. On macOS the sandbox also denies all other network access, child process execution, and writes outside the temporary host home. '
    printf 'Other platforms must provide an equivalent foreground executable through `RECIPE_SWEEP_LAUNCH_WRAPPER`; it must `exec` the host or forward stdin and signals, and remain alive until the host exits. `RECIPE_SWEEP_ALLOW_NETWORK=1` is an explicit escape hatch for an already isolated disposable machine.\n\n'
    printf 'A fresh high loopback HTTP port is chosen from `/dev/urandom` for each recipe. Set `RECIPE_SWEEP_PORT` only when a deterministic port is required in an already isolated environment.\n\n'
    printf -- '- Nodel v3 runtime baseline: `%s` (GraalVM Community 25.2.4 / JDK 25.0.4)\n' "$NODEL_RUNTIME_COMMIT"
    printf -- '- Audited recipe revision: [`%s`](https://github.com/museumsvictoria/nodel-recipes/commit/%s)\n' "$RECIPE_COMMIT" "$RECIPE_COMMIT"
    printf -- '- Inventory: %s non-retired `script.py` files\n' "$RECIPE_COUNT"
    printf -- '- Isolation used for this run: `%s`\n' "$ISOLATION_MODE"
    printf -- '- Host startup timeout: %ss\n' "$STARTUP_TIMEOUT_SECONDS"
    printf -- '- Per-recipe lifecycle timeout: %ss\n\n' "$SWEEP_TIMEOUT_SECONDS"
    printf 'A `PASS` means the script parsed, bindings were extracted, lifecycle hooks completed, and the node reached `Python node initialised` without a console error. '
    printf 'It does not prove that a device protocol, credential, operating-system command, or long-running callback works.\n\n'
    printf '## Summary\n\n'
    printf -- '- %s loaded without a startup error.\n' "$PASS_COUNT"
    printf -- '- %s failed on Python 2 syntax or language idioms.\n' "$PY2_COUNT"
    printf -- '- %s failed on a missing dependency or runtime assumption.\n' "$RUNTIME_COUNT"
    printf -- '- %s failed on Jython-specific Java interop.\n\n' "$INTEROP_COUNT"
    printf 'The class is the first observable load failure, not an exhaustive list of latent work. '
    printf '%s recipes contain direct Jython-style `java`, `org`, or `com` imports; %s of those loaded cleanly through v3' \
        "$DIRECT_INTEROP_COUNT" "$DIRECT_INTEROP_PASS_COUNT"
    printf "'s compatibility import hook, and %s recipes reached Java interop as their first failure. " "$INTEROP_COUNT"
    printf 'That is useful load evidence, not a device-behaviour guarantee.\n\n'
    printf '## Per-recipe results\n\n'
    cat "$ROWS"
    printf '\n## Representative pilot conversion set\n\n'
    printf 'A ten-recipe pilot covers the observed failure shapes without turning this phase into a mass conversion:\n\n'
    printf -- '- `AMX beacon receiver`: small mechanical `print` conversion and a managed UDP declaration.\n'
    printf -- '- `Brightsign/Brightscript`: old exception syntax in an HTTP-oriented recipe.\n'
    printf -- '- `Extron IN16XX series presentation switch/Mk1`: Python 2 long literals, with the loading Mk2 sibling as a nearby reference.\n'
    printf -- '- `App Launcher`: long literals followed by platform-specific Java/process integration.\n'
    printf -- '- `Frontend/Mk2`: mechanical syntax plus direct `File`, `Stream`, `SimpleName`, and `Nodel` imports.\n'
    printf -- '- `OSC Client`: old exception syntax plus Java file/stream imports and a bundled-module assumption.\n'
    printf -- '- `Alcorn 8TraXX`: a GraalPy `ForeignNone` versus integer comparison in logging.\n'
    printf -- '- `Sony VISCA Color Video Camera`: another `ForeignNone` path in a richer TCP/UDP/HTTP recipe.\n'
    printf -- '- `Yamaha AV Receiver over YNCA protocol`: the Python 2 `urlparse` module rename followed by Java interop.\n'
    printf -- '- `Extron MVC 121 Plus mixer`: the missing legacy `afterMain()` lifecycle assumption.\n\n'
    printf 'For a later conversion phase, each pilot should first pass this isolated load sweep, then receive simulator-backed protocol checks where practical. '
    printf 'Real credentials, physical devices, fleet operating-system behaviour, the remaining recipes, and the branch-versus-dual-version publishing decision stay out of scope here.\n'
} > "$OUTPUT"

log "wrote $OUTPUT"
printf 'PASS=%s PYTHON2=%s RUNTIME=%s INTEROP=%s TOTAL=%s\n' \
    "$PASS_COUNT" "$PY2_COUNT" "$RUNTIME_COUNT" "$INTEROP_COUNT" "$RECIPE_COUNT"
