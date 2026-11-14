package dev.nanonative.railix.creator;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class RailixCreatorDatabaseBrowserIT extends RailixCreatorBrowserSupport {

    @Test
    @Tag("responsive")
    void branchHeavyDatabaseProjectSupportsExamplesReuseValidationAndResponsiveLayouts() throws Exception {
        openProject(branchHeavyDatabaseProject());
        page.evaluate("() => void state.world.fit()");
        awaitScene();

        assertThat(branchOutcomes()).contains("upsert", "bulk", "delete", "otherwise", "ok", "not_found", "invalid");

        selectWorldNode("db-upsert");
        PlaywrightAssertions.assertThat(page.locator("#collection-value")).hasValue("orders");
        PlaywrightAssertions.assertThat(page.locator("#directory-value")).hasValue(".railix/data");
        PlaywrightAssertions.assertThat(page.locator("#key-field-value")).hasValue("_id");
        PlaywrightAssertions.assertThat(page.locator("#operation-option")).hasValue("upsert");
        assertThat(page.locator("#operation-option option").allTextContents()).hasSize(4);
        PlaywrightAssertions.assertThat(page.locator("#target-path")).isVisible();

        chooseVisibleExample("command:0");
        openInspectorTab("overview");
        assertThat(page.locator("#selection-overview").textContent())
                .contains("Ok", "Not found", "Invalid");

        openInspectorTab("inspect");
        page.locator("#collection-value").fill("../invalid");
        page.locator("#collection-value").press("Tab");
        PlaywrightAssertions.assertThat(page.locator("#collection-error"))
                .containsText("Collection must start and end with a lowercase ASCII letter or digit");

        page.locator("#collection-value").fill("orders");
        page.locator("#collection-value").press("Tab");
        waitForText("#build-state", "Built");
        PlaywrightAssertions.assertThat(page.locator("#collection-error")).hasCount(0);

        final Path screenshotDir = Files.createDirectories(Path.of("target", "screenshots"));
        if (page.viewportSize().width >= 1000) {
            page.setViewportSize(1280, 800);
            page.evaluate("() => void state.world.fit()");
            awaitScene();
            selectWorldNode("db-upsert");
            assertNoHorizontalOverflow();
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotDir.resolve("creator-database-desktop.png")));

            page.setViewportSize(390, 844);
            page.evaluate("() => void state.world.fit()");
            awaitScene();
            selectWorldNode("db-upsert");
            assertNoHorizontalOverflow();
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotDir.resolve("creator-database-narrow.png")));
        } else {
            assertNoHorizontalOverflow();
        }

        assertThat(pageErrors).isEmpty();
    }

    private void assertNoHorizontalOverflow() {
        final Boolean fits = (Boolean) page.evaluate(
                "() => document.documentElement.scrollWidth <= window.innerWidth + 1"
        );
        assertThat(fits).isTrue();
    }

    private static String branchHeavyDatabaseProject() {
        return """
            {
              "format": 1,
              "id": "orders-persistence-service",
              "nodes": [
                {"id": "app", "use": "railix.app", "inputs": {}},
                {
                  "id": "command",
                  "use": "railix.trigger.cli",
                  "inputs": {},
                  "examples": [
                    {
                      "name": "seed-order",
                      "payload": ["upsert"],
                      "context": {
                        "payload": {
                          "doc": {"_id": "ord-101", "customer": "Ada", "total": 199.95},
                          "batch": [
                            {"_id": "ord-101", "customer": "Ada", "total": 199.95},
                            {"_id": "ord-102", "customer": "Grace", "total": 84.5}
                          ],
                          "lookup_id": "ord-101"
                        }
                      }
                    },
                    {
                      "name": "batch-orders",
                      "payload": ["bulk"],
                      "context": {
                        "payload": {
                          "batch": [
                            {"_id": "ord-201", "customer": "Linus", "total": 42.0},
                            {"_id": "ord-202", "customer": "Margaret", "total": 310.25}
                          ]
                        }
                      }
                    },
                    {
                      "name": "lookup-missing",
                      "payload": ["get"],
                      "context": {"payload": {"lookup_id": "ord-missing"}}
                    },
                    {
                      "name": "invalid-document",
                      "payload": ["upsert"],
                      "context": {"payload": {"doc": "not-an-object"}}
                    }
                  ]
                },
                {
                  "id": "route-op",
                  "use": "railix.branch",
                  "inputs": {
                    "routes": [
                      {
                        "outcome": "upsert",
                        "inputs": {},
                        "groups": [[{
                          "option": "field",
                          "inputs": {"field": ["context", "payload", "arguments", 0]},
                          "when": {
                            "transforms": [],
                            "all": [[{"use": "value.equals", "inputs": {"expected": "upsert"}}]]
                          }
                        }]]
                      },
                      {
                        "outcome": "bulk",
                        "inputs": {},
                        "groups": [[{
                          "option": "field",
                          "inputs": {"field": ["context", "payload", "arguments", 0]},
                          "when": {
                            "transforms": [],
                            "all": [[{"use": "value.equals", "inputs": {"expected": "bulk"}}]]
                          }
                        }]]
                      },
                      {
                        "outcome": "delete",
                        "inputs": {},
                        "groups": [[{
                          "option": "field",
                          "inputs": {"field": ["context", "payload", "arguments", 0]},
                          "when": {
                            "transforms": [],
                            "all": [[{"use": "value.equals", "inputs": {"expected": "delete"}}]]
                          }
                        }]]
                      }
                    ]
                  }
                },
                {
                  "id": "db-upsert",
                  "use": "railix.database",
                  "inputs": {
                    "collection": "orders",
                    "directory": ".railix/data",
                    "key_field": "_id",
                    "operation": {
                      "option": "upsert",
                      "inputs": {
                        "document": ["context", "payload", "doc"],
                        "auto_key": true
                      }
                    },
                    "target": ["context", "result"]
                  }
                },
                {
                  "id": "db-bulk",
                  "use": "railix.database",
                  "inputs": {
                    "collection": "orders",
                    "directory": ".railix/data",
                    "key_field": "_id",
                    "operation": {
                      "option": "upsert",
                      "inputs": {
                        "document": ["context", "payload", "batch"],
                        "auto_key": true
                      }
                    },
                    "target": ["context", "result"]
                  }
                },
                {
                  "id": "db-get",
                  "use": "railix.database",
                  "inputs": {
                    "collection": "orders",
                    "directory": ".railix/data",
                    "key_field": "_id",
                    "operation": {
                      "option": "get",
                      "inputs": {"key": ["context", "payload", "lookup_id"]}
                    },
                    "target": ["context", "result"]
                  }
                },
                {
                  "id": "db-delete",
                  "use": "railix.database",
                  "inputs": {
                    "collection": "orders",
                    "directory": ".railix/data",
                    "key_field": "_id",
                    "operation": {
                      "option": "delete",
                      "inputs": {"key": ["context", "payload", "lookup_id"]}
                    },
                    "target": ["context", "result"]
                  }
                }
              ],
              "links": [
                {"from": "app.start", "to": "command"},
                {"from": "command.next", "to": "route-op"},
                {"from": "route-op.upsert", "to": "db-upsert"},
                {"from": "route-op.bulk", "to": "db-bulk"},
                {"from": "route-op.delete", "to": "db-delete"},
                {"from": "route-op.otherwise", "to": "db-get"},
                {"from": "db-upsert.ok", "to": "end"},
                {"from": "db-upsert.not_found", "to": "end"},
                {"from": "db-upsert.invalid", "to": "end"},
                {"from": "db-bulk.ok", "to": "end"},
                {"from": "db-bulk.not_found", "to": "end"},
                {"from": "db-bulk.invalid", "to": "end"},
                {"from": "db-get.ok", "to": "end"},
                {"from": "db-get.not_found", "to": "end"},
                {"from": "db-get.invalid", "to": "end"},
                {"from": "db-delete.ok", "to": "end"},
                {"from": "db-delete.not_found", "to": "end"},
                {"from": "db-delete.invalid", "to": "end"}
              ]
            }
            """;
    }
}