package org.nanonative.railix.fn;

@FunctionalInterface
public interface Step<C> {
  void accept(C ctx);
}

