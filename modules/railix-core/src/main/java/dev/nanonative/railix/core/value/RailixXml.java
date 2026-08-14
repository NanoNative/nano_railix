package dev.nanonative.railix.core.value;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless typed XML vocabulary owned by {@link RailixData}. */
final class RailixXml {
    private final String source;
    private final int maxDepth;
    private XMLStreamReader reader;
    private int nextElementOffset;

    private RailixXml(final String source, final int maxDepth) {
        this.source = source;
        this.maxDepth = maxDepth;
    }

    static RailixValue parse(final String source, final int maxDepth) {
        if (source.isBlank()) {
            throw invalid("Expected a Railix XML value element.", 1, 1);
        }
        rejectMarkup(source, "<!DOCTYPE", "XML DTD and custom entities are not supported.");
        rejectMarkup(source, "<!--", "XML comments are not supported.");
        rejectMarkup(source, "<![CDATA[", "XML CDATA is not supported.");
        rejectProcessingInstructions(source);
        return new RailixXml(source, maxDepth).document();
    }

    private RailixValue document() {
        try {
            final XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLInputFactory.IS_COALESCING, false);
            // Every typed container may add one field/item wrapper around its value.
            factory.setProperty("jdk.xml.maxElementDepth", (int) Math.min(source.length(), maxDepth * 2L + 1));
            reader = factory.createXMLStreamReader(new StringReader(source));
            final String declaredEncoding = reader.getCharacterEncodingScheme();
            if (declaredEncoding != null && !declaredEncoding.equalsIgnoreCase("UTF-8")) {
                throw unsupported("XML declaration encoding must be UTF-8.", new Position(1, 1));
            }
            RailixValue value = RailixValue.nullValue();
            while (reader.hasNext()) {
                final int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    value = value(0);
                }
            }
            return value;
        } catch (final RailixData.Failure failure) {
            throw failure;
        } catch (final XMLStreamException exception) {
            final int line = exception.getLocation() == null ? 1 : Math.max(1, exception.getLocation().getLineNumber());
            final int column = exception.getLocation() == null
                    ? 1
                    : Math.max(1, exception.getLocation().getColumnNumber());
            throw invalid("Malformed XML document.", line, column);
        } finally {
            close();
        }
    }

    private RailixValue value(final int enclosingDepth) throws XMLStreamException {
        final Position position = start();
        validateNamespace(position);
        final String element = reader.getLocalName();
        return switch (element) {
            case "object" -> {
                rejectAttributes(position);
                checkDepth(enclosingDepth + 1, position);
                yield object(enclosingDepth + 1, position);
            }
            case "array" -> {
                rejectAttributes(position);
                checkDepth(enclosingDepth + 1, position);
                yield array(enclosingDepth + 1, position);
            }
            case "string" -> {
                rejectAttributes(position);
                yield RailixValue.string(primitiveText());
            }
            case "number" -> {
                rejectAttributes(position);
                yield number(primitiveText(), position);
            }
            case "boolean" -> {
                rejectAttributes(position);
                yield bool(primitiveText(), position);
            }
            case "null" -> {
                rejectAttributes(position);
                final String text = primitiveText();
                if (!text.isEmpty()) {
                    throw invalid("XML null must be empty.", position);
                }
                yield RailixValue.nullValue();
            }
            default -> throw unsupported("Unsupported Railix XML element: " + element, position);
        };
    }

    private RailixValue.ObjectValue object(final int depth, final Position objectPosition) throws XMLStreamException {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                final Position position = start();
                validateNamespace(position);
                if (!reader.getLocalName().equals("field")) {
                    throw invalid("XML object accepts only field elements.", position);
                }
                final String name = fieldName(position);
                if (values.containsKey(name)) {
                    throw RailixData.failure(
                            "DATA_FIELD_DUPLICATE",
                            "Duplicate object field: " + name,
                            position.line(),
                            position.column()
                    );
                }
                values.put(name, wrapper("field", depth, position));
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                return RailixValue.object(values);
            } else {
                structuralText(event, "XML object cannot contain text.", objectPosition);
            }
        }
    }

    private RailixValue.ArrayValue array(final int depth, final Position arrayPosition) throws XMLStreamException {
        final List<RailixValue> values = new ArrayList<>();
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                final Position position = start();
                validateNamespace(position);
                if (!reader.getLocalName().equals("item")) {
                    throw invalid("XML array accepts only item elements.", position);
                }
                rejectAttributes(position);
                values.add(wrapper("item", depth, position));
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                return RailixValue.array(values);
            } else {
                structuralText(event, "XML array cannot contain text.", arrayPosition);
            }
        }
    }

    private RailixValue wrapper(
            final String wrapper,
            final int depth,
            final Position wrapperPosition
    ) throws XMLStreamException {
        RailixValue value = RailixValue.nullValue();
        boolean found = false;
        while (true) {
            final int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (found) {
                    throw invalid(
                            "XML " + wrapper + " must contain exactly one value element.",
                            start()
                    );
                }
                value = value(depth);
                found = true;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (!found) {
                    throw invalid(
                            "XML " + wrapper + " must contain exactly one value element.",
                            wrapperPosition
                    );
                }
                return value;
            } else {
                structuralText(event, "XML " + wrapper + " cannot contain text.", wrapperPosition);
            }
        }
    }

    private String primitiveText() throws XMLStreamException {
        final StringBuilder text = new StringBuilder();
        while (true) {
            final int event = reader.next();
            if (text(event)) {
                text.append(reader.getText());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                throw invalid("XML primitive values cannot contain child elements.", start());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                return text.toString();
            }
        }
    }

    private RailixValue number(final String source, final Position position) {
        if (!source.equals(source.strip())) {
            throw invalid("Invalid XML number.", position);
        }
        return switch (RailixJson.parse(source)) {
            case RailixJson.Parsed parsed when parsed.value() instanceof RailixValue.NumberValue -> parsed.value();
            case RailixJson.Invalid invalid
                    when RailixJson.NUMBER_SOURCE_LIMIT_MESSAGE.equals(invalid.message()) ->
                    throw RailixData.numberLimitExceeded();
            default -> throw invalid("Invalid XML number.", position);
        };
    }

    private static RailixValue bool(final String source, final Position position) {
        return switch (source) {
            case "true" -> RailixValue.bool(true);
            case "false" -> RailixValue.bool(false);
            default -> throw invalid("XML boolean must be exactly true or false.", position);
        };
    }

    private String fieldName(final Position position) {
        if (reader.getAttributeCount() != 1
                || !reader.getAttributeLocalName(0).equals("name")
                || namespaced(reader.getAttributeNamespace(0), reader.getAttributePrefix(0))) {
            if (reader.getAttributeCount() == 0) {
                throw invalid("XML field requires exactly one name attribute.", position);
            }
            throw unsupported("XML attributes are supported only as field names.", position);
        }
        return reader.getAttributeValue(0);
    }

    private void rejectAttributes(final Position position) {
        if (reader.getAttributeCount() > 0) {
            throw unsupported("XML attributes are supported only as field names.", position);
        }
    }

    private void validateNamespace(final Position position) {
        if (reader.getNamespaceCount() > 0
                || namespaced(reader.getNamespaceURI(), reader.getPrefix())) {
            throw unsupported("XML namespaces are not supported.", position);
        }
    }

    private static boolean namespaced(final String namespace, final String prefix) {
        return namespace != null && !namespace.isEmpty() || prefix != null && !prefix.isEmpty();
    }

    private void checkDepth(final int depth, final Position position) {
        if (depth > maxDepth) {
            throw RailixData.failure(
                    "DATA_DEPTH_EXCEEDED",
                    "Data exceeds the maximum container depth of " + maxDepth + ".",
                    position.line(),
                    position.column()
            );
        }
    }

    private void structuralText(final int event, final String message, final Position ownerPosition) {
        if (text(event) && !xmlWhitespace(reader.getText())) {
            throw invalid(message, ownerPosition);
        }
    }

    private Position start() {
        final String prefix = reader.getPrefix();
        final String name = prefix == null || prefix.isEmpty()
                ? reader.getLocalName()
                : prefix + ":" + reader.getLocalName();
        final int opening = Math.max(0, source.indexOf("<" + name, nextElementOffset));
        nextElementOffset = opening + 1;
        return position(source, Math.max(0, opening));
    }

    private void close() {
        if (reader != null) {
            try {
                reader.close();
            } catch (final XMLStreamException ignored) {
                // String-backed readers own no external resource after parsing.
            }
        }
    }

    private static boolean text(final int event) {
        return event == XMLStreamConstants.CHARACTERS;
    }

    private static boolean xmlWhitespace(final String source) {
        for (int index = 0; index < source.length(); index++) {
            if (!xmlWhitespace(source.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean xmlWhitespace(final char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }

    private static void rejectMarkup(final String source, final String markup, final String message) {
        final int index = source.indexOf(markup);
        if (index >= 0) {
            throw unsupported(message, position(source, index));
        }
    }

    private static void rejectProcessingInstructions(final String source) {
        int index = source.indexOf("<?");
        if (source.equals("<?xml")) {
            return;
        }
        if (index == 0 && source.startsWith("<?xml")
                && source.length() > 5 && xmlWhitespace(source.charAt(5))) {
            final int declarationEnd = source.indexOf("?>");
            if (declarationEnd < 0) {
                return;
            }
            if (source.substring(declarationEnd + 2).isBlank()) {
                throw invalid("Expected a Railix XML value element.", 1, 1);
            }
            index = source.indexOf("<?", declarationEnd + 2);
        }
        if (index >= 0) {
            throw unsupported("XML processing instructions are not supported.", position(source, index));
        }
    }

    private static Position position(final String source, final int offset) {
        int line = 1;
        int column = 1;
        for (int index = 0; index < offset; index++) {
            final char character = source.charAt(index);
            if (character == '\r') {
                line++;
                column = 1;
            } else if (character == '\n') {
                if (index == 0 || source.charAt(index - 1) != '\r') {
                    line++;
                }
                column = 1;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    private static RailixData.Failure invalid(final String message, final int line, final int column) {
        return RailixData.failure("DATA_XML_INVALID", message, line, column);
    }

    private static RailixData.Failure invalid(final String message, final Position position) {
        return invalid(message, position.line(), position.column());
    }

    private static RailixData.Failure unsupported(final String message, final Position position) {
        return RailixData.failure(
                "DATA_XML_UNSUPPORTED",
                message,
                position.line(),
                position.column()
        );
    }

    private record Position(int line, int column) {
    }
}
