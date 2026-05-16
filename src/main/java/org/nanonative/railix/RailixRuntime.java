package org.nanonative.railix;

import berlin.yuna.typemap.model.ConcurrentTypeMap;
import berlin.yuna.typemap.model.TypeMap;
import org.nanonative.railix.config.Config;
import org.nanonative.railix.metrics.Metrics;
import org.nanonative.railix.name.Names;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Railix singleton runtime.
 * <p>
 * This is the global place for "app-level" state that must outlive a single {@link Rail} execution:
 * <ul>
 *   <li>Compiled configuration (see {@link #config()})</li>
 *   <li>Actor registry (see {@link #actors()})</li>
 *   <li>Metrics registry (see {@link #metrics()})</li>
 *   <li>Default executor (see {@link #executor()})</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Access is via {@link #global()} only.</li>
 *   <li>Registration is last-write-wins for duplicate actor names and/or types.</li>
 *   <li>Config updates return a diff map; removals are represented as {@code key -> null} tombstones.</li>
 * </ul>
 */
public final class RailixRuntime {

  private static final RailixRuntime GLOBAL = new RailixRuntime();

  private final ConcurrentTypeMap config;
  private final Executor executor;
  private final Metrics metrics;
  private final ConcurrentHashMap<String, Object> actorsByName;
  private final ConcurrentHashMap<Class<?>, Object> actorsByType;
  private final Actors actors;

  private RailixRuntime() {
    this.config = new ConcurrentTypeMap(Config.load());
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.metrics = new Metrics();
    this.actorsByName = new ConcurrentHashMap<>();
    this.actorsByType = new ConcurrentHashMap<>();
    this.actors = new Actors();
  }

  public static RailixRuntime global() {
    return GLOBAL;
  }

  public ConcurrentTypeMap config() {
    return config;
  }

  public Executor executor() {
    return executor;
  }

  public Metrics metrics() {
    return metrics;
  }

  public Actors actors() {
    return actors;
  }

  Object actorByName(final String name) {
    return name == null ? null : actorsByName.get(name);
  }

  Object actorByType(final Class<?> type) {
    return type == null ? null : actorsByType.get(type);
  }

  void registerActor(final String name, final Class<?> type, final Object actor) {
    if (actor == null) {
      return;
    }
    if (name != null && !name.isBlank()) {
      actorsByName.put(name, actor);
    }
    if (type != null) {
      actorsByType.put(type, actor);
    }
  }

  /**
   * Updates the global config and returns a diff of changed keys to new values.
   * <p>
   * Rules:
   * <ul>
   *   <li>Keys are normalized via {@link Names#sanitize(String, String)} (underscore, lowercase).</li>
   *   <li>If a value is {@code null}, the key is removed and the diff contains a tombstone {@code key -> null}.</li>
   *   <li>If a value is unchanged (equals old), the key is not included in the diff.</li>
   * </ul>
   */
  public Map<Object, Object> updateConfig(final Map<?, ?> changes) {
    if (changes == null || changes.isEmpty())
      return Map.of();

    final TypeMap diff = new TypeMap();
    for (Map.Entry<?, ?> e : changes.entrySet()) {
      final Object key = (e.getKey() instanceof final String strKey)? Names.sanitize(strKey) : e.getKey();
      if (key == null || (key instanceof final String strKey && strKey.isBlank()))
        continue;

      final Object newValue = e.getValue();
      if (newValue == null) {
          config.remove(key);
          diff.put(key, null);
      } else if (!Objects.equals(config.get(key), newValue)) {
        config.put(key, newValue);
        diff.put(key, newValue);
      }
    }
    return diff;
  }
}
