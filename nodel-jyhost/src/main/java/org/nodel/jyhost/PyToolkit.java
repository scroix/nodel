package org.nodel.jyhost;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * This class provides Python-Java interfacing using GraalVM.
 */
public class PyToolkit {
    
    private static Context pythonContext;
    private static Value emptyDictClass;
    
    static {
        pythonContext = Context.newBuilder("python")
                              .allowAllAccess(true)
                              .build();
                              
        // Get MappingProxyType for immutable dicts
        pythonContext.eval("python", "from types import MappingProxyType");
        emptyDictClass = pythonContext.eval("python", "MappingProxyType");
    }
    
    /**
     * A convenient immutable constant for sharing.
     */
    public final static Value EmptyDict = emptyDictClass.newInstance();
    
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
