package thirdparty.production;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real bundle-owned filesystem Step used by production concurrency acceptance tests. */
public final class ProductionRuntimeConcurrencySteps {
    private ProductionRuntimeConcurrencySteps() {
    }

    /** Creates one file per invocation and returns that invocation's identifier. */
    public static final class CreateFile implements StepHandler {
        /** Creates one stateless handler. */
        public CreateFile() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final String id = input.string("id");
            try {
                Files.writeString(
                        Path.of(input.string("file")),
                        id,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
                return StepResult.outcome(input.primaryOutcome())
                        .write("target", RailixValue.string(id));
            } catch (final IOException exception) {
                throw new IllegalStateException("Production concurrency file creation failed.", exception);
            }
        }
    }

    /** Holds configured invocations inside the real Step boundary until the probe releases them. */
    public static final class Barrier implements StepHandler {
        private static final AtomicReference<CountDownLatch> ENTERED =
                new AtomicReference<>(new CountDownLatch(0));
        private static final AtomicReference<CountDownLatch> RELEASE =
                new AtomicReference<>(new CountDownLatch(0));

        /** Creates one stateless handler. */
        public Barrier() {
        }

        /** Arms the next {@code calls} invocations and returns that count. */
        public static int arm(final int calls) {
            if (calls < 1) {
                throw new IllegalArgumentException("Barrier calls must be positive.");
            }
            RELEASE.set(new CountDownLatch(1));
            ENTERED.set(new CountDownLatch(calls));
            return calls;
        }

        /** Waits for every armed invocation to enter the handler. */
        public static boolean awaitEntered(final long timeoutMillis) throws InterruptedException {
            return ENTERED.get().await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /** Releases every entered invocation. */
        public static boolean release() {
            RELEASE.get().countDown();
            return true;
        }

        @Override
        public StepResult run(final StepInput input) throws InterruptedException {
            final CountDownLatch entered = ENTERED.get();
            if (entered.getCount() > 0) {
                entered.countDown();
                RELEASE.get().await();
            }
            return StepResult.outcome(input.primaryOutcome());
        }
    }
}
