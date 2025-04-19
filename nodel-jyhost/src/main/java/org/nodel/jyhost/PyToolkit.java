package org.nodel.jyhost;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * This class provides Python-Java interfacing using GraalVM.
 */
public class PyToolkit {
    
    private static Context pythonContext;
    private static Value mappingProxyType;

    /**
     * A convenient immutable constant for sharing.
     */
    public final static Value EmptyDict;

    static {
        pythonContext = Context.newBuilder("python")
                              .allowAllAccess(true)
                              .build();

        // Get MappingProxyType from Python's types module
        pythonContext.eval("python", "from types import MappingProxyType");
        mappingProxyType = pythonContext.eval("python", "MappingProxyType");

        // Create an empty Python dictionary to pass to MappingProxyType
        Value emptyDict = pythonContext.eval("python", "{}");

        // Now create an immutable view of the empty dictionary
        EmptyDict = mappingProxyType.newInstance(emptyDict);
        // Alternative one-liner approach:
        // EmptyDict = pythonContext.eval("python", "MappingProxyType({})");
    }
    
    /**
     * Creates a new Python context with full access
     */
    public static Context createContext() {
        return Context.newBuilder("python")
                     .allowAllAccess(true)
                     .build();
    }
    
    /**
     * Cleanup resources
     */
    public static void shutdown() {
        if (pythonContext != null) {
            pythonContext.close();
        }
    }
}
