package thirdparty.production;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Installed third-party fixture exercising ordinary Java ownership and optional managed resources. */
public final class ResourceLifecycleSteps {
    public static final List<Resource> CREATED = new CopyOnWriteArrayList<>();
    public static final AtomicInteger CLOSED = new AtomicInteger();
    public static final AtomicInteger POSTS = new AtomicInteger();
    private static final AtomicInteger UNMANAGED_SINGLETON = new AtomicInteger();
    private static final AssertionError SHARED_FATAL = new AssertionError("Requested fatal shared failure.");

    private ResourceLifecycleSteps() { }

    public static String mode() {
        return System.getProperty("railix.resource.test", "normal");
    }

    public static final class Resource {
        private final int id;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();

        public Resource() {
            id = CREATED.size() + 1;
            CREATED.add(this);
        }

        public int id() {
            if (closed.get()) throw new AssertionError("Resource closed before its owner's POST or PROCESS.");
            return id;
        }

        public int calls() {
            return calls.get();
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) throw new AssertionError("Duplicate cleanup.");
            CLOSED.incrementAndGet();
            if (mode().equals("close-error")) throw new IllegalStateException("Requested cleanup failure.");
            if (mode().equals("close-fatal")) throw new AssertionError("Requested fatal cleanup failure.");
            if (mode().equals("repeated-fatal") || mode().equals("pre-close-fatal")) throw SHARED_FATAL;
        }
    }

    public static final class Handler {
        public static StepResult pre(final StepInput configuration, final StepInput context) {
            if (configuration.option("ownership").equals("shared")) {
                context.resource("client", Resource.class, configuration.string("key"), Resource::new, Resource::close);
            } else {
                context.resource("client", Resource.class, Resource::new, Resource::close);
            }
            if (configuration.string("label").equals("a")) {
                switch (mode()) {
                    case "pre-error" -> throw new IllegalStateException("Requested PRE failure.");
                    case "pre-fatal" -> throw new AssertionError("Requested fatal PRE failure.");
                    case "pre-close-fatal" -> throw SHARED_FATAL;
                    case "duplicate" -> context.resource("client", Resource.class, Resource::new, Resource::close);
                    case "recursive-registration" -> context.resource("second", Resource.class,
                            () -> context.resource("second", Resource.class, Resource::new, Resource::close), Resource::close);
                    case "null-factory" -> context.resource("second", Resource.class, null, Resource::close);
                    case "null-close" -> context.resource("second", Resource.class, Resource::new, null);
                    case "null-result" -> context.resource("second", Resource.class, () -> null, Resource::close);
                    case "factory-error" -> context.resource("second", Resource.class,
                            () -> { throw new IllegalStateException("Factory failed."); }, Resource::close);
                    case "blank-name" -> context.resource(" ", Resource.class, Resource::new, Resource::close);
                    case "null-type" -> context.resource("second", null, Resource::new, Resource::close);
                    case "null-key" -> context.resource("second", Resource.class, null, Resource::new, Resource::close);
                    case "missing" -> context.resource("missing", Resource.class);
                    case "wrong-type" -> context.resource("client", Object.class);
                    case "config-access" -> configuration.resource("client", Resource.class);
                    case "invalid-result" -> { return StepResult.outcome("next").output("value", RailixValue.number(1)); }
                    default -> { }
                }
            }
            return StepResult.outcome(context.primaryOutcome());
        }

        public static StepResult process(final StepInput configuration, final StepInput context) {
            if (mode().equals("late-register")) {
                context.resource("second", Resource.class, Resource::new, Resource::close);
            }
            final Resource resource = context.resource("client", Resource.class);
            resource.calls.incrementAndGet();
            UNMANAGED_SINGLETON.incrementAndGet();
            return StepResult.outcome(context.primaryOutcome()).output("value", RailixValue.number(resource.id()));
        }

        public static StepResult post(final StepInput configuration, final StepInput context) {
            context.resource("client", Resource.class).id();
            POSTS.incrementAndGet();
            if (configuration.string("label").equals("a")) {
                if (mode().equals("post-error")) throw new IllegalStateException("Requested POST failure.");
                if (mode().equals("post-fatal")) throw new AssertionError("Requested fatal POST failure.");
                if (mode().equals("post-register")) {
                    context.resource("second", Resource.class, Resource::new, Resource::close);
                }
            }
            return StepResult.outcome(context.primaryOutcome());
        }
    }
}
