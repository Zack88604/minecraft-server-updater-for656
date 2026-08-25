/**
 * A single file entry from the update manifest.
 */
class FileEntry {
    final String path, hash;
    final int size;

    FileEntry(String path, String hash, int size) {
        this.path = path;
        this.hash = hash;
        this.size = size;
    }
}
