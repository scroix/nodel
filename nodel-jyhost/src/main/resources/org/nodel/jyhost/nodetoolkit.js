/*
 * Nodel Toolkit for GraalVM JavaScript (GraalJS)
 *
 * This script provides the interface between JavaScript node recipes
 * ('script.js') and the Nodel framework. It is the JS counterpart of
 * nodetoolkit.py — the authoring surface is kept conceptually parallel so
 * the same concepts (actions, events, parameters, timers) carry across.
 *
 * The Java toolkit is injected as the global '_toolkit' before this runs.
 *
 * Binding conventions (discovered from the global scope, same as Python):
 *   var local_event_Status  = LocalEvent({ ... });    // + local_event_Status.emit(arg)
 *   var remote_action_Power = RemoteAction({ ... });  // + remote_action_Power.call(arg)
 *   var param_IPAddress     = Parameter({ ... });     // populated before main()
 *   function local_action_Refresh(arg) { ... }        // callable action
 *   function remote_event_PeerStatus(arg) { ... }     // remote event handler
 *
 * NOTE: use 'var' or 'function' for these top-level declarations — 'let' and
 * 'const' are scoped to the script, not the global object, so the host
 * cannot discover (or later replace) them.
 */

(function () {
    if (typeof _toolkit === 'undefined')
        throw new Error("Nodel toolkit not properly initialized. '_toolkit' missing.");
})();

// --- console -------------------------------------------------------------
// Replace GraalJS's built-in console with the node's console so that
// info/warn/error land on the right console streams (parity with Python).
var console = (function (javaConsole) {
    var wrapper = {
        log:   function (msg) { javaConsole.log(String(msg)); },
        info:  function (msg) { javaConsole.info(String(msg)); },
        warn:  function (msg) { javaConsole.warn(String(msg)); },
        error: function (msg) { javaConsole.error(String(msg)); }
    };
    // legacy 'console.instance' pattern (see Python shim)
    wrapper.instance = wrapper;
    return wrapper;
})(_toolkit.getConsole());

// --- binding metadata helpers ---------------------------------------------
function _processMetadata(metadata) {
    if (metadata === undefined || metadata === null)
        return {};
    if (typeof metadata === 'string')
        return { title: metadata };
    return metadata;
}

/** Metadata for a local event placeholder (replaced with the live event object after startup). */
function LocalEvent(metadata) { return _processMetadata(metadata); }

/** Metadata for a remote action placeholder (replaced with the live action object after startup). */
function RemoteAction(metadata) { return _processMetadata(metadata); }

/** Metadata for a parameter placeholder (replaced with the saved value before main()). */
function Parameter(metadata) { return _processMetadata(metadata); }

// --- programmatic action/event creation (parallels Python's decorators) ----

/** Create a local action that other nodes can call (like @local_action). */
function createLocalAction(name, handler, metadata) {
    return _toolkit.createAction(name, function (arg) { return handler(arg); }, _processMetadata(metadata));
}

/** Create a local event that can be emitted. */
function createLocalEvent(name, metadata) {
    return _toolkit.createEvent(name, _processMetadata(metadata));
}

/** Create a remote action that calls another node. */
function createRemoteAction(name, metadata, suggestedNode, suggestedAction) {
    return _toolkit.createRemoteAction(name, _processMetadata(metadata),
            suggestedNode || null, suggestedAction || null);
}

/** Create a remote event handler that listens to another node (like @remote_event). */
function createRemoteEvent(name, handler, metadata, suggestedNode, suggestedEvent) {
    return _toolkit.createRemoteEvent(name, function (arg) { handler(arg); }, _processMetadata(metadata),
            suggestedNode || null, suggestedEvent || null);
}

// --- timers ----------------------------------------------------------------

/**
 * A managed timer that executes a function periodically (seconds-based,
 * parity with the Python toolkit's Timer).
 */
function Timer(func, intervalSeconds, firstDelaySeconds, stopped) {
    this.wrapper = _toolkit.createTimer(
        func,
        Math.round((firstDelaySeconds || 0) * 1000),
        Math.round(intervalSeconds * 1000),
        !!stopped
    );
}
Timer.prototype.setDelayAndInterval = function (delaySeconds, intervalSeconds) {
    this.wrapper.setDelayAndInterval(Math.round(delaySeconds * 1000), Math.round(intervalSeconds * 1000));
};
Timer.prototype.setInterval = function (seconds) { this.wrapper.setInterval(Math.round(seconds * 1000)); };
Timer.prototype.setDelay = function (seconds) { this.wrapper.setDelay(Math.round(seconds * 1000)); };
Timer.prototype.reset = function () { this.wrapper.reset(); };
Timer.prototype.start = function () { this.wrapper.start(); };
Timer.prototype.stop = function () { this.wrapper.stop(); };
Timer.prototype.getDelay = function () { return this.wrapper.getDelay() / 1000.0; };
Timer.prototype.getInterval = function () { return this.wrapper.getInterval() / 1000.0; };
Timer.prototype.isStarted = function () { return this.wrapper.isStarted(); };
Timer.prototype.isStopped = function () { return this.wrapper.isStopped(); };

/**
 * Browser-style convenience: run 'func' once after 'delayMillis'.
 * (managed by the node — released on restart/reload)
 */
function setTimeout(func, delayMillis) {
    _toolkit.call(false, func, Math.round(delayMillis || 0), null, null);
}

/**
 * Browser-style convenience: run 'func' every 'intervalMillis'. Returns a
 * Timer (so clearInterval(handle) — or handle.stop() — can stop it).
 */
function setInterval(func, intervalMillis) {
    return new Timer(func, (intervalMillis || 0) / 1000.0, (intervalMillis || 0) / 1000.0, false);
}

/** Stops a timer previously returned by setInterval. */
function clearInterval(handle) {
    if (handle && handle.stop)
        handle.stop();
}

// --- scheduled calls ---------------------------------------------------------

/** Schedule a function call ('delaySeconds' optional; parity with Python's call). */
function call(func, delaySeconds, complete, error) {
    _toolkit.call(false, func, Math.round((delaySeconds || 0) * 1000), complete || null, error || null);
}

/** Schedule a thread-safe (callback-queue serialised) function call. */
function callSafe(func, delaySeconds, complete, error) {
    _toolkit.call(true, func, Math.round((delaySeconds || 0) * 1000), complete || null, error || null);
}

// --- network helpers ---------------------------------------------------------

/**
 * Create a managed TCP connection.
 * Usage: TCP({ dest: 'host:port', connected: fn, received: fn, sent: fn,
 *              disconnected: fn, timeout: fn,
 *              sendDelimiters: '\n', receiveDelimiters: '\r\n' })
 */
function TCP(options) {
    var o = options || {};
    return _toolkit.createTCP(
        o.dest || null,
        o.connected || null,
        o.received || null,
        o.sent || null,
        o.disconnected || null,
        o.timeout || null,
        o.sendDelimiters !== undefined ? o.sendDelimiters : '\n',
        o.receiveDelimiters !== undefined ? o.receiveDelimiters : '\r\n',
        o.binaryStartStopFlags || null
    );
}

/**
 * Create a managed UDP socket.
 * Usage: UDP({ source: '0.0.0.0:0', dest: 'host:port', ready: fn,
 *              received: function (source, data) { ... }, sent: fn, intf: '...' })
 */
function UDP(options) {
    var o = options || {};
    return _toolkit.createUDP(
        o.source !== undefined ? o.source : '0.0.0.0:0',
        o.dest || null,
        o.ready || null,
        o.received || null,
        o.sent || null,
        o.intf || null
    );
}

// --- utilities ----------------------------------------------------------------

/** Encode a value as a JSON string (host-aware; handles live Nodel objects). */
function jsonEncode(obj) { return _toolkit.toJson(obj); }

/** Decode a JSON string into a value. */
function jsonDecode(json) { return _toolkit.fromJson(json); }

/** Deep comparison of two values. */
function sameValue(obj1, obj2) { return _toolkit.sameValue(obj1, obj2); }

/** True if the value is null/undefined, an empty string or an empty array. */
function isEmpty(obj) { return obj === null || obj === undefined || obj.length === 0; }

// --- lookups --------------------------------------------------------------------
function lookupLocalAction(name) { return _toolkit.lookupLocalAction(name); }
function lookupLocalEvent(name) { return _toolkit.lookupLocalEvent(name); }
function lookupRemoteAction(name) { return _toolkit.lookupRemoteAction(name); }
function lookupRemoteEvent(name) { return _toolkit.lookupRemoteEvent(name); }
function lookupParameter(name) { return _toolkit.lookupParameter(name); }

// --- lifecycle hooks -------------------------------------------------------------
// beforeMain/afterMain/atCleanup parallel Python's @before_main/@after_main/
// @at_cleanup; the process_* functions below are invoked by the host and must
// keep their exact (snake_case) names.
var _nodelBeforeMainFunctions = [];
var _nodelAfterMainFunctions = [];
var _nodelCleanupFunctions = [];

/** Register a function to run before main(). */
function beforeMain(func) { _nodelBeforeMainFunctions.push(func); return func; }

/** Register a function to run after main(). */
function afterMain(func) { _nodelAfterMainFunctions.push(func); return func; }

/** Register a function to run at node shutdown/reload. */
function atCleanup(func) { _nodelCleanupFunctions.push(func); return func; }

function process_before_main_functions() {
    _nodelBeforeMainFunctions.forEach(function (func) { func(); });
    return _nodelBeforeMainFunctions.length;
}

function process_after_main_functions() {
    _nodelAfterMainFunctions.forEach(function (func) { func(); });
    return _nodelAfterMainFunctions.length;
}

function process_cleanup_functions() {
    var count = 0;
    _nodelCleanupFunctions.forEach(function (func) {
        try {
            func();
            count++;
        } catch (e) {
            console.warn('Cleanup function failed: ' + e);
        }
    });
    return count;
}

console.log('Nodel toolkit loaded (JavaScript) - console bridge established');
