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
import java.io.InputStream; // for FileInputStream
import java.io.FileInputStream;
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

import org.joda.time.DateTime;
import org.nodel.DateTimes;
import org.nodel.Exceptions;
import org.nodel.Handler;
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
import org.nodel.host.BaseDynamicNode;
import org.nodel.host.Binding;
import org.nodel.host.Bindings;
import org.nodel.host.LocalBindings;
import org.nodel.host.LogEntry;
import org.nodel.host.NodeConfig;
import org.nodel.host.NodelActionInfo;
import org.nodel.host.NodelEventInfo;
import org.nodel.host.OperationPendingException;
import org.nodel.host.ParamValues;
import org.nodel.host.ParameterBinding;
import org.nodel.host.ParameterBindings;
import org.nodel.host.RemoteBindingValues;
import org.nodel.host.RemoteBindings;
import org.nodel.io.Files;
import org.nodel.io.Stream;
import org.nodel.reflection.Param;
import org.nodel.reflection.Schema;
import org.nodel.reflection.Serialisation;
import org.nodel.reflection.Value;
import org.nodel.reflection.Service;
import org.nodel.threading.CallbackQueue;
import org.nodel.threading.TimerTask;
import org.nodel.toolkit.Console;
import org.nodel.toolkit.ManagedToolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.PolyglotException;

/**
 * Represents a Python-enabled Node under GraalVM (replacing Jython).
 */
public class PyNode extends BaseDynamicNode {

    private static Logger _logger = LoggerFactory.getLogger(PyNode.class);

    /**
     * Lock to help avoid overlapping operations.
     */
    private ReentrantLock _busy = new ReentrantLock();

    /**
     * When permanently closed (disposed).
     */
    private boolean _closed;

    /**
     * The script file.
     */
    private File _scriptFile;

    /**
     * Used to detect changes in the config/script.
     */
    private long _fileModifiedHash;

    /**
     * Our Graal Python context.
     */
    private Context _pythonContext;

    /**
     * Optionally keep references to Python functions.
     */
    private Map<String, org.graalvm.polyglot.Value> _pythonFunctions = new HashMap<>();

    /**
     * Track stuck function calls.
     */
    private AtomicLong _funcSeqNumber = new AtomicLong();
    private Map<String, Long> _activeFunctions = new HashMap<>();

    /**
     * Our general-purpose toolkit (was once injected into Jython).
     */
    protected ManagedToolkit _toolkit;

    /**
     * The NodelHost (owning host).
     */
    private NodelHost _nodelHost;
    public NodelHost getNodelHost() {
        return _nodelHost;
    }

    public PyNode(NodelHost nodelHost, SimpleName name, File root) throws IOException {
        super(name, root);
        _nodelHost = nodelHost;
        init();
    }

    /**
     * Script info container.
     */
    public class ScriptInfo {
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
            info.script = Stream.readFully(_scriptFile);
            return info;
        }

        @Service(name="raw")
        public String getRawScript() throws IOException {
            return Stream.readFully(_scriptFile);
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

    @Service(name="script")
    public Script getScript() {
        return _script;
    }

    /**
     * A callback queue for concurrency.
     */
    private CallbackQueue _callbackQueue;

    /**
     * Exception handler for user code.
     */
    private H2<String, Exception> _exceptionHandler = new Handler.H2<String, Exception>() {
        @Override
        public void handle(String context, Exception exc) {
            String msg = "(" + context + ") " + exc.toString();
            _logger.info(msg);
            _errReader.inject(msg);
        }
    };

    /**
     * Additional context for event-based exceptions.
     */
    private H1<Exception> _emitExceptionHandler = new H1<Exception>() {
        @Override
        public void handle(Exception exc) {
            Handler.tryHandle(_exceptionHandler, "Emitter", exc);
        }
    };

    /**
     * One-time init.
     */
    private void init() throws IOException {
        // Provide nodeConfig templates if needed
        String filePrefix = "nodeConfig";

        String schemaStr = Serialisation.serialise(Schema.getSchemaObject(NodeConfig.class), 4);
        File schemaFile = new File(_root, "_" + filePrefix + "_schema.json");
        if (!schemaFile.exists() || !Stream.tryReadFully(schemaFile).equals(schemaStr)) {
            Stream.writeFully(schemaFile, schemaStr);
        }

        String exampleStr = Serialisation.serialise(NodeConfig.Example, 4);
        File exampleFile = new File(_root, "_" + filePrefix + "_example.json");
        if (!exampleFile.exists() || !Stream.tryReadFully(exampleFile).equals(exampleStr)) {
            Stream.writeFully(exampleFile, exampleStr);
        }

        _configFile = new File(_root, filePrefix + ".json");
        if (!_configFile.exists()) {
            Stream.writeFully(_configFile, Serialisation.serialise(NodeConfig.Empty, 4));
        }

        _scriptFile = new File(_root, "script.py");
        if (!_scriptFile.exists()) {
            Stream.writeFully(_scriptFile, ExampleScript.get());
        }

        // watch config changes
        s_threadPool.execute(() -> monitorConfig());

        // watch stuck functions
        if (!_closed) {
            s_timerThread.schedule(new TimerTask() {
                @Override
                public void run() {
                    checkActiveFunctions();
                }
            }, 60000);
        }
    }

    /**
     * Watch for stuck calls
     */
    private void checkActiveFunctions() {
        StringBuilder sb = null;
        try {
            synchronized (_activeFunctions) {
                if (_activeFunctions.isEmpty())
                    return;

                for (Entry<String, Long> e : _activeFunctions.entrySet()) {
                    String funcKey = e.getKey();
                    long startedNanos = e.getValue();
                    long ms = (System.nanoTime() - startedNanos)/1_000_000L;
                    if (ms > 2*60000) {
                        if (sb == null) sb = new StringBuilder();
                        else sb.append(", ");
                        sb.append(funcKey + " (" + DateTimes.formatShortDuration(ms) + ")");
                    }
                }
            }
            if (sb != null) {
                String msg = "These functions are stuck - " + sb;
                _errReader.inject(msg);
                _logger.warn(msg);
            }
        } finally {
            if (!_closed) {
                s_timerThread.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        checkActiveFunctions();
                    }
                }, 60000);
            }
        }
    }

    /**
     * Monitor config file changes.
     */
    private void monitorConfig() {
        try {
            if (_configFile.exists()) {
                long mHash = _configFile.lastModified() + _scriptFile.lastModified();
                if (mHash != _fileModifiedHash) {
                    NodeConfig config = (NodeConfig) Serialisation.coerceFromJSON(NodeConfig.class,
                            Stream.readFully(_configFile));
                    applyConfig(config);
                    _fileModifiedHash = mHash;
                    _logger.info("Config updated successfully.");
                }
            }
        } catch (Exception exc) {
            String msg = "Could not parse node config; node will not be re-started. "
                    + Exceptions.formatExceptionGraph(exc);
            _errReader.inject(msg);
            _logger.warn("Config monitoring error; will retry in 10s", exc);
        } finally {
            if (!_closed) {
                s_timerThread.schedule(s_threadPool, new TimerTask() {
                    @Override
                    public void run() {
                        monitorConfig();
                    }
                }, 10000);
            }
        }
    }

    /**
     * Applies new config (and re-launches scripts).
     */
    private void applyConfig(final NodeConfig config) throws Exception {
        _busy.lock();
        try {
            Threads.AsyncResult<Void> op = Threads.executeAsync(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    applyConfig0(config);
                    return null;
                }
            });
            op.waitForResultOrThrowException();
        } finally {
            _busy.unlock();
        }
    }

    /**
     * Core logic: build a new Python context, run user scripts.
     */
    private void applyConfig0(NodeConfig config) throws Exception {
        boolean hasErrors = false;
        cleanupInterpreter();

        long startTime = System.nanoTime();
        _logger.info("Initializing new Python GraalVM context...");

        // 1) create the context or have it:
        initializePythonContext();
        _pythonFunctions.clear();

        // 2) create callback queue + toolkit
        _callbackQueue = new CallbackQueue();

        // 1. Create toolkit and inject it directly
        _toolkit = new ManagedToolkit(this)
                .setExceptionHandler(_exceptionHandler)
                .setCallbackHandler(_callbackQueue)
                .attachConsole(new Console.Interface() {
                    @Override
                    public void warn(Object o)  { logWarning(String.valueOf(o)); }
                    @Override
                    public void log(Object o)   { PyNode.this.log(String.valueOf(o)); }
                    @Override
                    public void info(Object o)  { logInfo(String.valueOf(o)); }
                    @Override
                    public void error(Object o) { logError(String.valueOf(o)); }
                });

        // 2. Inject toolkit directly into Python's global namespace
        _pythonContext.getBindings("python").putMember("_toolkit", _toolkit);

        // 3. Copy and execute nodetoolkit.py
        try (InputStream toolkitIS = PyNode.class.getResourceAsStream("nodetoolkit.py")) {
            File toolkitFile = new File(_metaRoot, "nodetoolkit.py");
            String newContent = Stream.readFully(toolkitIS);

            // Only write if changed
            String oldContent = toolkitFile.exists() ? Stream.tryReadFully(toolkitFile) : "";
            if (!newContent.equals(oldContent)) {
                Stream.writeFully(toolkitFile, newContent);
            }

            // Execute nodetoolkit.py directly
            _pythonContext.eval(Source.newBuilder("python", newContent, "nodetoolkit.py").build());
        }

        // Provide a reference to 'this' node, if desired
        _pythonContext.getBindings("python").putMember("_node", this);

        // gather dependencies
        List<String> dependenciesUsed = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        if (config.dependencies != null) {
            dependencies.addAll(Arrays.asList(config.dependencies));
        } else {
            dependencies.add("script.py");
        }

        // look for ingredient*.py or custom*.py
        String[] rootFiles = _root.list();
        if (rootFiles != null) {
            Arrays.sort(rootFiles);
            for (String f : rootFiles) {
                if (f.startsWith("ingredient") && f.endsWith(".py")) {
                    dependencies.add(f);
                }
            }
            for (String f : rootFiles) {
                if (f.startsWith("custom") && f.endsWith(".py")) {
                    dependencies.add(f);
                }
            }
        }

        Bindings bindings = Bindings.Empty;
        try {
            cleanupBindings();

            // evaluate each dependency
            for (String depFile : dependencies) {
                File pyFile = new File(_root, depFile);
                if (!pyFile.exists()) {
                    throw new FileNotFoundException(depFile + " is listed but missing.");
                }
                String content;
                try (FileInputStream fis = new FileInputStream(pyFile)) {
                    try (Scanner sc = new Scanner(fis, StandardCharsets.UTF_8.name())) {
                        content = sc.useDelimiter("\\A").next();
                    }
                }
                _pythonContext.eval(Source.newBuilder("python", content, depFile).build());
                dependenciesUsed.add(depFile);
            }

            // If you want to run BindingsExtractor, do so here:
            // bindings = BindingsExtractor.extract(_pythonContext.getBindings("python"), new ArrayList<String>());
            bindings = Bindings.Empty; // placeholder

            // apply remote + param values
            injectRemoteBindingValues(config, bindings.remote);
            injectParamValues(config, bindings.params);

            _config = config;
        } catch (Exception exc) {
            hasErrors = true;
            _errReader.inject(exc.toString());
            _logger.warn("Bindings application failed.", exc);
        }

        applyBindings(bindings);
        _bindings = bindings;

        // attempt "main"
        String msg;
        if (!hasErrors) {
            msg = String.format("(Python with scripts %s loaded in %s; calling 'main' if present...)",
                    dependenciesUsed.isEmpty()? "[none]" : dependenciesUsed,
                    DateTimes.formatPeriod(startTime));
            _outReader.inject(msg);
            _logger.info(msg);
        } else {
            msg = "(Loaded with errors; calling 'main' if present...)";
            _errReader.inject(msg);
            _logger.warn(msg);
        }

        try {
            org.graalvm.polyglot.Value maybeMain = _pythonContext.getBindings("python").getMember("main");
            if (maybeMain != null && maybeMain.canExecute()) {
                maybeMain.execute();
                _logger.info("(main completed)");
                _outReader.inject("(main completed)");
            } else {
                _logger.info("(no 'main' function found)");
            }
            _toolkit.enable();

            synchronized (_signal) {
                _started = DateTime.now();
                _desc = _bindings.desc;
                _signal.notifyAll();
            }
        } catch (PolyglotException pe) {
            msg = "'main' completed with errors: " + pe;
            _logger.warn(msg);
            _errReader.inject(msg);
        }
    }

    /**
     * Clean up old context + toolkit.
     */
    private void cleanupInterpreter() {
        if (_pythonContext != null) {
            try {
                org.graalvm.polyglot.Value cleanupFn =
                        _pythonContext.getBindings("python").getMember("processCleanupFunctions");
                if (cleanupFn != null && cleanupFn.canExecute()) {
                    cleanupFn.execute();
                    _logger.info("Ran '@at_cleanup' function(s).");
                }
            } catch (Exception exc) {
                _logger.warn("Cleanup function call failed", exc);
            }
            _pythonContext.close();
            _pythonContext = null;
            _pythonFunctions.clear();
            _logger.info("(cleanup complete)");
            _outReader.inject("(cleanup complete)");
        }
        if (_toolkit != null) {
            _toolkit.shutdown();
        }
        _callbackQueue = null;
    }

    /**
     * Build up local actions + events + remote actions + remote events + params
     */
    private void applyBindings(Bindings bindings) {
        if (bindings == null) {
            _logger.info("No bindings specified.");
            return;
        }
        int countLocal = bindLocalBindings(bindings.local);
        if (countLocal == 0) {
            _dummyBinding = new NodelServerAction(_name.getOriginalName(), "Dummy", null);
            _dummyBinding.registerAction(arg -> { /* no-op */ });
        }
        bindRemoteBindings(bindings.remote);
        bindParams(bindings.params);
    }

    private int bindLocalBindings(LocalBindings local) {
        if (local == null) {
            _logger.info("No local bindings.");
            return 0;
        }
        int count = 0;
        count += bindLocalActions(local.actions);
        count += bindLocalEvents(local.events);
        return count;
    }

    /**
     * For local actions.
     */
    private int bindLocalActions(Map<SimpleName, Binding> actions) {
        if (actions == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (Entry<SimpleName, Binding> e : actions.entrySet()) {
            SimpleName actName = e.getKey();
            Binding bd = e.getValue();

            final NodelServerAction act = new NodelServerAction(_name.getOriginalName(), actName.getReducedName(), bd);
            act.setThreadingEnvironment(_callbackQueue, null, _emitExceptionHandler);
            act.registerAction(new ActionRequestHandler() {
                @Override
                public void handleActionRequest(Object arg) {
                    addLog(DateTime.now(), LogEntry.Source.local, LogEntry.Type.action, actName, arg);
                    _onLocalActionRequest(actName, arg);
                }
            });
            addLocalAction(act);

            if (sb.length() > 0) sb.append(", ");
            sb.append("local_action_" + actName.getReducedName());
        }
        if (sb.length() > 0) {
            _logger.info("Local actions: {}", sb);
        }
        return actions.size();
    }

    private int bindLocalEvents(Map<SimpleName, Binding> events) {
        if (events == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (Entry<SimpleName, Binding> e : events.entrySet()) {
            SimpleName evtName = e.getKey();
            Binding bd = e.getValue();

            NodelServerEvent evt = new NodelServerEvent(_name.getOriginalName(), evtName.getReducedName(), bd, true);
            evt.setThreadingEnvironment(_callbackQueue, null, _emitExceptionHandler);
            evt.attachMonitor((ts, arg) ->
                    addLog(ts, LogEntry.Source.local, LogEntry.Type.event, evtName, arg)
            );
            addLocalEvent(evt);

            if (sb.length() > 0) sb.append(", ");
            sb.append("local_event_" + evtName.getReducedName());
        }
        if (sb.length() > 0) {
            _logger.info("Local events: {}", sb);
        }
        return events.size();
    }

    /**
     * For local action calls.
     */
    protected Object _onLocalActionRequest(SimpleName name, Object arg) {
        _logger.info("Action requested: {}", name);
        long seqNum = _funcSeqNumber.incrementAndGet();
        String funcName = "local_action_" + name;
        String key = funcName + "_" + seqNum;

        try {
            synchronized (_activeFunctions) {
                _activeFunctions.put(key, System.nanoTime());
            }
            if (_pythonContext == null)
                throw new IllegalStateException("Python not ready.");

            org.graalvm.polyglot.Value fn =
                    _pythonContext.getBindings("python").getMember(funcName);
            if (fn == null || !fn.canExecute()) {
                String msg = "No function named '" + funcName + "'";
                _logger.warn(msg);
                throw new IllegalStateException(msg);
            }
            try {
                if (getFunctionParamCount(fn) == 0) {
                    return fn.execute();
                } else {
                    return fn.execute(arg);
                }
            } catch (PolyglotException pe) {
                String m = "Action call failed - " + pe;
                _logger.info(m);
                _errReader.inject(m);
                throw new RuntimeException(pe);
            }
        } finally {
            synchronized (_activeFunctions) {
                _activeFunctions.remove(key);
            }
        }
    }

    /**
     * Remote bindings
     */
    private void bindRemoteBindings(RemoteBindings remote) {
        if (remote == null) {
            _logger.info("No remote bindings.");
            return;
        }
        bindRemoteActions(remote.actions);
        bindRemoteEvents(remote.events);
    }

    private void bindRemoteActions(Map<SimpleName, NodelActionInfo> actions) {
        if (actions == null) return;
        for (Entry<SimpleName, NodelActionInfo> e : actions.entrySet()) {
            SimpleName alias = e.getKey();
            NodelActionInfo info = e.getValue();
            NodelClientAction ca = new NodelClientAction(
                    alias,
                    !Strings.isBlank(info.node) ? new SimpleName(info.node) : null,
                    !Strings.isBlank(info.action) ? new SimpleName(info.action) : null
            );
            ca.attachMonitor(arg -> {
                if (ca.isUnbound())
                    addLog(DateTime.now(), LogEntry.Source.unbound, LogEntry.Type.action, alias, arg);
                else
                    addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.action, alias, arg);
            });
            ca.attachWiredStatusChanged(st -> {
                _logger.info("Action binding status: {} -> {}", alias.getReducedName(), st);
                addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.actionBinding, alias, st);
            });
            ca.registerActionInterest();
            _remoteActions.put(ca.getName(), ca);
            _logger.info("Mapped remote action '{}'", alias);
        }
    }

    private void bindRemoteEvents(Map<SimpleName, NodelEventInfo> events) {
        if (events == null) return;
        StringBuilder sb = new StringBuilder();
        for (Entry<SimpleName, NodelEventInfo> e : events.entrySet()) {
            SimpleName alias = e.getKey();
            NodelEventInfo ei = e.getValue();
            if (Strings.isBlank(ei.node) || Strings.isBlank(ei.event))
                continue;

            NodelClientEvent ce = new NodelClientEvent(alias, new SimpleName(ei.node), new SimpleName(ei.event));
            ce.setThreadingEnvironment(_callbackQueue, null, _emitExceptionHandler);
            ce.setHandler(new NodelEventHandler() {
                @Override
                public void handleEvent(SimpleName node, SimpleName event, Object arg) {
                    handleRemoteEventArrival(alias, ce, arg);
                }
            });
            ce.addBindingStateHandler(st -> {
                addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.eventBinding, alias, st);
                _logger.info("Event binding status: {} -> {}", alias.getReducedName(), st);
            });
            addRemoteEvent(ce);

            if (sb.length() > 0) sb.append(", ");
            sb.append("remote_event_" + alias.getReducedName());
        }
        if (sb.length() > 0) {
            _logger.info("Remote events: {}", sb);
        }
    }

    private void handleRemoteEventArrival(SimpleName alias, NodelClientEvent evt, Object arg) {
        _logger.info("Remote event arrived: {}", evt.getNodelPoint());
        addLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.event, alias, arg);

        long seqNum = _funcSeqNumber.incrementAndGet();
        String funcName = "remote_event_" + alias;
        String key = funcName + "_" + seqNum;

        synchronized (_activeFunctions) {
            _activeFunctions.put(key, System.nanoTime());
        }
        try {
            if (_pythonContext == null)
                throw new IllegalStateException("Python not ready.");

            org.graalvm.polyglot.Value fn =
                    _pythonContext.getBindings("python").getMember(funcName);
            if (fn == null || !fn.canExecute()) {
                _logger.warn("No function named '{}'", funcName);
                return;
            }
            if (getFunctionParamCount(fn) == 0) {
                fn.execute();
            } else {
                fn.execute(arg);
            }
        } catch (PolyglotException pe) {
            String m = "Remote event handling failed - " + pe;
            _logger.info(m);
            _errReader.inject(m);
            throw new RuntimeException(pe);
        } finally {
            synchronized (_activeFunctions) {
                _activeFunctions.remove(key);
            }
        }
    }

    /**
     * For parameters
     */
    private void bindParams(ParameterBindings params) {
        if (params == null) {
            _logger.info("No parameters.");
            return;
        }
        for (Entry<SimpleName, ParameterBinding> e : params.entrySet()) {
            SimpleName nm = e.getKey();
            ParameterBinding pb = e.getValue();
            Object val = pb.value;
            String paramName = "param_" + nm.getReducedName();
            _parameters.put(nm, new ParameterEntry(nm, val));
            _logger.info("Parameter '{}' = '{}'", paramName, val);
        }
    }

    public class Params {
        @Service(name="schema")
        public Map<String,Object> getSchema() {
            return _bindings.params.asSchema();
        }
        @Service(name="save")
        public void save(@Param(name="value", isMajor=true) ParamValues pvals) throws Exception {
            if (!_busy.tryLock())
                throw new OperationPendingException();
            try {
                _config.paramValues = pvals;
                injectParamValues(_config, _bindings.params);
                saveConfig0(_config);
            } finally {
                _busy.unlock();
            }
        }
        @Value(name="value", treatAsDefaultValue=true)
        public ParamValues getValue() {
            return _config.paramValues;
        }
    }

    private Params _params = new Params();

    @Service(name="params")
    public Params getParams() {
        return _params;
    }

    /**
     * Evaluate Python expression
     */
    @Service(name="eval")
    public Object eval(@Param(name="expr") final String expr, String source) throws Exception {
        if (_pythonContext == null)
            throw new RuntimeException("Interpreter not ready.");

        final long seq = _funcSeqNumber.incrementAndGet();
        final String key = "eval" + (Strings.isBlank(source)?"":"_"+source)+"_"+seq;

        trackFunction(key);
        try {
            Threads.AsyncResult<Object> op = Threads.executeAsync(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    org.graalvm.polyglot.Value result =
                            _pythonContext.eval("python", expr);
                    return result.isNull()? null : result;
                }
            });
            return op.waitForResultOrThrowException();
        } finally {
            untrackFunction(key);
        }
    }

    /**
     * Execute Python code fragment
     */
    @Service(name="exec")
    public void exec(@Param(name="code") final String code, String source) throws Exception {
        if (Strings.isBlank(code))
            throw new IllegalArgumentException("'code' cannot be empty.");
        if (_pythonContext == null)
            throw new RuntimeException("Interpreter not ready.");

        final long seq = _funcSeqNumber.incrementAndGet();
        final String key = "exec" + (Strings.isBlank(source)?"":"_"+source)+"_"+seq;

        trackFunction(key);
        try {
            Threads.AsyncResult<Void> op = Threads.executeAsync(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    _pythonContext.eval("python", code);
                    return null;
                }
            });
            op.waitForResultOrThrowException();
        } finally {
            untrackFunction(key);
        }
    }

    @Service(name="restart")
    public void restart() {
        _logger.info("restart() called");
        _fileModifiedHash = 0; // triggers re-init in monitorConfig
    }

    @Service(name="rename")
    public void rename(SimpleName newName) {
        _nodelHost.renameNode(this, newName);
        _logger.info("Renamed to {}. Will close & restart soon.", newName);
    }

    @Service(name="update")
    public void update(@Param(name="path") String path) {
        if (Strings.isBlank(path))
            throw new RuntimeException("No recipe path provided");
        File base = _nodelHost.recipes().getRecipeFolder(path);
        if (base == null)
            throw new RuntimeException("Recipe not found: " + path);

        Files.updateDir(base, _root);
        _logger.info("Node updated from recipe '{}'", path);
    }

    @Service(name="remove")
    public void remove(@Param(name="confirm") boolean confirm) {
        if (!confirm)
            throw new RuntimeException("'confirm' not set. Doing nothing.");

        close();
        int tries = 5;
        for (; tries>0; tries--) {
            Files.tryFlushDir(_root);
            _root.delete();
            if (!_root.exists()) break;
            Threads.safeWait(_signal, 1000);
        }
        if (tries<=0) {
            throw new RuntimeException("Tried removing node folder but it still exists. Retry manually later.");
        }
        _logger.info("Node deleted.");
    }

    private FilesEndPoint _files = new FilesEndPoint(_root);
    @Service(name="files")
    public FilesEndPoint files() {
        return _files;
    }

    /**
     * Fully close this node.
     */
    public void close() {
        synchronized(_signal) {
            if (_closed) return;
            _closed = true;
            _logger.info("Closing node...");
            cleanupBindings();
            cleanupInterpreter();
            super.close();
        }
    }

    /**
     * Basic function concurrency tracking.
     */
    private void trackFunction(String key) {
        synchronized (_activeFunctions) {
            _activeFunctions.put(key, System.nanoTime());
        }
    }
    private void untrackFunction(String key) {
        synchronized (_activeFunctions) {
            _activeFunctions.remove(key);
        }
    }

    /**
     * Simple param counting approach (always assume 1).
     */
    private int getFunctionParamCount(org.graalvm.polyglot.Value fn) {
        return 1;
    }

    /**
     * Save config to disk.
     */
    private void saveConfig0(NodeConfig config) throws Exception {
        try {
            if (config == null) return;
            String configStr = Serialisation.serialise(config, 4);
            Stream.writeFully(_configFile, configStr);
            _fileModifiedHash = _configFile.lastModified() + _scriptFile.lastModified();
            _logger.info("saveConfig completed.");
        } catch (Exception exc) {
            _logger.warn("saveConfig failed.", exc);
            throw exc;
        }
    }

    public Context getPythonContext() {
        return _pythonContext;
    }

    public void injectError(String source, Throwable th) {
        String msg = source + " - " + th.toString();
        _logger.info(msg);
        _errReader.inject(msg);
    }

    /**
     * Create a new Python context, appending _metaRoot to sys.path.
     */
    private void initializePythonContext() {
        _pythonContext = Context.newBuilder("python")
                .allowAllAccess(true)
                .build();

        // Append the .nodel folder to sys.path so "import nodetoolkit" can find nodetoolkit.py
        String metaPath = _metaRoot.getAbsolutePath().replace("\\", "/");
        String snippet = String.format(
                "import sys\n"
                        + "if '%s' not in sys.path:\n"
                        + "    sys.path.append('%s')\n",
                metaPath, metaPath
        );
        _pythonContext.eval("python", snippet);
    }

}