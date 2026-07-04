"""
Nodel Toolkit for GraalVM Python

This module provides the interface between Python scripts and the Nodel framework.
The Java toolkit is injected as '_toolkit' before this module loads.
"""

# First, verify our toolkit is present
if '_toolkit' not in globals():
    raise RuntimeError("Nodel toolkit not properly initialized. '_toolkit' missing.")

# Set up Java import system first - this enables importing Java classes
import sys
import inspect
from dataclasses import dataclass
from typing import Any, Optional, Callable, Dict, List, Union
from importlib.abc import MetaPathFinder, Loader
from importlib.util import spec_from_loader
from java import type as jtype

class JavaPackage:
    """Represents a Java package"""
    def __init__(self, toolkit, name):
        self._toolkit = toolkit
        self._name = name

        # Add required module attributes
        self.__name__ = name
        self.__package__ = name
        self.__path__ = []  # Empty list indicates it's a package
        self.__loader__ = None

    def __getattr__(self, name):
        fullname = f"{self._name}.{name}"

        # First try loading as a class
        try:
            # Use GraalPy Java interop to get the class
            cls = jtype(fullname)              # returns a Class or raises
        except Exception: # Catch any exception (e.g. if it's a package)
            cls = None

        if cls is None:
             # If not a class or import failed, return a new package
            return JavaPackage(self._toolkit, fullname)
        else:
             # otherwise we have a genuine class
             return cls

class JavaClassLoader(Loader):
    """Loads Java classes and packages"""
    def __init__(self, toolkit, fullname):
        self.toolkit = toolkit
        self.fullname = fullname

    def create_module(self, spec):
        # First check if it's a final class name
        try:
            cls = jtype(self.fullname)              # returns a Class or raises
        except Exception:
            cls = None

        if cls is None:
            # treat as package
            return JavaPackage(self.toolkit, self.fullname)

        # otherwise we have a genuine class
        cls.__name__ = self.fullname
        cls.__package__ = self.fullname.rpartition('.')[0]
        return cls

    def exec_module(self, module):
        # Nothing to execute for Java classes/packages
        pass

class JavaImportFinder(MetaPathFinder):
    """Finds Java packages and classes during import"""
    def __init__(self, toolkit):
        self.toolkit = toolkit
        # Define allowed root packages for security
        self.roots = {'org', 'java', 'com', 'javax'} # Add other roots as needed

    def find_spec(self, fullname, path, target=None):
        parts = fullname.split('.')
        if parts[0] in self.roots:
            # If the root package is allowed, return a spec. 
            # The loader (JavaClassLoader) will handle the actual resolution 
            # and raise an error if the type/package doesn't exist.
            return spec_from_loader(
                 fullname,
                 JavaClassLoader(self.toolkit, fullname)
            )
        return None

# Install the Java import hook immediately
sys.meta_path.insert(0, JavaImportFinder(_toolkit))

# --- Compatibility shim for console module import -----------------
import types, sys

# Get the console from the toolkit for direct use
java_console = _toolkit.getConsole() if hasattr(_toolkit, "getConsole") else None

# Create an enhanced console wrapper that can handle both patterns
class EnhancedConsole:
    def __init__(self, java_console):
        self._java_console = java_console
        # Add self-reference for legacy code pattern
        self.instance = self
    def log(self, message):
        self._java_console.log(str(message))
    def info(self, message):
        self._java_console.info(str(message))
    def warn(self, message):
        self._java_console.warn(str(message))
    def error(self, message):
        self._java_console.error(str(message))

# Create the wrapper instance
console_wrapper = EnhancedConsole(java_console)

# 1. Create a fake module called 'console'
console_mod = types.ModuleType("console")
console_mod.instance = console_wrapper  # Set wrapper as module's 'instance' attribute
sys.modules["console"] = console_mod    # Make 'import console' work

# 2. Set the global 'console' to the same wrapper
console = console_wrapper

# Make the console visible to all modules
import builtins
builtins.console = console_wrapper          # For direct console.info() calls
builtins.console.instance = console_wrapper # For console.instance.info() calls

print("Nodel toolkit loaded - enhanced console bridge established")

# Expose version info if available
VERSION = getattr(_toolkit, 'VERSION', 'unknown')

@dataclass
class EventMetadata:
    """Metadata structure for events"""
    title: Optional[str] = None
    desc: Optional[str] = None
    group: Optional[str] = None
    order: Optional[float] = None
    schema: Optional[Dict] = None

def _process_metadata(metadata: Union[str, Dict, EventMetadata, None]) -> Dict:
    """Convert various metadata formats into a standard dictionary"""
    if metadata is None:
        return {}
    if isinstance(metadata, str):
        return {'title': metadata}
    if isinstance(metadata, EventMetadata):
        return {k: v for k, v in metadata.__dict__.items() if v is not None}
    return metadata

def LocalEvent(metadata: Union[str, Dict, EventMetadata, None] = None) -> Dict:
    """
    Create metadata for a local event.

    Args:
        metadata: Event metadata as string (title), dict, or EventMetadata

    Returns:
        Dict containing processed metadata
    """
    return _process_metadata(metadata)

def RemoteAction(metadata: Union[str, Dict, EventMetadata, None] = None) -> Dict:
    """Create metadata for a remote action"""
    return _process_metadata(metadata)

def Parameter(metadata: Union[str, Dict, EventMetadata, None] = None) -> Dict:
    """Create metadata for a parameter"""
    return _process_metadata(metadata)

# Timer functionality
class Timer:
    """
    A managed timer that can execute functions periodically
    """
    def __init__(self, func: Callable,
                 intervalInSeconds: float,
                 firstDelayInSeconds: float = 0,
                 stopped: bool = False):
        """
        Create a new timer

        Args:
            func: Function to call
            intervalInSeconds: Time between calls
            firstDelayInSeconds: Initial delay before first call
            stopped: Whether to start in stopped state
        """
        self.wrapper = _toolkit.createTimer(
            func,
            int(firstDelayInSeconds * 1000),
            int(intervalInSeconds * 1000),
            stopped
        )

    def setDelayAndInterval(self, delayInSeconds: float, intervalInSeconds: float) -> None:
        """Update both delay and interval"""
        self.wrapper.setDelayAndInterval(
            int(delayInSeconds * 1000),
            int(intervalInSeconds * 1000)
        )

    def setInterval(self, seconds: float) -> None:
        """Set new interval between calls"""
        self.wrapper.setInterval(int(seconds * 1000))

    def setDelay(self, seconds: float) -> None:
        """Set new initial delay"""
        self.wrapper.setDelay(int(seconds * 1000))

    def getDelay(self) -> float:
        """Current delay in seconds"""
        return self.wrapper.getDelay() / 1000.0

    def getInterval(self) -> float:
        """Current interval in seconds"""
        return self.wrapper.getInterval() / 1000.0

    def isStarted(self) -> bool:
        """Whether timer is running"""
        return self.wrapper.isStarted()

    def isStopped(self) -> bool:
        """Whether timer is stopped"""
        return self.wrapper.isStopped()

    def reset(self) -> None:
        """Reset the timer"""
        self.wrapper.reset()

    def start(self) -> None:
        """Start the timer"""
        self.wrapper.start()

    def stop(self) -> None:
        """Stop the timer"""
        self.wrapper.stop()

# Action/Event Creation Functions
def create_local_action(name: str, handler: Callable, metadata: Any = None):
    """Create a local action that other nodes can call"""
    return _toolkit.createAction(name, handler, _process_metadata(metadata))

def create_local_event(name: str, metadata: Any = None):
    """Create a local event that can be emitted"""
    return _toolkit.createEvent(name, _process_metadata(metadata))

def Signal(name: str, metadata: Any = None):
    """Creates a local signal (on-the-fly). RESERVED FOR FUTURE DIFFERENTIATION FROM EVENT"""
    return create_local_event(name, metadata)

def Event(name: str, metadata: Any = None):
    """DEPRECATED - use 'create_local_event' or '@local_event' (see Signal)"""
    return create_local_event(name, metadata)

def Action(name: str, handler: Callable, metadata: Any = None):
    """DEPRECATED - use 'create_local_action' or '@local_action'"""
    return create_local_action(name, handler, metadata)

def create_remote_action(name: str, metadata: Any = None,
                        suggestedNode: str = None, suggestedAction: str = None):
    """Create a remote action that calls another node"""
    return _toolkit.createRemoteAction(name, _process_metadata(metadata),
                                     suggestedNode, suggestedAction)

def create_remote_event(name: str, handler: Callable, metadata: Any = None,
                       suggestedNode: str = None, suggestedEvent: str = None):
    """Create a remote event that listens to another node"""
    return _toolkit.createRemoteEvent(name, handler, _process_metadata(metadata),
                                    suggestedNode, suggestedEvent)

# Decorator Support
def _as_unary(handler: Callable) -> Callable:
    """Adapts a zero-arg handler to the single-arg form the toolkit expects"""
    if len(inspect.signature(handler).parameters) == 0:
        return lambda arg: handler()
    return handler

def local_action(metadata: Any = None):
    """Decorator to create a local action"""
    def decorator(handler: Callable):
        return create_local_action(handler.__name__, _as_unary(handler), metadata)
    return decorator

def remote_event(metadata: Any = None, suggestedNode: str = None,
                suggestedEvent: str = None):
    """Decorator to create a remote event handler"""
    def decorator(handler: Callable):
        return create_remote_event(handler.__name__, _as_unary(handler), metadata,
                                   suggestedNode, suggestedEvent)
    return decorator

# Network Functions
def TCP(dest=None, connected=None, received=None, sent=None,
        disconnected=None, timeout=None, sendDelimiters='\n',
        receiveDelimiters='\r\n', binaryStartStopFlags=None):
    """Create a managed TCP connection that attempts to stay open"""
    return _toolkit.createTCP(dest, connected, received, sent, disconnected,
                            timeout, sendDelimiters, receiveDelimiters,
                            binaryStartStopFlags)

def SSH(dest=None, connected=None, received=None, sent=None,
        disconnected=None, timeout=None, sendDelimiters='\n',
        receiveDelimiters='\r\n', username=None, password=None, echoDisabled=False):
    """Create a managed SSH connection ('shell' mode) for executing commands"""
    return _toolkit.createSSH(dest, connected, received, sent, disconnected,
                            timeout, sendDelimiters, receiveDelimiters,
                            username, password, echoDisabled)

def UDP(source: str = '0.0.0.0:0',
        dest: Optional[str] = None,
        ready: Optional[Callable] = None,
        received: Optional[Callable] = None,
        sent: Optional[Callable] = None,
        intf: Optional[str] = None):
    """Create a managed UDP connection"""
    return _toolkit.createUDP(source, dest, ready, received, sent, intf)

# Process Functions
def Process(command,            # the command line and arguments (list)
            # callbacks
            started=None,       # every time the process is started
            stdout=None,        # stdout handler
            stdin=None,         # feedback when .send is called (for convenience)
            stderr=None,        # stderr handler
            stopped=None,       # when the process stops / is stopped
            timeout=None,       # timeout when a request is issued but no response
            # arguments
            sendDelimiters='\n', receiveDelimiters='\r\n', # default delimiters
            working=None,       # working directory
            mergeErr=False,     # merge stderr into the stdout for convenience
            env=None):          # add/set environment variables (dict)
    """Create a managed OS process that attempts to stay executing"""
    return _toolkit.createProcess(command,
                                started, stdout, stdin, stderr, stopped, timeout,
                                sendDelimiters, receiveDelimiters,
                                working, mergeErr, env)

def quick_process(command,
                  stdinPush=None,     # text to push to stdin
                  started=None,       # a callback where arg is OS process ID
                  finished=None,      # single callback argument with these properties:
                                      #   'code': the exit code (or None if timed out)
                                      #   'stdout': the complete stdout capture
                                      #   'stderr': the complete stderr capture (if not merged)
                  timeoutInSeconds=0, # if positive, kills the process on timeout
                  working=None,       # the working directory
                  mergeErr=False,     # merge stderr into the stdout for convenience
                  env=None):          # add/set environment variables (dict)
    """Create a short-living process (still managed)"""
    return _toolkit.createQuickProcess(command, stdinPush,
                                     started, finished,
                                     int(timeoutInSeconds * 1000), working, mergeErr, env)

def request_queue(received=None, sent=None, timeout=None):
    """Create a safe request queue for mixing asynchronous and synchronous programming, e.g.

    queue = request_queue()

    def udp_received(source, data):
        queue.handle((source, data))

    queue.request(lambda: udp.send('?'), lambda arg: console.info('RECV UDP %s' % arg))
    """
    return _toolkit.createRequestQueue(received, sent, timeout)

# Utility Functions
def json_encode(obj: Any) -> str:
    """Encode object as JSON string"""
    return _toolkit.toJson(obj)

def json_decode(json_str: str) -> Any:
    """Decode JSON string to object"""
    return _toolkit.fromJson(json_str)

def same_value(obj1: Any, obj2: Any) -> bool:
    """Deep comparison of two values"""
    return _toolkit.sameValue(obj1, obj2)

def is_empty(obj: Any) -> bool:
    """Check if object is empty"""
    return obj is None or len(obj) == 0

def call(func: Callable, delay: float = 0,
         complete: Optional[Callable] = None,
         error: Optional[Callable] = None) -> None:
    """Schedule a function call"""
    _toolkit.call(False, func, int(delay * 1000), complete, error)

def call_safe(func: Callable, delay: float = 0,
              complete: Optional[Callable] = None,
              error: Optional[Callable] = None) -> None:
    """Schedule a thread-safe function call"""
    _toolkit.call(True, func, int(delay * 1000), complete, error)

def call_delayed(delay: float, func: Callable,
                 complete: Optional[Callable] = None,
                 error: Optional[Callable] = None) -> None:
    """DEPRECATED (use 'call' and optional args)"""
    call(func, delay, complete, error)

def next_seq() -> int:
    """Returns an atomically incrementing long integer"""
    return _toolkit.nextSequenceNumber()

def system_clock() -> int:
    """Returns a high-precision atomically incrementing clock in milliseconds"""
    return _toolkit.systemClockInMillis()

# Note: for DateTime functions:
#
#   now = date_now()
#   now2 = date_at(now.getYear(), now.getMonthOfYear(), now.getDayOfMonth(), now.getHourOfDay(), now.getMinuteOfHour(), now.getSecondOfMinute(), now.getMillisOfSecond())
#
#   now == now2 (is True)
#
# (for instant.toString(pattern), see http://www.joda.org/joda-time/apidocs/org/joda/time/format/DateTimeFormat.html)

def date_now():
    """'now' timestamp (based on excellent JODATIME library)"""
    return _toolkit.dateNow()

def date_at(year, month, day, hour, minute, second=0, millisecond=0):
    """a timestamp at another time (based on excellent JODATIME library)"""
    return _toolkit.dateAt(year, month, day, hour, minute, second, millisecond)

def date_instant(millis):
    """a timestamp based on a millisecond offset (JODATIME library)"""
    return _toolkit.dateAtInstant(int(millis))

def date_parse(s):
    """parses a date string e.g. '2016-06-13T08:17:11.836-04:00'"""
    return _toolkit.parseDate(s)

# Simple URL retriever (supports POST) where 'query' and 'headers' are dictionaries.
# If 'fullResponse', result is an object which includes 'statusCode', 'reason', 'content'
# and attributes made up of the response HTTP headers
def get_url(url, method=None, query=None, username=None, password=None, headers=None,
            contentType=None, post=None, connectTimeout=10, readTimeout=15, fullResponse=False):
    if fullResponse:
        return _toolkit.getHttpClient().makeRequest(url, method, query, username, password, headers,
                                                    contentType, post, int(connectTimeout*1000), int(readTimeout*1000))
    else:
        return _toolkit.getHttpClient().makeSimpleRequest(url, method, query, username, password, headers,
                                                          contentType, post, int(connectTimeout*1000), int(readTimeout*1000))

def getURL(url, method=None, query=None, username=None, password=None, headers=None,
           contentType=None, post=None, connectTimeout=10, readTimeout=15):
    """DEPRECATED (same as get_url)"""
    return get_url(url, method, query, username, password, headers,
                   contentType, post, connectTimeout, readTimeout)

# Node creation (on-the-fly)
def Node(nodeName):
    """Create a node (on-the-fly)"""
    return _toolkit.createNode(nodeName)

def Subnode(baseName):
    """Creates a node based on the name of an existing node (on-the-fly)"""
    return _toolkit.createSubnode(baseName)

def release_node(node):
    """Releases a node created with Node() or Subnode() and related resources"""
    return _toolkit.releaseNode(node)

def releaseNode(node):
    """DEPRECATED (see release_node)"""
    return _toolkit.releaseNode(node)

# Node Management
_nodel_before_main_functions: List[Callable] = []
_nodel_after_main_functions: List[Callable] = []
_nodel_cleanup_functions: List[Callable] = []

def before_main(func: Callable) -> Callable:
    """Register function to run before main"""
    _nodel_before_main_functions.append(func)
    return func

def after_main(func: Callable) -> Callable:
    """Register function to run after main"""
    _nodel_after_main_functions.append(func)
    return func

def at_cleanup(func: Callable) -> Callable:
    """Register function to run during cleanup"""
    _nodel_cleanup_functions.append(func)
    return func

def process_before_main_functions() -> int:
    """Execute all before_main functions"""
    for func in _nodel_before_main_functions:
        func()
    return len(_nodel_before_main_functions)

def process_after_main_functions() -> int:
    """Execute all after_main functions"""
    for func in _nodel_after_main_functions:
        func()
    return len(_nodel_after_main_functions)

def process_cleanup_functions() -> int:
    """Execute all cleanup functions"""
    count = 0
    for func in _nodel_cleanup_functions:
        try:
            func()
            count += 1
        except Exception as e:
            console.warn(f"Cleanup function failed: {e}")
    return count

# --- Lookup Functions ---
def lookup_local_action(name):
    """Find a local action by name."""
    return _toolkit.lookupLocalAction(name)

def lookup_local_event(name):
    """Find a local event by name."""
    return _toolkit.lookupLocalEvent(name)

def lookup_remote_action(name):
    """Find a remote action by name."""
    return _toolkit.lookupRemoteAction(name)

def lookup_remote_event(name):
    """Find a remote event by name."""
    return _toolkit.lookupRemoteEvent(name)

def lookup_parameter(name):
    """Looks up a parameter by simple name."""
    return _toolkit.lookupParameter(name)

# --- Convenience constants / string helpers ---

# a convenient immutable empty constant that can be used against most objects
# (arrays, dicts, sets, strings, etc.)
# (created Python-side: sharing PyToolkit.EmptyDict across GraalVM contexts is unsafe)
EMPTY = types.MappingProxyType({})

def is_blank(s) -> bool:
    """Returns true if a string is blank (None, empty or all white-space incl. tab, CR, LN)"""
    # implemented natively; 2.x imported org.nodel.Strings.isBlank but 'from <class> import
    # <static method>' is not supported by the Java import hook
    return s is None or len(str(s).strip()) == 0

# Make commonly used items available at module level
__all__ = [
    'console',
    'Timer',
    'LocalEvent',
    'RemoteAction',
    'Parameter',
    'create_local_action',
    'create_local_event',
    'create_remote_action',
    'create_remote_event',
    'local_action',
    'remote_event',
    'Signal',
    'Event',
    'Action',
    'TCP',
    'UDP',
    'SSH',
    'Process',
    'quick_process',
    'request_queue',
    'call',
    'call_safe',
    'call_delayed',
    'next_seq',
    'system_clock',
    'date_now',
    'date_at',
    'date_instant',
    'date_parse',
    'get_url',
    'getURL',
    'Node',
    'Subnode',
    'release_node',
    'releaseNode',
    'json_encode',
    'json_decode',
    'same_value',
    'is_empty',
    'is_blank',
    'EMPTY',
    'before_main',
    'after_main',
    'at_cleanup',
    'lookup_local_action',
    'lookup_local_event',
    'lookup_remote_action',
    'lookup_remote_event',
    'lookup_parameter',
]