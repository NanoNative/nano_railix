package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.runtime.AppPlan;
import dev.nanonative.railix.kernel.runtime.BuiltRailixAppLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Owns raw Creator authoring-spec document reads and saves for committed example app specs.
 */
public final class CreatorAuthoringSpecStore {

    private static final Pattern EXAMPLE_ID_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");
    private static final Pattern APP_ID_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*");
    private static final Pattern FLOW_ID_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");

    private CreatorAuthoringSpecStore() {}

    /**
     * Reads one committed authoring spec document under the repo examples tree.
     *
     * @param repoRoot workspace root
     * @param specPathValue absolute spec path
     * @return primitive UI model suitable for stable JSON
     */
    public static Map<String, Object> read(final Path repoRoot, final String specPathValue) {
        final Path normalizedRepoRoot = requireRepoRoot(repoRoot);
        final Path specPath = requireSpecPath(normalizedRepoRoot, specPathValue);
        final String content = readFile(specPath);
        return documentModel(normalizedRepoRoot, specPath, content, false);
    }

    /**
     * Saves one committed authoring spec document under the repo examples tree.
     *
     * @param repoRoot workspace root
     * @param requestBody stable-JSON request body containing {@code path} and {@code content}
     * @return primitive UI model suitable for stable JSON
     */
    public static Map<String, Object> save(final Path repoRoot, final String requestBody) {
        final Path normalizedRepoRoot = requireRepoRoot(repoRoot);
        final SaveRequest request = decodeSaveRequest(requestBody);
        final Path specPath = requireSpecPath(normalizedRepoRoot, request.path());
        writeFile(specPath, request.content());
        return documentModel(normalizedRepoRoot, specPath, request.content(), true);
    }

    /**
     * Creates one new example-scoped authoring spec and sample envelope under the repo examples tree.
     *
     * @param repoRoot workspace root
     * @param requestBody stable-JSON request body containing example and app metadata
     * @return primitive UI model suitable for stable JSON
     */
    public static Map<String, Object> create(final Path repoRoot, final String requestBody) {
        final Path normalizedRepoRoot = requireRepoRoot(repoRoot);
        final CreateRequest request = decodeCreateRequest(requestBody);
        final Path exampleDirectory = requireCreatableExampleDirectory(normalizedRepoRoot, request.exampleId());
        final Path specPath = exampleDirectory.resolve("railix.app.yaml");
        final Path envelopePath = exampleDirectory.resolve("sample.envelope.yaml");
        final String specContent = createSpecTemplate(request.appId(), request.appName(), request.flowId());
        final String envelopeContent = createEnvelopeTemplate();
        writeFile(specPath, specContent);
        writeFile(envelopePath, envelopeContent);
        final LinkedHashMap<String, Object> created = new LinkedHashMap<>(documentModel(normalizedRepoRoot, specPath, specContent, true));
        created.put("created", true);
        created.put("envelopePath", envelopePath.toString());
        created.put("relativeEnvelopePath", normalizedRepoRoot.relativize(envelopePath).toString());
        return Map.copyOf(created);
    }

    /**
     * Exports one committed authoring spec plus one committed example envelope into persisted JSON runtime inputs.
     *
     * @param repoRoot workspace root
     * @param requestBody stable-JSON request body containing {@code path} and {@code envelopePath}
     * @return primitive UI model suitable for stable JSON
     */
    public static Map<String, Object> export(final Path repoRoot, final String requestBody) {
        final Path normalizedRepoRoot = requireRepoRoot(repoRoot);
        final ExportRequest request = decodeExportRequest(requestBody);
        final Path specPath = requireSpecPath(normalizedRepoRoot, request.path());
        final Path envelopePath = requireEnvelopePath(specPath, request.envelopePath());
        final AppPlan appPlan = BuiltRailixAppLoader.loadPlan(specPath.toString());
        final Envelope envelope = BuiltRailixAppLoader.loadEnvelope(envelopePath.toString());
        final Path exportDirectory = exportDirectoryFor(envelopePath);
        final String envelopeBaseName = fileBaseName(envelopePath);
        final Path planLocation = exportDirectory.resolve("plan.json");
        final Path envelopeLocation = exportDirectory.resolve(envelopeBaseName + ".json");
        writeFile(planLocation, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(appPlan)));
        writeFile(envelopeLocation, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(envelope)));
        return orderedMap(
                "path", specPath.toString(),
                "relativePath", normalizedRepoRoot.relativize(specPath).toString(),
                "envelopePath", envelopePath.toString(),
                "relativeEnvelopePath", normalizedRepoRoot.relativize(envelopePath).toString(),
                "exportDirectory", exportDirectory.toString(),
                "planLocation", planLocation.toString(),
                "envelopeLocation", envelopeLocation.toString(),
                "exported", true,
                "appId", appPlan.appId(),
                "flowId", appPlan.flowId(),
                "message", ""
        );
    }

    private static SaveRequest decodeSaveRequest(final String requestBody) {
        final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(Objects.requireNonNull(requestBody, "requestBody"));
        return new SaveRequest(
                requiredString(body, "path"),
                requiredString(body, "content")
        );
    }

    private static ExportRequest decodeExportRequest(final String requestBody) {
        final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(Objects.requireNonNull(requestBody, "requestBody"));
        return new ExportRequest(
                requiredString(body, "path"),
                requiredString(body, "envelopePath")
        );
    }

    private static CreateRequest decodeCreateRequest(final String requestBody) {
        final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(Objects.requireNonNull(requestBody, "requestBody"));
        return new CreateRequest(
                requireExampleId(requiredString(body, "exampleId")),
                requireAppId(requiredString(body, "appId")),
                requireAppName(requiredString(body, "appName")),
                requireFlowId(requiredString(body, "flowId"))
        );
    }

    private static Map<String, Object> documentModel(
            final Path repoRoot,
            final Path specPath,
            final String content,
            final boolean saved
    ) {
        try {
            final AppPlan appPlan = BuiltRailixAppLoader.loadPlan(specPath.toString());
            return orderedMap(
                    "path", specPath.toString(),
                    "relativePath", repoRoot.relativize(specPath).toString(),
                    "content", content,
                    "saved", saved,
                    "parseStatus", "valid",
                    "appId", appPlan.appId(),
                    "flowId", appPlan.flowId(),
                    "message", ""
            );
        } catch (final RuntimeException exception) {
            return orderedMap(
                    "path", specPath.toString(),
                    "relativePath", repoRoot.relativize(specPath).toString(),
                    "content", content,
                    "saved", saved,
                    "parseStatus", "invalid",
                    "appId", "",
                    "flowId", "",
                    "message", summarize(exception)
            );
        }
    }

    private static Path requireRepoRoot(final Path repoRoot) {
        final Path normalized = Objects.requireNonNull(repoRoot, "repoRoot").toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("repoRoot directory not found: " + normalized);
        }
        return normalized;
    }

    private static Path requireSpecPath(final Path repoRoot, final String specPathValue) {
        final String rawPath = requiredNonBlank(specPathValue, "path");
        final Path specPath = Path.of(rawPath).toAbsolutePath().normalize();
        final Path examplesRoot = repoRoot.resolve("examples").normalize();
        if (!specPath.startsWith(examplesRoot)) {
            throw new IllegalArgumentException("path must stay under repoRoot/examples: " + specPath);
        }
        if (!"railix.app.yaml".equals(specPath.getFileName().toString())) {
            throw new IllegalArgumentException("path must target a committed railix.app.yaml file");
        }
        if (!Files.isRegularFile(specPath)) {
            throw new IllegalArgumentException("authoring spec file not found: " + specPath);
        }
        return specPath;
    }

    private static Path requireEnvelopePath(final Path specPath, final String envelopePathValue) {
        final String rawPath = requiredNonBlank(envelopePathValue, "envelopePath");
        final Path envelopePath = Path.of(rawPath).toAbsolutePath().normalize();
        final Path exampleDirectory = specPath.getParent();
        if (!envelopePath.startsWith(exampleDirectory)) {
            throw new IllegalArgumentException("envelopePath must stay under the same example directory: " + envelopePath);
        }
        if (!Files.isRegularFile(envelopePath)) {
            throw new IllegalArgumentException("envelope example file not found: " + envelopePath);
        }
        final String fileName = envelopePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!(fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".json"))
                || !fileName.contains("envelope")) {
            throw new IllegalArgumentException("envelopePath must target a committed envelope example file");
        }
        return envelopePath;
    }

    private static Path requireCreatableExampleDirectory(final Path repoRoot, final String exampleId) {
        final Path examplesRoot = repoRoot.resolve("examples").normalize();
        final Path exampleDirectory = examplesRoot.resolve(exampleId).normalize();
        if (!exampleDirectory.startsWith(examplesRoot)) {
            throw new IllegalArgumentException("exampleId must stay under repoRoot/examples: " + exampleId);
        }
        if (Files.exists(exampleDirectory)) {
            throw new IllegalArgumentException("exampleId already exists under repoRoot/examples: " + exampleId);
        }
        return exampleDirectory;
    }

    private static String readFile(final Path specPath) {
        try {
            return Files.readString(specPath);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Failed to read authoring spec: " + specPath, exception);
        }
    }

    private static void writeFile(final Path specPath, final String content) {
        final String normalizedContent = requiredNonBlank(content, "content");
        final Path tempFile = specPath.resolveSibling(specPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(specPath.getParent());
            Files.writeString(tempFile, normalizedContent);
            Files.move(
                    tempFile,
                    specPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (final IOException exception) {
            throw new UncheckedIOException("Failed to write authoring spec: " + specPath, exception);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (final IOException ignored) {
                // Best effort cleanup only.
            }
        }
    }

    private static String requiredString(final Map<String, Object> body, final String key) {
        final Object value = body.get(key);
        if (value instanceof String stringValue) {
            return requiredNonBlank(stringValue, key);
        }
        throw new IllegalArgumentException(key + " must be a non-blank string");
    }

    private static String requiredNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String requireExampleId(final String value) {
        final String normalized = requiredNonBlank(value, "exampleId").trim().toLowerCase(Locale.ROOT);
        if (!EXAMPLE_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("exampleId must match [a-z0-9-] without path separators");
        }
        return normalized;
    }

    private static String requireAppId(final String value) {
        final String normalized = requiredNonBlank(value, "appId").trim().toLowerCase(Locale.ROOT);
        if (!APP_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("appId must be a dotted lowercase identifier");
        }
        return normalized;
    }

    private static String requireFlowId(final String value) {
        final String normalized = requiredNonBlank(value, "flowId").trim().toLowerCase(Locale.ROOT);
        if (!FLOW_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("flowId must match [a-z0-9-] without path separators");
        }
        return normalized;
    }

    private static String requireAppName(final String value) {
        final String normalized = requiredNonBlank(value, "appName").trim();
        if (normalized.contains("\n") || normalized.contains("\r")
                || normalized.contains(":") || normalized.contains("\"") || normalized.contains("'")) {
            throw new IllegalArgumentException("appName must stay within the narrow plain-scalar create boundary");
        }
        return normalized;
    }

    private static String createSpecTemplate(final String appId, final String appName, final String flowId) {
        return """
                app:
                  id: %s
                  name: %s
                  version: 0.1.0
                  dependencies:
                    - { id: railix.std.trigger, version: 0.1.0 }
                    - { id: railix.std.data, version: 0.1.0 }
                  flows:
                    - id: %s
                      trigger: manual
                      steps:
                        - id: manual
                          use: railix.std.trigger.ManualTrigger
                          next: build-reply
                        - id: build-reply
                          use: railix.std.data.DataTransform
                          config:
                            mappings:
                              - target: ctx.name
                                expression:
                                  op: default
                                  value: { path: payload.name }
                                  default: { const: World }
                              - target: reply.mode
                                expression: { const: immediate }
                              - target: reply.status
                                expression: { const: ok }
                              - target: reply.payload.message
                                expression:
                                  op: template
                                  template: "Hello ${ctx.name}"
                          next: end
                """.formatted(appId, appName, flowId);
    }

    private static String createEnvelopeTemplate() {
        return """
                envelope:
                  source: creator.manual
                  protocol: manual
                  payload:
                    name: Ada
                  metadata:
                    actor: developer
                  replyChannel:
                    supported: true
                    modes: [immediate]
                """;
    }

    private static Path exportDirectoryFor(final Path envelopePath) {
        return envelopePath.getParent()
                .resolve("creator-export")
                .resolve(fileBaseName(envelopePath));
    }

    private static String fileBaseName(final Path path) {
        final String fileName = path.getFileName().toString();
        final int separatorIndex = fileName.lastIndexOf('.');
        return separatorIndex > 0 ? fileName.substring(0, separatorIndex) : fileName;
    }

    private static Map<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return Map.copyOf(values);
    }

    private static String summarize(final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private record SaveRequest(String path, String content) {
        private SaveRequest {
            path = Objects.requireNonNull(path, "path");
            content = Objects.requireNonNull(content, "content");
        }
    }

    private record ExportRequest(String path, String envelopePath) {
        private ExportRequest {
            path = Objects.requireNonNull(path, "path");
            envelopePath = Objects.requireNonNull(envelopePath, "envelopePath");
        }
    }

    private record CreateRequest(String exampleId, String appId, String appName, String flowId) {
        private CreateRequest {
            exampleId = Objects.requireNonNull(exampleId, "exampleId");
            appId = Objects.requireNonNull(appId, "appId");
            appName = Objects.requireNonNull(appName, "appName");
            flowId = Objects.requireNonNull(flowId, "flowId");
        }
    }
}
