package com.zack88604.autoupdater.gui.javafx;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * Central Maven artifact URL construction for the JavaFX runtime (Phase 2A).
 *
 * <p>The URL layout is the standard Maven repository path:
 * {@code <root>/org/openjfx/<module>/<version>/<module>-<version>-<classifier>.jar}.
 * All runtime download URL construction lives here — call sites never hardcode a
 * full URL. The root is swappable so future phases can add mirrors / failover
 * repositories (Maven Central today, e.g. Aliyun mirrors for CN users later);
 * {@code JavaFxRuntimeManager} holds the {@link MavenRepository} instance and the
 * default is Maven Central.</p>
 */
final class MavenRepository {

    static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2";
    private static final String OPENJFX_GROUP_PATH = "org/openjfx";

    private final String root;

    MavenRepository() {
        this(MAVEN_CENTRAL);
    }

    MavenRepository(String root) {
        String r = root == null ? MAVEN_CENTRAL : root;
        this.root = r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
    }

    /** Base repository URL (without a trailing slash). */
    String root() {
        return root;
    }

    /**
     * Maven Central URL for one runtime artifact, derived from
     * module/version/classifier (the embedded spec's authoritative coordinates):
     * {@code https://repo1.maven.org/maven2/org/openjfx/javafx-base/21.0.4/javafx-base-21.0.4-win.jar}.
     */
    URL artifactUrl(String version, JavaFxRuntimeManager.Artifact artifact)
            throws MalformedURLException {
        String file = artifact.module + "-" + version + "-" + artifact.classifier + ".jar";
        String path = OPENJFX_GROUP_PATH + "/" + artifact.module + "/" + version + "/" + file;
        return URI.create(root + "/" + path).toURL();
    }
}
