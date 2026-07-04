package org.nodel.jyhost;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodel.SimpleName;

import com.sun.net.httpserver.HttpServer;

/**
 * Verifies the 2.x scripting-toolkit globals restored for GraalVM parity —
 * next_seq, system_clock, the date_* family, get_url, Event/Action/Signal,
 * quick_process, Timer camelCase methods, TCP camelCase kwargs, EMPTY,
 * is_blank, lookup_parameter and the '_node' injection — by loading real
 * scripts into a PyNode the same way recipes are loaded.
 */
@DisplayName("Toolkit 2.x-parity globals")
public class ToolkitGlobalsTest {

    private static NodelHost sharedHost;
    private static Path sharedTempDirectory;

    private PyNode node;
    private Path nodeDirectory;

    @BeforeAll
    public static void setUpAll() throws Exception {
        sharedTempDirectory = Files.createTempDirectory("nodel-toolkit-test-");
        sharedHost = new NodelHost(
            sharedTempDirectory.toFile(),
            null,
            null,
            sharedTempDirectory.toFile());
    }

    @AfterAll
    public static void tearDownAll() throws Exception {
        if (sharedHost != null)
            sharedHost.shutdown();

        deleteDirectory(sharedTempDirectory);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (node != null) {
            node.close();
            node = null;
        }
        if (nodeDirectory != null) {
            deleteDirectory(nodeDirectory);
            nodeDirectory = null;
        }
    }

    /**
     * Writes 'script' as the node's script.py and boots a PyNode from it
     * (same path as recipe loading).
     */
    private PyNode loadNode(String name, String script) throws IOException {
        nodeDirectory = Files.createTempDirectory("nodel-toolkit-node-");
        Files.writeString(nodeDirectory.resolve("script.py"), script, StandardCharsets.UTF_8);
        node = new PyNode(sharedHost, new SimpleName(name), nodeDirectory.toFile());
        return node;
    }

    /**
     * Evaluates a Python expression in the node, returning its string form.
     */
    private String eval(String expr) throws Exception {
        Object result = node.eval(expr, "ToolkitGlobalsTest");
        return result == null ? null : result.toString();
    }

    @Test
    @DisplayName("restored globals behave like 2.x (dates, sequences, aliases, Timer, TCP kwargs)")
    public void restoredGlobals() throws Exception {
        String script = """
            _checks = []
            def _check(name, cond):
                if not cond:
                    _checks.append(name)

            _seq1 = next_seq()
            _seq2 = next_seq()
            _check('next_seq_monotonic', _seq2 > _seq1 >= 0)
            _check('system_clock', system_clock() > 0)

            _check('date_now', date_now().getMillis() > 0)
            _rt = date_instant(1465805831836)
            _check('date_instant', _rt.getMillis() == 1465805831836)
            _check('date_parse_roundtrip', date_parse(_rt.toString()).getMillis() == 1465805831836)
            _at = date_at(2020, 6, 13, 8, 17, 11, 836)
            _check('date_at', _at.getYear() == 2020 and _at.getMonthOfYear() == 6
                              and _at.getDayOfMonth() == 13 and _at.getHourOfDay() == 8
                              and _at.getMillisOfSecond() == 836)

            _check('is_blank', is_blank(None) and is_blank('') and is_blank(' \\t\\r\\n') and not is_blank('x'))
            _check('EMPTY', len(EMPTY) == 0 and EMPTY.get('anything') is None)
            _check('same_value', same_value('a', 'a') and not same_value('a', 'b') and same_value(1, 1))

            _evt = Event('My Event', {'group': 'Tests', 'order': next_seq()})
            _check('Event_lookup', lookup_local_event('My Event') is not None)
            _sig = Signal('My Signal', {'group': 'Tests'})
            _check('Signal_lookup', lookup_local_event('My Signal') is not None)
            def _handler(arg):
                pass
            _act = Action('My Action', _handler, {'group': 'Tests'})
            _check('Action_lookup', lookup_local_action('My Action') is not None)
            _check('lookup_parameter', lookup_parameter('nonExistent') is None)

            _timer = Timer(lambda: None, intervalInSeconds=60, firstDelayInSeconds=30, stopped=True)
            _timer.setDelayAndInterval(5, 10)
            _check('timer_camelCase', _timer.getDelay() == 5.0 and _timer.getInterval() == 10.0
                                      and _timer.isStopped() and not _timer.isStarted())
            _timer.setInterval(20)
            _timer.setDelay(2)
            _check('timer_setters', _timer.getInterval() == 20.0 and _timer.getDelay() == 2.0)

            _tcp = TCP(connected=lambda: None, received=lambda data: None, sent=lambda data: None,
                       disconnected=lambda: None, timeout=lambda: None,
                       sendDelimiters='\\n', receiveDelimiters='\\r\\n')
            _check('tcp_camelCase_kwargs', _tcp is not None)

            _check('node_injected', _node is not None)
            _check('callables', all(callable(f) for f in (
                get_url, getURL, SSH, Process, quick_process, request_queue,
                Node, Subnode, release_node, releaseNode, call_delayed)))

            toolkit_checks = 'OK' if not _checks else 'FAIL: ' + ','.join(_checks)

            def main():
                pass
            """;

        loadNode("toolkitGlobalsNode", script);

        String result = eval("globals().get('toolkit_checks', 'NOT-DEFINED (script failed to load)')");
        assertEquals("OK", result);
    }

    @Test
    @DisplayName("bundled example_script.py loads without NameError")
    public void exampleScriptLoads() throws Exception {
        // the generated example recipe uses date_now(), next_seq() and TCP camelCase kwargs
        loadNode("exampleScriptNode", ExampleScript.get());

        // 'log' is defined on the example script's last lines — reachable only if
        // the whole module executed (next_seq() is used at module level before it)
        assertEquals("true", eval("'true' if callable(globals().get('log')) else 'false'"),
            "example_script.py did not fully load — a toolkit global is missing again");
    }

    @Test
    @DisplayName("quick_process runs a short-living command and reports stdout")
    public void quickProcessRoundTrip() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"),
            "uses a unix 'echo' command");

        String script = """
            qp_state = {}
            def _qp_finished(arg):
                qp_state['stdout'] = str(arg.stdout or '').strip()
                qp_state['code'] = arg.code
                qp_state['done'] = True

            quick_process(['echo', 'hello-qp'], finished=_qp_finished)

            def main():
                pass
            """;

        loadNode("quickProcessNode", script);

        // the process and its 'finished' callback are asynchronous — poll
        long deadline = System.currentTimeMillis() + 15000;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            state = eval("'%s|%s|%s' % (qp_state.get('done'), qp_state.get('code'), qp_state.get('stdout'))");
            if (state != null && state.startsWith("True"))
                break;
            Thread.sleep(50);
        }

        assertEquals("True|0|hello-qp", state);
    }

    @Test
    @DisplayName("get_url fetches over HTTP with 2.x call signature")
    public void getUrlFetches() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "hello-web".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        try {
            loadNode("getUrlNode", "def main():\n    pass\n");

            int port = server.getAddress().getPort();
            assertEquals("hello-web",
                eval("get_url('http://127.0.0.1:" + port + "/')"));

            // fullResponse exposes statusCode/content like 2.x
            assertEquals("200|hello-web",
                eval("(lambda r: '%s|%s' % (r.statusCode, r.content))"
                    + "(get_url('http://127.0.0.1:" + port + "/', fullResponse=True))"));
        } finally {
            server.stop(0);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory))
            return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        }
    }

}
