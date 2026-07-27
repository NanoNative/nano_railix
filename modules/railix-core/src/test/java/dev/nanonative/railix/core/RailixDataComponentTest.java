package dev.nanonative.railix.core;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RailixDataComponentTest {
    @Test
    void jsonNormalizesNumbersAndObjectOrderIntoOneCanonicalValue() {
        final RailixData.Result result = RailixData.normalize(
                RailixData.Format.JSON,
                bytes("{\"z\":2.500,\"items\":[null,true],\"a\":{}}")
        );

        assertThat(result).isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                "a", RailixValue.object(Map.of()),
                "items", RailixValue.array(List.of(RailixValue.nullValue(), RailixValue.bool(true))),
                "z", RailixValue.number(new BigDecimal("2.5"))
        ))));
    }

    @Test
    void normalizedDataExposesCanonicalJson() {
        final RailixData.Normalized result = (RailixData.Normalized) RailixData.normalize(
                RailixData.Format.JSON,
                bytes("{\"z\":2.500,\"a\":1E+2}")
        );

        assertThat(result.canonicalJson()).isEqualTo("{\"a\":100,\"z\":2.5}");
    }

    @Test
    void missingFormatIsAStableIngressDiagnostic() {
        assertThat(RailixData.normalize(null, bytes("null")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_FORMAT_REQUIRED",
                        "Data format is required.",
                        0,
                        0
                ));
    }

    @Test
    void missingSourceIsAStableIngressDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, null))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_SOURCE_REQUIRED",
                        "Data source is required.",
                        0,
                        0
                ));
    }

    @Test
    void sourceLargerThanTheExplicitByteLimitIsRejectedBeforeParsing() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("00"), 1, 1))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_SOURCE_TOO_LARGE",
                        "Data source exceeds the 1-byte limit.",
                        0,
                        0
                ));
    }

    @Test
    void sourceAtTheExplicitByteLimitIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("0"), 1, 1))
                .isEqualTo(new RailixData.Normalized(RailixValue.number(0)));
    }

    @Test
    void invalidUtf8IsAStableIngressDiagnostic() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                new byte[]{(byte) 0xc3, 0x28}
        )).isEqualTo(new RailixData.Invalid(
                "DATA_SOURCE_UTF8_INVALID",
                "Data source is not valid UTF-8.",
                0,
                0
        ));
    }

    @Test
    void utf8BomIsExplicitlyRejected() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '0'}
        )).isEqualTo(new RailixData.Invalid(
                "DATA_BOM_UNSUPPORTED",
                "UTF-8 BOM is not supported.",
                0,
                0
        ));
    }

    @Test
    void zeroByteLimitIsAStableConfigurationDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("0"), 0, 1))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_LIMIT_INVALID",
                        "Maximum source bytes must be at least 1.",
                        0,
                        0
                ));
    }

    @Test
    void zeroDepthLimitIsAStableConfigurationDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("0"), 1, 0))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_LIMIT_INVALID",
                        "Maximum container depth must be at least 1.",
                        0,
                        0
                ));
    }

    @Test
    void explicitSourceLimitCannotExceedTheSupportedMaximum() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                bytes("0"),
                RailixData.MAX_SOURCE_BYTES + 1,
                1
        )).isEqualTo(new RailixData.Invalid(
                "DATA_LIMIT_INVALID",
                "Maximum source bytes must not exceed 8388608.",
                0,
                0
        ));
    }

    @Test
    void explicitSourceLimitMayExceedTheSafeDefault() {
        final byte[] source = bytes("0" + " ".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES));

        assertThat(RailixData.normalize(RailixData.Format.JSON, source, source.length, 1))
                .isEqualTo(new RailixData.Normalized(RailixValue.number(0)));
    }

    @Test
    void explicitDepthLimitCannotExceedTheSafeDefault() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                bytes("0"),
                1,
                RailixData.DEFAULT_MAX_DEPTH + 1
        )).isEqualTo(new RailixData.Invalid(
                "DATA_LIMIT_INVALID",
                "Maximum container depth must not exceed 64.",
                0,
                0
        ));
    }

    @Test
    void jsonContainerBeyondTheDepthLimitIsRejectedAtItsOpeningToken() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("[[]]"), 4, 1))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 1.",
                        1,
                        2
                ));
    }

    @Test
    void jsonContainerAtTheDepthLimitIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("[[]]"), 4, 2))
                .isEqualTo(new RailixData.Normalized(RailixValue.array(List.of(
                        RailixValue.array(List.of())
                ))));
    }

    @Test
    void malformedJsonKeepsTheJsonDiagnosticLocation() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("{\"x\":}")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_JSON_INVALID",
                        "Expected a JSON value.",
                        1,
                        6
                ));
    }

    @Test
    void yamlNormalizesNestedPrimitiveDataIntoCanonicalJson() {
        final RailixData.Normalized result = (RailixData.Normalized) RailixData.normalize(
                RailixData.Format.YAML,
                bytes("""
                        scores:
                          - 1
                          - 2.50
                        nothing: null
                        name: "Ada"
                        meta:
                          city: "Berlin"
                        age: 42.0
                        active: true
                        """)
        );

        assertThat(result.canonicalJson()).isEqualTo(
                "{\"active\":true,\"age\":42,\"meta\":{\"city\":\"Berlin\"},"
                        + "\"name\":\"Ada\",\"nothing\":null,\"scores\":[1,2.5]}"
        );
    }

    @Test
    void yamlRootQuotedStringIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"Ada\"")))
                .isEqualTo(new RailixData.Normalized(RailixValue.string("Ada")));
    }

    @Test
    void yamlEmptyObjectIsAcceptedAtTheDepthLimit() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("{}"), 2, 1))
                .isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of())));
    }

    @Test
    void yamlNestedContainerBeyondTheDepthLimitIsRejected() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a: {}"), 5, 1))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 1.",
                        1,
                        4
                ));
    }

    @Test
    void yamlDuplicateFieldIsRejectedAtTheSecondField() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a: 1\na: 2")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_FIELD_DUPLICATE",
                        "Duplicate object field: a",
                        2,
                        1
                ));
    }

    @Test
    void emptyYamlDocumentIsRejected() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes(" \n")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Expected a YAML value.",
                        1,
                        1
                ));
    }

    @Test
    void yamlTabsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a:\t1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML tabs are not supported.",
                        1,
                        3
                ));
    }

    @Test
    void yamlCommentsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a: 1 # comment")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML comments are not supported.",
                        1,
                        6
                ));
    }

    @Test
    void yamlDocumentMarkersAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("---\na: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML document markers are not supported.",
                        1,
                        1
                ));
    }

    @Test
    void yamlPlainStringsRequireExplicitQuotes() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("name: Ada")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "Plain YAML strings are not supported; use JSON double quotes.",
                        1,
                        7
                ));
    }

    @Test
    void yamlSingleQuotedStringsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("name: 'Ada'")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML single-quoted strings are not supported.",
                        1,
                        7
                ));
    }

    @Test
    void nonEmptyYamlFlowCollectionsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("items: [1]")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "Non-empty YAML flow collections are not supported.",
                        1,
                        8
                ));
    }

    @Test
    void yamlAnchorsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("value: &id 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML anchors, aliases, and tags are not supported.",
                        1,
                        8
                ));
    }

    @Test
    void yamlAliasesAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("value: *id")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML anchors, aliases, and tags are not supported.",
                        1,
                        8
                ));
    }

    @Test
    void yamlTagsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("value: !str 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML anchors, aliases, and tags are not supported.",
                        1,
                        8
                ));
    }

    @Test
    void yamlAnchorMappingKeysAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("&id key: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML anchors, aliases, and tags are not supported.",
                        1,
                        1
                ));
    }

    @Test
    void yamlAliasMappingKeysAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("*id: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML anchors, aliases, and tags are not supported.",
                        1,
                        1
                ));
    }

    @Test
    void yamlTagMappingKeysAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("!tag key: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML anchors, aliases, and tags are not supported.",
                        1,
                        1
                ));
    }

    @Test
    void yamlBlockScalarsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("value: |\n  text")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML block scalars are not supported.",
                        1,
                        8
                ));
    }

    @Test
    void yamlOddIndentationIsRejected() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a:\n b: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "YAML indentation must use multiples of two spaces.",
                        2,
                        1
                ));
    }

    @Test
    void yamlUnexpectedIndentationIsRejected() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a: 1\n  b: 2")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Unexpected YAML indentation.",
                        2,
                        3
                ));
    }

    @Test
    void yamlMappingsAndSequencesCannotMixAtOneDepth() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a: 1\n- 2")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Cannot mix YAML mappings and sequences at the same depth.",
                        2,
                        1
                ));
    }

    @Test
    void malformedJsonQuotedYamlStringIsRejectedAtTheScalar() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("name: \"Ada")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Invalid JSON-quoted YAML string.",
                        1,
                        7
                ));
    }

    @Test
    void xmlNormalizesNestedPrimitiveDataIntoCanonicalJson() {
        final RailixData.Normalized result = (RailixData.Normalized) RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <object>
                          <field name="scores">
                            <array>
                              <item><number>1</number></item>
                              <item><number>2.50</number></item>
                            </array>
                          </field>
                          <field name="nothing"><null/></field>
                          <field name="name"><string>Ada</string></field>
                          <field name="meta">
                            <object><field name="city"><string>Berlin</string></field></object>
                          </field>
                          <field name="age"><number>42.0</number></field>
                          <field name="active"><boolean>true</boolean></field>
                        </object>
                        """)
        );

        assertThat(result.canonicalJson()).isEqualTo(
                "{\"active\":true,\"age\":42,\"meta\":{\"city\":\"Berlin\"},"
                        + "\"name\":\"Ada\",\"nothing\":null,\"scores\":[1,2.5]}"
        );
    }

    @Test
    void xmlStringPreservesWhitespaceAndPredefinedEscapes() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<string> A &amp; &#65; </string>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.string(" A & A ")));
    }

    @Test
    void emptyXmlObjectIsAcceptedAtTheDepthLimit() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<object/>"), 9, 1))
                .isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of())));
    }

    @Test
    void emptyXmlArrayIsAcceptedAtTheDepthLimit() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<array/>"), 8, 1))
                .isEqualTo(new RailixData.Normalized(RailixValue.array(List.of())));
    }

    @Test
    void xmlNestedContainerBeyondTheDepthLimitIsRejectedAtItsElement() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <object>
                          <field name="value">
                            <array/>
                          </field>
                        </object>
                        """),
                80,
                1
        )).isEqualTo(new RailixData.Invalid(
                "DATA_DEPTH_EXCEEDED",
                "Data exceeds the maximum container depth of 1.",
                3,
                5
        ));
    }

    @Test
    void xmlContainerDepthBelowTheDefaultLimitIsNotBlockedByParserDefaults() {
        final String source = "<array><item>".repeat(50)
                + "<null/>"
                + "</item></array>".repeat(50);

        assertThat(RailixData.normalize(RailixData.Format.XML, bytes(source)))
                .isEqualTo(new RailixData.Normalized(nestedArrays(50)));
    }

    @Test
    void xmlDuplicateFieldIsRejectedAtTheSecondField() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <object>
                          <field name="a"><null/></field>
                          <field name="a"><null/></field>
                        </object>
                        """)
        )).isEqualTo(new RailixData.Invalid(
                "DATA_FIELD_DUPLICATE",
                "Duplicate object field: a",
                3,
                3
        ));
    }

    @Test
    void emptyXmlDocumentIsRejected() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes(" \n")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "Expected a Railix XML value element.",
                        1,
                        1
                ));
    }

    @Test
    void malformedXmlUsesAnAuthoredDiagnostic() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object><field name=\"x\"><null/></object>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "Malformed XML document.",
                1,
                34
        ));
    }

    @Test
    void xmlDtdIsExplicitlyUnsupported() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<!DOCTYPE object><object/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML DTD and custom entities are not supported.",
                1,
                1
        ));
    }

    @Test
    void xmlExternalEntityDeclarationIsRejectedBeforeResolution() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<!DOCTYPE string [<!ENTITY x SYSTEM \"file:///tmp/x\">]><string>&x;</string>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML DTD and custom entities are not supported.",
                1,
                1
        ));
    }

    @Test
    void xmlNamespacesAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object xmlns=\"urn:test\"/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML namespaces are not supported.",
                1,
                1
        ));
    }

    @Test
    void xmlAttributesOutsideFieldNamesAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object extra=\"x\"/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML attributes are supported only as field names.",
                1,
                1
        ));
    }

    @Test
    void arbitraryXmlElementsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<value/>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_UNSUPPORTED",
                        "Unsupported Railix XML element: value",
                        1,
                        1
                ));
    }

    @Test
    void xmlCommentsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<!-- no hidden data --><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML comments are not supported.",
                1,
                1
        ));
    }

    @Test
    void xmlProcessingInstructionsAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?work no?><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML processing instructions are not supported.",
                1,
                1
        ));
    }

    @Test
    void xmlCdataIsExplicitlyUnsupported() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<string><![CDATA[value]]></string>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML CDATA is not supported.",
                1,
                9
        ));
    }

    @Test
    void xmlFieldRequiresANameAttribute() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <object>
                          <field><null/></field>
                        </object>
                        """)
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML field requires exactly one name attribute.",
                2,
                3
        ));
    }

    @Test
    void xmlFieldRequiresOneValue() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <object>
                          <field name="a"/>
                        </object>
                        """)
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML field must contain exactly one value element.",
                2,
                3
        ));
    }

    @Test
    void xmlFieldRejectsASecondValue() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <object>
                          <field name="a">
                            <null/>
                            <string>x</string>
                          </field>
                        </object>
                        """)
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML field must contain exactly one value element.",
                4,
                5
        ));
    }

    @Test
    void xmlObjectAcceptsOnlyFieldElements() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <object>
                          <null/>
                        </object>
                        """)
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML object accepts only field elements.",
                2,
                3
        ));
    }

    @Test
    void xmlArrayAcceptsOnlyItemElements() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <array>
                          <null/>
                        </array>
                        """)
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML array accepts only item elements.",
                2,
                3
        ));
    }

    @Test
    void xmlItemRejectsAttributes() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("""
                        <array>
                          <item name="a"><null/></item>
                        </array>
                        """)
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML attributes are supported only as field names.",
                2,
                3
        ));
    }

    @Test
    void xmlBooleanUsesExactLowercaseLiterals() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<boolean>True</boolean>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "XML boolean must be exactly true or false.",
                        1,
                        1
                ));
    }

    @Test
    void xmlNumberUsesTheJsonDecimalGrammar() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<number>01</number>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "Invalid XML number.",
                        1,
                        1
                ));
    }

    @Test
    void xmlNullMustBeEmpty() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<null> </null>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "XML null must be empty.",
                        1,
                        1
                ));
    }

    @Test
    void xmlPrimitiveCannotContainAChildElement() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<string><null/></string>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML primitive values cannot contain child elements.",
                1,
                9
        ));
    }

    @Test
    void xmlRejectsASecondRootValue() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<null/><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "Malformed XML document.",
                1,
                9
        ));
    }

    @Test
    void jsonDepthScannerIgnoresContainersAndEscapedQuotesInsideStrings() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                bytes("{\"x\":\"[{\\\"}]\"}"),
                16,
                1
        )).isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                "x", RailixValue.string("[{\"}]")
        ))));
    }

    @Test
    void jsonDepthDiagnosticTracksCarriageReturnLines() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("[\r[]]"), 5, 1))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 1.",
                        2,
                        1
                ));
    }

    @Test
    void jsonDepthDiagnosticCountsCarriageReturnLineFeedOnce() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("[\r\n[]]"), 6, 1))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 1.",
                        2,
                        1
                ));
    }

    @Test
    void canonicalNumberExpansionBeyondTheLimitIsRejectedBeforeWriting() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("1e1024")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_NUMBER_LIMIT_EXCEEDED",
                        "Number exceeds the 1024-character canonical limit.",
                        0,
                        0
                ));
    }

    @Test
    void jsonScaleOverflowReturnsTheCanonicalNumberLimitDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("100e2147483647")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_NUMBER_LIMIT_EXCEEDED",
                        "Number exceeds the 1024-character canonical limit.",
                        0,
                        0
                ));
    }

    @Test
    void yamlScaleOverflowReturnsTheCanonicalNumberLimitDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("100e2147483647")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_NUMBER_LIMIT_EXCEEDED",
                        "Number exceeds the 1024-character canonical limit.",
                        0,
                        0
                ));
    }

    @Test
    void xmlScaleOverflowReturnsTheCanonicalNumberLimitDiagnostic() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<number>100e2147483647</number>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_NUMBER_LIMIT_EXCEEDED",
                "Number exceeds the 1024-character canonical limit.",
                0,
                0
        ));
    }

    @Test
    void jsonExtremeScaleZeroNormalizesToCanonicalZero() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("0e-2147483647")))
                .isEqualTo(new RailixData.Normalized(RailixValue.number(0)));
    }

    @Test
    void yamlExtremeScaleZeroNormalizesToCanonicalZero() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("0e-2147483647")))
                .isEqualTo(new RailixData.Normalized(RailixValue.number(0)));
    }

    @Test
    void xmlExtremeScaleZeroNormalizesToCanonicalZero() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<number>0e-2147483647</number>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.number(0)));
    }

    @Test
    void removableFractionalZeroesMayReduceTheCanonicalNumberBelowTheLimit() {
        final String source = "1" + "0".repeat(1_000) + "e-1500";

        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes(source)))
                .isEqualTo(new RailixData.Normalized(
                        RailixValue.number(new BigDecimal("1e-500"))
                ));
    }

    @Test
    void jsonNumberSourceLimitUsesTheNumberLimitDiagnostic() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                bytes("1".repeat(1_025))
        )).isEqualTo(new RailixData.Invalid(
                "DATA_NUMBER_LIMIT_EXCEEDED",
                "JSON number exceeds the 1024-character source limit.",
                1,
                1_026
        ));
    }

    @Test
    void negativeCanonicalNumberIncludesItsSign() {
        final RailixData.Normalized result = (RailixData.Normalized) RailixData.normalize(
                RailixData.Format.JSON,
                bytes("-1")
        );

        assertThat(result.canonicalJson()).isEqualTo("-1");
    }

    @Test
    void fractionalCanonicalNumberIncludesLeadingZeroes() {
        final RailixData.Normalized result = (RailixData.Normalized) RailixData.normalize(
                RailixData.Format.JSON,
                bytes("0.001")
        );

        assertThat(result.canonicalJson()).isEqualTo("0.001");
    }

    @Test
    void bareYamlSequenceEntryCanContainANestedObject() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("-\n  a: 1")))
                .isEqualTo(new RailixData.Normalized(RailixValue.array(List.of(
                        RailixValue.object(Map.of("a", RailixValue.number(1)))
                ))));
    }

    @Test
    void yamlSequenceCannotMixAMappingAtTheSameDepth() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("- 1\na: 2")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Cannot mix YAML mappings and sequences at the same depth.",
                        2,
                        1
                ));
    }

    @Test
    void yamlMappingCannotMixAScalarAtTheSameDepth() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a: 1\n\"loose\"")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Cannot mix YAML mappings and sequences at the same depth.",
                        2,
                        1
                ));
    }

    @Test
    void yamlMergeKeysAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("<<: {}")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML merge keys are not supported.",
                        1,
                        1
                ));
    }

    @Test
    void yamlMappingKeyCannotStartWithADigit() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("1a: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "YAML mapping keys must match [A-Za-z_][A-Za-z0-9_-]*.",
                        1,
                        1
                ));
    }

    @Test
    void yamlMappingKeyRejectsUnsupportedCharactersAfterItsFirstCharacter() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a.b: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "YAML mapping keys must match [A-Za-z_][A-Za-z0-9_-]*.",
                        1,
                        1
                ));
    }

    @Test
    void yamlMappingColonRequiresFollowingSpace() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a:1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Expected a space after the YAML mapping colon.",
                        1,
                        3
                ));
    }

    @Test
    void yamlMappingRequiresAValueAfterABareColon() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a:")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Expected an indented YAML value.",
                        1,
                        3
                ));
    }

    @Test
    void yamlNestedValueMustIndentExactlyTwoSpaces() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a:\n    b: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Unexpected YAML indentation.",
                        2,
                        5
                ));
    }

    @Test
    void yamlFalseLiteralIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("false")))
                .isEqualTo(new RailixData.Normalized(RailixValue.bool(false)));
    }

    @Test
    void yamlEmptyArrayIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("[]")))
                .isEqualTo(new RailixData.Normalized(RailixValue.array(List.of())));
    }

    @Test
    void malformedYamlNumberIsRejectedInsteadOfBecomingAString() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("01")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Invalid YAML number.",
                        1,
                        1
                ));
    }

    @Test
    void yamlDirectivesAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("%YAML 1.2")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML directives are not supported.",
                        1,
                        1
                ));
    }

    @Test
    void yamlEndDocumentMarkerIsExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("...")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML document markers are not supported.",
                        1,
                        1
                ));
    }

    @Test
    void yamlFoldedBlockScalarIsExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("value: >\n  text")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "YAML block scalars are not supported.",
                        1,
                        8
                ));
    }

    @Test
    void yamlQuotedStringMayContainCommentColonAndEscapedQuoteCharacters() {
        assertThat(RailixData.normalize(
                RailixData.Format.YAML,
                bytes("value: \"a#b:c\\\"d\"")
        )).isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                "value", RailixValue.string("a#b:c\"d")
        ))));
    }

    @Test
    void yamlFieldNamesAcceptUppercaseUnderscoreDigitsAndHyphen() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("A_1-z: false")))
                .isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                        "A_1-z", RailixValue.bool(false)
                ))));
    }

    @Test
    void yamlRootCannotStartAtAnIndentedLevel() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("  a: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Unexpected YAML indentation.",
                        1,
                        3
                ));
    }

    @Test
    void yamlNestedScalarCannotHaveAnUnattachedSibling() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a:\n  1\n  2")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Unexpected YAML indentation.",
                        3,
                        3
                ));
    }

    @Test
    void xmlFalseLiteralIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<boolean>false</boolean>")))
                .isEqualTo(new RailixData.Normalized(RailixValue.bool(false)));
    }

    @Test
    void xmlNumberRejectsFormattingWhitespace() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<number> 1</number>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "Invalid XML number.",
                        1,
                        1
                ));
    }

    @Test
    void xmlFieldRejectsAnAttributeOtherThanName() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object><field other=\"x\"><null/></field></object>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML attributes are supported only as field names.",
                1,
                9
        ));
    }

    @Test
    void xmlObjectRejectsNonWhitespaceText() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<object>text</object>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "XML object cannot contain text.",
                        1,
                        1
                ));
    }

    @Test
    void xmlArrayRejectsNonWhitespaceText() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<array>text</array>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "XML array cannot contain text.",
                        1,
                        1
                ));
    }

    @Test
    void xmlFieldRejectsNonWhitespaceText() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object><field name=\"a\">text</field></object>")
        )).isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "XML field cannot contain text.",
                        1,
                        9
        ));
    }

    @Test
    void xmlItemRequiresOneValue() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<array><item/></array>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML item must contain exactly one value element.",
                1,
                8
        ));
    }

    @Test
    void xmlItemRejectsASecondValue() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<array><item><null/><string>x</string></item></array>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML item must contain exactly one value element.",
                1,
                21
        ));
    }

    @Test
    void xmlDeclarationIsAccepted() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml version=\"1.0\" encoding=\"UTF-8\"?><null/>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.nullValue()));
    }

    @Test
    void xmlDeclarationCannotContradictStrictUtf8Ingress() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML declaration encoding must be UTF-8.",
                1,
                        1
                ));
    }

    @Test
    void xmlStylesheetProcessingInstructionIsNotMistakenForADeclaration() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml-stylesheet href=\"theme.css\"?><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML processing instructions are not supported.",
                1,
                        1
                ));
    }

    @Test
    void xmlDeclarationWithoutAValueIsRejectedExplicitly() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml version=\"1.0\"?>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "Expected a Railix XML value element.",
                1,
                1
        ));
    }

    @Test
    void unterminatedXmlDeclarationUsesTheAuthoredMalformedDiagnostic() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml version=\"1.0\"")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "Malformed XML document.",
                1,
                20
        ));
    }

    @Test
    void truncatedXmlDeclarationTargetUsesTheAuthoredMalformedDiagnostic() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "Malformed XML document.",
                1,
                6
        ));
    }

    @Test
    void xmlUtf8DeclarationAllowsStandardWhitespaceAroundEquals() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml version=\"1.0\" encoding = 'utf-8'?><null/>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.nullValue()));
    }

    @Test
    void xmlDeclarationAllowsTabAfterItsTarget() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml\tversion=\"1.0\" encoding=\"UTF-8\"?><null/>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.nullValue()));
    }

    @Test
    void xmlDeclarationAllowsCarriageReturnAfterItsTarget() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml\rversion=\"1.0\" encoding=\"UTF-8\"?><null/>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.nullValue()));
    }

    @Test
    void xmlDeclarationAllowsLineFeedAfterItsTarget() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml\nversion=\"1.0\" encoding=\"UTF-8\"?><null/>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.nullValue()));
    }

    @Test
    void predefinedXmlElementNamespaceIsStillExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<xml:object/>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_UNSUPPORTED",
                        "XML namespaces are not supported.",
                        1,
                        1
                ));
    }

    @Test
    void predefinedXmlAttributeNamespaceCannotMasqueradeAsAFieldName() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object><field xml:name=\"x\"><null/></field></object>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML attributes are supported only as field names.",
                1,
                9
        ));
    }

    @Test
    void encodingTextInsideMalformedXmlVersionIsNotTreatedAsADeclarationAttribute() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml version=\"encoding='ISO-8859-1'\"?><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "Malformed XML document.",
                1,
                38
        ));
    }

    @Test
    void xmlNumberCannotContainABooleanLiteral() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<number>true</number>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "Invalid XML number.",
                        1,
                        1
                ));
    }

    @Test
    void xmlCollectionsAllowOnlyXmlStructuralWhitespace() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object> \t\r\n</object>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of())));
    }

    @Test
    void xmlProcessingInstructionAfterDeclarationIsRejected() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml version=\"1.0\"?><?work no?><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML processing instructions are not supported.",
                1,
                22
        ));
    }

    @Test
    void xmlDtdDiagnosticTracksCarriageReturnLines() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("\r<!DOCTYPE null><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML DTD and custom entities are not supported.",
                2,
                1
        ));
    }

    @Test
    void xmlDtdDiagnosticCountsCarriageReturnLineFeedOnce() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("\r\n<!DOCTYPE null><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML DTD and custom entities are not supported.",
                2,
                1
        ));
    }

    @Test
    void emptyXmlFieldNameRemainsAnExplicitEmptyObjectKey() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object><field name=\"\"><null/></field></object>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                "", RailixValue.nullValue()
        ))));
    }

    @Test
    void normalizationIsStatelessAcrossConcurrentPublicCalls() throws Exception {
        final List<Callable<RailixData.Result>> calls = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            calls.add(() -> RailixData.normalize(
                    RailixData.Format.XML,
                    bytes("<object><field name=\"value\"><number>2.50</number></field></object>")
            ));
        }

        final List<RailixData.Result> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var future : executor.invokeAll(calls)) {
                results.add(future.get());
            }
        }

        assertThat(results).containsOnly(new RailixData.Normalized(RailixValue.object(Map.of(
                "value", RailixValue.number(new BigDecimal("2.5"))
        ))));
    }

    @Test
    void defaultByteLimitRejectsOneByteOverTheVisibleMaximum() {
        final byte[] source = new byte[RailixData.DEFAULT_MAX_SOURCE_BYTES + 1];

        assertThat(RailixData.normalize(RailixData.Format.JSON, source))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_SOURCE_TOO_LARGE",
                        "Data source exceeds the 1048576-byte limit.",
                        0,
                        0
                ));
    }

    @Test
    void defaultByteLimitAcceptsAValidDocumentAtTheVisibleMaximum() {
        final byte[] source = new byte[RailixData.DEFAULT_MAX_SOURCE_BYTES];
        Arrays.fill(source, (byte) ' ');
        source[0] = '0';

        assertThat(RailixData.normalize(RailixData.Format.JSON, source))
                .isEqualTo(new RailixData.Normalized(RailixValue.number(0)));
    }

    @Test
    void defaultDepthLimitRejectsTheSixtyFifthContainer() {
        final String source = "[".repeat(RailixData.DEFAULT_MAX_DEPTH + 1)
                + "0"
                + "]".repeat(RailixData.DEFAULT_MAX_DEPTH + 1);

        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes(source)))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 64.",
                        1,
                        65
                ));
    }

    @Test
    void defaultDepthLimitAcceptsExactlySixtyFourContainers() {
        final String source = "[".repeat(RailixData.DEFAULT_MAX_DEPTH)
                + "0"
                + "]".repeat(RailixData.DEFAULT_MAX_DEPTH);
        final RailixData.Normalized result = (RailixData.Normalized) RailixData.normalize(
                RailixData.Format.JSON,
                bytes(source)
        );

        assertThat(result.canonicalJson()).isEqualTo(source);
    }

    @Test
    void defaultDepthLimitAcceptsExactlySixtyFourYamlContainers() {
        assertThat(RailixData.normalize(
                RailixData.Format.YAML,
                bytes(yamlObjects(RailixData.DEFAULT_MAX_DEPTH))
        )).isEqualTo(new RailixData.Normalized(nestedObjects(RailixData.DEFAULT_MAX_DEPTH)));
    }

    @Test
    void defaultDepthLimitRejectsTheSixtyFifthYamlContainer() {
        assertThat(RailixData.normalize(
                RailixData.Format.YAML,
                bytes(yamlObjects(RailixData.DEFAULT_MAX_DEPTH + 1))
        )).isEqualTo(new RailixData.Invalid(
                "DATA_DEPTH_EXCEEDED",
                "Data exceeds the maximum container depth of 64.",
                65,
                129
        ));
    }

    @Test
    void defaultDepthLimitAcceptsExactlySixtyFourXmlContainers() {
        final String source = "<array><item>".repeat(RailixData.DEFAULT_MAX_DEPTH)
                + "<null/>"
                + "</item></array>".repeat(RailixData.DEFAULT_MAX_DEPTH);

        assertThat(RailixData.normalize(RailixData.Format.XML, bytes(source)))
                .isEqualTo(new RailixData.Normalized(nestedArrays(RailixData.DEFAULT_MAX_DEPTH)));
    }

    @Test
    void defaultDepthLimitRejectsTheSixtyFifthXmlContainer() {
        final String source = "<array><item>".repeat(RailixData.DEFAULT_MAX_DEPTH + 1)
                + "<null/>"
                + "</item></array>".repeat(RailixData.DEFAULT_MAX_DEPTH + 1);

        assertThat(RailixData.normalize(RailixData.Format.XML, bytes(source)))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 64.",
                        1,
                        833
                ));
    }

    @Test
    void canonicalNumberAtTheVisibleCharacterLimitIsAccepted() {
        final RailixData.Normalized result = (RailixData.Normalized) RailixData.normalize(
                RailixData.Format.JSON,
                bytes("1e1023")
        );

        assertThat(result.canonicalJson()).isEqualTo("1" + "0".repeat(1023));
    }

    @Test
    void partialUtf8BomPrefixAtTheSecondByteIsNotMisclassifiedAsABom() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                new byte[]{(byte) 0xef, (byte) 0x80, (byte) 0x80}
        )).isEqualTo(new RailixData.Invalid(
                "DATA_JSON_INVALID",
                "Expected a JSON value.",
                1,
                1
        ));
    }

    @Test
    void partialUtf8BomPrefixAtTheThirdByteIsNotMisclassifiedAsABom() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0x80}
        )).isEqualTo(new RailixData.Invalid(
                "DATA_JSON_INVALID",
                "Expected a JSON value.",
                1,
                1
        ));
    }

    @Test
    void jsonDepthDiagnosticTracksLineFeedLines() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("[\n[]]"), 5, 1))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 1.",
                        2,
                        1
                ));
    }

    @Test
    void jsonDepthDiagnosticTracksLineFeedAtTheFirstByte() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\n[[]]"), 5, 1))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 1.",
                        2,
                        2
                ));
    }

    @Test
    void xmlDtdDiagnosticTracksLineFeedLines() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("\n<!DOCTYPE null><null/>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML DTD and custom entities are not supported.",
                2,
                1
        ));
    }

    @Test
    void yamlRootScalarCannotHaveASecondRootScalar() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"a\"\n\"b\"")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Unexpected YAML indentation.",
                        2,
                        1
                ));
    }

    @Test
    void yamlMissingNestedValueIsRejectedBeforeTheNextField() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a:\nb: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "Expected an indented YAML value.",
                        1,
                        3
                ));
    }

    @Test
    void nonEmptyYamlFlowObjectIsExplicitlyUnsupported() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("value: {a: 1}")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_UNSUPPORTED",
                        "Non-empty YAML flow collections are not supported.",
                        1,
                        8
                ));
    }

    @Test
    void emptyYamlMappingKeyIsRejected() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes(": 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "YAML mapping keys must match [A-Za-z_][A-Za-z0-9_-]*.",
                        1,
                        1
                ));
    }

    @Test
    void yamlNegativeNumberIsAcceptedAsAScalarRatherThanASequence() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("-1")))
                .isEqualTo(new RailixData.Normalized(RailixValue.number(-1)));
    }

    @Test
    void yamlMappingKeyRejectsOpeningSquareBracket() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a[: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "YAML mapping keys must match [A-Za-z_][A-Za-z0-9_-]*.",
                        1,
                        1
                ));
    }

    @Test
    void yamlMappingKeyRejectsOpeningCurlyBrace() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("a{: 1")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_YAML_INVALID",
                        "YAML mapping keys must match [A-Za-z_][A-Za-z0-9_-]*.",
                        1,
                        1
                ));
    }

    @Test
    void jsonLineCommentsAreRejectedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("// comment\nnull")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_JSON_INVALID",
                        "Expected a JSON value.",
                        1,
                        1
                ));
    }

    @Test
    void jsonBlockCommentsAreRejectedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("/* comment */null")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_JSON_INVALID",
                        "Expected a JSON value.",
                        1,
                        1
                ));
    }

    @Test
    void jsonDuplicateFieldsKeepTheJsonDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("{\"a\":1,\"a\":2}")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_JSON_INVALID",
                        "Duplicate object field: a",
                        1,
                        8
                ));
    }

    @Test
    void jsonStandardWhitespaceIsAcceptedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes(" \t\r\nnull \t")))
                .isEqualTo(new RailixData.Normalized(RailixValue.nullValue()));
    }

    @Test
    void jsonUnicodeSurrogatePairIsAcceptedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\"\\uD83D\\uDE00\"")))
                .isEqualTo(new RailixData.Normalized(RailixValue.string("\uD83D\uDE00")));
    }

    @Test
    void yamlIgnoresBlankLinesAndTrailingSpaces() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\n  \nname: \"Ada\"  \n")))
                .isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                        "name", RailixValue.string("Ada")
                ))));
    }

    @Test
    void yamlAcceptsCarriageReturnLineFeedDocuments() {
        assertThat(RailixData.normalize(
                RailixData.Format.YAML,
                bytes("a: 1\r\nb:\r\n  - true\r\n  - true")
        )).isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                "a", RailixValue.number(1),
                "b", RailixValue.array(List.of(RailixValue.bool(true), RailixValue.bool(true)))
        ))));
    }

    @Test
    void yamlDoubleQuotedStringsUseJsonUnicodeEscapes() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"A\\u0042\"")))
                .isEqualTo(new RailixData.Normalized(RailixValue.string("AB")));
    }

    @Test
    void yamlSequencesPreserveRepeatedValues() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("- true\n- true")))
                .isEqualTo(new RailixData.Normalized(RailixValue.array(List.of(
                        RailixValue.bool(true),
                        RailixValue.bool(true)
                ))));
    }

    @Test
    void yamlNumbersUseJsonExponentGrammar() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("1e2")))
                .isEqualTo(new RailixData.Normalized(RailixValue.number(new BigDecimal("1E+2"))));
    }

    @Test
    void yamlEmptyDoubleQuotedStringIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"\"")))
                .isEqualTo(new RailixData.Normalized(RailixValue.string("")));
    }

    @Test
    void xmlUndefinedEntityUsesTheAuthoredMalformedDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<string>&unknown;</string>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "Malformed XML document.",
                        1,
                        18
                ));
    }

    @Test
    void xmlInternalCustomEntityDeclarationIsExplicitlyUnsupported() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<!DOCTYPE string [<!ENTITY x \"ok\">]><string>&x;</string>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML DTD and custom entities are not supported.",
                1,
                1
        ));
    }

    @Test
    void xmlInvalidNumericCharacterReferenceUsesTheAuthoredMalformedDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<string>&#0;</string>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "Malformed XML document.",
                        1,
                        13
                ));
    }

    @Test
    void xmlPrefixedNamespacesAreExplicitlyUnsupported() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<p:string xmlns:p=\"urn:test\">value</p:string>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML namespaces are not supported.",
                1,
                1
        ));
    }

    @Test
    void xmlFieldNameAttributePreservesCharacterReferences() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object><field name=\"a&amp;b\"><null/></field></object>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                "a&b", RailixValue.nullValue()
        ))));
    }

    @Test
    void xmlHexadecimalCharacterReferenceIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<string>&#x41;</string>")))
                .isEqualTo(new RailixData.Normalized(RailixValue.string("A")));
    }

    @Test
    void xmlAllowsStructuralWhitespaceAroundTheRootValue() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes(" \n<object/>\n ")))
                .isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of())));
    }

    @Test
    void xmlArraysPreserveRepeatedItems() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<array><item><null/></item><item><null/></item></array>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.array(List.of(
                RailixValue.nullValue(),
                RailixValue.nullValue()
        ))));
    }

    @Test
    void xmlNumbersUseJsonExponentGrammar() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<number>1e2</number>")))
                .isEqualTo(new RailixData.Normalized(RailixValue.number(new BigDecimal("1E+2"))));
    }

    @Test
    void xmlEmptyStringIsAccepted() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<string/>")))
                .isEqualTo(new RailixData.Normalized(RailixValue.string("")));
    }

    @Test
    void jsonArraysPreserveRepeatedValuesAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("[1,1]")))
                .isEqualTo(new RailixData.Normalized(RailixValue.array(List.of(
                        RailixValue.number(1),
                        RailixValue.number(1)
                ))));
    }

    @Test
    void jsonRootFalseIsAcceptedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("false")))
                .isEqualTo(new RailixData.Normalized(RailixValue.bool(false)));
    }

    @Test
    void jsonEmptyFieldNameIsAcceptedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("{\"\":null}")))
                .isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                        "", RailixValue.nullValue()
                ))));
    }

    @Test
    void jsonEscapedFieldNameIsAcceptedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("{\"a\\u0026b\":null}")))
                .isEqualTo(new RailixData.Normalized(RailixValue.object(Map.of(
                        "a&b", RailixValue.nullValue()
                ))));
    }

    @Test
    void jsonTrailingRootContentKeepsItsDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("true false")))
                .isEqualTo(invalidJson("Unexpected content after the JSON value.", 6));
    }

    @Test
    void jsonStandardEscapesAreAcceptedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(
                RailixData.Format.JSON,
                bytes("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"")
        )).isEqualTo(normalizedString("\"\\/\b\f\n\r\t"));
    }

    @Test
    void jsonUnsupportedEscapeKeepsItsDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\"\\x\"")))
                .isEqualTo(invalidJson("Unsupported JSON escape: \\x", 4));
    }

    @Test
    void jsonUnterminatedStringKeepsItsDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\"abc")))
                .isEqualTo(invalidJson("Unterminated JSON string.", 5));
    }

    @Test
    void jsonUnterminatedEscapeKeepsItsDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\"abc\\")))
                .isEqualTo(invalidJson("Unterminated JSON escape.", 6));
    }

    @Test
    void jsonIncompleteUnicodeEscapeKeepsItsDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\"\\u12")))
                .isEqualTo(invalidJson("Incomplete Unicode escape.", 4));
    }

    @Test
    void jsonInvalidUnicodeEscapeKeepsItsDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\"\\u12xz\"")))
                .isEqualTo(invalidJson("Invalid Unicode escape: 12xz", 8));
    }

    @Test
    void jsonRawControlCharacterKeepsItsDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\"\u0001\"")))
                .isEqualTo(invalidJson("Control characters are not allowed in JSON strings.", 3));
    }

    @Test
    void jsonUnpairedSurrogateKeepsItsDiagnosticAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(RailixData.Format.JSON, bytes("\"\\uD800\"")))
                .isEqualTo(invalidJson(
                        "Unpaired Unicode surrogate is not allowed in JSON strings.",
                        9
                ));
    }

    @Test
    void yamlStandardEscapesAreAcceptedAtTheNormalizationBoundary() {
        assertThat(RailixData.normalize(
                RailixData.Format.YAML,
                bytes("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"")
        )).isEqualTo(normalizedString("\"\\/\b\f\n\r\t"));
    }

    @Test
    void yamlUnsupportedEscapeUsesTheQuotedStringDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"\\x\"")))
                .isEqualTo(invalidQuotedYaml());
    }

    @Test
    void yamlUnterminatedEscapeUsesTheQuotedStringDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"abc\\")))
                .isEqualTo(invalidQuotedYaml());
    }

    @Test
    void yamlIncompleteUnicodeEscapeUsesTheQuotedStringDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"\\u12")))
                .isEqualTo(invalidQuotedYaml());
    }

    @Test
    void yamlInvalidUnicodeEscapeUsesTheQuotedStringDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"\\u12xz\"")))
                .isEqualTo(invalidQuotedYaml());
    }

    @Test
    void yamlRawControlCharacterUsesTheQuotedStringDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"\u0001\"")))
                .isEqualTo(invalidQuotedYaml());
    }

    @Test
    void yamlUnpairedSurrogateUsesTheQuotedStringDiagnostic() {
        assertThat(RailixData.normalize(RailixData.Format.YAML, bytes("\"\\uD800\"")))
                .isEqualTo(invalidQuotedYaml());
    }

    @Test
    void xmlFieldRejectsNamePlusAnotherAttribute() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<object><field name=\"a\" other=\"x\"><null/></field></object>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_UNSUPPORTED",
                "XML attributes are supported only as field names.",
                1,
                9
        ));
    }

    @Test
    void xmlItemRejectsNonWhitespaceText() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<array><item>text</item></array>")
        )).isEqualTo(new RailixData.Invalid(
                "DATA_XML_INVALID",
                "XML item cannot contain text.",
                1,
                8
        ));
    }

    @Test
    void xmlBooleanRejectsFormattingWhitespace() {
        assertThat(RailixData.normalize(RailixData.Format.XML, bytes("<boolean> true</boolean>")))
                .isEqualTo(new RailixData.Invalid(
                        "DATA_XML_INVALID",
                        "XML boolean must be exactly true or false.",
                        1,
                        1
                ));
    }

    @Test
    void xmlDeclarationWithoutEncodingIsAccepted() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<?xml version=\"1.0\"?><null/>")
        )).isEqualTo(new RailixData.Normalized(RailixValue.nullValue()));
    }

    @Test
    void xmlRemainingPredefinedCharacterReferencesAreAccepted() {
        assertThat(RailixData.normalize(
                RailixData.Format.XML,
                bytes("<string>&lt;&gt;&quot;&apos;</string>")
        )).isEqualTo(normalizedString("<>\"'"));
    }

    private static RailixValue nestedArrays(final int depth) {
        RailixValue value = RailixValue.nullValue();
        for (int index = 0; index < depth; index++) {
            value = RailixValue.array(List.of(value));
        }
        return value;
    }

    private static RailixValue nestedObjects(final int depth) {
        RailixValue value = RailixValue.nullValue();
        for (int index = 0; index < depth; index++) {
            value = RailixValue.object(Map.of("a", value));
        }
        return value;
    }

    private static String yamlObjects(final int depth) {
        final StringBuilder source = new StringBuilder();
        for (int index = 0; index < depth; index++) {
            source.append("  ".repeat(index)).append("a:\n");
        }
        return source.append("  ".repeat(depth)).append("null").toString();
    }

    private static RailixData.Normalized normalizedString(final String value) {
        return new RailixData.Normalized(RailixValue.string(value));
    }

    private static RailixData.Invalid invalidJson(final String message, final int column) {
        return new RailixData.Invalid("DATA_JSON_INVALID", message, 1, column);
    }

    private static RailixData.Invalid invalidQuotedYaml() {
        return new RailixData.Invalid(
                "DATA_YAML_INVALID",
                "Invalid JSON-quoted YAML string.",
                1,
                1
        );
    }

    private static byte[] bytes(final String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
