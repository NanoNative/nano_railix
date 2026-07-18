package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Selector;

import java.util.List;

public interface ContextDoc {
    RailixValue get(RailixPath path);
    List<RailixValue> select(Selector selector);
    default List<RailixPath> selectedPaths(final Selector selector) {
        throw new IllegalStateException("Selector path selection is not available for this context");
    }
    ContextDoc apply(Patch patch);
    ContextDoc applyAll(List<Patch> patches);
    ContextDiff diff(ContextDoc other);

    record ContextDiff(List<RailixPath> changedPaths) {
        public ContextDiff {
            changedPaths = List.copyOf(changedPaths);
        }
    }
}
