> This document tracks the migration of **Nodel** from a Jython-based "Python 2 on the JVM" model to a **GraalVM Polyglot** model that runs **Python 3**.
> It is intended both as architecture reference and as an up-to-date progress ledger.

---

## 1. Vision & Top-Level Goals

---

1.  **First-class Python 3**
    *   Enable node authors to write modern Python without Jython's limitations.
    *   Permit use of current Python libraries (subject to GraalPy compatibility).

2.  **One polyglot bridge, many languages**
    *   While the immediate target is Python, GraalVM also unlocks JavaScript, Ruby, R, etc., for future node types.

3.  **Keep the Java host intact**
    *   Networking, persistence, scheduling and web UI continue to live in `nodel-framework` / `nodel-jyhost` with minimal API churn for scripts.

---

## 2. Architecture Snapshot (GraalVM Path)

---

```mermaid
graph TD
    Browser -->|REST / WebSocket| PyNodePlugin[Java Host (PyNode plugin)<br>• creates Context per node<br>• injects ManagedToolkit<br>• evals nodetoolkit + user];
    HostCore[Java Host (Core)<br>• HTTPD / UI<br>• Discovery / I/O<br>• Persistency] <--> PyNodePlugin;
    PyNodePlugin -->|Polyglot API| GraalPyContext[GraalPy Context<br>(per node)];
    GraalPyContext -->|Python calls| PythonScripts[script.py<br>recipes/*];

    style Browser fill:#f9f,stroke:#333,stroke-width:2px;
    style HostCore fill:#ccf,stroke:#333,stroke-width:2px;
    style PyNodePlugin fill:#ccf,stroke:#333,stroke-width:2px;
    style GraalPyContext fill:#cfc,stroke:#333,stroke-width:2px;
    style PythonScripts fill:#cfc,stroke:#333,stroke-width:2px;
```

**Key facts**

*   One `org.graalvm.polyglot.Context` per node; created in `PyNode.createContext()`.
*   `allowAllAccess(true)` is a temporary bootstrap; will be hardened (see tasks).
*   `nodetoolkit.py` is executed first; it wraps the injected Java `ManagedToolkit` and recreates decorators (`@local_action`, `@remote_event`, …).
*   `BindingsExtractor` walks the Context's Python globals (`Value`) and registers Nodel actions/events/params.

---

## 3. Progress to Date (Apr 2025)

---

*   [x] **Interpreter swap**
    *   Jython removed from build; GraalPy libs added.
    *   Python stdout/stderr bridged to Nodel console with line buffering.

*   [x] **Lifecycle parity**
    *   `before_main`, `main`, `after_main`, `at_cleanup` executed in order.
    *   Hot-reload works on script/config file change.

*   [x] **Bindings discovery**
    *   Extractor rewritten for `Value` API; JSON/docstring metadata handled.

*   [x] **Python-side toolkit**
    *   Console shim keeps `console.instance` pattern alive.
    *   Timer, TCP, UDP, call/call_safe, decorator helpers ported.

*   [x] **Build & runtime** (Aug 2026)
    *   Gradle 9.6.1, GraalVM Community 25.2.4 on JDK 25.0.4 via the
        auto-provisioned Gradle toolchain (foojay resolver). Gradle-run hosts
        and tests use its matching LibGraal optimizing runtime.
    *   The shadow jar carries `Add-Opens` and `Enable-Native-Access` manifest
        entries, so `java -jar nodelhost.jar` runs flagless on the matching
        GraalVM distribution. Other JDK distributions are unsupported.
    *   Node starts, runs recipes, REPL (`exec`, `eval`) functional.
    *   Rebased onto `dev` (Playwright integration/e2e suite, LocalAutoDNS test
        discovery, dependency bumps); full `./gradlew build` green.

*   [x] **Node management & config parity** (Jul 2026)
    *   Restored the REST services the interpreter swap had dropped: `params`,
        `remote`, `restart`, `rename`, `update`, `remove`, `files`.
    *   `nodeConfig.json` is loaded on init and saved via the services; saved
        remote-binding values and parameter values inject into the extracted
        bindings; `param_X` globals receive their saved values before `main()`.
    *   Live host objects are exposed back into Python globals (`local_event_X`,
        `remote_action_X`) so `.emit(...)` / `.call(...)` work like Jython.
    *   Hot reload actually monitors the script/config files; failed script
        loads keep the node alive in an inspectable error state.
    *   The toolkit is enabled after `main()` (managed TCP/UDP now connect).

*   [x] **Wire compatibility proven** (Jul 2026)
    *   `scripts/compat-smoke.sh`: stock release `nodelhost` (Jython,
        v2.2.1.542) and the GraalVM host side-by-side with real multicast
        discovery + Nodel TCP binding — mutual discovery and remote
        action/event round-trips pass in BOTH directions.

*   [x] **Demo Python 3 recipes** (Jul 2026)
    *   `recipes/python3/` — TCP device node and timer/scheduler node with
        actions, events and parameters, REST-verified.

*   [x] **GraalJS node support — polyglot showcase** (Jul 2026, goal 2)
    *   **Language dispatch**: a node folder containing `script.js` boots a
        GraalJS context; `script.py` boots GraalPy (if both exist, Python wins
        with a warning). Detection lives in `PyNode.detectScriptLanguage()`;
        the rest of `PyNode` was already language-neutral (polyglot `Value`
        API throughout) and is now parameterised by a per-node `_languageId`.
    *   **JS toolkit shim** (`nodetoolkit.js`): console bridge, binding
        metadata helpers (`LocalEvent`/`RemoteAction`/`Parameter`),
        `createLocalAction`/`createLocalEvent`/`createRemoteAction`/
        `createRemoteEvent`, managed `Timer` + browser-style
        `setTimeout`/`setInterval`, lifecycle hooks (`beforeMain`/`afterMain`/
        `atCleanup`), `call`/`callSafe`, `jsonEncode`/`jsonDecode`, and
        `TCP`/`UDP` helpers (options-object style).
    *   **Zero changes** to `BindingsExtractor`, `ManagedToolkit`, or the
        wire/binding layer — confirming the node/toolkit stack is
        language-agnostic. `nodeConfig.json` handling (params, remote binding
        values) works for JS nodes unchanged.
    *   **Errors**: guest exceptions from JS nodes render as JS-style
        stacks (`TypeError: ... \n    at fn (script.js:12)`) in the web
        console (`PyNode.formatJavaScriptStack`); Python nodes keep their
        CPython tracebacks.
    *   **Verified**: `scripts/polyglot-smoke.sh` — JS + Python peers in ONE
        host; REST-visible JS actions/events/params; JS parameter
        save/reload; event and remote-action round trips in both directions
        (JS→Py and Py→JS). `scripts/compat-smoke.sh` extended with a GraalJS
        peer cross-bound to the STOCK Jython v2.2.1 host — all four JS↔Jython
        wire round trips pass alongside the original Python↔Jython ones.
        Plus `JsNodeTest` (JUnit, runs in CI's integrationTest job) covering
        dispatch, binding discovery, action invocation, lifecycle hooks and
        mixed-language hosting.
    *   **Docs**: `recipes/javascript/README.md` — "writing a JavaScript
        node" guide with a side-by-side Python/JS surface table; demo recipe
        under `recipes/javascript/greeter/`.
    *   **Drive-by fixes** (both languages): REST-invoked declarative actions
        no longer log an NPE (`handleActionRequest` now tolerates the absent
        completion callback); `nodetoolkit.py`'s `same_value` and `lookup_*`
        helpers now call the real `ManagedToolkit` methods
        (`areSameValue`/`get*` — they previously named methods that don't
        exist and threw at runtime).

*   [x] **Self-contained distributable** (Aug 2026, goal 3)
    *   **Shipped artifact: jpackage app-image** (bundled Java 25 runtime — no
        Java needed on the target machine). `./gradlew :nodel-jyhost:packageAppImage`
        stages the shadow jar, runs the GraalVM toolchain's
        `jpackage --type app-image` with the complete GraalVM module set
        (including compiler/JVMCI), Add-Opens and native-access options, and
        archives it with command-line
        `zip`/`tar` (Gradle's archivers drop symlinks/exec bits). ~300 MB
        uncompressed on macOS arm64.
        Verified: boots in an env with no `java`/`JAVA_HOME` (macOS) and in a
        java-less `debian:bookworm-slim` container (CI); passes
        `packaged-smoke.sh`, `polyglot-smoke.sh` and `compat-smoke.sh`.
    *   **CI**: `.github/workflows/package.yml` builds the Linux x64 app-image
        on every push (v3 / feat branches), smokes it inside a java-less
        container (asserting `java` is absent), and uploads the tar.gz
        artifact.
    *   **Smoke plumbing**: `smoke-lib.sh start_host` now execs a non-`.jar`
        `GRAAL_NODEL_JAR` directly, so every suite can gate a packaged
        launcher; `scripts/packaged-smoke.sh` (prepare/verify/run) is the
        deterministic, discovery-free functional gate used by CI.
    *   **Console-less stdin fix** (`Launch.tryReadFromConsole`): the packaged
        launcher bakes `-Dnodel.consoleless=true` into its java options, so an
        instant stdin EOF (double-click, `nohup`, `docker run -d`) no longer
        shuts the packaged host down at boot. Without the property (plain jar,
        test harnesses) EOF still means orderly shutdown, as always.

    **Native Image attempt (time-boxed, goal 3) — outcome: WORKS but demoted
    to experimental.** `./gradlew -PnativeImage :nodel-jyhost:nativeCompile`
    with `GRAALVM_HOME` = GraalVM Community 25.2.4 builds
    a 294 MB self-contained executable in ~2.5 min (peak RSS ~11.2 GB) that
    passes packaged-smoke, polyglot-smoke AND compat-smoke (full multicast
    discovery + all wire round trips vs stock Jython v2.2.1). Config ledger —
    every workaround and its why:
    *   Graal 25 language POMs are OSS licensed, so native-image and JVM builds
        use the same `org.graalvm.polyglot:python` / `js` dependencies without
        the old `-community` substitution.
    *   Removed `--initialize-at-build-time=org.slf4j` (was in the stale
        `graalvmNative` block): it captured Nodel's own slf4j binding
        (`SimpleLoggerFactory` → `Level` → timers/threads) in the image heap,
        which native-image rejects.
    *   `META-INF/native-image/org.nodel/nodel-jyhost/reachability-metadata.json`
        — generated by the native-image tracing agent driving the full
        packaged smoke on the JVM host. Covers the REST/reflection layer, JUL,
        and resources (`org/nodel/build.json`, `content.zip`, toolkit shims,
        nanohttpd mimetypes). Regenerate the same way if the REST surface
        changes.
    *   `META-INF/native-image/org.nodel/nodel-jyhost-manual/` (hand-written)
        — (a) the tracing agent does NOT capture Truffle guest→host interop,
        so every script-facing host type (ManagedToolkit, Console$Interface,
        Managed*, NodelServer/Client Action/Event, Handler$H0..H5 + proxy
        entries) is registered manually; first missing member fails as
        `Unknown identifier: getConsole`. (b) ALL 406 `org.nodel.**` classes
        are bulk-registered because Nodel's `Serialisation`/`Schema` reflect
        over their own model classes at runtime — an unregistered wire class
        (first seen: `NameServicesChannelMessage`) makes `Serialisation.wrap`
        fall back to `toString()`, which recurses into `serialise` →
        `StackOverflowError` and discovery goes silently dead. (c)
        `org.slf4j.impl.JDK14LoggingHandler`, loaded reflectively by JUL.
    *   **Why demoted**: recipes are trusted code that may interop with ANY
        Java class (`java.type(...)`), but a native image only exposes
        classes registered at build time. The default first-run node
        (recipes-sync, JGit) already fails with
        `TypeError: 'ForeignAbstractClass' object is not callable`; SNMP4J /
        jjwt / drop-in-jar recipes would fail the same way. This is inherent
        to AOT, not a missing config — the jpackage app-image has zero such
        caveats, so it ships.

    **Notes for goal 3 (native image)** — architectural record:
    *   The language set is now **GraalPy + GraalJS** (`org.graalvm.polyglot:js`
        added in `nodel-jyhost/build.gradle`); native-image configs must
        include both Truffle languages.
    *   Language selection is a per-node runtime decision keyed off the
        script filename — nothing is compile-time bound to Python, so adding
        a language = new `Context.newBuilder(id)` branch + a toolkit shim
        resource + traceback formatter.
    *   GraalJS enforces single-threaded context access (unlike GraalPy's
        GIL). The node's `CallbackQueue` already serialises actions, events
        and toolkit callbacks, so this holds in practice; concurrent REST
        `eval`/`exec` against a busy JS node is the only unserialised path
        (same behaviour class as Jython's interpreter lock — acceptable).

---

## 4. Remaining Work (priority-ordered)

---

### 4.1 HostAccess hardening

_(Deliberately out of scope for landing the migration: the GraalPy host keeps
the same trust model as the Jython host today — recipes are fully trusted code.
`allowAllAccess`/`HostAccess.ALL` stays until a dedicated hardening pass.)_

*   [ ] Switch `allowAllAccess(true)` -> `HostAccess.EXPLICIT`.
*   [ ] Annotate `ManagedToolkit` and other exposed classes with `@HostAccess.Export`.
*   [ ] Add negative tests ensuring forbidden reflection is blocked.

### 4.2 Toolkit internal clean-up

*   [x] Remove lingering `PyObject`, `PyFunction` fields; replace with `Value` when a back-reference is required.
*   [x] Audit methods that still return/accept Jython types.

### 4.3 Residual Jython code paths

*   [x] pysp servlet / template engine. Decide: port or deprecate.
*   [x] CLI helpers (JyConsole, etc.) – likely obsolete; remove or port.
*   [x] Unit tests referencing `PythonInterpreter`.

### 4.4 Recipe import semantics

*   [x] Reinstate Java import hook (`JavaImportFinder`) *or* document `polyglot.import_value("java.type", "...")`.
*   [x] Run Python 2->3 conversion on official recipe set; fix remaining syntax.

### 4.5 Threading & Callback discipline

*   [x] Decide on one of:
    *   a) single `_busy` lock + CallbackQueue, or
    *   b) rely solely on CallbackQueue (GraalPy already serialises).
*   [x] Remove redundant locking.

**Decision**: Option B was chosen. The `_busy` lock has been removed from PyNode, relying instead on GraalVM's native thread safety mechanisms and the CallbackQueue. Thread safety tests have been implemented to validate this approach (see PyNodeThreadingTest.java). This simplification improves performance by reducing lock contention while maintaining thread safety guarantees.

### 4.6 Context-in-native-image

*   [ ] Supply reflection/resource configs for Native Image (JGit, Jetty, SLF4J).
*   [ ] Add CI job building `nodel-jyhost-native`.

### 4.7 Error surfacing

*   [x] Map `PolyglotException` into structured JSON for web UI (parity with Jython).

**Implementation**: guest exceptions are rendered with Python's own `traceback`
module (via the guest exception object), so the web console's `err` stream shows
a genuine CPython-style traceback — file, line, function, source caret for syntax
errors — as structured console-log JSON items. A synthesised formatter
(`PyNode.formatPythonTraceback`) is the fallback when the guest object isn't
usable. A broken script no longer kills node creation: the node stays alive in an
error state (console inspectable, hot reload picks up the fix), matching Jython
behaviour.

### 4.8 Parameter serialisation check

*   [x] Verify `jsonEncode/jsonDecode` round-trip for GraalPy values; patch where necessary.

**Implementation**: `PolyglotValues` (nodel-framework) normalises guest values —
str/int/float/bool/None, lists, dicts, nested — into plain Java objects before
serialisation, and parses any top-level JSON value on decode (the stock
serialisation layer only accepted JSON objects, emitted top-level strings
unquoted, and dropped `None` dict values). `ManagedToolkit.toJson/fromJson`
(recipe `json_encode`/`json_decode`) delegate to it; `nodetoolkit.py` had been
calling the old `jsonEncode`/`jsonDecode` names, which no longer existed.
Verified by `GraalPyJsonRoundTripTest`.

### 4.9 Documentation & tooling

*   [x] BUILDING.md updated: GraalVM Community 25.2.4 + auto-provisioned
    toolchain (no `GRAALVM_HOME` needed), testing and wire-compat
    smoke instructions.
*   [x] Recipe-authoring notes for Python 3 differences (see §6).
*   [ ] Provide a "compatibility matrix" (feature / Jython / GraalPy).

---

## 5. Short-Term Roadmap

---

*   **Week 1**
    *   Lock down HostAccess; fix compile errors.
    *   Purge obvious Jython classes; green build.

*   **Week 2**
    *   Decide fate of pysp; implement path.
    *   Reactivate Java import hook; upgrade 10 pilot recipes.

*   **Week 3**
    *   Native-image CI; reflection configs.
    *   Web-UI error propagation parity.

*   **Week 4**
    *   Full recipe migration; ship beta artefact.

---

## 6. Conventions & Tips for Node Authors

---

Working demo recipes live in `recipes/python3/` (TCP device + scheduler) and
`recipes/javascript/` (greeter). For **JavaScript** nodes (`script.js` under
GraalJS) see the dedicated guide — `recipes/javascript/README.md` — which
maps every Python convention below to its JS counterpart.

**Python 3 language differences** (vs the Jython 2.5 host)

*   `print('x')` is a function — `print 'x'` is a syntax error.
*   f-strings are available and preferred: `print(f'level: {level}')`.
*   `dict.items()` / `.keys()` / `.values()` replace `iteritems()` etc.
*   Integer division is `//`; `/` always yields a float.
*   Exceptions: `except Exception as e:` (not `except Exception, e:`).
*   `unicode`/`basestring` are gone — everything is `str`.

**Toolkit / binding conventions** (unchanged from Jython)

*   Declarative bindings still work the same way: `param_X = Parameter({...})`,
    `local_event_X = LocalEvent({...})`, `remote_action_X = RemoteAction({...})`,
    `def remote_event_X(arg): ...`, and the `@local_action({...})` decorator.
    After startup the placeholders are replaced with live objects, so
    `local_event_X.emit(arg)` and `remote_action_X.call(arg)` behave as before.
*   Timers and network helpers are injected into the script's namespace by the
    toolkit (no import needed): `Timer`, `TCP`, `UDP`, `call`, `call_safe`,
    `json_encode`, `json_decode`, lifecycle decorators (`@before_main`,
    `@after_main`, `@at_cleanup`).

**Java interop**

*   Import Java types with the GraalPy interop API:
    ```python
    import java
    Git = java.type('org.eclipse.jgit.api.Git')
    ```
    Plain `import org.eclipse.jgit...` also works via the toolkit's import hook.

**Error reporting**

*   Script and handler errors surface in the node's web console as genuine
    Python tracebacks (file, line, function) on the error stream — same place
    as the Jython host.

---

## 7. Acknowledgements

---

This migration builds on the original Nodel architecture by Museum Victoria, enriched by the GraalVM community and contributors to this branch. Everyone is welcome to file issues and PRs against the **v3** branch — the long-lived GraalVM (Nodel 3.x) line developed alongside regular Nodel (`dev`, 2.2.x).
