package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

final class ActorsTest {

    @Test
    void register_withSameNameAndType_shouldUseLastWriteAndKeepCastSafety() {
        class ActorA {
        }
        class ActorB {
        }

        final Actors actors = RailixRuntime.global().actors();
        final String name = "railix_test_actor_last_write_wins";

        final ActorA first = new ActorA();
        final ActorA second = new ActorA();

        actors.register(name, ActorA.class, first);
        assertThat(actors.get(name, ActorA.class)).isSameAs(first);
        assertThat(actors.get(ActorA.class)).isSameAs(first);

        actors.register(name, ActorA.class, second);
        assertThat(actors.get(name, ActorA.class)).isSameAs(second);
        assertThat(actors.get(ActorA.class)).isSameAs(second);

        assertThat(actors.get(name, ActorB.class)).isNull();
        assertThat(actors.getOpt(name, ActorB.class)).isEmpty();
        assertThat(actors.getOpt("missing_actor")).isEmpty();
    }

    @Test
    void register_withUntypedActor_shouldNormalizeNameAndExposeRuntimeType() {
        final class HTTPActor {
        }

        final Actors actors = RailixRuntime.global().actors();
        final HTTPActor actor = new HTTPActor();

        actors.register("HTTP Actor", actor);

        assertThat(actors.get("http_actor")).isSameAs(actor);
        assertThat(actors.get("HTTP Actor", HTTPActor.class)).isSameAs(actor);
        assertThat(actors.get(HTTPActor.class)).isSameAs(actor);
    }

    @Test
    void register_withTypeOnly_shouldUseSanitizedSimpleName() {
        final class CustomScheduler {
        }

        final Actors actors = RailixRuntime.global().actors();
        final CustomScheduler actor = new CustomScheduler();

        actors.register(CustomScheduler.class, actor);

        assertThat(actors.get("custom_scheduler", CustomScheduler.class)).isSameAs(actor);
        assertThat(actors.getOpt(CustomScheduler.class)).isEqualTo(Optional.of(actor));
    }

    @Test
    void register_withBlankName_shouldStillExposeActorByTypeOnly() {
        final class BlankNamedActor {
        }

        final Actors actors = RailixRuntime.global().actors();
        final BlankNamedActor actor = new BlankNamedActor();

        actors.register("   ", actor);

        assertThat(actors.get("blank_named_actor")).isNull();
        assertThat(actors.get(BlankNamedActor.class)).isSameAs(actor);
    }

    @Test
    void register_withBlankTypedName_shouldStillExposeActorByExplicitTypeOnly() {
        final class TypedBlankActor {
        }

        final Actors actors = RailixRuntime.global().actors();
        final TypedBlankActor actor = new TypedBlankActor();

        actors.register("   ", TypedBlankActor.class, actor);

        assertThat(actors.get("typed_blank_actor")).isNull();
        assertThat(actors.get(TypedBlankActor.class)).isSameAs(actor);
    }

    @Test
    void register_withNullUntypedActor_shouldSkipPredictably() {
        final Actors actors = RailixRuntime.global().actors();

        assertThat(actors.register("ignored", (Object) null)).isSameAs(actors);
        assertThat(actors.get("ignored")).isNull();
        assertThat(actors.get("___")).isNull();
        assertThat(actors.get("   ", String.class)).isNull();
        assertThat(actors.get("missing", String.class)).isNull();
    }

    @Test
    void get_withPresentActorAndNullType_shouldReturnNullPredictably() {
        final class PresentActor {
        }

        final Actors actors = RailixRuntime.global().actors();
        final PresentActor actor = new PresentActor();
        final Class<PresentActor> nullType = null;

        actors.register("present_actor", actor);

        assertThat(actors.get("present_actor", nullType)).isNull();
        assertThat(actors.getOpt("present_actor", nullType)).isEmpty();
    }

    @Test
    void register_withNullOrBlankInputs_shouldSkipAndMissingLookupsStayEmpty() {
        final Actors actors = RailixRuntime.global().actors();
        final Class<Object> nullType = null;

        assertThat(actors.register((String) null, new Object())).isSameAs(actors);
        assertThat(actors.register("   ", new Object())).isSameAs(actors);
        assertThat(actors.register((String) null, Object.class, new Object())).isSameAs(actors);
        assertThat(actors.register("x", nullType, new Object())).isSameAs(actors);
        assertThat(actors.register("x", String.class, null)).isSameAs(actors);
        assertThat(actors.register(nullType, new Object())).isSameAs(actors);
        assertThat(actors.register(Object.class, null)).isSameAs(actors);

        assertThat(actors.get((String) null)).isNull();
        assertThat(actors.getOpt((String) null)).isEmpty();
        assertThat(actors.get("missing", nullType)).isNull();
        assertThat(actors.getOpt("missing", nullType)).isEmpty();
        assertThat(actors.get(nullType)).isNull();
        assertThat(actors.getOpt(nullType)).isEmpty();
    }
}
