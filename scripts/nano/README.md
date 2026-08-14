# Nano ARM64 build and benchmark

These tools are opt-in. They do not participate in the standard build or distribution tasks.

## Build

From the repository root:

```sh
./scripts/nano/build-image.sh
NATIVE_IMAGE_OPTIONS=-J-Xmx13g ./scripts/nano/build-artifact.sh
```

The first command creates the `nodel-nano-builder` Linux ARM64 image. The second uses the named Docker volume `nodel-nano-gradle-cache` for `GRADLE_USER_HOME`; it never mounts the workstation's Gradle cache. `NATIVE_IMAGE_OPTIONS` is passed to `native-image` and defaults to `-J-Xmx13g` when unset.

The output is `dist/nano/nodelhost-nano`, accompanied by `SHA256SUMS` and `PROVENANCE.txt`. The nano Gradle profile must already exist; this tooling deliberately does not define it.

The dependency-free tooling self-check is:

```sh
python3 scripts/nano/bench/test_bench.py
```

## Benchmark protocol

Run only isolated benchmark host instances. Each trial uses one host process, one result directory, and the same unused loopback HTTP port. Never overlap trials: the target device has only 425 MiB RAM, so concurrent runs invalidate the result.

For each v2 and v3 candidate, run these trials in order:

1. Host only: no node directories and no workload driver.
2. Host plus `NanoFix1`.
3. Host plus `NanoFix1` through `NanoFix5`.

Before a node trial, instantiate the `nanofix` recipe as directories named exactly `NanoFix1` through `NanoFixN`, with the recipe script in every node directory. Set each node's `label` parameter to its directory name; the driver reads `/params` and fails before timing if this value does not round-trip. Start `recipes/nano-fixture/tools/echo_peer.py` before the host; it must serve TCP `127.0.0.1:7401` and UDP `127.0.0.1:7402` for the entire warmup and soak.

Use this sequence for every trial, changing only the candidate binary, trial directory, and node count:

```sh
TRIAL=$PWD/results/v3-host-1
TOOLS=$PWD/scripts/nano/bench
PORT=18085
NODES=1
mkdir -p "$TRIAL/nodes"

# Candidate command. For an isolated v2 jar, use: set -- java -jar /absolute/path/to/nodelhost.jar
set -- "$PWD/dist/nano/nodelhost-nano"

# Populate NanoFix1..NanoFixN here. Omit this and the echo peer for host-only.
python3 recipes/nano-fixture/tools/echo_peer.py >"$TRIAL/echo.log" 2>&1 &
echo $! >"$TRIAL/echo.pid"

"$TOOLS/startup_time.sh" "$PORT" -- sh -c '
    trial=$1 port=$2
    shift 2
    echo $$ >"$trial/host.pid"
    cd "$trial"
    exec "$@" -p "$port" >host.stdout 2>host.stderr
' bench-launch "$TRIAL" "$PORT" "$@" >"$TRIAL/startup_ms.txt"

PID=$(cat "$TRIAL/host.pid")
"$TOOLS/memsample.sh" "$PID" "$TRIAL/memory.csv" 1 &
echo $! >"$TRIAL/memsample.pid"

# Discard this warmup from workload results. Memory sampling continues.
sleep 600

# Node trials: exactly 60 minutes, one ping per second per node.
python3 "$TOOLS/driver.py" \
    --base-url "http://127.0.0.1:$PORT" \
    --duration 3600 \
    --nodes "$NODES" \
    --rate 1 \
    --output-dir "$TRIAL"

# Host-only trial: replace the driver command with this 60-minute soak.
# sleep 3600

kill "$PID"
wait "$(cat "$TRIAL/memsample.pid")" 2>/dev/null || true
if [ -f "$TRIAL/echo.pid" ]; then
    kill "$(cat "$TRIAL/echo.pid")" 2>/dev/null || true
    wait "$(cat "$TRIAL/echo.pid")" 2>/dev/null || true
fi
```

The startup measurement is process launch to HTTP 200 from `/`; it does not claim that every node is ready. The sampler runs across the 10-minute warmup and 60-minute soak. `report.py` takes the steady RSS and PSS medians from the final 10 minutes, so the warmup is excluded. Latency percentiles use the nearest-rank method. Keep the driver rate, polling interval, duration, node naming, echo peer, sample interval, and HTTP method identical for v2 and v3.

The driver uses the classic REST endpoints shared by both versions:

- `POST /REST/nodes/{node}/actions/ping/call` with `{"arg":"token"}`
- `GET /REST/nodes/{node}/activity?from={sequence}`

The action form is exercised by `scripts/compat-smoke.sh`; the activity cursor is the classic web UI's polling path. One activity poll yields `pong`, `tick500`, `tick5s`, `tcpEcho`, and `udpEcho` with Nodel sequence metadata and fixture counter payloads.

Generate a report section after each trial:

```sh
python3 "$TOOLS/report.py" \
    --label "v3 host + 1 node" \
    --memory "$TRIAL/memory.csv" \
    --latency "$TRIAL/latency.csv" \
    --gaps "$TRIAL/gaps.json" \
    --startup "$TRIAL/startup_ms.txt" \
    --output BENCH_REPORT.md
```

Use `--append` for later sections. For host-only, omit `--latency` and `--gaps`. Preserve the raw CSV and JSON beside the report.

For each candidate, calculate node memory cost from the reported steady-state RSS:

- First-node cost: `RSS(host+1) - RSS(host-only)`.
- Nodes 2-5 average marginal cost: `(RSS(host+5) - RSS(host+1)) / 4`.
- All-node average cost: `(RSS(host+5) - RSS(host-only)) / 5`.

Counter decreases are recorded as node resets, not gaps. Counter increases larger than one are recorded as missing sequences, but these are observation-level gaps and therefore an upper bound on emission loss. Compare Final seq with Expected for the authoritative emission check. Expected is per node; for multi-node trials, Final seq is the per-node range. `tcpEcho` and `udpEcho` advance only while their transport is connected and ready, so a small startup deficit is normal.

On kernels without `/proc/PID/smaps_rollup`, the sampler sums `Pss` and `Anonymous` from `/proc/PID/smaps` and derives file RSS as total `Rss` minus `Anonymous`. On pre-4.5 kernels that approximation includes shmem in the file share. `Pss_Anon_kB` remains empty because it cannot be cleanly derived. Empty fields are reported as `N/A`; RSS is never substituted for PSS.
