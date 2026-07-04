# Nodel: Java/Jython Integration Architecture

This document describes the integration architecture between the core Java host and the Python-based node scripting layer (using Jython) in the Nodel project. It synthesizes information from various descriptions to provide a consolidated understanding for developers.

## 1. Core Architecture: Java Host + Python Nodes

* **Java Host:** The foundation is a robust Java application (`nodel-framework`, `nodel-jyhost`) that acts as the "node host". It manages the overall system, including:
    * Networking (TCP, UDP, SSH, HTTP/REST)
    * Node lifecycle management
    * Configuration and persistence
    * Threading and concurrency control
    * Timers and scheduled tasks
    * Resource management (connections, processes)
    * Logging
* **Python Nodes ("Recipes"):** Individual "nodes" (representing devices, services, or logic) have their behavior defined in Python scripts.
    * Typically named `script.py`, potentially with dependencies like `ingredients*.py` or `custom*.py`.
    * These scripts are executed using **Jython**, a Python implementation running on the Java Virtual Machine (JVM).
* **Division of Labor:**
    * **Java:** Handles the complex, low-level infrastructure and provides core services reliably.
    * **Python (Jython):** Provides the flexible, user-facing scripting layer for defining node-specific logic and integrations easily.

## 2. Embedding Jython (`PyNode.java`)

* The `org.nodel.jyhost.PyNode` Java class is the central component representing a single running Nodel node instance.
* **Interpreter Management:** Each `PyNode` instance creates and manages a dedicated `org.python.util.PythonInterpreter` instance from the Jython library. This ensures namespace isolation between different nodes.
* **Initialization:** When a `PyNode` starts, it performs several setup steps for its interpreter:
    * Creates a custom `PySystemState` to configure the Python environment (e.g., setting `sys.path` to include the node's script directory).
    * Sets the Python interpreter's current working directory.
    * Loads and executes the node's specific Python script(s) (`script.py`, etc.).

## 3. Exposing Java Services to Python (`ManagedToolkit.java` & `nodetoolkit.py`)

* **Java Service Facade (`ManagedToolkit.java`):** The `org.nodel.toolkit.ManagedToolkit` class (in `nodel-framework`) acts as a comprehensive Java facade, providing essential functionalities accessible from Python scripts. Examples include:
    * Network connections (TCP, UDP, SSH)
    * OS process management
    * Task scheduling (`call_delayed`, timers)
    * Node action/event management helpers
    * JSON encoding/decoding
    * Timing utilities
    * HTTP client access
* **Injection:** The Java host (`PyNode`) creates an instance of `ManagedToolkit`, specifically configured for the node it represents.
* **Python Wrapper (`nodetoolkit.py`):** This vital Python script (located in resources and executed during node initialization) serves as the Pythonic API layer over the Java services:
    * The Java `ManagedToolkit` instance is injected into the Python interpreter's namespace, making it accessible within `nodetoolkit.py` (often via `from sys import nodetoolkit`).
    * `nodetoolkit.py` defines user-friendly Python functions and decorators (e.g., `TCP()`, `UDP()`, `Timer()`, `call()`, `@local_action`, `@remote_event`, `@at_cleanup`) that wrap the corresponding methods on the underlying Java `ManagedToolkit` object.
    * User-written node scripts simply use these functions and decorators from `nodetoolkit.py`, abstracting away the direct Java interaction.

## 4. Linking Python Logic to Nodel Actions/Events (`BindingsExtractor.java`)

* **Discovery Mechanism:** After a node's Python script is loaded and executed, the Java host needs to identify which Python functions or variables correspond to Nodel's core communication concepts (actions, events, parameters).
* **`BindingsExtractor.java`:** This Java utility class is responsible for this discovery process. It inspects the namespace (globals/locals dictionary) of the node's initialized `PythonInterpreter`.
* **Naming Convention:** It specifically looks for Python functions or variables adhering to predefined naming patterns:
    * `local_action_{name}`: A Python function implementing an action provided *by* this node.
    * `local_event_{name}`: A Python object (often a `LocalEvent` instance created via the toolkit) used *by* this node to emit signals/events.
    * `remote_event_{name}`: A Python function defined to handle incoming events originating *from other* nodes.
    * `remote_action_{name}`: A Python object representing an action *on another* node that this node intends to call.
    * `param_{name}`: A configuration parameter specific to this node.
* **Registration:** `BindingsExtractor` collects metadata about these bindings (name, schema, description - often derived from Python docstrings or dictionaries assigned to the variables). `PyNode` then uses this information to register the corresponding Java objects (`NodelServerAction`, `NodelServerEvent`, `NodelClientAction`, `NodelClientEvent`) with the core Nodel framework, making them available on the network or ready to receive events.

## 5. Communication Flow

* **Java -> Python:**
    * When an external request triggers a node's local action (e.g., via the REST API), the Java host looks up the registered binding for that action name.
    * It retrieves the corresponding Python function name (`local_action_...`).
    * It uses the Jython API (e.g., `PyFunction.__call__`) to execute that Python function within the node's specific interpreter, passing arguments (converting Java objects to Python objects via `Py.java2py` if needed).
    * Similarly, incoming remote events destined for the node are dispatched by Java to the appropriate `remote_event_...` Python handler function.
* **Python -> Java:**
    * When a Python script calls a function provided by `nodetoolkit.py` (e.g., `timer = Timer(...)` or `info = lookup_local_action(...)`), the Python wrapper function executes.
    * This wrapper function calls the corresponding method on the injected Java `ManagedToolkit` instance.
    * The Java code then performs the requested operation (e.g., schedules a timer task, interacts with the Nodel framework).

## 6. Supporting Mechanisms

* **Threading & Concurrency:** Jython has known thread-safety limitations. The Nodel Java host implements safeguards:
    * Uses Java `ReentrantLock`s to serialize access to potentially non-thread-safe Jython operations (like interpreter initialization or critical script execution phases).
    * Employs a `CallbackQueue` (often managed by `ManagedToolkit`) to ensure that asynchronous callbacks originating from Java threads (e.g., timer expirations, network data arrival) are queued and executed sequentially within the correct node's Python interpreter context, preventing race conditions.
    * Utilizes a shared Java `ThreadPool` for managing background tasks efficiently.
* **HTTP Server (`NodelHostHTTPD.java`):**
    * Provides REST APIs for node management and interaction.
    * Serves a web-based user interface.
    * Supports `.pysp` (Python Server Pages) for dynamic web content generation, executing embedded Python code within the relevant node's interpreter context.
* **Lifecycle & Reloading:**
    * `PyNode` monitors the node's configuration file (`nodeConfig.json`) and associated script files for modifications.
    * If changes are detected, `PyNode` can trigger a graceful reload:
        * It runs Python functions decorated with `@at_cleanup`.
        * Shuts down the existing `PythonInterpreter`.
        * Creates a new interpreter instance.
        * Re-reads configuration and re-executes the scripts.
    * Decorators like `@before_main` and `@after_main` allow Python scripts to hook into the initialization and main execution phases.

## Summary

Nodel achieves its Java/Python integration by embedding Jython. Each node runs its Python logic in an isolated `PythonInterpreter` managed by the Java `PyNode` class. A comprehensive Java `ManagedToolkit` provides core services, exposed to Python scripts via the user-friendly `nodetoolkit.py` wrapper. A naming convention and the `BindingsExtractor` utility link Python functions/variables to Nodel's action/event system, enabling bidirectional communication. The Java host carefully manages threading and lifecycle operations to ensure stability and dynamic reloading capabilities.