package org.nodel.host;

/**
 * Extended NodelEventInfo implementation for Python bindings
 * that includes a reference to the Python function handler name.
 */
public class PyBindingInfo extends NodelEventInfo {
    
    private String _functionName;
    
    /**
     * Constructor.
     */
    public PyBindingInfo() {
        super();
    }
    
    /**
     * Gets the Python function name.
     */
    public String getFunctionName() {
        return _functionName;
    }
    
    /**
     * Sets the Python function name.
     */
    public void setFunctionName(String functionName) {
        _functionName = functionName;
    }
}
