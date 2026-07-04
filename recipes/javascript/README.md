# Writing a JavaScript node (GraalJS host)

Nodel 3.x hosts are polyglot: a node folder containing **`script.js`** boots a
GraalJS context, exactly as `script.py` boots a GraalPy one — same host, same
web UI, same wire protocols, same binding model. Python and JavaScript nodes
run side by side and bind to each other like any other Nodel nodes.

| Recipe | Shows |
|---|---|
| [`greeter/`](greeter/script.js) | a parameter, local actions (both styles), a local event, the managed `Timer` |

## The authoring surface

The JS surface is deliberately parallel to the Python one — same concepts,
same naming conventions, JS syntax:

| Concept | Python (`script.py`) | JavaScript (`script.js`) |
|---|---|---|
| Parameter | `param_X = Parameter({...})` | `var param_X = Parameter({...})` |
| Local event | `local_event_X = LocalEvent({...})` | `var local_event_X = LocalEvent({...})` |
| Local action (named) | `def local_action_X(arg):` | `function local_action_X(arg) {}` |
| Local action (with metadata) | `@local_action({...})` decorator | `createLocalAction('X', fn, {...})` |
| Remote action | `remote_action_X = RemoteAction({...})` | `var remote_action_X = RemoteAction({...})` |
| Remote event handler | `def remote_event_X(arg):` | `function remote_event_X(arg) {}` |
| Entry point | `def main():` | `function main() {}` |
| Lifecycle hooks | `@before_main` / `@after_main` / `@at_cleanup` | `beforeMain(fn)` / `afterMain(fn)` / `atCleanup(fn)` |
| Console | `console.info(...)` | `console.info(...)` |
| Timers | `Timer(fn, interval_s)` | `new Timer(fn, intervalSeconds)` — plus `setTimeout(fn, ms)` / `setInterval(fn, ms)` |
| Deferred calls | `call(fn, delay)` / `call_safe(...)` | `call(fn, delaySeconds)` / `callSafe(...)` |
| JSON | `json_encode` / `json_decode` | `jsonEncode` / `jsonDecode` |
| TCP / UDP | `TCP(dest=..., received=...)` | `TCP({ dest: ..., received: ... })` |

After startup the declaration placeholders are replaced with live host
objects, so — exactly like Python — you call:

```js
local_event_Status.emit('on');       // emit a local event
remote_action_Power.call('mute');    // call the bound remote action
```

and `param_X` globals hold their saved values before `main()` runs.

## Minimal example

```js
var param_Greeting = Parameter({ title: 'Greeting', schema: { type: 'string' } });

var local_event_Greeted = LocalEvent({ title: 'Greeted', schema: { type: 'string' } });

function local_action_Greet(arg) {
    var message = (param_Greeting || 'Hello') + ', ' + (arg || 'world') + '!';
    console.info(message);
    local_event_Greeted.emit(message);
}

function main() {
    console.info('greeter started');
}
```

Create a node in the web UI, save the above as `script.js` (and remove
`script.py` — if both exist, Python wins), and the action, event and
parameter appear in the UI and over REST like any other node.

## Rules & gotchas

* **Declare bindings with `var` or `function`** — top-level `let`/`const`
  are scoped to the script, not the global object, so the host can neither
  discover them nor swap in the live objects.
* One script file per node: `script.js` *or* `script.py`. If both are
  present the node runs Python and logs a warning.
* `console.log/info/warn/error` map to the node console's out/info/warn/err
  streams. Errors surface as JS-style stack traces in the web console.
* `setTimeout`/`setInterval` are provided by the toolkit (backed by the
  node's managed timers, so they are released on restart). `setInterval`
  returns a handle for `clearInterval(handle)` / `handle.stop()`.
* Script and handler execution is serialised through the node's callback
  queue — the usual JS single-threaded mental model applies.
* Java interop is available (same trust model as Python recipes):
  `var HashMap = Java.type('java.util.HashMap');`
* **Modules**: scripts are evaluated as classic scripts, not ES modules, so
  `import`/`export` and `require(...)` are not available. Small npm libraries
  that ship plain browser-style bundles can be pasted alongside and loaded
  manually, but there is no module loader — keep recipes self-contained.

## Cross-language binding

Nothing special is required — remote bindings are language-neutral. A
JavaScript node's `remote_action_X` can point at a Python node's action and
vice versa; events bind the same way. See
[`scripts/polyglot-smoke.sh`](../../scripts/polyglot-smoke.sh), which drives a
JS node and a Python node in one host through event and action round trips in
both directions.
