package org.nodel.jyhost;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * This class is reserved for Python-Java interfacing using GraalVM.
 */
public class PyToolkit {
    
    private static Context pythonContext;
    
    static {
        pythonContext = Context.newBuilder("python")
                              .allowAllAccess(true)
                              .build();
        // Create immutable empty dict using Python's frozendict
        pythonContext.eval("python", "from types import MappingProxyType");
    }
    
    /**
     * A convenient immutable constant for sharing.
     */
    public final static Value EmptyDict = pythonContext.eval("python", "MappingProxyType({})");
    
    /**
     * Cleanup resources
     */
    public static void shutdown() {
        if (pythonContext != null) {
            pythonContext.close();
        }
    }
}
