# Nodel v3 official recipe compatibility sweep

This is the first bounded phase of [scroix/nodel#32](https://github.com/scroix/nodel/issues/32). It records fresh-node load compatibility only; recipe conversion and physical-device behaviour testing are not part of this sweep.

## Reproducing the sweep

Run `scripts/recipe-compat-sweep.sh`. The harness checks out the pinned recipe revision, copies each complete recipe into a fresh host home, and waits for the v3 GraalPy node lifecycle to finish. The `(retired)` tree is excluded; active `Mk*`, `legacy`, and platform-specific variants remain in scope.

The harness binds its HTTP, REST, and native messaging listeners to loopback and suppresses multicast discovery. On macOS the sandbox also denies all other network access, child process execution, and writes outside the temporary host home. Other platforms must provide an equivalent foreground executable through `RECIPE_SWEEP_LAUNCH_WRAPPER`; it must `exec` the host or forward stdin and signals, and remain alive until the host exits. `RECIPE_SWEEP_ALLOW_NETWORK=1` is an explicit escape hatch for an already isolated disposable machine.

A fresh high loopback HTTP port is chosen from `/dev/urandom` for each recipe. Set `RECIPE_SWEEP_PORT` only when a deterministic port is required in an already isolated environment.

- Nodel v3 runtime baseline: `0517041cf6091d9a2449ec883ea60896385d8e32` (GraalVM Community 25.2.4 / JDK 25.0.4)
- Audited recipe revision: [`42cec03bdd0e43fc9252fe3dd6f6900f355f21a3`](https://github.com/museumsvictoria/nodel-recipes/commit/42cec03bdd0e43fc9252fe3dd6f6900f355f21a3)
- Inventory: 91 non-retired `script.py` files
- Isolation used for this run: `macos-sandbox`
- Host startup timeout: 30s
- Per-recipe lifecycle timeout: 30s

A `PASS` means the script parsed, bindings were extracted, lifecycle hooks completed, and the node reached `Python node initialised` without a console error. It does not prove that a device protocol, credential, operating-system command, or long-running callback works.

## Summary

- 36 loaded without a startup error.
- 47 failed on Python 2 syntax or language idioms.
- 8 failed on a missing dependency or runtime assumption.
- 0 failed on Jython-specific Java interop.

The class is the first observable load failure, not an exhaustive list of latent work. 20 recipes contain direct Jython-style `java`, `org`, or `com` imports; 9 of those loaded cleanly through v3's compatibility import hook, and 0 recipes reached Java interop as their first failure. That is useful load evidence, not a device-behaviour guarantee.

## Per-recipe results

| Recipe | Load | Failure class | Evidence |
|---|---:|---|---|
| `AMX beacon receiver` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `APC power controller` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `ATEN USB Industrial Hub switcher via serial` | PASS | — | Reached Python node initialised; no startup error. |
| `Advantech ADAM 6050 6060 relay module/Mk1` | PASS | — | Reached Python node initialised; no startup error. |
| `Advantech ADAM 6050 6060 relay module/Mk2` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Advantech ADAM 6050 6060 relay module/legacy` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `Alcorn 8TraXX` | FAIL | missing dependency/runtime assumptions | TypeError: '>=' not supported between instances of 'ForeignNone' and 'int' |
| `Alcorn Binloop HD player` | PASS | — | Reached Python node initialised; no startup error. |
| `Alcorn DVM-8500 player` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `App Launcher` | FAIL | Python 2 syntax | SyntaxError: invalid decimal literal |
| `BME280 Environmental Sensor chip` | PASS | — | Reached Python node initialised; no startup error. |
| `Barco E2 Event Master` | FAIL | missing dependency/runtime assumptions | E2 not responding. Please check connection!!! |
| `BayTech MRP100 PDU` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Biamp/Mk1 - Nexia` | PASS | — | Reached Python node initialised; no startup error. |
| `Biamp/Mk2 - Tesira (and Nexia)` | PASS | — | Reached Python node initialised; no startup error. |
| `BirdDog Mini Encoder or Decoder` | PASS | — | Reached Python node initialised; no startup error. |
| `Blackmagic Videohub and ATEM Mini range` | PASS | — | Reached Python node initialised; no startup error. |
| `Blaze PowerZone amplifiers via Open API` | PASS | — | Reached Python node initialised; no startup error. |
| `Brightsign/API` | PASS | — | Reached Python node initialised; no startup error. |
| `Brightsign/Brightscript` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `Calendar` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Computer Controller/General` | FAIL | Python 2 syntax | TabError: inconsistent use of tabs and spaces in indentation |
| `Computer Controller/Windows 10` | FAIL | Python 2 syntax | SyntaxError: invalid decimal literal |
| `Dataprobe iBoot/current` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Dataprobe iBoot/legacy` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Denon AV Receiver (AVR-X4100W)` | PASS | — | Reached Python node initialised; no startup error. |
| `Diagnostics/Nodel Framework Diagnostics` | PASS | — | Reached Python node initialised; no startup error. |
| `Diagnostics/Windows Excessive Resource Use Diagnostics` | PASS | — | Reached Python node initialised; no startup error. |
| `ENTTEC ODE Mk2 DMX Interface` | PASS | — | Reached Python node initialised; no startup error. |
| `Epson via ESC VP21 protocol` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Exhibit` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Extron CR48` | FAIL | Python 2 syntax | SyntaxError: invalid decimal literal |
| `Extron G2 series controllers` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Extron IN16XX series presentation switch/Mk1` | FAIL | Python 2 syntax | SyntaxError: invalid decimal literal |
| `Extron IN16XX series presentation switch/Mk2` | PASS | — | Reached Python node initialised; no startup error. |
| `Extron MVC 121 Plus mixer` | FAIL | missing dependency/runtime assumptions | NameError: name 'afterMain' is not defined. Did you mean: 'after_main'? |
| `Extron RAC 104 volume controller` | PASS | — | Reached Python node initialised; no startup error. |
| `Extron USB Series USB Switcher` | PASS | — | Reached Python node initialised; no startup error. |
| `Extron-DTP-CrossPoint-84-matrix-switcher` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Flows` | PASS | — | Reached Python node initialised; no startup error. |
| `Frontend/Mk1` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Frontend/Mk2` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Git Daemon process` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Git Sync process` | PASS | — | Reached Python node initialised; no startup error. |
| `Global Caché iTach IP2CC` | FAIL | Python 2 syntax | SyntaxError: invalid decimal literal |
| `Global Caché iTach IP2IR` | FAIL | Python 2 syntax | SyntaxError: invalid decimal literal |
| `Global Caché iTach IP2SL` | PASS | — | Reached Python node initialised; no startup error. |
| `Grandview motorised screen over HTTP` | PASS | — | Reached Python node initialised; no startup error. |
| `Group` | PASS | — | Reached Python node initialised; no startup error. |
| `InFocus IN126STa` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Integra amplifier` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Kramer VP-433` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `LG monitor/Mk1` | PASS | — | Reached Python node initialised; no startup error. |
| `LG monitor/Serial Gateway` | FAIL | missing dependency/runtime assumptions | Exception: No SetIDs specified |
| `Lutron GRX-CI-NWK-E Lighting Interface` | PASS | — | Reached Python node initialised; no startup error. |
| `Microsoft Exchange schedule retriever` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `NEC Display/Mk1` | PASS | — | Reached Python node initialised; no startup error. |
| `Network Monitors` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `Nodel Host process` | PASS | — | Reached Python node initialised; no startup error. |
| `Nodel Recipe sync` | PASS | — | Reached Python node initialised; no startup error. |
| `Nodel Surveyor` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `OSC Client` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `PDU/IP Power` | PASS | — | Reached Python node initialised; no startup error. |
| `PJLink` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `Panasonic Display/Mk1` | PASS | — | Reached Python node initialised; no startup error. |
| `Pharos Designer 2/API v11` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `Pharos Designer 2/API v12` | FAIL | Python 2 syntax | SyntaxError: multiple exception types must be parenthesized |
| `Philips Display` | PASS | — | Reached Python node initialised; no startup error. |
| `Philips Dynet Lighting Gateway` | PASS | — | Reached Python node initialised; no startup error. |
| `PySerial bridge process` | PASS | — | Reached Python node initialised; no startup error. |
| `QSC Q-SYS Core` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Raspberry Pi` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `SSDP receiver` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Samsung display` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Sangean WFT-1Di DAB and audio player` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Serveredge Switched PDU/Mk1` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Serveredge Switched PDU/Mk2` | PASS | — | Reached Python node initialised; no startup error. |
| `Sharp monitor/Mk1` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Sharp monitor/Mk2` | PASS | — | Reached Python node initialised; no startup error. |
| `Sony SSIP protocol for displays` | FAIL | Python 2 syntax | SyntaxError: invalid decimal literal |
| `Sony VISCA Color Video Camera` | FAIL | missing dependency/runtime assumptions | TypeError: '<' not supported between instances of 'ForeignNone' and 'int' |
| `Ubiquiti switches via UDM Pro API` | FAIL | Python 2 syntax | SyntaxError: invalid decimal literal |
| `VLC media player` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Wake-on-LAN` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Xilica Neutrino A0816-N DSP` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |
| `Yamaha AV Receiver over YNCA protocol` | FAIL | missing dependency/runtime assumptions | ModuleNotFoundError: No module named 'urlparse' |
| `Yamaha BD-S677B Blu-ray player via Global Caché IP2IR` | PASS | — | Reached Python node initialised; no startup error. |
| `Yamaha MRX7-D Signal Processor` | PASS | — | Reached Python node initialised; no startup error. |
| `Yamaha PC412-D Amplifier` | FAIL | missing dependency/runtime assumptions | TypeError: '>=' not supported between instances of 'ForeignNone' and 'int' |
| `Yamaha XMV Series amplifier (ready for MTX3, MTX5-D, MRX7-D, EXi8, EXo8)` | FAIL | missing dependency/runtime assumptions | TypeError: '>=' not supported between instances of 'ForeignNone' and 'int' |
| `projectiondesign projector` | FAIL | Python 2 syntax | SyntaxError: Missing parentheses in call to 'print'. Did you mean print(...)? |

## Representative pilot conversion set

A ten-recipe pilot covers the observed failure shapes without turning this phase into a mass conversion:

- `AMX beacon receiver`: small mechanical `print` conversion and a managed UDP declaration.
- `Brightsign/Brightscript`: old exception syntax in an HTTP-oriented recipe.
- `Extron IN16XX series presentation switch/Mk1`: Python 2 long literals, with the loading Mk2 sibling as a nearby reference.
- `App Launcher`: long literals followed by platform-specific Java/process integration.
- `Frontend/Mk2`: mechanical syntax plus direct `File`, `Stream`, `SimpleName`, and `Nodel` imports.
- `OSC Client`: old exception syntax plus Java file/stream imports and a bundled-module assumption.
- `Alcorn 8TraXX`: a GraalPy `ForeignNone` versus integer comparison in logging.
- `Sony VISCA Color Video Camera`: another `ForeignNone` path in a richer TCP/UDP/HTTP recipe.
- `Yamaha AV Receiver over YNCA protocol`: the Python 2 `urlparse` module rename followed by Java interop.
- `Extron MVC 121 Plus mixer`: the missing legacy `afterMain()` lifecycle assumption.

For a later conversion phase, each pilot should first pass this isolated load sweep, then receive simulator-backed protocol checks where practical. Real credentials, physical devices, fleet operating-system behaviour, the remaining recipes, and the branch-versus-dual-version publishing decision stay out of scope here.
