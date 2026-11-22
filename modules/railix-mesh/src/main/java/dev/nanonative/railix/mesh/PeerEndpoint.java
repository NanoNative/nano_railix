package dev.nanonative.railix.mesh;

import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/** A validated endpoint shared by deployment configuration and peer state messages. */
public record PeerEndpoint(String host, int port) {
    private static final int MAX_HOST_LENGTH = 253;
    private static final Pattern DOTTED_IPV4 = Pattern.compile("[0-9]+(?:\\.[0-9]+){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("([0-9a-fA-F:.]+)(%[a-zA-Z0-9_.-]+)?");

    /**
     * @throws IllegalArgumentException if the host is invalid or the port is outside 1–65535
     */
    public PeerEndpoint {
        if (!validHost(host) || port < 1 || port > 65535) {
            throw new IllegalArgumentException(MeshErrors.INVALID_ENDPOINT);
        }
    }

    /**
     * @param value an endpoint object with host and port fields
     * @return the parsed endpoint, or empty when the value is missing or invalid
     */
    public static Optional<PeerEndpoint> read(final RailixValue value) {
        if (!(value instanceof RailixValue.ObjectValue endpoint)
                || !endpoint.values().keySet().equals(Set.of("host", "port"))
                || !(endpoint.values().get("host") instanceof RailixValue.StringValue hostname)
                || !validHost(hostname.value())) {
            return Optional.empty();
        }
        final OptionalInt port = integer(endpoint.values().get("port"), 1, 65535);
        return port.isPresent() ? Optional.of(new PeerEndpoint(hostname.value(), port.getAsInt())) : Optional.empty();
    }

    static OptionalInt integer(final RailixValue value, final int lower, final int upper) {
        if (!(value instanceof RailixValue.NumberValue number)) {
            return OptionalInt.empty();
        }
        final var decimal = number.value();
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

    private static boolean validHost(final String host) {
        if (host == null || host.isBlank() || host.length() > MAX_HOST_LENGTH
                || host.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return false;
        }
        try {
            if (host.indexOf(':') >= 0) {
                return validIpv6Host(host);
            }
            final String name = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (DOTTED_IPV4.matcher(name).matches()) {
                Inet4Address.ofLiteral(name);
            }
            return !name.isEmpty() && !name.equals(".") && name.length() <= MAX_HOST_LENGTH;
        } catch (final IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validIpv6Host(final String host) {
        final String text = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        final var literal = IPV6_LITERAL.matcher(text);
        if (!literal.matches()) {
            return false;
        }
        // Validate a remote interface name as text. Its interface may exist only on the other host.
        final var address = Inet6Address.ofLiteral(literal.group(1));
        return literal.group(2) == null || address instanceof Inet6Address;
    }
}
