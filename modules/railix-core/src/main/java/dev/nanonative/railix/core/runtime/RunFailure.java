package dev.nanonative.railix.core.runtime;

/** Stable runtime failure. Expected business behavior remains a declared Step outcome. */
public record RunFailure(String code, String message, String stepId) {
}
