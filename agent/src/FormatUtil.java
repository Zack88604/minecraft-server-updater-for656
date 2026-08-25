/**
 * Small formatting helpers shared by the business and UI layers.
 */
final class FormatUtil {

    private FormatUtil() {}

    static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 0) bytesPerSec = 0;
        if (bytesPerSec >= 1_000_000_000) return String.format("%.1f GB/s", bytesPerSec / 1_000_000_000);
        if (bytesPerSec >= 1_000_000)     return String.format("%.1f MB/s", bytesPerSec / 1_000_000);
        if (bytesPerSec >= 1_000)         return String.format("%.0f KB/s", bytesPerSec / 1_000);
        return String.format("%.0f B/s", bytesPerSec);
    }
}
