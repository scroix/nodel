package org.nodel.host;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BootstrapConfigTest {

    @Test
    public void localInterfaceOnlyDefaultsToFalse() {
        assertFalse(new BootstrapConfig().getLocalInterfaceOnly());
    }

    @Test
    public void localInterfaceOnlyCommandLineFlagEnablesLoopbackBinding() {
        BootstrapConfig config = new BootstrapConfig();

        config.overrideWith(new String[] { "--localInterfaceOnly" });

        assertTrue(config.getLocalInterfaceOnly());
    }
}
