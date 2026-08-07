package org.nodel.discovery;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TopologyWatcherTest {

    @Test
    public void localInterfaceOnlyRemovesExternalInterfaces() throws Exception {
        Set<InetAddress> active = new HashSet<>();
        active.add(InetAddress.getByName("192.0.2.1"));

        TopologyWatcher.applyInterfaceScope(active, true);

        assertEquals(Set.of(TopologyWatcher.IPv4Loopback), active);
    }

    @Test
    public void automaticScopeRetainsExternalInterfaces() throws Exception {
        InetAddress external = InetAddress.getByName("192.0.2.1");
        Set<InetAddress> active = new HashSet<>();
        active.add(external);

        TopologyWatcher.applyInterfaceScope(active, false);

        assertEquals(Set.of(external), active);
    }
}
