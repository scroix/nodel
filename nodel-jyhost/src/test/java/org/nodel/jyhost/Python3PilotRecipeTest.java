package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;

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

    @Test
    public void brightSignLoadsWithoutAnAddressAndPreservesItsBindings() throws Exception {
        loadRecipe("brightSignPilot", "brightsign-brightscript");

        assertEquals("true", eval("playerStatus_timer.isStopped() and nodeStatus_timer.isStopped()"));
        assertEquals("", eval("fullAddress"));
        assertEquals(Set.of(
            "Power", "Wake", "Sleep", "Play", "Pause", "Volume", "Reboot", "Mute",
            "MuteOn", "MuteOff", "GetStatus"),
            reducedNames(node.getLocalActions().keySet()));
        assertEquals(Set.of(
            "Power", "DesiredPower", "Model", "Serial", "VideoMode", "Volume", "Mute",
            "Playback", "DesiredPlayback", "DesiredMute", "LastContactDetect", "Status",
            "LogLevel"),
            reducedNames(node.getLocalEvents().keySet()));
        assertEquals(Set.of("playerConfig"), reducedNames(node.getParameters().keySet()));
        eval("local_event_LastContactDetect.emit(str(date_instant(date_now().getMillis() - 300000)))");
        eval("_lastReceive = system_clock() - 60000");
        eval("nodeStatusCheck()");
        assertEquals("Missing for approx. 5 mins", eval("local_event_Status.getArg().get('message')"));
    }

    @Test
    public void brightSignConfiguredAddressUsesAnHttpSimulator() throws Exception {
        ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
        AtomicBoolean sleeping = new AtomicBoolean(false);
        AtomicBoolean playing = new AtomicBoolean(true);
        AtomicBoolean muted = new AtomicBoolean(false);
        AtomicBoolean validStatus = new AtomicBoolean(true);
        AtomicBoolean redirectStatus = new AtomicBoolean(false);
        AtomicBoolean oversizedStatus = new AtomicBoolean(false);
        ConcurrentLinkedQueue<String> redirectRequests = new ConcurrentLinkedQueue<>();
        HttpServer redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectTarget.createContext("/", exchange -> {
            redirectRequests.add(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        HttpServer simulator = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        simulator.createContext("/", exchange -> {
            String request = exchange.getRequestURI().toString();
            requests.add(request);
            if (request.equals("/status") && redirectStatus.get()) {
                exchange.getResponseHeaders().set(
                    "Location",
                    "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + "/redirected");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (request.equals("/playback?sleep=true"))
                sleeping.set(true);
            else if (request.equals("/playback?sleep=false"))
                sleeping.set(false);
            else if (request.equals("/playback?playback=play"))
                playing.set(true);
            else if (request.equals("/playback?playback=pause"))
                playing.set(false);
            else if (request.equals("/mute?mute"))
                muted.set(true);
            else if (request.equals("/mute?unmute"))
                muted.set(false);

            String model = oversizedStatus.get() ? "M".repeat(5000) : "XT1144";
            String volume = oversizedStatus.get() ? "V".repeat(5000) : "42";
            String serial = oversizedStatus.get() ? "S".repeat(5000) : "SIM-001";
            String videoMode = oversizedStatus.get() ? "D".repeat(5000) : "1920x1080x60p";
            String body = request.equals("/status") && validStatus.get()
                ? "{\"model\":\"" + model + "\",\"volume\":\"" + volume + "\","
                    + "\"serialNumber\":\"" + serial + "\",\"videomode\":\"" + videoMode + "\","
                    + "\"sleep\":\"" + sleeping.get() + "\","
                    + "\"playing\":\"" + playing.get() + "\","
                    + "\"muted\":\"" + muted.get() + "\"}"
                : request.equals("/status") ? "{invalid" : "{}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(response);
            }
        });
        redirectTarget.start();
        simulator.start();
        try {
            loadRecipe(
                "brightSignConfiguredPilot",
                "brightsign-brightscript",
                "{\"paramValues\":{\"playerConfig\":{\"ipAddress\":\"127.0.0.1\","
                    + "\"scriptPort\":\"" + simulator.getAddress().getPort() + "\","
                    + "\"udpPort\":5000}}}");

            assertEquals("true", eval("playerStatus_timer.isStarted() and nodeStatus_timer.isStarted()"));
            eval("playerStatusGet()");
            assertEventuallyEquals("XT1144", "local_event_Model.getArg()");
            assertEventuallyEquals("SIM-001", "local_event_Serial.getArg()");
            assertEventuallyEquals("42", "local_event_Volume.getArg()");

            oversizedStatus.set(true);
            eval("playerStatusGet()");
            assertEquals("true", eval(
                "all(len(str(event.getArg())) <= MAX_STATUS_FIELD_LENGTH + 40 for event in "
                    + "[local_event_Model, local_event_Serial, local_event_VideoMode, local_event_Volume])"));
            oversizedStatus.set(false);
            eval("playerStatusGet()");
            assertEventuallyEquals("XT1144", "local_event_Model.getArg()");

            requests.clear();
            node.getLocalActions().get(new SimpleName("Power")).call("Off");
            assertEventuallyContains(requests, "/playback?sleep=true");
            assertEventuallyEquals("Off", "local_event_Power.getArg()");

            node.getLocalActions().get(new SimpleName("Volume")).call(Integer.valueOf(42));
            assertEventuallyContains(requests, "/volume?42");

            node.getLocalActions().get(new SimpleName("Mute")).call("On");
            assertEventuallyContains(requests, "/mute?mute");
            assertEventuallyEquals("On", "local_event_Mute.getArg()");

            node.getLocalActions().get(new SimpleName("Pause")).call(null);
            assertEventuallyContains(requests, "/playback?playback=pause");
            assertEventuallyEquals("Paused", "local_event_Playback.getArg()");

            sleeping.set(true);
            requests.clear();
            eval("local_event_DesiredPower.emit('On')");
            eval("playerStatusGet()");
            assertEventuallyContains(requests, "/playback?sleep=false");
            assertEquals("On", eval("local_event_DesiredPower.getArg()"));
            eval("playerStatusGet()");
            assertEventuallyEquals("On", "local_event_Power.getArg()");

            validStatus.set(false);
            eval("playerStatusGet()");
            assertEquals("XT1144", eval("local_event_Model.getArg()"));
            validStatus.set(true);

            redirectStatus.set(true);
            redirectRequests.clear();
            eval("playerStatusGet()");
            Thread.sleep(250);
            assertTrue(redirectRequests.isEmpty(), "Status redirect unexpectedly followed");
            redirectStatus.set(false);
        } finally {
            simulator.stop(0);
            redirectTarget.stop(0);
        }
    }

    @Test
    public void extronIn16xxLoadsWithoutAnAddressAndPreservesItsBindings() throws Exception {
        loadRecipe("extronIn16xxPilot", "extron-in16xx-mk1");

        assertEquals("true", eval("local_event_Connected.getTimestamp() is None"));
        assertEquals("true", eval("timer.isStopped()"));
        try (ServerSocket unexpectedConnection = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            unexpectedConnection.setSoTimeout(6500);
            eval("tcp.setDest('127.0.0.1:" + unexpectedConnection.getLocalPort() + "')");
            assertThrows(SocketTimeoutException.class, unexpectedConnection::accept);
        }
        assertEquals("true", eval("local_event_Timeout.getTimestamp() is None"));
        assertEquals("false", eval("str(bool(param_EnableRawSend)).lower()"));
        assertEquals(Set.of("ipAddress", "port", "EnableRawSend"),
            reducedNames(node.getParameters().keySet()));

        eval("handleInputRespAndEmit('99', local_event_Input)");
        assertEquals("Unexpected input value 99", eval("local_event_GeneralError.getArg()"));
        assertEquals("true", eval("local_event_Input.getArg() is None"));
        eval("received('X' * 5000)");
        assertEquals("true", eval(
            "len(str(local_event_Received.getArg())) <= MAX_RECEIVED_EVENT_LENGTH + 40"));

        Set<String> expectedActions = new HashSet<>(Set.of(
            "RefreshVideoInput", "RefreshAudioInput", "RefreshInput", "SetProgramVolume", "Send"));
        Set<String> expectedEvents = new HashSet<>(Set.of(
            "Greeting", "GreetingFirmwareDate", "GeneralError", "VideoInput", "AudioInput", "Input",
            "ProgramVolume", "Connected", "Received", "Sent", "Disconnected", "Timeout"));
        for (int input = 1; input <= 8; input++) {
            expectedActions.add("Input" + input + "videoandaudioselect");
            expectedActions.add("Input" + input + "videoonlyselect");
            expectedActions.add("Input" + input + "audioonlyselect");
            expectedEvents.add("Input" + input + "videoandaudioselect");
            expectedEvents.add("Input" + input + "videoonlyselect");
            expectedEvents.add("Input" + input + "audioonlyselect");
        }
        assertEquals(expectedActions, reducedNames(node.getLocalActions().keySet()));
        assertEquals(expectedEvents, reducedNames(node.getLocalEvents().keySet()));
    }

    @Test
    public void extronIn16xxConfiguredAddressUsesATcpSimulator() throws Exception {
        try (ServerSocket simulator = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            simulator.setSoTimeout(20000);
            FutureTask<Socket> accept = new FutureTask<>(simulator::accept);
            Thread acceptThread = new Thread(accept, "extron-in16xx-simulator");
            acceptThread.setDaemon(true);
            acceptThread.start();

            loadRecipe(
                "extronIn16xxConfiguredPilot",
                "extron-in16xx-mk1",
                "{\"paramValues\":{\"ipAddress\":\"127.0.0.1\",\"port\":"
                    + simulator.getLocalPort() + "}}");

            try (Socket connection = accept.get(20, TimeUnit.SECONDS)) {
                serveExtronIn16xxStartup(
                    connection,
                    "G".repeat(5000),
                    "F".repeat(5000),
                    new String[] {"01", "02", "99" + "X".repeat(5000)});
                InputStream input = connection.getInputStream();
                OutputStream output = connection.getOutputStream();

                assertEventuallyEquals("true",
                    "local_event_Greeting.getTimestamp() is not None");
                assertEventuallyEquals("true",
                    "str(local_event_Greeting.getArg()).endswith('characters truncated]')");
                assertEventuallyEquals("true",
                    "local_event_GreetingFirmwareDate.getTimestamp() is not None");
                assertEventuallyEquals("true",
                    "str(local_event_GreetingFirmwareDate.getArg()).endswith('characters truncated]')");
                assertEventuallyEquals("Input 1", "local_event_VideoInput.getArg()");
                assertEventuallyEquals("Input 2", "local_event_AudioInput.getArg()");
                assertEventuallyEquals("true", "local_event_Input.getArg() is None");
                assertEventuallyEquals("true",
                    "local_event_GeneralError.getTimestamp() is not None");
                assertEventuallyEquals("true",
                    "str(local_event_GeneralError.getArg()).endswith('characters truncated]')");
                assertEventuallyEquals("true", "timer.isStarted()");
                eval("timer.stop()");

                connection.setSoTimeout(250);
                node.getLocalActions().get(new SimpleName("Send")).call("I");
                assertThrows(SocketTimeoutException.class, () -> readCrLfCommand(input));
                connection.setSoTimeout(10000);
                eval("param_EnableRawSend = True");

                node.getLocalActions()
                    .get(new SimpleName("Input 4 video and audio select"))
                    .call(null);
                assertEquals("4!", readCrLfCommand(input));
                eval("local_action_RefreshVideoInput()");
                connection.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, () -> readCrLfCommand(input));
                output.write("In4 All\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                connection.setSoTimeout(10000);
                assertEventuallyEquals("true",
                    "lookup_local_event('Input 4 video and audio select').getTimestamp() is not None");
                assertEquals("&", readCrLfCommand(input));
                output.write("04\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();

                node.getLocalActions().get(new SimpleName("Set Program Volume")).call(-125);
                assertEquals("\u001bD1*-125GRPM", readCrLfCommand(input));
                output.write("GrpmD1*-125\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();

                node.getLocalActions().get(new SimpleName("Send")).call("I");
                assertEquals("I", readCrLfCommand(input));
                eval("local_action_RefreshAudioInput()");
                connection.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, () -> readCrLfCommand(input));
                output.write("I\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                connection.setSoTimeout(10000);
                assertEquals("$", readCrLfCommand(input));
                output.write("05\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                connection.setSoTimeout(250);
                node.getLocalActions().get(new SimpleName("Send")).call("1!\r\n2!");
                assertThrows(SocketTimeoutException.class, () -> readCrLfCommand(input));
                connection.setSoTimeout(10000);

                eval("local_action_RefreshInput()");
                assertEquals("!", readCrLfCommand(input));
            }

            assertEventuallyEquals("true", "comms_connected is False");
            eval("local_action_RefreshVideoInput()");
            assertEquals("0", eval("str(tcp.getQueueLength())"));

            try (Socket reconnected = simulator.accept()) {
                serveExtronIn16xxStartup(
                    reconnected, "IN1608-2", "2026-08-09-b", new String[] {"04", "05", "06"});
                assertEventuallyEquals("IN1608-2", "local_event_Greeting.getArg()");
                assertEventuallyEquals("2026-08-09-b", "local_event_GreetingFirmwareDate.getArg()");
                assertEventuallyEquals("Input 4", "local_event_VideoInput.getArg()");
                assertEventuallyEquals("Input 5", "local_event_AudioInput.getArg()");
                assertEventuallyEquals("Input 6", "local_event_Input.getArg()");

                eval("tcp.setRequestTimeout(1000)");
                eval("local_action_RefreshInput()");
                assertEquals("!", readCrLfCommand(reconnected.getInputStream()));
                Thread.sleep(1200);
                eval("local_action_RefreshVideoInput()");
                assertEventuallyEquals("true", "local_event_Timeout.getTimestamp() is not None");
                assertEventuallyEquals("0", "str(tcp.getQueueLength())");
                assertEventuallyEquals("true", "timer.isStopped()");
            }

            try (Socket reconnectedAfterTimeout = simulator.accept()) {
                serveExtronIn16xxStartup(
                    reconnectedAfterTimeout,
                    "IN1608-3",
                    "2026-08-09-c",
                    new String[] {"07", "08", "01"});
                assertEventuallyEquals("IN1608-3", "local_event_Greeting.getArg()");
                assertEventuallyEquals("2026-08-09-c", "local_event_GreetingFirmwareDate.getArg()");
                assertEventuallyEquals("Input 7", "local_event_VideoInput.getArg()");
                assertEventuallyEquals("Input 8", "local_event_AudioInput.getArg()");
                assertEventuallyEquals("Input 1", "local_event_Input.getArg()");
            }
        }
        assertEventuallyEquals("true", "timer.isStopped()");
    }

    @Test
    public void oscClientUsesAContainedEncoderAndUdpSimulator() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))) {
            receiver.setSoTimeout(10000);
            loadRecipe(
                "oscClientPilot",
                "osc-client",
                "{\"paramValues\":{\"ipAddress\":\"127.0.0.1\",\"port\":"
                    + receiver.getLocalPort()
                    + ",\"patterns\":[{\"label\":\"Fade\",\"address\":\"/foo/bar\"}]}}");

            assertEquals(Set.of("ipAddress", "port", "patterns"),
                reducedNames(node.getParameters().keySet()));
            assertEquals(Set.of("Custom", "Fade"),
                reducedNames(node.getLocalActions().keySet()));
            assertTrue(Files.notExists(nodeDirectory.resolve("OSC.py")));
            assertEventuallyEquals("true", "udp.getListeningPort() > 0", 10);
            assertEventuallyEquals("true", "_udpReady");

            eval("_udpReady = False");
            receiver.setSoTimeout(250);
            node.getLocalActions().get(new SimpleName("Fade")).call(Integer.valueOf(42));
            assertThrows(SocketTimeoutException.class, () -> receiveDatagram(receiver));
            eval("_udpReady = True");
            receiver.setSoTimeout(10000);
            node.getLocalActions().get(new SimpleName("Fade")).call(Integer.valueOf(42));
            assertEquals(
                "2f666f6f2f626172000000002c6600003ed70a3d",
                HexFormat.of().formatHex(receiveDatagram(receiver)));
            node.getLocalActions().get(new SimpleName("Fade")).call("");
            assertEquals(
                "2f666f6f2f626172000000002c000000",
                HexFormat.of().formatHex(receiveDatagram(receiver)));

            node.getLocalActions().get(new SimpleName("Custom")).call(
                Map.of("address", "/cue", "arg", "7"));
            assertEquals(
                "2f637565000000002c69000000000007",
                HexFormat.of().formatHex(receiveDatagram(receiver)));

            node.getLocalActions().get(new SimpleName("Custom")).call(
                Map.of("address", "/signed", "arg", "-1"));
            assertEquals(
                "2f7369676e6564002c660000bf800000",
                HexFormat.of().formatHex(receiveDatagram(receiver)));
            assertTrue(Files.notExists(nodeDirectory.resolve("OSC.py")));

            receiver.setSoTimeout(250);
            node.getLocalActions().get(new SimpleName("Custom")).call(
                Map.of("address", "relative", "arg", "1"));
            node.getLocalActions().get(new SimpleName("Custom")).call(
                Map.of("address", "/overflow", "arg", "2147483648"));
            assertThrows(SocketTimeoutException.class, () -> receiveDatagram(receiver));
        }
    }

    @Test
    public void alcornLoadsWithoutAnAddressAndPreservesItsBindings() throws Exception {
        loadRecipe("alcornPilot", "alcorn-8traxx");

        assertEquals("true", eval("tcp is None"));
        assertEquals("true", eval("timer_Status.isStopped()"));
        assertEquals("2", eval("str(local_event_Status.getArg().get('level'))"));
        assertEquals("Not configured", eval("local_event_Status.getArg().get('message')"));
        eval("info(0, 'ForeignNone-safe logging')");
        node.getLocalActions().get(new SimpleName("increaseLogLevel")).call(null);
        assertEquals("1", eval("str(local_event_LogLevel.getArg())"));
        node.getLocalActions().get(new SimpleName("decreaseLogLevel")).call(null);
        assertEquals("0", eval("str(local_event_LogLevel.getArg())"));

        assertEquals(Set.of("ipAddress", "Port"), reducedNames(node.getParameters().keySet()));
        assertEquals(Set.of(
            "PlayFileToChannel", "LoopPlayToChannel", "AssignSoundToChannel", "ResetChannel",
            "MuteChannel", "KeylockControl", "PollVersion", "StatusCheck",
            "increaseLogLevel", "decreaseLogLevel"),
            reducedNames(node.getLocalActions().keySet()));
        assertEquals(Set.of(
            "IPAddress", "Port", "LastContactDetect", "Status", "Version", "LastError",
            "LogLevel"),
            reducedNames(node.getLocalEvents().keySet()));
        assertEquals(Set.of("IPAddress", "Port"), reducedNames(node.getRemoteEvents().keySet()));

        try (ServerSocket simulator = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            simulator.setSoTimeout(20000);
            eval("remote_event_IPAddress('127.0.0.1')");
            eval("remote_event_Port(" + simulator.getLocalPort() + ")");
            try (Socket connection = simulator.accept()) {
                assertEventuallyEquals("true", "tcp is not None");
                assertEventuallyEquals("true", "_commsConnected");
                assertEventuallyEquals("true", "timer_Status.isStarted()");
                eval("timer_Status.stop()");
                var started = node.getStarted();
                eval("remote_event_Port(None)");
                assertEventuallyEquals("true", "tcp is None");
                assertEventuallyEquals("false", "_commsConnected");
                assertEventuallyEquals("2", "str(local_event_Status.getArg().get('level'))");
                assertTrue(node.hasRestarted(started, 10000).compareTo(started) != 0);
                assertEquals("true", eval("not local_event_Port.getArg() and tcp is None"));
                assertEquals("2", eval("str(local_event_Status.getArg().get('level'))"));
                assertEquals("Not configured", eval(
                    "local_event_Status.getArg().get('message')"));
            }

            try (ServerSocket rebound = new ServerSocket(
                    0, 1, InetAddress.getByName("127.0.0.1"))) {
                rebound.setSoTimeout(20000);
                eval("remote_event_Port(" + rebound.getLocalPort() + ")");
                try (Socket connection = rebound.accept()) {
                    assertEventuallyEquals("true", "_commsConnected");
                    eval("timer_Status.stop()");
                }
            }
        }
    }

    @Test
    public void alcornClearsPersistedOkBeforeConnectingOrWaitingForConfig() throws Exception {
        loadRecipe("alcornStaleStatusPilot", "alcorn-8traxx");
        eval("local_event_Status.emit({'level': 0, 'message': 'OK'})");
        eval("local_event_Status.persistNow()");
        node.close();
        node = new PyNode(
            sharedHost,
            new SimpleName("alcornStaleStatusUnconfiguredReloaded"),
            nodeDirectory.toFile());

        assertEquals("false", eval("_commsConnected"));
        assertEquals("true", eval("timer_Status.isStopped()"));
        assertEquals("2", eval("str(local_event_Status.getArg().get('level'))"));
        assertEquals("Not configured", eval("local_event_Status.getArg().get('message')"));

        node.close();
        Files.writeString(
            nodeDirectory.resolve("nodeConfig.json"),
            "{\"paramValues\":{\"ipAddress\":\"127.0.0.1\",\"Port\":\"9\"}}");
        node = new PyNode(
            sharedHost,
            new SimpleName("alcornStaleStatusPilotReloaded"),
            nodeDirectory.toFile());

        assertEquals("false", eval("_commsConnected"));
        assertEquals("true", eval("timer_Status.isStopped()"));
        assertEquals("1", eval("str(local_event_Status.getArg().get('level'))"));
        assertEquals("Connecting", eval("local_event_Status.getArg().get('message')"));
    }

    @Test
    public void alcornConfiguredAddressUsesATcpSimulator() throws Exception {
        try (ServerSocket simulator = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            simulator.setSoTimeout(20000);
            FutureTask<Socket> accept = new FutureTask<>(simulator::accept);
            Thread acceptThread = new Thread(accept, "alcorn-8traxx-simulator");
            acceptThread.setDaemon(true);
            acceptThread.start();

            loadRecipe(
                "alcornConfiguredPilot",
                "alcorn-8traxx",
                "{\"paramValues\":{\"ipAddress\":\"127.0.0.1\",\"Port\":\""
                    + simulator.getLocalPort() + "\"}}");

            try (Socket connection = accept.get(20, TimeUnit.SECONDS)) {
                connection.setSoTimeout(10000);
                InputStream input = connection.getInputStream();
                OutputStream output = connection.getOutputStream();
                assertEventuallyEquals("true", "_commsConnected");
                assertEventuallyEquals("true", "timer_Status.isStarted()");
                eval("timer_Status.stop()");
                String tcpIdentity = eval("str(id(tcp))");
                eval("remote_event_IPAddress('192.0.2.55')");
                assertEquals(tcpIdentity, eval("str(id(tcp))"));
                assertEquals("true", eval("_commsConnected"));

                node.getLocalActions().get(new SimpleName("PollVersion")).call(null);
                assertEquals("?V", readCommand(input));
                output.write(("Alcorn McBride 8TraXX V2.1" + "X".repeat(5000) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("true",
                    "len(str(local_event_Version.getArg())) < 5000 "
                        + "and 'characters truncated]' in str(local_event_Version.getArg())");

                node.getLocalActions().get(new SimpleName("PollVersion")).call(null);
                assertEquals("?V", readCommand(input));
                output.write("Alcorn McBride 8TraXX V2.1\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("V2.1", "local_event_Version.getArg()");

                output.write("E11\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("Media Not Present (E11)", "local_event_LastError.getArg()");

                node.getLocalActions().get(new SimpleName("PlayFileToChannel")).call(
                    Map.of("Channel", "2", "Sound", Integer.valueOf(12)));
                assertEquals("122PL", readCommand(input));
                node.getLocalActions().get(new SimpleName("ResetChannel")).call(
                    Map.of("Channel", "3"));
                connection.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, () -> readCommand(input));
                output.write("R\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                connection.setSoTimeout(10000);
                assertEquals("3RJ", readCommand(input));
                output.write("R\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();

                connection.setSoTimeout(250);
                node.getLocalActions().get(new SimpleName("PlayFileToChannel")).call(
                    Map.of("Channel", "99", "Sound", Integer.valueOf(12)));
                node.getLocalActions().get(new SimpleName("PlayFileToChannel")).call(
                    Map.of("Channel", "2", "Sound", Integer.valueOf(999)));
                node.getLocalActions().get(new SimpleName("AssignSoundToChannel")).call(
                    Map.of("Channel", "2"));
                node.getLocalActions().get(new SimpleName("ResetChannel")).call(
                    Map.of("Channel", Integer.valueOf(0)));
                assertThrows(SocketTimeoutException.class, () -> readCommand(input));
                connection.setSoTimeout(10000);

                node.getLocalActions().get(new SimpleName("StatusCheck")).call(null);
                assertEquals("?V", readCommand(input));
                eval("_lastReceive = None");
                output.write("Alcorn McBride 8TraXX V2.1\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("true",
                    "_lastReceive is not None "
                        + "and local_event_Status.getArg().get('level') == 0");

                eval("_lastReceive = system_clock() - (6 * 60 * 1000)");
                eval("local_event_LastContactDetect.emit(str(date_now().minusMinutes(5)))");
                node.getLocalActions().get(new SimpleName("StatusCheck")).call(null);
                assertEquals("?V", readCommand(input));
                assertEquals("true", eval(
                    "(lambda message: message.startswith('Missing for <') "
                        + "and message.endswith(' mins') and message[13:-5].isdigit())"
                        + "(str(local_event_Status.getArg().get('message')))"));
                output.write("Alcorn McBride 8TraXX V2.1\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }

            assertEventuallyEquals("false", "_commsConnected");
            assertEventuallyEquals("true", "timer_Status.isStopped()");
            assertEventuallyEquals("2", "str(local_event_Status.getArg().get('level'))");
        }
    }

    @Test
    public void sonyViscaLoadsWithoutAnAddressAndUsesBindingDrivenUdp() throws Exception {
        try (DatagramSocket simulator = new DatagramSocket(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))) {
            simulator.setSoTimeout(10000);
            loadRecipe("sonyViscaPilot", "sony-visca-color-video-camera");

            assertEquals(Set.of("disabled", "ipAddress", "port", "viscaAddress", "StatusHTTP"),
                reducedNames(node.getParameters().keySet()));
            assertEquals(Set.of("IPAddress"), reducedNames(node.getRemoteEvents().keySet()));
            assertEquals(Set.of(
                "IPAddress", "PanSpeed", "TiltSpeed", "FocusMode", "Status",
                "LastContactDetect", "LogLevel"),
                reducedNames(node.getLocalEvents().keySet()));
            assertEquals(Set.of(
                "PanSpeed", "TiltSpeed", "ptzhome", "ptzup", "ptzdown", "ptzleft",
                "ptzright", "ptzstop", "ptzpresetreset", "ptzpresetset",
                "ptzpresetrecall", "ptzzoomstop", "ptzzoomtele", "ptzzoomwide",
                "ptzfocusmodeauto", "ptzfocusmodemanual", "ptzfocusstop",
                "ptzfocusfar", "ptzfocusnear", "httpPoll"),
                reducedNames(node.getLocalActions().keySet()));
            assertEquals("5", eval("str(local_event_PanSpeed.getArg())"));
            assertEquals("5", eval("str(local_event_TiltSpeed.getArg())"));
            assertEquals("false", eval("_destinationConfigured"));
            assertEquals("2", eval("str(local_event_Status.getArg().get('level'))"));
            assertEquals("false", eval("configureDestination('camera.invalid')"));
            assertEquals("Invalid address",
                eval("local_event_Status.getArg().get('message')"));
            assertEventuallyEquals("true", "_udpReady", 10);

            eval("_port = " + simulator.getLocalPort());
            eval("remote_event_IPAddress('127.0.0.1')");
            DatagramPacket reset = receiveDatagramPacket(simulator);
            assertEquals("020000010000000001", HexFormat.of().formatHex(
                Arrays.copyOf(reset.getData(), reset.getLength())));
            assertEventuallyEquals("false", "_pendingCommand['awaitingSend']");
            assertEquals("127.0.0.1:" + simulator.getLocalPort(),
                eval("_pendingCommand['target']"));
            byte[] resetReply = HexFormat.of().parseHex("020100010000000001");
            simulator.send(new DatagramPacket(
                resetReply, resetReply.length, reset.getSocketAddress()));
            assertEventuallyEquals("true", "_pendingCommand is None");

            node.getLocalActions().get(new SimpleName("ptz_up")).call(null);
            assertEquals("01000009000000018101060105050301ff",
                HexFormat.of().formatHex(receiveDatagram(simulator)));
            node.getLocalActions().get(new SimpleName("PanSpeed")).call(Integer.valueOf(24));
            node.getLocalActions().get(new SimpleName("TiltSpeed")).call(Integer.valueOf(20));
            node.getLocalActions().get(new SimpleName("ptz_right")).call(null);
            simulator.setSoTimeout(250);
            assertThrows(SocketTimeoutException.class, () -> receiveDatagram(simulator));
            byte[] acknowledgement = HexFormat.of().parseHex("01110003000000019041ff");
            simulator.send(new DatagramPacket(
                acknowledgement, acknowledgement.length, reset.getSocketAddress()));
            assertEventuallyEquals("Awaiting completion",
                "local_event_Status.getArg().get('message')");
            assertThrows(SocketTimeoutException.class, () -> receiveDatagram(simulator));
            byte[] completion = HexFormat.of().parseHex("01110003000000019051ff");
            simulator.send(new DatagramPacket(
                completion, completion.length, reset.getSocketAddress()));
            simulator.setSoTimeout(10000);
            assertEquals("01000009000000028101060118140203ff",
                HexFormat.of().formatHex(receiveDatagram(simulator)));
            completion = HexFormat.of().parseHex("01110003000000029051ff");
            simulator.send(new DatagramPacket(
                completion, completion.length, reset.getSocketAddress()));
            assertEventuallyEquals("true", "_pendingCommand is None");

            eval("RESPONSE_TIMEOUT_SECONDS = 0.1");
            node.getLocalActions().get(new SimpleName("ptz_preset_recall"))
                .call(Integer.valueOf(7));
            String presetPacket = HexFormat.of().formatHex(receiveDatagram(simulator));
            assertEquals("01000007000000038101043f0207ff", presetPacket);
            assertEquals(presetPacket, HexFormat.of().formatHex(receiveDatagram(simulator)));

            byte[] reply = HexFormat.of().parseHex("01110003000000039051ff");
            simulator.send(new DatagramPacket(reply, reply.length, reset.getSocketAddress()));
            assertEventuallyEquals("0", "str(local_event_Status.getArg().get('level'))");
            assertEventuallyEquals("true", "_lastReceive > 0");

            eval("RESPONSE_TIMEOUT_SECONDS = 1");
            node.getLocalActions().get(new SimpleName("ptz_home")).call(null);
            assertEquals("010000050000000481010604ff",
                HexFormat.of().formatHex(receiveDatagram(simulator)));
            node.getLocalActions().get(new SimpleName("ptz_stop")).call(null);
            assertEquals("01000009000000058101060105050303ff",
                HexFormat.of().formatHex(receiveDatagram(simulator)));
            completion = HexFormat.of().parseHex("01110003000000059051ff");
            simulator.send(new DatagramPacket(
                completion, completion.length, reset.getSocketAddress()));
            assertEventuallyEquals("true", "_pendingCommand is None");

            simulator.setSoTimeout(250);
            node.getLocalActions().get(new SimpleName("PanSpeed")).call(Integer.valueOf(0));
            node.getLocalActions().get(new SimpleName("ptz_preset_recall"))
                .call(Integer.valueOf(256));
            assertThrows(SocketTimeoutException.class, () -> receiveDatagram(simulator));

            eval("remote_event_IPAddress(None)");
            assertEquals("false", eval("_destinationConfigured"));
            assertEquals("true", eval("timer_poller.isStopped()"));
            assertEquals("true", eval("timer_statusCheck.isStopped()"));
            assertEquals("2", eval("str(local_event_Status.getArg().get('level'))"));
            String receiveCountBeforeLateReply = eval("str(_udpReceiveCount)");
            simulator.send(new DatagramPacket(reply, reply.length, reset.getSocketAddress()));
            assertEventuallyEquals("true",
                "_udpReceiveCount > " + receiveCountBeforeLateReply);
            assertEquals("2", eval("str(local_event_Status.getArg().get('level'))"));

            String failedUdpIdentity = eval("str(id(udp))");
            eval("udp.close(); SEND_CONFIRMATION_TIMEOUT_SECONDS = 0.1; "
                + "configureDestination('127.0.0.1')");
            simulator.setSoTimeout(10000);
            DatagramPacket recoveredReset = receiveDatagramPacket(simulator);
            assertEquals("02000001", HexFormat.of().formatHex(
                Arrays.copyOf(recoveredReset.getData(), 4)));
            assertNotEquals(failedUdpIdentity, eval("str(id(udp))"));
            assertEventuallyEquals("true", "_udpReady");
            byte[] recoveredResetReply = Arrays.copyOf(
                recoveredReset.getData(), recoveredReset.getLength());
            recoveredResetReply[1] = 0x01;
            simulator.send(new DatagramPacket(
                recoveredResetReply,
                recoveredResetReply.length,
                recoveredReset.getSocketAddress()));
            assertEventuallyEquals("true", "_pendingCommand is None");
        }
    }

    @Test
    public void sonyViscaConfiguredAddressUsesHttpAndUdpStatusSimulators() throws Exception {
        AtomicBoolean httpRequested = new AtomicBoolean(false);
        HttpServer httpServer = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        httpServer.createContext("/login", exchange -> {
            httpRequested.set(true);
            byte[] response = "camera-ready-token".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        httpServer.start();

        try (DatagramSocket simulator = new DatagramSocket(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))) {
            simulator.setSoTimeout(10000);
            loadRecipe(
                "sonyViscaConfiguredPilot",
                "sony-visca-color-video-camera",
                "{\"paramValues\":{\"ipAddress\":\"127.0.0.1\",\"port\":"
                    + simulator.getLocalPort()
                    + ",\"StatusHTTP\":{\"url\":\"http://127.0.0.1:"
                    + httpServer.getAddress().getPort()
                    + "/login\",\"token\":\"camera-ready-token\"}}}");

            DatagramPacket reset = receiveDatagramPacket(simulator);
            assertEquals("020000010000000001", HexFormat.of().formatHex(
                Arrays.copyOf(reset.getData(), reset.getLength())));
            byte[] resetReply = HexFormat.of().parseHex("020100010000000001");
            simulator.send(new DatagramPacket(
                resetReply, resetReply.length, reset.getSocketAddress()));
            assertEventuallyEquals("true", "_pendingCommand is None");
            eval("timer_poller.stop(); timer_statusCheck.stop(); _lastReceive = 0");
            node.getLocalActions().get(new SimpleName("httpPoll")).call(null);
            assertEventuallyEquals("true", "_lastReceive > 0");
            assertTrue(httpRequested.get());
            assertEquals("0", eval("str(local_event_Status.getArg().get('level'))"));
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    public void sonyViscaDisabledIgnoresBindingUpdates() throws Exception {
        try (DatagramSocket simulator = new DatagramSocket(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))) {
            simulator.setSoTimeout(250);
            loadRecipe(
                "sonyViscaDisabledPilot",
                "sony-visca-color-video-camera",
                "{\"paramValues\":{\"disabled\":true,\"port\":"
                    + simulator.getLocalPort() + "}}");

            assertEquals("false", eval("_udpReady"));
            assertEquals("false", eval("_destinationConfigured"));
            assertEquals("Disabled", eval("local_event_Status.getArg().get('message')"));
            eval("remote_event_IPAddress('127.0.0.1')");
            assertEquals("false", eval("_destinationConfigured"));
            assertEquals("true", eval("timer_poller.isStopped()"));
            assertEquals("true", eval("timer_statusCheck.isStopped()"));
            assertEquals("Disabled", eval("local_event_Status.getArg().get('message')"));
            assertThrows(SocketTimeoutException.class, () -> receiveDatagram(simulator));
        }
    }

    @Test
    public void yamahaYncaLoadsWithoutAnAddressAndPreservesDynamicBindings() throws Exception {
        loadRecipe("yamahaYncaPilot", "yamaha-av-receiver-ynca");

        assertEquals(Set.of("Disabled", "IPAddress"),
            reducedNames(node.getParameters().keySet()));
        assertEquals(Set.of("UPnPBeacon"), reducedNames(node.getRemoteEvents().keySet()));
        assertEquals("true", eval("tcp is None"));
        assertEquals("true", eval("all(poller.isStopped() for poller in pollers)"));
        assertEquals("true", eval("status_timer.isStopped()"));
        assertEquals("2", eval("str(local_event_Status.getArg().get('level'))"));
        assertEquals(59, node.getLocalActions().size());
        assertEquals(54, node.getLocalEvents().size());
        assertTrue(node.getLocalActions().containsKey(new SimpleName("Get Version")));
        assertTrue(node.getLocalActions().containsKey(new SimpleName("Main Power")));
        assertTrue(node.getLocalActions().containsKey(new SimpleName("Zone 2 Input HDMI5")));
        assertTrue(node.getLocalEvents().containsKey(new SimpleName("Main Input HDMI2")));
        assertTrue(node.getLocalEvents().containsKey(new SimpleName("Zone 2 Muting")));

        eval("local_event_DiscoveredIPAddress.emit('127.0.0.1'); setupTCP()");
        assertEquals("true", eval("tcp is None"));
        eval("param_IPAddress = 'not a valid host'; setupTCP()");
        assertEquals("true", eval("tcp is None"));
        assertEquals("Invalid address", eval("local_event_Status.getArg().get('message')"));
    }

    @Test
    public void yamahaYncaUsesATcpSimulatorForCommandsResponsesAndStatus() throws Exception {
        try (ServerSocket simulator = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            simulator.setSoTimeout(20000);
            loadRecipe("yamahaYncaConfiguredPilot", "yamaha-av-receiver-ynca");
            eval("YNCA_TCPPORT = " + simulator.getLocalPort()
                + "; param_IPAddress = '127.0.0.1'; setupTCP()");

            try (Socket connection = simulator.accept()) {
                connection.setSoTimeout(10000);
                InputStream input = connection.getInputStream();
                OutputStream output = connection.getOutputStream();
                assertEventuallyEquals("true", "_commsConnected");
                eval("stopPollers()");

                node.getLocalActions().get(new SimpleName("Get Main Power")).call(null);
                node.getLocalActions().get(new SimpleName("Get Zone 2 Power")).call(null);
                assertEquals("@MAIN:PWR=?", readCrLfCommand(input));
                long firstCommandReceivedAt = System.nanoTime();
                output.write("@MAIN:PWR=On\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEquals("@ZONE2:PWR=?", readCrLfCommand(input));
                long commandSpacingMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - firstCommandReceivedAt);
                assertTrue(commandSpacingMillis >= 95,
                    "YNCA commands were only " + commandSpacingMillis + " ms apart");
                output.write("@ZONE2:PWR=Standby\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();

                node.getLocalActions().get(new SimpleName("Main Power")).call("Off");
                assertEquals("@MAIN:PWR=Standby", readCrLfCommand(input));
                output.write("@MAIN:PWR=Standby\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("Off", "lookup_local_event('Main Power').getArg()");

                node.getLocalActions().get(new SimpleName("Main Muting Toggle")).call(null);
                assertEquals("@MAIN:MUTE=?", readCrLfCommand(input));
                long mutingQueryReceivedAt = System.nanoTime();
                output.write("@MAIN:MUTE=Off\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEquals("@MAIN:MUTE=On", readCrLfCommand(input));
                long mutingCommandSpacingMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - mutingQueryReceivedAt);
                assertTrue(mutingCommandSpacingMillis >= 95,
                    "YNCA mute query and set were only "
                        + mutingCommandSpacingMillis + " ms apart");
                output.write("@MAIN:MUTE=On\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("On", "lookup_local_event('Main Muting').getArg()");

                node.getLocalActions().get(new SimpleName("Main Volume"))
                    .call(Double.valueOf(-20.5));
                assertEquals("@MAIN:VOL=-20.5", readCrLfCommand(input));
                output.write("@MAIN:VOL=-20.5\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("-20.5", "str(lookup_local_event('Main Volume').getArg())");

                node.getLocalActions().get(new SimpleName("Main Input HDMI2")).call(null);
                assertEquals("@MAIN:INP=HDMI2", readCrLfCommand(input));
                output.write("@MAIN:INP=HDMI2\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("true", "lookup_local_event('Main Input HDMI2').getArg()");
                assertEventuallyEquals("false", "lookup_local_event('Main Input HDMI1').getArg()");

                node.getLocalActions().get(new SimpleName("Get Version")).call(null);
                assertEquals("@SYS:VERSION=?", readCrLfCommand(input));
                output.write(("@SYS:VERSION=" + "V".repeat(1000) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("true",
                    "len(str(lookup_local_event('Version').getArg())) < 1000 "
                        + "and 'characters truncated]' in str(lookup_local_event('Version').getArg())");

                eval("lastReceive[0] = 0");
                output.write("@RESTRICTED\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("true", "lastReceive[0] > 0");
                assertEventuallyEquals("Restricted",
                    "local_event_Status.getArg().get('message')");

                connection.setSoTimeout(250);
                node.getLocalActions().get(new SimpleName("Main Input")).call("INVALID");
                node.getLocalActions().get(new SimpleName("Main Power")).call("Maybe");
                assertThrows(SocketTimeoutException.class, () -> readCrLfCommand(input));
                connection.setSoTimeout(10000);

                eval("lastReceive[0] = system_clock() - (6 * 60 * 1000); "
                    + "local_event_LastContactDetect.emit(str(date_now().minusMinutes(5))); "
                    + "statusCheck()");
                assertEquals("true", eval(
                    "(lambda message: message.startswith('Missing for approx. ') "
                        + "and message.endswith(' mins') and message[20:-5].isdigit())"
                        + "(str(local_event_Status.getArg().get('message')))"));
                eval("lastReceive[0] = system_clock(); stopComms('Test stop'); "
                    + "tcp_received(_connectionGeneration, '@MAIN:PWR=On')");
                assertEquals("Test stop", eval("local_event_Status.getArg().get('message')"));
            }

            try (Socket reconnected = simulator.accept()) {
                assertEventuallyEquals("true", "_commsConnected");
                assertEventuallyEquals("0", "str(lastReceive[0])");
                assertEventuallyEquals("Awaiting response",
                    "local_event_Status.getArg().get('message')");
                eval("staleCycle = _connectionCycle; _connectionCycle += 1; "
                    + "handleTCPDisconnected(_connectionGeneration, staleCycle); "
                    + "handleTCPTimeout(_connectionGeneration, staleCycle)");
                assertEquals("true", eval("_commsConnected"));
                assertEquals("Awaiting response",
                    eval("local_event_Status.getArg().get('message')"));
                eval("stopPollers()");
                reconnected.setSoTimeout(10000);
                InputStream input = reconnected.getInputStream();
                OutputStream output = reconnected.getOutputStream();
                node.getLocalActions().get(new SimpleName("Main Muting Toggle")).call(null);
                assertEquals("@MAIN:MUTE=?", readCrLfCommand(input));
                node.getLocalActions().get(new SimpleName("Main Muting")).call("Off");
                output.write("@MAIN:MUTE=Off\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEquals("@MAIN:MUTE=Off", readCrLfCommand(input));
                String receiveCountBeforeExplicitMute = eval("str(_tcpReceiveCount)");
                output.write("@MAIN:MUTE=Off\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("true",
                    "_tcpReceiveCount > " + receiveCountBeforeExplicitMute);
                assertEventuallyEquals("Off", "lookup_local_event('Main Muting').getArg()");

                eval("[handler() for handler in connectionResetHandlers]");
                node.getLocalActions().get(new SimpleName("Main Muting Toggle")).call(null);
                assertEquals("@MAIN:MUTE=?", readCrLfCommand(input));
                output.write("@RESTRICTED\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("Restricted",
                    "local_event_Status.getArg().get('message')");
                String receiveCountBeforeUnsolicitedMute = eval("str(_tcpReceiveCount)");
                output.write("@MAIN:MUTE=Off\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("true",
                    "_tcpReceiveCount > " + receiveCountBeforeUnsolicitedMute);
                assertEventuallyEquals("Off", "lookup_local_event('Main Muting').getArg()");
                reconnected.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, () -> readCrLfCommand(input));

                eval("[handler() for handler in connectionResetHandlers]; "
                    + "MUTING_TOGGLE_TIMEOUT_SECONDS = 0.1");
                reconnected.setSoTimeout(10000);
                node.getLocalActions().get(new SimpleName("Main Muting Toggle")).call(null);
                assertEquals("@MAIN:MUTE=?", readCrLfCommand(input));
                Thread.sleep(250);
                String receiveCountBeforeLateMute = eval("str(_tcpReceiveCount)");
                output.write("@MAIN:MUTE=Off\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEventuallyEquals("true",
                    "_tcpReceiveCount > " + receiveCountBeforeLateMute);
                assertEventuallyEquals("Off", "lookup_local_event('Main Muting').getArg()");
                reconnected.setSoTimeout(250);
                assertThrows(SocketTimeoutException.class, () -> readCrLfCommand(input));

                eval("COMMAND_RESPONSE_TIMEOUT_SECONDS = 0.1");
                reconnected.setSoTimeout(10000);
                node.getLocalActions().get(new SimpleName("Get Main Power")).call(null);
                assertEquals("@MAIN:PWR=?", readCrLfCommand(input));
                assertEventuallyEquals("false", "_commsConnected");
                assertEventuallyEquals("true", "_activeCommandRequest is None");
                assertEventuallyEquals("true", "len(commandQueue) == 0");
            }

            simulator.close();
            assertEventuallyEquals("false", "_commsConnected");
            assertEventuallyEquals("true", "all(poller.isStopped() for poller in pollers)");
            assertEventuallyEquals("true", "status_timer.isStopped()");
            assertEventuallyEquals("2", "str(local_event_Status.getArg().get('level'))");
        }
    }

    @Test
    public void yamahaYncaDiscoveryParsesTheHostAndStartsConfiguredComms() throws Exception {
        try (ServerSocket simulator = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            simulator.setSoTimeout(20000);
            loadRecipe(
                "yamahaYncaDiscoveryPilot",
                "yamaha-av-receiver-ynca",
                "{\"remoteBindingValues\":{\"events\":{\"UPnPBeacon\":{"
                    + "\"node\":\"Discovery\",\"event\":\"Beacon\"}}}}");
            assertEquals("true", eval("tcp is None"));
            eval("YNCA_TCPPORT = " + simulator.getLocalPort());
            eval("remote_event_UPnPBeacon({'presentationurl':"
                + "'http://127.0.0.1:1234/device.xml'})");

            try (Socket connection = simulator.accept()) {
                assertEventuallyEquals("true", "_commsConnected");
                assertEquals("127.0.0.1", eval("local_event_DiscoveredIPAddress.getArg()"));
                eval("stopPollers()");
                eval("param_IPAddress = '192.0.2.1'; "
                    + "remote_event_UPnPBeacon({'presentationurl':'http://192.0.2.2/'})");
                assertEquals("127.0.0.1", eval("local_event_DiscoveredIPAddress.getArg()"));
                eval("local_event_DiscoveredIPAddress.persistNow() ");
            }

            node.close();
            Files.writeString(nodeDirectory.resolve("nodeConfig.json"), "{}");
            node = new PyNode(
                sharedHost,
                new SimpleName("yamahaYncaDiscoveryRemovedPilot"),
                nodeDirectory.toFile());
            assertEquals("127.0.0.1", eval("local_event_DiscoveredIPAddress.getArg()"));
            assertEquals("unbound", eval("str(lookup_remote_event('UPnPBeacon').getNode())"));
            assertEquals("true", eval("tcp is None"));
            assertEquals("Not configured", eval("local_event_Status.getArg().get('message')"));
        }
    }

    @Test
    public void appLauncherLoadsDisabledAndPreservesItsPublicBindings() throws Exception {
        loadRecipe("appLauncherDisabledPilot", "app-launcher");

        assertEquals("false", eval("_configured"));
        assertEquals("Not configured", eval("local_event_Status.getArg().get('message')"));
        assertEquals("Off", eval("local_event_Running.getArg()"));
        assertEquals(Set.of("Power", "PowerOn", "PowerOff"),
            reducedNames(node.getLocalActions().keySet()));
        assertEquals(Set.of(
            "Running", "DesiredPower", "Power", "LastStarted", "FirstInterrupted",
            "LastInterrupted", "PowerOn", "PowerOff", "Status"),
            reducedNames(node.getLocalEvents().keySet()));
        assertEquals(Set.of(
            "AppPath", "AppArgs", "AppWorkingDir", "PowerStateOnStart", "FeedbackFilters"),
            reducedNames(node.getParameters().keySet()));

        node.getLocalActions().get(new SimpleName("Power")).call("On");
        assertEquals("Off", eval("local_event_Running.getArg()"));
        assertEquals(
            "['--name', 'Peter Parker', '--count', '2']",
            eval("str(decodeArgList('--name \\\"Peter Parker\\\" --count 2'))"));
        assertEquals(
            "['Spider Man', '--define=a b', 'C:\\\\temp\\\\file.txt']",
            eval("str(decodeArgList('Spider\\\\ Man --define=\\\"a b\\\" C:\\\\temp\\\\file.txt'))"));
        assertEquals(
            "[\"O'Brien\", '\\\\\\\\server\\\\share']",
            eval("str(decodeArgList(\"O'Brien \\\\\\\\server\\\\share\"))"));
        assertEquals("true", eval("decodeArgList('\\\"unterminated') is None"));
        eval("param_FeedbackFilters = "
            + "({'type': 'Exclude', 'filter': str(i)} for i in range(1000))");
        eval("process_feedback('bounded feedback')");
        assertEquals("64", eval("next(param_FeedbackFilters).get('filter')"));
        eval("param_FeedbackFilters = ['invalid']");
        eval("process_feedback('malformed filter is ignored')");
        eval("param_FeedbackFilters = 7");
        eval("process_feedback('scalar filter collection is ignored')");
        assertEquals("5 mins ago", eval("toBriefTime(date_now().minusMinutes(5))"));
    }

    @Test
    public void appLauncherStartsAndStopsARealManagedProcess() throws Exception {
        String jshellExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("windows")
                ? "jshell.exe"
                : "jshell").toString();
        String config = "{\"paramValues\":{"
            + "\"AppPath\":" + jsonString(jshellExecutable) + ","
            + "\"PowerStateOnStart\":\"Off\"}}";

        loadRecipe("appLauncherManagedProcessPilot", "app-launcher", config);
        assertEquals("true", eval("_configured"));
        assertEquals("Off", eval("local_event_DesiredPower.getArg()"));

        node.getLocalActions().get(new SimpleName("Power On")).call(null);
        assertEventuallyEquals("On", "local_event_Running.getArg()", 12);
        assertEventuallyEquals("On", "local_event_Power.getArg()");
        assertEquals("true", eval("not is_blank(local_event_LastStarted.getArg())"));

        node.getLocalActions().get(new SimpleName("Power Off")).call(null);
        assertEventuallyEquals("Off", "local_event_Running.getArg()");
        assertEventuallyEquals("Off", "local_event_Power.getArg()");
        eval("statusCheck()");
        assertEquals("OK", eval("local_event_Status.getArg().get('message')"));
        assertEquals("true", eval("is_blank(local_event_LastInterrupted.getArg())"));

        node.getLocalActions().get(new SimpleName("Power On")).call(null);
        node.getLocalActions().get(new SimpleName("Power Off")).call(null);
        Thread.sleep(6500);
        assertEquals("Off", eval("local_event_DesiredPower.getArg()"));
        assertEquals("Off", eval("local_event_Running.getArg()"));
        assertEquals("true", eval("is_blank(local_event_LastInterrupted.getArg())"));

        node.getLocalActions().get(new SimpleName("Power On")).call(null);
        assertEventuallyEquals("On", "local_event_Running.getArg()", 12);
        node.getLocalActions().get(new SimpleName("Power Off")).call(null);
        node.getLocalActions().get(new SimpleName("Power On")).call(null);
        assertEventuallyEquals(
            "true",
            "_runningGeneration == _powerGeneration and local_event_Running.getArg() == 'On'",
            12);
        assertEquals("true", eval("is_blank(local_event_LastInterrupted.getArg())"));
        node.getLocalActions().get(new SimpleName("Power Off")).call(null);
        assertEventuallyEquals("Off", "local_event_Running.getArg()");

        node.getLocalActions().get(new SimpleName("Power On")).call(null);
        assertEventuallyEquals("On", "local_event_Running.getArg()", 12);
        eval("_process.stop()");
        assertEventuallyEquals("Off", "local_event_Running.getArg()");
        assertEquals("true", eval("not is_blank(local_event_FirstInterrupted.getArg())"));
        assertEquals("true", eval("not is_blank(local_event_LastInterrupted.getArg())"));
        node.getLocalActions().get(new SimpleName("Power Off")).call(null);
    }

    @Test
    public void appLauncherRestoresPersistedOnAndRecordsAnInterruption() throws Exception {
        String jshellExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("windows")
                ? "jshell.exe"
                : "jshell").toString();
        String config = "{\"paramValues\":{"
            + "\"AppPath\":" + jsonString(jshellExecutable) + ","
            + "\"PowerStateOnStart\":\"Off\"}}";

        loadRecipe("appLauncherPersistedOnPilot", "app-launcher", config);
        node.getLocalActions().get(new SimpleName("Power On")).call(null);
        assertEventuallyEquals("On", "local_event_Running.getArg()", 12);
        eval("local_event_DesiredPower.persistNow()");
        assertEventuallyEventSeedCount(1, true);

        node.close();
        Files.writeString(
            nodeDirectory.resolve("nodeConfig.json"),
            "{\"paramValues\":{"
                + "\"AppPath\":" + jsonString(jshellExecutable) + ","
                + "\"PowerStateOnStart\":\"(previous)\"}}");
        node = new PyNode(
            sharedHost,
            new SimpleName("appLauncherPersistedOnPilotReloaded"),
            nodeDirectory.toFile());

        assertEquals("On", eval("local_event_DesiredPower.getArg()"));
        assertEventuallyEquals("On", "local_event_Running.getArg()", 12);
        assertEquals("0", eval("str(_runningGeneration)"));
        eval("_process.stop()");
        assertEventuallyEquals("Off", "local_event_Running.getArg()");
        assertEquals("true", eval("not is_blank(local_event_FirstInterrupted.getArg())"));
        assertEquals("true", eval("not is_blank(local_event_LastInterrupted.getArg())"));
        node.getLocalActions().get(new SimpleName("Power Off")).call(null);
    }

    @Test
    public void frontendMk2CreatesBoundedDynamicBindingsFromNodeContent() throws Exception {
        String indexXml = """
            <dashboard title="Pilot">
              <section title="Controls">
                <button join="Projector Power"/>
                <slider action="Volume" event="Level"/>
                <button action="Local Action"/>
                <text event="Local Event"/>
              </section>
            </dashboard>
            """;
        String schemasJson = "{"
            + "\"button\":{\"type\":\"boolean\"},"
            + "\"slider_action\":{\"type\":\"number\"},"
            + "\"slider_signal\":{\"type\":\"number\"}}";
        String config = "{\"paramValues\":{"
            + "\"suggestedNode\":\"Simulator Device\","
            + "\"localOnlyActions\":\"Local Action\","
            + "\"localOnlySignals\":\"Local Event\"}}";

        loadRecipeWithFiles(
            "frontendMk2BindingsPilot",
            "frontend-mk2",
            config,
            Map.of(
                "content/index.xml", indexXml,
                "content/schemas.json", schemasJson));

        assertEquals(Set.of(
            "CreateFromSample", "ProjectorPower", "Volume", "LocalAction"),
            reducedNames(node.getLocalActions().keySet()));
        assertEquals(Set.of("Clock", "ProjectorPower", "Level", "LocalEvent"),
            reducedNames(node.getLocalEvents().keySet()));
        assertEquals(Set.of("ProjectorPower", "Volume"),
            reducedNames(node.getRemoteActions().keySet()));
        assertEquals(Set.of("ProjectorPower", "Level"),
            reducedNames(node.getRemoteEvents().keySet()));
        assertEquals(Set.of("suggestedNode", "localOnlySignals", "localOnlyActions"),
            reducedNames(node.getParameters().keySet()));
        assertEquals("number", eval("dynamicActions['Volume'].getSchema().get('type')"));
        assertEquals("number", eval("dynamicEvents['Level'].getArgSchema().get('type')"));

        node.getLocalActions().get(new SimpleName("Local Action")).call(Integer.valueOf(7));
        eval("remoteEventHandlers['Level'](42)");
        assertEquals("42", eval("str(dynamicEvents['Level'].getArg())"));
        assertThrows(Exception.class, () -> eval(
            "reducedNames(" + pythonString("x,".repeat(513)) + ", 'test')"));
        assertThrows(Exception.class, () -> eval(
            "boundedBindingName('x' * (MAX_BINDING_NAME_LENGTH + 1))"));
        assertThrows(Exception.class, () -> eval("boundedBindingName('--')"));

        Path oversized = nodeDirectory.resolve("oversized.xml");
        Files.writeString(oversized, "x".repeat((256 * 1024) + 1));
        assertThrows(Exception.class, () -> eval(
            "readBoundedText(" + pythonString(oversized.toString()) + ", 'test')"));

        Path dtd = nodeDirectory.resolve("dtd.xml");
        Files.writeString(dtd, "<!DOCTYPE x [<!ENTITY y 'z'>]><x>&y;</x>");
        assertThrows(Exception.class, () -> eval(
            "loadIndexFile(" + pythonString(dtd.toString()) + ")"));

        Path utf16Dtd = nodeDirectory.resolve("utf16-dtd.xml");
        Files.write(
            utf16Dtd,
            "<!DOCTYPE x [<!ENTITY y 'z'>]><x>&y;</x>"
                .getBytes(StandardCharsets.UTF_16LE));
        assertThrows(Exception.class, () -> eval(
            "loadIndexFile(" + pythonString(utf16Dtd.toString()) + ")"));
    }

    @Test
    public void frontendMk2RejectsLateInvalidDefinitionsWithoutPartialBindings()
            throws Exception {
        StringBuilder invalidIndex = new StringBuilder(
            "<dashboard><button join=\"Partial Binding\"/>");
        for (int depth = 0; depth < 66; depth++)
            invalidIndex.append("<section>");
        for (int depth = 0; depth < 66; depth++)
            invalidIndex.append("</section>");
        invalidIndex.append("</dashboard>");

        loadRecipeWithFiles(
            "frontendMk2AtomicValidationPilot",
            "frontend-mk2",
            null,
            Map.of("content/index.xml", invalidIndex.toString()));

        assertEquals(Set.of("CreateFromSample"), reducedNames(node.getLocalActions().keySet()));
        assertEquals(Set.of("Clock"), reducedNames(node.getLocalEvents().keySet()));
        assertTrue(node.getRemoteActions().isEmpty());
        assertTrue(node.getRemoteEvents().isEmpty());
        assertEquals("0", eval("str(len(dynamicActions) + len(dynamicEvents))"));
    }

    @Test
    public void frontendMk2RejectsSampleSymlinkEscapes() throws Exception {
        loadRecipe("frontendMk2SamplePilot", "frontend-mk2");

        Path externalDirectory = Files.createTempDirectory("nodel-frontend-external-");
        Path linkedContent = nodeDirectory.resolve("content");
        try {
            try {
                Files.createSymbolicLink(linkedContent, externalDirectory);
                Path linkedSample = nodeDirectory.resolve("linked-sample.xml");
                Files.writeString(linkedSample, "<dashboard/>");
                assertEquals("false", eval(
                    "createFromSample(False, " + pythonString(linkedSample.toString()) + ")"));
                assertTrue(!Files.exists(externalDirectory.resolve("index.xml")));
                Files.delete(linkedContent);
            } catch (UnsupportedOperationException | SecurityException | FileSystemException e) {
                assumeTrue(false, "Symlink creation unavailable: " + e.getMessage());
            }
        } finally {
            Files.deleteIfExists(linkedContent);
            deleteDirectory(externalDirectory);
        }
    }

    @Test
    public void frontendMk2CopiesOnlyABoundedSampleAndNeverOverwrites() throws Exception {
        loadRecipe("frontendMk2SamplePilot", "frontend-mk2");
        assertTrue(reducedNames(node.getLocalActions().keySet()).contains("CreateFromSample"));

        Path oversized = nodeDirectory.resolve("oversized-sample.xml");
        Files.writeString(oversized, "x".repeat((256 * 1024) + 1));
        assertEquals("false", eval(
            "createFromSample(False, " + pythonString(oversized.toString()) + ")"));
        assertTrue(!Files.exists(nodeDirectory.resolve("content").resolve("index.xml")));

        Path sample = nodeDirectory.resolve("sample.xml");
        Files.writeString(sample, "<dashboard title=\"Sample\"/>");
        assertEquals("true", eval(
            "createFromSample(False, " + pythonString(sample.toString()) + ")"));
        Path destination = nodeDirectory.resolve("content").resolve("index.xml");
        assertEquals("<dashboard title=\"Sample\"/>", Files.readString(destination));

        Files.writeString(sample, "<dashboard title=\"Replacement\"/>");
        assertEquals("false", eval(
            "createFromSample(False, " + pythonString(sample.toString()) + ")"));
        assertEquals("<dashboard title=\"Sample\"/>", Files.readString(destination));
    }

    private void loadRecipe(String nodeName, String recipeName) throws IOException {
        loadRecipe(nodeName, recipeName, null);
    }

    private void loadRecipe(String nodeName, String recipeName, String configJson) throws IOException {
        loadRecipeWithFiles(nodeName, recipeName, configJson, Map.of());
    }

    private void loadRecipeWithFiles(
            String nodeName,
            String recipeName,
            String configJson,
            Map<String, String> files) throws IOException {
        Path script = locateRecipesDirectory().resolve(recipeName).resolve("script.py");
        assertTrue(Files.isRegularFile(script), "Recipe script not found: " + script);

        nodeDirectory = Files.createTempDirectory("nodel-python3-pilot-node-");
        Files.copy(script, nodeDirectory.resolve("script.py"));
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path destination = nodeDirectory.resolve(entry.getKey()).normalize();
            assertTrue(destination.startsWith(nodeDirectory), "Test file escaped node directory");
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, entry.getValue());
        }
        if (configJson != null)
            Files.writeString(nodeDirectory.resolve("nodeConfig.json"), configJson);
        node = new PyNode(sharedHost, new SimpleName(nodeName), nodeDirectory.toFile());
    }

    private static String jsonString(String value) {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n") + "\"";
    }

    private static String pythonString(String value) {
        return "'" + value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "\\r")
            .replace("\n", "\\n") + "'";
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

    private static String readCrLfCommand(InputStream input) throws IOException {
        ByteArrayOutputStream command = new ByteArrayOutputStream();
        for (;;) {
            int value = input.read();
            if (value < 0)
                throw new IOException("Simulator connection closed before a command arrived");
            if (value == '\n')
                return command.toString(StandardCharsets.US_ASCII);
            if (value != '\r')
                command.write(value);
            if (command.size() >= 256)
                throw new IOException("Simulator received an oversized command");
        }
    }

    private static DatagramPacket receiveDatagramPacket(DatagramSocket receiver)
            throws IOException {
        byte[] buffer = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        receiver.receive(packet);
        return packet;
    }

    private static byte[] receiveDatagram(DatagramSocket receiver) throws IOException {
        DatagramPacket packet = receiveDatagramPacket(receiver);
        return Arrays.copyOf(packet.getData(), packet.getLength());
    }

    private static void serveExtronIn16xxStartup(
            Socket connection,
            String greeting,
            String firmwareDate,
            String[] inputResponses) throws IOException {
        connection.setSoTimeout(10000);
        InputStream input = connection.getInputStream();
        OutputStream output = connection.getOutputStream();
        output.write((greeting + "\r\n" + firmwareDate + "\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        output.flush();

        for (int request = 0; request < 3; request++) {
            String command = readCrLfCommand(input);
            String response = switch (command) {
                case "&" -> inputResponses[0];
                case "$" -> inputResponses[1];
                case "!" -> inputResponses[2];
                default -> throw new IOException(
                    "Unexpected Extron IN16XX simulator command: " + command);
            };
            output.write((response + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
    }

    private static void assertEventuallyContains(Collection<String> values, String expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (values.contains(expected))
                return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);

        assertTrue(values.contains(expected), "Missing " + expected + " in " + values);
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
