package org.nodel.toolkit;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nodel.SimpleName;
import org.nodel.host.BaseDynamicNode;
import org.nodel.threading.CallbackQueue;
import org.nodel.threading.ThreadPool;
import org.nodel.threading.Timers;

class ManagedProcessTest {

    @TempDir
    Path tempDirectory;

    @Test
    void stopWaitsForAnInFlightLaunchAndClosesItBeforeReturning() throws Exception {
        CountDownLatch launchEntered = new CountDownLatch(1);
        CountDownLatch allowLaunch = new CountDownLatch(1);
        CountDownLatch stopCalled = new CountDownLatch(1);
        CountDownLatch callbackQueueEntered = new CountDownLatch(1);
        CountDownLatch allowCallbacks = new CountDownLatch(1);
        CountDownLatch stoppedCallback = new CountDownLatch(1);
        AtomicReference<Process> launchedProcess = new AtomicReference<>();
        AtomicBoolean startedCallback = new AtomicBoolean();

        BaseDynamicNode node = mock(BaseDynamicNode.class);
        when(node.getName()).thenReturn(new SimpleName("ManagedProcessStopRace"));
        when(node.getRoot()).thenReturn(tempDirectory.toFile());

        String jshell = Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("windows")
                ? "jshell.exe"
                : "jshell").toString();
        ThreadPool threadPool = new ThreadPool("_managedProcessStopRace", 1, 250);
        CallbackQueue callbackQueue = new CallbackQueue();
        ManagedProcess process = new ManagedProcess(
            node,
            List.of(jshell),
            () -> {},
            exception -> {},
            callbackQueue,
            threadPool,
            new Timers("_managedProcessStopRace")) {

            @Override
            Process startProcess(ProcessBuilder processBuilder) throws IOException {
                launchEntered.countDown();
                try {
                    if (!allowLaunch.await(10, TimeUnit.SECONDS))
                        throw new IOException("Timed out waiting to release the test launch");
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to launch", exc);
                }
                Process child = super.startProcess(processBuilder);
                launchedProcess.set(child);
                return child;
            }
        };
        process.setStartedHandler(() -> startedCallback.set(true));
        process.setStoppedHandler(exitCode -> stoppedCallback.countDown());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> stopFuture = null;
        Future<?> queueBlocker = executor.submit(() -> callbackQueue.handle(() -> {
            callbackQueueEntered.countDown();
            try {
                allowCallbacks.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
            }
        }, exception -> {}));
        try {
            assertTrue(callbackQueueEntered.await(1, TimeUnit.SECONDS));
            process.init();
            assertTrue(launchEntered.await(8, TimeUnit.SECONDS));

            stopFuture = executor.submit(() -> {
                stopCalled.countDown();
                process.stop();
            });
            assertTrue(stopCalled.await(1, TimeUnit.SECONDS));
            Future<?> pendingStop = stopFuture;
            assertThrows(TimeoutException.class,
                () -> pendingStop.get(200, TimeUnit.MILLISECONDS));

            allowLaunch.countDown();
            stopFuture.get(5, TimeUnit.SECONDS);

            Process child = launchedProcess.get();
            assertNotNull(child);
            child.waitFor(5, TimeUnit.SECONDS);
            assertFalse(child.isAlive());

            allowCallbacks.countDown();
            queueBlocker.get(2, TimeUnit.SECONDS);
            assertTrue(stoppedCallback.await(2, TimeUnit.SECONDS));
            assertFalse(startedCallback.get());
        } finally {
            allowLaunch.countDown();
            allowCallbacks.countDown();
            process.close();
            if (stopFuture != null)
                stopFuture.cancel(true);
            queueBlocker.cancel(true);
            executor.shutdownNow();
        }
    }
}
