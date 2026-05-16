package org.nanonative.railix;

import org.nanonative.railix.name.Names;

import java.util.Optional;

/**
 * Global actor access facade.
 * <p>
 * Actors are global objects stored in the {@link RailixRuntime} singleton.
 * <p>
 * Design rules:
 * <ul>
 *   <li>Actors are <b>global</b> (not stored in payload or ctx).</li>
 *   <li>Missing actors return {@code null} by default.</li>
 *   <li>Defensive variants exist via {@code *Opt()} methods.</li>
 *   <li>Registration is <b>last-write-wins</b> for duplicate names and/or types.</li>
 *   <li>Typed lookups are cast-safe: if the actor exists but cannot be cast, {@code null} is returned.</li>
 * </ul>
 */
public class Actors {

  protected Actors() {
  }

  public Actors register(final String name, final Object actor) {
    if (actor == null) {
      return this;
    }
    RailixRuntime.global().registerActor(normalize(name), actor.getClass(), actor);
    return this;
  }

  public <T> Actors register(final String name, final Class<T> type, final T actor) {
    if (type == null || actor == null) {
      return this;
    }
    RailixRuntime.global().registerActor(normalize(name), type, actor);
    return this;
  }

  public <T> Actors register(final Class<T> type, final T actor) {
    if (type == null || actor == null) {
      return this;
    }
    RailixRuntime.global().registerActor(normalize(Names.methodKey(type.getSimpleName())), type, actor);
    return this;
  }

  public Object get(final String name) {
    return RailixRuntime.global().actorByName(normalize(name));
  }

  public Optional<Object> getOpt(final String name) {
    return Optional.ofNullable(get(name));
  }

  public <T> T get(final String name, final Class<T> type) {
    return castOrNull(get(name), type);
  }

  public <T> Optional<T> getOpt(final String name, final Class<T> type) {
    return Optional.ofNullable(get(name, type));
  }

  public <T> T get(final Class<T> type) {
    return castOrNull(RailixRuntime.global().actorByType(type), type);
  }

  public <T> Optional<T> getOpt(final Class<T> type) {
    return Optional.ofNullable(get(type));
  }

  private static String normalize(final String raw) {
    final String s = Names.sanitize(raw, "");
    return s.isBlank() ? null : s;
  }

  private static <T> T castOrNull(final Object actor, final Class<T> type) {
    if (actor == null || type == null) {
      return null;
    }
    return type.isInstance(actor) ? type.cast(actor) : null;
  }
}
