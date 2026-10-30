package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;
import org.xml.sax.helpers.DefaultHandler;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only built-in and user icon catalog for Creator presentation editing. */
final class IconLibrary {
    private static final int MAX_CUSTOM_ICONS = 128;
    private static final int MAX_DIAGNOSTICS = 64;
    private static final int MAX_DIRECTORY_ENTRIES = 512;
    private static final int MAX_ICON_BYTES = 65_536;
    private static final int MAX_ICON_DIMENSION = 2_048;
    private static final int MAX_TOTAL_ICON_BYTES = 1_048_576;
    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final List<Icon> BUILT_INS = List.of(
            svg("flow", "Flow", "<path d='M4 7h6m4 0h6M7 4v6m10-6v6M7 14v6m10-6v6M7 17h10'/><circle cx='7' cy='7' r='2'/><circle cx='17' cy='7' r='2'/><circle cx='7' cy='17' r='2'/><circle cx='17' cy='17' r='2'/>"),
            svg("bolt", "Bolt", "<path d='M13 2 5 14h6l-1 8 9-13h-6z'/>"),
            svg("database", "Database", "<ellipse cx='12' cy='5' rx='8' ry='3'/><path d='M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6'/>"),
            svg("terminal", "Terminal", "<rect x='3' y='4' width='18' height='16' rx='2'/><path d='m7 9 3 3-3 3m6 0h4'/>"),
            svg("network", "Network", "<circle cx='5' cy='12' r='3'/><circle cx='19' cy='5' r='3'/><circle cx='19' cy='19' r='3'/><path d='m8 11 8-4M8 13l8 4'/>"),
            svg("shield", "Shield", "<path d='M12 2 4 5v6c0 5 3.4 9.1 8 11 4.6-1.9 8-6 8-11V5z'/>"),
            svg("globe", "Web", "<circle cx='12' cy='12' r='9'/><ellipse cx='12' cy='12' rx='4' ry='9'/><path d='M3 12h18'/>"),
            svg("filter", "Filter", "<path d='M3 4h18l-7 8v7l-4 2v-9z'/>"),
            svg("route", "Route", "<path d='M5 20V7h13m-4-4 4 4-4 4M5 13h9l4 5'/><circle cx='5' cy='20' r='2'/><circle cx='19' cy='19' r='2'/>"),
            svg("transform", "Transform", "<path d='M3 7h18m-4-4 4 4-4 4M21 17H3m4-4-4 4 4 4'/>"),
            svg("code", "Code", "<path d='m8 5-6 7 6 7m8-14 6 7-6 7M14 3l-4 18'/>"),
            svg("clock", "Schedule", "<circle cx='12' cy='12' r='9'/><path d='M12 6v6l4 2'/>"),
            svg("mail", "Message", "<rect x='3' y='5' width='18' height='14' rx='2'/><path d='m3 6 9 7 9-7'/>"),
            svg("file", "File", "<path d='M14 2H5v20h14V7zM14 2v5h5M8 12h8M8 16h8'/>"),
            svg("queue", "Queue", "<rect x='3' y='5' width='4' height='14' rx='1'/><rect x='10' y='5' width='4' height='14' rx='1'/><rect x='17' y='5' width='4' height='14' rx='1'/>"),
            svg("lock", "Secret", "<rect x='4' y='10' width='16' height='11' rx='2'/><path d='M8 10V6a4 4 0 0 1 8 0v4M12 15v2'/>"),
            svg("check", "Validate", "<circle cx='12' cy='12' r='9'/><path d='m7 12 3 3 7-7'/>"),
            svg("calculator", "Calculate", "<rect x='5' y='2' width='14' height='20' rx='2'/><path d='M8 6h8M8 11h1m6 0h1M8 15h1m6 0h1M8 19h1m6 0h1'/>")
    );
    private final Path directory;

    IconLibrary(final Path railixHome) {
        directory = railixHome.toAbsolutePath().normalize().resolve("icons");
    }

    RailixValue.ObjectValue listing() throws IOException {
        final List<RailixValue> icons = new ArrayList<>(BUILT_INS.stream().<RailixValue>map(Icon::value).toList());
        final List<RailixValue> diagnostics = new ArrayList<>();
        final Set<String> ids = new LinkedHashSet<>(BUILT_INS.stream().map(Icon::id).toList());
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.list(directory)) {
                final List<Path> bounded = paths.limit(MAX_DIRECTORY_ENTRIES + 1L).toList();
                if (bounded.size() > MAX_DIRECTORY_ENTRIES) {
                    addDiagnostic(diagnostics, diagnostic(
                            directory,
                            "CREATOR_ICON_CATALOG_LIMIT",
                            "Icon catalog scans at most 512 directory entries."
                    ));
                }
                int customIcons = 0;
                long totalBytes = 0;
                for (final Path path : bounded.stream().limit(MAX_DIRECTORY_ENTRIES).sorted().toList()) {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    final String file = path.getFileName().toString();
                    final int dot = file.lastIndexOf('.');
                    final String stem = dot > 0 ? file.substring(0, dot) : "";
                    final String extension = dot > 0 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
                    final String mediaType = switch (extension) {
                        case "svg" -> "image/svg+xml";
                        case "png" -> "image/png";
                        default -> "";
                    };
                    final String id = "custom:" + stem;
                    final long size = Files.size(path);
                    final byte[] bytes = size <= MAX_ICON_BYTES ? Files.readAllBytes(path) : new byte[0];
                    if (!SAFE_NAME.matcher(stem).matches() || mediaType.isEmpty() || bytes.length == 0
                            || !ids.add(id) || !valid(mediaType, bytes)) {
                        addDiagnostic(diagnostics, diagnostic(path, size > MAX_ICON_BYTES
                                ? "CREATOR_ICON_TOO_LARGE" : "CREATOR_ICON_INVALID", null));
                        continue;
                    }
                    if (customIcons == MAX_CUSTOM_ICONS || size > MAX_TOTAL_ICON_BYTES - totalBytes) {
                        addDiagnostic(diagnostics, diagnostic(
                                directory,
                                "CREATOR_ICON_CATALOG_LIMIT",
                                "Icon catalog accepts up to 128 custom icons and 1048576 source bytes."
                        ));
                        break;
                    }
                    icons.add(new Icon(id, stem, mediaType, Base64.getEncoder().encodeToString(bytes)).value());
                    customIcons++;
                    totalBytes += size;
                }
            }
        }
        return RailixValue.object(Map.of(
                "diagnostics", RailixValue.array(diagnostics),
                "icons", RailixValue.array(icons)
        ));
    }

    static boolean valid(final String mediaType, final byte[] bytes) {
        if ("image/png".equals(mediaType)) {
            return validPng(bytes);
        }
        return validSvg(bytes);
    }

    private static boolean validPng(final byte[] bytes) {
        if (bytes.length < 24) {
            return false;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        final int width = ByteBuffer.wrap(bytes, 16, 4).getInt();
        final int height = ByteBuffer.wrap(bytes, 20, 4).getInt();
        if (width < 1 || height < 1 || width > MAX_ICON_DIMENSION || height > MAX_ICON_DIMENSION) {
            return false;
        }
        try {
            final var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                image.flush();
            }
            return image != null;
        } catch (final IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean validSvg(final byte[] bytes) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            final var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            final var root = builder.parse(new ByteArrayInputStream(bytes))
                    .getDocumentElement();
            final String namespace = root.getNamespaceURI();
            final boolean svg = "svg".equals(root.getLocalName()) || "svg".equals(root.getTagName());
            return svg && (namespace == null || SVG_NAMESPACE.equals(namespace));
        } catch (final Exception exception) {
            return false;
        }
    }

    private static void addDiagnostic(
            final List<RailixValue> diagnostics,
            final RailixValue.ObjectValue diagnostic
    ) {
        if (diagnostics.size() < MAX_DIAGNOSTICS) {
            diagnostics.add(diagnostic);
        }
    }

    private static RailixValue.ObjectValue diagnostic(
            final Path path,
            final String code,
            final String message
    ) {
        return RailixValue.object(Map.of(
                "code", RailixValue.string(code),
                "message", RailixValue.string(message == null
                        ? "Icon must be a valid SVG or PNG up to 65536 bytes and 2048 pixels per side."
                        : message),
                "path", RailixValue.string(path.toString()),
                "severity", RailixValue.string("warning")
        ));
    }

    private static Icon svg(final String id, final String name, final String body) {
        final String source = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' "
                + "stroke='#173b40' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'>"
                + body + "</svg>";
        return new Icon(id, name, "image/svg+xml", Base64.getEncoder().encodeToString(
                source.getBytes(StandardCharsets.UTF_8)
        ));
    }

    private record Icon(String id, String name, String mediaType, String data) {
        RailixValue.ObjectValue value() {
            return RailixValue.object(Map.of(
                    "data", RailixValue.string(data),
                    "id", RailixValue.string(id),
                    "media_type", RailixValue.string(mediaType),
                    "name", RailixValue.string(name)
            ));
        }
    }
}
