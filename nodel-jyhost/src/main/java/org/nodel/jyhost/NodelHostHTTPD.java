package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownServiceException;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.joda.time.DateTime;
import org.nodel.SimpleName;
import org.nodel.Strings;
import org.nodel.core.Nodel;
import org.nodel.core.NodelClients.NodeURL;
import org.nodel.diagnostics.Diagnostics;
import org.nodel.discovery.AdvertisementInfo;
import org.nodel.discovery.AutoDNS;
import org.nodel.discovery.TopologyWatcher;
import org.nodel.host.BaseNode;
import org.nodel.io.Stream;
import org.nodel.io.UTF8Charset;
import org.nodel.json.XML;
import org.nodel.logging.LogEntry;
import org.nodel.logging.Logging;
import org.nodel.reflection.Param;
import org.nodel.reflection.Serialisation;
import org.nodel.reflection.SerialisationException;
import org.nodel.reflection.Service;
import org.nodel.reflection.Value;
import org.nodel.rest.EndpointNotFoundException;
import org.nodel.rest.REST;
import org.nodel.websockets.WebSocketInterceptor;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.request.Request;
import org.nanohttpd.protocols.http.response.IStatus;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// GraalVM-specific exception
import org.graalvm.polyglot.PolyglotException;

/**
 * Replacement for NodelHostHTTPD, removing Jython references and using GraalVM Python.
 */
public class NodelHostHTTPD extends NanoHTTPD {

    private static AtomicLong s_instance = new AtomicLong();
    protected long _instance = s_instance.getAndIncrement();

    protected Logger _logger = LoggerFactory.getLogger(this.getClass().getName() + "_" + _instance);

    private String _userAgent;

    /**
     * The REST model exposed by this HTTP server.
     */
    public class RESTModel {

        @Service(name = "nodes", order = 1, title = "Nodes", desc = "Node lookup by Node name.", genericClassA = SimpleName.class, genericClassB = BaseNode.class)
        public AbstractMap<SimpleName, BaseNode> nodes = new AbstractMap<SimpleName, BaseNode>() {
            @Override
            public Set<Map.Entry<SimpleName, BaseNode>> entrySet() {
                return BaseNode.getNodes().entrySet();
            }

            @Override
            public BaseNode get(Object key) {
                return BaseNode.getNode((SimpleName) key);
            }
        };

        @Value(name = "nodes", order = 1, title = "Nodes", desc = "All the managed nodes.", genericClassA = SimpleName.class, genericClassB = BaseNode.class)
        public Map<SimpleName, BaseNode> getNodes() {
            return BaseNode.getNodes();
        }

        @Service(name = "recipes", order = 1, title = "Recipes", desc = "Recipes that new nodes can be based on", genericClassA = String.class)
        public RecipesEndPoint recipes() {
            return _nodelHost.recipes();
        }

        @Value(name = "started", title = "Started", desc = "When the host started.")
        public DateTime __started = DateTime.now();

        @Service(name = "allNodes", order = 5, title = "All nodes", desc = "Returns all the advertised nodes.")
        public Collection<AdvertisementInfo> getAllNodes() {
            return _nodelHost.getAdvertisedNodes();
        }

        @Service(name = "discovery", order = 6, title = "Discovery service", desc = "Multicast discovery services.")
        public AutoDNS discovery() {
            return AutoDNS.instance();
        }

        @Service(name = "nodeURLs", order = 6, title = "Node URLs", desc = "Returns the addresses of all advertised nodes.")
        public List<NodeURL> nodeURLs(@Param(name = "filter", title = "Filter", desc = "Optional string filter.") String filter)
                throws IOException {
            return _nodelHost.getNodeURLs(filter);
        }

        @Service(name = "nodeURLsForNode", order = 6, title = "Node URLs", desc = "Returns the addresses of all advertised nodes.")
        public List<NodeURL> nodeURLsForNode(@Param(name = "name") SimpleName name) throws IOException {
            return _nodelHost.getNodeURLsForNode(name);
        }

        @Service(name = "logs", title = "Logs", desc = "Detailed program logs.")
        public LogEntry[] getLogs(
                @Param(name = "from", title = "From", desc = "Start inclusion point.") long from,
                @Param(name = "max", title = "Max", desc = "Results count limit.") int max) {
            List<LogEntry> result = Logging.instance().getLogs(from, max);
            return result.toArray(new LogEntry[result.size()]);
        }

        @Service(name = "warningLogs", title = "Warning logs", desc = "Same as 'logs' except filtered by warning-level.")
        public LogEntry[] getWarningLogs(
                @Param(name = "from", title = "From", desc = "Start inclusion point.") long from,
                @Param(name = "max", title = "Max", desc = "Results count limit.") int max) {
            List<LogEntry> result = Logging.instance().getWarningLogs(from, max);
            return result.toArray(new LogEntry[result.size()]);
        }

        @Service(name = "diagnostics", order = 6, title = "Diagnostics", desc = "Diagnostics related to the entire framework.")
        public Diagnostics framework() {
            return Diagnostics.shared();
        }

        @Service(name = "newNode", order = 7, title = "New node", desc = "Creates a new node.")
        public void newNode(@Param(name = "base") String base, SimpleName name) {
            _nodelHost.newNode(base, name);
        }

        @Service(name = "toolkit", title = "Toolkit", desc = "The toolkit reference.")
        public Info getToolkitReference() throws IOException {
            try (InputStream nodetoolkitStream = PyNode.class.getResourceAsStream("nodetoolkit.py")) {
                Info info = new Info();
                info.script = Stream.readFully(nodetoolkitStream);
                return info;
            }
        }
    }

    public static class Info {
        @Value(name = "script")
        public String script;
    }

    private NodelHost _nodelHost;
    private RESTModel _restModel = new RESTModel();

    private final TopologyWatcher.ChangeHandler _topologyWatcherChangeHandler = new TopologyWatcher.ChangeHandler() {
        @Override
        public void handle(List<InetAddress> appeared, List<InetAddress> disappeared) {
            handleTopologyChange(appeared, disappeared);
        }
    };

    public NodelHostHTTPD(int port, File directory) throws IOException {
        super(port, directory, false);

        TopologyWatcher.shared().addOnChangeHandler(_topologyWatcherChangeHandler);

        init();
    }

    /**
     * Setup additional interceptors, etc.
     */
    private void init() {
        WebSocketInterceptor wsInterceptor = new WebSocketInterceptor();
        addHTTPInterceptor(wsInterceptor);
    }

    @Override
    public void stop() {
        super.stop();
        if (_topologyWatcherChangeHandler != null) {
            TopologyWatcher.shared().removeOnChangeHandler(_topologyWatcherChangeHandler);
        }
    }

    public void setNodeHost(NodelHost value) {
        _nodelHost = value;
    }

    @Override
    public Response serve(String uri, File root, String method, Properties params, Request request) {
        _logger.debug("Serving '" + uri + "'...");

        // track the user-agent if present
        String userAgent = request.header.getProperty("user-agent");
        if (userAgent != null)
            _userAgent = userAgent;

        // Decide whether we’re dealing with a node subfolder or top-level
        Object restTarget = _restModel;
        String[] parts = (uri.startsWith("/") ? uri.substring(1) : uri).split("/");

        // e.g. /nodes/nodeName/...
        if (parts.length >= 2 && parts[0].equalsIgnoreCase("nodes")) {
            SimpleName nodeName = new SimpleName(parts[1]);
            BaseNode node = BaseNode.getNode(nodeName);
            if (node == null)
                return prepareNotFoundResponse(uri, "Node");

            // ensure trailing slash
            if (parts.length == 2 && !uri.endsWith("/"))
                return prepareRedirectResponse(encodeUri(uri + "/"));

            File nodeRoot = node.getRoot();
            root = new File(nodeRoot, "content");
            restTarget = node;

            // Rebuild 'uri' and 'parts' but drop the first 2 elements
            int OFFSET = 2;
            StringBuilder sb = new StringBuilder();
            String[] newParts = new String[parts.length - OFFSET];
            for (int a = OFFSET; a < parts.length; a++) {
                String p = parts[a];
                sb.append('/').append(p);
                newParts[a - OFFSET] = p;
            }
            if (sb.length() == 0)
                sb.append('/');
            uri = sb.toString();
            parts = newParts;
        }

        // Check for REST usage: e.g. /REST/...
        if (parts.length > 0 && parts[0].equals("REST")) {
            // drop 'REST'
            String[] newParts = new String[parts.length - 1];
            System.arraycopy(parts, 1, newParts, 0, parts.length - 1);
            parts = newParts;

            try {
                Object target;
                if (method.equalsIgnoreCase("GET")) {
                    target = REST.resolveRESTcall(restTarget, parts, params, null);
                } else if (method.equalsIgnoreCase("POST")) {
                    target = REST.resolveRESTcall(restTarget, parts, params, request.raw);
                } else {
                    throw new UnknownServiceException("Unexpected method - '" + method + "'");
                }

                if (target instanceof Response) {
                    Response resp = (Response) target;
                    resp.addHeader("Access-Control-Allow-Origin", "*");
                    return resp;
                } else {
                    // Convert to JSON
                    String json = Serialisation.serialise(target);
                    Response resp = new Response(Status.OK, "application/json; charset=utf-8", json);
                    resp.addHeader("Access-Control-Allow-Origin", "*");
                    return resp;
                }
            } catch (EndpointNotFoundException exc) {
                return prepareExceptionMessageResponse(Status.NOT_FOUND, exc, false);
            } catch (FileNotFoundException exc) {
                return prepareExceptionMessageResponse(Status.NOT_FOUND, exc, false);
            } catch (SerialisationException exc) {
                return prepareExceptionMessageResponse(Status.INTERNAL_ERROR, exc, params.containsKey("trace"));
            } catch (UnknownServiceException exc) {
                return prepareExceptionMessageResponse(Status.INTERNAL_ERROR, exc, false);
            } catch (PolyglotException exc) {
                // Was PyException in Jython; now we catch PolyglotException for Python errors
                _logger.warn("Python script exception during REST operation. {}", exc.toString());
                return prepareExceptionMessageResponse(Status.INTERNAL_ERROR, exc, params.containsKey("trace"));
            } catch (Exception exc) {
                _logger.warn("Unexpected exception during REST operation.", exc);
                return prepareExceptionMessageResponse(Status.INTERNAL_ERROR, exc, params.containsKey("trace"));
            }
        } else {
            // Handle potential "pysp" server pages
            if (params.containsKey("_edit")) {
                // Force to editor
                return super.serve("/editor.htm", root, method, params, request);
            } else if (params.containsKey("_source")) {
                // Serve the raw text
                File target = resolveFile(uri, root);
                if (target == null)
                    return new Response(Status.NOT_FOUND, "text/plain", "Not found - " + uri);
                else
                    return new Response(Status.OK, "text/plain; charset=utf-8", Stream.tryReadFully(target));
            } else if (params.containsKey("_write")) {
                // Overwrite the file with the posted data
                File target = resolveFile(uri, root);
                if (target == null)
                    return new Response(Status.NOT_FOUND, "text/plain", "Not found - " + uri);

                if (request.raw == null || request.raw.length == 0)
                    return new Response(Status.FORBIDDEN, "text/plain", "No POST data provided.");

                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(target);
                    fos.write(request.raw);
                    return new Response(Status.OK, "text/plain", request.raw.length + " bytes written.");
                } catch (Exception exc) {
                    return new Response(Status.INTERNAL_ERROR, "text/plain", "Problem writing file.");
                } finally {
                    Stream.safeClose(fos);
                }
            }

            // If restTarget is a PyNode, try to interpret the file as a ".pysp"
            if (restTarget instanceof PyNode) {
                PyNode pyNode = (PyNode) restTarget;
                // Try exact .pysp
                if (uri.endsWith(".pysp")) {
                    Response r = handlePySp(pyNode, uri, root, method, params, request);
                    if (r != null && !Status.NOT_FOUND.equals(r.getStatus()))
                        return r;
                } else {
                    // Attempt with .pysp appended
                    Response r = handlePySp(pyNode, uri + ".pysp", root, method, params, request);
                    if (r != null && !Status.NOT_FOUND.equals(r.getStatus()))
                        return r;
                }
            }

            // If not PyNode or not a pysp, serve statically
            return super.serve(uri, root, method, params, request);
        }
    }

    private Response prepareExceptionMessageResponse(IStatus httpCode, Throwable exc, boolean includeStackTrace) {
        ExceptionMessage message = new ExceptionMessage();
        Throwable current = exc;
        ExceptionMessage currentMsg = message;
        while (current != null) {
            currentMsg.error = current.getClass().getSimpleName();
            currentMsg.message = current.getMessage();
            if (currentMsg.message == null || currentMsg.message.isEmpty())
                currentMsg.message = current.toString();

            if (includeStackTrace) {
                currentMsg.stackTrace = captureStackTrace(current);
                // Only capture once
                includeStackTrace = false;
            }
            if (current.getCause() == null) break;
            current = current.getCause();
            currentMsg.cause = new ExceptionMessage();
            currentMsg = currentMsg.cause;
        }
        Response resp = new Response(httpCode, "application/json; charset=utf-8", Serialisation.serialise(message));
        resp.addHeader("Access-Control-Allow-Origin", "*");
        return resp;
    }

    private Response prepareNotFoundResponse(String path, String type) {
        ExceptionMessage errorResponse = new ExceptionMessage();
        errorResponse.error = "NotFound";
        errorResponse.message = type + " '" + path + "' was not found.";
        errorResponse.code = "404";
        return new Response(Status.NOT_FOUND, "application/json; charset=utf-8",
                Serialisation.serialise(errorResponse));
    }

    /**
     * Simple exception info for JSON serialization.
     */
    public class ExceptionMessage {
        @Value(name = "code")
        public String code;
        @Value(name = "error")
        public String error;
        @Value(name = "message")
        public String message;
        @Value(name = "cause")
        public ExceptionMessage cause;
        @Value(name = "stackTrace")
        public String stackTrace;
    }

    private static String captureStackTrace(Throwable exc) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exc.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * For PySP pages, we assemble them into a string of Python code, then eval it with
     * GraalVM. We'll store the output in a ServerPageResponse object and serve that.
     */
    private Response handlePySp(final PyNode node, String uri, File root, String method,
                                Properties params, final Request request) {

        // Attempt to serve the file (which should physically exist)
        Response baseResponse = super.serve(uri, root, method, params, request, true);
        if (!Status.OK.equals(baseResponse.getStatus()))
            return baseResponse;

        // Prepare a response collector
        final ServerPageResponse response = new ServerPageResponse();
        response.status = "200 OK";
        response.mimeType = "text/html";

        try {
            String template = Stream.readFully(new InputStreamReader(baseResponse.getData(), UTF8Charset.instance()));

            // Build the Python script
            final StringBuilder scriptBuilder = new StringBuilder();
            final Throwable[] exceptionHolder = new Throwable[1];

            ServerSideFilter filter = new ServerSideFilter(template) {
                char lastBlock = ' ';

                @Override
                public void resolveExpression(String expr) throws Throwable {
                    if (lastBlock == 'e' || lastBlock == 'p')
                        scriptBuilder.append("; \n");
                    scriptBuilder.append("resp.print(").append(expr).append(")");
                    lastBlock = 'e';
                }

                @Override
                public void evaluateBlock(String block) throws Throwable {
                    scriptBuilder.append(block);
                    lastBlock = 'b';
                }

                @Override
                public void passThrough(String data) throws Throwable {
                    if (lastBlock == 'e' || lastBlock == 'p')
                        scriptBuilder.append("; \n");
                    scriptBuilder.append("resp.print('").append(data).append("')");
                    lastBlock = 'p';
                }

                @Override
                public void comment(String comment) throws Throwable {
                    // do nothing
                }

                @Override
                public void resolveEscapedExpression(String expr) throws Throwable {
                    if (lastBlock == 'e' || lastBlock == 'p')
                        scriptBuilder.append("; \n");
                    scriptBuilder.append("resp.escape(").append(expr).append(")");
                    lastBlock = 'e';
                }

                @Override
                public void handleError(Throwable th) {
                    exceptionHolder[0] = th;
                }
            };
            filter.process();

            if (exceptionHolder[0] != null) {
                node.injectError("Ignoring PySp parse error",
                        new RuntimeException(exceptionHolder[0]));
            }

            String finalScript = scriptBuilder.toString();
            if (params.containsKey("_compiled")) {
                return new Response(Status.OK, "text/plain; charset=utf-8", finalScript);
            }

            // Now evaluate the script inside the node's Python context
            // Synchronized to prevent concurrency collisions on the same Context
            synchronized (node) {
                try {
                    // Insert the per-request variables
                    node.getPythonContext().getBindings("python").putMember("req", request);
                    node.getPythonContext().getBindings("python").putMember("resp", response);

                    // Evaluate the assembled script
                    node.getPythonContext().eval("python", finalScript);
                } finally {
                    // Remove them to avoid leaking data between requests
                    node.getPythonContext().getBindings("python").removeMember("req");
                    node.getPythonContext().getBindings("python").removeMember("resp");
                }
            }

            // inside handlePySp when there's a parse error:
            if (exceptionHolder[0] != null) {
                node.injectError("Ignoring PySp parse error",
                        new RuntimeException(exceptionHolder[0]));
            }

            // inside handlePySp when injecting request, response:
            synchronized (node) {
                node.getPythonContext().getBindings("python").putMember("req", request);
                node.getPythonContext().getBindings("python").putMember("resp", response);
                node.getPythonContext().eval("python", finalScript);
                node.getPythonContext().getBindings("python").removeMember("req");
                node.getPythonContext().getBindings("python").removeMember("resp");
            }

            // Prepare final response
            Response nanoResponse = new Response(Status.OK, response.mimeType, response.getData());
            nanoResponse.setHeaders(response.headers);
            return nanoResponse;

        } catch (PolyglotException pgex) {
            _logger.warn("Graal Python exception during PySP filter (URI:{}). {}", uri, pgex.toString());
            return prepareExceptionMessageResponse(Status.INTERNAL_ERROR, pgex, params.contains("trace"));
        } catch (Exception exc) {
            _logger.warn("Unexpected exception during PySP filter (URI:{}).", uri, exc);
            return prepareExceptionMessageResponse(Status.INTERNAL_ERROR, exc, params.contains("trace"));
        }
    }

    /**
     * A simpler utility class to hold the response of a "server-page."
     */
    public static class ServerPageResponse {
        public String status;
        public String mimeType;
        public Properties headers = new Properties();
        private StringBuilder _sb = new StringBuilder();

        public void addHeader(String name, String value) {
            headers.put(name, value);
        }

        public void print(Object value) {
            _sb.append(value);
        }

        public void println() {
            _sb.append(System.lineSeparator());
        }

        public void println(Object value) {
            _sb.append(value).append(System.lineSeparator());
        }

        public void escape(Object value) {
            String escaped = (value != null) ? XML.escape(value.toString()) : "";
            _sb.append(escaped);
        }

        public String getData() {
            return _sb.toString();
        }
    }

    /**
     * Called when the set of local IP addresses changes. Adjusts Nodel HTTP addresses, logs.
     */
    private void handleTopologyChange(List<InetAddress> appeared, List<InetAddress> disappeared) {
        InetAddress[] addresses = TopologyWatcher.shared().getInterfaces();
        String[] httpAddresses = new String[addresses.length];
        String[] httpNodeAddresses = new String[addresses.length];

        for (int a = 0; a < addresses.length; a++) {
            httpAddresses[a] = String.format("http://%s:%s%s",
                    addresses[a].getHostAddress(),
                    Nodel.getHTTPPort(),
                    Nodel.getHTTPSuffix());
            httpNodeAddresses[a] = String.format("http://%s:%s",
                    addresses[a].getHostAddress(),
                    Nodel.getHTTPPort());
        }
        Nodel.updateHTTPAddresses(httpAddresses, httpNodeAddresses);

        for (InetAddress newly : appeared) {
            System.out.println("    (web interface available at http://"
                    + newly.getHostAddress() + ":"
                    + Nodel.getHTTPPort() + ")\n");
        }

        for (InetAddress gone : disappeared) {
            System.out.println("    (" + gone.getHostAddress() + " interface disappeared)");
        }
    }

    public String getUserAgent() {
        return _userAgent;
    }

}
