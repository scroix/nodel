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
import org.nodel.core.BindingState;
import org.nodel.core.NodelClientAction;
import org.nodel.core.NodelClientEvent;
import org.nodel.core.NodelEventHandler;
import org.nodel.core.NodelServerAction;
import org.nodel.core.NodelServerEvent;
import org.nodel.host.*;
import org.nodel.io.Files;
import org.nodel.io.Stream;
import org.nodel.Threads;
import org.nodel.reflection.Param;
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

/**
 * Represents a script-enabled Node under GraalVM (replacing Jython).
 * <p>
 * The node's language is selected by the script file present in its folder —
 * 'script.py' boots a GraalPy context, 'script.js' boots a GraalJS one. All
 * host/guest interaction goes through the language-neutral polyglot Value API.
 * <p>
 * Thread safety is ensured through GraalVM's built-in Context synchronization mechanisms.
 * Each method that interacts with the guest context uses the enter/leave pattern provided by
 * GraalVM to ensure proper thread attachment and operation serialization.
 * <p>
 * This class previously used a _busy lock, but that was found to be redundant with GraalVM's
 * native thread safety and was removed to improve performance.
 */
public class PyNode extends BaseDynamicNode {

    private static Logger _logger = LoggerFactory.getLogger(PyNode.class);

    /**
     * The guest languages a node can run, carrying everything that varies per
     * language: the GraalVM language ID, a display name for console/log
     * messages, the node's script filename and the toolkit bootstrap
     * resource. Adding a language means adding a constant here (plus its
     * toolkit shim and traceback formatter).
     */
    enum ScriptLanguage {

        PYTHON("python", "Python", "script.py", "/org/nodel/jyhost/nodetoolkit.py"),

        JAVASCRIPT("js", "JavaScript", "script.js", "/org/nodel/jyhost/nodetoolkit.js");

        final String id;

        final String displayName;

        final String scriptFileName;

        final String toolkitResource;

        ScriptLanguage(String id, String displayName, String scriptFileName, String toolkitResource) {
            this.id = id;
            this.displayName = displayName;
            this.scriptFileName = scriptFileName;
            this.toolkitResource = toolkitResource;
        }

        /**
         * The toolkit file extracted into the node's meta directory (the
         * resource's last path segment).
         */
        String toolkitFileName() {
            return toolkitResource.substring(toolkitResource.lastIndexOf('/') + 1);
        }

        /**
         * True if the (lower-cased) filename has any supported language's
         * script extension.
         */
        static boolean isScriptFile(String name) {
            String lc = name.toLowerCase();
            for (ScriptLanguage language : values())
                if (lc.endsWith(language.scriptFileName.substring(language.scriptFileName.lastIndexOf('.'))))
                    return true;
            return false;
        }

        /**
         * True if any supported language's script file exists in the folder.
         */
        static boolean anyScriptFileIn(File root) {
            for (ScriptLanguage language : values())
                if (new File(root, language.scriptFileName).exists())
                    return true;
            return false;
        }

    }

    /**
     * This node's guest language; selected per script file in createContext().
     */
    private ScriptLanguage _language = ScriptLanguage.PYTHON;

    /**
     * The shared GraalVM guest context (Python or JavaScript).
     * <p>
     * This context is thread-safe by GraalVM design, which automatically serializes access
     * to the guest environment without requiring additional locks.
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
     * The thread-state handler (GraalPy attaches threads to its context on
     * demand, so no per-thread preparation is required — unlike Jython's
     * PySystemState)
     */
    private H0 _threadStateHandler = new Handler.H0() {

        @Override
        public void handle() {
            // (no per-thread state required under GraalVM)
        }

    };

    /**
     * The exception handler (surfaces toolkit exceptions in the node console).
     */
    private Handler.H2<String, Exception> _exceptionHandler = new Handler.H2<String, Exception>() {

        @Override
        public void handle(String context, Exception th) {
            // unwrap to find a guest (Python) exception for a proper traceback
            Throwable cause = th;
            while (cause != null && !(cause instanceof PolyglotException))
                cause = cause.getCause();

            if (cause instanceof PolyglotException && ((PolyglotException) cause).isGuestException()) {
                _logger.info("(" + context + ") " + cause.toString());
                _errReader.inject("(" + context + ")");
                for (String line : renderGuestTraceback((PolyglotException) cause).split("\n"))
                    _errReader.inject(line);
            } else {
                String message = "(" + context + ") " + th.toString();
                _logger.info(message);
                _errReader.inject(message);
            }
        }

    };

    /**
     * The config file that contains the node configuration.
     * (the live config itself lives in BaseNode._config)
     */
    private File _configFile;

    /**
     * How often the script / config files are checked for changes (hot reload).
     */
    private static final long MONITOR_PERIOD = 10000;

    /**
     * Serialises teardown / re-init cycles (hot reload, config saves, close).
     */
    private final Object _reloadLock = new Object();

    /**
     * The toolkit bootstrap sources, read from the classpath once per JVM
     * (every node context evaluates the same immutable resource).
     */
    private static final Map<String, String> s_toolkitSourceCache = new java.util.concurrent.ConcurrentHashMap<>();

    public PyNode(NodelHost nodelHost, SimpleName name, File root) throws IOException {
        super(name, root);
        _nodelHost = nodelHost;
        _configFile = new File(root, "nodeConfig.json");

        createContext();
        try {
            init();
        } catch (Exception e) {
            // this instance is about to be abandoned by the host — release anything
            // the failed init managed to register so a later retry starts clean
            try { teardown(); } catch (Exception e2) { /* best effort */ }

            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to initialize " + _language.displayName + " node", e);
        }

        // watch for script / config file changes (hot reload)
        scheduleMonitor();
    }

    /**
     * Self-rescheduling file monitor that drives hot reload (checkReload()).
     */
    private void scheduleMonitor() {
        if (_closed)
            return;

        s_timerThread.schedule(s_threadPool, new TimerTask() {

            @Override
            public void run() {
                try {
                    checkReload();
                } catch (Exception exc) {
                    _logger.warn("Reload monitoring failed; will retry.", exc);
                } finally {
                    scheduleMonitor();
                }
            }

        }, MONITOR_PERIOD);
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
                    return lc.startsWith(scriptFilePrefix) && ScriptLanguage.isScriptFile(lc);
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
            String scriptExt = _scriptFile.getName().substring(_scriptFile.getName().lastIndexOf('.'));
            Files.copy(_scriptFile, new File(_root, scriptFilePrefix + timestamp + scriptExt));

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

        _outReader.inject("Initialising " + _language.displayName + " node...");
        _logger.info("Initialising " + _language.displayName + " node...");

        // the script file (and language) were detected in createContext()
        if (!_scriptFile.exists() && _language == ScriptLanguage.PYTHON) {
            // Create default script file if it doesn't exist
            Stream.writeFully(_scriptFile, "# Default Python script\n\ndef main():\n    print('Hello from Python')\n");
        }
            
        // Clear existing Python functions
        _pythonFunctions.clear();

        // Initialise the toolkit for this node
        _callbackQueue = new CallbackQueue();
        _toolkit = new ManagedToolkit(this)
            .setExceptionHandler(_exceptionHandler)
            .setThreadStateHandler(_threadStateHandler)
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
            _pythonContext.getBindings(_language.id).putMember("_toolkit", _toolkit);

            // Inject the node itself (2.x parity: recipes use '_node' for name lookups, etc.)
            _pythonContext.getBindings(_language.id).putMember("_node", this);

            // Load and execute the toolkit bootstrap script BEFORE any user script
            loadToolkit();

            // record the hash up-front so a broken script isn't re-run until one
            // of the files actually changes (hot reload)
            _fileModifiedHash = calculateFileModifiedHash();

            // Now run the user script (script.py or script.js)
            boolean scriptLoaded = false;
            if (_scriptFile.exists()) {
                try {
                    executeFileScript(_scriptFile);
                    scriptLoaded = true;
                } catch (PolyglotException e) {
                    // the Python-style traceback has already been surfaced in the
                    // console; keep the node alive in an error state so the console
                    // stays inspectable and a script fix hot-reloads (Jython parity)
                    markFailed(e);
                }
            }

            if (scriptLoaded) {
                // Extract bindings from Python
                List<String> warnings = new ArrayList<>();
                org.graalvm.polyglot.Value pythonGlobals = _pythonContext.getBindings(_language.id);
                Bindings bindings = BindingsExtractor.extract(pythonGlobals, warnings);

                // overlay the saved remote-binding and parameter values (nodeConfig.json)
                NodeConfig config = loadConfig();
                injectRemoteBindingValues(config, bindings.remote);
                injectParamValues(config, bindings.params);
                _config = config;

                applyBindings(bindings);
                _bindings = bindings;
                for (String warning : warnings) {
                    _logger.warn(warning);
                }

                // Execute the Python lifecycle functions
                _pythonContext.enter();
                try {
                    // Execute any before_main hooks
                    _logger.info("Running before_main functions...");
                    org.graalvm.polyglot.Value beforeMainFunc = pythonGlobals.getMember("process_before_main_functions");
                    if (beforeMainFunc != null && beforeMainFunc.canExecute()) {
                        beforeMainFunc.execute();
                    }
                    
                    // Execute main() if it exists
                    _logger.info("Looking for main() function...");
                    org.graalvm.polyglot.Value mainFunc = pythonGlobals.getMember("main");
                    if (mainFunc != null && mainFunc.canExecute()) {
                        _logger.info("Executing main() function...");
                        mainFunc.execute();
                    } else {
                        _logger.info("No main() function found, skipping");
                    }
                    
                    // Execute any after_main hooks
                    _logger.info("Running after_main functions...");
                    org.graalvm.polyglot.Value afterMainFunc = pythonGlobals.getMember("process_after_main_functions");
                    if (afterMainFunc != null && afterMainFunc.canExecute()) {
                        afterMainFunc.execute();
                    }

                    // nothing went wrong — kick off the toolkit (starts managed
                    // TCP/UDP/process connections, etc.)
                    _toolkit.enable();
                } catch (PolyglotException e) {
                    handlePolyglotException("Executing lifecycle functions", e);
                } catch (Exception e) {
                    handleException("Executing lifecycle functions", e);
                } finally {
                    try { _pythonContext.leave(); } catch(Exception e) { /* ignore */ }
                }

            }

            _logger.info(_language.displayName + " node initialised.");
            _outReader.inject(_language.displayName + " node initialised.");
            
            // Mark as successfully initialized by setting the description
            synchronized (_signal) {
                _started = DateTime.now();
                _desc = "GraalVM " + _language.displayName + " Node";
                _signal.notifyAll();
            }
            
        } catch (Exception e) {
            _logger.error("Failed to initialise " + _language.displayName + " node: " + e.toString());
            _outReader.inject("Failed to initialise " + _language.displayName + " node: " + e.toString());
            handleException("Initialisation", e);

            // unregister any bindings that were applied before the failure so a
            // retry (folder rescan or hot reload) doesn't hit 'Already bound'
            try { cleanupBindings(); } catch (Exception e2) { /* best effort */ }

            markFailed(e);
            throw e;
        }
    }

    /**
     * Loads the node configuration (nodeConfig.json), falling back to an empty config.
     */
    private NodeConfig loadConfig() {
        try {
            if (_configFile != null && _configFile.exists())
                return (NodeConfig) Serialisation.coerceFromJSON(NodeConfig.class, Stream.readFully(_configFile));
        } catch (Exception exc) {
            _errReader.inject("Could not parse the node config file; using an empty config. " + exc);
            _logger.warn("Could not parse " + _configFile, exc);
        }
        return new NodeConfig();
    }

    /**
     * Loads and executes the language's toolkit bootstrap script
     * (nodetoolkit.py or nodetoolkit.js) before user scripts.
     */
    private void loadToolkit() throws IOException {
        // the resource is immutable per JVM so it is read from the classpath once
        String content = s_toolkitSourceCache.get(_language.toolkitResource);
        if (content == null) {
            try (InputStream is = PyNode.class.getResourceAsStream(_language.toolkitResource)) {
                if (is == null) {
                    throw new FileNotFoundException("Required resource not found: " + _language.toolkitResource);
                }
                content = readFullyFromStream(is);
            }
            s_toolkitSourceCache.put(_language.toolkitResource, content);
        }

        try {
            // Extract a reference copy into the node's meta directory
            File toolkitFile = new File(_metaRoot, _language.toolkitFileName());
            toolkitFile.getParentFile().mkdirs();
            Stream.writeFully(toolkitFile, content);
            _logger.info("Extracted toolkit script to: {}", toolkitFile.getAbsolutePath());
            // Execute it
            Source source = Source.newBuilder(_language.id, content, toolkitFile.getName()).build();
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
     * Detects the node's script file and language: 'script.js' selects
     * GraalJS, otherwise 'script.py' / GraalPy (the default). If both files
     * are present, Python wins (and a warning is logged).
     */
    private void detectScriptLanguage() {
        File pyFile = new File(_root, ScriptLanguage.PYTHON.scriptFileName);
        File jsFile = new File(_root, ScriptLanguage.JAVASCRIPT.scriptFileName);

        if (jsFile.exists() && !pyFile.exists()) {
            _scriptFile = jsFile;
            _language = ScriptLanguage.JAVASCRIPT;
        } else {
            if (jsFile.exists() && pyFile.exists())
                _logger.warn("Both script.py and script.js are present; using script.py");

            _scriptFile = pyFile;
            _language = ScriptLanguage.PYTHON;
        }
    }

    /**
     * Creates and configures the GraalVM context.
     */
    private Context createContext() {
        // Ensure LineReaders are initialized first
        _outReader = new LineReader();
        _errReader = new LineReader();

        // select the guest language from the script file present in the node folder
        detectScriptLanguage();

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

        _logger.info("Creating GraalVM {} context for node '{}'", _language.displayName, getName());

        try {
            // Capture the classloader
            ClassLoader hostCl = Thread.currentThread().getContextClassLoader();

            // Use standard Context.newBuilder instead of GraalPyResources.contextBuilder
            _pythonContext = Context.newBuilder(_language.id)
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(name -> true)
                .hostClassLoader(hostCl)
                .out(stdoutStream)
                .err(stderrStream)
                // without the Graal compiler the fallback runtime warns on every
                // context; don't spam each node's console with it
                .option("engine.WarnInterpreterOnly", "false")
                .build();
            return _pythonContext;
        } catch (Exception e) {
            _logger.error("Failed to create GraalVM " + _language.displayName + " context", e);
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
        Source source = Source.newBuilder(_language.id, scriptFile).build();
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
        Source source = Source.newBuilder(_language.id, scriptContent, scriptName).build();
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
            org.graalvm.polyglot.Value func = _pythonContext.getBindings(_language.id).getMember(functionName);
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
            // surface a language-native traceback in the console's error stream
            // (parity with the Jython host)
            for (String line : renderGuestTraceback(e).split("\n"))
                _errReader.inject(line);
        } else {
            _errReader.inject("(" + context + ") " + e.toString());
        }

        _logger.error("PolyglotException during '{}'", context, e);
    }

    /**
     * Renders a guest exception in its language's native style — a CPython
     * traceback for Python nodes, an Error + stack for JavaScript nodes.
     */
    private String renderGuestTraceback(PolyglotException e) {
        if (_language == ScriptLanguage.JAVASCRIPT)
            return formatJavaScriptStack(e);

        return renderPythonTraceback(e);
    }

    /**
     * Renders a guest exception using Python's own 'traceback' module (full
     * CPython fidelity, including source lines), falling back to a synthesised
     * traceback when the guest object or context isn't usable.
     */
    private String renderPythonTraceback(PolyglotException e) {
        try {
            org.graalvm.polyglot.Value guestObject = e.getGuestObject();
            Context context = _pythonContext;
            if (guestObject != null && context != null) {
                org.graalvm.polyglot.Value formatter = context.eval(ScriptLanguage.PYTHON.id,
                        "(lambda ex: ''.join(__import__('traceback').format_exception(ex)))");
                String formatted = formatter.execute(guestObject).asString();
                if (!Strings.isBlank(formatted))
                    return formatted.trim();
            }
        } catch (Exception e2) {
            // fall through to the synthesised form
        }
        return formatPythonTraceback(e);
    }

    /**
     * Formats a guest (Python) exception the way CPython would print it, so the
     * web console output is comparable to the Jython host's.
     */
    static String formatPythonTraceback(PolyglotException e) {
        StringBuilder sb = new StringBuilder();

        if (e.isSyntaxError()) {
            // GraalPy syntax errors carry the file/line detail in the message,
            // e.g. "SyntaxError: invalid syntax (script.py, line 3)"
            org.graalvm.polyglot.SourceSection loc = e.getSourceLocation();
            if (loc != null && loc.getSource() != null)
                sb.append("  File \"").append(loc.getSource().getName())
                  .append("\", line ").append(loc.getStartLine()).append("\n");
            sb.append(e.getMessage());
            return sb.toString();
        }

        // collect guest frames; polyglot stack traces are innermost-first but
        // Python prints outermost-first
        List<PolyglotException.StackFrame> guestFrames = new ArrayList<>();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame())
                guestFrames.add(frame);
        }

        sb.append("Traceback (most recent call last):");
        for (int i = guestFrames.size() - 1; i >= 0; i--) {
            PolyglotException.StackFrame frame = guestFrames.get(i);
            org.graalvm.polyglot.SourceSection ss = frame.getSourceLocation();
            String file = (ss != null && ss.getSource() != null) ? ss.getSource().getName() : "<unknown>";
            int line = ss != null ? ss.getStartLine() : -1;
            sb.append("\n  File \"").append(file).append("\", line ").append(line)
              .append(", in ").append(frame.getRootName());
        }

        // GraalPy messages are already in "ExceptionType: detail" form
        sb.append("\n").append(e.getMessage());

        return sb.toString();
    }

    /**
     * Formats a guest (JavaScript) exception the way a JS engine would print
     * it — "TypeError: detail" followed by "    at func (file:line)" frames.
     */
    static String formatJavaScriptStack(PolyglotException e) {
        StringBuilder sb = new StringBuilder();

        // GraalJS messages are already in "ErrorType: detail" form
        sb.append(e.getMessage());

        if (e.isSyntaxError()) {
            org.graalvm.polyglot.SourceSection loc = e.getSourceLocation();
            if (loc != null && loc.getSource() != null)
                sb.append("\n    at ").append(loc.getSource().getName())
                  .append(":").append(loc.getStartLine());
            return sb.toString();
        }

        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame())
                continue;
            org.graalvm.polyglot.SourceSection ss = frame.getSourceLocation();
            String file = (ss != null && ss.getSource() != null) ? ss.getSource().getName() : "<unknown>";
            int line = ss != null ? ss.getStartLine() : -1;
            sb.append("\n    at ").append(frame.getRootName())
              .append(" (").append(file).append(":").append(line).append(")");
        }

        return sb.toString();
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
        if (_pythonContext != null)
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
        if (_closed)
            return;

        // consider every language's script file so a node can be converted
        // between script.py and script.js (reload() re-detects the language)
        if (!ScriptLanguage.anyScriptFileIn(_root))
            return;

        long currentHash = calculateFileModifiedHash();

        if (currentHash != _fileModifiedHash) {
            _logger.info("Change detected, reloading " + _language.displayName + " node...");

            try {
                reload();
            } catch (Exception e) {
                _logger.error("Failed to reload node: " + e.getMessage());
            }
        }
    }

    /**
     * Tears down the current Python context and boots a fresh one; used by
     * hot reload and config saves.
     */
    private void reload() throws Exception {
        synchronized (_reloadLock) {
            if (_closed)
                return;

            teardown();
            createContext();
            init();
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
            org.graalvm.polyglot.Value func = _pythonContext.getBindings(_language.id).getMember(functionName);
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

    public void handleActionRequest(SimpleName actionName, Object arg, ActionRequestHandler handlerOrNull) {
        // fire-and-forget callers (e.g. a script's lookup_local_action(...).call()) pass no
        // completion handler; failures are logged to the node's console regardless
        final ActionRequestHandler handler = (handlerOrNull != null) ? handlerOrNull : (result) -> { };

        if (_closed || _pythonContext == null) {
             handler.handleActionRequest(new RuntimeException("Node is closed or not initialised"));
             return;
        }

        String functionName = "local_action_" + actionName.toString();
        org.graalvm.polyglot.Value cached = _pythonFunctions.get(functionName);
        if (cached == null) {
            // resolve lazily from the context (and cache for next time)
            cachePythonFunction(functionName);
            cached = _pythonFunctions.get(functionName);
        }
        final org.graalvm.polyglot.Value pyFunc = cached;

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
                } catch (PolyglotException e) {
                    handlePolyglotException("action '" + actionName + "'", e);
                    handler.handleActionRequest(new RuntimeException(
                            "Error executing action '" + actionName + "': " + e.getMessage(), e));
                } catch (Exception e) {
                    String errMsg = "Error executing action '" + actionName + "': " + e.getMessage();
                    _logger.error(errMsg);
                    _errReader.inject(errMsg);
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

        org.graalvm.polyglot.Value cached = _pythonFunctions.get(remoteFunctionName);
        if (cached == null) {
            // resolve lazily from the context (and cache for next time)
            cachePythonFunction(remoteFunctionName);
            cached = _pythonFunctions.get(remoteFunctionName);
        }
        final org.graalvm.polyglot.Value pyFunc = cached;
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
                } catch (PolyglotException e) {
                    handlePolyglotException("event handler '" + functionName + "'", e);
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
     * Evaluates a script expression within the node's context.
     * Used by REST API and other clients.
     *
     * @param expr The Python expression to evaluate
     * @param source A description of the source of the request (for logging)
     * @return The result of the evaluation
     */
    @Service(name="eval", title="Evaluate", desc="Evaluates a script expression (in the node's language).")
    public Object eval(@Param(name="expr", title="Expression", desc="A script expression.") final String expr,
                      String source) throws Exception {
        if (_closed || _pythonContext == null)
            throw new RuntimeException("The interpreter is not initialized or has been closed.");

        final String functionKey = "eval" + (!Strings.isBlank(source) ? "_" + source : "") + "_" + _funcSeqNumber.getAndIncrement();

        try {
            _logger.info("Evaluating expression: " + expr);

            try {
                _pythonContext.enter();

                Source source1 = Source.newBuilder(_language.id, expr, "eval").buildLiteral(); 
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
    @Service(name="exec", title="Execute", desc="Execute a script code fragment (in the node's language).")
    public void exec(@Param(name="code", title="Code", desc="A script code fragment.") final String code,
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
                    Source exprSource = Source.newBuilder(_language.id, code, source).buildLiteral(); 
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
                    _pythonContext.eval(Source.newBuilder(_language.id, code, source).build()); 
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
     * Tears down the Python context and toolkit, releasing all script-side
     * resources. Does NOT mark the node closed — see close() for permanent
     * shutdown; reload() calls this before booting a fresh context.
     */
    private void teardown() {
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

        _logger.info(_language.displayName + " node destroyed.");
        _outReader.inject(_language.displayName + " node destroyed.");
    }

    /**
     * Permanently shuts down the node.
     */
    @Override
    public void close() {
        synchronized (_reloadLock) {
            if (_closed)
                return;

            super.close();

            teardown();
        }
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
        File[] files = dir.listFiles((d, name) -> ScriptLanguage.isScriptFile(name));
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
            handleActionRequest(name, requestArg, null); // fire-and-forget; completion not needed
        };
        action.registerAction(handler);

        injectLocalAction(action); 
    }

    private void addEvent(SimpleName name, Binding binding) {
        NodelServerEvent event = new NodelServerEvent(getName(), name, binding);

        injectLocalEvent(event);

        // replace the metadata placeholder (e.g. 'local_event_X = LocalEvent(...)')
        // with the live event object so scripts can call '.emit(...)' on it
        exposeInPython("local_event_" + name.toString(), event);
    }

    private void addRemoteAction(final SimpleName name, NodelActionInfo info) {
        Binding meta = new Binding(info.title, info.desc, info.group, info.caution, info.order, null);

        // empty bindings are allowed and will show up as "unbound" sources
        SimpleName remoteNode = !Strings.isBlank(info.node) ? new SimpleName(info.node) : null;
        SimpleName remoteAction = !Strings.isBlank(info.action) ? new SimpleName(info.action) : null;

        // NOTE: this is a *declarative* binding — it is already present in
        // _bindings.remote.actions via the extractor, so it is registered
        // directly instead of via injectRemoteAction() (the toolkit-creation
        // path, which reserves a new section in the bindings/config)
        final NodelClientAction ra = new NodelClientAction(name, meta, remoteNode, remoteAction);

        ra.attachMonitor(new Handler.H1<Object>() {

            @Override
            public void handle(Object arg) {
                if (ra.isUnbound())
                    addLog(DateTime.now(), LogEntry.Source.unbound, LogEntry.Type.action, name, arg);
                else
                    addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.action, name, arg);
            }

        });
        ra.attachWiredStatusChanged(new Handler.H1<BindingState>() {

            @Override
            public void handle(BindingState status) {
                _logger.info("Action binding status: {} - '{}'", name.getReducedName(), status);

                addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.actionBinding, name, status);
            }

        });

        _remoteActions.put(ra.getName(), ra);
        ra.registerActionInterest();

        // replace the metadata placeholder (e.g. 'remote_action_X = RemoteAction(...)')
        // with the live action object so scripts can call '.call(...)' on it
        exposeInPython("remote_action_" + name.toString(), ra);
    }

    /**
     * Exposes a host object as a Python global, replacing a declaration
     * placeholder so scripts can interact with the live Nodel object.
     */
    private void exposeInPython(String globalName, Object hostObject) {
        try {
            _pythonContext.enter();
            try {
                _pythonContext.getBindings(_language.id).putMember(globalName, hostObject);
            } finally {
                try { _pythonContext.leave(); } catch (Exception e) { /* ignore */ }
            }
        } catch (Exception exc) {
            handleException("Exposing '" + globalName + "' to Python", exc);
        }
    }

    private void addRemoteEvent(final SimpleName name, NodelEventInfo info) {
        Binding meta = new Binding(info.title, info.desc, info.group, info.caution, info.order, null);

        // empty bindings are allowed and will show up as "unbound" sources
        SimpleName remoteNode = !Strings.isBlank(info.node) ? new SimpleName(info.node) : null;
        SimpleName remoteEvent = !Strings.isBlank(info.event) ? new SimpleName(info.event) : null;

        // NOTE: this is a *declarative* binding — it is already present in
        // _bindings.remote.events via the extractor, so it is registered
        // directly instead of via injectRemoteEvent() (the toolkit-creation
        // path, which reserves a new section in the bindings/config)
        final NodelClientEvent re = new NodelClientEvent(name, meta, remoteNode, remoteEvent);

        NodelEventHandler handler = (remoteNodeName, remoteEventName, arg) -> {
            handleEvent(name, arg);
        };
        re.setHandler(handler);

        re.addBindingStateHandler(new Handler.H1<BindingState>() {

            @Override
            public void handle(BindingState status) {
                _logger.info("Event binding status: {} - '{}'", name.getReducedName(), status);

                addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.eventBinding, name, status);
            }

        });

        // seeds, persists and registers interest (BaseNode)
        addRemoteEvent(re);

        if (info instanceof PyBindingInfo) {
            _pythonEventHandlers.put(name, ((PyBindingInfo) info).getFunctionName());
        }
    }

    private void addParameter(SimpleName name, ParameterBinding param) {
        Object value = param.value;

        String paramName = "param_" + name.toString();

        exposeInPython(paramName, value);

        _parameters.put(name, new ParameterEntry(name, value));

        _logger.info("Created parameter '{}' in script (initial value '{}').", paramName, value);
    }

    // -----------------------------------------------------------------
    //  Node management / config REST services (parity with the Jython host)
    // -----------------------------------------------------------------

    /**
     * Persists the config and reboots the script context so the new values apply.
     */
    private void saveConfig0(NodeConfig config) throws Exception {
        if (config == null)
            return;

        _config = config;

        Stream.writeFully(_configFile, Serialisation.serialise(config, 4));

        reload();
    }

    public class Params {

        @Service(name = "schema", title = "Schema", desc = "Returns the processed schema that produced data that can be used by 'save'.")
        public Map<String, Object> getSchema() {
            return _bindings.params.asSchema();
        }

        @Service(name = "save", title = "Save", desc = "Saves a set of parameters.")
        public void save(@Param(name = "value", title = "Value", desc = "The parameter values.", isMajor = true, genericClassA = SimpleName.class, genericClassB = Object.class)
                         ParamValues paramValues) throws Exception {
            NodeConfig config = _config;
            config.paramValues = paramValues;

            saveConfig0(config);
        }

        @Value(name = "value", title = "Value", desc = "The value object.", treatAsDefaultValue = true)
        public ParamValues getValue() {
            return _config.paramValues;
        }

    }

    /**
     * Holds the live params.
     */
    private Params _params = new Params();

    @Service(name = "params", title = "Params", desc = "The live parameters.")
    public Params getParams() {
        return _params;
    }

    public class Remote {

        @Service(name = "schema", title = "Schema", desc = "Returns the processed schema that produces data that can be used by 'save'.")
        public Map<String, Object> getSchema() {
            return _bindings.remote.asSchema();
        }

        @Service(name = "save", title = "Save", desc = "Saves the remote binding values.")
        public void save(@Param(name = "value", desc = "The remoting binding values.", isMajor = true, title = "Value")
                         RemoteBindingValues remoteBindingValues) throws Exception {
            NodeConfig config = _config;
            config.remoteBindingValues = remoteBindingValues;

            saveConfig0(config);
        }

        @Value(name = "value", title = "Value", desc = "The remote binding values.", treatAsDefaultValue = true)
        public RemoteBindingValues getValue() {
            return _config.remoteBindingValues;
        }

    }

    /**
     * Holds the live remote bindings.
     */
    private Remote _remote = new Remote();

    @Service(name = "remote", title = "Remote", desc = "The remote bindings.")
    public Remote getRemote() {
        return _remote;
    }

    /**
     * Restarts the node.
     */
    @Service(name = "restart", title = "Restart", desc = "Restarts this node.")
    public void restart() {
        _logger.info("restart() called");

        // force the monitor to consider the files changed...
        _fileModifiedHash = 0;

        // ...and kick a reload off in the background now
        s_threadPool.execute(new Runnable() {

            @Override
            public void run() {
                checkReload();
            }

        });
    }

    /**
     * Renames the node.
     */
    @Service(name = "rename", title = "Rename", desc = "Renames a node.")
    public void rename(SimpleName newName) {
        _nodelHost.renameNode(this, newName);

        // would get here without any exceptions, the folder should have been renamed
        _logger.info("This node has been renamed. It will close down and restart under a new name shortly.");
    }

    /**
     * Updates the node from a recipe.
     */
    @Service(name = "update", title = "Updates", desc = "Updates a node from a recipe.")
    public void update(@Param(name = "path") String path) {
        if (Strings.isBlank(path))
            throw new RuntimeException("No recipe path name was provided");

        File baseDir = _nodelHost.recipes().getRecipeFolder(path);

        if (baseDir == null)
            throw new RuntimeException("A recipe with path \"" + path + "\" could not be found.");

        Files.updateDir(baseDir, _root);

        _logger.info("This node has been update (basePath={})", path);
    }

    /**
     * Removes the node.
     */
    @Service(name = "remove", title = "Remove", desc = "Removes a node.")
    public void remove(@Param(name = "confirm") boolean confirm) {
        if (!confirm)
            throw new RuntimeException("'confirm' flag was not set. Nothing removed.");

        this.close();

        // in case of lingering operations, try a few times...

        int triesLeft = 5;
        for (; triesLeft > 0; triesLeft--) {

            // flush all files
            Files.tryFlushDir(_root);

            // files are flushed, now the directory itself
            _root.delete();

            if (!_root.exists())
                break;

            Threads.safeWait(_signal, 1000);

            // and try some more...
        }

        if (triesLeft <= 0)
            throw new RuntimeException("Attempts were made to remove the node however it still exists. Temporary file locking might be preventing the removal of the node. Try again later.");

        // the folder should have been removed!
        _logger.info("The node has been deleted.");
    }

    /**
     * (end-point)
     */
    private FilesEndPoint _files = new FilesEndPoint(_root);

    /**
     * An end-point that supports simple file management.
     */
    @Service(name = "files")
    public FilesEndPoint files() { return _files; }
}