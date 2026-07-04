# Nodel Smoke-Test Playbook

Run these smokes after any significant change to the Nodel platform (Java framework, jyhost,
web UI). The playbook layers up from the committed test suite to a live standalone host probed
over REST and driven through a real browser. Commands assume **repo root** as working directory
unless noted. Budget ~15 minutes plus ~5 minutes for the full gradle build.

Fixture recipes purpose-built for this playbook live in [recipes/smoketest/](recipes/smoketest/)
(a producer/consumer pair — see its README). Deeper build/test background is in
[BUILDING.md](BUILDING.md).

### Agent Execution Notes

When running these smoke tests with an AI agent (Claude Code, etc.):

- **Follow the playbook sequentially.** Later sections depend on earlier ones (the host from §2,
  the fixture nodes from §4). Do not cherry-pick easy sections.
- **Never skip a section without proving the blocker.** Run the prerequisite command, capture the
  exact error, and report which sections it blocks. Do not assume.
- **Use unique markers.** When invoking actions, use a fresh value (e.g. `smoke-$RANDOM`) so
  console/activity assertions can't match stale entries from an earlier run.
- **The browser section is mandatory**, not decorative. Perform it with real browser tooling
  (Claude Preview MCP, claude-in-chrome, Playwright). Read §7's tooling notes first — there are
  two known automation traps (zero-size viewport, coordinate clicks that don't land).
- **Do not modify platform source** (Java, webui, gradle config) to make a check pass. A check
  that fails against unmodified source is a finding: record it under Known Issues.
- **Clean up.** §9 must leave no host process, no `/tmp/nodel-smoke-host`, and a `git status`
  showing nothing beyond what you intended to change.

---

## Quick Prep

| Step | Command / Action | Notes |
|------|------------------|-------|
| 1 | `cd` to the repo root | All commands assume it. |
| 2 | `java -version` | **Running** the host needs JDK 21+. Building only needs JDK 11+ on the PATH to bootstrap Gradle — the build auto-provisions a Java 21 toolchain (see BUILDING.md). |
| 3 | Ports | The manual smoke host uses **8089**; the gradle test suite owns **18085** (and kills anything on it — don't park your own host there). |
| 4 | `export BASE=http://127.0.0.1:8089` | Used by every curl below. |

### Pre-flight Verification

```bash
# 1. JDK present and 21+ (needed to run the built host; Gradle self-provisions its own toolchain)
java -version
# Expected: 'openjdk version "21...' or later

# 2. Smoke port free (kill leftovers from a previous run)
lsof -ti :8089 | xargs kill 2>/dev/null; rm -rf /tmp/nodel-smoke-host
lsof -ti :8089 || echo "port 8089 free"
# Expected: "port 8089 free"

# 3. Fixture recipes present (incl. the JavaScript recipe for §6)
ls recipes/smoketest/smoketest-producer/script.py recipes/smoketest/smoketest-consumer/script.py \
   recipes/javascript/greeter/script.js
# Expected: all three paths print (no "No such file")
```

---

## 1. Committed Test Suite + Build (one command)

`./gradlew build` runs the full committed suite — framework unit tests plus the Playwright
integration and E2E tests against a dedicated host on port 18085 — **and** produces the
standalone jar. It is the first layer of smoke coverage; don't re-implement what it already
covers.

```bash
./gradlew build --console=plain 2>&1 | tee /tmp/nodel-gradle-build.log | tail -5
grep -c "PASSED" /tmp/nodel-gradle-build.log; grep -c "FAILED" /tmp/nodel-gradle-build.log
```

**Cached-build trap:** if this tree was built recently, gradle reports `BUILD SUCCESSFUL in ~2s
... up-to-date` with **0** `PASSED` lines — the tests did not run and nothing was smoked. In
that case re-run with `./gradlew build --rerun-tasks --console=plain ...` (same tee/grep) to
force the suite to execute.

**Expected observables** (from a run that actually executes the tasks):

- `BUILD SUCCESSFUL` in ~3–5m (a first-ever run also downloads Playwright browsers; `31
  actionable tasks: 30 executed, 1 up-to-date` when forced — `npmSetup` stays up-to-date)
- On the order of 138 `PASSED` lines, `0` FAILED (grep -c exits 1 on zero matches — that's the pass case)
- The only skipped *test* is `DiscoverySmokeTests > testNodeUrlsContainsLocalNode()` (opt-in
  multicast, see §8). A bare grep for `SKIPPED` also matches gradle *task* lines like
  `Task :nodel-webui-js:npmSetup SKIPPED` — ignore those.
- Artifacts appear:

```bash
ls nodel-jyhost/build/distributions/standalone/
# Expected: nodelhost-<branch>-3.0.0-rev<N>.jar  (fat jar bundling GraalPy — ~140 MB, not the ~20 MB of the 2.x line)
ls nodel-framework/build/libs/
# Expected: nodel-framework-3.0.0.jar  AND  nodel-framework-3.0.0-test-fixtures.jar
```

The test-fixtures jar carries `LocalAutoDNS` (deterministic, non-multicast discovery) and is
required on the manual host's classpath in §2.

Variants (see BUILDING.md): `./gradlew build -x test` (skip tests),
`:nodel-jyhost:integrationTest` / `:nodel-jyhost:e2eTest` (run layers separately),
`HEADED=1 SLOWMO=500 ./gradlew :nodel-jyhost:e2eTest --rerun` (watch the browser).

---

## 2. Launch the Standalone Host

Run the built jar from a scratch directory (**never the repo root** — the host writes `nodes/`,
`recipes/`, lock and bootstrap files into its working directory) with LocalAutoDNS on the
classpath so discovery is deterministic.

**Two v3 launch requirements** (both handled by the commands below):

1. **Java 21+.** If the PATH `java` is older, use the toolchain Gradle provisioned during §1:

   ```bash
   JAVA=java   # keep if `java -version` says 21+
   java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])' || JAVA=$(find ~/.gradle/jdks -type f -path "*/bin/java" 2>/dev/null | head -1)
   "$JAVA" -version
   # Expected: openjdk version "21..." or later
   ```

2. **GraalPy `--add-opens` flags.** The standalone jar's manifest carries an `Add-Opens`
   attribute, but that only applies to `java -jar`; launching with `-cp` (needed to add the
   test-fixtures jar) requires them explicitly:

   ```bash
   ADD_OPENS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
   ```

**Option A — Claude Preview MCP** (preferred for agents; the same server then backs §7's browser
checks). Create `.claude/launch.json` (untracked — delete it in §9):

```json
{
  "version": "0.0.1",
  "configurations": [
    {
      "name": "nodel-smokehost",
      "runtimeExecutable": "bash",
      "runtimeArgs": [
        "-c",
        "REPO=\"$PWD\"; JAVA=java; java -version 2>&1 | grep -qE '\"(2[1-9]|[3-9][0-9])' || JAVA=$(find ~/.gradle/jdks -type f -path '*/bin/java' 2>/dev/null | head -1); mkdir -p /tmp/nodel-smoke-host/nodes; cd /tmp/nodel-smoke-host; tail -f /dev/null | \"$JAVA\" -cp \"$(ls \"$REPO\"/nodel-jyhost/build/distributions/standalone/nodelhost-*.jar | head -1):$(ls \"$REPO\"/nodel-framework/build/libs/*test-fixtures*.jar | head -1)\" --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED '-Dorg.nodel.discovery.impl=org.nodel.discovery.LocalAutoDNS;instance' org.nodel.jyhost.Launch -p 8089"
      ],
      "port": 8089
    }
  ]
}
```

then `preview_start` with name `nodel-smokehost`. Expected: "Server started successfully on
port 8089" and a `serverId` for later `preview_*` calls.

**Option B — plain shell** (works anywhere; browser checks then need separate tooling):

```bash
JAR=$(ls "$PWD"/nodel-jyhost/build/distributions/standalone/nodelhost-*.jar | head -1)
FIX=$(ls "$PWD"/nodel-framework/build/libs/*test-fixtures*.jar | head -1)
mkdir -p /tmp/nodel-smoke-host/nodes
(cd /tmp/nodel-smoke-host && tail -f /dev/null | "$JAVA" -cp "$JAR:$FIX" $ADD_OPENS \
  '-Dorg.nodel.discovery.impl=org.nodel.discovery.LocalAutoDNS;instance' \
  org.nodel.jyhost.Launch -p 8089 > host.log 2>&1 &)
```

The `tail -f /dev/null |` keeps stdin open — the host treats EOF on stdin as "Enter pressed"
and shuts down.

### Host Pre-flight

```bash
# Ready within ~5s (observed: 2s)
for i in $(seq 1 30); do curl -s -o /dev/null -w "%{http_code}" $BASE/ | grep -q 200 && break; sleep 1; done
curl -s -o /dev/null -w "HTTP %{http_code}\n" $BASE/
# Expected: HTTP 200

ls /tmp/nodel-smoke-host
# Expected: _API_schema.json  _bootstrap_example.json  _bootstrap_schema.json  _instance.lock  custom  nodes  recipes

# Startup banner (host.log for Option B; preview server logs for Option A):
#   Nodel [GraalPython] v3.0.0-<branch>_r<N> is running.
#   (web interface available at http://<ip>:8089)

# LocalAutoDNS audit line — deliberately logged at WARN so it is visible at default level.
# It appears once the first node registers (a few seconds in), via the framework log API:
sleep 5; curl -s "$BASE/REST/logs?from=0&max=5" | grep -o "LocalAutoDNS enabled[^\"]*"
# Expected: LocalAutoDNS enabled (test/local discovery) - not for production use.
```

**First-run note:** the host auto-creates one convenience node, `Nodel Recipes Sync for
<HOSTNAME> <port> (...)`, which clones the official recipes into
`/tmp/nodel-smoke-host/recipes/nodel-official-recipes` (needs network; harmless if it can't).
Expect it in every node list below.

---

## 3. Host-Level REST Surface

All under `$BASE/REST`. Every check below returns HTTP 200 with `Content-Type:
application/json` unless stated.

```bash
# Host metadata — startup timestamp + node map
curl -s $BASE/REST | head -c 200
# Expected: {"started":"<ISO timestamp>","nodes":{"Nodel Recipes Sync for ...":{...

# Node map (name -> {name, desc, started, nodelVersion})
curl -s $BASE/REST/nodes | python3 -m json.tool | head
# Expected: JSON object keyed by node name; each value has "desc":"GraalVM Python Node" and
# "nodelVersion":"3.0.0-<branch>_r<N>" — the full build identifier, same as the banner.
# (A plain "2.2.1" here means the host predates scroix/nodel#37.)

# Diagnostics — JVM/host state
curl -s $BASE/REST/diagnostics | python3 -c "import sys,json;print(sorted(json.load(sys.stdin).keys()))"
# Expected keys include: agent, availableProcessors, freeMemory, hostPath, hostingRule,
#                        hostname, httpAddresses, maxMemory, nodesRoot, startTime,
#                        systemProperties, totalMemory, uptime

# Framework logs (newest-first; seq/timestamp/level/message rows)
curl -s "$BASE/REST/logs?from=0&max=3" | head -c 300
# Expected: JSON array; the earliest entry is the LocalAutoDNS warning from §2. (A SyncNow
# null-handler ERROR also appearing here means the host predates scroix/nodel#37.)

# Python toolkit reference served to script authors
curl -s $BASE/REST/toolkit | head -c 120
# Expected: {"script":"\"\"\"\nNodel Toolkit for GraalVM Python\n...

# Discovery service state (plain string, not JSON)
curl -s -w " (status %{http_code})\n" $BASE/REST/discovery
# Expected: org.nodel.discovery.LocalAutoDNS@<hash> (status 200)

# Advertised nodes / URLs (served by LocalAutoDNS's in-process registry)
curl -s $BASE/REST/allNodes | head -c 120
# Expected: JSON array, one AdvertisementInfo entry per registered node
curl -s $BASE/REST/nodeURLs | head -c 250
# Expected: [{"address":"http://<ip>:8089/nodes/<ReducedName>/","node":"<full name>"}, ...]

# Error shapes
curl -s -o /dev/null -w "status %{http_code}\n" $BASE/REST/nonexistent-endpoint-12345
# Expected: status 404
curl -s -o /dev/null -w "status %{http_code}\n" $BASE/REST/nodes/NoSuchNode/console
# Expected: status 404
```

---

## 4. Node Lifecycle (fixture recipes)

### 4.1 Create by dropping a recipe into `nodes/`

The host live-scans its `nodes/` directory — a copied recipe folder becomes a running node
without any restart.

```bash
cp -R recipes/smoketest/smoketest-producer "/tmp/nodel-smoke-host/nodes/Smoke Producer"
cp -R recipes/smoketest/smoketest-consumer "/tmp/nodel-smoke-host/nodes/Smoke Consumer"

# Poll until both consoles respond (observed: ~11s; scan interval is several seconds)
for i in $(seq 1 30); do
  P=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/REST/nodes/Smoke%20Producer/console?from=0&max=5")
  C=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/REST/nodes/Smoke%20Consumer/console?from=0&max=5")
  [ "$P" = 200 ] && [ "$C" = 200 ] && break; sleep 1
done; echo "producer=$P consumer=$C after ${i}s"
# Expected: producer=200 consumer=200

curl -s "$BASE/REST/nodes/Smoke%20Producer/console?from=0&max=5" | grep -o "Smoke producer started"
# Expected: Smoke producer started
# (console entries are returned newest-first; each row has seq/timestamp/console/comment)
```

### 4.2 Node-level surface

```bash
curl -s "$BASE/REST/nodes/Smoke%20Producer/actions"
# Expected: {"sendPing":{"group":"Ping","name":"sendPing",...,"schema":{"type":"string"},"title":"Send Ping"}}

curl -s "$BASE/REST/nodes/Smoke%20Producer/events" | head -c 250
# Expected: object with "Ping" (no arg yet) and "Status" with "arg":"Ready" (emitted by main());
# both carry the "group" and "schema" from their LocalEvent metadata (missing group/schema
# means the host predates scroix/nodel#37)

curl -s "$BASE/REST/nodes/Smoke%20Producer/params"
# Expected: {}  (the Label parameter exists but is unset)
```

### 4.3 Invoke an action, observe console / event / activity

```bash
MARK="smoke-$RANDOM"   # fresh marker so greps can't match stale entries
curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/nodes/Smoke%20Producer/actions/sendPing/call" \
  -H "Content-Type: application/json" -d "{\"arg\":\"$MARK\"}"
# Expected: true (status 200)

sleep 1
curl -s "$BASE/REST/nodes/Smoke%20Producer/console?from=0&max=3" | grep -o "Ping sent: $MARK"
# Expected: Ping sent: <your marker>

curl -s "$BASE/REST/nodes/Smoke%20Producer/events/Ping" | head -c 120
# Expected: {"arg":"<your marker>","schema":{"type":"string"},"seq":...  (last emitted value retained)

curl -s "$BASE/REST/nodes/Smoke%20Producer/activity?from=0" | head -c 300
# Expected: array with {"source":"local","type":"action","alias":"sendPing","arg":"<marker>"}
#           and {"source":"local","type":"event","alias":"Ping","arg":"<marker>"}
```

### 4.4 Restart

```bash
curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/nodes/Smoke%20Producer/restart" \
  -H "Content-Type: application/json" -d '{}'
# Expected: true (status 200)
sleep 5
curl -s "$BASE/REST/nodes/Smoke%20Producer/console?from=0&max=5" | head -c 300
# Expected: newest entries show the reload cycle (oldest→newest): "Python node destroyed." →
#           "Initialising Python node..." → "Nodel toolkit loaded - enhanced console bridge
#           established" → "Executed toolkit bootstrap script" → "Smoke producer started" →
#           "Python node initialised."
# Console history (and seq numbering) persists across the restart.
```

### 4.5 Create from a recipe via the API, rename, remove

The host serves recipes from its own `recipes/` working subdirectory.

```bash
cp -R recipes/smoketest /tmp/nodel-smoke-host/recipes/smoketest

curl -s "$BASE/REST/recipes/list" | python3 -c "import sys,json;print([r['path'] for r in json.load(sys.stdin) if 'smoketest' in r['path']])"
# Expected: ['smoketest/smoketest-consumer', 'smoketest/smoketest-producer']
# (plus nodel-official-recipes/* entries when the sync node had network)

curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/newNode?base=smoketest/smoketest-producer" \
  -H "Content-Type: application/json" -d '{"value":"Smoke Clone"}'
# Expected: true (status 200)
for i in $(seq 1 30); do curl -s "$BASE/REST/nodes/Smoke%20Clone/console?from=0&max=5" | grep -q "started" && break; sleep 1; done
echo "clone up after ${i}s"
# Expected: clone up within ~10s, console shows "Smoke producer started"

# Rename — the node reloads under the new name; wait for re-registration before touching it
curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/nodes/Smoke%20Clone/rename" \
  -H "Content-Type: application/json" -d '{"value":"Smoke Clone 2"}'
# Expected: true (status 200)
for i in $(seq 1 15); do curl -s $BASE/REST/nodes | grep -q "Smoke Clone 2" && break; sleep 1; done
echo "renamed node registered after ${i}s"
# Expected: registered within a few seconds. Calling endpoints on the new name immediately
# after rename can 404 ({"error":"EndpointNotFoundException",...}) — poll first (see Common Issues).

curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/nodes/Smoke%20Clone%202/remove?confirm=true" \
  -H "Content-Type: application/json" -d '{}'
# Expected: true (status 200)
sleep 2; curl -s $BASE/REST/nodes | grep -c "Smoke Clone" || echo "clone gone"
# Expected: 0 then "clone gone" (grep -c prints its count before the || fires);
#           the node directory is also removed from nodes/
```

---

## 5. Action → Event Binding Between Nodes (LocalAutoDNS)

The consumer's `remote_event_IncomingPing` is wired to the producer's `Ping` event over the
node-to-node channel; LocalAutoDNS resolves the producer's address in-process, so this works
with multicast disabled.

```bash
# Configure the binding (this restarts the consumer's script)
curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/nodes/Smoke%20Consumer/remote/save" \
  -H "Content-Type: application/json" \
  -d '{"actions":{},"events":{"IncomingPing":{"node":"Smoke Producer","event":"Ping"}}}'
# Expected: true (status 200)

curl -s "$BASE/REST/nodes/Smoke%20Consumer/remote"
# Expected: {"actions":{},"events":{"IncomingPing":{"node":"Smoke Producer","event":"Ping"}}}

sleep 3  # let the consumer reload and the binding wire up

# Fire a ping with a fresh marker
MARK="bound-$RANDOM"
curl -s -X POST "$BASE/REST/nodes/Smoke%20Producer/actions/sendPing/call" \
  -H "Content-Type: application/json" -d "{\"arg\":\"$MARK\"}"
# Expected: true

for i in $(seq 1 10); do curl -s "$BASE/REST/nodes/Smoke%20Consumer/console?from=0&max=10" | grep -q "$MARK" && break; sleep 1; done
curl -s "$BASE/REST/nodes/Smoke%20Consumer/console?from=0&max=3" | grep -o "Received ping: $MARK"
# Expected (within ~2s of the call): Received ping: <your marker>

curl -s "$BASE/REST/nodes/Smoke%20Consumer/activity?from=0" | head -c 400
# Expected entries (newest-first):
#   {"source":"local","type":"event","alias":"Received","arg":"<marker>"}      <- re-emitted locally
#   {"source":"remote","type":"event","alias":"IncomingPing","arg":"<marker>"} <- received over binding
#   {"source":"remote","type":"eventBinding","alias":"IncomingPing","arg":"Wired"} <- binding handshake
```

---

## 6. GraalJS Node (script.js)

v3 hosts are polyglot: a node folder containing `script.js` boots a GraalJS context instead of
GraalPy — same binding model, REST surface and wire protocols. This section proves language
dispatch, JS binding discovery and a cross-language binding, using the demo recipe at
`recipes/javascript/greeter/` (authoring guide: `recipes/javascript/README.md`).

```bash
cp -R recipes/javascript/greeter "/tmp/nodel-smoke-host/nodes/JS Greeter"

# Poll until the node spins up (live folder scan, same as §4)
for i in $(seq 1 30); do curl -sf -o /dev/null "$BASE/REST/nodes/JS%20Greeter/console?from=0&max=1" && break; sleep 1; done

# Boot sequence — JavaScript (not Python) markers, and the JS toolkit banner
curl -s "$BASE/REST/nodes/JS%20Greeter/console?from=0&max=8"
# Expected rows (oldest→newest): "Initialising JavaScript node..." → "Nodel toolkit loaded
# (JavaScript) - console bridge established" → "Executed toolkit bootstrap script" →
# "Greeter node started (greeting: \"Hello\")" → "JavaScript node initialised."

# Language dispatch visible in the node map
curl -s "$BASE/REST/nodes" | grep -o '"JS Greeter":{[^}]*}'
# Expected: contains "desc":"GraalVM JavaScript Node" (Python nodes say "GraalVM Python Node")

# JS bindings discovered from the global object — metadata (title/group/schema) intact
curl -s "$BASE/REST/nodes/JS%20Greeter/actions"
# Expected: {"Reset":{"group":"Greeter","name":"Reset","order":2,...,"title":"Reset"},"Greet":{"name":"Greet",...}}
# (Greet is a convention-named function — function local_action_Greet(arg);
#  Reset was created programmatically with metadata — createLocalAction('Reset', fn, {...}))
curl -s "$BASE/REST/nodes/JS%20Greeter/events"
# Expected: {"Greeted":{"schema":{"type":"object","properties":{"name":{...},"message":{...}}},
#            ...,"group":"Greeter",...}} — the JS metadata object survived extraction
curl -s "$BASE/REST/nodes/JS%20Greeter/params/schema"
# Expected: {"type":"object","title":"Parameters","properties":{"Greeting":{"type":"string","hint":"Hello",...}}}

# Parameter save / reload round trip (same semantics as a Python node)
curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/nodes/JS%20Greeter/params/save" \
  -H "Content-Type: application/json" -d '{"Greeting": "Gday"}'
# Expected: true (status 200); the node restarts with the saved value
sleep 6
curl -s -X POST "$BASE/REST/nodes/JS%20Greeter/actions/Greet/call" \
  -H "Content-Type: application/json" -d '{"arg":"Nodel"}'
# Expected: true
curl -s "$BASE/REST/nodes/JS%20Greeter/console?from=0&max=3" | grep -o 'Gday, Nodel![^"]*'
# Expected: Gday, Nodel! (greeting #1)
curl -s "$BASE/REST/nodes/JS%20Greeter/activity?from=0" | head -c 300
# Expected: the local "Greeted" event with its STRUCTURED arg intact:
#   {"source":"local","type":"event","alias":"Greeted","arg":{"name":"Nodel","message":"Gday, Nodel!"}}

# Cross-language binding: the Python consumer's remote event wired onto the JS node's event
curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/nodes/Smoke%20Consumer/remote/save" \
  -H "Content-Type: application/json" \
  -d '{"actions":{},"events":{"IncomingPing":{"node":"JS Greeter","event":"Greeted"}}}'
# Expected: true (status 200)
sleep 4
curl -s -X POST "$BASE/REST/nodes/JS%20Greeter/actions/Greet/call" \
  -H "Content-Type: application/json" -d '{"arg":"cross-language"}'
# Expected: true
for i in $(seq 1 10); do curl -s "$BASE/REST/nodes/Smoke%20Consumer/console?from=0&max=10" | grep -q "cross-language" && break; sleep 1; done
curl -s "$BASE/REST/nodes/Smoke%20Consumer/console?from=0&max=3" | grep -o 'Received ping: .*"'
# Expected: Received ping: {name: \\"cross-language\\", message: \\"Gday, cross-language!\\"}
# (quotes JSON-escaped by the console endpoint; an object emitted by GraalJS, received by a
#  GraalPy handler — structure preserved end-to-end)

# Restore the §5 binding so §7's UI cross-check behaves as documented
curl -s -w " (status %{http_code})\n" -X POST "$BASE/REST/nodes/Smoke%20Consumer/remote/save" \
  -H "Content-Type: application/json" \
  -d '{"actions":{},"events":{"IncomingPing":{"node":"Smoke Producer","event":"Ping"}}}'
# Expected: true (status 200)
```

Deeper cross-language coverage — both directions, remote actions, and GraalJS against a stock
2.x Jython host over real multicast — is scripted in `scripts/polyglot-smoke.sh` and
`scripts/compat-smoke.sh`.

---

## 7. Web UI (browser-driven)

Perform with real browser tooling against `http://127.0.0.1:8089`. With Claude Preview MCP the
§2 Option A server is already the target; otherwise use claude-in-chrome / Playwright against
the same URL.

> **Automation traps (both hit during authoring):**
> 1. **Zero-size viewport.** A fresh Claude Preview page can report `innerWidth/innerHeight = 0`
>    — every coordinate click silently misses while reporting success. `preview_resize` to an
>    explicit size (e.g. 1280×900) before any clicking, and verify with
>    `preview_eval` → `({w: innerWidth, h: innerHeight})`.
> 2. **Coordinate clicks may still not land** on this UI (Bootstrap accordions, jsviews-rendered
>    buttons). Prefer JS-dispatched clicks via eval — `document.querySelector(...).click()` —
>    which exercise the same delegated jQuery handlers. Form inputs bind on the `input` event,
>    so tool "fill" helpers work (verify the jsviews model with
>    `$.view(document.querySelector('.nodel-schema-action[data-name="sendPing"] form')).data`).

| # | Check | Action | Expected observable |
|---|-------|--------|---------------------|
| 7.1 | Node list renders | Load `http://127.0.0.1:8089/` | URL settles at `/locals.xml#Locals`. Navbar with the nodel logo and a host icon whose tooltip (`title` attribute) is "Browse this host"; filter box; `total: 3`; links for `Smoke Producer`, `Smoke Consumer`, and the recipes-sync node. |
| 7.2 | Node page renders | Navigate to `/nodes/SmokeProducer/` | URL settles at `.../nodel.xml#Activity`; `document.title` = `Smoke Producer`; navbar brand shows the node name; Console panel shows the same entries as the REST console (e.g. `Smoke producer started`). |
| 7.3 | Action form renders lazily | Expand the "Ping" group panel (`$(document.getElementById('0_actsig_group')).collapse('show')` — the group's form content only renders on expand) | Panel gains class `collapse in`; an `input.form-control` and a `Send Ping` submit button appear inside `.nodel-schema-action[data-name="sendPing"]`. |
| 7.4 | Invoke action from UI | Fill the input with a fresh marker (e.g. `ui-$RANDOM`), then JS-click the `Send Ping` button | Within ~2s the on-page Console shows `Ping sent: <marker>`. Cross-check over REST: producer console has `Ping sent: <marker>` **and** (binding from §5 still live) consumer console has `Received ping: <marker>`. |
| 7.5 | Add node via UI | On `/`, click `.nodel-add .addgrp .dropdown-toggle`, fill `.nodel-add input.nodenamval` with `UI Smoke Node`, click `.nodel-add .nodeaddsubmit` | Browser navigates to the new node's page; its console shows the v3 default stub starting (`Hello from Python` between the `Initialising Python node...` / `Python node initialised.` markers — v3 no longer provisions the 2.x example recipe, see Known Issues). REST: `UI Smoke Node` appears in `$BASE/REST/nodes` within a few seconds. |
| 7.6 | Cleanup UI node | `curl -s -X POST "$BASE/REST/nodes/UI%20Smoke%20Node/remove?confirm=true" -H "Content-Type: application/json" -d '{}'` | `true`; node disappears from `$BASE/REST/nodes`. |

---

## 8. Optional: Real Multicast Discovery (environment permitting)

Everything above uses LocalAutoDNS deliberately. To exercise production multicast discovery:

```bash
NODEL_TEST_DISCOVERY=1 ./gradlew :nodel-jyhost:integrationTest --tests org.nodel.DiscoverySmokeTests --rerun
# (--rerun because the env var is not a gradle task input)
```

- **Expected on a multicast-capable network:** `DiscoverySmokeTests > testNodeUrlsContainsLocalNode() PASSED`,
  and the gradle output says `Using multicast AutoDNS for tests`.
- **Observed on the authoring machine (macOS, 2026-07):** `FAILED —
  org.opentest4j.AssertionFailedError at DiscoverySmokeTests.java:36` after the 30s poll: the
  host starts cleanly but `/REST/nodeURLs` never lists the test node. The host logs show no
  multicast errors — the probes simply never arrive. Typical causes: macOS Local Network
  permission not granted to the gradle-spawned `java`, or a network that filters multicast.
  Treat a failure here as environmental **after** checking
  `nodel-jyhost/nodelhost-temp/error.log` for real errors; it does not block the rest of the
  playbook.

---

## 9. Cleanup

```bash
# Stop the host: preview_stop <serverId> (Option A) or:
lsof -ti :8089 | xargs kill 2>/dev/null

rm -rf /tmp/nodel-smoke-host
rm -f .claude/launch.json && rmdir .claude 2>/dev/null   # if §2 Option A created it

# The root build/ directory is gitignored on this line (scroix/nodel#35), so gradle strays
# under it (problems-report etc.) no longer dirty the tree. build/compat-smoke/ and
# build/polyglot-smoke/ are just the smoke scripts' work dirs — safe to delete; removing
# build/compat-smoke/ only costs a stock-jar re-download on the next wire-compat run.

git status --short
# Expected: nothing beyond changes you intended (a clean playbook run leaves the tree untouched)
```

---

## Known Issues

Genuine product bugs found while executing this playbook get recorded here (do not patch around
them).

- *(2.x baseline: no product bugs found — authored 2026-07-04 against v2.2.1 rev550; the v3
  replay below was run 2026-07-04 against v3.0.0 rev624, 138/138 committed tests passing)*
- **OPEN — boot-time save/maintenance race wedges a node (v3):** a config save (e.g.
  `/remote/save`) issued within ~1s of a node first answering REST — while the host's initial
  maintenance pass is still constructing sibling nodes — cancels a GraalPy context mid-init,
  leaks the node's event-name registration, and the host then retries a duplicate node for the
  same folder every ~10s, failing forever with `Already bound - <node>.<event>` (subsequent
  saves on the live node return 500). Reproduced 100% on clean v3 (rev628); found while
  authoring §6 (scroix/nodel#34), though it is language-independent — the trigger is save
  timing, not GraalJS. The playbook avoids the window naturally (§5's save happens minutes
  after boot). Do not save node config in the first seconds after host start.
- **FIXED in scroix/nodel#37 — stale version constant (v3):** a 3.0.0 host reported
  `"nodelVersion":"2.2.1"` on `/REST/nodes`; `Nodel.getVersion()` now resolves from the
  build manifest (same source as the banner), so the field carries the full
  `3.0.0-<branch>_r<N>` identifier.
- **FIXED in scroix/nodel#37 — function-style local actions logged a null-handler error
  (v3):** every invocation of a `def local_action_X` binding ran the body then logged
  `Error executing action ...: "this.val$handler" is null`; the built-in recipes-sync node
  hit it on each `SyncNow` timer fire. Decorator-style actions were unaffected.
- **FIXED in scroix/nodel#37 — dict binding metadata was lost on extraction (v3):**
  a GraalPy dict exposes hash entries, not members, so `LocalEvent`/`Parameter` dicts and
  action docstrings fell through to a name-only fallback — `/events` had no
  `group`/`schema`, and the web UI's grouping and forms degraded with them.
- **Example recipe no longer provisioned (v3):** a blank node gets a one-line
  `Hello from Python` stub written by `PyNode.init()`; `ExampleScript.java` /
  `example_script.py` (the 2.x "Recipe has started!" example) are now dead code. The
  toolkit globals the example depends on (`date_now()`, `next_seq()`) were restored in
  scroix/nodel#36, so this is now just a matter of rewiring `ExampleScript` into
  `PyNode`'s default-script path.
- **Minor test-infra leak:** on Unix the gradle `startNodelhost` task keeps the host's stdin
  open via a `bash -c "tail -f /dev/null | java ..."` wrapper, and `stopNodelhost` doesn't
  reap all of it. Observed 2026-07-04: a completed `./gradlew build` orphans the
  `tail -f /dev/null` child (bash and java are killed), and runs interrupted before their
  in-invocation stop leave the whole bash/tail wrapper behind (java child dead, port 18085
  free — harmless, but they accumulate). Defined in `nodel-jyhost/build.gradle`
  (`startNodelhost`/`stopNodelhost`). When cleaning up, `ps` for these patterns and kill only
  the PIDs whose start times match *your* runs — a blanket
  `pkill -f 'tail -f /dev/null'` can EOF the stdin of live hosts belonging to other
  sessions/worktrees on the same machine and shut them down.

---

## Common Issues & Solutions

| Issue | Solution |
|-------|----------|
| Host fails at startup with `InaccessibleObjectException` / `module java.base does not "opens ..."` | Launched with `-cp` but without the GraalPy `--add-opens` flags (the jar manifest's `Add-Opens` only applies to `java -jar`). Use the §2 commands verbatim. |
| `UnsupportedClassVersionError` (class file version 65.0) at startup | PATH `java` is older than 21. Resolve `$JAVA` per §2 (Gradle-provisioned toolchain under `~/.gradle/jdks`). |
| Host exits immediately when backgrounded | Stdin closed. Keep it open: `tail -f /dev/null \| java ...` (see §2). |
| Node doesn't appear after copying into `nodes/` | The directory scan runs every few seconds; poll the node's `/console` endpoint for up to ~30s (observed 7–11s) before concluding failure. |
| 404 `EndpointNotFoundException` right after a rename | The node reloads and re-registers under the new name; poll `$BASE/REST/nodes` until it lists the new name, then continue. |
| `remote/save` seems to "lose" console history | Saving remote bindings restarts the node's script (console shows the `Python node destroyed.` → `Initialising Python node...` reload cycle); history is retained above the reload marker. |
| Gradle tests fail with port conflicts | The suite owns 18085 and kills whatever holds it. Keep the manual smoke host on 8089. |
| Browser clicks "succeed" but nothing happens | See §7 traps: resize the viewport (0×0 default) and use JS `element.click()` instead of coordinate clicks. |
| Clicking a node link on the list page doesn't navigate | nodel.js runs periodic redirect/reload polls that can interrupt an in-flight navigation (see `TestBase.recreatePage` rationale). Navigate directly to `/nodes/<ReducedName>/` — reduced name strips spaces/hyphens/underscores/dots. |
| `/REST/recipes/list` missing official recipes | The recipes-sync node needs network to clone `nodel-official-recipes`; local recipes copied into the host's `recipes/` dir still list fine. |
| POST endpoints return parse errors | Send an explicit empty JSON body: `-H "Content-Type: application/json" -d '{}'`. |

---

## Success Criteria

Minimum viable smoke coverage achieved when:

- [ ] `./gradlew build` → `BUILD SUCCESSFUL`, 0 test failures, standalone + test-fixtures jars produced
- [ ] Standalone host serves HTTP 200 on :8089 from a scratch dir, banner logged, `LocalAutoDNS enabled` visible in `/REST/logs`
- [ ] Host-level REST: `/REST`, `/nodes`, `/diagnostics`, `/logs`, `/toolkit`, `/discovery`, `/allNodes`, `/nodeURLs` all 200 with expected shapes; unknown endpoints 404
- [ ] Both fixture nodes live-load from `nodes/`, consoles show their `started` lines
- [ ] `sendPing` action call returns `true`; console, `/events/Ping` last-value, and `/activity` all show the marker
- [ ] Node restart, recipe-based `newNode`, rename, and `remove?confirm=true` all succeed (with re-registration polling)
- [ ] Binding saved via `/remote/save`, `Wired` handshake in activity, and a ping marker propagates producer → consumer console/activity
- [ ] GraalJS node (`script.js`) boots with JavaScript console markers, its actions/events/params (metadata intact) appear over REST, and its structured event propagates into the Python consumer over a cross-language binding
- [ ] ⚠️ **Browser-driven**: node list renders; node page shows live console; action invoked from the UI reaches both nodes; add-node UI creates a running node (v3 default stub)
- [ ] Multicast section run **or** its blocker proven (logs checked, cause recorded)
- [ ] Cleanup leaves no host process, no `/tmp/nodel-smoke-host`, and a clean `git status`

---

*Authored 2026-07-04 against the 2.x line (v2.2.1 rev550); ported to and fully re-executed
against the v3 line (Nodel 3.0.0 on GraalVM, rev624) on 2026-07-04 — every command above was
run and its output verified on v3 during the port. Expectations that depend on the
scroix/nodel#37 fixes were re-verified against a host built with those fixes applied. The
GraalJS section (§6) arrived with scroix/nodel#34 and was executed the same way — every
command run against a live host built from that branch.*
