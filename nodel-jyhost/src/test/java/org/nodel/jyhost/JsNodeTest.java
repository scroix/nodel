package org.nodel.jyhost;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodel.SimpleName;

import org.nodel.io.Stream;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests language dispatch and the JavaScript toolkit: a node whose folder
 * contains 'script.js' boots a GraalJS context with the JS flavour of the
 * toolkit, and its bindings are discovered exactly like a Python node's —
 * while Python nodes keep working side by side in the same host.
 */
@DisplayName("JavaScript (GraalJS) node tests")
public class JsNodeTest {

    private static NodelHost sharedHost;
    private static Path sharedTempDirectory;

    @BeforeAll
    public static void setUpAll() throws Exception {
        sharedTempDirectory = Files.createTempDirectory("nodel-js-test-");
        sharedHost = new NodelHost(
            sharedTempDirectory.toFile(),    // root directory
            null,                            // inclusion filters
            null,                            // exclusion filters
            sharedTempDirectory.toFile()     // recipes root
        );
    }

    @AfterAll
    public static void tearDownAll() throws Exception {
        if (sharedHost != null)
            sharedHost.shutdown();

        org.nodel.io.Files.tryFlushDir(sharedTempDirectory.toFile(), true);
    }

    private static File newNodeDir(String name) throws Exception {
        // note: NOT under the host's scanned nodes root — the maintenance
        // scanner would otherwise boot duplicate nodes from these folders
        return Files.createTempDirectory(name).toFile();
    }

    private static void write(File dir, String filename, String content) throws Exception {
        Stream.writeFully(new File(dir, filename), content);
    }

    private static final String JS_SCRIPT =
            "var local_event_Status = LocalEvent({ title: 'Status', schema: { type: 'string' } });\n" +
            "var remote_action_Peer = RemoteAction({ title: 'Peer' });\n" +
            "var param_Prefix = Parameter({ title: 'Prefix', schema: { type: 'string' } });\n" +
            "var counter = 0;\n" +
            "var lifecycle = [];\n" +
            "beforeMain(function () { lifecycle.push('before'); });\n" +
            "afterMain(function () { lifecycle.push('after'); });\n" +
            "function local_action_Bump(arg) { counter++; }\n" +
            "function remote_event_PeerStatus(arg) { }\n" +
            "createLocalAction('Reset', function () { counter = 0; }, { title: 'Reset', group: 'Test' });\n" +
            "function main() { lifecycle.push('main'); console.info('js test node started'); }\n";

    @Test
    @DisplayName("script.js boots a GraalJS node with discovered bindings")
    public void testJsNodeBindingsDiscovery() throws Exception {
        File dir = newNodeDir("js-node");
        write(dir, "script.js", JS_SCRIPT);

        PyNode node = new PyNode(sharedHost, new SimpleName("JsBindingsNode"), dir);
        try {
            // naming-convention action + programmatic action are both live
            assertTrue(node.getLocalActions().containsKey(new SimpleName("Bump")),
                    "convention-named local action discovered");
            assertTrue(node.getLocalActions().containsKey(new SimpleName("Reset")),
                    "programmatically-created local action registered");

            // declarative local event
            assertTrue(node.getLocalEvents().containsKey(new SimpleName("Status")),
                    "declarative local event discovered");

            // the JS metadata object (member-based, unlike Python's hash-entry
            // dicts) survives the extractor: the event's schema made it through
            java.util.Map<String, Object> schema =
                    node.getLocalEvents().get(new SimpleName("Status")).getArgSchema();
            assertNotNull(schema, "JS metadata schema extracted");
            assertEquals("string", schema.get("type"), "JS metadata schema content intact");

            // lifecycle hooks ran in order around main()
            Object lifecycle = node.eval("lifecycle.join(',')", "test");
            assertEquals("before,main,after", lifecycle);
        } finally {
            node.close();
        }
    }

    @Test
    @DisplayName("JS action invocation executes the script function")
    public void testJsActionInvocation() throws Exception {
        File dir = newNodeDir("js-action-node");
        write(dir, "script.js", JS_SCRIPT);

        PyNode node = new PyNode(sharedHost, new SimpleName("JsActionNode"), dir);
        try {
            node.handleActionRequest(new SimpleName("Bump"), null, null);

            // the action executes asynchronously via the callback queue
            long deadline = System.currentTimeMillis() + 10000;
            int counter = 0;
            while (System.currentTimeMillis() < deadline) {
                try {
                    counter = ((Number) node.eval("counter", "test")).intValue();
                } catch (Exception e) {
                    // transient contention with the action callback; retry
                }
                if (counter == 1)
                    break;
                Thread.sleep(100);
            }
            assertEquals(1, counter, "local action 'Bump' incremented the script counter");
        } finally {
            node.close();
        }
    }

    @Test
    @DisplayName("Python and JavaScript nodes run side by side in one host")
    public void testMixedLanguageNodes() throws Exception {
        File pyDir = newNodeDir("py-node");
        write(pyDir, "script.py",
                "local_event_Status = LocalEvent({'title': 'Status'})\n" +
                "def local_action_Go(arg):\n" +
                "    pass\n" +
                "def main():\n" +
                "    console.info('py test node started')\n");

        File jsDir = newNodeDir("js-node2");
        write(jsDir, "script.js", JS_SCRIPT);

        PyNode pyNode = new PyNode(sharedHost, new SimpleName("MixedPyNode"), pyDir);
        PyNode jsNode = new PyNode(sharedHost, new SimpleName("MixedJsNode"), jsDir);
        try {
            assertTrue(pyNode.getLocalActions().containsKey(new SimpleName("Go")));
            assertTrue(jsNode.getLocalActions().containsKey(new SimpleName("Bump")));

            // each context speaks its own language
            assertEquals("py", pyNode.eval("'p' + 'y'", "test"));
            assertEquals("js", jsNode.eval("'j' + 's'", "test"));
        } finally {
            pyNode.close();
            jsNode.close();
        }
    }
}
