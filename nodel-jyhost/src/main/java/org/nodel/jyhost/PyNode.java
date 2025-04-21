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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;

import org.joda.time.DateTime;
import org.nodel.Handler;
import org.nodel.Handler.H0;
import org.nodel.Handler.H1;
import org.nodel.SimpleName;
import org.nodel.Strings;
import org.nodel.core.ActionRequestHandler;
import org.nodel.core.NodelClientAction;
import org.nodel.core.NodelClientEvent;
import org.nodel.core.NodelEventHandler;
import org.nodel.core.NodelServerAction;
import org.nodel.core.NodelServerEvent;
import org.nodel.host.*;
import org.nodel.io.Files;
import org.nodel.io.Stream;
import org.nodel.reflection.Param;
import org.nodel.reflection.Service;
import org.nodel.reflection.Value;
import org.nodel.threading.CallbackQueue;
import org.nodel.toolkit.Console;
import org.nodel.toolkit.ManagedToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

/**
 * Represents a Python-enabled Node under GraalVM (replacing Jython).
 * <p>
 * Thread safety is ensured through GraalVM's built-in Context synchronization mechanisms.
 * Each method that interacts with the Python context uses the enter/leave pattern provided by
 * GraalVM to ensure proper thread attachment and operation serialization.
 * <p>
 * This class previously used a _busy lock, but that was found to be redundant with GraalVM's
 * native thread safety and was removed to improve performance.
 */
public class PyNode extends BaseDynamicNode {

    private static Logger _logger = LoggerFactory.getLogger(PyNode.class);

    /**
     * Language ID for GraalVM Python.
     */
    private static final String PYTHON_LANGUAGE_ID = "python";

    /**
     * The shared GraalVM Python context.
     * <p>
     * This context is thread-safe by GraalVM design, which automatically serializes access
     * to the Python environment without requiring additional locks.
     */
    private Context _pythonContext;

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
     * <p>
     * This map is protected by the GraalVM Context's thread safety when being accessed during
     * Python operations, and by synchronized blocks when accessed from Java-only code.
     */
    private Map<String, org.graalvm.polyglot.Value> _pythonFunctions = new HashMap<>();

    /**
     * Stores mapping from remote event simple name to python handler function name.
     */
    private Map<SimpleName,String> _pythonEventHandlers = new HashMap<>();

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

    private static final String TOOLKIT_RESOURCE = "/org/nodel/jyhost/nodetoolkit.py";

    public PyNode(NodelHost nodelHost, SimpleName name, File root) throws IOException { 
        super(name, root);
        _nodelHost = nodelHost;
        
        createContext(); 
        try {
            init(); 
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to initialize Python node", e);
        }

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
        if (_closed) return; 

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
        _toolkit = new ManagedToolkit(this)
            .setCallbackHandler(_callbackQueue)
            .attachConsole(new Console.Interface() {
                @Override
                public void warn(Object obj) {
                    logWarning(String.valueOf(obj));
                }
                @Override
                public void log(Object obj) {
                    PyNode.this.log(String.valueOf(obj));
                }
                @Override
                public void info(Object obj) {
                    logInfo(String.valueOf(obj));
                }
                @Override
                public void error(Object obj) {
                    logError(String.valueOf(obj));
                }
            });

        try {
            // First, clean up any previous bindings 
            cleanupBindings();
                
            // Inject the toolkit into the Python context
            _pythonContext.getPolyglotBindings().putMember("_toolkit", _toolkit);
            _pythonContext.getBindings(PYTHON_LANGUAGE_ID).putMember("_toolkit", _toolkit);

            // Load and execute the toolkit bootstrap script BEFORE any user script
            loadToolkit();

            // Now run the user script (script.py)
            if (_scriptFile.exists()) {
                executeFileScript(_scriptFile);

                // Extract bindings from Python
                List<String> warnings = new ArrayList<>();
                org.graalvm.polyglot.Value pythonGlobals = _pythonContext.getBindings(PYTHON_LANGUAGE_ID);
                Bindings bindings = BindingsExtractor.extract(pythonGlobals, warnings);
                applyBindings(bindings); 
                for (String warning : warnings) {
                    _logger.warn(warning);
                }

                // Execute the Python lifecycle functions
                _pythonContext.enter();
                try {
                    // Execute any before_main hooks
                    _logger.info("Running before_main functions...");
                    org.graalvm.polyglot.Value beforeMainFunc = _pythonContext.getBindings(PYTHON_LANGUAGE_ID).getMember("process_before_main_functions");
                    if (beforeMainFunc != null && beforeMainFunc.canExecute()) {
                        beforeMainFunc.execute();
                    }
                    
                    // Execute main() if it exists
                    _logger.info("Looking for main() function...");
                    org.graalvm.polyglot.Value mainFunc = _pythonContext.getBindings(PYTHON_LANGUAGE_ID).getMember("main");
                    if (mainFunc != null && mainFunc.canExecute()) {
                        _logger.info("Executing main() function...");
                        mainFunc.execute();
                    } else {
                        _logger.info("No main() function found, skipping");
                    }
                    
                    // Execute any after_main hooks
                    _logger.info("Running after_main functions...");
                    org.graalvm.polyglot.Value afterMainFunc = _pythonContext.getBindings(PYTHON_LANGUAGE_ID).getMember("process_after_main_functions");
                    if (afterMainFunc != null && afterMainFunc.canExecute()) {
                        afterMainFunc.execute();
                    }
                } catch (PolyglotException e) {
                    handlePolyglotException("Executing lifecycle functions", e);
                } catch (Exception e) {
                    handleException("Executing lifecycle functions", e);
                } finally {
                    try { _pythonContext.leave(); } catch(Exception e) { /* ignore */ }
                }

                // Store file modification hash for reload detection
                _fileModifiedHash = calculateFileModifiedHash();

            }

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
            markFailed(e); 
            throw e; 
        }
    }

    /**
     * Loads and executes the toolkit bootstrap script (nodetoolkit.py) before user scripts.
     */
    private void loadToolkit() throws IOException {
        // Load toolkit from classpath (absolute path is more reliable)
        try (InputStream is = PyNode.class.getResourceAsStream(TOOLKIT_RESOURCE)) {
            if (is == null) {
                throw new FileNotFoundException("Required resource not found: " + TOOLKIT_RESOURCE);
            }
            // Extract to node's meta directory
            File toolkitFile = new File(_metaRoot, "nodetoolkit.py");
            toolkitFile.getParentFile().mkdirs(); 
            Stream.writeFully(toolkitFile, readFullyFromStream(is));
            _logger.info("Extracted toolkit script to: {}", toolkitFile.getAbsolutePath());
            // Execute it
            Source source = Source.newBuilder(PYTHON_LANGUAGE_ID, toolkitFile).build();
            _pythonContext.eval(source);
            _logger.info("Executed toolkit bootstrap script");
            _outReader.inject("Executed toolkit bootstrap script");
        } catch (org.graalvm.polyglot.PolyglotException e) {
            handlePolyglotException("Loading toolkit script", e);
            throw new IOException("Failed to execute toolkit script", e);
        } catch (Exception e) {
            handleException("Loading toolkit script", e);
            throw new IOException("Failed to execute toolkit script", e);
        }
    }

    /**
     * Mark the node as failed with the given exception.
     */
    private void markFailed(Exception e) {
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
            // Capture the classloader
            ClassLoader hostCl = Thread.currentThread().getContextClassLoader();

            System.out.println("DEBUG  Loader used by PyNode: " + hostCl);
            try {
                Class<?> c = hostCl.loadClass("org.nodel.jyhost.NodelHost");
                System.out.println("DEBUG  Can load NodelHost from that loader: YES");
            } catch (ClassNotFoundException e) {
                System.out.println("DEBUG  Can load NodelHost from that loader: NO");
            }

            // Use standard Context.newBuilder instead of GraalPyResources.contextBuilder
            _pythonContext = Context.newBuilder("python")
                .allowHostAccess(HostAccess.ALL) 
                .allowHostClassLookup(name -> true) 
                .hostClassLoader(hostCl) 
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
                return null; 
            }
        } catch (PolyglotException e) {
            handlePolyglotException("Executing function " + functionName, e);
            return null; 
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
            StringBuilder stackTrace = new StringBuilder();
            stackTrace.append("\n--- Python stack trace (guest) ---\n");
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                stackTrace.append("  at ").append(frame.toString()).append("\n");
            }
            stackTrace.append("--- End Python stack trace ---");
            _logger.error(stackTrace.toString());
            _outReader.inject(stackTrace.toString());
        }
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
        _pythonContext.enter();
        try { 
        } finally {
            try { _pythonContext.leave(); } catch(Exception e) { /* ignore */ }
        }
    }


    protected void reset() {
        _pythonContext.close(true);
    }

    protected void enable() {
        if (_toolkit != null) {
           _toolkit.enable(); 
        }
    }

    protected void disable() {
         if (_toolkit != null) {
           // Toolkit no longer has explicit disable method
           // Just ensure any references are released properly
         }
    }

    protected void checkReload() {
        if (_closed || !_scriptFile.exists()) {
            return;
        }

        long currentHash = calculateFileModifiedHash();

        if (currentHash != _fileModifiedHash) {
            _logger.info("Change detected, reloading Python node...");
            
            try {
                destroy(); 
                init();    
            } catch (Exception e) {
                _logger.error("Failed to reload node: " + e.getMessage());
            }
        }
    }

    private void applyBindings(Bindings bindings) {
        if (bindings == null) {
            _logger.info("No bindings were specified.");
            return;
        }
        
        if (bindings.local != null && bindings.local.actions != null) {
            for (Entry<SimpleName, Binding> entry : bindings.local.actions.entrySet()) {
                addAction(entry.getKey(), entry.getValue());
            }
        }
        
        if (bindings.local != null && bindings.local.events != null) {
            for (Entry<SimpleName, Binding> entry : bindings.local.events.entrySet()) {
                addEvent(entry.getKey(), entry.getValue());
            }
        }
        
        if (bindings.remote != null && bindings.remote.actions != null) {
            for (Entry<SimpleName, NodelActionInfo> entry : bindings.remote.actions.entrySet()) {
                addRemoteAction(entry.getKey(), entry.getValue());
            }
        }
        
        if (bindings.remote != null && bindings.remote.events != null) {
            for (Entry<SimpleName, NodelEventInfo> entry : bindings.remote.events.entrySet()) {
                addRemoteEvent(entry.getKey(), entry.getValue());
            }
        }
        
        if (bindings.params != null) {
            for (Entry<SimpleName, ParameterBinding> entry : bindings.params.entrySet()) {
                addParameter(entry.getKey(), entry.getValue());
            }
        }
        
        _logger.info("Applied {} local actions, {} local events, {} remote actions, {} remote events, {} parameters",
            (bindings.local != null && bindings.local.actions != null) ? bindings.local.actions.size() : 0,
            (bindings.local != null && bindings.local.events != null) ? bindings.local.events.size() : 0,
            (bindings.remote != null && bindings.remote.actions != null) ? bindings.remote.actions.size() : 0,
            (bindings.remote != null && bindings.remote.events != null) ? bindings.remote.events.size() : 0,
            (bindings.params != null) ? bindings.params.size() : 0);
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
                _logger.warn("Could not find or cache executable Python function: " + functionName);
            }
        } catch (Exception e) {
             handleException("Caching function " + functionName, e);
        } finally {
            try { _pythonContext.leave(); } catch(Exception e) { /* ignore */ }
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

        final H0 callbackRunnable = new H0() {
            @Override
            public void handle() {
                long startTime = System.currentTimeMillis();
                _logger.info("Executing local action: " + functionName);

                try {
                    _pythonContext.enter(); 
                    if (arg != null) {
                        pyFunc.execute(arg);
                    } else {
                        pyFunc.execute();
                    }
                    handler.handleActionRequest(null); 
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

        String remoteFunctionName = _pythonEventHandlers.get(eventName);
        if (remoteFunctionName == null) remoteFunctionName = "remote_event_" + eventName; 

        final org.graalvm.polyglot.Value pyFunc = _pythonFunctions.get(remoteFunctionName);
        if (pyFunc == null || !pyFunc.canExecute()) {
            _logger.warn("Remote event handler function '" + remoteFunctionName + "' not found or not executable.");
            return;
        }

        final String functionName = remoteFunctionName;

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
        return new ArrayList<>(_pythonFunctions.keySet());
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

        final String functionKey = "eval" + (!Strings.isBlank(source) ? "_" + source : "") + "_" + _funcSeqNumber.getAndIncrement();

        try {
            _logger.info("Evaluating expression: " + expr);

            try {
                _pythonContext.enter();

                Source source1 = Source.newBuilder(PYTHON_LANGUAGE_ID, expr, "eval").buildLiteral(); 
                org.graalvm.polyglot.Value result = _pythonContext.eval(source1);

                if (result.isString()) {
                    return result.asString();
                } else if (result.isNumber()) {
                    return result.as(Number.class);
                } else if (result.isBoolean()) {
                    return result.asBoolean();
                } else if (result.isNull()) {
                    return null;
                } else {
                    return result;
                }
            } catch (PolyglotException e) {
                handlePolyglotException("Evaluating '" + expr + "'", e);
                throw new RuntimeException("Evaluation failed: " + e.getMessage(), e);
            } finally {
                try { _pythonContext.leave(); } catch (Exception e) { /* ignore */ }
            }
        } finally {
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
            return; 
        }

        final String functionKey = "exec" + (!Strings.isBlank(source) ? "_" + source : "") + "_" + _funcSeqNumber.getAndIncrement();

        try {
            _logger.info("Executing code fragment" + (source != null ? " from " + source : ""));

            try {
                _pythonContext.enter();

                boolean evaluated = false;
                try {
                    _logger.debug("Trying to evaluate as expression (literal): [{}]", code);
                    Source exprSource = Source.newBuilder(PYTHON_LANGUAGE_ID, code, source).buildLiteral(); 
                    org.graalvm.polyglot.Value result = _pythonContext.eval(exprSource);
                    _logger.debug("Evaluation as expression succeeded.");

                    if (result != null && !result.isNull()) {
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
                                resultStr = result.toString(); 
                            }
                        } catch (Exception e) {
                            resultStr = "<Error converting result to string: " + e.getMessage() + ">";
                        }

                        _outReader.write(resultStr + "\n");
                        _outReader.flush(); 
                    }
                    evaluated = true; 
                } catch (PolyglotException pe) {
                    if (pe.isSyntaxError()) {
                        _logger.debug("Eval as expression failed (SyntaxError), will try as statement: {}", code);
                        evaluated = false;
                    } else {
                        _logger.error("Eval as expression failed (Non-SyntaxError): {}", pe.getMessage());
                        handlePolyglotException("Evaluating expression in exec", pe);
                        throw new RuntimeException("Evaluation failed: " + pe.getMessage(), pe);
                    }
                }

                if (!evaluated) {
                    _logger.debug("Executing code fragment as statement: {}", code);
                    _pythonContext.eval(Source.newBuilder(PYTHON_LANGUAGE_ID, code, source).build()); 
                    _logger.debug("Execution as statement finished.");
                }

            } catch (PolyglotException e) {
                handlePolyglotException("Executing code fragment", e);
                throw new RuntimeException("Execution failed: " + e.getMessage(), e);
            } finally {
                try { _pythonContext.leave(); } catch (Exception e) { /* ignore */ }
            }
        } finally {
        }
    }

    /**
     * Tracks function execution for detecting stuck callbacks.
     */
    private void trackFunction(String functionName) {
        _logger.debug("Function execution started: {}", functionName);
    }

    /**
     * Removes function from tracking.
     */
    private void untrackFunction(String functionName) {
        _logger.debug("Function execution completed: {}", functionName);
    }

    /**
     * Creates a custom lock for thread synchronization.
     * In the original Jython implementation, this worked around Jython threading issues.
     * In the GraalVM implementation, this is no longer needed as GraalVM's Context
     * provides its own thread-safety guarantees. This now returns a dummy lock for
     * API compatibility.
     */
    private ReentrantLock getAReentrantLock() {
        _logger.debug("getAReentrantLock() called - no longer needed with GraalVM threading model");
        return new ReentrantLock();
    }

    /**
     * Destroys this node, releasing all resources.
     */
    private void destroy() {
        if (_closed) return;
        _closed = true; 

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

        if (_toolkit != null) {
            Stream.safeClose(_toolkit);
            _toolkit = null;
        }

        if (_pythonContext != null) {
            try {
                _logger.info("Closing Python context for node {}", getName());
                _pythonContext.close(true); 
            } catch (Exception e) {
                _logger.error("Error closing Python context for node " + getName(), e);
            } finally {
               _pythonContext = null; 
            }
        }

        _pythonFunctions.clear();
        reset(); 

        _logger.info("Python node destroyed.");
        _outReader.inject("Python node destroyed.");
    }

    /**
     * Calculates a hash based on modification times of relevant files.
     */
    private long calculateFileModifiedHash() {
        long hash = 5381;
        hash = (hash << 5) + hash + _root.lastModified();         
        hash = (hash << 5) + hash + _scriptFile.lastModified();     
        if (_configFile != null)
            hash = (hash << 5) + hash + _configFile.lastModified(); 

        List<File> pyFiles = findPyFiles(_root);
        File libDir = new File(_root, "lib");
        if (libDir.isDirectory()) {
             pyFiles.addAll(findPyFiles(libDir));
        }
        pyFiles.sort(Comparator.comparing(File::getAbsolutePath)); 
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

    // -----------------------------------------------------------------
    //  Binding implementation methods
    // -----------------------------------------------------------------
    private void addAction(SimpleName name, Binding binding) {
        NodelServerAction action = new NodelServerAction(getName(), name, binding);

        ActionRequestHandler handler = (requestArg) -> {
            handleActionRequest(name, requestArg, null); 
        };
        action.registerAction(handler);

        injectLocalAction(action); 
    }

    private void addEvent(SimpleName name, Binding binding) {
        NodelServerEvent event = new NodelServerEvent(getName(), name, binding);

        injectLocalEvent(event); 
    }

    private void addRemoteAction(SimpleName name, NodelActionInfo info) {
        Binding meta = new Binding(info.title, info.desc, info.group, info.caution, info.order, null); 

        SimpleName remoteNode = info.node != null ? new SimpleName(info.node) : null;
        SimpleName remoteAction = info.action != null ? new SimpleName(info.action) : null;

        NodelClientAction ra = new NodelClientAction(name, meta, remoteNode, remoteAction);

        injectRemoteAction(ra, remoteNode, remoteAction);
    }

    private void addRemoteEvent(SimpleName name, NodelEventInfo info) {
        Binding meta = new Binding(info.title, info.desc, info.group, info.caution, info.order, null); 

        SimpleName remoteNode = info.node != null ? new SimpleName(info.node) : null;
        SimpleName remoteEvent = info.event != null ? new SimpleName(info.event) : null;

        NodelClientEvent re = new NodelClientEvent(name, meta, remoteNode, remoteEvent);

        NodelEventHandler handler = (remoteNodeName, remoteEventName, arg) -> {
            handleEvent(name, arg); 
        };
        re.setHandler(handler);

        injectRemoteEvent(re, remoteNode, remoteEvent);

        if (info instanceof PyBindingInfo) {
            _pythonEventHandlers.put(name, ((PyBindingInfo) info).getFunctionName());
        }
    }

    private void addParameter(SimpleName name, ParameterBinding param) { /* TODO */ }
}