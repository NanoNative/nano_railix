package dev.nanonative.railix.core;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

final class ValueRefinementContractE2eTest {
    @ParameterizedTest(name = "canonical {index}: {0}")
    @MethodSource("canonicalValues")
    void everyCanonicalRailixShapeIsAccepted(final RailixValue value) {
        assertThat(ValueRefinement.canonical().rejection(value)).isEmpty();
    }

    @Test
    void noRefinementDoesNotTraverseAnInvalidDescendant() {
        final RailixValue value = RailixValue.array(List.of(RailixValue.number(oversizedNumber())));

        assertThat(ValueRefinement.none().rejection(value)).isEmpty();
    }

    @Test
    void noRefinementDoesNotTraverseAnUnpairedSurrogate() {
        final RailixValue value = RailixValue.array(List.of(RailixValue.string("\uD800")));

        assertThat(ValueRefinement.none().rejection(value)).isEmpty();
    }

    @Test
    void noRefinementDoesNotTraverseExcessiveContainerDepth() {
        assertThat(ValueRefinement.none().rejection(nestedArrays(65))).isEmpty();
    }

    @Test
    void wideArrayValidationKeepsOnlyDepthBoundedTraversalState() {
        final RailixValue value = RailixValue.array(java.util.Collections.nCopies(
                100_000,
                RailixValue.nullValue()
        ));

        assertThat(ValueRefinement.canonical().withMaxDepth(1).rejection(value)).isEmpty();
    }

    @Test
    void wideObjectValidationKeepsOnlyDepthBoundedTraversalState() {
        final Map<String, RailixValue> fields = new LinkedHashMap<>();
        for (int index = 0; index < 100_000; index++) {
            fields.put("field-" + index, RailixValue.nullValue());
        }

        assertThat(ValueRefinement.canonical().withMaxDepth(1).rejection(RailixValue.object(fields)))
                .isEmpty();
    }

    @Test
    void canonicalRefinementRejectsANestedNumberOutsideTheDomain() {
        final RailixValue value = RailixValue.array(List.of(RailixValue.number(oversizedNumber())));

        assertThat(ValueRefinement.canonical().rejection(value))
                .contains("Value contains a number outside the canonical 1024-character domain.");
    }

    @Test
    void canonicalRefinementRejectsANestedUnpairedSurrogate() {
        final RailixValue value = RailixValue.array(List.of(RailixValue.string("\uD800")));

        assertThat(ValueRefinement.canonical().rejection(value))
                .contains("Value contains an unpaired Unicode surrogate.");
    }

    @Test
    void canonicalRefinementRejectsAnUnpairedSurrogateInAnObjectKey() {
        final RailixValue value = RailixValue.object(Map.of("\uD800", RailixValue.nullValue()));

        assertThat(ValueRefinement.canonical().rejection(value))
                .contains("Value contains an unpaired Unicode surrogate.");
    }

    @Test
    void explicitMaximumDepthAcceptsItsExactBoundary() {
        assertThat(ValueRefinement.canonical().withMaxDepth(2).rejection(nestedArrays(2))).isEmpty();
    }

    @Test
    void explicitMaximumDepthRejectsTheNextContainer() {
        assertThat(ValueRefinement.canonical().withMaxDepth(2).rejection(nestedArrays(3)))
                .contains("Value exceeds maximum container depth 2.");
    }

    @Test
    void canonicalDepthRejectsAProgrammaticValueBeyondTheGlobalDomain() {
        assertThat(ValueRefinement.canonical().rejection(nestedArrays(RailixData.DEFAULT_MAX_DEPTH + 1)))
                .contains("Value exceeds maximum container depth 64.");
    }

    @Test
    void canonicalJsonByteLimitAcceptsItsExactUtf8Boundary() {
        assertThat(ValueRefinement.canonical().withMaxJsonBytes(4).rejection(RailixValue.string("é")))
                .isEmpty();
    }

    @Test
    void canonicalJsonByteLimitRejectsTheNextLowerBoundary() {
        assertThat(ValueRefinement.canonical().withMaxJsonBytes(3).rejection(RailixValue.string("é")))
                .contains("Canonical JSON exceeds 3 bytes.");
    }

    @ParameterizedTest(name = "exact canonical JSON bytes {index}: {0}")
    @MethodSource("canonicalJsonValues")
    void canonicalJsonByteMeasurementAcceptsTheExactWriterSize(final RailixValue value) {
        final int bytes = RailixJson.write(value).getBytes(StandardCharsets.UTF_8).length;

        assertThat(ValueRefinement.canonical().withMaxJsonBytes(bytes).rejection(value)).isEmpty();
    }

    @ParameterizedTest(name = "one fewer canonical JSON byte {index}: {0}")
    @MethodSource("canonicalJsonValuesAboveOneByte")
    void canonicalJsonByteMeasurementRejectsOneFewerThanTheWriterSize(final RailixValue value) {
        final int bytes = RailixJson.write(value).getBytes(StandardCharsets.UTF_8).length;

        assertThat(ValueRefinement.canonical().withMaxJsonBytes(bytes - 1).rejection(value))
                .contains("Canonical JSON exceeds " + (bytes - 1) + " bytes.");
    }

    @Test
    void refinementRejectsJavaNullAtItsPublicBoundary() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ValueRefinement.canonical().rejection(null))
                .withMessage("Value candidate cannot be Java null.");
    }

    @Test
    void objectTraversalUsesStableRejectionPrecedence() {
        final Map<String, RailixValue> first = new LinkedHashMap<>();
        first.put("z", RailixValue.string("\uD800"));
        first.put("a", RailixValue.number(oversizedNumber()));
        final Map<String, RailixValue> second = new LinkedHashMap<>();
        second.put("a", RailixValue.number(oversizedNumber()));
        second.put("z", RailixValue.string("\uD800"));

        assertThat(List.of(
                ValueRefinement.canonical().rejection(RailixValue.object(first)),
                ValueRefinement.canonical().rejection(RailixValue.object(second))
        )).allSatisfy(rejection -> assertThat(rejection)
                .contains("Value contains a number outside the canonical 1024-character domain."));
    }

    private static Stream<RailixValue> canonicalValues() {
        return Stream.of(
                RailixValue.nullValue(),
                RailixValue.bool(true),
                RailixValue.number(new BigDecimal("1.20")),
                RailixValue.string("Railix"),
                RailixValue.array(List.of(RailixValue.number(1))),
                RailixValue.object(Map.of("value", RailixValue.bool(false)))
        );
    }

    private static Stream<RailixValue> canonicalJsonValues() {
        return Stream.of(
                RailixValue.nullValue(),
                RailixValue.bool(true),
                RailixValue.bool(false),
                RailixValue.number(new BigDecimal("-1.2300")),
                RailixValue.number(new BigDecimal("-0.0")),
                RailixValue.string("Railix"),
                RailixValue.string("\""),
                RailixValue.string("\\"),
                RailixValue.string("\b"),
                RailixValue.string("\f"),
                RailixValue.string("\n"),
                RailixValue.string("\r"),
                RailixValue.string("\t"),
                RailixValue.string("\u0001"),
                RailixValue.string("é"),
                RailixValue.string("😀"),
                RailixValue.array(List.of(RailixValue.nullValue(), RailixValue.bool(true))),
                RailixValue.object(Map.of("é", RailixValue.number(1)))
        );
    }

    private static Stream<RailixValue> canonicalJsonValuesAboveOneByte() {
        return canonicalJsonValues().filter(value ->
                RailixJson.write(value).getBytes(StandardCharsets.UTF_8).length > 1
        );
    }

    private static BigDecimal oversizedNumber() {
        return BigDecimal.TEN.pow(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS);
    }

    private static RailixValue nestedArrays(final int depth) {
        RailixValue value = RailixValue.nullValue();
        for (int index = 0; index < depth; index++) {
            value = RailixValue.array(List.of(value));
        }
        return value;
    }
}
