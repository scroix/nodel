package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.channels.FileLock;

import org.joda.time.DateTime;
import org.nodel.StartupException;
import org.nodel.Threads;
import org.nodel.Version;
import org.nodel.core.Nodel;
import org.nodel.host.BootstrapConfig;
import org.nodel.io.Files;
import org.nodel.io.Packages;
import org.nodel.io.Stream;
import org.nodel.json.JSONArray;
import org.nodel.json.JSONException;
import org.nodel.json.JSONObject;
import org.nodel.logging.slf4j.SimpleLogger;
import org.nodel.reflection.Objects;
import org.nodel.reflection.Schema;
import org.nodel.reflection.Serialisation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.JDK14LoggingHandler;

import org.nanohttpd.protocols.http.NanoHTTPD;

// --- GraalVM-specific imports ---
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

/**
 * Main program entry-point (migrated from Jython to GraalVM's Python).
 */
public class Launch {

    /**
     * Program version.
     */
    public final static String VERSION = Version.shared().version;

    /**
     * (initialised late in 'initLogging' depending on config)
     */
    private Logger _logger;

    /**
     * Contains the least amount of config required to get the program running.
     */
    private BootstrapConfig _bootstrapConfig;

    /**
     * The root directory this program will use.
     * (overridden by bootstrap)
     */
    private File _root = new File(".");

    /**
     * The nodel host
     */
    protected NodelHost _nodelHost;

    /**
     * A locking mechanism to prevent unintended duplicate instances.
     * Is never released.
     */
    private FileLock _hostInstanceLock = null;

    /**
     * Holds the program arguments.
     */
    private static String[] s_processArgs;

    /**
     * A shared GraalVM Python context (if your application needs a global context).
     */
    private static Context s_graalPythonContext;

    /**
     * (see full constructor)
     */
    public Launch() throws StartupException, IOException, JSONException {
        this(null, null);
    }

    /**
     * (see full constructor)
     */
    public Launch(String[] args) throws StartupException, IOException, JSONException {
        this(null, args);
    }

    /**
     * @param workingDirectory A non-default working directory instead of "." (current folder)
     * @param args A set of arguments (normally from the command-line)
     */
    public Launch(File workingDirectory, String[] args) throws StartupException, IOException, JSONException {
        File lastErrorFile = new File("_lastError.txt");

        try {
            if (workingDirectory != null)
                _root = workingDirectory;

            if (args != null)
                s_processArgs = args;

            bootstrap();
            start();

            lastErrorFile.delete();

        } catch (Exception exc) {
            // dump to file first before throwing
            try (PrintWriter pw = new PrintWriter(lastErrorFile)) {
                pw.println(DateTime.now());
                exc.printStackTrace(pw);
                pw.flush();
            }
            throw exc;
        }
    }

    /**
     * Console launch entry-point.
     */
    public static void main(String[] args) throws IOException, JSONException, StartupException {
        Launch launch = new Launch(args);

        System.out.println("Nodel [GraalPython] v" + VERSION + " is running.");
        System.out.println();
        System.out.println("Press Enter to initiate a shutdown.");
        System.out.println();

        tryReadFromConsole();

        System.out.println("Shutdown initiated...");

        launch.shutdown();

        System.out.println("Finished.");
    }

    /**
     * In some console-less environments (e.g. javaw.exe), stdin may be invalid but should not
     * prevent start up.
     */
    private static void tryReadFromConsole() {
        try {
            System.in.read();
        } catch (IOException exc) {
            // unlikely this will be seen anywhere but dump anyway
            System.err.println("(Running in console-less mode. To terminate process, kill manually or via /diagnostics)");

            // no signals available so just sleep
            Threads.sleep(Long.MAX_VALUE);
        }
    }

    /**
     * Performs entire bootstrap process.
     * (can throw exceptions)
     */
    private void bootstrap() throws IOException, JSONException, StartupException {
        String bootStrapPrefix = "bootstrap";

        // dump the bootstrap config schema if it hasn't already been dumped
        Object schema = Schema.getSchemaObject(BootstrapConfig.class);
        String schemaString = Serialisation.serialise(schema, 4);

        // load for the bootstrap config schema file
        File bootstrapSchemaFile = new File(_root, "_" + bootStrapPrefix + "_schema.json");
        String bootstrapSchemaFileString = null;
        if (bootstrapSchemaFile.exists()) {
            bootstrapSchemaFileString = Stream.readFully(bootstrapSchemaFile);
        }

        // only write out the schema file if it's different or never been written out
        if (bootstrapSchemaFileString == null || !bootstrapSchemaFileString.equals(schemaString)) {
            Stream.writeFully(bootstrapSchemaFile, schemaString);
        }

        // dump the example config schema if it hasn't already been dumped
        String exampleBootstrapString = Serialisation.serialise(BootstrapConfig.Example, 4);

        // load for the bootstrap config schema file
        File exampleBootstrapFile = new File(_root, "_" + bootStrapPrefix + "_example.json");
        String exampleBootstrapFileString = null;
        if (exampleBootstrapFile.exists()) {
            exampleBootstrapFileString = Stream.readFully(exampleBootstrapFile);
        }

        // only write out the schema file if it's different or never been written out
        if (exampleBootstrapFileString == null || !exampleBootstrapFileString.equals(exampleBootstrapString)) {
            Stream.writeFully(exampleBootstrapFile, exampleBootstrapString);
        }

        // now look for the bootstrap config itself
        File bootstrapFile = new File(_root, bootStrapPrefix + ".json");
        if (bootstrapFile.exists()) {
            _bootstrapConfig = (BootstrapConfig) Serialisation.coerceFromJSON(
                    BootstrapConfig.class, Stream.readFully(bootstrapFile));
        } else {
            _bootstrapConfig = new BootstrapConfig();
        }

        // override with any command-line arguments
        _bootstrapConfig.overrideWith(s_processArgs);

        // provide the REST API schema too
        Object apiSchema = Schema.getSchemaObject(NodelHostHTTPD.RESTModel.class);
        File apiSchemaFile = new File(_root, "_API" + "_schema.json");
        Object existing = apiSchemaFile.exists()
                ? Serialisation.deserialise(Object.class, Stream.readFully(apiSchemaFile))
                : null;
        if (!Objects.sameValue(apiSchema, existing)) {
            Stream.writeFully(apiSchemaFile, Serialisation.serialise(apiSchema, 4));
        }

        initLogging();
    }

    /**
     * Logging related initialisation.
     */
    private void initLogging() {
        try {
            File loggingDir = null;

            if (_bootstrapConfig.getEnableProgramLogging()) {
                loggingDir = prepareDirectory("logs", _root, _bootstrapConfig.getLogsDirectory());
                SimpleLogger.setFolderOut(loggingDir);
            }

            _logger = LoggerFactory.getLogger(Launch.class.getName());

            if (loggingDir != null)
                System.out.println("    (file logging is enabled [" + loggingDir.getAbsolutePath() + "])\n");

            // deal with all the known third-party logging frameworks
            JDK14LoggingHandler.init();

        } catch (Exception exc) {
            System.err.println("Logging configuration failure; logging may or may not be functional.");
            exc.printStackTrace();
        }
    }

    /**
     * Configures the GraalVM Python context.
     * Replaces the old Jython-based initialisePython() code.
     */
    private static void initialiseGraalPython() {
        // Create a GraalVM Engine if needed (optional).
        Engine engine = Engine.newBuilder().build();

        // Build a Python context, allowing necessary capabilities.
        s_graalPythonContext = Context.newBuilder("python")
                .allowAllAccess(true)   // Carefully consider security
                .engine(engine)
                .build();

        // Example usage:
        // s_graalPythonContext.eval("python", "print('Hello from GraalPython')");
        //
        // Adapting JSON objects to Python would require
        // manual conversion or different bridging strategy
        // if you rely on dynamic transformations.
    }

    private void start() throws IOException {
        // immediately prevent unintended duplicate instances
        createHostInstanceLockOrFail();

        // opt-in interfaces
        String[] interfaces = _bootstrapConfig.getNetworkInterfaces();
        if (interfaces != null && interfaces.length > 0) {
            Nodel.setInterfacesToUse(_bootstrapConfig.getNetworkInterfaces());
        }

        // check if advertisements should be disabled
        if (_bootstrapConfig.getDisableAdvertisements()) {
            Nodel.setDisableServerAdvertisements(true);
        }

        // use specific Nodel Messaging TCP port? (and UDP which is reserved for future use)
        int requestedMessagingPort = _bootstrapConfig.getMessagingPort();
        if (requestedMessagingPort > 0) {
            // best to quickly check port availability if fixed ports are being used and choose to fail
            // immediately.
            try {
                ServerSocket ss = new ServerSocket(requestedMessagingPort);
                ss.close();
            } catch (Exception exc) {
                throw new IOException("Could not bind Nodel Messaging port on TCP "
                        + requestedMessagingPort, exc);
            }
            Nodel.updateMessagingPort(requestedMessagingPort);
        }

        // Instead of Jython initialization, use GraalPython
        initialiseGraalPython();

        _logger.info("Nodel [GraalPython] is starting... version=" + VERSION);

        // Only relative paths will be allowed

        // verify the content directory exists (or can be created)
        File embeddedContentDirectory = prepareDirectory("embedded content", _root,
                _bootstrapConfig.getContentDirectory());

        // prepare 'custom' which holds user and admin folders
        File customRoot = prepareDirectory("custom", _root, "custom");

        // prepare 'custom content' which holds custom content files (used as first preference)
        File customContentDirectory = prepareDirectory("custom content", customRoot, "content");

        File recipesRoot = prepareDirectory("recipes", _root, _bootstrapConfig.recipesRoot);

        // now the kick off the httpd end-point arbitrary bound or by port request
        NodelHostHTTPD nodelHostHTTPD = null;
        int requestedPort = _bootstrapConfig.getNodelHostPort();
        int tryPort = requestedPort;

        File lastHTTPPortCache = new File(".lastHTTPPort");
        int lastHTTPPort = lastHTTPPortCache.exists()
                ? Integer.parseInt(Stream.readFully(lastHTTPPortCache))
                : 0;

        if (requestedPort <= 0) {
            // ideally use 8085 as an arbitrary port
            tryPort = lastHTTPPort == 0 ? 8085 : lastHTTPPort;
        }

        // have got config now (default one or one from disk) so
        // fire up pyNode console
        File nodelRoot = prepareDirectory("nodelRoot", _root, _bootstrapConfig.getNodelRoot());

        _nodelHost = new NodelHost(nodelRoot,
                _bootstrapConfig.getInclFilters(),
                _bootstrapConfig.getExclFilters(),
                recipesRoot);

        // make two attempts to bind to an arbitrary port (try previously used port first),
        // or one attempt to bind to a requested one.
        for (int a = 0; a < 2; a++) {
            try {
                nodelHostHTTPD = new NodelHostHTTPD(tryPort, embeddedContentDirectory);
                nodelHostHTTPD.setFirstChoiceDir(customContentDirectory);
                nodelHostHTTPD.setNodeHost(_nodelHost);

                // kick off the HTTPD
                nodelHostHTTPD.start(); // throws exception if any

                // update with actual listening port
                Nodel.setHTTPPort(nodelHostHTTPD.getListeningPort());

            } catch (Exception exc) {
                // port would be in use
                if (nodelHostHTTPD != null) {
                    nodelHostHTTPD.stop();
                    nodelHostHTTPD = null;
                }

                // specific port was requested?
                if (requestedPort > 0) {
                    if (exc instanceof BindException)
                        // provide instructive feedback if possible
                        throw new BindException("Cannot bind to TCP port " + requestedPort
                                + "; another process must already be bound to the port; "
                                + "use '-p 0' if \"any port\" binding is preferred");
                    else
                        throw exc;
                }

                // try any port on second attempt
                tryPort = 0;
                continue;
            }
            break;
        }

        // if this is the first time launch has run
        boolean firstTime = false;

        // retrieve '.version' file to determine whether embedded packages need to be extracted
        String version = null;
        File versionFile = new File(_root, ".version");
        if (versionFile.exists()) {
            try {
                version = Stream.readFully(versionFile);
            } catch (Exception exc) {
                // ignore
            }
        } else {
            firstTime = true;
        }

        // extract the embedded content if different versions or content directory is empty
        if (!VERSION.equalsIgnoreCase(version) || embeddedContentDirectory.list().length == 0) {
            _logger.info("Previous version (if any) is different to current version or the embedded content folder is empty. Cleaning out contents of package folders (if present) and extracting current...");

            long deleted;
            deleted = Files.tryFlushDir(embeddedContentDirectory);
            _logger.info("For 'content', flushed " + deleted + " file(s).");

            extractEmbeddedPackage("content", embeddedContentDirectory);

            _logger.info("'admin' and 'content' packages have been extracted.");

            // update the version stamp so extraction isn't done again
            Stream.writeFully(versionFile, VERSION);
        }

        // stamp the cache if it's a different port
        if (lastHTTPPort != Nodel.getHTTPPort()) {
            Stream.writeFully(lastHTTPPortCache, String.valueOf(Nodel.getHTTPPort()));
        }

        _logger.info("HTTP interface bound to TCP port " + Nodel.getHTTPPort());

        _logger.info("All interfaces and end-points have been successfully launched. httpPort="
                + nodelHostHTTPD.getPort());

        if (_bootstrapConfig.getDisableAdvertisements())
            _logger.info("(advertisements are disabled)");

        if (firstTime)
            firstTimePrep();
    }

    /**
     * If this is the very first time Nodel has started, extract the default node.
     */
    private void firstTimePrep() {
        _logger.info("This the first run ever; extracting 'first_node.py' if no other nodes exist");

        // only continue if some nodes exist
        if (_nodelHost.getRoot().list().length > 0)
            return;

        // create folder (designated temporary) for later rename
        File nodeFolder = new File(_nodelHost.getRoot(), "_tmpFirstNode");
        nodeFolder.mkdirs();

        try (InputStream is = PyNode.class.getResourceAsStream("first_node.py")) {
            File file = new File(nodeFolder, "script.py");
            Stream.writeFully(is, file);
        } catch (Exception exc) {
            // ignore
        }

        // rename (move) last to avoid file write collision
        String nodeName = String.format("Nodel Recipes Sync for $HOSTNAME ${http port} (${os.name}, ${mac})");
        nodeFolder.renameTo(new File(_nodelHost.getRoot(), nodeName));
    }

    /**
     * (is never unlocked)
     */
    @SuppressWarnings("resource")
    private void createHostInstanceLockOrFail() throws RuntimeException {
        Exception exc = null;
        File lockFile = new File(_root, "_instance.lock");
        try {
            _hostInstanceLock = new RandomAccessFile(lockFile, "rw").getChannel().tryLock();
        } catch (IOException ioExc) {
            exc = ioExc;
        }

        if (_hostInstanceLock == null) {
            String message = "Could not create a Nodel host instance lock in the designated working directory ("
                    + lockFile.getAbsolutePath()
                    + "). Is another host running there? Are there file permissions issues?";
            System.err.println(message);

            if (exc == null)
                throw new RuntimeException(message);
            else
                throw new RuntimeException(message, exc);
        }
    }

    /**
     * Utility method to ensure a directory exists, failing if it's not possible
     * to create one.
     */
    private static File prepareDirectory(String type, File root, String dirString) throws IOException {
        File dir = new File(dirString);
        if (!dir.isAbsolute())
            dir = new File(root, dirString);

        if (dir.exists() && !dir.isDirectory())
            throw new IOException("'" + type + "' directory exists but is not a directory.");

        // make the directory if one doesn't already exist
        if (!dir.exists())
            if (!dir.mkdirs())
                throw new IOException("Could not create '" + type + "' directory.");

        return dir;
    }

    /**
     * Extracts an embedded package, will generally only be called once.
     * @throws IOException
     */
    private void extractEmbeddedPackage(String packageType, File outDirectory) throws IOException {
        // use the default package if necessary
        _logger.info("Unzipping embedded  '" + packageType + "' package...");

        try (InputStream is = BootstrapConfig.class.getResourceAsStream(packageType + ".zip")) {
            if (is != null) {
                if (!Packages.unpackZip(is, outDirectory))
                    throw new IOException("Could not extract package '" + packageType + "'");
            }
        }
    }

    /**
     * Permanently shuts down the nodel host
     */
    public void shutdown() {
        if (_nodelHost != null)
            _nodelHost.shutdown();
    }
}