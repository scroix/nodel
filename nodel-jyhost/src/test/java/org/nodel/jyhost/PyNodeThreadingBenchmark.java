package org.nodel.jyhost;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.nodel.SimpleName;

/**
 * Benchmarks for PyNode thread performance after removing the _busy lock.
 * This class measures the performance improvements from the new threading model.
 */
@DisplayName("PyNode Threading Benchmarks")
@Tag("benchmark")
public class PyNodeThreadingBenchmark {
    
    // Test parameters
    private static final int THREAD_COUNT = 20;
    private static final int OPERATIONS_PER_THREAD = 50;
    private static final int WARMUP_COUNT = 5;
    
    private Path tempDirectory;
    private PyNode testNode;
    
    // Shared NodelHost instance for all tests
    private static NodelHost sharedHost;
    private static Path sharedTempDirectory;
    
    @BeforeAll
    public static void setUpAll() throws Exception {
        // Create a shared temporary directory for all tests
        sharedTempDirectory = Files.createTempDirectory("nodel-shared-benchmark-test-");
        
        try {
            // Set up a shared test host with minimal parameters
            sharedHost = new NodelHost(
                sharedTempDirectory.toFile(),    // root directory
                null,                           // inclusion filters (null for no filtering)
                null,                           // exclusion filters (null for no filtering)
                sharedTempDirectory.toFile()    // recipes root directory (same as root for tests)
            );
        } catch (IllegalStateException e) {
            // If diagnostics have already been registered (by another test class), 
            // just reuse the existing NodelHost instance from one of the other test classes
            try {
                // First try PyNodeThreadingTest
                Class<?> threadingTestClass = Class.forName("org.nodel.jyhost.PyNodeThreadingTest");
                java.lang.reflect.Field hostField = threadingTestClass.getDeclaredField("sharedHost");
                hostField.setAccessible(true);
                sharedHost = (NodelHost) hostField.get(null);
                
                if (sharedHost == null) {
                    // Fall back to PyNodeStressTest
                    Class<?> stressTestClass = Class.forName("org.nodel.jyhost.PyNodeStressTest");
                    hostField = stressTestClass.getDeclaredField("sharedHost");
                    hostField.setAccessible(true);
                    sharedHost = (NodelHost) hostField.get(null);
                }
                
                if (sharedHost == null) {
                    throw new RuntimeException("Could not find shared NodelHost instance");
                }
            } catch (Exception reflectionEx) {
                throw new RuntimeException("Error accessing shared host", reflectionEx);
            }
        }
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
    
    @BeforeEach
    public void setUp() throws Exception {
        // Create a temporary directory for the test node
        tempDirectory = Files.createTempDirectory("nodel-benchmark-test-");
        
        // Create a test node
        Path nodeDir = tempDirectory.resolve("benchmarkNode");
        Files.createDirectories(nodeDir);
        testNode = new PyNode(sharedHost, new SimpleName("benchmarkNode"), nodeDir.toFile());
        testNode.init();
        
        // Prepare some test functions for benchmarking
        testNode.exec("def empty_function(): pass", "Benchmark");
        testNode.exec("def light_function(n=10): return sum(range(n))", "Benchmark");
        testNode.exec("def compute_function(n=100): return [i*i for i in range(n)]", "Benchmark");
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        if (testNode != null) {
            testNode.close();
        }
        
        // Delete the temporary directory
        deleteDirectory(tempDirectory.toFile());
    }
    
    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
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
    @DisplayName("Benchmark concurrent Python function calls")
    public void benchmarkConcurrentFunctionCalls() throws Exception {
        // Warm up to stabilize JIT compilation
        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_COUNT; i++) {
            runConcurrentOperations("empty_function()", 5, 10);
        }
        
        // Run the actual benchmarks
        System.out.println("\nRunning benchmarks:");
        
        // Benchmark empty function
        long emptyFunctionTime = runConcurrentOperations(
            "empty_function()", THREAD_COUNT, OPERATIONS_PER_THREAD);
        
        // Benchmark light computation function
        long lightFunctionTime = runConcurrentOperations(
            "light_function(50)", THREAD_COUNT, OPERATIONS_PER_THREAD);
        
        // Benchmark heavier computation function
        long computeFunctionTime = runConcurrentOperations(
            "compute_function(200)", THREAD_COUNT, OPERATIONS_PER_THREAD);
        
        // Calculate operations per second
        int totalOperations = THREAD_COUNT * OPERATIONS_PER_THREAD;
        double emptyOps = calculateOpsPerSecond(totalOperations, emptyFunctionTime);
        double lightOps = calculateOpsPerSecond(totalOperations, lightFunctionTime);
        double computeOps = calculateOpsPerSecond(totalOperations, computeFunctionTime);
        
        // Format results
        DecimalFormat fmt = new DecimalFormat("#,###.##");
        System.out.println("\nBenchmark Results:");
        System.out.println("------------------");
        System.out.println("Empty function: " + fmt.format(emptyOps) + " ops/sec");
        System.out.println("Light function: " + fmt.format(lightOps) + " ops/sec");
        System.out.println("Compute function: " + fmt.format(computeOps) + " ops/sec");
        
        // The test should pass as long as we can complete the benchmark
        assertTrue(emptyOps > 0, "Should be able to execute empty functions concurrently");
        assertTrue(lightOps > 0, "Should be able to execute light functions concurrently");
        assertTrue(computeOps > 0, "Should be able to execute compute functions concurrently");
    }
    
    /**
     * Run concurrent operations against the test node and measure performance
     */
    private long runConcurrentOperations(String expression, int threadCount, int opsPerThread) throws Exception {
        System.out.println("Benchmarking: " + expression);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final AtomicInteger completedOps = new AtomicInteger(0);
        final List<Exception> exceptions = new ArrayList<>();
        
        // Record start time
        long startTime = System.currentTimeMillis();
        
        // Submit tasks
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        testNode.eval(expression, "Benchmark");
                        completedOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    synchronized(exceptions) {
                        exceptions.add(e);
                    }
                }
            });
        }
        
        // Shutdown the executor and wait for completion
        executor.shutdown();
        boolean completed = executor.awaitTermination(60, TimeUnit.SECONDS);
        
        // Record end time
        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;
        
        // Verify no exceptions occurred
        if (!exceptions.isEmpty()) {
            System.err.println("Exception during benchmark: " + exceptions.get(0));
            exceptions.get(0).printStackTrace();
            fail("Exceptions occurred during benchmark");
        }
        
        // Verify all operations completed
        assertTrue(completed, "All operations should complete within timeout");
        assertEquals(threadCount * opsPerThread, completedOps.get(), 
            "All operations should complete successfully");
        
        System.out.println("  " + threadCount + " threads x " + opsPerThread + " ops = " 
            + (threadCount * opsPerThread) + " total ops");
        System.out.println("  Time: " + elapsedTime + " ms");
        
        return elapsedTime;
    }
    
    /**
     * Calculate operations per second
     */
    private double calculateOpsPerSecond(int operations, long milliseconds) {
        return (operations / (milliseconds / 1000.0));
    }
}
