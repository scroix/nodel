# Python 3 demo recipes (GraalPy host)

These experimental recipes demonstrate modern **Python 3** node scripts running
on the GraalVM (GraalPy) host. They live here while v3 is a demo/alpha; the
production [`museumsvictoria/nodel-recipes`](https://github.com/museumsvictoria/nodel-recipes)
repository remains a read-only source for compatibility work.

The small demos use Python-3-only syntax directly. Pilot ports preserve the
official recipe's bindings and protocol behaviour while making device I/O
opt-in where practical, so merely loading an example does not contact a LAN.

| Recipe | Shows |
|---|---|
| [`tcp-device/`](tcp-device/script.py) | parameters, local actions/events, managed `TCP()` helper driving a line-based device |
| [`scheduler/`](scheduler/script.py) | parameters, local actions/events, managed `Timer()` emitting periodic structured events |
| [`amx-beacon-receiver/`](amx-beacon-receiver/script.py) | opt-in managed multicast `UDP()`, bounded dynamic discovery events, and AMXB packet parsing; disabling the receiver clears its 128-device registry |
| [`extron-mvc-121-plus/`](extron-mvc-121-plus/script.py) | guarded managed `TCP()`, stopped-until-configured pollers, mixer actions/events, and response parsing |

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
