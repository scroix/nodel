package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nodel.SimpleName;

@DisplayName("Experimental Python 3 pilot recipes")
public class Python3PilotRecipeTest {

    private static NodelHost sharedHost;
    private static Path sharedTempDirectory;

    private PyNode node;
    private Path nodeDirectory;

    @BeforeAll
    public static void setUpAll() throws Exception {
        sharedTempDirectory = Files.createTempDirectory("nodel-python3-pilots-");
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

    @Test
    public void amxBeaconLoadsWithoutNetworkAndParsesASimulatedPacket() throws Exception {
        loadRecipe("amxBeaconPilot", "amx-beacon-receiver");

        assertEquals("true", eval("multicast_receiver is None"));

        eval("multicast_received(" +
            "'192.168.178.173:9131', " +
            "'AMXB<-UUID=GlobalCache_000C1E038995><-Make=GlobalCache><-Model=iTachIP2IR>'" +
            ")");

        assertEquals("GlobalCache", eval(
            "eventsByUUID['GlobalCache_000C1E038995'].getArg().get('make')"));
        assertEquals("iTachIP2IR", eval(
            "eventsByUUID['GlobalCache_000C1E038995'].getArg().get('model')"));
        assertEquals("192.168.178.173", eval(
            "lookup_local_event('GlobalCache_000C1E038995 IP Address').getArg()"));
        assertEquals("1", eval("str(len(registry_event.getArg()))"));

        eval("uuidByBindingName.clear()");
        eval("loadRegistry()");
        assertEquals("1", eval("str(len(uuidByBindingName))"));

        eval("multicast_received('192.0.2.1:9131', 'AMXB<-UUID=>')");
        eval("multicast_received('192.0.2.1:9131', 'AMXB<-UUID=GlobalCache000C1E038995>')");
        assertEquals("1", eval("str(len(eventsByUUID))"));

        eval("MAX_DISCOVERED_BEACONS = 1");
        eval("multicast_received('192.0.2.1:9131', 'AMXB<-UUID=Another_Beacon>')");
        assertEquals("1", eval("str(len(eventsByUUID))"));

        eval("registry_event.persistNow()");
        eval("eventsByUUID['GlobalCache_000C1E038995'].persistNow()");
        eval("lookup_local_event('GlobalCache_000C1E038995 IP Address').persistNow()");
        assertEventuallyEventSeedCount(3, true);

        Path unrelatedSeed = nodeDirectory.resolve(".nodel").resolve("unrelated.event.json");
        Files.writeString(unrelatedSeed, "unrelated");
        node.close();
        Files.writeString(
            nodeDirectory.resolve("nodeConfig.json"),
            "{\"paramValues\":{\"EnableReceiver\":true}}");
        node = new PyNode(
            sharedHost,
            new SimpleName("amxBeaconPilotReloaded"),
            nodeDirectory.toFile());
        assertEquals("1", eval("str(len(uuidByBindingName))"));
        assertEquals("0", eval("str(len(eventsByUUID))"));

        Path externalScript = Files.createTempFile("amx-external-script-", ".py");
        try {
            Path linkedScript = nodeDirectory.resolve("linked-script.py");
            try {
                Files.createSymbolicLink(linkedScript, externalScript);
                String linkedScriptLiteral = linkedScript.toString()
                    .replace("\\", "\\\\")
                    .replace("'", "\\'");
                assertEquals(
                    nodeDirectory.toRealPath().resolve(".nodel").toString(),
                    eval("metadataDirectoryFor('" + linkedScriptLiteral + "')"));
            } catch (UnsupportedOperationException | SecurityException | FileSystemException e) {
                // Symlink creation is unavailable on some Windows and restricted filesystems.
            }
        } finally {
            Files.deleteIfExists(externalScript);
        }

        assertEquals("true", eval("clearRegistry()"));
        assertEquals("0", eval("str(len(registry_event.getArg()))"));
        assertEventuallyEventSeedCount(2, false);
        assertEquals("unrelated", Files.readString(unrelatedSeed));
    }

    @Test
    public void amxBeaconEnabledConstructsManagedMulticastReceiver() throws Exception {
        loadRecipe(
            "amxBeaconEnabledPilot",
            "amx-beacon-receiver",
            "{\"paramValues\":{\"EnableReceiver\":true}}");

        assertEquals("false", eval("multicast_receiver is None"));
        assertEquals("239.255.250.250:9131", eval("multicast_receiver.getSource()"));
        assertEquals(Set.of("EnableReceiver"), reducedNames(node.getParameters().keySet()));

        assertEventuallyEquals("true", "multicast_receiver.getListeningPort() == 9131", 10);
        byte[] payload = (
            "AMXB<-UUID=Simulator_0001><-Make=Codex><-Model=LocalMulticast>"
        ).getBytes(StandardCharsets.US_ASCII);
        DatagramPacket packet = new DatagramPacket(
            payload,
            payload.length,
            InetAddress.getByName("239.255.250.250"),
            9131);
        try (MulticastSocket sender = new MulticastSocket()) {
            sender.setTimeToLive(0);
            for (int attempt = 0; attempt < 10; attempt++) {
                sender.send(packet);
                if ("true".equals(eval("'Simulator_0001' in eventsByUUID")))
                    break;
                Thread.sleep(100);
            }
        }

        assertEventuallyEquals("Codex", "eventsByUUID['Simulator_0001'].getArg().get('make')");
    }

    @Test
    public void extronMixerLoadsDisabledAndParsesASimulatedVolumeResponse() throws Exception {
        loadRecipe("extronMixerPilot", "extron-mvc-121-plus");

        assertEquals("Address not configured", eval("local_event_Status.getArg().get('message')"));
        assertEquals("true", eval("all(poller.isStopped() for poller in pollers)"));
        assertEquals("false", eval("comms_connected"));

        eval("parseVolumeResp('Vol51')");
        assertEquals("51", eval("str(varOutVolume.getArg())"));
        eval("parseVolumeResp('Vol9999')");
        assertEquals("51", eval("str(varOutVolume.getArg())"));
        assertEquals("true", eval("boundedInteger('0V\\r1Z\\r', 0, 100, 'volume') is None"));
        assertEquals("true", eval(
            "addressedBoundedResponse('garbage*1', 'DsM60002*', 0, 1, 'mute response') is None"));
        assertEquals("105", eval(
            "str(addressedBoundedResponse('105', 'DsG60002*', -1000, 1000, 'level response'))"));
        assertEquals("1", eval("str(gainResponse('Aud1', 1, 'gain response'))"));
        eval("received('X' * 5000)");
        assertEquals("true", eval(
            "len(str(local_event_Received.getArg())) <= MAX_RECEIVED_EVENT_LENGTH + 32"));

        assertEquals(Set.of(
            "VarOutMuting", "VarOutVol", "VarOutVolIncr", "VarOutVolDecr", "VarOutVolNudge",
            "Mic1Gain", "Mic1GainIncr", "Mic1GainDecr",
            "Mic2Gain", "Mic2GainIncr", "Mic2GainDecr",
            "Line3Gain", "Line3GainIncr", "Line3GainDecr",
            "FixedOutLMuting", "FixedOutLLevel", "FixedOutRMuting", "FixedOutRLevel"),
            reducedNames(node.getLocalActions().keySet()));
        assertEquals(Set.of(
            "Status", "VarOutMuting", "VarOutVol", "Mic1Gain", "Mic2Gain", "Line3Gain",
            "FixedOutLMuting", "FixedOutLLevel", "FixedOutRMuting", "FixedOutRLevel",
            "Connected", "Received", "Sent", "Disconnected", "Timeout"),
            reducedNames(node.getLocalEvents().keySet()));
        assertEquals(Set.of("disabled", "address"), reducedNames(node.getParameters().keySet()));
    }

    @Test
    public void extronMixerRejectsAnInvalidAddressWithoutStartingComms() throws Exception {
        loadRecipe(
            "extronMixerInvalidAddressPilot",
            "extron-mvc-121-plus",
            "{\"paramValues\":{\"address\":\"mixer-host\"}}");

        assertEquals("Invalid address", eval("local_event_Status.getArg().get('message')"));
        assertEquals("false", eval("comms_connected"));
        assertEquals("true", eval("all(poller.isStopped() for poller in pollers)"));
    }

    @Test
    public void extronMixerConfiguredAddressConnectsToASimulator() throws Exception {
        try (ServerSocket simulator = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            simulator.setSoTimeout(20000);
            FutureTask<Socket> accept = new FutureTask<>(simulator::accept);
            Thread acceptThread = new Thread(accept, "extron-mvc-121-plus-simulator");
            acceptThread.setDaemon(true);
            acceptThread.start();

            String address = simulator.getInetAddress().getHostAddress() + ":" + simulator.getLocalPort();
            loadRecipe(
                "extronMixerConfiguredPilot",
                "extron-mvc-121-plus",
                "{\"paramValues\":{\"address\":\"" + address + "\"}}");

            assertEquals("true", eval("tcp is not None"));
            try (Socket connection = accept.get(20, TimeUnit.SECONDS)) {
                assertTrue(connection.isConnected());
                assertEventuallyEquals("true", "all(poller.isStarted() for poller in pollers)");

                connection.setSoTimeout(10000);
                InputStream input = connection.getInputStream();
                OutputStream output = connection.getOutputStream();
                boolean volumeRequestSeen = false;
                for (int attempt = 0; attempt < 12 && !volumeRequestSeen; attempt++) {
                    String command = readCommand(input);
                    String response = simulatedExtronResponse(command);
                    output.write((response + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    volumeRequestSeen = "V".equals(command);
                }
                assertTrue(volumeRequestSeen, "Simulator did not receive the volume poll");
                assertEventuallyEquals("51", "str(varOutVolume.getArg())");

                node.getLocalActions().get(new SimpleName("Var Out Muting")).call(Boolean.TRUE);
                boolean muteSetSeen = false;
                for (int attempt = 0; attempt < 12 && !muteSetSeen; attempt++) {
                    String command = readCommand(input);
                    String response = simulatedExtronResponse(command);
                    output.write((response + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    muteSetSeen = "1Z".equals(command);
                }
                assertTrue(muteSetSeen, "Simulator did not receive the mute action");
                assertEventuallyEquals("true", "varOutMuting.getArg() is True");

                String tcpIdentity = eval("str(id(tcp))");
                eval("request('Q', parseVolumeResp)");
                eval("request('R', parseVolumeResp)");
                assertEventuallyEquals("true", "tcp.getQueueLength() > 0");
                eval("timeout()");
                assertEquals("true", eval("all(poller.isStopped() for poller in pollers)"));
                assertEquals("0", eval("str(tcp.getQueueLength())"));
                assertEquals(tcpIdentity, eval("str(id(tcp))"));
            }

            try (Socket reconnected = simulator.accept()) {
                assertTrue(reconnected.isConnected());
                assertEventuallyEquals("true", "all(poller.isStarted() for poller in pollers)");
                assertEventuallyEquals("true", "comms_connected");
                String tcpIdentity = eval("str(id(tcp))");
                eval("request('Q', parseVolumeResp)");
                eval("request('R', parseVolumeResp)");
                assertEventuallyEquals("true", "tcp.getQueueLength() > 0");
                eval("timeout()");
                assertEquals("true", eval("all(poller.isStopped() for poller in pollers)"));
                assertEquals("0", eval("str(tcp.getQueueLength())"));
                assertEquals(tcpIdentity, eval("str(id(tcp))"));
            }

            try (Socket reconnectedAgain = simulator.accept()) {
                assertTrue(reconnectedAgain.isConnected());
                assertEventuallyEquals("true", "all(poller.isStarted() for poller in pollers)");
            }
            simulator.close();
            assertEventuallyEquals("true", "all(poller.isStopped() for poller in pollers)");
            assertEventuallyEquals("0", "str(tcp.getQueueLength())");
        }
    }

    private void loadRecipe(String nodeName, String recipeName) throws IOException {
        loadRecipe(nodeName, recipeName, null);
    }

    private void loadRecipe(String nodeName, String recipeName, String configJson) throws IOException {
        Path script = locateRecipesDirectory().resolve(recipeName).resolve("script.py");
        assertTrue(Files.isRegularFile(script), "Recipe script not found: " + script);

        nodeDirectory = Files.createTempDirectory("nodel-python3-pilot-node-");
        Files.copy(script, nodeDirectory.resolve("script.py"));
        if (configJson != null)
            Files.writeString(nodeDirectory.resolve("nodeConfig.json"), configJson);
        node = new PyNode(sharedHost, new SimpleName(nodeName), nodeDirectory.toFile());
    }

    private String eval(String expression) throws Exception {
        Object result = node.eval(expression, "Python3PilotRecipeTest");
        return result == null ? null : result.toString();
    }

    private void assertEventuallyEquals(String expected, String expression) throws Exception {
        assertEventuallyEquals(expected, expression, 5);
    }

    private void assertEventuallyEquals(String expected, String expression, int timeoutSeconds)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        String actual;
        do {
            actual = eval(expression);
            if (expected.equals(actual))
                return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);

        assertEquals(expected, actual);
    }

    private long countEventSeedFiles() throws IOException {
        Path metadataDirectory = nodeDirectory.resolve(".nodel");
        if (!Files.isDirectory(metadataDirectory))
            return 0;
        try (var files = Files.list(metadataDirectory)) {
            return files
                .filter(path -> path.getFileName().toString().endsWith(".event.json"))
                .count();
        }
    }

    private void assertEventuallyEventSeedCount(long expected, boolean atLeast) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long actual;
        do {
            actual = countEventSeedFiles();
            if (atLeast ? actual >= expected : actual == expected)
                return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);

        String nodeFiles;
        try (var files = Files.walk(nodeDirectory)) {
            nodeFiles = files.map(Path::toString).collect(Collectors.joining(", "));
        }
        assertEquals(expected, actual, "Node files: " + nodeFiles);
    }

    private static Set<String> reducedNames(Collection<SimpleName> names) {
        return names.stream().map(SimpleName::getReducedName).collect(Collectors.toSet());
    }

    private static String readCommand(InputStream input) throws IOException {
        ByteArrayOutputStream command = new ByteArrayOutputStream();
        for (;;) {
            int value = input.read();
            if (value < 0)
                throw new IOException("Extron simulator connection closed before a command arrived");
            if (value == '\r')
                return command.toString(StandardCharsets.US_ASCII);
            if (command.size() >= 256)
                throw new IOException("Extron simulator received an oversized command");
            command.write(value);
        }
    }

    private static String simulatedExtronResponse(String command) throws IOException {
        return switch (command) {
            case "Z" -> "Amt1";
            case "1Z" -> "Amt1";
            case "V" -> "Vol51";
            case "1G" -> "In1 Aud0";
            case "2G" -> "In2 Aud0";
            case "3G" -> "In3 Aud0";
            case "\u001bM60002AU" -> "DsM60002*0";
            case "\u001bG60002AU" -> "DsG60002*0";
            case "\u001bM60003AU" -> "DsM60003*0";
            case "\u001bG60003AU" -> "DsG60003*0";
            default -> throw new IOException("Unexpected Extron simulator command: " + command);
        };
    }

    private static Path locateRecipesDirectory() {
        Path fromModule = Path.of("..", "recipes", "python3");
        if (Files.isDirectory(fromModule))
            return fromModule.toAbsolutePath().normalize();

        return Path.of("recipes", "python3").toAbsolutePath().normalize();
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
