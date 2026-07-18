package dev.nanonative.railix.kernel.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Resolves pack-provided steps for a {@code use} identifier.
 */
public interface StepProvider {

    /**
     * Resolve the step for the provided {@code use} identifier.
     *
     * @param use step identifier from the app plan
     * @return the resolved step when this provider supports the identifier
     */
    Optional<Step> resolve(String use);

    /**
     * Returns the runtime module identifier that owns this provider.
     *
     * @return non-blank runtime module identifier, or {@code unnamed} when unavailable
     */
    default String providerModuleId() {
        final Module module = getClass().getModule();
        final String moduleName = module == null ? "" : module.getName();
        return moduleName == null || moduleName.isBlank() ? "unnamed" : moduleName;
    }

    /**
     * Returns the stable {@code use} identifiers this provider intends to resolve.
     *
     * Providers that do not override this method are treated as not reporting capability metadata yet.
     *
     * @return immutable or defensively copied list of supported use identifiers
     */
    default List<String> supportedUses() {
        return List.of();
    }
}
