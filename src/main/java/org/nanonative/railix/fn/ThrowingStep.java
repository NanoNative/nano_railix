package org.nanonative.railix.fn;

@FunctionalInterface
public interface ThrowingStep<C> {
  void run(C ctx) throws Exception;
}

