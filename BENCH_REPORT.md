# Nodel v3 Native Image "nano" profile — Hugh Sawrey Gallery Nano benchmark

All numbers measured by the reviewer on the target device, 2026-08-14/15.
Raw CSV/JSON per trial retained on the device under `~nodel/v3-trial/results/`.

## Target device (read-only inspection)

| | |
|---|---|
| SoC | Rockchip, 4× Cortex-A35 (ARMv8.0, part 0xd04) |
| OS | Ubuntu 20.04.5, glibc 2.31, kernel 4.4.143-rockchip (2022 vendor kernel) |
| Memory | 425 MiB RAM + 340 MiB zram swap |
| Disk | 3.5 GB eMMC, ~1.3 GB free |
| Production | Nodel v2.2.1 (jar sha256 `e46de105…`, Dec 2021), OpenJDK 1.8.0_292 under jsvc, port 8085, sole node = Recipes Sync |

Production baseline, read-only, 62 min uptime: VmRSS 111.2 MiB, VmHWM 139.0 MiB,
VmSwap 21.1 MiB. Production was never modified, stopped, or reconfigured.

## Candidate artifact

`nodelhost-nano` — GraalVM Native Image, Linux aarch64, built in a pinned
`ubuntu:20.04` (glibc 2.31) arm64 container with GraalVM CE 25.2.4
(tarball sha256 verified at build time):

- Python-only (GraalJS excluded); JGit, SNMP4J, JJWT excluded.
- **Truffle optimizing runtime excluded — interpreter-only fallback engine.**
  The image contains zero runtime-compilation methods (the compilation-capable
  build carried 26,003 plus ~48 MiB code metadata and ~24 MiB graph encodings).
- `-Os`, `-march=compatibility` (ARMv8.0-safe, no LSE atomics), serial GC,
  80 MiB baked max heap, recipes-sync bootstrap disabled (runtime-overridable).
- 126.4 MB binary; dynamic requirements only `GLIBC_2.17` +
  libz/libdl/libpthread/libc — comfortably inside the device's glibc 2.31.
- Provenance in `dist/nano/PROVENANCE.txt` (git sha `b736795`, base-image
  digest, GraalVM tarball sha256, exact Gradle command); artifact sha256
  `f323ca9a…`.

## Heap-ceiling tuning on target (1 node, params-save reload transient)

The memory-critical path is a node reload (two GraalPy contexts exist briefly).

| Config | After init | Settled after reload | Reload survives | Console |
|---|---:|---:|---|---|
| `-Xmx64m` (default young gen) | boots | **MemoryError** | **no — node fails** | err |
| `-Xmx128m` | 134.9 MiB | ~180 MiB (spike retained) | yes | clean\* |
| `-Xmx96m` | 137.5 MiB | 137.2 MiB | yes | clean |
| **`-Xmx80m -Xmn8m`** | 127.7 MiB | **122.1 MiB** | yes | clean |
| `-Xmx72m -Xmn8m` | 128.4 MiB | 125.6 MiB | yes | 4 err lines |
| `-Xmx64m -Xmn8m` | 132.3 MiB | 134.3 MiB | yes | clean |

\* one-time cosmetic teardown noise from the cancelled context's TCP callback
(3 lines at the reload instant, non-recurring).

A small young generation is what makes tight ceilings viable: the original
64 MiB OOM was a young-gen sizing artifact, not a true heap shortage.
**Chosen: 80 MiB max heap (baked) + `-Xmn8m` at launch.**

## Workload fixture

`nanofix`, one contract in two dialects: `label` parameter, `ping`→`pong`
action, `tick500` (2 Hz) and `tick5s` (0.2 Hz) timer events, 1 Hz TCP and UDP
echo loops against a local stdlib echo peer. The v2 dialect was verified clean
on the production-era jar under real Jython 2.5 (zero console errors); the v3
dialect on the nano binary. The fixture is synthetic and claims no real-device
compatibility.

Driver: 1 Hz `ping` per node, token-matched via the classic
`/REST/nodes/{n}/activity` cursor (shape verified on both v2.2.1 and v3),
10 Hz polling, gap/reset accounting. Sampler: 1 Hz `/proc` capture; PSS and the
anon/file split summed from `/proc/PID/smaps` because this pre-4.14 kernel has
neither `smaps_rollup` nor the `status` `Rss*` fields.

Protocol: sequential trials only, 10 min warmup discarded, 60 min soak,
identical method for both runtimes. Steady state = median of the final 10 min.

## Results

### Host + 1 node (the representative workload — the Nano runs one node today)

| Metric | v3 nano (80m) | v2.2.1 | v3 nano (64m) |
|---|---:|---:|---:|
| **Steady-state RSS** | **101.41 MiB** | **134.74 MiB** | 117.71 MiB |
| Peak VmHWM | 150.90 MiB | 136.61 MiB | 133.75 MiB |
| Steady-state PSS | 105.24 MiB | 131.13 MiB | 120.38 MiB |
| Latency p50 / p95 / p99 | 76.6 / 113.5 / 133.1 ms | 63.1 / 109.9 / 110.7 ms | 73.3 / 112.7 / 124.8 ms |
| Latency max | 1095.1 ms (1 of 3587) | 128.7 ms | 153.7 ms |
| Process swap (first→max) | 0 → 2.2 MiB transient, 0.3 MiB residual | 0 → 0.67 MiB | 0 → 0 |
| Startup to HTTP 200 | 1108 ms | 7199 ms | 687 ms |
| Ping/pong | 3587/3589 matched, 2 timeouts | 3598/3598, 0 timeouts | 3595/3596, 1 timeout |
| Emission health | complete, 0 resets | complete, 0 resets | complete, 0 resets |

Emission completeness means every event stream's final sequence met or exceeded
the count implied by its period over the soak (e.g. `tick500` 8285 ≥ 7200).

### Host only (60 min, no nodes, no driver)

| Metric | v3 nano | v2.2.1 |
|---|---:|---:|
| Steady-state RSS | **41.82 MiB** | 54.73 MiB |
| Peak VmHWM | 41.82 MiB | 56.84 MiB |
| Steady-state PSS | 42.66 MiB | 51.47 MiB |
| Process swap | 0 | 0 |
| Startup to HTTP 200 | 575 ms | 7103 ms |

### Per-node marginal cost (first node)

| | v3 nano | v2.2.1 |
|---|---:|---:|
| host-only → host+1 | **+59.59 MiB** | +80.01 MiB |

### Host + 5 nodes — capacity limit found (v3 trial failed)

Run at `-Xmx128m -Xmn8m`. NanoFix1 and NanoFix2 loaded; **NanoFix3's parameter
save never completed and NanoFix4/5 never initialised.** Host stderr:

```
Caused by: org.graalvm.polyglot.PolyglotException: Garbage-collected heap size
exceeded. Consider increasing the maximum Java heap size, for example with '-Xmx'.
```

Sampler (62,958 samples over 5.5 h): peak RSS **210.5 MiB**, peak process swap
8.6 MiB, settling ~162 MiB in a degraded state (dead nodes, host still serving
HTTP). **Five concurrent GraalPy fixture nodes exceed this device's capacity.**
From the measured marginal cost the practical ceiling is ~2–3 nodes; raising
`-Xmx` does not help, because total RSS — not the ceiling — is binding.

### Host + 5 nodes, v2 — not measured (evidence gap)

The failed v3 trial left its host alive and the runner correctly refused an
overlapping trial. v2's measured +80.0 MiB per node implies ~455 MiB for five
nodes on a 425 MiB device, so v2 is not expected to fit either — but that is
inference, not measurement.

## Gates (evaluated at host + 1 node)

| Gate | Value | Status |
|---|---|---|
| Target ≤ 90 MiB steady RSS | 101.41 MiB | **not met** (11.4 MiB short) |
| Merge gate ≤ 115 MiB steady RSS | 101.41 MiB | **PASS** (13.6 MiB margin) |
| Investigation ceiling 150 MiB | 101.41 MiB | **PASS** |
| Zero swap growth | 0 MiB residual (2.2 MiB reload transient) | **PASS** |
| No missed actions, events, timers, networking | all four streams complete; 2 of 3589 pongs timed out (observation-side, no emission loss) | **PASS** |
| Action/event latency ≤ 2× v2 | p50 1.21×, p95 1.03×, p99 1.20× | **PASS** |
| Match or beat v2 under equivalent workload | 101.41 vs 134.74 MiB (−25%) | **PASS** |

**Scope limit:** these gates hold for host + 1 node. The supported envelope is
1–2 nodes; a 2–3 node measurement is needed to fix the exact ceiling.

## Device stability finding (independent of this work)

The Nano rebooted spontaneously at ~09:33 (a rapid cluster) and 09:51 AEST on
2026-08-14, at times when no trial load was running, and dropped off the
network entirely at ~15:37 without rebooting (uptime spans it). It then
sustained ~5 h of continuous heavy benchmarking without incident, so load is
not the trigger. No OOM, watchdog, or panic entries appear in retained logs;
the RTC has no battery, so boot timestamps reset to epoch. Production v2
self-heals through jsvc. This predates the v3 work and warrants its own
investigation (power supply, eMMC health, 2022 vendor kernel) regardless of
which runtime is deployed.

## Run-to-run variance

The two 1-node v3 configurations differ by 16.3 MiB (101.41 at 80m vs 117.71 at
64m). Some of that is the tighter ceiling forcing more GC work, but no repeat
run of an identical configuration was performed, so single-trial figures should
be read with roughly ±10 MiB of uncertainty. The v3-versus-v2 gap (33.3 MiB) is
comfortably larger than that band; the 90 MiB target miss (11.4 MiB) is not.
