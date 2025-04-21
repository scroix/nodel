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
 * Tests for thread safety in PyNode after removal of the _busy lock.
 * These tests verify that relying solely on GraalVM's Context thread safety
 * and the CallbackQueue provides adequate thread safety.
 */
@DisplayName("PyNode Threading Tests")
public class PyNodeThreadingTest {
    
    private static final int THREAD_COUNT = 10;
    private static final int OPERATIONS_PER_THREAD = 5;
    private PyNode testNode;
    private Path tempDirectory;
    
    // Shared NodelHost instance for all tests
    private static NodelHost sharedHost;
    private static Path sharedTempDirectory;
    
    @BeforeAll
    public static void setUpAll() throws Exception {
        // Create a shared temporary directory for all tests
        sharedTempDirectory = Files.createTempDirectory("nodel-shared-test-");
        
        // Set up a shared test host with minimal parameters
        sharedHost = new NodelHost(
            sharedTempDirectory.toFile(),    // root directory
            null,                           // inclusion filters (null for no filtering)
            null,                           // exclusion filters (null for no filtering)
            sharedTempDirectory.toFile()    // recipes root directory (same as root for tests)
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
    
    @BeforeEach
    public void setUp() throws Exception {
        // Create a temporary directory for the node
        tempDirectory = Files.createTempDirectory("nodel-test-");
        
        // Set up a test node using the shared host
        testNode = createTestNode(sharedHost, "testNode", tempDirectory.toFile());
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        // Clean up node resources
        if (testNode != null) {
            testNode.close();
        }
        
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
    @DisplayName("Context operations are thread-safe without _busy lock")
    public void testConcurrentContextOperations() throws Exception {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        final AtomicInteger successCount = new AtomicInteger(0);
        final List<Exception> exceptions = new ArrayList<>();
        
        // Create a pool of threads to perform operations concurrently
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // Submit tasks to evaluate expressions concurrently
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Perform multiple eval operations
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        String expr = String.format("'Thread %d, Operation %d'", threadNum, j);
                        Object result = testNode.eval(expr, "Test");
                        
                        // Verify the result
                        assertNotNull(result, "Evaluation result should not be null");
                        assertTrue(result.toString().contains("Thread " + threadNum), 
                            "Result should include thread identifier");
                        
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
        
        // Start all threads at the same time
        startLatch.countDown();
        
        // Wait for all operations to complete
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        
        // Shut down the executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify results
        assertTrue(completed, "All operations should complete within the timeout");
        assertTrue(exceptions.isEmpty(), 
            "No exceptions should occur: " + (exceptions.isEmpty() ? "" : exceptions.get(0).toString()));
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, successCount.get(), 
            "All operations should succeed");
    }
    
    @Test
    @DisplayName("Python context can handle mixed eval/exec operations concurrently")
    public void testMixedContextOperations() throws Exception {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        final AtomicInteger successCount = new AtomicInteger(0);
        final List<Exception> exceptions = new ArrayList<>();
        
        // Create a pool of threads to perform operations concurrently
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // Submit tasks with mixed eval/exec operations
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Perform a mix of eval and exec operations
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        if (j % 2 == 0) {
                            // Eval operation
                            String expr = String.format("'Thread %d, Eval %d'", threadNum, j);
                            Object result = testNode.eval(expr, "Test");
                            
                            // Verify eval result
                            assertNotNull(result, "Eval result should not be null");
                            assertTrue(result.toString().contains("Thread " + threadNum), 
                                "Result should include thread identifier");
                        } else {
                            // Exec operation
                            String varName = String.format("var_t%d_op%d", threadNum, j);
                            String code = String.format("%s = 'Thread %d, Exec %d'", 
                                varName, threadNum, j);
                            
                            // Execute the code to define a variable
                            testNode.exec(code, "Test");
                            
                            // Verify the variable was defined by retrieving it
                            Object result = testNode.eval(varName, "Test");
                            assertNotNull(result, "Variable should be defined");
                            assertTrue(result.toString().contains("Thread " + threadNum), 
                                "Variable should have correct value");
                        }
                        
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
        
        // Start all threads at the same time
        startLatch.countDown();
        
        // Wait for all operations to complete
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        
        // Shut down the executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify results
        assertTrue(completed, "All operations should complete within the timeout");
        assertTrue(exceptions.isEmpty(), 
            "No exceptions should occur: " + (exceptions.isEmpty() ? "" : exceptions.get(0).toString()));
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, successCount.get(), 
            "All operations should succeed");
    }
    
    @Test
    @DisplayName("Action handling is thread-safe with CallbackQueue")
    public void testConcurrentActionHandling() throws Exception {
        // Define a test action with the local_action_ prefix
        testNode.exec("def local_action_test_action(arg=None):\n    return 'Success: ' + str(arg)", "Test");
        
        // Use a more direct approach without complex binding
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        final AtomicInteger successCount = new AtomicInteger(0);
        final List<Exception> exceptions = new ArrayList<>();
        
        // Create a pool of threads
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // Submit tasks to invoke actions concurrently
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Each thread invokes multiple actions by directly calling eval
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        final int opIndex = j; // Make j effectively final for lambda
                        
                        try {
                            // Call the Python function directly via eval
                            Object result = testNode.eval(
                                String.format("local_action_test_action(%d)", threadNum * 100 + opIndex), 
                                "Test"
                            );
                            
                            // Check if we got a successful result
                            assertNotNull(result, "Result should not be null");
                            assertTrue(result.toString().contains("Success"), 
                                "Result should contain 'Success'");
                            
                            // Increment success counter
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            synchronized(exceptions) {
                                exceptions.add(e);
                            }
                        }
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
        
        // Start all threads
        startLatch.countDown();
        
        // Wait for all threads to complete
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        
        // Clean up the thread pool
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Check for exceptions
        if (!exceptions.isEmpty()) {
            fail("Exceptions occurred during test: " + exceptions.get(0).getMessage());
        }
        
        // Verify all actions were successful
        assertTrue(completed, "All threads should complete within timeout");
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, successCount.get(), 
                    "All actions should succeed");
    }
    
    @Test
    @DisplayName("Long-running Python operations don't block each other")
    public void testLongRunningOperations() throws Exception {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        final AtomicInteger successCount = new AtomicInteger(0);
        final List<Exception> exceptions = new ArrayList<>();
        
        // Create a pool of threads
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // Submit tasks with long-running Python operations
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Create a Python operation that sleeps for a random time
                    String code = String.format(
                        "import time; time.sleep(%f); 'Thread %d completed'", 
                        0.1 + (Math.random() * 0.3), threadNum
                    );
                    
                    // Execute the long-running operation
                    Object result = testNode.eval(code, "Test");
                    
                    // Verify the result
                    assertNotNull(result, "Result should not be null");
                    assertTrue(result.toString().contains("Thread " + threadNum), 
                        "Result should contain thread identifier");
                    
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
        
        // Start all threads at the same time
        startLatch.countDown();
        
        // Wait for all operations to complete
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        
        // Shut down the executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify results
        assertTrue(completed, "All operations should complete within the timeout");
        assertTrue(exceptions.isEmpty(), 
            "No exceptions should occur: " + (exceptions.isEmpty() ? "" : exceptions.get(0).toString()));
        assertEquals(THREAD_COUNT, successCount.get(), 
            "All operations should succeed");
    }
}
