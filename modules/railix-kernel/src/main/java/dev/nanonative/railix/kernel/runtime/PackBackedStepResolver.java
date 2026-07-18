package dev.nanonative.railix.kernel.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Resolves steps from installed pack providers discovered through {@link ServiceLoader}.
 */
public final class PackBackedStepResolver implements StepResolver {

    private final List<StepProvider> providers;

    public PackBackedStepResolver() {
        this(loadProviders());
    }

    public PackBackedStepResolver(final Iterable<? extends StepProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        final List<StepProvider> loadedProviders = new ArrayList<>();
        for (final StepProvider provider : providers) {
            loadedProviders.add(Objects.requireNonNull(provider, "provider"));
        }
        this.providers = List.copyOf(loadedProviders);
    }

    @Override
    public Step resolve(final String use) {
        Objects.requireNonNull(use, "use");
        if (use.isBlank()) {
            throw new IllegalArgumentException("use must not be blank");
        }
        Step resolved = null;
        String matchedProvider = null;
        for (final StepProvider provider : providers) {
            final Step candidate = provider.resolve(use).orElse(null);
            if (candidate == null) {
                continue;
            }
            if (resolved != null) {
                throw new IllegalStateException(
                        "Multiple step providers resolved use: "
                                + use
                                + " ["
                                + matchedProvider
                                + ", "
                                + provider.getClass().getName()
                                + "]"
                );
            }
            resolved = candidate;
            matchedProvider = provider.getClass().getName();
        }
        return resolved;
    }

    public static List<StepProvider> loadProviders() {
        final LinkedHashMap<String, StepProvider> providers = new LinkedHashMap<>();
        for (final StepProvider provider : ServiceLoader.load(StepProvider.class)) {
            providers.putIfAbsent(provider.getClass().getName(), provider);
        }
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            for (final StepProvider provider : ServiceLoader.load(StepProvider.class, contextClassLoader)) {
                providers.putIfAbsent(provider.getClass().getName(), provider);
            }
        }
        return List.copyOf(new ArrayList<>(providers.values()));
    }
}
