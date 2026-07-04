package org.nodel.toolkit;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Value;
import org.nodel.json.JSONException;
import org.nodel.json.JSONObject;
import org.nodel.json.JSONTokener;
import org.nodel.reflection.Serialisation;

/**
 * Converts polyglot (guest language) values into plain Java objects so the rest
 * of the framework — serialisation in particular — can treat them like any other
 * host data. Without this, a Python 'str' arriving through an Object-typed
 * parameter serialises via toString() (unquoted) and 'None' dict values are
 * silently dropped.
 */
public class PolyglotValues {

    /**
     * Encodes any value — guest or host — as a JSON string (see toPlainJava).
     * Used by ManagedToolkit.toJson (recipe 'json_encode').
     */
    public static String toJson(Object obj) {
        Object plain = toPlainJava(obj);

        // the serialiser historically emits top-level strings unquoted, which
        // does not survive a decode — quote them properly here
        if (plain instanceof String)
            return JSONObject.quote((String) plain);

        return Serialisation.serialise(plain);
    }

    /**
     * Decodes ANY JSON value — object, array or scalar — into plain Java objects.
     * (Serialisation.coerceFromJSON only accepts top-level JSON objects.)
     * Used by ManagedToolkit.fromJson (recipe 'json_decode').
     */
    public static Object fromJson(String json) {
        try {
            Object parsed = new JSONTokener(json).nextValue();
            return Serialisation.coerce(Object.class, parsed);
        } catch (JSONException exc) {
            throw new RuntimeException("JSON not formatted correctly.", exc);
        }
    }

    /**
     * Recursively converts a value that may originate from a guest language into
     * plain Java objects (String / Boolean / Number / List / Map / null). Host
     * objects and non-polyglot values pass through unchanged.
     */
    public static Object toPlainJava(Object obj) {
        if (obj == null)
            return null;

        return convert(Value.asValue(obj));
    }

    private static Object convert(Value value) {
        if (value.isNull())
            // the JSON layer drops plain nulls from maps; the sentinel serialises
            // as a proper JSON 'null' so None values survive the round-trip
            return JSONObject.NULL;

        if (value.isHostObject())
            return value.asHostObject();

        if (value.isString())
            return value.asString();

        if (value.isBoolean())
            return value.asBoolean();

        if (value.isNumber()) {
            if (value.fitsInInt())
                return value.asInt();
            if (value.fitsInLong())
                return value.asLong();
            if (value.fitsInDouble())
                return value.asDouble();
            if (value.fitsInBigInteger())
                return value.asBigInteger();
            return value.asDouble();
        }

        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            List<Object> list = new ArrayList<>((int) Math.min(size, Integer.MAX_VALUE));
            for (long i = 0; i < size; i++)
                list.add(convert(value.getArrayElement(i)));
            return list;
        }

        if (value.hasHashEntries()) {
            Map<Object, Object> map = new LinkedHashMap<>();
            Value iterator = value.getHashEntriesIterator();
            while (iterator.hasIteratorNextElement()) {
                Value entry = iterator.getIteratorNextElement();
                map.put(convert(entry.getArrayElement(0)), convert(entry.getArrayElement(1)));
            }
            return map;
        }

        // guest object with no natural Java shape — fall back to its string form
        return value.toString();
    }

}
