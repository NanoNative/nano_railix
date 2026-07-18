package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.SettingsTree;
import dev.nanonative.railix.kernel.runtime.StepProviderCatalog;
import dev.nanonative.railix.kernel.runtime.AppPlan;
import dev.nanonative.railix.kernel.runtime.BuiltRailixApp;
import dev.nanonative.railix.kernel.runtime.BuiltRailixAppLoader;
import dev.nanonative.railix.kernel.runtime.LocalExecutionKernel;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepResolver;

import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Same-instance-only remote execution boundary with deterministic rejection plus inline or deferred execution.
 */
public final class CreatorRemoteExecutionBoundary {

    private static final int CONTRACT_VERSION = 1;
    private static final String ENDPOINT_PATH = "/api/remote-execution/requests";
    private static final String PREFLIGHT_ENDPOINT_PATH = "/api/remote-execution/preflight";
    private static final String REMOTE_APP_ID = "creator-remote-execution";

    private CreatorRemoteExecutionBoundary() {}

    public static BoundaryResponse evaluate(
            final String requestBody,
            final String currentInstanceId,
            final StepProviderCatalog.Report stepProviderCatalog,
            final StepResolver stepResolver,
            final Map<String, Object> instancesSnapshot,
            final Path runsRoot,
            final boolean deferredQueueAvailable
    ) {
        final Request request = Request.parse(requestBody);
        final Map<String, Object> runtimeIdentity = stepProviderCatalog.toRuntimeIdentityModel();
        final boolean supportedHere = stepProviderCatalog.supportedUses().contains(request.stepUse())
                && firstUnsupportedResourceRef(stepProviderCatalog.supportedResourceRefPatterns(), request.resourceRefs()) == null;
        final boolean capabilityDigestMatched = stepProviderCatalog.reportsCompleteUseCatalog()
                && stringValue(runtimeIdentity, "capabilityDigest").equals(request.capabilityDigest());
        if (!Objects.equals(currentInstanceId, request.targetInstanceId())) {
            return rejectForDifferentTarget(
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    capabilityDigestMatched,
                    supportedHere,
                    Objects.requireNonNull(instancesSnapshot, "instancesSnapshot")
            );
        }
        if (!stepProviderCatalog.supportedUses().contains(request.stepUse())) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    capabilityDigestMatched,
                    false,
                    "unsupported-step-use",
                    "Requested step use is not advertised by this runtime.",
                    false
            );
        }
        final String unsupportedResourceRef = firstUnsupportedResourceRef(
                stepProviderCatalog.supportedResourceRefPatterns(),
                request.resourceRefs()
        );
        if (unsupportedResourceRef != null) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    capabilityDigestMatched,
                    false,
                    "unsupported-resource-ref",
                    "Requested resource ref is outside the advertised runtime resource-ref patterns: " + unsupportedResourceRef,
                    false
            );
        }
        if (!stepProviderCatalog.reportsCompleteUseCatalog()) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    false,
                    true,
                    "runtime-identity-incomplete",
                    "Runtime identity is incomplete because installed step providers did not report a full supported-use catalog.",
                    false
            );
        }
        if (!stringValue(runtimeIdentity, "capabilityDigest").equals(request.capabilityDigest())) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    false,
                    true,
                    "runtime-identity-mismatch",
                    "Capability digest did not match the current runtime identity.",
                    false
            );
        }
        if (!Objects.equals(currentInstanceId, request.requesterInstanceId())) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "trust-loopback-only",
                    "The current remote execution boundary only trusts loopback requests from the same instance id.",
                    false
            );
        }
        if (!request.permissions().requested().isEmpty() || !request.permissions().decisions().isEmpty()) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "permission-propagation-unsupported",
                    "Cross-instance permission propagation is not supported by the current remote execution boundary.",
                    false
            );
        }
        if (!isSupportedReplyMode(request.replyMode())) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "reply-mode-unsupported",
                    "Only NONE, IMMEDIATE, and DEFERRED reply modes are accepted by the current remote execution request boundary.",
                    false
            );
        }
        if ("DEFERRED".equals(request.replyMode()) && !deferredQueueAvailable) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "deferred-queue-unavailable",
                    "Deferred same-instance remote execution is currently unavailable because the local deferred queue is at capacity.",
                    true
            );
        }
        if (request.timeoutMillis() != 0L) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "timeout-unsupported",
                    "timeoutMillis is not supported by the current remote execution boundary.",
                    "DEFERRED".equals(request.replyMode())
            );
        }
        final Step step;
        try {
            step = requireResolvedStep(stepResolver, request.stepUse());
        } catch (final RuntimeException exception) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "runtime-capability-drift",
                    "The installed runtime advertised the requested step but did not resolve it through the active execution resolver: "
                            + summarize(exception),
                    "DEFERRED".equals(request.replyMode())
            );
        }
        if (!step.contract().settings().requested().isEmpty() && request.settingsLocation().isBlank()) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "settings-location-required",
                    "Same-instance execution for steps with settings reads requires a target-local settingsLocation.",
                    false
            );
        }
        return accepted(
                request,
                currentInstanceId,
                stepProviderCatalog.supportedResourceRefPatterns(),
                runtimeIdentity,
                stepResolver,
                runsRoot
        );
    }

    public static BoundaryResponse preflight(
            final String requestBody,
            final String currentInstanceId,
            final StepProviderCatalog.Report stepProviderCatalog,
            final StepResolver stepResolver,
            final Map<String, Object> instancesSnapshot,
            final boolean deferredQueueAvailable
    ) {
        final Request request = Request.parse(requestBody);
        final Map<String, Object> runtimeIdentity = stepProviderCatalog.toRuntimeIdentityModel();
        final boolean supportedHere = stepProviderCatalog.supportedUses().contains(request.stepUse())
                && firstUnsupportedResourceRef(stepProviderCatalog.supportedResourceRefPatterns(), request.resourceRefs()) == null;
        final boolean capabilityDigestMatched = stepProviderCatalog.reportsCompleteUseCatalog()
                && stringValue(runtimeIdentity, "capabilityDigest").equals(request.capabilityDigest());
        if (!Objects.equals(currentInstanceId, request.targetInstanceId())) {
            return preflightRejectForDifferentTarget(
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    capabilityDigestMatched,
                    supportedHere,
                    Objects.requireNonNull(instancesSnapshot, "instancesSnapshot")
            );
        }
        if (!stepProviderCatalog.supportedUses().contains(request.stepUse())) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    capabilityDigestMatched,
                    false,
                    "unsupported-step-use",
                    "Requested step use is not advertised by this runtime.",
                    deferredQueueAvailable
            );
        }
        final String unsupportedResourceRef = firstUnsupportedResourceRef(
                stepProviderCatalog.supportedResourceRefPatterns(),
                request.resourceRefs()
        );
        if (unsupportedResourceRef != null) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    capabilityDigestMatched,
                    false,
                    "unsupported-resource-ref",
                    "Requested resource ref is outside the advertised runtime resource-ref patterns: " + unsupportedResourceRef,
                    deferredQueueAvailable
            );
        }
        if (!stepProviderCatalog.reportsCompleteUseCatalog()) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    false,
                    true,
                    "runtime-identity-incomplete",
                    "Runtime identity is incomplete because installed step providers did not report a full supported-use catalog.",
                    deferredQueueAvailable
            );
        }
        if (!stringValue(runtimeIdentity, "capabilityDigest").equals(request.capabilityDigest())) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    false,
                    true,
                    "runtime-identity-mismatch",
                    "Capability digest did not match the current runtime identity.",
                    deferredQueueAvailable
            );
        }
        if (!Objects.equals(currentInstanceId, request.requesterInstanceId())) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "trust-loopback-only",
                    "The current remote execution boundary only trusts loopback requests from the same instance id.",
                    deferredQueueAvailable
            );
        }
        if (!request.permissions().requested().isEmpty() || !request.permissions().decisions().isEmpty()) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "permission-propagation-unsupported",
                    "Cross-instance permission propagation is not supported by the current remote execution boundary.",
                    deferredQueueAvailable
            );
        }
        if (!isSupportedReplyMode(request.replyMode())) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "reply-mode-unsupported",
                    "Only NONE, IMMEDIATE, and DEFERRED reply modes are accepted by the current remote execution request boundary.",
                    deferredQueueAvailable
            );
        }
        if ("DEFERRED".equals(request.replyMode()) && !deferredQueueAvailable) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "deferred-queue-unavailable",
                    "Deferred same-instance remote execution is currently unavailable because the local deferred queue is at capacity.",
                    false
            );
        }
        if (request.timeoutMillis() != 0L) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "timeout-unsupported",
                    "timeoutMillis is not supported by the current remote execution boundary.",
                    deferredQueueAvailable
            );
        }
        final Step step;
        try {
            step = requireResolvedStep(stepResolver, request.stepUse());
        } catch (final RuntimeException exception) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "runtime-capability-drift",
                    "The installed runtime advertised the requested step but did not resolve it through the active execution resolver: "
                            + summarize(exception),
                    deferredQueueAvailable
            );
        }
        if (!step.contract().settings().requested().isEmpty() && request.settingsLocation().isBlank()) {
            return preflightReject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    true,
                    true,
                    "settings-location-required",
                    "Same-instance execution for steps with settings reads requires a target-local settingsLocation.",
                    deferredQueueAvailable
            );
        }
        return new BoundaryResponse(
                200,
                admittedBody(
                        request,
                        currentInstanceId,
                        stepProviderCatalog.supportedResourceRefPatterns(),
                        runtimeIdentity,
                        deferredQueueAvailable
                )
        );
    }

    public static BoundaryResponse invalidRequest(final RuntimeException exception) {
        return new BoundaryResponse(400, orderedMap(
                "requestContractVersion", CONTRACT_VERSION,
                "responseContractVersion", CONTRACT_VERSION,
                "decision", "rejected",
                "accepted", false,
                "rejectionCode", "invalid-request",
                "message", summarize(exception),
                "remoteExecutionSupported", false,
                "sameInstanceExecutionSupported", true,
                "executionScope", "same-instance-only",
                "runtimeIdentityMatched", false,
                "capabilityDigestMatched", false,
                "supportedHere", false,
                "supportedResourceRefs", List.of(),
                "permissionPropagationSupported", false,
                "queueSupported", false,
                "result", orderedMap(
                        "status", "not-executed",
                        "runId", "",
                        "outcome", "",
                        "reply", Map.of()
                ),
                "exitCode", 2,
                "succeeded", false
        ));
    }

    public static BoundaryResponse invalidPreflightRequest(final RuntimeException exception) {
        return new BoundaryResponse(400, orderedMap(
                "requestContractVersion", CONTRACT_VERSION,
                "responseContractVersion", CONTRACT_VERSION,
                "decision", "rejected",
                "admitted", false,
                "rejectionCode", "invalid-request",
                "message", summarize(exception),
                "remoteExecutionSupported", false,
                "sameInstanceExecutionSupported", true,
                "preflightScope", "same-instance-only",
                "deliverySupported", false,
                "executionStarted", false,
                "executionScheduled", false,
                "targetKnown", false,
                "targetState", "",
                "runtimeIdentityMatched", false,
                "capabilityDigestMatched", false,
                "supportedHere", false,
                "supportedResourceRefs", List.of(),
                "permissionPropagationSupported", false,
                "queueSupported", false
        ));
    }

    public static Map<String, Object> advertisement() {
        return orderedMap(
                "status", "same-instance-inline-and-deferred",
                "endpointPath", ENDPOINT_PATH,
                "preflightEndpointPath", PREFLIGHT_ENDPOINT_PATH,
                "requestContractVersion", CONTRACT_VERSION,
                "responseContractVersion", CONTRACT_VERSION,
                "acceptedExecutionSupported", true,
                "acceptedExecutionScope", "same-instance-inline-or-deferred",
                "preflightSupported", true,
                "supportedReplyModes", List.of("NONE", "IMMEDIATE", "DEFERRED"),
                "deterministicRejectionSupported", true,
                "queueSupported", true,
                "supportedDecisionStatuses", List.of("accepted", "rejected"),
                "supportedPreflightDecisionStatuses", List.of("admitted", "rejected"),
                "supportedRejectionReasons", List.of(
                        "unknown-target-instance",
                        "unsupported-step-use",
                        "unsupported-resource-ref",
                        "runtime-identity-incomplete",
                        "runtime-identity-mismatch",
                        "target-instance-inactive",
                        "target-runtime-identity-incomplete",
                        "target-runtime-identity-mismatch",
                        "target-unsupported-step-use",
                        "target-unsupported-resource-ref",
                        "cross-instance-delivery-unsupported",
                        "trust-loopback-only",
                        "permission-propagation-unsupported",
                        "reply-mode-unsupported",
                        "deferred-queue-unavailable",
                        "timeout-unsupported",
                        "runtime-capability-drift",
                        "settings-location-required",
                        "invalid-request"
                )
        );
    }

    private static BoundaryResponse reject(
            final int status,
            final Request request,
            final String currentInstanceId,
            final StepProviderCatalog.Report stepProviderCatalog,
            final Map<String, Object> runtimeIdentity,
            final boolean capabilityDigestMatched,
            final boolean supportedHere,
            final String rejectionCode,
            final String message,
            final boolean queueSupported
    ) {
        return new BoundaryResponse(status, orderedMap(
                "requestContractVersion", CONTRACT_VERSION,
                "responseContractVersion", CONTRACT_VERSION,
                "requestId", request.requestId(),
                "requesterInstanceId", request.requesterInstanceId(),
                "targetInstanceId", currentInstanceId,
                "decision", "rejected",
                "accepted", false,
                "rejectionCode", rejectionCode,
                "message", message,
                "remoteExecutionSupported", false,
                "sameInstanceExecutionSupported", true,
                "executionScope", "same-instance-only",
                "runtimeIdentityMatched", capabilityDigestMatched,
                "capabilityDigestMatched", capabilityDigestMatched,
                "supportedHere", supportedHere,
                "supportedResourceRefs", stepProviderCatalog.supportedResourceRefPatterns(),
                "permissionPropagationSupported", false,
                "queueSupported", queueSupported,
                "result", orderedMap(
                        "status", "not-executed",
                        "runId", "",
                        "outcome", "",
                        "reply", Map.of()
                )
        ));
    }

    private static BoundaryResponse preflightReject(
            final int status,
            final Request request,
            final String currentInstanceId,
            final StepProviderCatalog.Report stepProviderCatalog,
            final Map<String, Object> runtimeIdentity,
            final boolean capabilityDigestMatched,
            final boolean supportedHere,
            final String rejectionCode,
            final String message,
            final boolean queueSupported
    ) {
        final Map<String, Object> base = reject(
                status,
                request,
                currentInstanceId,
                stepProviderCatalog,
                runtimeIdentity,
                capabilityDigestMatched,
                supportedHere,
                rejectionCode,
                message,
                queueSupported
        ).body();
        return new BoundaryResponse(
                status,
                preflightRejectedBody(
                        request,
                        runtimeIdentity,
                        base,
                        Objects.equals(request.targetInstanceId(), currentInstanceId),
                        Objects.equals(request.targetInstanceId(), currentInstanceId) ? "active" : "",
                        Map.of()
                )
        );
    }

    private static BoundaryResponse preflightRejectForDifferentTarget(
            final Request request,
            final String currentInstanceId,
            final StepProviderCatalog.Report stepProviderCatalog,
            final Map<String, Object> runtimeIdentity,
            final boolean capabilityDigestMatched,
            final boolean supportedHere,
            final Map<String, Object> instancesSnapshot
    ) {
        final BoundaryResponse rejection = rejectForDifferentTarget(
                request,
                currentInstanceId,
                stepProviderCatalog,
                runtimeIdentity,
                capabilityDigestMatched,
                supportedHere,
                instancesSnapshot
        );
        final Map<String, Object> discoveredTarget = findDiscoveredInstance(instancesSnapshot, request.targetInstanceId());
        final LinkedHashMap<String, Object> discoveredDiagnostics = new LinkedHashMap<>();
        copyDiscoveredTargetDiagnostics(rejection.body(), discoveredDiagnostics);
        return new BoundaryResponse(
                rejection.status(),
                preflightRejectedBody(
                        request,
                        runtimeIdentity,
                        rejection.body(),
                        !discoveredTarget.isEmpty(),
                        stringOrBlank(discoveredTarget, "state"),
                        discoveredDiagnostics
                )
        );
    }

    private static BoundaryResponse rejectForDifferentTarget(
            final Request request,
            final String currentInstanceId,
            final StepProviderCatalog.Report stepProviderCatalog,
            final Map<String, Object> runtimeIdentity,
            final boolean capabilityDigestMatched,
            final boolean supportedHere,
            final Map<String, Object> instancesSnapshot
    ) {
        final Map<String, Object> discoveredTarget = findDiscoveredInstance(instancesSnapshot, request.targetInstanceId());
        if (discoveredTarget.isEmpty()) {
            return reject(
                    409,
                    request,
                    currentInstanceId,
                    stepProviderCatalog,
                    runtimeIdentity,
                    capabilityDigestMatched,
                    supportedHere,
                    "unknown-target-instance",
                    "Remote execution request targeted a different instance.",
                    false
            );
        }
        final Map<String, Object> targetRuntimeIdentity = mapOrEmpty(discoveredTarget, "runtimeIdentity");
        final List<String> targetSupportedUses = stringListOrEmpty(targetRuntimeIdentity, "supportedUses");
        final List<String> targetSupportedResourceRefs = stringListOrEmpty(targetRuntimeIdentity, "supportedResourceRefPatterns");
        final boolean targetActive = "active".equals(stringOrBlank(discoveredTarget, "state"));
        final boolean targetRuntimeIdentityComplete = booleanOrFalse(targetRuntimeIdentity, "reportsCompleteUseCatalog");
        final String targetCapabilityDigest = stringOrBlank(targetRuntimeIdentity, "capabilityDigest");
        final boolean targetCapabilityDigestMatched = targetRuntimeIdentityComplete
                && Objects.equals(targetCapabilityDigest, request.capabilityDigest());
        final boolean targetSupportsRequestedUse = targetSupportedUses.contains(request.stepUse());
        final String targetUnsupportedResourceRef = firstUnsupportedResourceRef(targetSupportedResourceRefs, request.resourceRefs());
        final boolean targetSupportsRequestedResourceRefs = targetUnsupportedResourceRef == null;
        final TargetRejection rejection;
        if (!targetActive) {
            rejection = new TargetRejection(
                    "target-instance-inactive",
                    "Target instance is discovered through the shared registry but is not currently active."
            );
        } else if (!targetRuntimeIdentityComplete) {
            rejection = new TargetRejection(
                    "target-runtime-identity-incomplete",
                    "Target instance is discovered, but its runtime identity is incomplete so cross-instance planning cannot rely on its capability catalog yet."
            );
        } else if (!targetCapabilityDigestMatched) {
            rejection = new TargetRejection(
                    "target-runtime-identity-mismatch",
                    "Target instance is discovered, but its advertised capability digest does not match the request."
            );
        } else if (!targetSupportsRequestedUse) {
            rejection = new TargetRejection(
                    "target-unsupported-step-use",
                    "Target instance is discovered, but it does not advertise the requested step use."
            );
        } else if (!targetSupportsRequestedResourceRefs) {
            rejection = new TargetRejection(
                    "target-unsupported-resource-ref",
                    "Target instance is discovered, but it does not advertise the requested resource-ref pattern: "
                            + targetUnsupportedResourceRef
            );
        } else {
            rejection = new TargetRejection(
                    "cross-instance-delivery-unsupported",
                    "Target instance is discovered and capability-aligned, but cross-instance remote execution delivery is still unsupported. The current boundary remains loopback-only."
            );
        }
        final LinkedHashMap<String, Object> body = new LinkedHashMap<>(reject(
                409,
                request,
                currentInstanceId,
                stepProviderCatalog,
                runtimeIdentity,
                capabilityDigestMatched,
                supportedHere,
                rejection.rejectionCode(),
                rejection.message(),
                false
        ).body());
        body.put("discoveredTargetKnown", true);
        body.put("discoveredTargetInstanceId", stringOrBlank(discoveredTarget, "instanceId"));
        body.put("discoveredTargetState", stringOrBlank(discoveredTarget, "state"));
        body.put("discoveredTargetCurrentInstance", booleanOrFalse(discoveredTarget, "currentInstance"));
        body.put("discoveredTargetLoopbackControlUrl", stringOrBlank(discoveredTarget, "loopbackControlUrl"));
        body.put("discoveredTargetTrustMode", stringOrBlank(mapOrEmpty(discoveredTarget, "trust"), "mode"));
        body.put("discoveredTargetRemoteExecutionBoundaryStatus", stringOrBlank(
                mapOrEmpty(discoveredTarget, "remoteExecutionBoundary"),
                "status"
        ));
        body.put("discoveredTargetAcceptedExecutionScope", stringOrBlank(
                mapOrEmpty(discoveredTarget, "remoteExecutionBoundary"),
                "acceptedExecutionScope"
        ));
        body.put("discoveredTargetCapabilityDigest", targetCapabilityDigest);
        body.put("discoveredTargetCapabilityDigestMatched", targetCapabilityDigestMatched);
        body.put("discoveredTargetSupportsRequestedUse", targetSupportsRequestedUse);
        body.put("discoveredTargetSupportsRequestedResourceRefs", targetSupportsRequestedResourceRefs);
        return new BoundaryResponse(409, body);
    }

    private static Map<String, Object> findDiscoveredInstance(
            final Map<String, Object> instancesSnapshot,
            final String targetInstanceId
    ) {
        for (final Map<String, Object> instance : listOfMaps(instancesSnapshot, "entries")) {
            if (Objects.equals(targetInstanceId, stringOrBlank(instance, "instanceId"))) {
                return instance;
            }
        }
        return Map.of();
    }

    private static BoundaryResponse accepted(
            final Request request,
            final String currentInstanceId,
            final List<String> supportedResourceRefs,
            final Map<String, Object> runtimeIdentity,
            final StepResolver stepResolver,
            final Path runsRoot
    ) {
        final String executionId = createExecutionId();
        final ExecutionPlan executionPlan = prepareExecution(request, executionId, stepResolver, runsRoot);
        if ("DEFERRED".equals(request.replyMode())) {
            return new BoundaryResponse(
                    202,
                    executionPlan.queuedResponseBody(currentInstanceId, supportedResourceRefs, runtimeIdentity),
                    new DeferredExecution(
                            executionId,
                            () -> executionPlan.completedResponseBody(
                                    currentInstanceId,
                                    supportedResourceRefs,
                                    runtimeIdentity,
                                    true,
                                    "Deferred request completed on the same instance through the current remote execution boundary."
                            ),
                            interruptionMessage -> executionPlan.interruptedResponseBody(
                                    currentInstanceId,
                                    supportedResourceRefs,
                                    runtimeIdentity,
                                    interruptionMessage
                            )
                    )
            );
        }
        return new BoundaryResponse(
                200,
                executionPlan.completedResponseBody(
                        currentInstanceId,
                        supportedResourceRefs,
                        runtimeIdentity,
                        false,
                        "Request executed inline on the same instance through the current remote execution boundary."
                )
        );
    }

    public static BoundaryResponse deferredQueueUnavailable(
            final String requestBody,
            final String currentInstanceId,
            final StepProviderCatalog.Report stepProviderCatalog,
            final String message
    ) {
        final Request request = Request.parse(requestBody);
        final Map<String, Object> runtimeIdentity = stepProviderCatalog.toRuntimeIdentityModel();
        final boolean supportedHere = stepProviderCatalog.supportedUses().contains(request.stepUse())
                && firstUnsupportedResourceRef(stepProviderCatalog.supportedResourceRefPatterns(), request.resourceRefs()) == null;
        final boolean capabilityDigestMatched = stepProviderCatalog.reportsCompleteUseCatalog()
                && stringValue(runtimeIdentity, "capabilityDigest").equals(request.capabilityDigest());
        return reject(
                409,
                request,
                currentInstanceId,
                stepProviderCatalog,
                runtimeIdentity,
                capabilityDigestMatched,
                supportedHere,
                "deferred-queue-unavailable",
                message,
                true
        );
    }

    private static ExecutionPlan prepareExecution(
            final Request request,
            final String executionId,
            final StepResolver stepResolver,
            final Path runsRoot
    ) {
        final Envelope envelope = KernelContractCodec.envelopeFromUiModel(request.inputEnvelope());
        final RailixValue.ObjectValue stepConfig = requireObjectValue(request.stepConfig(), "stepConfig");
        final String flowId = flowIdForUse(request.stepUse());
        final String runId = createRunId(request.requestId(), executionId);
        final SettingsTree settingsTree = request.settingsLocation().isBlank()
                ? SettingsTree.empty()
                : loadSettingsTree(request.settingsLocation());
        final AppPlan plan = new AppPlan(
                REMOTE_APP_ID,
                flowId,
                "remote-step",
                localPlanPermissions(request.permissions()),
                List.of(new AppPlan.StepInvocation("remote-step", request.stepUse(), stepConfig, Map.of()))
        );
        return new ExecutionPlan(
                executionId,
                request,
                plan,
                envelope,
                settingsTree,
                stepResolver,
                runId,
                runsRoot,
                runsRoot.resolve(REMOTE_APP_ID).resolve(flowId).resolve(runId).toAbsolutePath().normalize()
        );
    }

    private static Step requireResolvedStep(final StepResolver stepResolver, final String use) {
        final Step step = Objects.requireNonNull(stepResolver, "stepResolver").resolve(use);
        if (step == null) {
            throw new IllegalStateException("Installed runtime did not resolve advertised step use: " + use);
        }
        return step;
    }

    private static SettingsTree loadSettingsTree(final String settingsLocation) {
        try {
            return BuiltRailixAppLoader.loadSettingsTree(settingsLocation);
        } catch (final RuntimeException exception) {
            throw new IllegalArgumentException("settingsLocation could not be loaded: " + exception.getMessage(), exception);
        }
    }

    private static PermissionSet localPlanPermissions(final PermissionSet requestPermissions) {
        return new PermissionSet(Map.of(), requestPermissions.granted(), List.of());
    }

    private static RailixValue.ObjectValue requireObjectValue(final Map<String, Object> model, final String fieldName) {
        final RailixValue value = KernelContractCodec.railixValueFromUiModel(model);
        if (value instanceof RailixValue.ObjectValue objectValue) {
            return objectValue;
        }
        throw new IllegalArgumentException(fieldName + " must decode to an object value");
    }

    private static String flowIdForUse(final String stepUse) {
        final String normalized = stepUse.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return normalized.isBlank() ? "same-instance-inline" : normalized;
    }

    private static String createExecutionId() {
        return "remote-execution-" + UUID.randomUUID();
    }

    private static String createRunId(final String requestId, final String executionId) {
        final String normalizedRequestId = requestId.isBlank()
                ? "anonymous-request"
                : requestId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        final String normalizedExecutionId = executionId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return "run-remote-execution-" + normalizedRequestId + "-" + normalizedExecutionId;
    }

    private static String firstUnsupportedResourceRef(final List<String> supportedPatterns, final List<String> resourceRefs) {
        for (final String resourceRef : resourceRefs) {
            final boolean supported = supportedPatterns.stream().anyMatch(pattern -> resourceRefMatchesPattern(resourceRef, pattern));
            if (!supported) {
                return resourceRef;
            }
        }
        return null;
    }

    private static boolean resourceRefMatchesPattern(final String resourceRef, final String pattern) {
        if (pattern.endsWith("/*")) {
            final String prefix = pattern.substring(0, pattern.length() - 1);
            return resourceRef.startsWith(prefix);
        }
        return pattern.equals(resourceRef);
    }

    private static boolean isSupportedReplyMode(final String replyMode) {
        return "NONE".equals(replyMode) || "IMMEDIATE".equals(replyMode) || "DEFERRED".equals(replyMode);
    }

    private static String summarize(final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private static int intValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        throw new IllegalArgumentException(key + " must be an integer");
    }

    private static boolean booleanOrFalse(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }
        throw new IllegalArgumentException(key + " must be an object");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof Map<?, ?> mapValue ? (Map<String, Object>) mapValue : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> listValue)) {
            throw new IllegalArgumentException(key + " must be an array of strings");
        }
        final List<String> strings = new ArrayList<>();
        for (final Object entry : listValue) {
            if (!(entry instanceof String stringValue) || stringValue.isBlank()) {
                throw new IllegalArgumentException(key + " must be an array of non-blank strings");
            }
            strings.add(stringValue);
        }
        return List.copyOf(strings);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof List<?> listValue)) {
            return List.of();
        }
        final List<Map<String, Object>> maps = new ArrayList<>();
        for (final Object entry : listValue) {
            if (entry instanceof Map<?, ?> mapValue) {
                maps.add((Map<String, Object>) mapValue);
            }
        }
        return List.copyOf(maps);
    }

    private static List<String> stringListOrEmpty(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof List<?> listValue)) {
            return List.of();
        }
        final List<String> strings = new ArrayList<>();
        for (final Object entry : listValue) {
            if (entry instanceof String stringValue && !stringValue.isBlank()) {
                strings.add(stringValue);
            }
        }
        return List.copyOf(strings);
    }

    private static String stringValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        throw new IllegalArgumentException(key + " must be a non-blank string");
    }

    private static String stringOrBlank(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof String stringValue ? stringValue : "";
    }

    private static LinkedHashMap<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    public record BoundaryResponse(int status, Map<String, Object> body, DeferredExecution deferredExecution) {
        public BoundaryResponse(final int status, final Map<String, Object> body) {
            this(status, body, null);
        }
    }

    public record DeferredExecution(
            String executionId,
            Supplier<Map<String, Object>> complete,
            Function<String, Map<String, Object>> interrupted
    ) {
        public DeferredExecution {
            executionId = Objects.requireNonNull(executionId, "executionId");
            complete = Objects.requireNonNull(complete, "complete");
            interrupted = Objects.requireNonNull(interrupted, "interrupted");
        }
    }

    private record TargetRejection(String rejectionCode, String message) {
        private TargetRejection {
            rejectionCode = Objects.requireNonNull(rejectionCode, "rejectionCode");
            message = Objects.requireNonNull(message, "message");
        }
    }

    private static Map<String, Object> admittedBody(
            final Request request,
            final String currentInstanceId,
            final List<String> supportedResourceRefs,
            final Map<String, Object> runtimeIdentity,
            final boolean queueSupported
    ) {
        return orderedMap(
                "requestContractVersion", CONTRACT_VERSION,
                "responseContractVersion", CONTRACT_VERSION,
                "requestId", request.requestId(),
                "requesterInstanceId", request.requesterInstanceId(),
                "targetInstanceId", currentInstanceId,
                "decision", "admitted",
                "admitted", true,
                "rejectionCode", "",
                "message", "Request is admitted for same-instance execution through the current remote execution preflight boundary.",
                "remoteExecutionSupported", false,
                "sameInstanceExecutionSupported", true,
                "preflightScope", "same-instance-only",
                "deliverySupported", true,
                "executionStarted", false,
                "executionScheduled", false,
                "targetKnown", true,
                "targetState", "active",
                "runtimeIdentityMatched", true,
                "capabilityDigestMatched", true,
                "supportedHere", true,
                "supportedResourceRefs", supportedResourceRefs,
                "permissionPropagationSupported", false,
                "queueSupported", queueSupported,
                "runtimeIdentityDigest", stringValue(runtimeIdentity, "capabilityDigest")
        );
    }

    private static Map<String, Object> preflightRejectedBody(
            final Request request,
            final Map<String, Object> runtimeIdentity,
            final Map<String, Object> base,
            final boolean targetKnown,
            final String targetState,
            final Map<String, Object> discoveredDiagnostics
    ) {
        final LinkedHashMap<String, Object> body = orderedMap(
                "requestContractVersion", base.get("requestContractVersion"),
                "responseContractVersion", base.get("responseContractVersion"),
                "requestId", request.requestId(),
                "requesterInstanceId", request.requesterInstanceId(),
                "targetInstanceId", request.targetInstanceId(),
                "decision", "rejected",
                "admitted", false,
                "rejectionCode", base.get("rejectionCode"),
                "message", base.get("message"),
                "remoteExecutionSupported", base.get("remoteExecutionSupported"),
                "sameInstanceExecutionSupported", base.get("sameInstanceExecutionSupported"),
                "preflightScope", "same-instance-only",
                "deliverySupported", false,
                "executionStarted", false,
                "executionScheduled", false,
                "targetKnown", targetKnown,
                "targetState", targetState,
                "runtimeIdentityMatched", base.get("runtimeIdentityMatched"),
                "capabilityDigestMatched", base.get("capabilityDigestMatched"),
                "supportedHere", base.get("supportedHere"),
                "supportedResourceRefs", base.get("supportedResourceRefs"),
                "permissionPropagationSupported", base.get("permissionPropagationSupported"),
                "queueSupported", base.get("queueSupported"),
                "runtimeIdentityDigest", stringOrBlank(runtimeIdentity, "capabilityDigest")
        );
        body.putAll(discoveredDiagnostics);
        return body;
    }

    private static void copyDiscoveredTargetDiagnostics(
            final Map<String, Object> source,
            final Map<String, Object> target
    ) {
        for (final String key : List.of(
                "discoveredTargetKnown",
                "discoveredTargetInstanceId",
                "discoveredTargetState",
                "discoveredTargetCurrentInstance",
                "discoveredTargetLoopbackControlUrl",
                "discoveredTargetTrustMode",
                "discoveredTargetRemoteExecutionBoundaryStatus",
                "discoveredTargetAcceptedExecutionScope",
                "discoveredTargetCapabilityDigest",
                "discoveredTargetCapabilityDigestMatched",
                "discoveredTargetSupportsRequestedUse",
                "discoveredTargetSupportsRequestedResourceRefs"
        )) {
            if (source.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
    }

    private static Map<String, Object> acceptedBody(
            final Request request,
            final String currentInstanceId,
            final List<String> supportedResourceRefs,
            final Map<String, Object> runtimeIdentity,
            final String executionId,
            final String message,
            final boolean queueSupported,
            final Map<String, Object> result
    ) {
        return orderedMap(
                "requestContractVersion", CONTRACT_VERSION,
                "responseContractVersion", CONTRACT_VERSION,
                "executionId", executionId,
                "requestId", request.requestId(),
                "requesterInstanceId", request.requesterInstanceId(),
                "targetInstanceId", currentInstanceId,
                "decision", "accepted",
                "accepted", true,
                "rejectionCode", "",
                "message", message,
                "remoteExecutionSupported", false,
                "sameInstanceExecutionSupported", true,
                "executionScope", "same-instance-only",
                "runtimeIdentityMatched", true,
                "capabilityDigestMatched", true,
                "supportedHere", true,
                "supportedResourceRefs", supportedResourceRefs,
                "permissionPropagationSupported", false,
                "queueSupported", queueSupported,
                "result", result,
                "runtimeIdentityDigest", stringValue(runtimeIdentity, "capabilityDigest")
        );
    }

    private record Request(
            String requestId,
            String requesterInstanceId,
            String targetInstanceId,
            String stepUse,
            List<String> resourceRefs,
            String capabilityDigest,
            String replyMode,
            PermissionSet permissions,
            Map<String, Object> stepConfig,
            Map<String, Object> inputEnvelope,
            String settingsLocation,
            long timeoutMillis
    ) {
        private static Request parse(final String requestBody) {
            final Map<String, Object> values = KernelContractCodec.parseStableJsonObject(requestBody);
            if (intValue(values, "requestContractVersion") != CONTRACT_VERSION) {
                throw new IllegalArgumentException("requestContractVersion must be " + CONTRACT_VERSION);
            }
            return new Request(
                    optionalStringValue(values, "requestId"),
                    stringValue(values, "requesterInstanceId"),
                    stringValue(values, "targetInstanceId"),
                    stringValue(values, "stepUse"),
                    stringListValue(values, "resourceRefs"),
                    stringValue(values, "capabilityDigest"),
                    stringValue(values, "replyMode").toUpperCase(java.util.Locale.ROOT),
                    KernelContractCodec.permissionSetFromUiModel(mapValue(values, "permissions")),
                    mapValue(values, "stepConfig"),
                    mapValue(values, "inputEnvelope"),
                    optionalStringValue(values, "settingsLocation"),
                    optionalLongValue(values, "timeoutMillis")
            );
        }

        private static String optionalStringValue(final Map<String, Object> values, final String key) {
            final Object value = values.get(key);
            if (value == null) {
                return "";
            }
            if (value instanceof String stringValue) {
                return stringValue;
            }
            throw new IllegalArgumentException(key + " must be a string");
        }

        private static long optionalLongValue(final Map<String, Object> values, final String key) {
            final Object value = values.get(key);
            if (value == null) {
                return 0L;
            }
            if (value instanceof Number numberValue) {
                return numberValue.longValue();
            }
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private record ExecutionPlan(
            String executionId,
            Request request,
            AppPlan plan,
            Envelope envelope,
            SettingsTree settingsTree,
            StepResolver stepResolver,
            String runId,
            Path runsRoot,
            Path runFolder
    ) {
        private ExecutionPlan {
            executionId = Objects.requireNonNull(executionId, "executionId");
            request = Objects.requireNonNull(request, "request");
            plan = Objects.requireNonNull(plan, "plan");
            envelope = Objects.requireNonNull(envelope, "envelope");
            settingsTree = Objects.requireNonNull(settingsTree, "settingsTree");
            stepResolver = Objects.requireNonNull(stepResolver, "stepResolver");
            runId = Objects.requireNonNull(runId, "runId");
            runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
            runFolder = Objects.requireNonNull(runFolder, "runFolder");
        }

        private Map<String, Object> queuedResponseBody(
                final String currentInstanceId,
                final List<String> supportedResourceRefs,
                final Map<String, Object> runtimeIdentity
        ) {
            return acceptedBody(
                    request,
                    currentInstanceId,
                    supportedResourceRefs,
                    runtimeIdentity,
                    executionId,
                    "Request accepted for deferred same-instance execution. Poll the remote request history for completion.",
                    true,
                    orderedMap(
                            "status", "queued",
                            "appId", plan.appId(),
                            "flowId", plan.flowId(),
                            "runId", runId,
                            "outcome", "",
                            "executedSteps", 0,
                            "reply", Map.of(),
                            "outputContext", Map.of(),
                            "runFolder", runFolder.toString(),
                            "signalCount", 0
                    )
            );
        }

        private Map<String, Object> interruptedResponseBody(
                final String currentInstanceId,
                final List<String> supportedResourceRefs,
                final Map<String, Object> runtimeIdentity,
                final String interruptionMessage
        ) {
            return acceptedBody(
                    request,
                    currentInstanceId,
                    supportedResourceRefs,
                    runtimeIdentity,
                    executionId,
                    interruptionMessage,
                    true,
                    orderedMap(
                            "status", "interrupted",
                            "appId", plan.appId(),
                            "flowId", plan.flowId(),
                            "runId", runId,
                            "outcome", "",
                            "executedSteps", 0,
                            "reply", Map.of(),
                            "outputContext", Map.of(),
                            "runFolder", runFolder.toString(),
                            "signalCount", 0
                    )
            );
        }

        private Map<String, Object> completedResponseBody(
                final String currentInstanceId,
                final List<String> supportedResourceRefs,
                final Map<String, Object> runtimeIdentity,
                final boolean queueSupported,
                final String message
        ) {
            try {
                final LocalExecutionKernel.RunRecord record = new BuiltRailixApp(
                        plan,
                        new LocalExecutionKernel(),
                        stepResolver
                ).run(new LocalExecutionKernel.RunRequest(runId, runsRoot, envelope, settingsTree));
                return acceptedBody(
                        request,
                        currentInstanceId,
                        supportedResourceRefs,
                        runtimeIdentity,
                        executionId,
                        message,
                        queueSupported,
                        orderedMap(
                                "status", "failed".equals(record.outcome()) ? "failed" : "executed",
                                "appId", record.appId(),
                                "flowId", record.flowId(),
                                "runId", record.runId(),
                                "outcome", record.outcome(),
                                "executedSteps", record.executedSteps(),
                                "reply", KernelContractCodec.toUiModel(record.reply()),
                                "outputContext", KernelContractCodec.toUiModel(record.context().root()),
                                "runFolder", record.runFolder().toAbsolutePath().normalize().toString(),
                                "signalCount", record.signalLog().signalCount()
                        )
                );
            } catch (final RuntimeException exception) {
                return acceptedBody(
                        request,
                        currentInstanceId,
                        supportedResourceRefs,
                        runtimeIdentity,
                        executionId,
                        "Deferred request failed before the same-instance execution could complete: " + summarize(exception),
                        queueSupported,
                        orderedMap(
                                "status", "failed",
                                "appId", plan.appId(),
                                "flowId", plan.flowId(),
                                "runId", runId,
                                "outcome", "failed",
                                "executedSteps", 0,
                                "reply", Map.of(),
                                "outputContext", Map.of(),
                                "runFolder", runFolder.toString(),
                                "signalCount", 0
                        )
                );
            }
        }
    }
}
