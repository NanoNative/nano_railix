package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.StepContract;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepProvider;
import dev.nanonative.railix.kernel.runtime.StepProviderCatalog;
import dev.nanonative.railix.kernel.runtime.StepResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorRemoteExecutionBoundaryTest {

    @Test
    void shouldRejectRemoteExecutionRequestWhenExecutionResolverDriftsFromAdvertisedCapability() {
        final StepProviderCatalog.Report report = StepProviderCatalog.capture(List.of(advertisedProvider(
                "std.data.capture-payload"
        )));
        final String instanceId = "creator-instance-test";
        final String capabilityDigest = (String) report.toRuntimeIdentityModel().get("capabilityDigest");
        final StepResolver missingResolver = use -> null;

        final CreatorRemoteExecutionBoundary.BoundaryResponse response = CreatorRemoteExecutionBoundary.evaluate(
                KernelContractCodec.toStableJson(validRequest(instanceId, capabilityDigest)),
                instanceId,
                report,
                missingResolver,
                Map.of("entries", List.of()),
                Path.of("build").resolve("runs"),
                true
        );

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body())
                .containsEntry("decision", "rejected")
                .containsEntry("accepted", false)
                .containsEntry("rejectionCode", "runtime-capability-drift")
                .containsEntry("queueSupported", false);
        assertThat((Map<String, Object>) response.body().get("result")).containsEntry("status", "not-executed");
    }

    private static Map<String, Object> validRequest(final String instanceId, final String capabilityDigest) {
        final LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        request.put("requestContractVersion", 1);
        request.put("requestId", "remote-boundary-drift");
        request.put("requesterInstanceId", instanceId);
        request.put("targetInstanceId", instanceId);
        request.put("stepUse", "std.data.capture-payload");
        request.put("resourceRefs", List.of());
        request.put("capabilityDigest", capabilityDigest);
        request.put("replyMode", "IMMEDIATE");
        request.put("permissions", KernelContractCodec.toUiModel(PermissionSet.none()));
        request.put("stepConfig", KernelContractCodec.toUiModel(new RailixValue.ObjectValue(Map.of())));
        request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                "remote.dev",
                "remote",
                new RailixValue.ObjectValue(Map.of("orderId", new RailixValue.StringValue("order-1"))),
                new RailixValue.ObjectValue(Map.of()),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        )));
        request.put("timeoutMillis", 0);
        return request;
    }

    private static StepProvider advertisedProvider(final String useId) {
        return new StepProvider() {
            @Override
            public Optional<Step> resolve(final String use) {
                if (!useId.equals(use)) {
                    return Optional.empty();
                }
                return Optional.of(new Step() {
                    @Override
                    public StepContract contract() {
                        return new StepContract(
                                useId,
                                "0.1.0",
                                useId,
                                useId,
                                StepContract.Kind.NORMAL,
                                List.of(),
                                List.of(),
                                Map.of("ok", new StepContract.Outcome("ok")),
                                new StepContract.Settings(List.of()),
                                PermissionSet.none(),
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
                });
            }

            @Override
            public String providerModuleId() {
                return "railix.std.data";
            }

            @Override
            public List<String> supportedUses() {
                return List.of(useId);
            }
        };
    }
}
