# Nano fixture recipe

`nanofix` is a synthetic cross-runtime fixture for comparing a Nodel v3
GraalPy host with a 2021-era Nodel v2 Jython host. It does not claim or test
compatibility with any real device.

## Contract

Each node exposes:

- string parameter `label`, used for parameter save/read round-trip checks;
- string action `ping`, which immediately emits local string event `pong` with
  the same token;
- integer event `tick500`, emitted every 500 ms with a monotonically increasing
  sequence;
- integer event `tick5s`, emitted every 5 s with a monotonically increasing
  sequence;
- a line-based TCP client to `127.0.0.1:7401`, sending `SEQ <n>\n` every second
  while connected and emitting integer event `tcpEcho` for each echoed sequence;
- a UDP client to `127.0.0.1:7402`, sending `SEQ <n>\n` every second while ready
  and emitting integer event `udpEcho` for each echoed sequence.

Each counter starts at 1 and does not reset while its node runs. Test drivers
must tolerate a reset to 1 after a node restart.

## Run the echo peer

The peer uses only the Python 3.8 standard library and serves both transports:

```bash
python3 recipes/nano-fixture/tools/echo_peer.py
```

Use `--tcp-port` and `--udp-port` to override the defaults when testing the peer
itself. The recipes intentionally use the fixed contract ports.

## Install on Nodel v3

Create one directory per fixture node under the host home and copy the v3
script into each directory:

```bash
mkdir -p "$NODEL_V3_HOME/nodes/NanoFix1"
cp recipes/nano-fixture/v3/script.py \
  "$NODEL_V3_HOME/nodes/NanoFix1/script.py"
```

Repeat as `NanoFix2` through `NanoFixN`. The host live-scans `nodes/`; an
existing node can be restarted after replacing its `script.py`.

## Install on Nodel v2

Use the same node layout with the Jython 2.5-compatible script:

```bash
mkdir -p "$NODEL_V2_HOME/nodes/NanoFix1"
cp recipes/nano-fixture/v2/script.py \
  "$NODEL_V2_HOME/nodes/NanoFix1/script.py"
```

Repeat as `NanoFix2` through `NanoFixN`.

## Basic REST checks

With a fixture named `NanoFix1` on the default local port:

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"label":"round-trip-1"}' \
  'http://127.0.0.1:8085/REST/nodes/NanoFix1/params/save'
curl 'http://127.0.0.1:8085/REST/nodes/NanoFix1/params'

curl -X POST -H 'Content-Type: application/json' \
  -d '{"arg":"token-1"}' \
  'http://127.0.0.1:8085/REST/nodes/NanoFix1/actions/ping/call'
curl 'http://127.0.0.1:8085/REST/nodes/NanoFix1/events'
```

