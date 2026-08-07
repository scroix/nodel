package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NodelHostHTTPDTest {

    @Test
    public void localInterfaceOnlyServerBindsToLoopback(@TempDir Path root) throws Exception {
        NodelHostHTTPD server = new NodelHostHTTPD(0, root.toFile(), true);

        try {
            server.start();

            assertTrue(server.getLocalHostOnly());
            assertTrue(server.getMyServerSocket().getInetAddress().isLoopbackAddress());
            assertEquals("127.0.0.1", server.getMyServerSocket().getInetAddress().getHostAddress());
        } finally {
            server.stop();
        }
    }
}
