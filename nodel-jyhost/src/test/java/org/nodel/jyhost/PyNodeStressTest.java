package org.nodel.jyhost;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.nodel.SimpleName;

/**
 * Stress tests for PyNode to validate thread safety under heavy load.
 * These tests subject the PyNode to extreme concurrency conditions to ensure
 * the removal of the _busy lock doesn't introduce race conditions or other
 * thread safety issues.
 */
@DisplayName("PyNode Stress Tests")
@Tag("stress")
public class PyNodeStressTest {
    
    // High concurrency test parameters
    private static final int HIGH_THREAD_COUNT = 50;
    private static final int OPERATIONS_PER_THREAD = 10;
    private static final int NODE_COUNT = 5;
    
    private Path tempDirectory;
    private List<PyNode> testNodes;
    
    // Shared NodelHost instance for all tests
    private static NodelHost sharedHost;
    private static Path sharedTempDirectory;
    
    @BeforeAll
    public static void setUpAll() throws Exception {
        // Create a shared temporary directory for all tests
        sharedTempDirectory = Files.createTempDirectory("nodel-shared-stress-test-");
        
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
            // just reuse the existing NodelHost instance from GraalVMContextTest
            try {
                Class<?> graalTestClass = Class.forName("org.nodel.jyhost.GraalVMContextTest");
                java.lang.reflect.Field hostField = graalTestClass.getDeclaredField("sharedHost");
                hostField.setAccessible(true);
                sharedHost = (NodelHost) hostField.get(null);
                
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
        // Create a temporary directory for nodes
        tempDirectory = Files.createTempDirectory("nodel-stress-test-");
        
        // Create multiple test nodes
        testNodes = new ArrayList<>();
        for (int i = 0; i < NODE_COUNT; i++) {
            String nodeName = "testNode" + i;
            Path nodeDir = tempDirectory.resolve(nodeName);
            Files.createDirectories(nodeDir);
            
            PyNode node = createTestNode(sharedHost, nodeName, nodeDir.toFile());
            node.init();
            testNodes.add(node);
        }
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        // Clean up node resources
        for (PyNode node : testNodes) {
            node.close();
        }
        testNodes.clear();
        
        // Delete the temporary directory
        deleteDirectory(tempDirectory.toFile());
    }
    
    /**
     * Create a test PyNode instance with basic setup
     */
    private PyNode createTestNode(NodelHost host, String name, File rootDirectory) throws IOException {
        SimpleName nodeName = new SimpleName(name);
        return new PyNode(host, nodeName, rootDirectory);
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
    @DisplayName("Node handles high concurrency load without errors")
    public void testHighConcurrencyLoad() throws Exception {
        PyNode targetNode = testNodes.get(0); // Use first node for high concurrency test
        
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(HIGH_THREAD_COUNT);
        final AtomicInteger successCount = new AtomicInteger(0);
        final List<Exception> exceptions = new ArrayList<>();
        
        // Create a large thread pool
        ExecutorService executor = Executors.newFixedThreadPool(HIGH_THREAD_COUNT);
        
        // Submit many concurrent tasks
        for (int i = 0; i < HIGH_THREAD_COUNT; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    // Wait for the starting signal
                    startLatch.await();
                    
                    // Each thread performs multiple operations
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        // Choose operation type based on thread number and iteration
                        int opType = (threadNum + j) % 3;
                        
                        switch (opType) {
                            case 0: // Eval operation
                                String expr = String.format("'Stress Test: Thread %d, Op %d'", threadNum, j);
                                Object result = targetNode.eval(expr, "StressTest");
                                assertNotNull(result, "Evaluation result should not be null");
                                break;
                                
                            case 1: // Exec operation
                                String code = String.format(
                                    "for i in range(10): pass # Thread %d, Op %d", threadNum, j);
                                targetNode.exec(code, "StressTest");
                                break;
                                
                            case 2: // Define and call a function
                                String funcName = String.format("stress_func_%d_%d", threadNum, j);
                                String funcDef = String.format(
                                    "def %s(): return 'Stress function called by Thread %d'",
                                    funcName, threadNum);
                                targetNode.exec(funcDef, "StressTest");
                                
                                // Call the function we just defined
                                String funcCall = funcName + "()";
                                Object funcResult = targetNode.eval(funcCall, "StressTest");
                                assertNotNull(funcResult, "Function call result should not be null");
                                break;
                        }
                        
                        // Increment success counter
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    synchronized(exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all operations to complete (with generous timeout)
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        
        // Shutdown the executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify results
        assertTrue(completed, "All operations should complete within the timeout");
        if (!exceptions.isEmpty()) {
            System.err.println("First exception: " + exceptions.get(0));
            exceptions.get(0).printStackTrace();
        }
        assertTrue(exceptions.isEmpty(), "No exceptions should occur");
        assertEquals(HIGH_THREAD_COUNT * OPERATIONS_PER_THREAD, successCount.get(), 
            "All operations should succeed");
    }
    
    @Test
    @DisplayName("Multiple nodes can operate concurrently without interference")
    public void testMultipleNodesOperation() throws Exception {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(NODE_COUNT * 10); // 10 threads per node
        final AtomicInteger successCount = new AtomicInteger(0);
        final List<Exception> exceptions = new ArrayList<>();
        
        // Create a thread pool
        ExecutorService executor = Executors.newFixedThreadPool(NODE_COUNT * 10);
        
        // For each node, create multiple threads
        for (int nodeIndex = 0; nodeIndex < NODE_COUNT; nodeIndex++) {
            final PyNode node = testNodes.get(nodeIndex);
            
            // Create 10 threads per node
            for (int threadIndex = 0; threadIndex < 10; threadIndex++) {
                final int nodeIdx = nodeIndex;
                final int threadIdx = threadIndex;
                
                executor.submit(() -> {
                    try {
                        // Wait for all threads to be ready
                        startLatch.await();
                        
                        // Each thread performs 5 operations
                        for (int opIdx = 0; opIdx < 5; opIdx++) {
                            // Create unique identifiers for this operation
                            String nodeId = "Node" + nodeIdx;
                            String threadId = "Thread" + threadIdx;
                            String opId = "Op" + opIdx;
                            
                            // Define variables specific to this node/thread/operation
                            String variableName = nodeId + "_" + threadId + "_" + opId;
                            String code = String.format(
                                "%s = 'Value from %s, %s, %s'", 
                                variableName, nodeId, threadId, opId);
                            
                            // Execute the operation
                            node.exec(code, "MultiNodeTest");
                            
                            // Verify the operation succeeded by retrieving the variable
                            Object result = node.eval(variableName, "MultiNodeTest");
                            assertNotNull(result);
                            assertTrue(result.toString().contains(nodeId));
                            assertTrue(result.toString().contains(threadId));
                            
                            // Increment success counter
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        synchronized(exceptions) {
                            exceptions.add(e);
                        }
                    } finally {
                        endLatch.countDown();
                    }
                });
            }
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all operations to complete
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        
        // Shutdown the executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify results
        assertTrue(completed, "All operations should complete within the timeout");
        if (!exceptions.isEmpty()) {
            System.err.println("First exception: " + exceptions.get(0));
            exceptions.get(0).printStackTrace();
        }
        assertTrue(exceptions.isEmpty(), "No exceptions should occur");
        assertEquals(NODE_COUNT * 10 * 5, successCount.get(), 
            "All operations should succeed");
    }
    
    @Test
    @DisplayName("Mixed workload with long and short operations")
    public void testMixedWorkload() throws Exception {
        PyNode targetNode = testNodes.get(0);
        
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(30); // 30 threads
        final AtomicInteger successCount = new AtomicInteger(0);
        final List<Exception> exceptions = new ArrayList<>();
        
        // Create a thread pool
        ExecutorService executor = Executors.newFixedThreadPool(30);
        
        // Submit a mix of tasks
        for (int i = 0; i < 30; i++) {
            final int threadNum = i;
            
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Determine operation type based on thread number
                    if (threadNum % 3 == 0) {
                        // Long-running operation
                        String code = String.format(
                            "import time; time.sleep(0.5); 'Long task completed by Thread %d'", 
                            threadNum);
                        Object result = targetNode.eval(code, "MixedWorkload");
                        assertNotNull(result);
                    } 
                    else if (threadNum % 3 == 1) {
                        // Quick operations
                        for (int j = 0; j < 10; j++) {
                            String expr = String.format("'Quick op %d from Thread %d'", j, threadNum);
                            Object result = targetNode.eval(expr, "MixedWorkload");
                            assertNotNull(result);
                        }
                    }
                    else {
                        // Mixed eval and exec
                        for (int j = 0; j < 5; j++) {
                            String varName = String.format("t%d_var%d", threadNum, j);
                            targetNode.exec(varName + " = " + j * threadNum, "MixedWorkload");
                            Object result = targetNode.eval(varName, "MixedWorkload");
                            assertNotNull(result);
                            assertEquals(j * threadNum, ((Number)result).intValue());
                        }
                    }
                    
                    // Increment success counter
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized(exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all operations to complete
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        
        // Shutdown the executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify results
        assertTrue(completed, "All operations should complete within the timeout");
        if (!exceptions.isEmpty()) {
            System.err.println("First exception: " + exceptions.get(0));
            exceptions.get(0).printStackTrace();
        }
        assertTrue(exceptions.isEmpty(), "No exceptions should occur");
        assertEquals(30, successCount.get(), "All threads should succeed");
    }
}
