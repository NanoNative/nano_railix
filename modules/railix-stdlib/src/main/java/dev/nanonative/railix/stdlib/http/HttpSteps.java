package dev.nanonative.railix.stdlib.http;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded outbound HTTP Steps backed only by the Java HTTP client. */
public final class HttpSteps {
    private static final int MAX_URL_BYTES = 8_192;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_HEADER_BYTES = 16_384;
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade"
    );

    private HttpSteps() {
    }

    /** Returns the bounded HTTP GET Step. */
    public static StepDefinition get() {
        return definition("http.get", "GET", false);
    }

    /** Returns the bounded canonical-JSON HTTP POST Step. */
    public static StepDefinition post() {
        return definition("http.post", "POST", true);
    }

    /** Returns the bounded HTTP DELETE Step. */
    public static StepDefinition delete() {
        return definition("http.delete", "DELETE", false);
    }

    private static StepDefinition definition(
            final String id,
            final String method,
            final boolean hasBody
    ) {
        final StepDefinition.Builder definition = StepDefinition.named(id, "1.0.0")
                .config(
                        "format",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.DATA_FORMAT,
                        RailixValue.string("json")
                )
                .config(
                        "timeoutMillis",
                        ValueShape.NUMBER,
                        StepDefinition.ConfigFormat.TIMEOUT_MILLIS,
                        RailixValue.number(30_000)
                )
                .input("url", ValueShape.string())
                .input("headers", ValueShape.OBJECT);
        if (hasBody) {
            definition.input("body", ValueShape.ANY);
        }
        return definition
                .output("status", ValueShape.NUMBER)
                .output("headers", ValueShape.OBJECT)
                .output("body", ValueShape.ANY)
                .outcome("success")
                .outcome("redirect")
                .outcome("client-error")
                .outcome("server-error")
                .outcome("other")
                .outcome("rejected")
                .run(input -> request(input, method, hasBody));
    }

    private static StepResult request(
            final StepInput input,
            final String method,
            final boolean hasBody
    ) throws InterruptedException {
        final Optional<URI> uri = uri(input.string("url"));
        final Optional<Map<String, String>> headers = requestHeaders(input.value("headers"));
        if (uri.isEmpty() || headers.isEmpty()) {
            return rejected();
        }

        final Optional<byte[]> body = hasBody
                ? requestBody(input.value("body"))
                : Optional.of(new byte[0]);
        if (body.isEmpty()) {
            return rejected();
        }

        final long timeoutMillis = ((RailixValue.NumberValue) input.configValue("timeoutMillis"))
                .value()
                .longValueExact();
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        final HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) uri.get().toURL().openConnection();
            connection.setConnectTimeout((int) timeoutMillis);
            connection.setReadTimeout((int) timeoutMillis);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            for (final Map.Entry<String, String> header : headers.get().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (hasBody && !containsHeader(headers.get(), "content-type")) {
                connection.setRequestProperty("Content-Type", "application/json");
            }
        } catch (final IOException | IllegalArgumentException exception) {
            return rejected();
        }

        try {
            checkInterrupted();
            if (hasBody) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.get().length);
                try (var output = connection.getOutputStream()) {
                    output.write(body.get());
                }
                checkInterrupted();
            }
            connection.setReadTimeout(remainingMillis(deadline));
            final int status = connection.getResponseCode();
            final Optional<RailixValue.ObjectValue> responseHeaders =
                    responseHeaders(connection);
            if (responseHeaders.isEmpty()) {
                return rejected(status);
            }
            final byte[] source;
            try {
                source = responseBody(connection, status, deadline);
            } catch (final IOException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                return rejected(status, responseHeaders.get());
            }
            if (source.length > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
                return rejected(status, responseHeaders.get());
            }
            final Optional<RailixValue> responseBody;
            if (source.length == 0) {
                responseBody = Optional.of(RailixValue.nullValue());
            } else {
                final RailixData.Format format = RailixData.Format.valueOf(
                        input.configString("format").toUpperCase(Locale.ROOT)
                );
                responseBody = switch (RailixData.normalize(format, source)) {
                    case RailixData.Normalized normalized -> Optional.of(normalized.value());
                    case RailixData.Invalid ignored -> Optional.empty();
                };
                if (responseBody.isEmpty()) {
                    return rejected(status, responseHeaders.get());
                }
            }
            checkInterrupted();
            return result(
                    outcome(status),
                    status,
                    responseHeaders.get(),
                    responseBody.get()
            );
        } catch (final IOException | IllegalArgumentException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            return rejected();
        } finally {
            connection.disconnect();
        }
    }

    private static Optional<URI> uri(final String source) {
        if (source.isEmpty()
                || utf8Length(source, MAX_URL_BYTES) > MAX_URL_BYTES) {
            return Optional.empty();
        }
        try {
            final URI uri = URI.create(source);
            final String scheme = uri.getScheme();
            final int port = uri.getPort();
            if (!uri.isAbsolute()
                    || scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || port == 0
                    || port > 65_535) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Map<String, String>> requestHeaders(final RailixValue value) {
        if (!(value instanceof RailixValue.ObjectValue object)
                || object.values().size() > MAX_HEADERS) {
            return Optional.empty();
        }
        final Map<String, String> headers = new LinkedHashMap<>();
        final Set<String> names = new HashSet<>();
        int bytes = 0;
        for (final Map.Entry<String, RailixValue> header : object.values().entrySet()) {
            if (!(header.getValue() instanceof RailixValue.StringValue string)) {
                return Optional.empty();
            }
            final String lowerName = header.getKey().toLowerCase(Locale.ROOT);
            if (!validHeaderName(header.getKey())
                    || !validHeaderValue(string.value())
                    || RESTRICTED_HEADERS.contains(lowerName)
                    || !names.add(lowerName)) {
                return Optional.empty();
            }
            final int nameBytes = utf8Length(header.getKey(), MAX_HEADER_BYTES - bytes);
            if (nameBytes > MAX_HEADER_BYTES - bytes) {
                return Optional.empty();
            }
            bytes += nameBytes;
            final int valueBytes = utf8Length(string.value(), MAX_HEADER_BYTES - bytes);
            if (valueBytes > MAX_HEADER_BYTES - bytes) {
                return Optional.empty();
            }
            bytes += valueBytes;
            headers.put(header.getKey(), string.value());
        }
        return Optional.of(headers);
    }

    private static Optional<byte[]> requestBody(final RailixValue value) {
        try {
            return RailixJson.write(value, RailixData.DEFAULT_MAX_SOURCE_BYTES)
                    .map(json -> json.getBytes(StandardCharsets.UTF_8));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<RailixValue.ObjectValue> responseHeaders(
            final HttpURLConnection connection
    ) {
        final Map<String, List<RailixValue>> fields = new LinkedHashMap<>();
        int bytes = 0;
        for (int index = 1; ; index++) {
            final String headerName = connection.getHeaderFieldKey(index);
            if (headerName == null) {
                break;
            }
            final String value = connection.getHeaderField(index);
            final String name = headerName.toLowerCase(Locale.ROOT);
            final List<RailixValue> values = fields.computeIfAbsent(
                    name,
                    ignored -> new ArrayList<>()
            );
            if (fields.size() > MAX_HEADERS) {
                return Optional.empty();
            }
            final int nameBytes = utf8Length(name, MAX_HEADER_BYTES - bytes);
            if (nameBytes > MAX_HEADER_BYTES - bytes) {
                return Optional.empty();
            }
            bytes += nameBytes;
            final int valueBytes = utf8Length(value, MAX_HEADER_BYTES - bytes);
            if (valueBytes > MAX_HEADER_BYTES - bytes) {
                return Optional.empty();
            }
            bytes += valueBytes;
            values.add(RailixValue.string(value));
        }
        final Map<String, RailixValue> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, List<RailixValue>> field : fields.entrySet()) {
            normalized.put(field.getKey(), RailixValue.array(field.getValue()));
        }
        return Optional.of(RailixValue.object(normalized));
    }

    private static byte[] responseBody(
            final HttpURLConnection connection,
            final int status,
            final long deadline
    ) throws IOException, InterruptedException {
        connection.setReadTimeout(remainingMillis(deadline));
        final Optional<InputStream> stream = responseStream(connection, status);
        if (stream.isEmpty()) {
            return new byte[0];
        }
        try (InputStream response = stream.get()) {
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8_192];
            while (body.size() <= RailixData.DEFAULT_MAX_SOURCE_BYTES) {
                checkInterrupted();
                connection.setReadTimeout(remainingMillis(deadline));
                final int count = response.read(
                        buffer,
                        0,
                        Math.min(
                                buffer.length,
                                RailixData.DEFAULT_MAX_SOURCE_BYTES + 1 - body.size()
                        )
                );
                if (count < 0) {
                    return body.toByteArray();
                }
                body.write(buffer, 0, count);
            }
            return body.toByteArray();
        }
    }

    private static Optional<InputStream> responseStream(
            final HttpURLConnection connection,
            final int status
    ) throws IOException {
        try {
            return Optional.of(connection.getInputStream());
        } catch (final IOException exception) {
            final Optional<InputStream> stream =
                    Optional.ofNullable(connection.getErrorStream());
            if (stream.isEmpty() && status < 400) {
                throw exception;
            }
            return stream;
        }
    }

    private static boolean containsHeader(
            final Map<String, String> headers,
            final String name
    ) {
        for (final String header : headers.keySet()) {
            if (header.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validHeaderName(final String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            final char character = name.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && "!#$%&'*+-.^_`|~".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean validHeaderValue(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if ((character < 0x20 && character != '\t') || character == 0x7f) {
                return false;
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    private static String outcome(final int status) {
        if (status >= 200 && status <= 299) {
            return "success";
        }
        if (status >= 300 && status <= 399) {
            return "redirect";
        }
        if (status >= 400 && status <= 499) {
            return "client-error";
        }
        if (status >= 500 && status <= 599) {
            return "server-error";
        }
        return "other";
    }

    private static int utf8Length(final String value, final int limit) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            final int width;
            if (character <= 0x7f) {
                width = 1;
            } else if (character <= 0x7ff) {
                width = 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                width = 4;
                index++;
            } else if (Character.isSurrogate(character)) {
                width = 1;
            } else {
                width = 3;
            }
            if (bytes > limit - width) {
                return limit + 1;
            }
            bytes += width;
        }
        return bytes;
    }

    private static int remainingMillis(final long deadline) throws SocketTimeoutException {
        final long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new SocketTimeoutException("HTTP Step deadline elapsed.");
        }
        return (int) Math.min(300_000, Math.max(1, (nanos + 999_999) / 1_000_000));
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private static StepResult rejected() {
        return rejected(0);
    }

    private static StepResult rejected(final int status) {
        return rejected(status, RailixValue.object(Map.of()));
    }

    private static StepResult rejected(
            final int status,
            final RailixValue.ObjectValue headers
    ) {
        return result("rejected", status, headers, RailixValue.nullValue());
    }

    private static StepResult result(
            final String outcome,
            final int status,
            final RailixValue.ObjectValue headers,
            final RailixValue body
    ) {
        return StepResult.outcome(outcome)
                .output("status", RailixValue.number(status))
                .output("headers", headers)
                .output("body", body);
    }
}
