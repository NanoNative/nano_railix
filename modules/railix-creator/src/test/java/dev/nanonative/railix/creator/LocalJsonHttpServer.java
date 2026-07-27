package dev.nanonative.railix.creator;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LocalJsonHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    LocalJsonHttpServer(final String response) throws IOException {
        final byte[] body = response.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    String url(final String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
