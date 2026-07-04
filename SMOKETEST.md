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
  (Claude Preview MCP, claude-in-chrome, Playwright). Read §6's tooling notes first — there are
  two known automation traps (zero-size viewport, coordinate clicks that don't land).
- **Do not modify platform source** (Java, webui, gradle config) to make a check pass. A check
  that fails against unmodified source is a finding: record it under Known Issues.
- **Clean up.** §8 must leave no host process, no `/tmp/nodel-smoke-host`, and a `git status`
  showing nothing beyond what you intended to change.

---

## Quick Prep

| Step | Command / Action | Notes |
|------|------------------|-------|
| 1 | `cd` to the repo root | All commands assume it. |
| 2 | `java -version` | Needs JDK 11+ (e.g. `openjdk version "11.0.31"`). |
| 3 | Ports | The manual smoke host uses **8089**; the gradle test suite owns **18085** (and kills anything on it — don't park your own host there). |
| 4 | `export BASE=http://127.0.0.1:8089` | Used by every curl below. |

### Pre-flight Verification

```bash
# 1. JDK present and 11+
java -version
# Expected: 'openjdk version "11...' or later

# 2. Smoke port free (kill leftovers from a previous run)
lsof -ti :8089 | xargs kill 2>/dev/null; rm -rf /tmp/nodel-smoke-host
lsof -ti :8089 || echo "port 8089 free"
# Expected: "port 8089 free"

# 3. Fixture recipes present
ls recipes/smoketest/smoketest-producer/script.py recipes/smoketest/smoketest-consumer/script.py
# Expected: both paths print (no "No such file")
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

- `BUILD SUCCESSFUL` in ~3–5m (a first-ever run also downloads Playwright browsers; `28
  actionable tasks: 28 executed` when forced)
- On the order of 125 `PASSED` lines, `0` FAILED (grep -c exits 1 on zero matches — that's the pass case)
- The only skipped *test* is `DiscoverySmokeTests > testNodeUrlsContainsLocalNode()` (opt-in
  multicast, see §7). A bare grep for `SKIPPED` also matches gradle *task* lines like
  `Task :nodel-webui-js:npmSetup SKIPPED` — ignore those.
- Artifacts appear:

```bash
ls nodel-jyhost/build/distributions/standalone/
# Expected: nodelhost-<branch>-2.2.1-rev<N>.jar  (~20 MB)
ls nodel-framework/build/libs/
# Expected: nodel-framework-2.2.1.jar  AND  nodel-framework-2.2.1-test-fixtures.jar
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

**Option A — Claude Preview MCP** (preferred for agents; the same server then backs §6's browser
checks). Create `.claude/launch.json` (untracked — delete it in §8):

```json
{
  "version": "0.0.1",
  "configurations": [
    {
      "name": "nodel-smokehost",
      "runtimeExecutable": "bash",
      "runtimeArgs": [
        "-c",
        "REPO=\"$PWD\"; mkdir -p /tmp/nodel-smoke-host/nodes; cd /tmp/nodel-smoke-host; tail -f /dev/null | java -cp \"$(ls \"$REPO\"/nodel-jyhost/build/distributions/standalone/nodelhost-*.jar | head -1):$(ls \"$REPO\"/nodel-framework/build/libs/*test-fixtures*.jar | head -1)\" '-Dorg.nodel.discovery.impl=org.nodel.discovery.LocalAutoDNS;instance' org.nodel.jyhost.Launch -p 8089"
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
(cd /tmp/nodel-smoke-host && tail -f /dev/null | java -cp "$JAR:$FIX" \
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
# Expected: _bootstrap_example.json  _bootstrap_schema.json  _instance.lock  custom  nodes  recipes

# Startup banner (host.log for Option B; preview server logs for Option A):
#   Nodel [Jython] v2.2.1-<branch>_r<N> is running.
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
# Expected: JSON object keyed by node name; each value has "nodelVersion":"2.2.1"

# Diagnostics — JVM/host state
curl -s $BASE/REST/diagnostics | python3 -c "import sys,json;print(sorted(json.load(sys.stdin).keys()))"
# Expected keys include: agent, availableProcessors, freeMemory, hostname, httpAddresses,
#                        nodesRoot, startTime, systemProperties, uptime, vmArgs

# Framework logs (newest-first; seq/timestamp/level/message rows)
curl -s "$BASE/REST/logs?from=0&max=3" | head -c 300
# Expected: JSON array; the earliest entry is the LocalAutoDNS warning from §2

# Python toolkit reference served to script authors
curl -s $BASE/REST/toolkit | head -c 120
# Expected: {"script":"from sys import nodetoolkit\n...

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
# Expected: object with "Ping" (no arg yet) and "Status" with "arg":"Ready" (emitted by main())

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
# Expected: newest entries show the reload cycle: "(clean up complete)" then
#           "(Python and script.py loaded in ...)" then "Smoke producer started"
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

## 6. Web UI (browser-driven)

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
| 6.1 | Node list renders | Load `http://127.0.0.1:8089/` | URL settles at `/locals.xml#Locals`. Navbar with the nodel logo and a host icon whose tooltip (`title` attribute) is "Browse this host"; filter box; `total: 3`; links for `Smoke Producer`, `Smoke Consumer`, and the recipes-sync node. |
| 6.2 | Node page renders | Navigate to `/nodes/SmokeProducer/` | URL settles at `.../nodel.xml#Activity`; `document.title` = `Smoke Producer`; navbar brand shows the node name; Console panel shows the same entries as the REST console (e.g. `Smoke producer started`). |
| 6.3 | Action form renders lazily | Expand the "Ping" group panel (`$(document.getElementById('0_actsig_group')).collapse('show')` — the group's form content only renders on expand) | Panel gains class `collapse in`; an `input.form-control` and a `Send Ping` submit button appear inside `.nodel-schema-action[data-name="sendPing"]`. |
| 6.4 | Invoke action from UI | Fill the input with a fresh marker (e.g. `ui-$RANDOM`), then JS-click the `Send Ping` button | Within ~2s the on-page Console shows `Ping sent: <marker>`. Cross-check over REST: producer console has `Ping sent: <marker>` **and** (binding from §5 still live) consumer console has `Received ping: <marker>`. |
| 6.5 | Add node via UI | On `/`, click `.nodel-add .addgrp .dropdown-toggle`, fill `.nodel-add input.nodenamval` with `UI Smoke Node`, click `.nodel-add .nodeaddsubmit` | Browser navigates to the new node's page; its console shows the example recipe starting (`Recipe has started!` plus an `IP address has not been specified` warning). REST: `UI Smoke Node` appears in `$BASE/REST/nodes` within a few seconds. |
| 6.6 | Cleanup UI node | `curl -s -X POST "$BASE/REST/nodes/UI%20Smoke%20Node/remove?confirm=true" -H "Content-Type: application/json" -d '{}'` | `true`; node disappears from `$BASE/REST/nodes`. |

---

## 7. Optional: Real Multicast Discovery (environment permitting)

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

## 8. Cleanup

```bash
# Stop the host: preview_stop <serverId> (Option A) or:
lsof -ti :8089 | xargs kill 2>/dev/null

rm -rf /tmp/nodel-smoke-host
rm -f .claude/launch.json && rmdir .claude 2>/dev/null   # if §2 Option A created it
rm -rf build   # stray root-level gradle problems-report dir left by --tests runs

git status --short
# Expected: nothing beyond changes you intended (a clean playbook run leaves the tree untouched;
# authoring this playbook leaves only SMOKETEST.md and recipes/smoketest/)
```

---

## Known Issues

Genuine product bugs found while executing this playbook get recorded here (do not patch around
them).

- *(no product bugs found — authored 2026-07-04 against rev550 of this working tree; 125/125
  committed tests passing)*
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
| `WARNING: An illegal reflective access operation has occurred` (Jython/guava) on host startup | Benign on JDK 11 — startup proceeds. |
| Host exits immediately when backgrounded | Stdin closed. Keep it open: `tail -f /dev/null \| java ...` (see §2). |
| Node doesn't appear after copying into `nodes/` | The directory scan runs every few seconds; poll the node's `/console` endpoint for up to ~30s (observed 7–11s) before concluding failure. |
| 404 `EndpointNotFoundException` right after a rename | The node reloads and re-registers under the new name; poll `$BASE/REST/nodes` until it lists the new name, then continue. |
| `remote/save` seems to "lose" console history | Saving remote bindings restarts the node's script (console shows `(clean up complete)` → reload); history is retained above the reload marker. |
| Gradle tests fail with port conflicts | The suite owns 18085 and kills whatever holds it. Keep the manual smoke host on 8089. |
| Browser clicks "succeed" but nothing happens | See §6 traps: resize the viewport (0×0 default) and use JS `element.click()` instead of coordinate clicks. |
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
- [ ] ⚠️ **Browser-driven**: node list renders; node page shows live console; action invoked from the UI reaches both nodes; add-node UI creates a running example node
- [ ] Multicast section run **or** its blocker proven (logs checked, cause recorded)
- [ ] Cleanup leaves no host process, no `/tmp/nodel-smoke-host`, and a clean `git status`

---

*Authored 2026-07-04 against this working tree (v2.2.1 rev550); every command above was executed
and its output verified during authoring.*
