package org.nanonative.railix;

import berlin.yuna.typemap.model.ConcurrentTypeMap;
import berlin.yuna.typemap.model.TypeMapI;
import org.nanonative.railix.metrics.Metrics;

import java.util.concurrent.Executor;

public final class RailDef {
    private final Rail blueprint;

    RailDef(final Rail blueprint) {
        this.blueprint = blueprint;
    }

    Rail blueprint() {
        return blueprint;
    }

    public Object source() {
        return blueprint.source();
    }

    public ConcurrentTypeMap config() {
        return blueprint.config();
    }

    public TypeMapI<?> railConfig() {
        return blueprint.railConfig();
    }

    public Executor executor() {
        return blueprint.executor();
    }

    public Metrics metrics() {
        return blueprint.metrics();
    }

    public Actors actors() {
        return blueprint.actors();
    }

    public boolean isConcurrent() {
        return blueprint.isConcurrent();
    }

    public Result fire() {
        return blueprint.fire();
    }

    public Result fire(final Object payload) {
        return blueprint.fire(payload);
    }

    public Result fire(final Object payload, final Object ctx) {
        return blueprint.fire(payload, ctx);
    }
}
