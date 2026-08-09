# Python 3 demo recipes (GraalPy host)

These experimental recipes demonstrate modern **Python 3** node scripts running
on the GraalVM (GraalPy) host. They live here while v3 is a demo/alpha; the
production [`museumsvictoria/nodel-recipes`](https://github.com/museumsvictoria/nodel-recipes)
repository remains a read-only source for compatibility work.

The small demos use Python-3-only syntax directly. Pilot ports preserve the
official recipe's bindings and protocol behaviour while making device I/O
opt-in where practical, so merely loading an example does not contact a LAN.
Pilot sources are audited against museumsvictoria/nodel-recipes commit
`42cec03bdd0e43fc9252fe3dd6f6900f355f21a3`.

| Recipe | Shows |
|---|---|
| [`tcp-device/`](tcp-device/script.py) | parameters, local actions/events, managed `TCP()` helper driving a line-based device |
| [`scheduler/`](scheduler/script.py) | parameters, local actions/events, managed `Timer()` emitting periodic structured events |
| [`amx-beacon-receiver/`](amx-beacon-receiver/script.py) | opt-in managed multicast `UDP()`, bounded dynamic discovery events, and AMXB packet parsing; disabling the receiver clears its 128-device registry |
| [`extron-mvc-121-plus/`](extron-mvc-121-plus/script.py) | guarded managed `TCP()`, stopped-until-configured pollers, mixer actions/events, and response parsing |
| [`brightsign-brightscript/`](brightsign-brightscript/script.py) | Python 3 HTTP control and bounded state reconciliation for the Brightsign plugin, with polling stopped until an address is configured and cross-host redirects disabled |
| [`extron-in16xx-mk1/`](extron-in16xx-mk1/script.py) | Python 3 long-integer cleanup, guarded managed `TCP()`, dynamic input bindings, corrected SIS input queries, bounded diagnostics, queue-safe reconnects, and an opt-in raw Send action |
| [`osc-client/`](osc-client/script.py) | contained Python 3 OSC encoding, parameter-defined actions, bounded message strings, and managed UDP without runtime dependency downloads |
| [`alcorn-8traxx/`](alcorn-8traxx/script.py) | ForeignNone-safe logging, lazily configured managed `TCP()`, restart-safe binding changes, queued 8 TraXX acknowledgements, bounded responses, and connection-aware status |

These alpha examples assume a trusted management network: Nodel's management
APIs are unauthenticated, and configured devices are trusted peers. The shared
`get_url` helper limits response size and idle read time but does not impose a
total response deadline. Simulator tests therefore cover compatibility and
protocol behaviour, not hostile networks or physical-device validation.

## Trying them

Copy a recipe folder into the host's `nodes/` directory (or create a node in the
web UI and paste the script). Everything is drivable over REST, e.g.:

```bash
# save a parameter (node restarts with the new value)
curl -X POST -H "Content-Type: application/json" \
     -d '{"IntervalSeconds": 2}' \
     "http://localhost:8085/REST/nodes/DemoScheduler/params/save"

# invoke an action
curl -X POST -H "Content-Type: application/json" -d '{}' \
     "http://localhost:8085/REST/nodes/DemoScheduler/actions/Start/call"

# watch events / console
curl "http://localhost:8085/REST/nodes/DemoScheduler/activity"
curl "http://localhost:8085/REST/nodes/DemoScheduler/console?from=0&max=20"
```

The TCP device recipe defaults to `127.0.0.1:4999`; point it at any line-based
TCP service (`nc -lk 4999` works for experimenting) or set its `Address`
parameter.

See `POLYGLOT_INTEGRATION.md` (§6) for recipe-authoring notes on Python 3
differences from the Jython host.
