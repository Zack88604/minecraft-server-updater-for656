package com.zack88604.autoupdater.gui.preset;

import java.util.Objects;

/**
 * A local record that the user approved one signed server GUI-preset identity.
 *
 * <p>Trust is bound to the preset identifier, key identifier, and fingerprint
 * of the locally configured public key. It is not a blanket trust decision for
 * arbitrary archives sent by the server.</p>
 */
public final class ServerGuiPresetTrust {

    private final String presetId;
    private final String keyId;
    private final String keyFingerprint;
    private final String archiveName;

    public ServerGuiPresetTrust(String presetId, String keyId, String keyFingerprint,
                                String archiveName) {
        this.presetId = Objects.requireNonNull(presetId, "presetId");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.keyFingerprint = Objects.requireNonNull(keyFingerprint, "keyFingerprint");
        this.archiveName = Objects.requireNonNull(archiveName, "archiveName");
    }

    public String getPresetId() {
        return presetId;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getKeyFingerprint() {
        return keyFingerprint;
    }

    public String getArchiveName() {
        return archiveName;
    }

    /** Return whether this trust decision applies to the supplied signed offer. */
    public boolean matches(ServerGuiPresetOffer offer, String expectedFingerprint) {
        return presetId.equals(offer.getId())
                && keyId.equals(offer.getKeyId())
                && keyFingerprint.equals(expectedFingerprint)
                && archiveName.equals(offer.getArchiveName());
    }
}
