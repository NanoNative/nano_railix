package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thirdparty.conformance.ThirdPartyKvStep;
import thirdparty.conformance.ThirdPartyKvSteps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseStepGeneratedApplicationE2eTest {
    @TempDir
    private Path directory;

    @Test
    void generatedJarPackagesDatabaseStepOnlyWhenReferenced() throws Exception {
        final Path withoutDbProject = writeProject(directory.resolve("without-db"), CreatorProjects.lowercaseCli());
        final Path withoutDbJar = productionArtifact(withoutDbProject, CreatorProjects.lowercaseCli()).jar();
        try (JarFile jar = new JarFile(withoutDbJar.toFile())) {
            final List<String> entries = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(entries).noneMatch(entry -> entry.contains("DatabaseStepHandlers"));
        }

        final String withDbSource = databaseCliProject();
        final Path withDbProject = writeProject(directory.resolve("with-db"), withDbSource);
        final Path withDbJar = productionArtifact(withDbProject, withDbSource).jar();
        try (JarFile jar = new JarFile(withDbJar.toFile())) {
            final List<String> entries = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(entries)
                    .contains("dev/nanonative/railix/stdlib/DatabaseStepHandlers.class")
                    .noneMatch(entry -> entry.startsWith("dev/nanonative/railix/stdlib/DatabaseStepHandlers$"));
        }
    }

    @Test
    void productionJarPersistsCrudAndBulkUpsertAcrossSeparateJvmLaunches() throws Exception {
        final String source = databaseCliProject();
        final Path project = writeProject(directory.resolve("prod-persistence"), source);
        final Path jar = productionArtifact(project, source).jar();

        final ProcessResult upserted = runJar(jar, "upsert");
        assertThat(upserted.exitCode()).isZero();
        assertThat(parseJson(upserted.output())).isEqualTo(RailixValue.object(Map.of(
                "_id", RailixValue.string("ord-1"),
                "total", RailixValue.number(new BigDecimal("99999.12345")),
                "active", RailixValue.bool(true)
        )));

        final ProcessResult fetchedAfterRestart = runJar(jar, "get");
        assertThat(fetchedAfterRestart.exitCode()).isZero();
        assertThat(parseJson(fetchedAfterRestart.output())).isEqualTo(RailixValue.object(Map.of(
                "_id", RailixValue.string("ord-1"),
                "total", RailixValue.number(new BigDecimal("99999.12345")),
                "active", RailixValue.bool(true)
        )));

        final ProcessResult bulkSaved = runJar(jar, "bulk");
        assertThat(bulkSaved.exitCode()).isZero();
        assertThat(((RailixValue.ArrayValue) parseJson(bulkSaved.output())).values()).hasSize(2);

        final ProcessResult deleted = runJar(jar, "delete");
        assertThat(deleted.exitCode()).isZero();
        assertThat(parseJson(deleted.output())).isEqualTo(RailixValue.object(Map.of(
                "_id", RailixValue.string("ord-1"),
                "total", RailixValue.number(new BigDecimal("250.75")),
                "active", RailixValue.bool(false)
        )));

        final ProcessResult missingAfterDelete = runJar(jar, "get");
        assertThat(missingAfterDelete.exitCode()).isEqualTo(44);
        assertThat(parseJson(missingAfterDelete.output())).isEqualTo(RailixValue.string("not_found"));
    }

    @Test
    void creatorRollingReplacementReleasesCollectionLockAndPreservesStoredRecords() throws Exception {
        final Path workspace = directory.resolve("rolling-workspace");
        final Path project = writeProject(workspace, rollingCreatorProject(false));

        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"))) {
            final URI baseUri = creator.baseUri();
            assertThat(number(awaitExamples(baseUri, 1), "succeeded")).isEqualTo(1);
            final RailixValue.ObjectValue before = application(baseUri);
            assertThat(exampleResult(baseUri, "cli:0")).isEqualTo(RailixValue.object(Map.of(
                    "_id", RailixValue.string("acct-7"),
                    "balance", RailixValue.number(new BigDecimal("500.25"))
            )));

            final HttpResponse<String> update = creatorRequest(
                    baseUri,
                    "POST",
                    "/api/project",
                    rollingCreatorProject(true)
            );
            assertThat(update.statusCode()).isEqualTo(200);
            assertThat(number(awaitExamples(baseUri, 1), "succeeded")).isEqualTo(1);

            final RailixValue.ObjectValue after = application(baseUri);
            assertThat(string(after, "state")).isEqualTo("running");
            assertThat(number(after, "pid")).isNotEqualTo(number(before, "pid"));
            assertThat(exampleResult(baseUri, "cli:0")).isEqualTo(RailixValue.object(Map.of(
                    "_id", RailixValue.string("acct-7"),
                    "balance", RailixValue.number(new BigDecimal("500.25"))
            )));
        }
    }

    @Test
    void installedThirdPartyPersistenceBundleRunsThroughIdenticalPublicContracts() throws Exception {
        final Path workspace = Files.createDirectories(directory.resolve("third-party-db"));
        final Path externalStoreFile = workspace.resolve("external-kv.json");
        final StepCatalog catalog = GeneratedApplicationFixture.installedCatalog(
                workspace,
                ThirdPartyKvSteps.class,
                ThirdPartyKvSteps.definitions(),
                ThirdPartyKvSteps.class,
                ThirdPartyKvStep.class
        );
        final String source = """
                {
                  "format": 1,
                  "id": "third-party-persistence",
                  "nodes": [
                    {"id": "app", "use": "railix.app", "inputs": {}},
                    {
                      "id": "cli",
                      "use": "railix.trigger.cli",
                      "inputs": {},
                      "examples": [{"name": "probe", "payload": ["get", "missing"]}]
                    },
                    {
                      "id": "route",
                      "use": "railix.switch",
                      "inputs": {
                        "cases": [
                          {
                            "outcome": "write",
                            "option": "field",
                            "inputs": {"field": ["context", "payload", "arguments", 0]},
                            "when": {
                              "transforms": [],
                              "all": [[{"use": "value.equals", "inputs": {"expected": "put"}}]]
                            }
                          }
                        ]
                      }
                    },
                    {
                      "id": "put-kv",
                      "use": "acme.kv.store",
                      "inputs": {
                        "file": %s,
                        "mode": {
                          "option": "put",
                          "inputs": {
                            "key": ["context", "payload", "arguments", 1],
                            "value": ["context", "payload", "arguments", 2]
                          }
                        },
                        "target": ["context", "result"]
                      }
                    },
                    {
                      "id": "get-kv",
                      "use": "acme.kv.store",
                      "inputs": {
                        "file": %s,
                        "mode": {
                          "option": "get",
                          "inputs": {"key": ["context", "payload", "arguments", 1]}
                        },
                        "target": ["context", "result"]
                      }
                    },
                    {
                      "id": "missing-kv",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "result"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": "absent"},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    }
                  ],
                  "links": [
                    {"from": "app.start", "to": "cli"},
                    {"from": "cli.next", "to": "route"},
                    {"from": "route.write", "to": "put-kv"},
                    {"from": "route.otherwise", "to": "get-kv"},
                    {"from": "put-kv.ok", "to": "end"},
                    {"from": "put-kv.not_found", "to": "missing-kv"},
                    {"from": "get-kv.ok", "to": "end"},
                    {"from": "get-kv.not_found", "to": "missing-kv"},
                    {"from": "missing-kv.next", "to": "end"}
                  ]
                }
                """.formatted(
                RailixJson.write(RailixValue.string(externalStoreFile.toString())),
                RailixJson.write(RailixValue.string(externalStoreFile.toString()))
        );

        final Path project = writeProject(workspace, source);
        final Path jar = productionArtifact(project, source, catalog).jar();

        final ProcessResult put = runJar(jar, "put", "greeting", "hello-from-third-party");
        assertThat(put.exitCode()).as(put.output()).isZero();
        assertThat(parseJson(put.output())).isEqualTo(RailixValue.string("hello-from-third-party"));

        final ProcessResult getPresent = runJar(jar, "get", "greeting");
        assertThat(getPresent.exitCode()).isZero();
        assertThat(parseJson(getPresent.output())).isEqualTo(RailixValue.string("hello-from-third-party"));

        final ProcessResult getMissing = runJar(jar, "get", "unknown");
        assertThat(getMissing.exitCode()).as(getMissing.output()).isZero();
        assertThat(parseJson(getMissing.output())).isEqualTo(RailixValue.string("absent"));
    }

    @Test
    void productionJarExecutesQueryFindWithDefaultsAndUniqueIndexRejection() throws Exception {
        final Path workspace = directory.resolve("find-e2e");
        final String source = databaseFindCliProject();
        final Path project = writeProject(workspace, source);
        final Path jar = productionArtifact(project, source).jar();

        final ProcessResult addFirst = runJar(jar, "add-1");
        assertThat(addFirst.exitCode()).as(addFirst.output()).isZero();
        final RailixValue.ObjectValue doc1 = (RailixValue.ObjectValue) parseJson(addFirst.output());
        assertThat(doc1.values().get("code")).isEqualTo(RailixValue.string("PR-1"));

        final ProcessResult addDuplicate = runJar(jar, "add-dup");
        assertThat(addDuplicate.exitCode()).as(addDuplicate.output()).isEqualTo(49);
        assertThat(parseJson(addDuplicate.output())).isEqualTo(RailixValue.string("conflict_rejected"));

        final ProcessResult addSecond = runJar(jar, "add-2");
        assertThat(addSecond.exitCode()).as(addSecond.output()).isZero();

        final ProcessResult findGadgets = runJar(jar, "find");
        assertThat(findGadgets.exitCode()).as(findGadgets.output()).isZero();
        final RailixValue.ArrayValue results = (RailixValue.ArrayValue) parseJson(findGadgets.output());
        assertThat(results.values()).hasSize(2);

        final RailixValue.ObjectValue pr1 = results.values().stream()
                .map(item -> (RailixValue.ObjectValue) item)
                .filter(item -> RailixValue.string("PR-1").equals(item.values().get("code")))
                .findFirst()
                .orElseThrow();
        assertThat(pr1.values().get("price")).isEqualTo(RailixValue.number(50));
        assertThat(pr1.values().get("discount")).isEqualTo(RailixValue.number(5));
        assertThat(pr1.values()).doesNotContainKey("category");
    }

    @Test
    void httpTriggerAndDatabaseStepPersistJsonCrudAcrossLiveRequests() throws Exception {
        final String source = databaseHttpProject();
        final Path project = writeProject(directory.resolve("http-db"), source);
        final Path jar = productionArtifact(project, source).jar();

        final Process server = RailixPackageIT.instrumentJava(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                jar.toString()
        )).redirectErrorStream(true).start();
        server.getOutputStream().close();
        try (HttpClient client = HttpClient.newHttpClient()) {
            final URI baseUri = awaitHttpBaseUri(server);

            final HttpResponse<String> created = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/orders"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"customer\":\"Ada\",\"amount\":149.95}",
                                    StandardCharsets.UTF_8
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(created.statusCode()).isEqualTo(201);
            final RailixValue.ObjectValue stored = (RailixValue.ObjectValue) parseJson(created.body());
            final String generatedId = string(stored, "_id");
            assertThat(generatedId).hasSize(36);
            assertThat(stored.values())
                    .containsEntry("customer", RailixValue.string("Ada"))
                    .containsEntry("amount", RailixValue.number(new BigDecimal("149.95")));

            final HttpResponse<String> fetched = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/orders?" + generatedId))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(fetched.statusCode()).isEqualTo(200);
            assertThat(parseJson(fetched.body())).isEqualTo(stored);

            final HttpResponse<String> missing = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/orders?nonexistent-id"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(parseJson(missing.body())).isEqualTo(RailixValue.object(Map.of(
                    "error", RailixValue.string("not_found")
            )));
        } finally {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) {
                server.destroyForcibly();
                server.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static URI awaitHttpBaseUri(final Process server) throws Exception {
        try (var readerPool = Executors.newVirtualThreadPerTaskExecutor()) {
            final var future = readerPool.submit(() -> {
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8)
                );
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Railix HTTP ")) {
                        return URI.create(line.substring("Railix HTTP ".length()).strip());
                    }
                }
                throw new AssertionError("HTTP server exited before printing listener URL.");
            });
            return future.get(20, TimeUnit.SECONDS);
        }
    }

    private static String databaseHttpProject() {
        return """
                {
                  "format": 1,
                  "id": "database-http-app",
                  "nodes": [
                    {"id": "app", "use": "railix.app", "inputs": {}},
                    {
                      "id": "http",
                      "use": "railix.trigger.http",
                      "inputs": {"host": "127.0.0.1", "port": 0},
                      "examples": [{
                        "name": "probe",
                        "payload": {"method": "GET", "path": "/orders", "query": "missing", "headers": {}, "body": null}
                      }]
                    },
                    {
                      "id": "route-method",
                      "use": "railix.switch",
                      "inputs": {
                        "cases": [{
                          "outcome": "create",
                          "option": "field",
                          "inputs": {"field": ["context", "payload", "request", "method"]},
                          "when": {"transforms": [], "all": [[{"use": "value.equals", "inputs": {"expected": "POST"}}]]}
                        }]
                      }
                    },
                    {
                      "id": "create-order",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "http-orders",
                        "operation": {
                          "option": "upsert",
                          "inputs": {
                            "document": ["context", "payload", "request", "body"],
                            "auto_key": true
                          }
                        },
                        "target": ["context", "body"]
                      }
                    },
                    {
                      "id": "status-created",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "status"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": 201},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "read-order",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "http-orders",
                        "operation": {
                          "option": "get",
                          "inputs": {"key": ["context", "payload", "request", "query"]}
                        },
                        "target": ["context", "body"]
                      }
                    },
                    {
                      "id": "body-missing",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "body"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": {"error": "not_found"}},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "status-missing",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "status"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": 404},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "status-bad",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "status"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": 400},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    }
                  ],
                  "links": [
                    {"from": "app.start", "to": "http"},
                    {"from": "http.next", "to": "route-method"},
                    {"from": "route-method.create", "to": "create-order"},
                    {"from": "create-order.ok", "to": "status-created"},
                    {"from": "create-order.not_found", "to": "body-missing"},
                    {"from": "create-order.invalid", "to": "status-bad"},
                    {"from": "status-created.next", "to": "end"},
                    {"from": "route-method.otherwise", "to": "read-order"},
                    {"from": "read-order.ok", "to": "end"},
                    {"from": "read-order.not_found", "to": "body-missing"},
                    {"from": "read-order.invalid", "to": "status-bad"},
                    {"from": "body-missing.next", "to": "status-missing"},
                    {"from": "status-missing.next", "to": "end"},
                    {"from": "status-bad.next", "to": "end"}
                  ]
                }
                """;
    }

    private static String databaseCliProject() {
        return """
                {
                  "format": 1,
                  "id": "database-cli-app",
                  "nodes": [
                    {"id": "app", "use": "railix.app", "inputs": {}},
                    {
                      "id": "cli",
                      "use": "railix.trigger.cli",
                      "inputs": {},
                      "examples": [{"name": "default", "payload": ["get"]}]
                    },
                    {
                      "id": "route",
                      "use": "railix.switch",
                      "inputs": {
                        "cases": [
                          {
                            "outcome": "do-upsert",
                            "option": "field",
                            "inputs": {"field": ["context", "payload", "arguments", 0]},
                            "when": {
                              "transforms": [],
                              "all": [[{"use": "value.equals", "inputs": {"expected": "upsert"}}]]
                            }
                          },
                          {
                            "outcome": "do-bulk",
                            "option": "field",
                            "inputs": {"field": ["context", "payload", "arguments", 0]},
                            "when": {
                              "transforms": [],
                              "all": [[{"use": "value.equals", "inputs": {"expected": "bulk"}}]]
                            }
                          },
                          {
                            "outcome": "do-delete",
                            "option": "field",
                            "inputs": {"field": ["context", "payload", "arguments", 0]},
                            "when": {
                              "transforms": [],
                              "all": [[{"use": "value.equals", "inputs": {"expected": "delete"}}]]
                            }
                          }
                        ]
                      }
                    },
                    {
                      "id": "prepare-doc",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "payload", "doc"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": {"_id": "ord-1", "total": 99999.12345, "active": true}},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "db-upsert",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "orders",
                        "operation": {
                          "option": "upsert",
                          "inputs": {
                            "document": ["context", "payload", "doc"],
                            "auto_key": false
                          }
                        },
                        "target": ["context", "result"]
                      }
                    },
                    {
                      "id": "prepare-batch",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "payload", "batch"],
                        "value": [{
                          "option": "literal",
                          "inputs": {
                            "literal": [
                              {"_id": "ord-1", "total": 250.75, "active": false},
                              {"_id": "ord-2", "total": 10.5, "active": true}
                            ]
                          },
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "db-bulk",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "orders",
                        "operation": {
                          "option": "bulk_upsert",
                          "inputs": {
                            "documents": ["context", "payload", "batch"],
                            "auto_key": false
                          }
                        },
                        "target": ["context", "result"]
                      }
                    },
                    {
                      "id": "prepare-key",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "payload", "lookup_id"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": "ord-1"},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "db-get",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "orders",
                        "operation": {
                          "option": "get",
                          "inputs": {"key": ["context", "payload", "lookup_id"]}
                        },
                        "target": ["context", "result"]
                      }
                    },
                    {
                      "id": "prepare-del-key",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "payload", "lookup_id"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": "ord-1"},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "db-delete",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "orders",
                        "operation": {
                          "option": "delete",
                          "inputs": {"key": ["context", "payload", "lookup_id"]}
                        },
                        "target": ["context", "result"]
                      }
                    },
                    {
                      "id": "mark-missing",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "result"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": "not_found"},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "status-missing",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "exit_code"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": 44},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    }
                  ],
                  "links": [
                    {"from": "app.start", "to": "cli"},
                    {"from": "cli.next", "to": "route"},
                    {"from": "route.do-upsert", "to": "prepare-doc"},
                    {"from": "prepare-doc.next", "to": "db-upsert"},
                    {"from": "db-upsert.ok", "to": "end"},
                    {"from": "db-upsert.not_found", "to": "mark-missing"},
                    {"from": "db-upsert.invalid", "to": "end"},
                    {"from": "route.do-bulk", "to": "prepare-batch"},
                    {"from": "prepare-batch.next", "to": "db-bulk"},
                    {"from": "db-bulk.ok", "to": "end"},
                    {"from": "db-bulk.not_found", "to": "mark-missing"},
                    {"from": "db-bulk.invalid", "to": "end"},
                    {"from": "route.do-delete", "to": "prepare-del-key"},
                    {"from": "prepare-del-key.next", "to": "db-delete"},
                    {"from": "db-delete.ok", "to": "end"},
                    {"from": "db-delete.not_found", "to": "mark-missing"},
                    {"from": "db-delete.invalid", "to": "end"},
                    {"from": "route.otherwise", "to": "prepare-key"},
                    {"from": "prepare-key.next", "to": "db-get"},
                    {"from": "db-get.ok", "to": "end"},
                    {"from": "db-get.not_found", "to": "mark-missing"},
                    {"from": "db-get.invalid", "to": "end"},
                    {"from": "mark-missing.next", "to": "status-missing"},
                    {"from": "status-missing.next", "to": "end"}
                  ]
                }
                """;
    }

    private static String databaseFindCliProject() {
        return """
                {
                  "format": 1,
                  "id": "db-find-app",
                  "nodes": [
                    {"id": "app", "use": "railix.app", "inputs": {}},
                    {
                      "id": "cli",
                      "use": "railix.trigger.cli",
                      "inputs": {},
                      "examples": [{
                        "name": "example-find",
                        "payload": ["find"]
                      }]
                    },
                    {
                      "id": "route",
                      "use": "railix.switch",
                      "inputs": {
                        "cases": [
                          {
                            "option": "field",
                            "inputs": {"field": ["context", "payload", "arguments", 0]},
                            "when": {
                              "transforms": [],
                              "all": [[{"use": "value.equals", "inputs": {"expected": "add-1"}}]]
                            },
                            "outcome": "do-add-1"
                          },
                          {
                            "option": "field",
                            "inputs": {"field": ["context", "payload", "arguments", 0]},
                            "when": {
                              "transforms": [],
                              "all": [[{"use": "value.equals", "inputs": {"expected": "add-dup"}}]]
                            },
                            "outcome": "do-add-dup"
                          },
                          {
                            "option": "field",
                            "inputs": {"field": ["context", "payload", "arguments", 0]},
                            "when": {
                              "transforms": [],
                              "all": [[{"use": "value.equals", "inputs": {"expected": "add-2"}}]]
                            },
                            "outcome": "do-add-2"
                          }
                        ]
                      }
                    },
                    {
                      "id": "prepare-doc-1",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "payload", "data"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": {"code": "PR-1", "category": "gadget", "price": 50}},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "prepare-doc-dup",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "payload", "data"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": {"code": "PR-1", "category": "gadget", "price": 99}},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "prepare-doc-2",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "payload", "data"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": {"code": "PR-2", "category": "gadget", "price": 75}},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "prepare-filter",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "payload", "data"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": {"category": "gadget"}},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "db-add",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "items",
                        "unique_fields": ["code"],
                        "index_fields": ["category"],
                        "operation": {
                          "option": "upsert",
                          "inputs": {
                            "document": ["context", "payload", "data"],
                            "auto_key": true
                          }
                        },
                        "target": ["context", "result"]
                      }
                    },
                    {
                      "id": "db-find",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "items",
                        "unique_fields": ["code"],
                        "index_fields": ["category"],
                        "operation": {
                          "option": "find",
                          "inputs": {
                            "filter": ["context", "payload", "data"],
                            "fields": ["code", "price"],
                            "defaults": {"discount": 5}
                          }
                        },
                        "target": ["context", "result"]
                      }
                    },
                    {
                      "id": "mark-conflict",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "result"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": "conflict_rejected"},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    },
                    {
                      "id": "status-conflict",
                      "use": "railix.field-manipulation",
                      "inputs": {
                        "field": ["context", "exit_code"],
                        "value": [{
                          "option": "literal",
                          "inputs": {"literal": 49},
                          "when": {"transforms": [], "all": []}
                        }],
                        "steps": []
                      }
                    }
                  ],
                  "links": [
                    {"from": "app.start", "to": "cli"},
                    {"from": "cli.next", "to": "route"},
                    {"from": "route.do-add-1", "to": "prepare-doc-1"},
                    {"from": "prepare-doc-1.next", "to": "db-add"},
                    {"from": "route.do-add-dup", "to": "prepare-doc-dup"},
                    {"from": "prepare-doc-dup.next", "to": "db-add"},
                    {"from": "route.do-add-2", "to": "prepare-doc-2"},
                    {"from": "prepare-doc-2.next", "to": "db-add"},
                    {"from": "route.otherwise", "to": "prepare-filter"},
                    {"from": "prepare-filter.next", "to": "db-find"},
                    {"from": "db-add.ok", "to": "end"},
                    {"from": "db-add.not_found", "to": "end"},
                    {"from": "db-add.invalid", "to": "mark-conflict"},
                    {"from": "db-find.ok", "to": "end"},
                    {"from": "db-find.not_found", "to": "end"},
                    {"from": "db-find.invalid", "to": "end"},
                    {"from": "mark-conflict.next", "to": "status-conflict"},
                    {"from": "status-conflict.next", "to": "end"}
                  ]
                }
                """;
    }

    private static String rollingCreatorProject(final boolean readOnly) {
        if (!readOnly) {
            return """
                    {
                      "format": 1,
                      "id": "rolling-db",
                      "nodes": [
                        {"id": "app", "use": "railix.app", "inputs": {}},
                        {
                          "id": "cli",
                          "use": "railix.trigger.cli",
                          "inputs": {},
                          "examples": [{
                            "name": "seed-account",
                            "payload": [],
                            "context": {"payload": {"account": {"_id": "acct-7", "balance": 500.25}}}
                          }]
                        },
                        {
                          "id": "save-account",
                          "use": "railix.database",
                          "inputs": {
                            "collection": "accounts",
                            "operation": {
                              "option": "upsert",
                              "inputs": {
                                "document": ["context", "payload", "account"],
                                "auto_key": false
                              }
                            },
                            "target": ["context", "result"]
                          }
                        }
                      ],
                      "links": [
                        {"from": "app.start", "to": "cli"},
                        {"from": "cli.next", "to": "save-account"},
                        {"from": "save-account.ok", "to": "end"},
                        {"from": "save-account.not_found", "to": "end"},
                        {"from": "save-account.invalid", "to": "end"}
                      ]
                    }
                    """;
        }
        return """
                {
                  "format": 1,
                  "id": "rolling-db",
                  "nodes": [
                    {"id": "app", "use": "railix.app", "inputs": {}},
                    {
                      "id": "cli",
                      "use": "railix.trigger.cli",
                      "inputs": {},
                      "examples": [{
                        "name": "read-account",
                        "payload": [],
                        "context": {"payload": {"id": "acct-7"}}
                      }]
                    },
                    {
                      "id": "load-account",
                      "use": "railix.database",
                      "inputs": {
                        "collection": "accounts",
                        "operation": {
                          "option": "get",
                          "inputs": {"key": ["context", "payload", "id"]}
                        },
                        "target": ["context", "result"]
                      }
                    }
                  ],
                  "links": [
                    {"from": "app.start", "to": "cli"},
                    {"from": "cli.next", "to": "load-account"},
                    {"from": "load-account.ok", "to": "end"},
                    {"from": "load-account.not_found", "to": "end"},
                    {"from": "load-account.invalid", "to": "end"}
                  ]
                }
                """;
    }

    private static Path writeProject(final Path workspace, final String source) throws IOException {
        Files.createDirectories(workspace);
        final Path project = workspace.resolve("railix.project.json");
        Files.writeString(project, source, StandardCharsets.UTF_8);
        return project;
    }

    private static ApplicationBuilder.Artifact productionArtifact(
            final Path project,
            final String source
    ) throws IOException {
        return productionArtifact(project, source, StandardLibrary.catalog());
    }

    private static ApplicationBuilder.Artifact productionArtifact(
            final Path project,
            final String source,
            final StepCatalog catalog
    ) throws IOException {
        final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) result);
    }

    private static ProcessResult runJar(final Path jar, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(arguments));
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(command))
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            final var output = reader.submit(() ->
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Generated application timed out: " + jar);
            }
            return new ProcessResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        }
    }

    private static RailixValue parseJson(final String text) {
        final RailixJson.Result parsed = RailixJson.parse(text);
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return ((RailixJson.Parsed) parsed).value();
    }

    private static RailixValue exampleResult(final URI baseUri, final String id) throws Exception {
        final HttpResponse<String> response = creatorRequest(baseUri, "GET", "/api/examples/" + id + "/view", "");
        assertThat(response.statusCode()).isEqualTo(200);
        final RailixValue.ObjectValue view = (RailixValue.ObjectValue) parseJson(response.body());
        final RailixValue.ObjectValue result = (RailixValue.ObjectValue) view.values().get("result");
        final RailixValue.ObjectValue context = (RailixValue.ObjectValue) result.values().get("context");
        return context.values().get("result");
    }

    private static RailixValue.ObjectValue awaitExamples(final URI baseUri, final long completed) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        RailixValue.ObjectValue examples;
        do {
            examples = CreatorServerE2eSupport.examples(baseUri);
            if (number(examples, "completed") == completed) {
                return examples;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Examples did not complete: " + examples);
    }

    private static RailixValue.ObjectValue application(final URI baseUri) throws Exception {
        return (RailixValue.ObjectValue) parseJson(creatorRequest(baseUri, "GET", "/api/application", "").body());
    }

    private static HttpResponse<String> creatorRequest(
            final URI baseUri,
            final String method,
            final String path,
            final String body
    ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("X-Railix-Creator-Token", baseUri.getRawFragment().substring("token=".length()))
                .method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static long number(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.NumberValue) object.values().get(field)).value().longValueExact();
    }

    private static String string(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.StringValue) object.values().get(field)).value();
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
