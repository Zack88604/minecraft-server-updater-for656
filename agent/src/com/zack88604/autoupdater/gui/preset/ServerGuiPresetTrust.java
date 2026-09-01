package com.zack88604.autoupdater.gui.preset;

import java.util.Objects;

/**
 * A local record that the user approved one update server and preset identity.
 *
 * <p>The configured update server is the trust boundary. Approval is scoped to
 * its normalized URL and one preset identifier, rather than granting blanket
 * permission for arbitrary archives.</p>
 */
public final class ServerGuiPresetTrust {

    private final String serverUrl;
    private final String presetId;
    private final String archiveName;

    public ServerGuiPresetTrust(String serverUrl, String presetId, String archiveName) {
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.presetId = Objects.requireNonNull(presetId, "presetId");
        this.archiveName = Objects.requireNonNull(archiveName, "archiveName");
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getPresetId() {
        return presetId;
    }

    public String getArchiveName() {
        return archiveName;
    }

    /** Return whether this trust decision applies to the supplied server offer. */
    public boolean matches(ServerGuiPresetOffer offer, String offeredServerUrl) {
        return serverUrl.equals(offeredServerUrl)
                && presetId.equals(offer.getId())
                && archiveName.equals(offer.getArchiveName());
    }
}
