/**
 * Final result returned by the update service.
 */
class UpdateResult {
    final int updated;
    final int failed;

    UpdateResult(int updated, int failed) {
        this.updated = updated;
        this.failed = failed;
    }
}
