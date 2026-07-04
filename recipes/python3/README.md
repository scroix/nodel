# Python 3 demo recipes (GraalPy host)

Two small recipes demonstrating modern **Python 3** node scripts running on the
GraalVM (GraalPy) host — f-strings, `dict.items()`, `print()` and keyword-only
arguments; none of this parses under the legacy Jython 2.5 host.

| Recipe | Shows |
|---|---|
| [`tcp-device/`](tcp-device/script.py) | parameters, local actions/events, managed `TCP()` helper driving a line-based device |
| [`scheduler/`](scheduler/script.py) | parameters, local actions/events, managed `Timer()` emitting periodic structured events |

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
