package dev.nanonative.railix.mesh;

import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A validated endpoint shared by deployment configuration and peer state messages. */
public record PeerEndpoint(String host, int port) {
    private static final int MAX_HOST_LENGTH = 253;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final Set<String> FIELDS = Set.of("host", "port");
    private static final Pattern DOTTED_IPV4 = Pattern.compile("[0-9]+(?:\\.[0-9]+){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("([0-9a-fA-F:.]+)(%[a-zA-Z0-9_.-]+)?");

    /**
     * @throws IllegalArgumentException if the host is invalid or the port is outside 1–65535
     */
    public PeerEndpoint {
        host = normalizeHost(host);
        if (host.isEmpty() || port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(MeshErrors.INVALID_ENDPOINT);
        }
    }

    /**
     * @param value an endpoint object with host and port fields
     * @return the parsed endpoint, or empty when the value is missing or invalid
     */
    public static Optional<PeerEndpoint> read(final RailixValue value) {
        final Map<String, RailixValue> fields = endpointFields(value);
        final String host = normalizeHost(hostname(fields));
        if (host.isEmpty()) {
            return Optional.empty();
        }
        final OptionalInt port = port(fields);
        if (port.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PeerEndpoint(host, port.getAsInt()));
    }

    /**
     * @param value an endpoint object with host and port fields
     * @return whether the value describes a valid endpoint
     */
    public static boolean isValid(final RailixValue value) {
        final Map<String, RailixValue> fields = endpointFields(value);
        final String host = hostname(fields);
        return !normalizeHost(host).isEmpty() && port(fields).isPresent();
    }

    private static Map<String, RailixValue> endpointFields(final RailixValue value) {
        if (value instanceof RailixValue.ObjectValue endpoint && endpoint.values().keySet().equals(FIELDS)) {
            return endpoint.values();
        }
        return Map.of();
    }

    private static String hostname(final Map<String, RailixValue> fields) {
        return fields.get("host") instanceof RailixValue.StringValue name ? name.value() : "";
    }

    private static OptionalInt port(final Map<String, RailixValue> fields) {
        return integer(fields.get("port"), MIN_PORT, MAX_PORT);
    }

    static OptionalInt integer(final RailixValue value, final int lower, final int upper) {
        if (!(value instanceof RailixValue.NumberValue number)) {
            return OptionalInt.empty();
        }
        final BigDecimal decimal = number.value();
        if (decimal.compareTo(BigDecimal.valueOf(lower)) < 0
                || decimal.compareTo(BigDecimal.valueOf(upper)) > 0
                || decimal.stripTrailingZeros().scale() > 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(decimal.intValue());
    }

    /** @return the host and port as an object for a peer state message */
    public RailixValue.ObjectValue value() {
        return RailixValue.object(Map.of("host", RailixValue.string(host), "port", RailixValue.number(port)));
    }

    private static String normalizeHost(final String host) {
        if (host == null || host.isBlank() || host.length() > MAX_HOST_LENGTH
                || host.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return "";
        }
        try {
            if (host.indexOf(':') >= 0) {
                return validIpv6Host(host) ? host : "";
            }
            final String name = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (name.equals(".") || name.length() > MAX_HOST_LENGTH) {
                return "";
            }
            if (DOTTED_IPV4.matcher(name).matches()) {
                Inet4Address.ofLiteral(name);
            }
            return name;
        } catch (final IllegalArgumentException invalid) {
            return "";
        }
    }

    private static boolean validIpv6Host(final String host) {
        final String text = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        final Matcher literal = IPV6_LITERAL.matcher(text);
        if (!literal.matches()) {
            return false;
        }
        // Validate a remote interface name as text. Its interface may exist only on the other host.
        final InetAddress address = Inet6Address.ofLiteral(literal.group(1));
        return literal.group(2) == null || address instanceof Inet6Address;
    }
}
