package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import org.joda.time.DateTime;
import org.nodel.DateTimes;
import org.nodel.Exceptions;
import org.nodel.Handler;
import org.nodel.Handler.H0;
import org.nodel.Handler.H1;
import org.nodel.Handler.H2;
import org.nodel.SimpleName;
import org.nodel.Strings;
import org.nodel.Threads;
import org.nodel.core.ActionRequestHandler;
import org.nodel.core.BindingState;
import org.nodel.core.Nodel;
import org.nodel.core.NodelClientAction;
import org.nodel.core.NodelClientEvent;
import org.nodel.core.NodelEventHandler;
import org.nodel.core.NodelServerAction;
import org.nodel.core.NodelServerEvent;
import org.nodel.host.*;
import org.nodel.io.Files;
import org.nodel.io.Stream;
import org.nodel.reflection.Param;
import org.nodel.reflection.Schema;
import org.nodel.reflection.Serialisation;
import org.nodel.reflection.Service;
import org.nodel.reflection.Value;
import org.nodel.threading.CallbackQueue;
import org.nodel.threading.TimerTask;
import org.nodel.toolkit.Console;
import org.nodel.toolkit.ManagedToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.python.embedding.GraalPyResources;

/**
 * Represents a Python-enabled Node under GraalVM (replacing Jython).
 */
public class PyNode extends BaseDynamicNode {

    private static Logger _logger = LoggerFactory.getLogger(PyNode.class);

    /**
     * Language ID for GraalVM Python.
     */
    private static final String PYTHON_LANGUAGE_ID = "python";

    /**
     * The shared GraalVM Python context.
     */
    private Context _pythonContext; // Make non-final again

    /**
     * Lock to help avoid overlapping operations, especially around Context access.
     * GraalVM Contexts have their own threading rules, but this adds an outer layer.
     */
    private ReentrantLock _busy = new ReentrantLock();

    /**
     * When permanently closed (disposed).
     */
    private boolean _closed;

    /**
     * The main Python script file.
     */
    private File _scriptFile;

    /**
     * Used to detect changes in the config/script/dependencies.
     */
    private long _fileModifiedHash;

    /**
     * Stores references to Python functions (local actions, remote event handlers).
     */
    private Map<String, org.graalvm.polyglot.Value> _pythonFunctions = new HashMap<>();

    /**
     * Track stuck function calls.
     */
    private AtomicLong _funcSeqNumber = new AtomicLong();

    /**
     * Our general-purpose toolkit (injected into the Python context).
     */
    protected ManagedToolkit _toolkit;

    /**
     * The NodelHost (owning host).
     */
    private NodelHost _nodelHost;
    public NodelHost getNodelHost() {
        return _nodelHost;
    }

    /**
     * For standard output from the Python context.
     */
    protected LineReader _outReader;

    /**
     * For error output from the Python context.
     */
    protected LineReader _errReader;

    /**
     * A callback queue for orderly, predictable handling of callbacks.
     */
    private CallbackQueue _callbackQueue = new CallbackQueue();

    /**
     * The node configuration object.
     */
    private NodeConfig _nodeConfig;
    
    /**
     * The config file that contains the node configuration.
     */
    private File _configFile;

    public PyNode(NodelHost nodelHost, SimpleName name, File root) throws IOException { // Remove Context parameter
        super(name, root);
        _nodelHost = nodelHost;
        
        createContext(); // Create the context internally
        
        // init() is called by BaseDynamicNode constructor via checkInit
    }

    /**
     * Script info container.
     */
    public static class ScriptInfo {
        @Value(name="modified")
        public DateTime modified;

        @Value(name="script")
        public String script;
    }

    /**
     * Script endpoints for the web UI or REST.
     */
    public class Script {
        @Value(name="scriptInfo", treatAsDefaultValue=true)
        public ScriptInfo getScriptInfo() throws IOException {
            ScriptInfo info = new ScriptInfo();
            info.modified = new DateTime(_scriptFile.lastModified());
            info.script = readFullyFromStream(new FileInputStream(_scriptFile));
            return info;
        }

        @Service(name="raw")
        public String getRawScript() throws IOException {
            return readFullyFromStream(new FileInputStream(_scriptFile));
        }

        @Service(name="save")
        public void save(@Param(name="script") String script) throws IOException {
            final String scriptFilePrefix = "script_backup_";

            // Rolling backup
            File[] backups = _root.listFiles(new FileFilter() {
                @Override
                public boolean accept(File f) {
                    String lc = f.getName().toLowerCase();
                    return lc.startsWith(scriptFilePrefix) && lc.endsWith(".py");
                }
            });
            if (backups != null && backups.length > 0) {
                Arrays.sort(backups, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return Long.compare(f1.lastModified(), f2.lastModified());
                    }
                });
                // keep at most 5
                if (backups.length > 5)
                    backups[0].delete();
            }

            // make a backup
            String timestamp = DateTime.now().toString("YYYY-MM-dd_HHmmssSSS");
            Files.copy(_scriptFile, new File(_root, scriptFilePrefix + timestamp + ".py"));

            // write new content
            Stream.writeFully(_scriptFile, script);
        }
    }

    private Script _script = new Script();

    public boolean requiresInit() {
        return true;
    }
    
    public void init() throws Exception {
        // Called by BaseDynamicNode when first needed or after reload
        _busy.lock();
        try {
            if (_closed) return; // Do not re-init if permanently closed

            _outReader.inject("Initialising Python node...");
            _logger.info("Initialising Python node...");

            // Locate script file
            _scriptFile = new File(_root, "script.py");
            if (!_scriptFile.exists()) {
                // Create default script file if it doesn't exist
                Stream.writeFully(_scriptFile, "# Default Python script\n\ndef main():\n    print('Hello from Python')\n");
            }
            
            // Clear existing Python functions
            _pythonFunctions.clear();

            // Initialise the toolkit for this node
            _callbackQueue = new CallbackQueue();
            _toolkit = new ManagedToolkit(this);
            
            try {
                // First, clean up any previous bindings 
                cleanupBindings();
                
                // Inject the toolkit into the Python context
                _pythonContext.getPolyglotBindings().putMember("_toolkit", _toolkit);
                _pythonContext.getBindings(PYTHON_LANGUAGE_ID).putMember("_toolkit", _toolkit);

                // Inject startup message into the web console
                String msg = "Initialising new Python interpreter...";
                _outReader.inject(msg);
                _logger.info(msg);

                // Set up the Python environment (e.g., execute bootstrap code)
                if (_scriptFile.exists()) {
                    executeFileScript(_scriptFile);
                }

                // Extract bindings from Python
                List<String> warnings = new ArrayList<>();
                // TODO: Implement proper BindingsExtractor for GraalVM Python
                Bindings bindings = Bindings.Empty; // Temporary placeholder
                setBindings(bindings);
                for (String warning : warnings) {
                    _logger.warn(warning);
                }

                // Execute any 'after_main' functions
                // TODO: Call Python main function or equivalent

                // Store file modification hash for reload detection
                _fileModifiedHash = calculateFileModifiedHash();

                _logger.info("Python node initialised.");
                _outReader.inject("Python node initialised.");
                
                // Mark as successfully initialized by setting the description
                synchronized (_signal) {
                    _started = DateTime.now();
                    _desc = "GraalVM Python Node";
                    _signal.notifyAll();
                }
                
            } catch (Exception e) {
                _logger.error("Failed to initialise Python node: " + e.toString());
                _outReader.inject("Failed to initialise Python node: " + e.toString());
                handleException("Initialisation", e);
                markFailed(e); // Mark node as failed
                throw e; // Re-throw to signal failure
            } finally {
                _busy.unlock();
            }
        } catch (Exception e) {
            _logger.error("Failed to initialise Python node: " + e.toString());
            _outReader.inject("Failed to initialise Python node: " + e.toString());
            handleException("Initialisation", e);
            markFailed(e); // Mark node as failed
            throw e; // Re-throw to signal failure
        }
    }

    /**
     * Mark the node as failed with the given exception.
     */
    private void markFailed(Exception e) {
        // Implementation depends on BaseDynamicNode's API
        // If there's a setFailed method in the parent class, you'd call it here
        // Otherwise implement necessary failure handling logic
        _logger.error("Node marked as failed: " + e.getMessage());
    }

    /**
     * Creates and configures the GraalVM context.
     */
    private Context createContext() {
        // Ensure LineReaders are initialized first
        _outReader = new LineReader();
        _errReader = new LineReader();

        // IMPORTANT: Set handlers IMMEDIATELY after creation
        _outReader.setHandler(new Handler.H1<String>() {
            @Override
            public void handle(String line) {
                log(line);
            }
        });
        _errReader.setHandler(new Handler.H1<String>() {
            @Override
            public void handle(String line) {
                logError(line);
            }
        });

        // Create separate streams for stdout and stderr using proper line buffering
        OutputStream stdoutStream = makeStream(_outReader);
        OutputStream stderrStream = makeStream(_errReader);

        _logger.info("Creating GraalVM Python context for node '{}'", getName());

        try {
            _pythonContext = GraalPyResources.contextBuilder()
                .allowAllAccess(true)
                .out(stdoutStream)
                .err(stderrStream)
                .build();
            return _pythonContext;
        } catch (Exception e) {
            _logger.error("Failed to create GraalVM Python context", e);
            throw new RuntimeException("Context creation failed", e);
        }
    }

    /**
     * Creates a line-buffered OutputStream that injects complete lines into the given LineReader.
     */
    private OutputStream makeStream(LineReader lr) {
        return new OutputStream() {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            @Override public synchronized void write(int b) throws IOException {
                if (b == '\n') {
                    lr.inject(buf.toString(StandardCharsets.UTF_8));
                    buf.reset();
                } else {
                    buf.write(b);
                }
            }
            @Override public void flush() throws IOException {
                if (buf.size() > 0) {
                    lr.inject(buf.toString(StandardCharsets.UTF_8));
                    buf.reset();
                }
            }
        };
    }

    /**
     * Executes a Python script from a file.
     */
    private void executeFileScript(File scriptFile) throws IOException {
        _logger.info("Executing script: " + scriptFile.getName());
        Source source = Source.newBuilder(PYTHON_LANGUAGE_ID, scriptFile).build();
        try {
            _pythonContext.eval(source);
        } catch (PolyglotException e) {
            handlePolyglotException("Executing file script " + scriptFile.getName(), e);
            throw e;
        }
    }

    /**
     * Executes a Python script from classpath resources.
     */
    private void executeResourceScript(String resourcePath, String scriptName) throws IOException {
        _logger.info("Executing resource script: " + scriptName);
        InputStream is = PyNode.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new FileNotFoundException("Resource not found: " + resourcePath);
        }
        String scriptContent = readFullyFromStream(is);
        Source source = Source.newBuilder(PYTHON_LANGUAGE_ID, scriptContent, scriptName).build();
        try {
            _pythonContext.eval(source);
        } catch (PolyglotException e) {
            handlePolyglotException("Executing resource script " + scriptName, e);
            throw e;
        }
    }

    /**
     * Calls a Python function within the context.
     * Assumes function takes no arguments and checks for existence.
     * Returns the result as a Value or null if function doesn't exist or fails.
     */
    private org.graalvm.polyglot.Value executePythonFunction(String functionName) {
        try {
            org.graalvm.polyglot.Value func = _pythonContext.getBindings(PYTHON_LANGUAGE_ID).getMember(functionName);
            if (func != null && func.canExecute()) {
                return func.execute();
            } else {
                // Function doesn't exist, might be okay (e.g., optional hooks)
                // _logger.log("Python function '" + functionName + "' not found or not executable.");
                return null;
            }
        } catch (PolyglotException e) {
            handlePolyglotException("Executing function " + functionName, e);
            return null; // Indicate failure
        } catch (Exception e) {
            handleException("Executing function " + functionName, e);
            return null;
        }
    }

    /**
     * Handles a PolyglotException, logging it appropriately.
     */
    private void handlePolyglotException(String context, PolyglotException e) {
        _logger.error(String.format("Error %s: %s", context, e.getMessage()));
        if (e.isGuestException()) {
            // Log guest language stack trace if available, with more formatting
            StringBuilder stackTrace = new StringBuilder();
            stackTrace.append("\n--- Python stack trace (guest) ---\n");
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                stackTrace.append("  at ").append(frame.toString()).append("\n");
            }
            stackTrace.append("--- End Python stack trace ---");
            _logger.error(stackTrace.toString());
            // Also inject to web console for visibility
            _outReader.inject(stackTrace.toString());
        }
        // Also log the Java stack trace for context
        _logger.error("PolyglotException during '{}'", context, e);
    }

    /**
     * Handles general exceptions.
     */
    private void handleException(String context, Exception e) {
        String errorMsg = getStackTraceAsString(e);
        String formatted = String.format("--- Java exception during %s ---\n%s--- End Java exception ---", context, errorMsg);
        _logger.error(formatted);
        _outReader.inject(formatted);
        _logger.error("Exception during '{}'", context, e);
    }

    /**
     * Utility method to get stack trace as string since Exceptions.stackTraceToString is not available.
     */
    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * Prepares the thread state before executing guest code (Python).
     */
    private void threadStateHandler() {
        // In GraalVM, context.enter() and context.leave() manage thread association.
        // This is often handled implicitly by execute() or eval(), but explicit
        // management might be needed for complex async callbacks.
        // For now, we assume implicit handling is sufficient.
        // If explicit control is needed:
        // _pythonContext.enter();
        // try { ... guest code ... } finally { _pythonContext.leave(); }
    }


    protected void reset() {
        // No Python-specific interpreter reset needed like with Jython.
        // Context recreation handles reset.
    }

    protected void enable() {
        // ManagedToolkit handles enabling its components (timers start, etc.)
        if (_toolkit != null) {
           // Toolkit enabling logic might be needed here if not automatic
           // _toolkit.enable(); // If such a method exists
        }
    }

    protected void disable() {
        // ManagedToolkit handles disabling its components (timers stop, etc.)
         if (_toolkit != null) {
           // Toolkit disabling logic might be needed here if not automatic
           // _toolkit.disable(); // If such a method exists
        }
    }

    protected void checkReload() {
        if (_closed || !_scriptFile.exists()) {
            return;
        }

        long currentHash = calculateFileModifiedHash();

        if (currentHash != _fileModifiedHash) {
            _logger.info("Change detected, reloading Python node...");
            
            // BaseDynamicNode handles the reload process (destroy, init)
            try {
                destroy(); // First clean up resources
                init();    // Then re-initialize
            } catch (Exception e) {
                _logger.error("Failed to reload node: " + e.getMessage());
            }
        }
    }

    protected void setBindings(Bindings bindings) {
        _busy.lock();
        try {
            // Apply the bindings to the node
            applyBindings(bindings);
            
            // After setting bindings, cache references to the Python functions
            // needed for actions and remote event handlers.
            _pythonFunctions.clear();

            if (bindings != null && bindings.local != null) {
                 // Cache Local Action functions
                for (Entry<SimpleName, Binding> entry : bindings.local.actions.entrySet()) {
                    // Get the function name - using a safe approach since definition might not exist
                    String functionName = "local_action_" + entry.getKey().toString();
                    cachePythonFunction(functionName);
                }
            }
            if (bindings != null && bindings.remote != null) {
                 // Cache Remote Event handler functions
                 for (Entry<SimpleName, NodelEventInfo> entry : bindings.remote.events.entrySet()) {
                    // The binding definition (function name) is stored within NodelEventInfo if using BindingsExtractor correctly
                    if (entry.getValue() instanceof PyBindingInfo) {
                        cachePythonFunction(((PyBindingInfo)entry.getValue()).getFunctionName());
                    }
                 }
            }
        } finally {
            _busy.unlock();
        }
    }
    
    /**
     * Applies the bindings to the node.
     */
    private void applyBindings(Bindings bindings) {
        if (bindings == null) {
            _logger.info("No bindings were specified.");
            return;
        }
        
        // Here we would register local and remote bindings, events, etc.
        // For now, this is a placeholder
        _logger.info("Applied {} bindings", 
            (bindings.local != null ? 
                (bindings.local.actions != null ? bindings.local.actions.size() : 0) +
                (bindings.local.events != null ? bindings.local.events.size() : 0) : 0) +
            (bindings.remote != null ? 
                (bindings.remote.actions != null ? bindings.remote.actions.size() : 0) +
                (bindings.remote.events != null ? bindings.remote.events.size() : 0) : 0)
        );
    }

    /**
     * Gets the Python function Value from the context and caches it.
     */
    private void cachePythonFunction(String functionName) {
        if (_pythonContext == null || functionName == null || functionName.isEmpty()) {
            return;
        }
        try {
            _pythonContext.enter();
            org.graalvm.polyglot.Value func = _pythonContext.getBindings(PYTHON_LANGUAGE_ID).getMember(functionName);
            if (func != null && func.canExecute()) {
                _pythonFunctions.put(functionName, func);
            } else {
                // Function doesn't exist, might be okay (e.g., optional hooks)
                _logger.warn("Could not find or cache executable Python function: " + functionName);
            }
        } catch (Exception e) {
             handleException("Caching function " + functionName, e);
        } finally {
            try { _pythonContext.leave(); } catch(Exception le) { /* ignore */ }
        }
    }

    // --- Action / Event Handling --- Override methods from BaseDynamicNode ---

    public void handleActionRequest(SimpleName actionName, Object arg, final ActionRequestHandler handler) {
        if (_closed || _pythonContext == null) {
             handler.handleActionRequest(new RuntimeException("Node is closed or not initialised"));
             return;
        }

        String functionName = "local_action_" + actionName.toString();
        final org.graalvm.polyglot.Value pyFunc = _pythonFunctions.get(functionName);

        if (pyFunc == null || !pyFunc.canExecute()) {
            _logger.warn("Local action function '" + functionName + "' not found or not executable.");
            handler.handleActionRequest(new RuntimeException("Action '" + actionName + "' not implemented"));
            return;
        }

        long seq = _funcSeqNumber.getAndIncrement();
        String funcKey = "action:" + functionName;

        // Use the callback queue to ensure execution within the node's context/thread
        final H0 callbackRunnable = new H0() {
            @Override
            public void handle() {
                long startTime = System.currentTimeMillis();
                _logger.info("Executing local action: " + functionName);

                try {
                    _pythonContext.enter(); // Ensure thread is attached
                    // Execute the Python function with the argument
                    if (arg != null) {
                        pyFunc.execute(arg);
                    } else {
                        pyFunc.execute();
                    }
                    handler.handleActionRequest(null); // Signal success
                } catch (Exception e) {
                    String errMsg = "Error executing action '" + actionName + "': " + e.getMessage();
                    _logger.error(errMsg);
                    handler.handleActionRequest(new RuntimeException(errMsg, e));
                } finally {
                    try { _pythonContext.leave(); } catch (Exception le) { /* ignore */ }
                    _logger.info("Action execution completed in " + (System.currentTimeMillis() - startTime) + "ms.");
                }
            }
        };

        // Execute the callback handling errors
        _callbackQueue.handle(callbackRunnable, new H1<Exception>() {
            @Override
            public void handle(Exception error) {
                if (error != null) {
                    handler.handleActionRequest(error);
                }
            }
        });
    }

    public void handleEvent(SimpleName eventName, Object arg) {
        if (_closed || _pythonContext == null)
            return;

        // For normal Python events triggered from remote events, we need to find the handler
        // This will be retrieved from the PyBindingInfo if available
        String remoteFunctionName = "remote_event_" + eventName.toString(); // Default naming convention
        
        // More sophisticated lookup might be needed here based on your binding structure

        final org.graalvm.polyglot.Value pyFunc = _pythonFunctions.get(remoteFunctionName);
        if (pyFunc == null || !pyFunc.canExecute()) {
            _logger.warn("Remote event handler function '" + remoteFunctionName + "' not found or not executable.");
            return;
        }

        final String functionName = remoteFunctionName;
        
        // Use the callback queue to ensure execution within the node's context/thread
        final H0 callbackRunnable = new H0() {
            @Override
            public void handle() {
                long startTime = System.currentTimeMillis();
                _logger.info("Handling remote event using: " + functionName);

                try {
                    _pythonContext.enter();
                    if (arg != null) {
                        pyFunc.execute(arg);
                    } else {
                        pyFunc.execute();
                    }
                } catch (Exception e) {
                    String errMsg = "Error handling event using '" + functionName + "': " + e.getMessage();
                    _logger.error(errMsg);
                    injectError(errMsg, new RuntimeException(errMsg, e));
                } finally {
                    try { _pythonContext.leave(); } catch (Exception le) { /* ignore */ }
                    _logger.info("Event handling completed in " + (System.currentTimeMillis() - startTime) + "ms.");
                }
            }
        };

        // Execute the callback handling errors
        _callbackQueue.handle(callbackRunnable, new H1<Exception>() {
            @Override
            public void handle(Exception error) {
                if (error != null) {
                    String errMsg = "Error in callback queue: " + error.getMessage();
                    _logger.error(errMsg);
                    injectError(errMsg, new RuntimeException(errMsg, error));
                }
            }
        });
    }

    // --- Getters for Web UI / REST API --- //

    /**
     * Returns the list of Python functions detected/cached.
     */
    @Service(name="pythonFunctions")
    public List<String> getPythonFunctions() {
        _busy.lock();
        try {
            return new ArrayList<>(_pythonFunctions.keySet());
        } finally {
            _busy.unlock();
        }
    }

    /**
     * Provides access to the script endpoints.
     */
    @Service(name="script")
    public Script getScriptService() {
        return _script;
    }

    /**
     * Provides access to the raw toolkit object (for debugging/introspection).
     * Be cautious exposing this directly.
     */
    @Service(name="toolkit")
    public ManagedToolkit getToolkit() {
        return _toolkit;
    }

    /**
     * Returns the Python context for this node.
     * Used for server-side Python scripting (PySp).
     */
    public Context getPythonContext() {
        return _pythonContext;
    }
    
    /**
     * Injects an error into the node's console.
     * 
     * @param message The error message
     * @param exception The exception that caused the error
     */
    public void injectError(String message, RuntimeException exception) {
        _logger.error(message + ": " + exception.getMessage());
    }
    
    /**
     * Evaluates a Python expression within the node's context.
     * Used by REST API and other clients.
     * 
     * @param expr The Python expression to evaluate
     * @param source A description of the source of the request (for logging)
     * @return The result of the evaluation
     */
    @Service(name="eval", title="Evaluate", desc="Evaluates a Python expression.")
    public Object eval(@Param(name="expr", title="Expression", desc="A Python expression.") final String expr, 
                      String source) throws Exception {
        if (_closed || _pythonContext == null)
            throw new RuntimeException("The interpreter is not initialized or has been closed.");
        
        // For tracking function calls
        final String functionKey = "eval" + (!Strings.isBlank(source) ? "_" + source : "") + "_" + _funcSeqNumber.getAndIncrement();
        
        try {
            _busy.lock();
            _logger.info("Evaluating expression: " + expr);
            
            try {
                _pythonContext.enter();
                
                // Evaluate the Python expression and return the result
                Source source1 = Source.newBuilder(PYTHON_LANGUAGE_ID, expr, "eval").buildLiteral(); // Try as expression
                org.graalvm.polyglot.Value result = _pythonContext.eval(source1);
                
                // Convert the result to Java if possible
                if (result.isString()) {
                    return result.asString();
                } else if (result.isNumber()) {
                    return result.as(Number.class);
                } else if (result.isBoolean()) {
                    return result.asBoolean();
                } else if (result.isNull()) {
                    return null;
                } else {
                    // Return the raw Value object
                    return result;
                }
            } catch (PolyglotException e) {
                handlePolyglotException("Evaluating '" + expr + "'", e);
                throw new RuntimeException("Evaluation failed: " + e.getMessage(), e);
            } finally {
                try { _pythonContext.leave(); } catch (Exception e) { /* ignore */ }
            }
        } finally {
            _busy.unlock();
        }
    }
    
    /**
     * Executes Python code within the node's context.
     * 
     * @param code The Python code to execute
     * @param source A description of the source of the request (for logging)
     */
    @Service(name="exec", title="Execute", desc="Execute Python code fragment.")
    public void exec(@Param(name="code", title="Code", desc="A Python code fragment.") final String code, 
                    String source) throws Exception {
        if (_closed || _pythonContext == null)
            throw new RuntimeException("The interpreter is not initialized or has been closed.");
        
        if (code == null || code.trim().isEmpty()) {
            _logger.debug("Empty code fragment received in exec.");
            return; // Nothing to execute
        }
        
        final String functionKey = "exec" + (!Strings.isBlank(source) ? "_" + source : "") + "_" + _funcSeqNumber.getAndIncrement();
        
        try {
            _busy.lock();
            _logger.info("Executing code fragment" + (source != null ? " from " + source : ""));
            
            try {
                _pythonContext.enter();
                
                // REPL-like behavior: Try evaluating as an expression first.
                boolean evaluated = false;
                try {
                    _logger.debug("Trying to evaluate as expression (literal): [{}]", code);
                    Source exprSource = Source.newBuilder(PYTHON_LANGUAGE_ID, code, source).buildLiteral(); // Try as expression
                    org.graalvm.polyglot.Value result = _pythonContext.eval(exprSource);
                    _logger.debug("Evaluation as expression succeeded.");

                    // If eval succeeded and result is not null/void, print it.
                    if (result != null && !result.isNull()) {
                        // Filter out module objects using GraalVM metadata
                        org.graalvm.polyglot.Value meta = result.getMetaObject();
                        if (meta != null && "module".equals(meta.getMetaSimpleName())) {
                            // Skip printing module objects
                        } else {
                            // Convert result to string safely
                            String resultStr;
                            try {
                                if (result.isString()) {
                                    resultStr = result.asString();
                                } else if (result.isHostObject()) {
                                    resultStr = result.asHostObject().toString();
                                } else if (result.isNumber()) {
                                    resultStr = result.as(Number.class).toString();
                                } else if (result.isBoolean()) {
                                    resultStr = result.asBoolean() ? "true" : "false";
                                } else {
                                    resultStr = result.toString(); // Fallback
                                }
                            } catch (Exception e) {
                                resultStr = "<Error converting result to string: " + e.getMessage() + ">";
                            }

                            // Write to the node's output stream (which goes to LineReader -> log)
                            _outReader.write(resultStr + "\n"); 
                            _outReader.flush(); // Ensure output is visible
                        }
                    }
                    evaluated = true; // Mark as successfully evaluated

                } catch (PolyglotException pe) {
                    // Check if it's a SyntaxError suggesting it's not an expression
                    if (pe.isSyntaxError()) { 
                        _logger.debug("Eval as expression failed (SyntaxError), will try as statement: {}", code);
                        // It failed as an expression, so let's proceed to execute it as a statement block.
                        evaluated = false; 
                    } else {
                        // Different error during evaluation, rethrow it.
                        _logger.error("Eval as expression failed (Non-SyntaxError): {}", pe.getMessage());
                        handlePolyglotException("Evaluating expression in exec", pe);
                        throw new RuntimeException("Evaluation failed: " + pe.getMessage(), pe); 
                    }
                }

                // If it wasn't successfully evaluated as an expression, execute it as a statement block.
                if (!evaluated) {
                    _logger.debug("Executing code fragment as statement: {}", code);
                    // Use the original source name for statement execution
                    _pythonContext.eval(Source.newBuilder(PYTHON_LANGUAGE_ID, code, source).build()); // Execute as statement
                    _logger.debug("Execution as statement finished.");
                }
 
            } catch (PolyglotException e) {
                handlePolyglotException("Executing code fragment", e);
                throw new RuntimeException("Execution failed: " + e.getMessage(), e);
            } finally {
                try { _pythonContext.leave(); } catch (Exception e) { /* ignore */ }
            }
        } finally {
            _busy.unlock();
        }
    }
    
    /**
     * Tracks function execution for detecting stuck callbacks.
     */
    private void trackFunction(String functionName) {
        // In the GraalVM implementation, we can track function execution differently
        // This is a placeholder that could be expanded with more sophisticated tracking
        _logger.debug("Function execution started: {}", functionName);
    }
    
    /**
     * Removes function from tracking.
     */
    private void untrackFunction(String functionName) {
        // Corresponding function to end tracking
        _logger.debug("Function execution completed: {}", functionName);
    }
    
    /**
     * Creates a custom lock for thread synchronization.
     * In the original Jython implementation, this worked around Jython threading issues.
     * In the GraalVM implementation, this may not be needed but is kept for API compatibility.
     */
    private ReentrantLock getAReentrantLock() {
        // In GraalVM, we don't need the complex locking mechanism used with Jython
        // We can just use our instance lock
        return _busy;
    }

    /**
     * Destroys this node, releasing all resources.
     */
    private void destroy() {
        _busy.lock();
        try {
            if (_closed) return;
            _closed = true; // Mark as permanently closed

            _logger.info("Destroying Python node...");

            // Run Python cleanup functions first (if context is still valid)
            if (_pythonContext != null) {
                try {
                    _pythonContext.enter();
                    executePythonFunction("process_cleanup_functions");
                } catch (Exception e) {
                    handleException("Cleanup function execution", e);
                } finally {
                    try { _pythonContext.leave(); } catch (Exception le) { /* ignore */ }
                }
            }

            // Close the toolkit (stops timers, closes connections, etc.)
            if (_toolkit != null) {
                Stream.safeClose(_toolkit);
                _toolkit = null;
            }

            // Close the GraalVM context for this node
            if (_pythonContext != null) {
                try {
                    _logger.info("Closing Python context for node {}", getName());
                    _pythonContext.close(true); // true = cancel running executions
                } catch (Exception e) {
                    _logger.error("Error closing Python context for node " + getName(), e);
                } finally {
                   _pythonContext = null; // Ensure it's nullified
                }
            }

            _pythonFunctions.clear();
            reset(); // Reset state in BaseDynamicNode

            _logger.info("Python node destroyed.");

        } finally {
            _busy.unlock();
        }
    }

    /**
     * Calculates a hash based on modification times of relevant files.
     */
    private long calculateFileModifiedHash() {
        long hash = 5381;
        hash = (hash << 5) + hash + _root.lastModified();          // Root directory
        hash = (hash << 5) + hash + _scriptFile.lastModified();     // script.py
        if (_configFile != null)
            hash = (hash << 5) + hash + _configFile.lastModified(); // nodeConfig.json

        // Include any .py files in the root or lib directory
        List<File> pyFiles = findPyFiles(_root);
        File libDir = new File(_root, "lib");
        if (libDir.isDirectory()) {
             pyFiles.addAll(findPyFiles(libDir));
        }
        pyFiles.sort(Comparator.comparing(File::getAbsolutePath)); // Ensure consistent order
        for (File pyFile : pyFiles) {
            hash = (hash << 5) + hash + pyFile.lastModified();
        }

        return hash;
    }

    /**
     * Finds Python files in a directory (non-recursive).
     */
    private List<File> findPyFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".py"));
        return files != null ? Arrays.asList(files) : new ArrayList<>();
    }

    /**
     * Utility method to read fully from stream with UTF-8 encoding.
     */
    private String readFullyFromStream(InputStream is) throws IOException {
        try (java.util.Scanner scanner = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    /**
     * Logs general log.
     */
    protected void log(String line) {
        addConsoleLog(DateTime.now(), ConsoleLogEntry.Console.out, line);
    }

    /**
     * Logs an error.
     */
    protected void logError(String line) {
        addConsoleLog(DateTime.now(), ConsoleLogEntry.Console.err, line);
    }

    /**
     * Logs a warning.
     */
    protected void logWarning(String line) {
        addConsoleLog(DateTime.now(), ConsoleLogEntry.Console.warn, line);
    }

    /**
     * Logs an info event.
     */
    protected void logInfo(String line) {
        addConsoleLog(DateTime.now(), ConsoleLogEntry.Console.info, line);
    }

    /**
     * Called by NodelHost when node name registration fails.
     */
    public void handleNameRegistrationFailure(Exception exc) {
        _logger.error("Node name registration failed", exc);
        markFailed(exc);
    }
}