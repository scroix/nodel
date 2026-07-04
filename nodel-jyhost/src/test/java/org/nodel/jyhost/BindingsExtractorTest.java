package org.nodel.jyhost;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.nodel.SimpleName;
import org.nodel.host.Binding;
import org.nodel.host.Bindings;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extraction of binding metadata from Python globals — in particular that
 * dict-style metadata (title/group/schema/desc/caution/order) survives the
 * trip from a GraalPy dict into a Binding (regression: local events lost
 * 'group' and 'schema' over REST on the v3 line).
 */
public class BindingsExtractorTest {

    private static Context newPythonContext() {
        return Context.newBuilder("python")
                .allowHostAccess(HostAccess.ALL)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    @Test
    public void localEventDictMetadataSurvivesExtraction() {
        try (Context ctx = newPythonContext()) {
            ctx.eval("python",
                    "local_event_Ping = {'title': 'Ping!', 'group': 'PingGroup', " +
                    "'desc': 'A ping.', 'order': 3, 'schema': {'type': 'string'}}\n");

            Bindings bindings = BindingsExtractor.extract(ctx.getBindings("python"), new ArrayList<>());

            Binding event = bindings.local.events.get(new SimpleName("Ping"));
            assertNotNull(event, "local_event_Ping should be extracted");
            assertEquals("Ping!", event.title);
            assertEquals("PingGroup", event.group, "'group' must survive extraction");
            assertEquals("A ping.", event.desc);
            assertEquals(3, (int) event.order);
            assertNotNull(event.schema, "'schema' must survive extraction");
            assertEquals("string", event.schema.get("type"));
        }
    }

    @Test
    public void functionStyleLocalActionDocstringMetadataSurvivesExtraction() {
        try (Context ctx = newPythonContext()) {
            ctx.eval("python",
                    "def local_action_Poke(arg=None):\n" +
                    "    '{\"title\": \"Poke!\", \"group\": \"PokeGroup\", \"schema\": {\"type\": \"string\"}}'\n" +
                    "    pass\n");

            List<String> warnings = new ArrayList<>();
            Bindings bindings = BindingsExtractor.extract(ctx.getBindings("python"), warnings);

            Binding action = bindings.local.actions.get(new SimpleName("Poke"));
            assertNotNull(action, "local_action_Poke should be extracted");
            assertEquals("Poke!", action.title);
            assertEquals("PokeGroup", action.group);
            assertNotNull(action.schema);
        }
    }
}
