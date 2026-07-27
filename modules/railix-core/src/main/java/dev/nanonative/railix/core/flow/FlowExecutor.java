package dev.nanonative.railix.core.flow;

import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Executes compiler-owned plans without rediscovering or validating graph structure. */
final class FlowExecutor {
    private FlowExecutor() {
    }

    static RunResult run(final CompiledFlow flow, final RailixValue.ObjectValue inputs) {
        if (Thread.currentThread().isInterrupted()) {
            return new RunResult.Cancelled(List.of());
        }
        if (inputs == null) {
            return new RunResult.Rejected(List.of(Diagnostic.atPath(
                    "FLOW_INPUT_OBJECT_REQUIRED",
                    "Flow inputs must be an object.",
                    "inputs"
            )));
        }
        final List<Diagnostic> admissionDiagnostics = validateInputs(flow.inputs(), inputs, false);
        if (!admissionDiagnostics.isEmpty()) {
            return new RunResult.Rejected(admissionDiagnostics);
        }
        return execute(flow, flow.entry(), inputs, Optional.empty());
    }

    static RunResult runStepEvent(
            final CompiledFlow flow,
            final String stepId,
            final RailixValue.ObjectValue inputs
    ) {
        if (Thread.currentThread().isInterrupted()) {
            return new RunResult.Cancelled(List.of());
        }
        if (stepId == null || stepId.isBlank()) {
            return new RunResult.Rejected(List.of(Diagnostic.atPath(
                    "FLOW_STEP_EVENT_REQUIRED",
                    "Step event id must be a non-blank string.",
                    "step"
            )));
        }
        final boolean declared = flow.triggers().stream().anyMatch(trigger ->
                "http".equals(trigger.type())
                        && trigger.config().values().get("step") instanceof RailixValue.StringValue step
                        && step.value().equals(stepId)
        );
        if (!declared) {
            return new RunResult.Rejected(List.of(Diagnostic.atPath(
                    "FLOW_STEP_EVENT_NOT_DECLARED",
                    "Step event is not declared: " + stepId + ".",
                    "step"
            )));
        }
        if (inputs == null) {
            return new RunResult.Rejected(List.of(Diagnostic.atPath(
                    "STEP_INPUT_OBJECT_REQUIRED",
                    "Step inputs must be an object.",
                    "inputs"
            )));
        }
        final Map<String, ValueShape> shapes = new LinkedHashMap<>();
        for (final Map.Entry<String, CompiledFlow.Binding> input
                : flow.nodes().get(stepId).inputBindings().entrySet()) {
            shapes.put(input.getKey(), input.getValue().shape());
        }
        final List<Diagnostic> admissionDiagnostics = validateInputs(shapes, inputs, true);
        if (!admissionDiagnostics.isEmpty()) {
            return new RunResult.Rejected(admissionDiagnostics);
        }
        return execute(
                flow,
                stepId,
                RailixValue.object(Map.of()),
                Optional.of(inputs)
        );
    }

    private static RunResult execute(
            final CompiledFlow flow,
            final String firstStep,
            final RailixValue.ObjectValue flowInputs,
            final Optional<RailixValue.ObjectValue> directInputs
    ) {
        final Map<String, Map<String, RailixValue>> outputsByStep = new LinkedHashMap<>();
        final List<RunResult.StepExecution> executions = new ArrayList<>();
        String stepId = firstStep;
        while (!ControlGraph.END.equals(stepId)) {
            if (Thread.currentThread().isInterrupted()) {
                return new RunResult.Cancelled(executions);
            }
            final CompiledFlow.Node node = flow.nodes().get(stepId);
            final Map<String, RailixValue> stepInputs = new LinkedHashMap<>();
            if (stepId.equals(firstStep) && directInputs.isPresent()) {
                stepInputs.putAll(directInputs.get().values());
            } else {
                for (final Map.Entry<String, CompiledFlow.Binding> binding : node.inputBindings().entrySet()) {
                    final MappingResult mapped = resolve(
                            binding.getValue(),
                            "input " + node.id() + "." + binding.getKey(),
                            flowInputs,
                            outputsByStep
                    );
                    if (mapped instanceof MappingRejected rejected) {
                        return new RunResult.Rejected(List.of(rejected.diagnostic()), executions);
                    }
                    stepInputs.put(binding.getKey(), ((MappedValue) mapped).value());
                }
            }

            final StepResult result;
            try {
                result = node.handler().run(new StepInput(stepInputs, node.config()));
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new RunResult.Cancelled(executions);
            } catch (final RuntimeException exception) {
                return failed(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        stepId,
                        executions
                );
            }
            final Optional<RunFailure> contractFailure = validateStepResult(node, result);
            if (contractFailure.isPresent()) {
                return new RunResult.Failed(contractFailure.get(), executions);
            }
            outputsByStep.put(stepId, result.outputs());
            executions.add(new RunResult.StepExecution(stepId, result.outcome()));
            stepId = node.transitions().get(result.outcome());
        }

        if (Thread.currentThread().isInterrupted()) {
            return new RunResult.Cancelled(executions);
        }
        final Map<String, RailixValue> flowOutputs = new LinkedHashMap<>();
        for (final Map.Entry<String, CompiledFlow.Binding> binding : flow.outputBindings().entrySet()) {
            final MappingResult mapped = resolve(
                    binding.getValue(), "output " + binding.getKey(), flowInputs, outputsByStep
            );
            if (mapped instanceof MappingRejected rejected) {
                return new RunResult.Rejected(List.of(rejected.diagnostic()), executions);
            }
            flowOutputs.put(binding.getKey(), ((MappedValue) mapped).value());
        }
        return new RunResult.Succeeded(RailixValue.object(flowOutputs), executions);
    }

    private static List<Diagnostic> validateInputs(
            final Map<String, ValueShape> shapes,
            final RailixValue.ObjectValue inputs,
            final boolean step
    ) {
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final String code = step ? "STEP_INPUT" : "FLOW_INPUT";
        final String label = step ? "Step input" : "Flow input";
        final String missingLabel = step ? "Required Step input" : "Required flow input";
        for (final String name : shapes.keySet().stream().sorted().toList()) {
            final ValueShape shape = shapes.get(name);
            final RailixValue value = inputs.values().get(name);
            if (value == null) {
                diagnostics.add(Diagnostic.atPath(
                        code + "_REQUIRED",
                        missingLabel + " is missing: " + name,
                        "inputs." + name
                ));
            } else if (!shape.accepts(value)) {
                diagnostics.add(Diagnostic.atPath(
                        code + "_TYPE_MISMATCH",
                        label + " " + name + " requires " + shape
                                + " but received " + ValueShape.shapeOf(value) + ".",
                        "inputs." + name
                ));
            }
        }
        for (final String supplied : inputs.values().keySet().stream().sorted().toList()) {
            if (!shapes.containsKey(supplied)) {
                diagnostics.add(Diagnostic.atPath(
                        code + "_UNKNOWN",
                        "Unknown " + (step ? "Step" : "flow") + " input: " + supplied,
                        "inputs." + supplied
                ));
            }
        }
        return diagnostics;
    }

    private static Optional<RunFailure> validateStepResult(
            final CompiledFlow.Node node,
            final StepResult result
    ) {
        if (result == null) {
            return failure("STEP_RESULT_MISSING", "Step returned no result.", node.id());
        }
        if (result.outcome() == null) {
            return failure("STEP_OUTCOME_MISSING", "Step returned no outcome.", node.id());
        }
        if (!node.outcomes().contains(result.outcome())) {
            return failure(
                    "STEP_OUTCOME_UNDECLARED",
                    "Step returned an undeclared outcome: " + result.outcome(),
                    node.id()
            );
        }
        for (final CompiledFlow.Output output : node.outputs()) {
            final RailixValue value = result.outputs().get(output.name());
            if (value == null) {
                return failure(
                        "STEP_OUTPUT_REQUIRED",
                        "Step did not produce required output: " + output.name(),
                        node.id()
                );
            }
            if (!output.shape().accepts(value)) {
                return failure(
                        "STEP_OUTPUT_TYPE_MISMATCH",
                        "Step output " + output.name() + " requires " + output.shape()
                                + " but produced " + ValueShape.shapeOf(value) + ".",
                        node.id()
                );
            }
        }
        for (final String supplied : result.outputs().keySet().stream().sorted().toList()) {
            if (node.outputs().stream().noneMatch(output -> output.name().equals(supplied))) {
                return failure(
                        "STEP_OUTPUT_UNDECLARED",
                        "Step produced undeclared output: " + supplied,
                        node.id()
                );
            }
        }
        return Optional.empty();
    }

    private static MappingResult resolve(
            final CompiledFlow.Binding binding,
            final String target,
            final RailixValue.ObjectValue flowInputs,
            final Map<String, Map<String, RailixValue>> outputsByStep
    ) {
        final Map<CompiledFlow.Mapping, RailixValue> values = new LinkedHashMap<>();
        for (final CompiledFlow.Mapping mapping : binding.mappings()) {
            final RailixValue source = source(mapping.source(), flowInputs, outputsByStep);
            final MappingResult selected = select(mapping, source);
            if (selected instanceof MappingRejected) {
                return selected;
            }
            values.put(mapping, ((MappedValue) selected).value());
        }
        final List<MappedLeaf> leaves = new ArrayList<>(binding.assemblyMappings().size());
        for (final CompiledFlow.Mapping mapping : binding.assemblyMappings()) {
            leaves.add(new MappedLeaf(mapping.targetPath(), values.get(mapping)));
        }
        final RailixValue assembled = assemble(leaves, 0);
        if (!binding.shape().accepts(assembled)) {
            final int connection = binding.mappings().getFirst().connectionIndex();
            return rejected(
                    "FLOW_MAPPING_TARGET_TYPE_MISMATCH",
                    "Mapped " + target + " requires " + binding.shape()
                            + " but produced " + ValueShape.shapeOf(assembled) + ".",
                    "connections[" + connection + "]"
            );
        }
        return new MappedValue(assembled);
    }

    private static RailixValue source(
            final CompiledFlow.ValueSource source,
            final RailixValue.ObjectValue flowInputs,
            final Map<String, Map<String, RailixValue>> outputsByStep
    ) {
        return switch (source) {
            case CompiledFlow.FlowInput input -> flowInputs.values().get(input.name());
            case CompiledFlow.StepOutput output -> outputsByStep.get(output.stepId()).get(output.port());
        };
    }

    private static MappingResult select(final CompiledFlow.Mapping mapping, final RailixValue source) {
        RailixValue selected = source;
        for (int index = 0; index < mapping.sourcePath().elements().size(); index++) {
            final DraftFlow.Path.Element element = mapping.sourcePath().elements().get(index);
            if (element instanceof DraftFlow.Path.Field field) {
                if (!(selected instanceof RailixValue.ObjectValue object)) {
                    return sourceTypeMismatch(mapping, index, "object", selected);
                }
                if (!object.values().containsKey(field.name())) {
                    return missing(mapping, index);
                }
                selected = object.values().get(field.name());
            } else {
                final DraftFlow.Path.Index arrayIndex = (DraftFlow.Path.Index) element;
                if (!(selected instanceof RailixValue.ArrayValue array)) {
                    return sourceTypeMismatch(mapping, index, "array", selected);
                }
                if (arrayIndex.value() >= array.values().size()) {
                    return missing(mapping, index);
                }
                selected = array.values().get(arrayIndex.value());
            }
        }
        return convert(mapping, selected);
    }

    private static MappingResult missing(final CompiledFlow.Mapping mapping, final int elementIndex) {
        if (!mapping.defaultValue().isEmpty()) {
            return new MappedValue(mapping.defaultValue().getFirst());
        }
        return rejected(
                "FLOW_MAPPING_SOURCE_MISSING",
                "Source path " + mapping.sourcePath().json() + " does not exist.",
                mappingPath(mapping, "sourcePath[" + elementIndex + "]")
        );
    }

    private static MappingResult sourceTypeMismatch(
            final CompiledFlow.Mapping mapping,
            final int elementIndex,
            final String required,
            final RailixValue actual
    ) {
        return rejected(
                "FLOW_MAPPING_SOURCE_TYPE_MISMATCH",
                "Source path " + mapping.sourcePath().json() + " requires an " + required + " at "
                        + mapping.sourcePath().prefix(elementIndex).json() + " but found "
                        + ValueShape.shapeOf(actual) + ".",
                mappingPath(mapping, "sourcePath[" + elementIndex + "]")
        );
    }

    private static MappingResult convert(final CompiledFlow.Mapping mapping, final RailixValue value) {
        return switch (mapping.conversion()) {
            case NONE -> new MappedValue(value);
            case NUMBER_TO_STRING -> numberToString(mapping, value);
            case BOOLEAN_TO_STRING -> value instanceof RailixValue.BooleanValue bool
                    ? new MappedValue(RailixValue.string(Boolean.toString(bool.value())))
                    : conversionTypeMismatch(mapping, value);
            case STRING_TO_NUMBER -> stringToNumber(mapping, value);
            case STRING_TO_BOOLEAN -> stringToBoolean(mapping, value);
        };
    }

    private static MappingResult stringToNumber(
            final CompiledFlow.Mapping mapping,
            final RailixValue value
    ) {
        if (!(value instanceof RailixValue.StringValue string)) {
            return conversionTypeMismatch(mapping, value);
        }
        if (!string.value().equals(string.value().strip())) {
            return conversionInvalid(mapping);
        }
        return normalizedNumber(mapping, string.value(), false);
    }

    private static MappingResult numberToString(
            final CompiledFlow.Mapping mapping,
            final RailixValue value
    ) {
        if (!(value instanceof RailixValue.NumberValue number)) {
            return conversionTypeMismatch(mapping, value);
        }
        return normalizedNumber(mapping, number.value().toString(), true);
    }

    private static MappingResult normalizedNumber(
            final CompiledFlow.Mapping mapping,
            final String encoded,
            final boolean toString
    ) {
        if (encoded.length() > RailixData.MAX_CANONICAL_NUMBER_CHARACTERS) {
            return conversionLimitExceeded(mapping);
        }
        final RailixData.Result normalized = RailixData.normalize(
                RailixData.Format.JSON,
                encoded.getBytes(StandardCharsets.UTF_8)
        );
        if (normalized instanceof RailixData.Normalized number
                && number.value() instanceof RailixValue.NumberValue) {
            return toString
                    ? new MappedValue(RailixValue.string(number.canonicalJson()))
                    : new MappedValue(number.value());
        }
        if (normalized instanceof RailixData.Normalized) {
            return conversionInvalid(mapping);
        }
        final RailixData.Invalid invalid = (RailixData.Invalid) normalized;
        return "DATA_NUMBER_LIMIT_EXCEEDED".equals(invalid.code())
                || "DATA_SOURCE_TOO_LARGE".equals(invalid.code())
                ? conversionLimitExceeded(mapping)
                : conversionInvalid(mapping);
    }

    private static MappingResult stringToBoolean(
            final CompiledFlow.Mapping mapping,
            final RailixValue value
    ) {
        if (!(value instanceof RailixValue.StringValue string)) {
            return conversionTypeMismatch(mapping, value);
        }
        return switch (string.value()) {
            case "true" -> new MappedValue(RailixValue.bool(true));
            case "false" -> new MappedValue(RailixValue.bool(false));
            default -> conversionInvalid(mapping);
        };
    }

    private static MappingResult conversionTypeMismatch(
            final CompiledFlow.Mapping mapping,
            final RailixValue actual
    ) {
        return rejected(
                "FLOW_MAPPING_CONVERSION_TYPE_MISMATCH",
                "Conversion " + mapping.conversion().encoded() + " requires " + mapping.conversion().source()
                        + " but selected " + ValueShape.shapeOf(actual) + ".",
                mappingPath(mapping, "convert")
        );
    }

    private static MappingResult conversionInvalid(final CompiledFlow.Mapping mapping) {
        return rejected(
                "FLOW_MAPPING_CONVERSION_INVALID",
                "Cannot convert " + mapping.conversion().source() + " to " + mapping.conversion().target() + ".",
                mappingPath(mapping, "convert")
        );
    }

    private static MappingResult conversionLimitExceeded(final CompiledFlow.Mapping mapping) {
        return rejected(
                "FLOW_MAPPING_CONVERSION_LIMIT_EXCEEDED",
                "Number conversion exceeds the " + RailixData.MAX_CANONICAL_NUMBER_CHARACTERS
                        + "-character source or canonical limit.",
                mappingPath(mapping, "convert")
        );
    }

    private static MappingRejected rejected(final String code, final String message, final String path) {
        return new MappingRejected(Diagnostic.atPath(code, message, path));
    }

    private static String mappingPath(final CompiledFlow.Mapping mapping, final String field) {
        return "connections[" + mapping.connectionIndex() + "]." + field;
    }

    private static RailixValue assemble(final List<MappedLeaf> leaves, final int depth) {
        if (leaves.size() == 1 && leaves.getFirst().path().elements().size() == depth) {
            return leaves.getFirst().value();
        }
        if (leaves.getFirst().path().elements().get(depth) instanceof DraftFlow.Path.Field) {
            final Map<String, RailixValue> object = new LinkedHashMap<>();
            int start = 0;
            while (start < leaves.size()) {
                final DraftFlow.Path.Field field = (DraftFlow.Path.Field) leaves.get(start)
                        .path().elements().get(depth);
                final int end = groupEnd(leaves, depth, start);
                object.put(field.name(), assemble(leaves.subList(start, end), depth + 1));
                start = end;
            }
            return RailixValue.object(object);
        }
        final List<RailixValue> array = new ArrayList<>();
        int start = 0;
        while (start < leaves.size()) {
            final int end = groupEnd(leaves, depth, start);
            array.add(assemble(leaves.subList(start, end), depth + 1));
            start = end;
        }
        return RailixValue.array(array);
    }

    private static int groupEnd(final List<MappedLeaf> leaves, final int depth, final int start) {
        final DraftFlow.Path.Element element = leaves.get(start).path().elements().get(depth);
        int end = start + 1;
        while (end < leaves.size() && leaves.get(end).path().elements().get(depth).equals(element)) {
            end++;
        }
        return end;
    }

    private sealed interface MappingResult permits MappedValue, MappingRejected {
    }

    private record MappedValue(RailixValue value) implements MappingResult {
    }

    private record MappingRejected(Diagnostic diagnostic) implements MappingResult {
    }

    private record MappedLeaf(DraftFlow.Path path, RailixValue value) {
    }

    private static Optional<RunFailure> failure(
            final String code,
            final String message,
            final String stepId
    ) {
        return Optional.of(new RunFailure(code, message, stepId));
    }

    private static RunResult.Failed failed(
            final String code,
            final String message,
            final String stepId,
            final List<RunResult.StepExecution> executions
    ) {
        return new RunResult.Failed(new RunFailure(code, message, stepId), executions);
    }
}
