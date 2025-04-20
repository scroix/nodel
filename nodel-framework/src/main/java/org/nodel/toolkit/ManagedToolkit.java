package org.nodel.toolkit;

/* 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. 
 */

import java.io.Closeable;
import java.io.IOException;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import org.joda.time.DateTime;
import org.nodel.Handler;
import org.nodel.SimpleName;
import org.nodel.Handler.H0;
import org.nodel.Handler.H1;
import org.nodel.Handler.H2;
import org.nodel.Strings;
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
import org.nodel.host.LogEntry;
import org.nodel.host.BaseNode.ParameterEntry;
import org.nodel.io.Stream;
import org.nodel.net.NodelHTTPClient;
import org.nodel.net.NodelHttpClientProvider;
import org.nodel.reflection.Serialisation;
import org.nodel.threading.CallbackQueue;
import org.nodel.threading.ThreadPool;
import org.nodel.threading.TimerTask;
import org.nodel.threading.Timers;
import org.nodel.toolkit.QuickProcess.FinishedArg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.graalvm.polyglot.HostAccess;

/**
 * A simple toolkit aimed within a managed, shared scripting environment.
 */
public class ManagedToolkit implements AutoCloseable, Closeable {

    /**
     * (logging related)
     */
    private static AtomicLong s_instanceCounter = new AtomicLong();

    /**
     * The shared timers.
     */
    private static Timers s_timers = new Timers("Toolkit");

    /**
     * The shared thread-pool.
     */
    private static ThreadPool s_threadPool = new ThreadPool("Toolkit", 32);
    
    /**
     * (logging related)
     */
    private Logger _logger = LoggerFactory.getLogger(String.format("%s.instance%d", this.getClass().getName(), s_instanceCounter.getAndIncrement()));    

    /**
     * (for locking / synchronisation)
     */
    private final Object _lock = new Object();
    
    /**
     * Keeps track of the number of threads in use.
     */
    private AtomicLong _threadsInUse = new AtomicLong();
    
    /**
     * A callback queue for orderly, predictable handling of callbacks.
     */
    private CallbackQueue _callbackQueue;

    /**
     * The node name for debugging purposes.
     */
    private BaseDynamicNode _node;

    /**
     * Permanently closed.
     */
    private boolean _closed;
    
    /**
     * The console interface
     */
    private Console.Interface _console = Console.NullConsole();

    /**
     * The exception handler, with context
     */
    private Handler.H2<String, Exception> _exceptionHandler;
    
    /**
     * ('exceptionHandler' with context)
     */
    private H1<Exception> _actionExceptionHandler = createExceptionHandlerWithContext("action");
    
    /**
     * ('exceptionHandler' with context)
     */    
    private H1<Exception> _remoteEventExceptionHandler = createExceptionHandlerWithContext("remoteEvent");
    
    /**
     * ('exceptionHandler' with context)
     */
    private H1<Exception> _callDelayedExceptionHandler = createExceptionHandlerWithContext("callDelayed");
    
    /**
     * ('exceptionHandler' with context)
     */
    private H1<Exception> _timerExceptionHandler = createExceptionHandlerWithContext("timer");
    
    /**
     * ('exceptionHandler' with context)
     */
    private H1<Exception> _tcpExceptionHandler = createExceptionHandlerWithContext("tcp");
    
    /**
     * ('exceptionHandler' with context)
     */
    private H1<Exception> _requestQueueExceptionHandler = createExceptionHandlerWithContext("requestQueue");    
    
    /**
     * ('exceptionHandler' with context)
     */
    private H1<Exception> _udpExceptionHandler = createExceptionHandlerWithContext("udp");
    
    /**
     * ('exceptionHandler' with context)
     */
    private H1<Exception> _processExceptionHandler = createExceptionHandlerWithContext("process");
    
    /**
     * ('exceptionHandler' with context)
     */
    private H1<Exception> _emitExceptionHandler = createExceptionHandlerWithContext("emit");    
    
    /**
     * Call from within calling thread, usually sets up the thread-state environment.
     */
    private H0 _threadStateHandler;
    
    /**
     * Returns a custom console or 'Null' console.
     */
    @HostAccess.Export
    public Console.Interface getConsole() {
        return _console;
    }    

    /**
     * (used in '_timerTasks'; may be extended in future)
     */
    private class TimerEntry {

        public TimerTask timerTask;

    }

    /**
     * Holds all the managed delayed calls
     */
    private Set<TimerEntry> _delayCalls = new HashSet<TimerEntry>();
    
    /**
     * All the managed timers.
     */
    private Set<ManagedTimer> _timers = new HashSet<ManagedTimer>();
    
    /**
     * Holds all the TCP connections
     */
    private Set<ManagedTCP> _tcpConnections = new HashSet<ManagedTCP>();
    
    /**
     * Holds all the UDP sockets
     */
    private Set<ManagedUDP> _udpSockets = new HashSet<ManagedUDP>();

    /**
     * Holds all the SSH connections
     */
    private Set<ManagedSSH> _sshConnections = new HashSet<ManagedSSH>();
    
    /**
     * Holds all the long living managed processes
     */
    private Set<ManagedProcess> _processes = new HashSet<ManagedProcess>();
    
    /**
     * Holds all the quick processes
     */
    private Set<QuickProcess> _quickProcesses = new HashSet<QuickProcess>();
    
    /**
     * Nodel actions
     */
    private Set<ManagedNode> _managedNodes = new HashSet<ManagedNode>();
    
    /**
     * Whether or not this toolkit has been enabled (i.e. TCP connections, timers, etc are activated)
     */
    private boolean _enabled;

    /**
     * An atomically incrementing long integer.
     */
    private AtomicLong _sequenceCounter = new AtomicLong(0);

    /**
     * (constructor)
     */
    public ManagedToolkit(BaseDynamicNode node) {
        _node = node;
    }
    
    /**
     * Attaches a custom console.
     */
    @HostAccess.Export
    public ManagedToolkit attachConsole(Console.Interface value) {
        _console = value;
        return this;
    }

    /**
     * An exception-handler when invocations within thread-pools fail.
     */
    @HostAccess.Export
    public ManagedToolkit setExceptionHandler(Handler.H2<String, Exception> handler) {
        _exceptionHandler = handler;
        
        return this;
    }
    
    /**
     * Handler which gets called from the executing threads, usually use to establish thread-state
     * environment.
     */
    @HostAccess.Export
    public ManagedToolkit setThreadStateHandler(H0 handler) {
        _threadStateHandler = handler;
        
        return this;
    }
    
    /**
     * Sets the callback handler for orderly callback handling. 
     */
    @HostAccess.Export
    public ManagedToolkit setCallbackHandler(CallbackQueue handler) {
        _callbackQueue = handler;
        
        return this;
    }    
    
    /**
     * Calls a function (optionally delayed) in an optionally thread-safe way and gets its result or exception asynchronously.
     */
    @HostAccess.Export
    public <T> ManagedToolkit call(final boolean threadSafe, 
                                   final Callable<T> func, 
                                   long delay, 
                                   final H1<T> onComplete, 
                                   final H1<Exception> onError) {
        if (func == null)
            throw new IllegalArgumentException("No function provided.");

        synchronized (_lock) {
            if (_closed)
                throw new IllegalStateException("Node is closed.");

            final TimerEntry entry = new TimerEntry();
            entry.timerTask = s_timers.schedule(s_threadPool, new TimerTask() {

                @Override
                public void run() {
                    _threadsInUse.incrementAndGet();
                    
                    try {
                        // call the thread-state handler to allow thread state initialisation
                        _threadStateHandler.handle();
                    
                        // functions are considered long running

                        T result;
                        if (threadSafe)
                            result = _callbackQueue.handle(func);
                        else
                            result = func.call();

                        // call the 'onComplete' callback if it exists
                        _callbackQueue.handle(onComplete, result, _callDelayedExceptionHandler);

                    } catch (Exception th) {
                        if (onError != null)
                            _callbackQueue.handle(onError, th, _callDelayedExceptionHandler);
                        else
                            // call the global exception handler
                            _callDelayedExceptionHandler.handle(th);

                    } finally {
                        synchronized (_lock) {
                            // doesn't matter if doesn't exist
                            _delayCalls.remove(entry);
                        }
                        
                        _threadsInUse.decrementAndGet();
                    }
                }

            }, delay);

            _delayCalls.add(entry);
        }
        
        return this;
    }
    
    @HostAccess.Export
    public void releaseCalls() {
        synchronized (_lock) {
            // cancel each timer task
            for (TimerEntry entry : _delayCalls) {
                TimerTask timerTask = entry.timerTask;
                if (timerTask != null)
                    timerTask.cancel();
            }
            _delayCalls.clear();
        }
    }
    
    /**
     * Creates a repeating timer.
     */
    @HostAccess.Export
    public ManagedTimer createTimer(H0 func, long delay, long interval, boolean stopped) {
        synchronized (_lock) {
            if (_closed)
                throw new IllegalStateException("Node is closed.");

            // create a timer (will be stopped)
            ManagedTimer timer = new ManagedTimer(func, stopped, _threadStateHandler, s_timers, s_threadPool, _timerExceptionHandler, _callbackQueue);
            
            timer.setDelayAndInterval(delay, interval);
            
            _timers.add(timer);
            
            // if created after normal init, start (if no 'stopped' flag)
            if (_enabled && !stopped)
                timer.start();
            
            // otherwise .start() will be called from 'enable'
            
            return timer;
        }
    }

    @HostAccess.Export
    public void releaseTimers() {
        synchronized (_lock) {
            for (ManagedTimer timer : _timers) {
                Stream.safeClose(timer);
            }
            _timers.clear();
        }
    }
    
    /**
     * Constructs a managed TCP connection.
     */
    @HostAccess.Export
    public ManagedTCP createTCP(String dest,
                                H0 onConnected,
                                H1<String> onReceived, 
                                H1<String> onSent,
                                H0 onDisconnected,
                                H0 onTimeout,
                                String sendDelimiters,
                                String receiveDelimiters,
                                String binaryStartStopFlags) {
        // create a new TCP connection providing this environment's facilities
        ManagedTCP tcp = new ManagedTCP(_node, dest, _threadStateHandler, _tcpExceptionHandler, _callbackQueue, s_threadPool, s_timers);
        
        // set up the callback handlers as provided by the user
        tcp.setConnectedHandler(onConnected);
        tcp.setReceivedHandler(onReceived);
        tcp.setSentHandler(onSent);
        tcp.setDisconnectedHandler(onDisconnected);
        tcp.setTimeoutHandler(onTimeout);
        tcp.setSendDelimeters(sendDelimiters);
        tcp.setReceiveDelimeters(receiveDelimiters);
        tcp.setBinaryStartStopFlags(binaryStartStopFlags);
        
        synchronized(_lock) {
            if (_closed)
                Stream.safeClose(tcp);
            else
                _tcpConnections.add(tcp);
        }
        
        return tcp;
    }
    
    @HostAccess.Export
    public ManagedUDP createUDP(String source,
                                String dest,
                                H0 onReady, 
                                H2<String, String> onReceived, 
                                H1<String> onSent,
                                String intf) {
        ManagedUDP udp = new ManagedUDP(_node, source, dest, _threadStateHandler, _udpExceptionHandler, _callbackQueue, s_threadPool, s_timers);
        
        udp.setReadyHandler(onReady);
        udp.setReceivedHandler(onReceived);
        udp.setSentHandler(onSent);
        udp.setIntf(intf);
        
        synchronized(_lock) {
            if (_closed)
                Stream.safeClose(udp);
            else
                _udpSockets.add(udp);
        }
        
        return udp;        
    }

    /**
     * Constructs a managed SSH connection.
     */
    @HostAccess.Export
    public ManagedSSH createSSH(String dest,
                                H0 onConnected,
                                H1<String> onReceived,
                                H1<String> onSent,
                                H0 onDisconnected,
                                H0 onTimeout,
                                String sendDelimiters,
                                String receiveDelimiters,
                                String username,
                                String password,
                                boolean disableEcho) {
        // create a new TCP connection providing this environment's facilities
        ManagedSSH ssh = new ManagedSSH(_node, dest, _threadStateHandler, _tcpExceptionHandler, _callbackQueue, s_threadPool, s_timers);

        // set up the callback handlers as provided by the user
        ssh.setConnectedHandler(onConnected);
        ssh.setReceivedHandler(onReceived);
        ssh.setSentHandler(onSent);
        ssh.setDisconnectedHandler(onDisconnected);
        ssh.setTimeoutHandler(onTimeout);
        ssh.setSendDelimeters(sendDelimiters);
        ssh.setReceiveDelimiters(receiveDelimiters);

        ssh.setDisableEcho(disableEcho);
        ssh.setUsername(username);
        ssh.setPassword(password);

        synchronized (_lock) {
            if (_closed)
                Stream.safeClose(ssh);
            else
                _sshConnections.add(ssh);
        }

        return ssh;
    }

    /**
     * Constructs a managed OS process.
     */
    @HostAccess.Export
    public ManagedProcess createProcess(List<String> command,
                                H0 onStarted,
                                H1<String> onOut, 
                                H1<String> onIn,
                                H1<String> onErr,
                                H1<Integer> onStopped,
                                H0 onTimeout,
                                String sendDelimiters,
                                String receiveDelimiters,
                                String working,
                                boolean mergestderr,
                                Map<String, String> env) {
        ManagedProcess process = new ManagedProcess(_node, command, _threadStateHandler, _processExceptionHandler, _callbackQueue, s_threadPool, s_timers);
        
        // set up the callback handlers as provided by the user
        process.setStartedHandler(onStarted);
        process.setOutHandler(onOut);
        process.setInHandler(onIn);
        process.setErrHandler(onErr);
        process.setStoppedHandler(onStopped);
        process.setTimeoutHandler(onTimeout);
        
        // set general arguments
        process.setSendDelimeters(sendDelimiters);
        process.setReceiveDelimeters(receiveDelimiters);
        process.setWorking(working);
        process.setMergeError(mergestderr);
        process.setEnv(env);
        
        synchronized(_lock) {
            if (_closed)
                Stream.safeClose(process);
            else
                _processes.add(process);
        }
        
        // if the toolkit has already been initialised, start the process
        if (_enabled) {
            process.init();
        }

        return process;
    }
    
    /**
     * Constructs a managed TCP connection.
     */
    @HostAccess.Export
    public RequestQueue createRequestQueue(
                                H1<Object> onReceived, 
                                H0 onSent,
                                H0 onTimeout) {
        RequestQueue requestQueue = new RequestQueue(_node, _threadStateHandler, _requestQueueExceptionHandler, _callbackQueue, s_threadPool, s_timers);
        
        // set up the callback handlers as provided by the user
        requestQueue.setReceivedHandler(onReceived);
        requestQueue.setSentHandler(onSent);
        requestQueue.setTimeoutHandler(onTimeout);
        
        synchronized(_lock) {
            if (_closed)
                Stream.safeClose(requestQueue);
        }
        
        return requestQueue;
    }    
    
    /**
     * Releases all TCP connections
     */
    @HostAccess.Export
    public void releaseTCPs() {
        synchronized (_lock) {
            // close all connections
            for (ManagedTCP conn : _tcpConnections)
                Stream.safeClose(conn);

            _tcpConnections.clear();
        }
    }
    
    /**
     * Releases all UDP connections
     */
    @HostAccess.Export
    public void releaseUDPs() {
        synchronized (_lock) {
            // close all connections
            for (ManagedUDP socket : _udpSockets)
                Stream.safeClose(socket);

            _udpSockets.clear();
        }
    }
    
    /**
     * Releases all processes
     */
    @HostAccess.Export
    public void releaseProcesses() {
        synchronized (_lock) {
            // close all long living processes (safe copy)
            for (ManagedProcess process : new ArrayList<>(_processes))
                Stream.safeClose(process);

            _processes.clear();
            
            // and quick processes (safe copy)
            for (QuickProcess quickProcess: new ArrayList<>(_quickProcesses))
                Stream.safeClose(quickProcess);
            
            _quickProcesses.clear();
        }
    }    
    
    /**
     * Releases all secure shells
     */
    @HostAccess.Export
    public void releaseSecureShells() {
        synchronized (_lock) {
            // close all secure shells
            for (ManagedSSH ssh : _sshConnections)
                Stream.safeClose(ssh);

            _sshConnections.clear();
        }
    }

    /**
     * Creates a managed node
     */
    @HostAccess.Export
    public ManagedNode createNode(String name) {
        if (Strings.isBlank(name))
            throw new IllegalArgumentException("Name cannot be empty");

        synchronized (_lock) {
            if (_closed)
                throw new IllegalStateException("Node is closed.");

            ManagedNode node = new ManagedNode(new SimpleName(name), _callbackQueue, _threadStateHandler);

            _managedNodes.add(node);

            return node;
        }
    }
    
    /**
     * Creates a managed node (sub node)
     */
    @HostAccess.Export
    public ManagedNode createSubnode(String suffix) {
        if (Strings.isBlank(suffix))
            throw new IllegalArgumentException("Suffix cannot be empty");

        synchronized (_lock) {
            if (_closed)
                throw new IllegalStateException("Node is closed.");

            ManagedNode node = new ManagedNode(new SimpleName(Nodel.reduce(_node.getName().getOriginalName(), true) + " " + suffix), _callbackQueue, _threadStateHandler);

            _managedNodes.add(node);

            return node;
        }
    }
    
    /**
     * Removes a previously created managed node, fully releases all of its resources.
     */
    @HostAccess.Export
    public void releaseNode(ManagedNode node) {
        if (node == null)
            throw new IllegalArgumentException("Node is missing");
        
        synchronized(_lock) {
            node.close();
            
            _managedNodes.remove(node);
        }
    }

    @HostAccess.Export
    public void releaseNodes() {
        // close all managed nodes
        for (ManagedNode node : _managedNodes) {
            node.close();
        }
        _managedNodes.clear();
    }
    
    @HostAccess.Export
    public NodelServerAction createAction(String actionName, final Handler.H1<Object> actionFunction, Binding metadata) {
        synchronized (_lock) {
            if (_closed)
                throw new IllegalStateException("Node is closed.");
            
            final NodelServerAction action = new NodelServerAction(_node.getName(), new SimpleName(Nodel.reduce(actionName)), metadata);
            action.registerAction(new ActionRequestHandler() {

                @Override
                public void handleActionRequest(Object arg) {
                    _threadStateHandler.handle();

                    _node.injectLog(DateTime.now(), LogEntry.Source.local, LogEntry.Type.action, action.getAction(), arg);

                    _callbackQueue.handle(actionFunction, arg, _actionExceptionHandler);
                }

            });

            _node.injectLocalAction(action);
            
            return action;
        }
    }
    
    /**
     * (overloaded - metadata as a map)
     */
    @HostAccess.Export
    public NodelServerAction createAction(String actionName, final Handler.H1<Object> actionFunction, Map<String, Object> metadata) {
        return createAction(actionName, actionFunction, (Binding) Serialisation.coerce(Binding.class, metadata));
    }
    
    @HostAccess.Export
    public void releaseAction(NodelServerAction action) {
        if (action == null)
            throw new IllegalArgumentException("No action provided");
        
        synchronized(_lock) {
            action.close();
            
            _node.extractLocalAction(action);
        }
    }
    
    /**
     * Looks up a Nodel action from this Node.
     */
    @HostAccess.Export
    public NodelServerAction getLocalAction(String name) {
        return _node.getLocalActions().get(new SimpleName(name));
    }
    
    @HostAccess.Export
    public NodelServerEvent createEvent(String eventName, Binding metadata) {
        synchronized (_lock) {
            if (_closed)
                throw new IllegalStateException("Node is closed.");

            NodelServerEvent event = new NodelServerEvent(_node.getName(), new SimpleName(Nodel.reduce(eventName)), metadata);
            event.setThreadingEnvironment(_callbackQueue, _threadStateHandler, _emitExceptionHandler);
            _node.injectLocalEvent(event);

            return event;
        }
    }
    
    @HostAccess.Export
    public NodelServerEvent createEvent(String eventName, Map<String, Object> metadata) {
        return createEvent(eventName, (Binding) Serialisation.coerce(Binding.class, metadata));
    }
    
    @HostAccess.Export
    public void releaseEvent(NodelServerEvent event) {
        if (event == null)
            throw new IllegalArgumentException("No event provided");
        
        synchronized (_lock) {
            event.close();

            _node.extractLocalEvent(event);
        }
    }
    
    /**
     * Creates a remote action.
     */
    @HostAccess.Export
    public NodelClientAction createRemoteAction(String actionName, Map<String, Object> metadata, String suggestedNodeName, String suggestedActionName) {
        return createRemoteAction(actionName, (Binding) Serialisation.coerce(Binding.class, metadata), suggestedNodeName, suggestedActionName);
    }
    
    /**
     * Looks up a Nodel event from this Node.
     */
    @HostAccess.Export
    public NodelServerEvent getLocalEvent(String name) {
        return _node.getLocalEvents().get(new SimpleName(name));
    }    

    /**
     * Creates a remote action.
     */
    @HostAccess.Export
    public NodelClientAction createRemoteAction(String actionName, Binding metadata, String suggestedNodeName, String suggestedActionName) {
        synchronized (_lock) {
            if (_closed)
                throw new IllegalStateException("Node is closed.");
            
            final SimpleName action = new SimpleName(actionName);
            
            SimpleName suggestedNode = (suggestedNodeName != null ? new SimpleName(suggestedNodeName) : null);
            SimpleName suggestedAction = (suggestedActionName != null ? new SimpleName(suggestedActionName) : null);
            
            if (metadata == null)
                metadata = new Binding();
            
            if (Strings.isEmpty(metadata.title)) // 'isEmpty' in case blank is deliberate
                metadata.title = actionName;

            final NodelClientAction clientAction = new NodelClientAction(new SimpleName(actionName), metadata, null, null);
            
            clientAction.attachMonitor(new Handler.H1<Object>() {
                
                @Override
                public void handle(Object arg) {
                    if (clientAction.isUnbound())
                        _node.injectLog(DateTime.now(), LogEntry.Source.unbound, LogEntry.Type.action, action, arg);                    
                    else
                        _node.injectLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.action, action, arg);
                }
                
            });
            clientAction.attachWiredStatusChanged(new Handler.H1<BindingState>() {
                
                @Override
                public void handle(BindingState status) {
                    _logger.info("Action binding status: {} - '{}'", action.getReducedName(), status);
                    
                    _node.injectLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.actionBinding, action, status);
                }
                
            });            
            
            _node.injectRemoteAction(clientAction, suggestedNode, suggestedAction);

            return clientAction;
        }
    }
    
    /**
     * Looks up a Nodel remote action from this Node.
     */
    @HostAccess.Export
    public NodelClientAction getRemoteAction(String name) {
        return _node.getRemoteActions().get(new SimpleName(name));
    }

    /**
     * Creates a remote event.
     */
    @HostAccess.Export
    public NodelClientEvent createRemoteEvent(String eventName, Handler.H1<Object> eventFunction, Map<String, Object> metadata, String suggestedNodeName, String suggestedEventName) {
        return createRemoteEvent(eventName, eventFunction, (Binding) Serialisation.coerce(Binding.class, metadata), suggestedNodeName, suggestedEventName);
    }

    /**
     * Creates a remote action.
     */
    @HostAccess.Export
    public NodelClientEvent createRemoteEvent(String eventName, final Handler.H1<Object> eventFunction, Binding metadata, String suggestedNodeName, String suggestedEventName) {
        synchronized (_lock) {
            if (_closed)
                throw new IllegalStateException("Node is closed.");
            
            final SimpleName event = new SimpleName(eventName);
            
            SimpleName suggestedNode = (suggestedNodeName != null ? new SimpleName(suggestedNodeName) : null);
            SimpleName suggestedEvent = (suggestedEventName != null ? new SimpleName(suggestedEventName) : null);            
            
            if (metadata == null)
                metadata = new Binding();
            
            if (Strings.isEmpty(metadata.title)) // 'isEmpty' in case blank is deliberate
                metadata.title = eventName;

            final NodelClientEvent clientEvent = new NodelClientEvent(new SimpleName(eventName), metadata, null, null);
            clientEvent.setThreadingEnvironment(_callbackQueue, _threadStateHandler, _emitExceptionHandler);
            
            clientEvent.setHandler(new NodelEventHandler() {
                
                @Override
                public void handleEvent(SimpleName node, SimpleName cEvent, Object arg) {
                    _node.injectLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.event, event, arg);
                    
                    _threadStateHandler.handle();

                    _callbackQueue.handle(eventFunction, arg, _remoteEventExceptionHandler);                    
                }
                
            });
            
            clientEvent.addBindingStateHandler(new Handler.H1<BindingState>() {
                
                @Override
                public void handle(BindingState status) {
                    _node.injectLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.eventBinding, event, status);
                    
                    _logger.info("Event binding status: {} - '{}'", event.getReducedName(), status);
                }
                
            });             
            
            _node.injectRemoteEvent(clientEvent, suggestedNode, suggestedEvent);

            return clientEvent;
        }
    }
    
    /**
     * Looks up a Nodel remote event from this Node.
     */
    @HostAccess.Export
    public NodelClientEvent getRemoteEvent(String name) {
        return _node.getRemoteEvents().get(new SimpleName(name));
    }
    
    /**
     * Looks up a parameter value using SimpleName resolution.
     */
    @HostAccess.Export
    public Object lookupParameter(String name) {
        ParameterEntry result = _node.getParameters().get(new SimpleName(name));
        return (result != null ? result.value : null);
    }

    /**
     * Kicks off any resources set up within this toolkit like TCP connections, timers, etc.
     */
    @HostAccess.Export
    public void enable() {
        synchronized (_lock) {
            if (_enabled)
                return;

            _enabled = true;
            
            for(ManagedTimer timer: _timers) {
                // start only those that haven't opted out
                if (!timer.getStoppedAtFirst() && (timer.getDelay() > 0 || timer.getInterval() > 0)) {
                    timer.start();
                }
            }

            for (ManagedTCP tcp : _tcpConnections) {
                tcp.start();
            }

            for (ManagedUDP udp : _udpSockets) {
                udp.start();
            }

            for (ManagedSSH ssh : _sshConnections) {
                ssh.start();
            }

            for (ManagedProcess process : _processes) {
                process.init();
            }
        }
    }
    
    // Safe URL timeouts are optimised for servers that are likely available and responsive.
    
    /**
     * The re-useable client for this toolkit instance.
     * (lazily created)
     */
    private NodelHTTPClient _httpClient;
    
    /**
     * Release the http client if it was created.
     */
    private void releaseHttpClient() {
        if (_httpClient != null) {
            try {
                synchronized (_lock) {
                    _httpClient.close();
                }
                
            } catch (Exception exc) {
                _logger.warn("HTTP client connection manager may not have shutdown cleanly", exc);
            }
        }
    }
    
    /**
     * Lazily gets the HTTP client
     */
    @HostAccess.Export
    public NodelHTTPClient getHttpClient() {
        synchronized(_lock) {
            ensureNotClosed();
            
            // lazily create
            if (_httpClient == null)
                _httpClient = NodelHttpClientProvider.instance().create(); // Fixed method call
                
            return _httpClient;
        }
    }
    
    
    /**
     * A very simple URL getter. queryArgs, contentType, postData are all optional.
     * 
     * Safe timeouts are used to avoid non-responsive servers being able to hold up connections indefinitely.
     */
    @HostAccess.Export
    public String getURL(String urlStr, String method, Map<String, String> query, String username, String password, Map<String, String> headers, String contentType, String post,
            Integer connectTimeout, Integer readTimeout, boolean resultWithHeaders) throws IOException {
        if (_closed)
            throw new IllegalStateException("Node is closed.");

        return getHttpClient().makeSimpleRequest(urlStr, method, query, username, password, headers, contentType, post, connectTimeout, readTimeout);
    }

    /**
     * Permanently cleans up this instance of the toolkit and related
     * resources.
     */
    @HostAccess.Export
    @Override
    public void close() throws IOException {
        synchronized (_lock) {
            if (_closed)
                return;

            _closed = true;

            _logger.info("Closing toolkit.");

            // get snapshots of collections to iterate over since some of the close handlers might affect the original collections
            Set<ManagedTimer> timersSnapshot = new HashSet<>(_timers);
            Set<TimerEntry> delayCallsSnapshot = new HashSet<>(_delayCalls);

            // cancel each timer task
            for (TimerEntry entry : delayCallsSnapshot) {
                TimerTask timerTask = entry.timerTask;
                if (timerTask != null)
                    timerTask.cancel();
            }

            // no longer need these (for GC)
            _delayCalls.clear();

            // close all timers
            for (ManagedTimer timer : timersSnapshot) {
                Stream.safeClose(timer);
            }

            // no longer need these
            _timers.clear();

            // release other managed resources
            releaseCalls();
            releaseTimers();
            releaseTCPs();
            releaseUDPs();
            releaseProcesses();
            releaseSecureShells();
            releaseNodes();
            releaseHttpClient();
        }
    }

    /**
     * Cleans up resources held by this toolkit.
     * @deprecated Use close() instead
     */
    @HostAccess.Export
    @Deprecated
    public void shutdown() throws IOException {
        close();
    }

    /**
     * Encodes simple objects into a JSON string.
     */
    @HostAccess.Export
    public String toJson(Object object) {
        ensureNotClosed();
        return Serialisation.serialise(object);
    }

    /**
     * Decodes JSON strings into plain native objects.
     */
    @HostAccess.Export
    public Object fromJson(String json) {
        ensureNotClosed();
        return Serialisation.deserialise(Object.class, json); // Fixed to include class parameter
    }
    
    /**
     * Returns an atomically incrementing long integer.
     */
    @HostAccess.Export
    public long nextSequenceNumber() {
        return _sequenceCounter.getAndIncrement();
    }
    
    /**
     * Returns a high-resolution atomically incrementing system-clock in millis (can wrap).
     */
    @HostAccess.Export
    public long systemClockInMillis() {
        return System.nanoTime() / 1000000; 
    }
    
    /**
     * Compares two objects using Nodel's value comparison logic.
     */
    @HostAccess.Export
    public boolean areSameValue(Object obj1, Object obj2) {
        ensureNotClosed();
        return org.nodel.reflection.Objects.sameValue(obj1, obj2); // Fixed: using Objects.sameValue
    }

    /**
     * Checks if an object is null or empty.
     */
    @HostAccess.Export
    public boolean isEmpty(Object obj) {
        ensureNotClosed();
        
        if (obj == null)
            return true;
        
        if (obj instanceof String)
            return org.nodel.Strings.isBlank((String)obj);
        
        if (obj instanceof Collection)
            return ((Collection<?>)obj).isEmpty();
        
        if (obj instanceof Map)
            return ((Map<?,?>)obj).isEmpty();
        
        if (obj.getClass().isArray())
            return java.lang.reflect.Array.getLength(obj) == 0;
        
        // For other types, assume non-empty
        return false;
    }
    
    /**
     * 'now' as a DateTime instance.
     */
    @HostAccess.Export
    public DateTime dateNow() {
        return DateTime.now();
    }
    
    /**
     * A DateTime instance from composite date / time values.
     */
    @HostAccess.Export
    public DateTime dateAt(int year, int month, int day, int hour, int minute, int second, int millisecond) {
        return new DateTime(year, month, day, hour, minute, second, millisecond);
    }
    
    /**
     * A DateTime instance from 1970-based millis.
     */
    @HostAccess.Export
    public DateTime dateAtInstant(long millis) {
        return new DateTime(millis);
    }    

    /**
     * Parses a DateTime from the specified string.
     */
    @HostAccess.Export
    public DateTime parseDate(String str) {
        return DateTime.parse(str);
    }

    /**
     * (convenience function)
     */
    private H1<Exception> createExceptionHandlerWithContext(final String context) {
        return new H1<Exception>() {

            @Override
            public void handle(Exception value) {
                Handler.tryHandle(_exceptionHandler, context, value);
            }

        };
    }

    /**
     * Provides access to Java classes for Python scripts.
     * This method is used by the Python import hook to load Java classes dynamically.
     *
     * @param className The fully qualified Java class name
     * @return The Java Class object
     * @throws ClassNotFoundException if the class cannot be found
     */
    @HostAccess.Export
    public Class<?> getClass(String className) throws ClassNotFoundException {
        if (_closed) {
            throw new ClassNotFoundException("Toolkit is closed, cannot load class: " + className);
        }

        // Example context loader logic (replace with actual if different)
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ManagedToolkit.class.getClassLoader();
        }
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }

        try {
            // Security checks might go here if needed
            // ...

            return Class.forName(className, true, loader);

        } catch (ClassNotFoundException e) {
            // Only log at debug level since this is expected for packages
            _logger.debug("Class not found: {}", className);
            throw e;
        }
        // Other potential exceptions like LinkageError might also be relevant
    }

    /**
     * Tests if a Java class is available.
     * Used by Python to check if a class exists before attempting to load it.
     *
     * @param className The fully qualified Java class name
     * @return true if the class exists and is accessible
     */
    @HostAccess.Export
    public boolean hasClass(String className) {
        if (_closed) {
            return false;
        }

        try {
            getClass(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    @HostAccess.Export
    public void init(BaseDynamicNode node, CallbackQueue callbackQueue, Runnable threadStateHandler, H2<String, Exception> exceptionHandler) {
        synchronized(_lock) {
            this._node = node;
            this._callbackQueue = callbackQueue;
            this._threadStateHandler = threadStateHandler != null ? () -> threadStateHandler.run() : null;
            this._exceptionHandler = exceptionHandler;
        }
    }

    @HostAccess.Export
    public void handleActionRequest(String actionName, Object arg, Object handler) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: _node.handleActionRequest(new SimpleName(actionName), arg, handler);
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public void handleEvent(String eventName, Object arg) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API 
        // Original: _node.handleEvent(new SimpleName(eventName), arg);
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }
    
    @HostAccess.Export
    public Object lookup(String name) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: return _node.lookup(new SimpleName(name));
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public Object getParameter(String name) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: return _node.getParameterValue(new SimpleName(name));
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public Map<String, Object> getParameters() {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original:
        // Map<String, Object> result = new HashMap<String, Object>();
        // for (ParameterEntry entry : _node.getParameterValues()) {
        //    result.put(entry.name.getOriginalName(), entry.value);
        // }
        // return result;
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }
    
    @HostAccess.Export
    public Object TCP(Object arg) {
        ensureNotClosed();
        
        // TODO: Fix constructor parameters for GraalVM
        // Original: ManagedTCP tcp = new ManagedTCP(_node, arg, _threadStateHandler, _tcpExceptionHandler, _callbackQueue, s_threadPool, s_timers);
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }
    
    @HostAccess.Export
    public Object UDP(Object arg) {
        ensureNotClosed();
        
        // TODO: Fix constructor parameters for GraalVM
        // Original: ManagedUDP udp = new ManagedUDP(_node, arg, _threadStateHandler, _udpExceptionHandler, _callbackQueue, s_threadPool, s_timers);
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }
    
    @HostAccess.Export
    public Object SSH(Object arg) {
        ensureNotClosed();
        
        // TODO: Fix constructor parameters for GraalVM
        // Original: ManagedSSH ssh = new ManagedSSH(_node, arg, _threadStateHandler, _tcpExceptionHandler, _callbackQueue, s_threadPool, s_timers);
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }
    
    @HostAccess.Export
    public Object Process(Object arg) {
        ensureNotClosed();
        
        // TODO: Fix constructor parameters for GraalVM
        // Original: ManagedProcess process = new ManagedProcess(_node, arg, _threadStateHandler, _processExceptionHandler, _callbackQueue, s_threadPool, s_timers);
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    // Remove both incorrect QuickProcess methods
    
    @HostAccess.Export
    public void setEventState(String eventName, Object state) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API and fix BindingState.create
        // Original: _node.setLocalEventState(new SimpleName(eventName), BindingState.create(state));
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public void raiseEvent(final String eventName, final Object arg) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original:
        // _callbackQueue.post(new Runnable() {
        //    @Override
        //    public void run() {
        //        _node.signalLocalEvent(new SimpleName(eventName), arg);
        //    }
        // });
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public void subscribeToEvent(String eventName, String remoteNode, String remoteEvent) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: _node.subscribeRemoteEvent(new SimpleName(eventName), new SimpleName(remoteNode), new SimpleName(remoteEvent));
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public void unsubscribeFromEvent(String eventName) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: _node.unsubscribeRemoteEvent(new SimpleName(eventName));
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public void bindToAction(String actionName, String remoteNode, String remoteAction) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: _node.bindRemoteAction(new SimpleName(actionName), new SimpleName(remoteNode), new SimpleName(remoteAction));
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public void unbindFromAction(String actionName) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: _node.unbindRemoteAction(new SimpleName(actionName));
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public void callAction(String actionName, Object arg, Object resultHandler, Object errorHandler) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: _node.callRemoteAction(new SimpleName(actionName), arg, createActionRequestHandler(resultHandler, errorHandler));
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public void callAction(String actionName, Object arg) {
        callAction(actionName, arg, null, null);
    }

    @HostAccess.Export
    public Binding getActionInfo(String actionName) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original:
        // NodelServerAction action = _node.getLocalAction(new SimpleName(actionName));
        // return action != null ? action.getMetadata() : null;
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public Binding getEventInfo(String eventName) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original:
        // NodelServerEvent event = _node.getLocalEvent(new SimpleName(eventName));
        // return event != null ? event.getMetadata() : null;
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public Binding getRemoteEventInfo(String eventName) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original:
        // NodelClientEvent event = _node.getRemoteEvent(new SimpleName(eventName));
        // return event != null ? event.getBinding() : null;
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }

    @HostAccess.Export
    public Binding getRemoteActionInfo(String actionName) {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original:
        // NodelClientAction action = _node.getRemoteAction(new SimpleName(actionName));
        // return action != null ? action.getBinding() : null;
        throw new UnsupportedOperationException("Method not implemented for GraalVM");
    }
    
    @HostAccess.Export
    public String getVersion() {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: return _node.getVersion();
        return "GraalVM Version"; // Placeholder
    }
    
    @HostAccess.Export
    public String getNodeName() {
        ensureNotClosed();
        
        // TODO: Replace with appropriate BaseDynamicNode API
        // Original: return _node.getName().toString();
        return "GraalVM Node"; // Placeholder
    }

    @HostAccess.Export
    public Object lookupLocalAction(String name) {
        return getLocalAction(name);
    }

    @HostAccess.Export
    public Object lookupLocalEvent(String name) {
        return getLocalEvent(name);
    }

    @HostAccess.Export
    public Object lookupRemoteAction(String name) {
        return getRemoteAction(name);
    }

    @HostAccess.Export
    public Object lookupRemoteEvent(String name) {
        return getRemoteEvent(name);
    }

    private void ensureNotClosed() {
        if (_closed)
            throw new IllegalStateException("Node is closed.");
    }

    private ActionRequestHandler createActionRequestHandler(final Object resultHandler, final Object errorHandler) {
        return new ActionRequestHandler() {

            @Override
            public void handleActionRequest(Object arg) {
                _threadStateHandler.handle();

                _node.injectLog(DateTime.now(), LogEntry.Source.remote, LogEntry.Type.action, new SimpleName(arg.toString()), arg);

                if (resultHandler != null) {
                    if (resultHandler instanceof H1<?>) {
                        @SuppressWarnings("unchecked") // Safe because of instanceof check above
                        H1<Object> handler = (H1<Object>) resultHandler;
                        _callbackQueue.handle(handler, arg, _actionExceptionHandler);
                    } else {
                        _logger.warn("resultHandler is not an instance of H1<?>: {}", resultHandler.getClass());
                    }
                }
            }

        };
    }
}
