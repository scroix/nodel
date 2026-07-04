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
                 interval_seconds: float,
                 first_delay_seconds: float = 0,
                 stopped: bool = False):
        """
        Create a new timer

        Args:
            func: Function to call
            interval_seconds: Time between calls
            first_delay_seconds: Initial delay before first call
            stopped: Whether to start in stopped state
        """
        self.wrapper = _toolkit.createTimer(
            func,
            int(first_delay_seconds * 1000),
            int(interval_seconds * 1000),
            stopped
        )

    def set_delay_and_interval(self, delay_seconds: float, interval_seconds: float) -> None:
        """Update both delay and interval"""
        self.wrapper.setDelayAndInterval(
            int(delay_seconds * 1000),
            int(interval_seconds * 1000)
        )

    def set_interval(self, seconds: float) -> None:
        """Set new interval between calls"""
        self.wrapper.setInterval(int(seconds * 1000))

    def set_delay(self, seconds: float) -> None:
        """Set new initial delay"""
        self.wrapper.setDelay(int(seconds * 1000))

    def reset(self) -> None:
        """Reset the timer"""
        self.wrapper.reset()

    def start(self) -> None:
        """Start the timer"""
        self.wrapper.start()

    def stop(self) -> None:
        """Stop the timer"""
        self.wrapper.stop()

    @property
    def delay(self) -> float:
        """Current delay in seconds"""
        return self.wrapper.getDelay() / 1000.0

    @property
    def interval(self) -> float:
        """Current interval in seconds"""
        return self.wrapper.getInterval() / 1000.0

    @property
    def is_started(self) -> bool:
        """Whether timer is running"""
        return self.wrapper.isStarted()

    @property
    def is_stopped(self) -> bool:
        """Whether timer is stopped"""
        return self.wrapper.isStopped()

# Action/Event Creation Functions
def create_local_action(name: str, handler: Callable, metadata: Any = None):
    """Create a local action that other nodes can call"""
    return _toolkit.createAction(name, handler, _process_metadata(metadata))

def create_local_event(name: str, metadata: Any = None):
    """Create a local event that can be emitted"""
    return _toolkit.createEvent(name, _process_metadata(metadata))

def create_remote_action(name: str, metadata: Any = None,
                        suggested_node: str = None, suggested_action: str = None):
    """Create a remote action that calls another node"""
    return _toolkit.createRemoteAction(name, _process_metadata(metadata),
                                     suggested_node, suggested_action)

def create_remote_event(name: str, handler: Callable, metadata: Any = None,
                       suggested_node: str = None, suggested_event: str = None):
    """Create a remote event that listens to another node"""
    return _toolkit.createRemoteEvent(name, handler, _process_metadata(metadata),
                                    suggested_node, suggested_event)

# Decorator Support
def local_action(metadata: Any = None):
    """Decorator to create a local action"""
    def decorator(handler: Callable):
        sig = inspect.signature(handler)
        if len(sig.parameters) == 0:
            return _toolkit.createAction(
                handler.__name__,
                lambda arg: handler(),
                _process_metadata(metadata)
            )
        return _toolkit.createAction(
            handler.__name__,
            handler,
            _process_metadata(metadata)
        )
    return decorator

def remote_event(metadata: Any = None, suggested_node: str = None,
                suggested_event: str = None):
    """Decorator to create a remote event handler"""
    def decorator(handler: Callable):
        sig = inspect.signature(handler)
        if len(sig.parameters) == 0:
            return _toolkit.createRemoteEvent(
                handler.__name__,
                lambda arg: handler(),
                _process_metadata(metadata),
                suggested_node,
                suggested_event
            )
        return _toolkit.createRemoteEvent(
            handler.__name__,
            handler,
            _process_metadata(metadata),
            suggested_node,
            suggested_event
        )
    return decorator

# Network Functions
def TCP(*args, dest=None, connected=None, received=None, sent=None,
        disconnected=None, timeout=None, send_delimiters='\n',
        receive_delimiters='\r\n', binary_start_stop_flags=None):
    """Create a managed TCP connection"""
    # Handle both positional and keyword arguments
    if args:
        dest = args[0] if len(args) > 0 else dest

    return _toolkit.createTCP(dest, connected, received, sent, disconnected,
                            timeout, send_delimiters, receive_delimiters,
                            binary_start_stop_flags)

def UDP(source: str = '0.0.0.0:0',
        dest: Optional[str] = None,
        ready: Optional[Callable] = None,
        received: Optional[Callable] = None,
        sent: Optional[Callable] = None,
        intf: Optional[str] = None):
    """Create a managed UDP connection"""
    return _toolkit.createUDP(source, dest, ready, received, sent, intf)

# Utility Functions
def json_encode(obj: Any) -> str:
    """Encode object as JSON string"""
    return _toolkit.toJson(obj)

def json_decode(json_str: str) -> Any:
    """Decode JSON string to object"""
    return _toolkit.fromJson(json_str)

def same_value(obj1: Any, obj2: Any) -> bool:
    """Deep comparison of two values"""
    return _toolkit.areSameValue(obj1, obj2)

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
    if not _toolkit:
        raise RuntimeError("Toolkit not properly initialized")
    return _toolkit.getLocalAction(name)

def lookup_local_event(name):
    """Find a local event by name."""
    if not _toolkit:
        raise RuntimeError("Toolkit not properly initialized")
    return _toolkit.getLocalEvent(name)

def lookup_remote_action(name):
    """Find a remote action by name."""
    if not _toolkit:
        raise RuntimeError("Toolkit not properly initialized")
    return _toolkit.getRemoteAction(name)

def lookup_remote_event(name):
    """Find a remote event by name."""
    if not _toolkit:
        raise RuntimeError("Toolkit not properly initialized")
    return _toolkit.getRemoteEvent(name)

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
    'TCP',
    'UDP',
    'call',
    'call_safe',
    'json_encode',
    'json_decode',
    'same_value',
    'is_empty',
    'before_main',
    'after_main',
    'at_cleanup',
    'lookup_local_action',
    'lookup_local_event',
    'lookup_remote_action',
    'lookup_remote_event',
]