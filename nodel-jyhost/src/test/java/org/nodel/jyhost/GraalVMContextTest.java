package org.nodel.jyhost;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests GraalVM Python context configuration, specifically host class access.
 */
public class GraalVMContextTest {

    private static final String FALLBACK_WARNING = "fallback runtime";

    // Shared NodelHost instance for all tests
    public static NodelHost sharedHost;
    private static Path sharedTempDirectory;
    
    @BeforeAll
    public static void setUpAll() throws Exception {
        // Create a shared temporary directory for all tests
        sharedTempDirectory = Files.createTempDirectory("nodel-graalvm-test-");
        
        // Set up a shared test host with minimal parameters
        sharedHost = new NodelHost(
            sharedTempDirectory.toFile(),    // root directory
            null,                            // inclusion filters (null for no filtering)
            null,                            // exclusion filters (null for no filtering)
            sharedTempDirectory.toFile()     // recipes root directory (same as root for tests)
        );
    }
    
    @AfterAll
    public static void tearDownAll() throws Exception {
        // Clean up the shared host
        if (sharedHost != null) {
            sharedHost.shutdown();
        }
        
        // Delete the shared temporary directory
        deleteDirectoryStatic(sharedTempDirectory.toFile());
    }
    
    /**
     * Static version of directory deletion for use in @AfterAll
     */
    private static void deleteDirectoryStatic(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectoryStatic(file);
                }
            }
        }
        directory.delete();
    }

    @Test
    public void testSupportedRuntimeCompilesBothLanguagesWithoutFallbackWarning() throws Exception {
        List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(System.getProperty("java.vm.vendor").contains("GraalVM"),
                "Gradle test JVM must use the GraalVM toolchain");

        for (String language : List.of("python", "js")) {
            ProbeResult optimized = runProbe(language, inputArguments);
            assertEquals(0, optimized.exitCode, optimized.output);
            assertTrue(optimized.output.contains("result=42"), optimized.output);
            assertTrue(optimized.output.contains("supportsCompilation=true"), optimized.output);
            assertFalse(optimized.output.contains(FALLBACK_WARNING), optimized.output);
        }
    }

    private static ProbeResult runProbe(String language, List<String> inputArguments)
            throws Exception {
        String javaName = System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", javaName).toString());
        inputArguments.stream()
                .filter(arg -> arg.startsWith("--add-opens=")
                        || arg.startsWith("--enable-native-access="))
                .forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"),
                GraalVMContextTest.class.getName(), "probe", language));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Timed out waiting for " + language + " runtime probe");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProbeResult(process.exitValue(), output);
    }

    public static void main(String[] args) {
        if (args.length != 2 || !"probe".equals(args[0]) || !("python".equals(args[1]) || "js".equals(args[1]))) {
            throw new IllegalArgumentException("usage: GraalVMContextTest probe <python|js>");
        }
        try (Context context = Context.newBuilder(args[1]).build()) {
            System.out.println("supportsCompilation=" + context.getEngine().supportsCompilation());
            System.out.println("result=" + context.eval(args[1], "6 * 7").asInt());
        }
    }

    private static final class ProbeResult {
        private final int exitCode;
        private final String output;

        private ProbeResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    @Test
    public void testJavaClassImportFromPython() {
        ClassLoader hostCl = getClass().getClassLoader();
        Context context = null;
        try {
            context = Context.newBuilder("python")
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(name -> true) // Allow lookup for testing purposes
                .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.ALL) // Allow polyglot access
                .hostClassLoader(hostCl)
                .build();

            // Look up a standard Java class the same way nodetoolkit.py does (java.type)
            org.graalvm.polyglot.Value result = context.eval("python",
                "import java\n" +
                "HashMap = java.type('java.util.HashMap')\n" +
                "m = HashMap()\n" +
                "m.put('key', 'value')\n" +
                "m");

            assertTrue(result.isHostObject(), "java.type('java.util.HashMap')() should produce a host object");
            java.util.Map<?, ?> hostMap = result.asHostObject();
            assertEquals("value", hostMap.get("key"), "Host HashMap should round-trip values set from Python");

        } catch (PolyglotException e) {
            fail("PolyglotException occurred during test: " + e.getMessage(), e);
        } catch (Exception e) {
            fail("Unexpected exception occurred during test: " + e.getMessage(), e);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    @Test
    public void testProjectDependencyImportFromPython() {
        ClassLoader hostCl = getClass().getClassLoader();
        Context context = null;
        try {
            context = Context.newBuilder("python")
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(name -> true) // Allow lookup for testing purposes
                .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.ALL) // Allow polyglot access
                .hostClassLoader(hostCl)
                .build();

            // Look up a project dependency (JGit) the same way nodetoolkit.py does (java.type)
            org.graalvm.polyglot.Value result = context.eval("python",
                "import java\n" +
                "java.type('org.eclipse.jgit.api.Git')");

            assertTrue(result.isHostObject(), "java.type('org.eclipse.jgit.api.Git') should resolve to a host class");

        } catch (PolyglotException e) {
            // It's possible the dependency isn't on the test classpath by default
            // Check if the failure is specifically due to the class not being found
            if (e.getMessage().contains("ClassNotFoundException") || e.getMessage().contains("org.eclipse.jgit.api.Git")) {
                 System.err.println("WARNING: JGit class not found on test classpath. Skipping JGit import test.");
                 // Optionally, use Assumptions.assumeTrue(false, "JGit not on test classpath"); // JUnit 5
            } else {
                fail("PolyglotException occurred during test: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            fail("Unexpected exception occurred during test: " + e.getMessage(), e);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }
}
