package com.zack88604.autoupdater.gui.preset;

import com.zack88604.autoupdater.infrastructure.http.ServerGuiPresetTransport;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;

/**
 * Retrieves and installs a server GUI preset before UI creation.
 *
 * <p>The configured update server is trusted to publish the descriptor and
 * archive. The descriptor hash and size still detect interrupted or mismatched
 * downloads; loading classes remains the bootstrap's responsibility after the
 * user has approved the server and preset identity.</p>
 */
public final class ServerGuiPresetManager {

    private static final String DESCRIPTOR_PATH = "/api/v2/gui-preset";

    private final ServerGuiPresetTransport transport;

    public ServerGuiPresetManager() {
        this(new ServerGuiPresetTransport());
    }

    ServerGuiPresetManager(ServerGuiPresetTransport transport) {
        this.transport = transport;
    }

    /**
     * Return an installed offer or {@code null} when the optional server offer
     * is unavailable, invalid, or cannot be installed.
     */
    public InstalledPreset install(List<String> serverUrls, GuiPresetStore store) {
        if (serverUrls == null) {
            return null;
        }
        for (String serverUrl : serverUrls) {
            ServerGuiPresetTransport.Response response = transport.fetchDescriptor(
                    Collections.singletonList(serverUrl), DESCRIPTOR_PATH);
            if (response == null) {
                continue;
            }

            ServerGuiPresetOffer offer;
            try {
                offer = ServerGuiPresetOffer.parse(response.getBody());
            } catch (IllegalArgumentException error) {
                continue;
            }

            InstalledPreset installed = installFromResponse(response, offer, store);
            if (installed != null) {
                return installed;
            }
        }
        return null;
    }

    private InstalledPreset installFromResponse(ServerGuiPresetTransport.Response response,
                                                ServerGuiPresetOffer offer,
                                                GuiPresetStore store) {
        File temporary = null;
        try {
            File destination = store.getServerPresetArchive(offer.getId());
            if (!matchesExpectedArchive(destination, offer)) {
                temporary = File.createTempFile("server-gui-", ".jar", destination.getParentFile());
                if (!transport.download(response, offer.getDownloadPath(), temporary,
                        offer.getSize()) || !matchesExpectedArchive(temporary, offer)) {
                    return null;
                }
                store.replaceServerPresetArchive(temporary, destination);
                temporary = null;
            }

            GuiPreset preset = findPreset(store.findLoadablePresets(),
                    offer.getArchiveName());
            if (preset == null) {
                return null;
            }
            return new InstalledPreset(offer, preset, response.getServerUrl());
        } catch (IOException error) {
            return null;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException ignored) {
                    // A later launch will clean its own fresh temporary file.
                }
            }
        }
    }

    private static GuiPreset findPreset(List<GuiPreset> presets, String archiveName) {
        for (GuiPreset preset : presets) {
            if (archiveName.equals(preset.getArchiveName())) {
                return preset;
            }
        }
        return null;
    }

    private static boolean matchesExpectedArchive(File archive, ServerGuiPresetOffer offer)
            throws IOException {
        return archive.isFile() && archive.length() == offer.getSize()
                && offer.getSha256().equals(sha256(archive));
    }

    private static String sha256(File archive) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(archive)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hash.append(Character.forDigit((value >>> 4) & 0x0F, 16));
                hash.append(Character.forDigit(value & 0x0F, 16));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    /** An installed archive plus the server identity that supplied it. */
    public static final class InstalledPreset {
        private final ServerGuiPresetOffer offer;
        private final GuiPreset preset;
        private final String serverUrl;

        private InstalledPreset(ServerGuiPresetOffer offer, GuiPreset preset,
                                String serverUrl) {
            this.offer = offer;
            this.preset = preset;
            this.serverUrl = serverUrl;
        }

        public ServerGuiPresetOffer getOffer() {
            return offer;
        }

        public GuiPreset getPreset() {
            return preset;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        public ServerGuiPresetTrust toTrustRecord() {
            return new ServerGuiPresetTrust(serverUrl, offer.getId(), offer.getArchiveName());
        }
    }
}
