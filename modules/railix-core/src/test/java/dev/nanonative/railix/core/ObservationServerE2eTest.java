package dev.nanonative.railix.core;

import dev.nanonative.railix.core.runtime.ObservationServer;
import dev.nanonative.railix.core.runtime.RuntimeMetrics;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class ObservationServerE2eTest {
    @TempDir
    Path home;

    @Test
    void heldStatusReadLeavesApplicationIdentityAvailable() throws Exception {
        final var hold = new AtomicBoolean();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var details = Map.<String, RailixValue>of("state", RailixValue.string("ready"));
        final Path jar = observationArtifact();
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var server = open(loader.loadClass(ObservedArtifact.class.getName()), () -> {
                 if (hold.get()) {
                     entered.countDown();
                     try {
                         release.await(10, SECONDS);
                     } catch (final InterruptedException interrupted) {
                         Thread.currentThread().interrupt();
                     }
                 }
                 return details;
             });
             var client = HttpClient.newHttpClient()) {
            final var connection = ObservationServer.connections(home.resolve(".railix")).getFirst();
            final var status = request(connection, "/v1/status");
            hold.set(true);
            final var first = client.sendAsync(status, HttpResponse.BodyHandlers.discarding());
            try {
                assertThat(entered.await(5, SECONDS)).isTrue();
                assertThatThrownBy(() -> client.send(status, HttpResponse.BodyHandlers.discarding()))
                        .isInstanceOf(IOException.class)
                        .isNotInstanceOf(HttpTimeoutException.class);
                final var identity = client.send(request(connection, "/v1/application"),
                        HttpResponse.BodyHandlers.discarding());
                assertThat(identity.statusCode()).isEqualTo(200);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, SECONDS).statusCode()).isEqualTo(200);
        }
    }

    private ObservationServer open(final Class<?> artifact, final Supplier<Map<String, RailixValue>> details) {
        final String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            final var metrics = new RuntimeMetrics("observation-test", new String[0], new String[0]);
            return ObservationServer.open(artifact, metrics, details).orElseThrow();
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    private Path observationArtifact() throws IOException {
        final Path jar = home.resolve("application.jar");
        final var manifest = new Manifest();
        final var attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Railix-Project-Id", "sha256-" + "a".repeat(64));
        final String entry = ObservedArtifact.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var bytes = ObservedArtifact.class.getResourceAsStream("/" + entry)) {
            output.putNextEntry(new JarEntry(entry));
            bytes.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static HttpRequest request(final ObservationServer.Connection connection, final String path) {
        return HttpRequest.newBuilder(connection.origin().resolve(path))
                .header("Authorization", "Bearer " + connection.token())
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
    }

    public static final class ObservedArtifact {
    }
}
