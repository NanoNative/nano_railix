package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.StepContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Captures deterministic runtime capability metadata from installed {@link StepProvider}s.
 */
public final class StepProviderCatalog {

    private StepProviderCatalog() {}

    /**
     * Loads the currently installed provider catalog through {@link java.util.ServiceLoader}.
     *
     * @return deterministic provider catalog
     */
    public static Report loadInstalled() {
        return capture(PackBackedStepResolver.loadProviders());
    }

    /**
     * Captures provider metadata from the provided providers.
     *
     * @param providers provider iterable
     * @return deterministic provider catalog
     */
    public static Report capture(final Iterable<? extends StepProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        final List<ProviderEntry> entries = new ArrayList<>();
        final Set<String> moduleIds = new LinkedHashSet<>();
        final Set<String> supportedUses = new LinkedHashSet<>();
        final Set<String> stepKinds = new LinkedHashSet<>();
        final Set<String> supportedResourceRefPatterns = new LinkedHashSet<>();
        final Map<String, String> useOwners = new LinkedHashMap<>();
        int unreportedProviderCount = 0;
        for (final StepProvider provider : providers) {
            final StepProvider nonNullProvider = Objects.requireNonNull(provider, "provider");
            final ProviderEntry entry = ProviderEntry.from(nonNullProvider);
            entries.add(entry);
            moduleIds.add(entry.moduleId());
            if (entry.reportsSupportedUses()) {
                for (final String supportedUse : entry.supportedUses()) {
                    final String owner = entry.moduleId() + ":" + entry.providerClassName();
                    final String previousOwner = useOwners.putIfAbsent(supportedUse, owner);
                    if (previousOwner != null) {
                        throw new IllegalStateException("Multiple step providers advertised use: " + supportedUse);
                    }
                    supportedUses.add(supportedUse);
                    final Step resolvedStep = provider.resolve(supportedUse)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Provider advertised use but did not resolve it: " + supportedUse
                            ));
                    final StepContract contract = Objects.requireNonNull(resolvedStep.contract(), "contract");
                    stepKinds.add(contract.kind().name());
                    supportedResourceRefPatterns.addAll(supportedResourceRefPatterns(contract));
                }
            } else {
                unreportedProviderCount++;
            }
        }
        entries.sort(java.util.Comparator.comparing(ProviderEntry::moduleId).thenComparing(ProviderEntry::providerClassName));
        return new Report(
                entries,
                List.copyOf(moduleIds),
                List.copyOf(supportedUses),
                List.copyOf(stepKinds),
                List.copyOf(supportedResourceRefPatterns),
                unreportedProviderCount
        );
    }

    /**
     * Deterministic provider-catalog report.
     *
     * @param providers provider entries
     * @param providerModuleIds unique provider module ids
     * @param supportedUses unique supported use ids
     * @param stepKinds unique resolved step kinds for reported uses
     * @param supportedResourceRefPatterns unique requested resource-ref patterns for reported uses
     * @param unreportedProviderCount providers that did not report stable use metadata
     */
    public record Report(
            List<ProviderEntry> providers,
            List<String> providerModuleIds,
            List<String> supportedUses,
            List<String> stepKinds,
            List<String> supportedResourceRefPatterns,
            int unreportedProviderCount
    ) {
        public Report {
            providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
            providerModuleIds = normalizedStrings(providerModuleIds, "providerModuleIds");
            supportedUses = normalizedStrings(supportedUses, "supportedUses");
            stepKinds = normalizedStrings(stepKinds, "stepKinds");
            supportedResourceRefPatterns = normalizedStrings(supportedResourceRefPatterns, "supportedResourceRefPatterns");
            if (unreportedProviderCount < 0 || unreportedProviderCount > providers.size()) {
                throw new IllegalArgumentException("unreportedProviderCount must be between 0 and provider count");
            }
        }

        public int providerCount() {
            return providers.size();
        }

        public int providerModuleCount() {
            return providerModuleIds.size();
        }

        public int supportedUseCount() {
            return supportedUses.size();
        }

        public int supportedResourceRefPatternCount() {
            return supportedResourceRefPatterns.size();
        }

        public int reportedProviderCount() {
            return providers.size() - unreportedProviderCount;
        }

        public boolean reportsCompleteUseCatalog() {
            return unreportedProviderCount == 0;
        }

        public LinkedHashMap<String, Object> toRuntimeIdentityModel() {
            final LinkedHashMap<String, Object> identity = orderedMap(
                    "contractVersion", 1,
                    "status", "provider-backed",
                    "identitySource", "service-loader-step-providers",
                    "reportsCompleteUseCatalog", reportsCompleteUseCatalog(),
                    "remoteExecutionCompatible", false,
                    "notes", "Runtime provider identity only; not workspace pack install state, remote execution support, or cache compatibility.",
                    "providerCount", providerCount(),
                    "reportedProviderCount", reportedProviderCount(),
                    "unreportedProviderCount", unreportedProviderCount,
                    "providerModuleCount", providerModuleCount(),
                    "supportedUseCount", supportedUseCount(),
                    "supportedResourceRefPatternCount", supportedResourceRefPatternCount(),
                    "providerModuleIds", providerModuleIds,
                    "supportedUses", supportedUses,
                    "stepKinds", stepKinds,
                    "supportedResourceRefPatterns", supportedResourceRefPatterns
            );
            if (reportsCompleteUseCatalog()) {
                final LinkedHashMap<String, Object> capabilityBasis = orderedMap(
                        "contractVersion", 1,
                        "identitySource", "service-loader-step-providers",
                        "providerModuleIds", providerModuleIds,
                        "supportedUses", supportedUses,
                        "stepKinds", stepKinds,
                        "supportedResourceRefPatterns", supportedResourceRefPatterns
                );
                identity.put("capabilityDigestAlgorithm", "sha256");
                identity.put("capabilityDigest", sha256Digest(KernelContractCodec.toStableJson(capabilityBasis)));
            }
            return identity;
        }

        public LinkedHashMap<String, Object> toUiModel() {
            return orderedMap(
                    "providerCount", providerCount(),
                    "reportedProviderCount", reportedProviderCount(),
                    "unreportedProviderCount", unreportedProviderCount,
                    "providerModuleCount", providerModuleCount(),
                    "supportedUseCount", supportedUseCount(),
                    "stepKinds", stepKinds,
                    "supportedResourceRefPatternCount", supportedResourceRefPatternCount(),
                    "supportedResourceRefPatterns", supportedResourceRefPatterns,
                    "providerModuleIds", providerModuleIds,
                    "supportedUses", supportedUses,
                    "providers", providers.stream().map(ProviderEntry::toUiModel).toList()
            );
        }
    }

    /**
     * Deterministic provider entry.
     *
     * @param moduleId runtime module id
     * @param providerClassName provider class name
     * @param reportsSupportedUses whether the provider reported exact supported use metadata
     * @param supportedUses supported stable use identifiers
     */
    public record ProviderEntry(
            String moduleId,
            String providerClassName,
            boolean reportsSupportedUses,
            List<String> supportedUses
    ) {
        public ProviderEntry {
            moduleId = requireNonBlank(moduleId, "moduleId");
            providerClassName = requireNonBlank(providerClassName, "providerClassName");
            supportedUses = normalizedStrings(supportedUses, "supportedUses");
        }

        static ProviderEntry from(final StepProvider provider) {
            final List<String> supportedUses = provider.supportedUses();
            return new ProviderEntry(
                    provider.providerModuleId(),
                    provider.getClass().getName(),
                    !supportedUses.isEmpty(),
                    supportedUses
            );
        }

        LinkedHashMap<String, Object> toUiModel() {
            return orderedMap(
                    "moduleId", moduleId,
                    "providerClassName", providerClassName,
                    "reportsSupportedUses", reportsSupportedUses,
                    "supportedUses", supportedUses
            );
        }
    }

    private static List<String> normalizedStrings(final List<String> values, final String fieldName) {
        Objects.requireNonNull(values, fieldName);
        final LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (final String value : values) {
            normalized.add(requireNonBlank(value, fieldName + "[]"));
        }
        return normalized.stream().sorted().toList();
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static List<String> supportedResourceRefPatterns(final StepContract contract) {
        final List<String> patterns = new ArrayList<>();
        contract.permissions().requested().forEach((permission, resources) -> {
            if ("settings.read".equals(permission)) {
                return;
            }
            for (final String resource : resources) {
                patterns.add(requireNonBlank(resource, "supportedResourceRefPatterns[]"));
            }
        });
        return patterns.stream()
                .map(resource -> resource.toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
    }

    private static LinkedHashMap<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    private static String sha256Digest(final String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
