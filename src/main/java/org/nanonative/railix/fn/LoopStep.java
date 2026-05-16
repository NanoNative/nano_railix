package org.nanonative.railix.fn;

@FunctionalInterface
public interface LoopStep<C> {
  void accept(C ctx, int index, Object key, Object value);
}
