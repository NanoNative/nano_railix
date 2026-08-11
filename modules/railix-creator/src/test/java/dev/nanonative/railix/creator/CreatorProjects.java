package dev.nanonative.railix.creator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class CreatorProjects {
    private CreatorProjects() {
    }

    static String empty(final String id) {
        return """
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}}
                ],"links":[]}
                """.formatted(id);
    }

    static String lowercaseCli() {
        return example("lowercase-app");
    }

    static String grouping() {
        return """
                {"format":1,"id":"grouping","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[]
                  }]},
                  {"id":"lowercase-text","use":"railix.field-manipulation","inputs":{}},
                  {"id":"return-text","use":"railix.field-manipulation","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase-text"},
                  {"from":"lowercase-text.next","to":"return-text"},
                  {"from":"return-text.next","to":"end"}
                ]}
                """;
    }

    static String nestedLowercase() {
        return """
                {"format":1,"id":"nested-lowercase","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"hello","payload":[],"context":{"payload":{"text":"Hello RAILIX"}}
                  }]},
                  {"id":"lowercase-text","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","text"],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"text.lowercase","inputs":{}}]
                  }},
                  {"id":"return-text","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"field","inputs":{
                      "source":["context","payload","text"]
                    }}],"steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase-text"},
                  {"from":"lowercase-text.next","to":"return-text"},
                  {"from":"return-text.next","to":"end"}
                ]}
                """;
    }

    static String previewBoundary() {
        return """
                {"format":1,"id":"preview-boundary","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"hello","payload":["Hello RAILIX"]
                  }]},
                  {"id":"lowercase-text","use":"text.lowercase","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","result"]}},
                  {"id":"overwrite","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"later"}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase-text"},
                  {"from":"lowercase-text.ok","to":"overwrite"},
                  {"from":"overwrite.next","to":"end"}
                ]}
                """;
    }

    private static String example(final String name) {
        final Path project = Path.of(
                "..",
                "..",
                "examples",
                name,
                "railix.project.json"
        ).toAbsolutePath().normalize();
        try {
            return Files.readString(project);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + project, exception);
        }
    }

    static String fallibleNumber() {
        return """
                {
                  "format":1,
                  "id":"fallible-number",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                      "name":"number","payload":[],"context":{"payload":{"value":"12.5"}}
                    }]},
                    {"id":"convert","use":"railix.field-manipulation","inputs":{
                      "field":["context","payload","value"],
                      "value":[{"option":"current","inputs":{}}],
                      "steps":[{"use":"text.to-number","inputs":{}}]
                    }},
                    {"id":"number-result","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"field","inputs":{
                        "source":["context","payload","value"]
                      }}],
                      "steps":[]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"convert"},
                    {"from":"convert.next","to":"number-result"},
                    {"from":"number-result.next","to":"end"}
                  ]
                }
                """;
    }

    static String orderedCandidates() {
        return """
                {"format":1,"id":"ordered-candidates","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"existing","payload":[],"context":{"payload":{"value":"existing"}}
                  }]},
                  {"id":"change","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","value"],
                    "value":[
                      {"option":"current","inputs":{},"when":[{
                        "use":"value.equals","inputs":{"expected":"different"}
                      }]},
                      {"option":"literal","inputs":{"literal":"fallback"},"when":[]}
                    ],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"change"},
                  {"from":"change.next","to":"end"}
                ]}
                """;
    }

}
