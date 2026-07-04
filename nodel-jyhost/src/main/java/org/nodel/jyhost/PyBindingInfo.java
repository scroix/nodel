package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.nodel.host.NodelEventInfo;

/**
 * Extends NodelEventInfo to include the function name of the Python event handler.
 * This is crucial for proper binding extraction with GraalVM bindings.
 */
public class PyBindingInfo extends NodelEventInfo {
    
    /**
     * The name of the Python function that handles this event.
     */
    private final String functionName;
    
    /**
     * Creates a new PyBindingInfo with the given function name.
     * 
     * @param functionName The Python function name (e.g., "remote_event_something")
     */
    public PyBindingInfo(String functionName) {
        this.functionName = functionName;
    }
    
    /**
     * Creates a new PyBindingInfo, copying fields from a base NodelEventInfo and adding the function name.
     * 
     * @param base Base event info to copy fields from
     * @param functionName The Python function name
     */
    public PyBindingInfo(NodelEventInfo base, String functionName) {
        super();
        this.group = base.group;
        this.title = base.title;
        this.desc = base.desc;
        this.caution = base.caution;
        this.order = base.order;
        this.functionName = functionName;
    }
    
    /**
     * Gets the Python function name for this binding.
     */
    public String getFunctionName() {
        return functionName;
    }
}
