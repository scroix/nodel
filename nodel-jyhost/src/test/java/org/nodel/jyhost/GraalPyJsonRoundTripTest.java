package org.nodel.jyhost;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.nodel.toolkit.PolyglotValues;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the jsonEncode/jsonDecode (toolkit 'toJson'/'fromJson') round-trip
 * for GraalPy values — dict/list/str/int/float/bool/None, nested — without
 * corruption (POLYGLOT_INTEGRATION.md §4.8).
 * <p>
 */
public class GraalPyJsonRoundTripTest {

    /**
     * The exact codec used by ManagedToolkit.toJson / fromJson (the recipe-facing
     * 'json_encode' / 'json_decode'), exercised across the same polyglot boundary
     * a recipe script uses.
     */
    public static class Codec {
        public String encode(Object obj) {
            return PolyglotValues.toJson(obj);
        }

        public Object decode(String json) {
            return PolyglotValues.fromJson(json);
        }
    }

    private static Context context;

    @BeforeAll
    static void setUp() {
        context = Context.newBuilder("python")
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(name -> true)
                .build();
        context.getBindings("python").putMember("_codec", new Codec());
    }

    @AfterAll
    static void tearDown() {
        if (context != null)
            context.close(true);
    }

    @Test
    public void encodesNestedPythonStructures() {
        Value json = context.eval("python",
            "_codec.encode({" +
            "  'text': 'hello'," +
            "  'integer': 42," +
            "  'floating': 1.5," +
            "  'boolTrue': True," +
            "  'boolFalse': False," +
            "  'nothing': None," +
            "  'aList': [1, 'two', 3.5, True, None]," +
            "  'nested': {'inner': [{'x': 1}, {'y': 2}]}" +
            "})");

        assertTrue(json.isString(), "encode must produce a string");

        // decode on the Java side and verify every value survived
        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = (Map<String, Object>) new Codec().decode(json.asString());

        assertEquals("hello", decoded.get("text"));
        assertEquals(42, ((Number) decoded.get("integer")).intValue());
        assertEquals(1.5, ((Number) decoded.get("floating")).doubleValue(), 0.0);
        assertEquals(Boolean.TRUE, decoded.get("boolTrue"));
        assertEquals(Boolean.FALSE, decoded.get("boolFalse"));
        assertTrue(decoded.containsKey("nothing"), "null-valued key must be present");
        assertNull(decoded.get("nothing"));

        List<?> list = (List<?>) decoded.get("aList");
        assertEquals(5, list.size());
        assertEquals(1, ((Number) list.get(0)).intValue());
        assertEquals("two", list.get(1));
        assertEquals(3.5, ((Number) list.get(2)).doubleValue(), 0.0);
        assertEquals(Boolean.TRUE, list.get(3));
        assertNull(list.get(4));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) decoded.get("nested");
        List<?> inner = (List<?>) nested.get("inner");
        assertEquals(2, inner.size());
        assertEquals(1, ((Number) ((Map<?, ?>) inner.get(0)).get("x")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) inner.get(1)).get("y")).intValue());
    }

    @Test
    public void roundTripsBackIntoPython() {
        // encode in Python, decode in Java (host object), inspect from Python
        Value verdict = context.eval("python",
            "(lambda: (" +
            "  (lambda decoded:" +
            "     decoded['text'] == 'hi'" +
            "     and decoded['n'] == 7" +
            "     and decoded['f'] == 2.25" +
            "     and decoded['flag'] == True" +
            "     and decoded['maybe'] is None" +
            "     and list(decoded['seq']) == [1, 2, 3]" +
            "     and decoded['deep']['leaf'] == 'ok'" +
            "  )(_codec.decode(_codec.encode({" +
            "     'text': 'hi', 'n': 7, 'f': 2.25, 'flag': True, 'maybe': None," +
            "     'seq': [1, 2, 3], 'deep': {'leaf': 'ok'}})))" +
            "))()");

        assertTrue(verdict.asBoolean(), "Python-side round-trip comparison must hold");
    }

    @Test
    public void roundTripsScalars() {
        // NOTE: the framework's serialiser historically emits top-level strings
        // unquoted (same under the Jython host), so scalar coverage asserts
        // round-trip semantics rather than exact JSON text
        assertTrue(context.eval("python", "_codec.decode(_codec.encode('plain')) == 'plain'").asBoolean());
        assertTrue(context.eval("python", "_codec.decode(_codec.encode(5)) == 5").asBoolean());
        assertTrue(context.eval("python", "_codec.decode(_codec.encode(True)) == True").asBoolean());
        assertEquals("5", context.eval("python", "_codec.encode(5)").asString());
        assertEquals("true", context.eval("python", "_codec.encode(True)").asString());
    }
}
