package org.nanonative.railix.fn;

@FunctionalInterface
public interface ThrowingFunction<T, R> {
  R apply(T t) throws Exception;
}

