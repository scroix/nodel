# Smoke-test fixture recipes

Purpose-built fixture nodes for the smoke-test playbook at [SMOKETEST.md](../../SMOKETEST.md)
(repository root). They are not example recipes for production use — real recipes live in
[museumvictoria/nodel-recipes](https://github.com/museumvictoria/nodel-recipes).

| Fixture | Purpose |
|---------|---------|
| `smoketest-producer/` | `sendPing` action emits a `Ping` local event (plus a `Status` event and a `Label` parameter, so the node-level REST surface has something on every endpoint). |
| `smoketest-consumer/` | `IncomingPing` remote event logs each received ping and re-emits it as a `Received` local event — proves inter-node action → event propagation once bound to the producer. |

To use: copy each fixture directory into the running host's `nodes/` directory
(the host live-scans it), e.g.

```bash
cp -R recipes/smoketest/smoketest-producer "$HOST_DIR/nodes/Smoke Producer"
cp -R recipes/smoketest/smoketest-consumer "$HOST_DIR/nodes/Smoke Consumer"
```

then follow the playbook.
