package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.StepContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepProviderCatalogTest {

    @Test
    void shouldCaptureDeterministicProviderModulesAndSupportedUses() {
        final StepProviderCatalog.Report report = StepProviderCatalog.capture(List.of(
                provider("railix.std.trigger", "dev.example.TriggerProvider", List.of(
                        stepDescriptor("railix.std.trigger.ManualTrigger", StepContract.Kind.TRIGGER, Map.of()),
                        stepDescriptor("railix.std.trigger.CliTrigger", StepContract.Kind.TRIGGER, Map.of())
                )),
                provider("railix.std.data", "dev.example.DataProvider", List.of(
                        stepDescriptor("railix.std.data.DataTransform", StepContract.Kind.NORMAL, Map.of()),
                        stepDescriptor("railix.std.data.DataValidate", StepContract.Kind.NORMAL, Map.of())
                ))
        ));

        assertThat(report.providerCount()).isEqualTo(2);
        assertThat(report.reportedProviderCount()).isEqualTo(2);
        assertThat(report.unreportedProviderCount()).isZero();
        assertThat(report.providerModuleCount()).isEqualTo(2);
        assertThat(report.supportedUseCount()).isEqualTo(4);
        assertThat(report.stepKinds()).containsExactly("NORMAL", "TRIGGER");
        assertThat(report.supportedResourceRefPatternCount()).isZero();
        assertThat(report.providerModuleIds()).containsExactly("railix.std.data", "railix.std.trigger");
        assertThat(report.supportedUses()).containsExactly(
                "railix.std.data.DataTransform",
                "railix.std.data.DataValidate",
                "railix.std.trigger.CliTrigger",
                "railix.std.trigger.ManualTrigger"
        );
        assertThat(report.providers()).extracting(StepProviderCatalog.ProviderEntry::moduleId)
                .containsExactly("railix.std.data", "railix.std.trigger");
        assertThat(report.providers()).allSatisfy(provider -> assertThat(provider.reportsSupportedUses()).isTrue());
        assertThat(report.providers().getFirst().supportedUses())
                .containsExactly("railix.std.data.DataTransform", "railix.std.data.DataValidate");
        assertThat(report.toUiModel())
                .containsEntry("providerCount", 2)
                .containsEntry("reportedProviderCount", 2)
                .containsEntry("unreportedProviderCount", 0)
                .containsEntry("providerModuleCount", 2)
                .containsEntry("supportedUseCount", 4)
                .containsEntry("supportedResourceRefPatternCount", 0);
    }

    @Test
    void shouldCaptureSupportedResourceRefPatternsFromResolvedStepContracts() {
        final StepProviderCatalog.Report report = StepProviderCatalog.capture(List.of(
                provider("railix.std.file", "dev.example.FileProvider", List.of(
                        stepDescriptor(
                                "railix.std.file.FileWrite",
                                StepContract.Kind.NORMAL,
                                Map.of(
                                        "settings.read", List.of("settings.file.rootDir"),
                                        "resource.create", List.of("file/*"),
                                        "resource.write", List.of("file/*")
                                )
                        )
                )),
                provider("railix.std.store", "dev.example.StoreProvider", List.of(
                        stepDescriptor(
                                "railix.std.store.StoreRead",
                                StepContract.Kind.NORMAL,
                                Map.of(
                                        "settings.read", List.of("settings.store.rootDir"),
                                        "store.read", List.of("store/*")
                                )
                        )
                ))
        ));

        assertThat(report.supportedResourceRefPatternCount()).isEqualTo(2);
        assertThat(report.supportedResourceRefPatterns()).containsExactly("file/*", "store/*");
        assertThat(report.stepKinds()).containsExactly("NORMAL");
    }

    @Test
    void shouldRejectBlankSupportedUseMetadata() {
        assertThatThrownBy(() -> StepProviderCatalog.capture(List.of(
                provider("railix.std.data", "dev.example.InvalidProvider", List.of(
                        stepDescriptor(" ", StepContract.Kind.NORMAL, Map.of())
                ))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supportedUses[]");
    }

    @Test
    void shouldMarkProvidersWithoutSupportedUseMetadataAsUnreported() {
        final StepProviderCatalog.Report report = StepProviderCatalog.capture(List.of(provider(
                "railix.std.legacy",
                "dev.example.LegacyProvider",
                List.of()
        )));

        assertThat(report.providerCount()).isEqualTo(1);
        assertThat(report.reportedProviderCount()).isZero();
        assertThat(report.unreportedProviderCount()).isEqualTo(1);
        assertThat(report.supportedUseCount()).isZero();
        assertThat(report.providers().getFirst().reportsSupportedUses()).isFalse();
        assertThat(report.toUiModel()).containsEntry("unreportedProviderCount", 1);
    }

    @Test
    void shouldExposeDeterministicProviderBackedRuntimeIdentity() {
        final StepProviderCatalog.Report firstReport = StepProviderCatalog.capture(List.of(
                provider("railix.std.trigger", "dev.example.TriggerProvider", List.of(
                        stepDescriptor("railix.std.trigger.ManualTrigger", StepContract.Kind.TRIGGER, Map.of()),
                        stepDescriptor("railix.std.trigger.CliTrigger", StepContract.Kind.TRIGGER, Map.of())
                )),
                provider("railix.std.data", "dev.example.DataProvider", List.of(
                        stepDescriptor(
                                "railix.std.data.DataTransform",
                                StepContract.Kind.NORMAL,
                                Map.of("resource.read", List.of("store/*"))
                        )
                ))
        ));
        final StepProviderCatalog.Report secondReport = StepProviderCatalog.capture(List.of(
                provider("railix.std.data", "dev.example.DataProvider", List.of(
                        stepDescriptor(
                                "railix.std.data.DataTransform",
                                StepContract.Kind.NORMAL,
                                Map.of("resource.read", List.of("store/*"))
                        )
                )),
                provider("railix.std.trigger", "dev.example.TriggerProvider", List.of(
                        stepDescriptor("railix.std.trigger.ManualTrigger", StepContract.Kind.TRIGGER, Map.of()),
                        stepDescriptor("railix.std.trigger.CliTrigger", StepContract.Kind.TRIGGER, Map.of())
                ))
        ));

        final Map<String, Object> firstIdentity = firstReport.toRuntimeIdentityModel();
        final Map<String, Object> secondIdentity = secondReport.toRuntimeIdentityModel();

        assertThat(firstReport.reportsCompleteUseCatalog()).isTrue();
        assertThat(firstIdentity)
                .containsEntry("contractVersion", 1)
                .containsEntry("status", "provider-backed")
                .containsEntry("identitySource", "service-loader-step-providers")
                .containsEntry("reportsCompleteUseCatalog", true)
                .containsEntry("remoteExecutionCompatible", false)
                .containsEntry("providerModuleCount", 2)
                .containsEntry("supportedUseCount", 3)
                .containsEntry("supportedResourceRefPatternCount", 1);
        assertThat(firstIdentity).doesNotContainKey("workspacePackIds");
        assertThat((String) firstIdentity.get("notes")).contains("not workspace pack install state");
        assertThat((String) firstIdentity.get("capabilityDigestAlgorithm")).isEqualTo("sha256");
        assertThat((String) firstIdentity.get("capabilityDigest")).startsWith("sha256:");
        assertThat(firstIdentity.get("capabilityDigest")).isEqualTo(secondIdentity.get("capabilityDigest"));
        assertThat(firstIdentity.get("providerModuleIds")).isEqualTo(secondIdentity.get("providerModuleIds"));
        assertThat(firstIdentity.get("supportedUses")).isEqualTo(secondIdentity.get("supportedUses"));
        assertThat(firstIdentity.get("supportedResourceRefPatterns"))
                .isEqualTo(secondIdentity.get("supportedResourceRefPatterns"));
    }

    @Test
    void shouldReportIncompleteProviderBackedRuntimeIdentityWhenProviderMetadataIsUnreported() {
        final StepProviderCatalog.Report report = StepProviderCatalog.capture(List.of(provider(
                "railix.std.legacy",
                "dev.example.LegacyProvider",
                List.of()
        )));

        final Map<String, Object> identity = report.toRuntimeIdentityModel();

        assertThat(report.reportsCompleteUseCatalog()).isFalse();
        assertThat(identity)
                .containsEntry("status", "provider-backed")
                .containsEntry("identitySource", "service-loader-step-providers")
                .containsEntry("reportsCompleteUseCatalog", false)
                .containsEntry("remoteExecutionCompatible", false)
                .containsEntry("providerCount", 1)
                .containsEntry("reportedProviderCount", 0)
                .containsEntry("unreportedProviderCount", 1);
        assertThat(identity).doesNotContainKey("capabilityDigest");
        assertThat(identity).doesNotContainKey("capabilityDigestAlgorithm");
    }

    @Test
    void shouldRejectReportedUseThatDoesNotResolve() {
        assertThatThrownBy(() -> StepProviderCatalog.capture(List.of(new StepProvider() {
            @Override
            public Optional<Step> resolve(final String use) {
                return Optional.empty();
            }

            @Override
            public String providerModuleId() {
                return "railix.std.legacy";
            }

            @Override
            public List<String> supportedUses() {
                return List.of("railix.std.legacy.LegacyStep");
            }
        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Provider advertised use but did not resolve it");
    }

    @Test
    void shouldRejectDuplicateReportedUseOwnership() {
        assertThatThrownBy(() -> StepProviderCatalog.capture(List.of(
                provider("railix.std.one", "dev.example.ProviderOne", List.of(
                        stepDescriptor("railix.std.shared.Step", StepContract.Kind.NORMAL, Map.of())
                )),
                provider("railix.std.two", "dev.example.ProviderTwo", List.of(
                        stepDescriptor("railix.std.shared.Step", StepContract.Kind.NORMAL, Map.of())
                ))
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple step providers advertised use: railix.std.shared.Step");
    }

    private static StepProvider provider(
            final String moduleId,
            final String providerClassName,
            final List<StepDescriptor> stepDescriptors
    ) {
        return new StepProvider() {
            @Override
            public Optional<Step> resolve(final String use) {
                return stepDescriptors.stream()
                        .filter(stepDescriptor -> stepDescriptor.useId().equals(use))
                        .findFirst()
                        .map(StepProviderCatalogTest::step);
            }

            @Override
            public String providerModuleId() {
                return moduleId;
            }

            @Override
            public List<String> supportedUses() {
                return stepDescriptors.stream().map(StepDescriptor::useId).toList();
            }

            @Override
            public String toString() {
                return providerClassName;
            }
        };
    }

    private static Step step(final StepDescriptor stepDescriptor) {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        stepDescriptor.useId(),
                        "0.1.0",
                        stepDescriptor.useId(),
                        stepDescriptor.useId(),
                        stepDescriptor.kind(),
                        List.of(),
                        List.of(),
                        Map.of("ok", new StepContract.Outcome("ok")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.requestedOnly(stepDescriptor.requestedPermissions()),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of()
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return new Result("ok", List.of());
            }
        };
    }

    private static StepDescriptor stepDescriptor(
            final String useId,
            final StepContract.Kind kind,
            final Map<String, List<String>> requestedPermissions
    ) {
        return new StepDescriptor(useId, kind, requestedPermissions);
    }

    private record StepDescriptor(
            String useId,
            StepContract.Kind kind,
            Map<String, List<String>> requestedPermissions
    ) {}
}
