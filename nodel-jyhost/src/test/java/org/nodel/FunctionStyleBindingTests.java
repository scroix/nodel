package org.nodel;

import com.microsoft.playwright.APIResponse;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regressions found by the v3 smoke-test playbook:
 * - function-style local actions (def local_action_X) fired a null-handler
 *   NPE on every invocation (logged as an error though the body still ran)
 * - dict metadata on module-level bindings (LocalEvent/Parameter dicts,
 *   action docstrings) was lost, so /events had no group/schema over REST
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FunctionStyleBindingTests extends TestBase {

    private static final String TEST_NODE = "E2E Function Style Bindings";

    private static final String SCRIPT =
            "local_event_Ping = LocalEvent({'title': 'Ping!', 'group': 'PingGroup', 'schema': {'type': 'string'}})\n" +
            "\n" +
            "def local_action_Poke(arg=None):\n" +
            "    '{\"title\": \"Poke!\", \"group\": \"PokeGroup\", \"schema\": {\"type\": \"string\"}}'\n" +
            "    console.info('Poked: %s' % arg)\n" +
            "    local_event_Ping.emit(arg)\n" +
            "\n" +
            "def main():\n" +
            "    console.info('fn-style node started')\n";

    @BeforeAll
    public static void setup() {
        initBrowser();
        boolean created = createTestNode(TEST_NODE, SCRIPT);
        assumeTrue(created, "Test node must be created and discovered for these tests to run");
    }

    @AfterAll
    public static void teardown() {
        deleteTestNode(TEST_NODE);
        closeBrowser();
    }

    @Test
    @Order(1)
    public void testFunctionStyleActionInvokesWithoutError() {
        APIResponse response = apiPost(
                "/nodes/" + encode(TEST_NODE) + "/actions/Poke/call",
                "{\"arg\": \"fn-style-marker\"}");
        assertEquals(200, response.status(), "Function-style action call should return 200");
        assertTrue(response.text().contains("true"),
                "Action call should return true, got: " + response.text());

        // the action body must have run...
        assertTrue(waitForConsoleContains(TEST_NODE, "Poked: fn-style-marker", 5000),
                "Action body should log to the console");

        // ...and completion must not have blown up on a null handler
        // (previously: Error executing action 'Poke': ... "this.val$handler" is null)
        APIResponse console = apiGet("/nodes/" + encode(TEST_NODE) + "/console?from=0&max=50");
        assertFalse(console.text().contains("Error executing action"),
                "No action execution error should be logged, got: " + console.text());
    }

    @Test
    @Order(2)
    public void testLocalEventKeepsDictMetadataOverRest() {
        APIResponse response = apiGet("/nodes/" + encode(TEST_NODE) + "/events");
        assertEquals(200, response.status(), "Events endpoint should return 200");
        String body = response.text();
        assertTrue(body.contains("\"title\":\"Ping!\""), "Event title should survive, got: " + body);
        assertTrue(body.contains("\"group\":\"PingGroup\""), "Event group should survive, got: " + body);
        assertTrue(body.contains("\"type\":\"string\""), "Event schema should survive, got: " + body);
    }

    @Test
    @Order(3)
    public void testFunctionStyleActionKeepsDocstringMetadataOverRest() {
        APIResponse response = apiGet("/nodes/" + encode(TEST_NODE) + "/actions");
        assertEquals(200, response.status(), "Actions endpoint should return 200");
        String body = response.text();
        assertTrue(body.contains("\"title\":\"Poke!\""), "Action title should survive, got: " + body);
        assertTrue(body.contains("\"group\":\"PokeGroup\""), "Action group should survive, got: " + body);
    }
}
