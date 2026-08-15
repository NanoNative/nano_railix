package dev.nanonative.railix.core;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RailixValueNullContractTest {
    @Test
    void javaNullCannotBecomeARailixNumber() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RailixValue.number((BigDecimal) null))
                .withMessage("Railix number cannot be Java null.");
    }

    @Test
    void javaNullCannotBecomeARailixString() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RailixValue.string(null))
                .withMessage("Railix string cannot be Java null.");
    }

    @Test
    void aRailixArrayRequiresAJavaList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RailixValue.array(null))
                .withMessage("Railix array values cannot be Java null.");
    }

    @Test
    void aRailixArrayCannotContainJavaNull() {
        final List<RailixValue> values = new ArrayList<>();
        values.add(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RailixValue.array(values))
                .withMessage("Railix array value at index 0 cannot be Java null.");
    }

    @Test
    void aRailixObjectRequiresAJavaMap() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RailixValue.object(null))
                .withMessage("Railix object values cannot be Java null.");
    }

    @Test
    void aRailixObjectCannotContainAJavaNullKey() {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        values.put(null, RailixValue.string("value"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RailixValue.object(values))
                .withMessage("Railix object field name cannot be Java null.");
    }

    @Test
    void aRailixObjectCannotContainAJavaNullValue() {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        values.put("field", null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RailixValue.object(values))
                .withMessage("Railix object field 'field' cannot be Java null.");
    }

    @Test
    void jsonWriterRejectsJavaNullExplicitly() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RailixJson.write(null))
                .withMessage("Railix JSON value cannot be Java null.");
    }
}
