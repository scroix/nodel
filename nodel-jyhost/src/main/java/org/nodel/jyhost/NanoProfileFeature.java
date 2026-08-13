package org.nodel.jyhost;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import java.io.File;
import java.io.InputStream;

import org.nodel.io.Stream;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeSystemProperties;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Bakes nano-only runtime properties into the native executable.
 */
public final class NanoProfileFeature implements Feature {

    static final String RECIPES_SYNC_ENABLED_PROPERTY = "nodel.recipesSync.enabled";

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        RuntimeSystemProperties.register(RECIPES_SYNC_ENABLED_PROPERTY, "false");
        RuntimeSystemProperties.register("polyglot.engine.WarnInterpreterOnly", "false");
    }
}

/** Replaces only the nano image's first-run bootstrap; normal JVM code is unchanged. */
@TargetClass(Launch.class)
final class Target_org_nodel_jyhost_Launch {

    @Alias private NodelHost _nodelHost;

    @Substitute
    private void firstTimePrep() {
        if (!Boolean.parseBoolean(System.getProperty(NanoProfileFeature.RECIPES_SYNC_ENABLED_PROPERTY, "true")))
            return;

        if (_nodelHost.getRoot().list().length > 0)
            return;

        File nodeFolder = new File(_nodelHost.getRoot(), "_tmpFirstNode");
        nodeFolder.mkdirs();

        try (InputStream is = PyNode.class.getResourceAsStream("first_node.py")) {
            Stream.writeFully(is, new File(nodeFolder, "script.py"));
        } catch (Exception exc) {
            // Preserve the standard bootstrap's best-effort extraction.
        }

        String nodeName = String.format("Nodel Recipes Sync for $HOSTNAME ${http port} (${os.name}, ${mac})");
        nodeFolder.renameTo(new File(_nodelHost.getRoot(), nodeName));
    }
}
