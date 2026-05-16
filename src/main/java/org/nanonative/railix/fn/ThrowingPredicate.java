package org.nanonative.railix.fn;

@FunctionalInterface
public interface ThrowingPredicate<T> {
  boolean test(T t) throws Exception;
}

