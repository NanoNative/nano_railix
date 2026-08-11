package dev.nanonative.railix.core.project;

/** Stable compiler or runtime feedback suitable for Creator annotations. */
public record Diagnostic(
        String code,
        String message,
        String path,
        int line,
        int column
) {
    public static Diagnostic atPath(final String code, final String message, final String path) {
        return new Diagnostic(code, message, path, 0, 0);
    }
}
