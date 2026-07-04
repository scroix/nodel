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

*   [x] **Build & runtime**
    *   Gradle 8.13, Java 21, GraalVM Native Image plugin compile.
    *   Node starts, runs recipes, REPL (`exec`, `eval`) functional.

---

## 4. Remaining Work (priority-ordered)

---

### 4.1 HostAccess hardening

_(Deferred as lower priority; not critical path for initial functionality)_

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

*   [ ] Verify `jsonEncode/jsonDecode` round-trip for GraalPy values; patch where necessary.

### 4.9 Documentation & tooling

*   [ ] Update BUILDING.md with `GRAALVM_HOME`, native-image flags, recipe migration guide.
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

*   Use `from polyglot import import_value` to reach Java types, e.g.
    `Git = import_value("java.type:org.eclipse.jgit.api.Git")` (subject to final import policy).

*   All timers & network helpers are now classes/functions in `nodetoolkit` – import them directly:
    ```python
    from nodetoolkit import Timer, TCP, local_action
    ```

*   Python 3 only – ensure `print()` functions, `items()` instead of `iteritems()`, integer division (`//`) where appropriate.

---

## 7. Acknowledgements

---

This migration builds on the original Nodel architecture by Museum Victoria, enriched by the GraalVM community and contributors to this branch. Everyone is welcome to file issues and PRs against the **migrate-to-graalvm** branch while the work is stabilising.