package dev.nanonative.railix.kernel.model;

import java.util.Map;

public record OperatorContract(
        String id,
        String version,
        String displayName,
        String category,
        Map<String, Input> inputs,
        Map<String, Config> config,
        Map<String, Output> outputs,
        Map<String, RailixValue> ui
) {
    public OperatorContract {
        inputs = Map.copyOf(inputs);
        config = Map.copyOf(config);
        outputs = Map.copyOf(outputs);
        ui = Map.copyOf(ui);
    }

    public record Input(String type, boolean required, boolean dynamic, int min) {}

    public record Config(String type, boolean required, RailixValue defaultValue) {}

    public record Output(String type) {}
}
