package org.nanonative.railix.apt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Railix} interface method as an actor accessor (not a ctx/meta key).
 * <p>
 * The method must be no-arg and return the same type as {@link #value()}.
 *
 * <h2>Key rules</h2>
 * <ul>
 *   <li>If {@link #name()} is empty, the key defaults to the method name (sanitized).</li>
 *   <li>If {@link #name()} is provided, it is sanitized and used as the key.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RailActor {
  Class<?> value();

  String name() default "";
}
