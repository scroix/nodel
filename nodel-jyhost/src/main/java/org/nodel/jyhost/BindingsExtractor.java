package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.nodel.SimpleName;
import org.nodel.host.Binding;
import org.nodel.host.Bindings;
import org.nodel.host.LocalBindings;
import org.nodel.host.NodelActionInfo;
import org.nodel.host.NodelEventInfo;
import org.nodel.host.ParameterBinding;
import org.nodel.host.ParameterBindings;
import org.nodel.host.RemoteBindings;
import org.nodel.host.PyBindingInfo;
import org.nodel.reflection.Serialisation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.graalvm.polyglot.Value;

/**
 * Extracts Nodel bindings from a GraalVM Python context (or "globals" Value).
 */
public class BindingsExtractor {

    /**
     * (logging)
     */
    private static Logger s_logger = LoggerFactory.getLogger(BindingsExtractor.class.getName());

    /**
     * Examines a GraalVM Python "globals" {@link Value}, extracting the parts that form the
     * bindings, and returns any warnings.
     *
     * @param pythonGlobals  the top-level Python bindings from GraalVM (e.g. {@code context.getBindings("python")})
     * @param outWarnings    a list to be populated with any warnings about malformed bindings
     *
     * @return a {@link Bindings} object containing the extracted definitions
     */
    public static Bindings extract(Value pythonGlobals, List<String> outWarnings) {
        // Prepare data structures
        Map<SimpleName, Binding> localActions  = new LinkedHashMap<>();
        Map<SimpleName, Binding> localEvents   = new LinkedHashMap<>();
        Map<SimpleName, Binding> remoteActions = new LinkedHashMap<>();
        Map<SimpleName, Binding> remoteEvents  = new LinkedHashMap<>();
        Map<SimpleName, Binding> paramValues   = new LinkedHashMap<>();

        String desc = null; // for the __doc__ / description

        // Gather all top-level variable names (keys) from Python
        List<String> localsList = new ArrayList<>(pythonGlobals.getMemberKeys());

        // Sort them alphabetically (to mimic Jython's "HashMap -> sorted" approach)
        Collections.sort(localsList, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return o1.compareTo(o2);
            }
        });

        // Attempt to extract __doc__ if present
        // (This is optional. If your code doesn't rely on a docstring for the entire script, you can skip this.)
        if (pythonGlobals.hasMember("__doc__")) {
            Value docVal = pythonGlobals.getMember("__doc__");
            if (docVal != null && docVal.isString()) {
                // If not None / null, store as the node description
                desc = docVal.asString();
            }
        }

        // Now iterate through all top-level keys
        for (String key : localsList) {
            // skip the special __doc__ (already handled)
            if ("__doc__".equals(key)) {
                continue;
            }

            Value value = pythonGlobals.getMember(key);

            // Check for local_action_
            SimpleName localActionName = testForBinding(key, "local_action_");
            if (localActionName != null) {
                // We treat anything that canExecute() as a function, but we also
                // accept that it might have doc or dictionary-like metadata.
                localActions.put(localActionName, createBinding(localActionName, value, outWarnings));
                continue; // done with this key
            }

            // local_event_
            SimpleName localEventName = testForBinding(key, "local_event_");
            if (localEventName != null) {
                localEvents.put(localEventName, createBinding(localEventName, value, outWarnings));
                continue;
            }

            // remote_event_
            SimpleName remoteEventName = testForBinding(key, "remote_event_");
            if (remoteEventName != null) {
                remoteEvents.put(remoteEventName, createBinding(remoteEventName, value, outWarnings));
                continue;
            }

            // remote_action_
            SimpleName remoteActionName = testForBinding(key, "remote_action_");
            if (remoteActionName != null) {
                remoteActions.put(remoteActionName, createBinding(remoteActionName, value, outWarnings));
                continue;
            }

            // param_
            SimpleName paramName = testForBinding(key, "param_");
            if (paramName != null) {
                paramValues.put(paramName, createBinding(paramName, value, outWarnings));
                continue;
            }
        }

        // Build final Bindings
        Bindings bindings = new Bindings();
        bindings.desc = desc; // docstring at the top level

        // local (actions + events)
        bindings.local = new LocalBindings();
        bindings.local.actions = localActions;
        bindings.local.events  = localEvents;

        // remote (actions + events)
        bindings.remote = new RemoteBindings();
        bindings.remote.actions = new LinkedHashMap<>();
        for (Entry<SimpleName, Binding> entry : remoteActions.entrySet()) {
            Binding binding = entry.getValue();
            NodelActionInfo action = new NodelActionInfo();
            action.group   = binding.group;
            action.title   = binding.title;
            action.desc    = binding.desc;
            action.caution = binding.caution;
            action.order   = binding.order;
            bindings.remote.actions.put(entry.getKey(), action);
        }

        bindings.remote.events = new LinkedHashMap<>();
        for (Entry<SimpleName, Binding> entry : remoteEvents.entrySet()) {
            Binding binding = entry.getValue();
            String functionName = "remote_event_" + entry.getKey().toString();
            
            // Create our custom PyBindingInfo that preserves the function name
            org.nodel.jyhost.PyBindingInfo event = new org.nodel.jyhost.PyBindingInfo(functionName);
            event.group   = binding.group;
            event.title   = binding.title;
            event.desc    = binding.desc;
            event.caution = binding.caution;
            event.order   = binding.order;
            
            bindings.remote.events.put(entry.getKey(), event);
        }

        // parameters
        bindings.params = new ParameterBindings();
        for (Entry<SimpleName, Binding> entry : paramValues.entrySet()) {
            Binding binding = entry.getValue();
            ParameterBinding paramBinding = new ParameterBinding();
            paramBinding.group  = binding.group;
            paramBinding.title  = binding.title;
            paramBinding.desc   = binding.desc;
            paramBinding.order  = binding.order;
            paramBinding.schema = binding.schema;
            bindings.params.put(entry.getKey(), paramBinding);
        }

        return bindings;
    }

    /**
     * Creates a {@link Binding} from a GraalVM Python {@link Value}, trying to handle:
     * - Dictionary-like objects
     * - String-based JSON or title
     * - Null / None
     */
    private static Binding createBinding(SimpleName bindingName, Value definition, List<String> outWarnings) {
        Binding binding = null;
        Exception exc = null;

        try {
            // If the value has members, treat it like a dict
            if (definition.hasMembers()) {
                // Convert to a Java Map to pass into Serialisation
                Map<String, Object> dictMap = toJavaMap(definition);
                binding = (Binding) Serialisation.coerce(
                        Binding.class,
                        dictMap,
                        String.class,
                        Object.class
                );
            }
            else {
                // If it's not a dict, maybe it's None or a simple string
                if (!definition.isNull()) {
                    // If it's a string, it might be JSON or a simple title
                    if (definition.isString()) {
                        String asText = definition.asString();
                        if (!(asText == null || asText.isEmpty())) {
                            if (asText.trim().startsWith("{")) {
                                // Parse as JSON
                                binding = (Binding) Serialisation.coerceFromJSON(Binding.class, asText);
                            } else {
                                // Just treat it as a user-friendly title
                                binding = new Binding();
                                binding.title = asText;
                            }
                        }
                    }
                    // If it's executable (a function), we can check __doc__:
                    else if (definition.canExecute()) {
                        binding = new Binding();
                        // Attempt to read docstring:
                        Value docVal = definition.getMember("__doc__");
                        if (docVal != null && docVal.isString()) {
                            String docStr = docVal.asString();
                            if (docStr.trim().startsWith("{")) {
                                // docstring might be JSON
                                try {
                                    Binding docBinding = (Binding) Serialisation.coerceFromJSON(Binding.class, docStr);
                                    binding = docBinding;
                                } catch (Exception docExc) {
                                    // fallback: store doc as title
                                    binding.title = docStr;
                                }
                            } else {
                                // fallback: store doc as title
                                binding.title = docStr;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            exc = e;
        }

        if (exc != null) {
            // ignore the failure but note a warning
            String warnMessage = "Binding '" + bindingName + "' had invalid meta-data; ignoring - "
                    + compressedErrorMessage(exc);
            outWarnings.add(warnMessage);
            s_logger.warn(warnMessage);
        }

        if (binding == null) {
            // If we couldn't parse anything, default to an empty binding
            binding = new Binding();
        }

        // If no title is set, give it the reduced name
        if (binding.title == null || binding.title.isEmpty()) {
            binding.title = bindingName.getReducedName();
        }

        return binding;
    }

    /**
     * Converts a GraalVM Python Value (that "hasMembers()") into a Java Map.
     * Recursively converts nested structures. Adjust as needed for your environment.
     */
    private static Map<String, Object> toJavaMap(Value val) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!val.hasMembers()) {
            return map; // or throw?
        }
        Set<String> keys = val.getMemberKeys();
        for (String k : keys) {
            Value child = val.getMember(k);

            if (child == null || child.isNull()) {
                map.put(k, null);
            }
            else if (child.hasMembers()) {
                // nested dict or object
                map.put(k, toJavaMap(child));
            }
            else if (child.isString()) {
                map.put(k, child.asString());
            }
            else if (child.isBoolean()) {
                map.put(k, child.asBoolean());
            }
            else if (child.isNumber()) {
                // e.g. integer, double, etc.
                // you could do child.asInt() or child.asDouble()
                map.put(k, child.as(Number.class));
            }
            else if (child.canExecute()) {
                // function or callable - store as a string or skip
                map.put(k, "<function>");
            }
            else {
                // fallback
                map.put(k, child.toString());
            }
        }
        return map;
    }

    /**
     * Helper method to check if a given key matches the prefix pattern
     * (e.g. "local_action_", "remote_action_", etc.)
     *
     * @return a SimpleName if it matches, or null if not
     */
    private static SimpleName testForBinding(String rawName, String prefix) {
        String lowerCaseName = rawName.toLowerCase();

        int index = lowerCaseName.indexOf(prefix);
        if (index < 0 || rawName.length() == prefix.length()) {
            return null;
        }

        // get the substring after the prefix
        String name = rawName.substring(prefix.length());
        return new SimpleName(name);
    }

    /**
     * Creates a compressed error message by including nested causes.
     */
    private static String compressedErrorMessage(Throwable exc) {
        StringBuilder sb = new StringBuilder();
        Throwable cause = exc.getCause();

        if (cause == null) {
            return exc.getMessage();
        } else {
            sb.append(exc.getMessage())
                    .append(" (")
                    .append(compressedErrorMessage(cause))
                    .append(")");
            return sb.toString();
        }
    }

}
