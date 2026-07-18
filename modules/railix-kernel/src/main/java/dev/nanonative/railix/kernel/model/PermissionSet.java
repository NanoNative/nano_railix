package dev.nanonative.railix.kernel.model;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PermissionSet(
        Map<String, List<String>> requested,
        Map<String, List<String>> granted,
        List<Decision> decisions
) {
    public PermissionSet {
        requested = normalizePermissions(requested);
        granted = normalizePermissions(granted);
        decisions = List.copyOf(decisions);
    }

    public static PermissionSet none() {
        return new PermissionSet(Map.of(), Map.of(), List.of());
    }

    public static PermissionSet requestedOnly(final Map<String, List<String>> requested) {
        return new PermissionSet(requested, Map.of(), List.of());
    }

    public record Decision(String permission, String resource, DecisionResult decision, String reason) {
        public Decision {
            permission = requireNonBlank(permission, "permission");
            resource = requireNonBlank(resource, "resource");
            decision = Objects.requireNonNull(decision, "decision");
            reason = requireNonBlank(reason, "reason");
        }
    }

    public enum DecisionResult {
        GRANTED,
        DENIED
    }

    private static Map<String, List<String>> normalizePermissions(final Map<String, List<String>> permissions) {
        final Map<String, List<String>> normalized = new LinkedHashMap<>();
        permissions.forEach((key, value) -> normalized.put(requireNonBlank(key, "permission"), List.copyOf(value)));
        return Map.copyOf(normalized);
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
