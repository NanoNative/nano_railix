package dev.nanonative.railix.core.runtime;

/**
 * Stable runtime failure. Expected business behavior remains a declared Step outcome.
 *
 * @param code stable machine-readable failure code
 * @param message concise developer-facing explanation
 * @param stepId executable Step id that failed
 * @param path exact authored nested-program path, or empty for a top-level Step failure
 */
public record RunFailure(String code, String message, String stepId, String path) {
    /**
     * Creates a failure owned by a top-level Step.
     *
     * @param code stable machine-readable failure code
     * @param message concise developer-facing explanation
     * @param stepId executable Step id that failed
     */
    public RunFailure(final String code, final String message, final String stepId) {
        this(code, message, stepId, "");
    }
}
