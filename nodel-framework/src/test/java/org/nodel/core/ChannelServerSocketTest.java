package org.nodel.core;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChannelServerSocketTest {

    @Test
    public void localInterfaceOnlyServerBindsToLoopback() throws Exception {
        ChannelServerSocket server = new ChannelServerSocket(0, true);
        CountDownLatch started = new CountDownLatch(1);
        server.setStartedHandler(port -> started.countDown());

        try {
            server.start();

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(server.getBoundAddress().isLoopbackAddress());
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void immediateShutdownClosesTheListener() {
        for (int attempt = 0; attempt < 25; attempt++) {
            ChannelServerSocket server = new ChannelServerSocket(0, true);

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                server.start();
                server.shutdown();
            });
            assertTrue(server.isShutdown());
        }
    }
}
