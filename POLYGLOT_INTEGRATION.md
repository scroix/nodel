# Nodel: Target Architecture with GraalVM Python

This document outlines the proposed architecture for the Nodel project after migrating from Jython to GraalVM's Polyglot Engine for running Python node scripts.

## 1. Core Architecture: Java Host + GraalVM Python Nodes

* **Java Host:** Remains the foundation, handling infrastructure (networking, core lifecycle, resources) using standard Java.
* **Python Nodes ("Recipes"):** Node-specific logic continues to be written in Python (now **Python 3**). These scripts will be executed by GraalVM's Python language runtime, managed via the Polyglot Engine.
* **Polyglot Engine:** GraalVM's Polyglot Engine becomes the bridge, replacing Jython. It allows embedding and interacting with Python code from Java in a standardized way.

## 2. Embedding GraalVM Python (`PyNode.java` Adaptation)

* **`Context` replaces `PythonInterpreter`:** The core change in `PyNode.java` will be replacing the Jython `PythonInterpreter` with `org.graalvm.polyglot.Context`. Each `PyNode` instance will manage its own isolated `Context` for running its specific Python script(s).
* **Context Configuration:** Creating a `Context` requires careful configuration using `Context.newBuilder("python")`:
    * **Host Access:** Explicit configuration (`HostAccess.EXPLICIT` or custom policies) will be needed to define precisely which Java classes/methods from the host (`ManagedToolkit`, etc.) are allowed to be accessed or called by the Python code.
    * **Python Options:** Setting Python-specific options like the Python path (`python.PythonPath`), virtual environment paths, and potentially filesystem access (`allowIO`).
    * **Threading:** Deciding whether to allow multi-threaded access (`allowMultithreading(true)`), although a single Context cannot execute Python code concurrently on multiple threads.
* **Script Execution:** Instead of `interpreter.execfile()`, `PyNode` will use `context.eval(Source.newBuilder(...).build())` to load and execute the node's Python scripts (`script.py`, etc.).

## 3. Exposing Java Services to Python (`ManagedToolkit` & `nodetoolkit.py` Adaptation)

* **Java Service Facade (`ManagedToolkit.java`):**
    * This class remains the provider of core Java services.
    * **Crucially:** Methods intended to be called from Python *must* be explicitly exposed to the GraalVM Polyglot Engine, typically by annotating them with `@HostAccess.Export` (when using `HostAccess.EXPLICIT`).
* **Injecting Java Objects into Context:** `PyNode` will inject the configured `ManagedToolkit` instance into the Python `Context`'s bindings, making it accessible to the Python code.
    ```java
    // Inside PyNode, after creating the toolkit instance
    context.getPolyglotBindings().putMember("java_managed_toolkit_instance", toolkit);
    ```
* **Python Wrapper (`nodetoolkit.py`):**
    * This script still acts as the Pythonic API layer.
    * It will need to import the injected Java object using GraalVM's mechanism:
        ```python
        # Inside nodetoolkit.py
        from polyglot import import_value
        nodetoolkit = import_value('java_managed_toolkit_instance')

        # Example wrapper function
        def Timer(func, intervalInSeconds, firstDelayInSeconds=0, stopped=False):
            # Calls the EXPOSED Java method via GraalVM interop
            return nodetoolkit.createTimer(func, int(firstDelayInSeconds * 1000),
                                         int(intervalInSeconds * 1000), stopped)
        ```
    * The wrapper functions will now rely entirely on GraalVM's cross-language calling mechanism.
    * **Requires Python 3 syntax.**

## 4. Linking Python Logic to Nodel Actions/Events (`BindingsExtractor` Rewrite)

* **Polyglot API Introspection:** `BindingsExtractor` needs a significant rewrite. It can no longer directly introspect Jython objects. The new process will involve:
    1.  Ensuring the node's script has been executed within the `Context`.
    2.  Getting access to the Python script's global scope: `Value bindings = context.getBindings("python");`
    3.  Iterating through the members of the `bindings` `Value` object: `bindings.getMemberKeys()`.
    4.  For each member key (variable/function name):
        * Check if the name matches the Nodel conventions (`local_action_`, `remote_event_`, etc.).
        * Get the corresponding `Value`: `Value memberValue = bindings.getMember(key);`
        * Determine its nature: Is it executable (`memberValue.canExecute()`)? Is it readable?
        * Extract metadata: This might involve calling helper functions within the Python script via `context.eval()` or accessing properties/docstrings if the `Value` API allows (e.g., `memberValue.getMember("__doc__").asString()`).
* **Registration:** The extracted binding information (name, type, metadata, and potentially the `Value` object representing the function) will be used by `PyNode` to register with the core Nodel framework, similar to before, but now storing references compatible with GraalVM execution.

## 5. Interaction Mechanisms via Polyglot API

* **Java -> Python Calls:**
    * To execute a Python function (e.g., `local_action_turn_on`), `PyNode` will:
        1.  Retrieve the function `Value` from the context bindings: `Value functionValue = context.getBindings("python").getMember("local_action_turn_on");`
        2.  Check `functionValue.canExecute()`.
        3.  Execute it, passing arguments: `Value result = functionValue.execute(arg1, arg2);` (Java arguments are automatically converted where possible).
        4.  Convert the result `Value` back to the expected Java type: `String status = result.as(String.class);`
* **Python -> Java Calls:**
    * When Python code calls a method on the imported `nodetoolkit` object (which is the Java `ManagedToolkit` instance):
        1.  GraalVM's Polyglot Engine intercepts the call.
        2.  It checks if the method is accessible based on the `HostAccess` configuration (e.g., presence of `@HostAccess.Export`).
        3.  It converts Python arguments to Java types.
        4.  It invokes the Java method.
        5.  It converts the Java return value back into a GraalVM `Value` for the Python side.

## 6. Threading Model Considerations

* GraalVM `Context` objects have specific threading rules. If configured with `allowMultithreading(true)`, multiple Java threads can *access* the context (e.g., call Python functions), but only one thread can be *executing code inside* the context at any given time.
* Explicit context management (`context.enter()` / `context.leave()`) might be required if Java threads need to interact with the same context over extended periods or across different operations.
* The need for the original `ReentrantLock` might diminish for protecting Jython internals, but locking might still be required for managing concurrent access to shared *Java* resources or ensuring serialized execution of specific Python logic if the `CallbackQueue` pattern is maintained for business logic reasons.

## 7. Python Environment

* The system will target **Python 3.x**. Existing node scripts written for Python 2 (common with older Jython versions) will need to be updated.
* Dependency management might leverage GraalVM's Python environment tools (e.g., `graalpy -m venv ...`).

## Summary

Migrating Nodel's Python integration to GraalVM involves replacing Jython's `PythonInterpreter` with GraalVM's `Context` and rewriting all Java-Python interaction points to use the GraalVM Polyglot API (`Value`, `HostAccess`, `Context` methods). Key tasks include configuring the `Context` correctly, adapting `BindingsExtractor` to use `Value` introspection, ensuring Java methods are properly exposed (`@HostAccess.Export`), updating `nodetoolkit.py` to use `polyglot.import_value` and Python 3 syntax, and reviewing the threading model based on `Context` rules. This provides a more standardized, modern, and potentially performant way to integrate Python scripting.