package org.nanonative.railix.fn;

@FunctionalInterface
public interface RunStep<C> {
  boolean run(C ctx) throws Exception;

  static <C> RunStep<C> step(final Step<C> step) {
    return ctx -> {
      if (step != null) {
        step.accept(ctx);
      }
      return true;
    };
  }
}

