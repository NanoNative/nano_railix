package dev.nanonative.railix.creator;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.nanonative.railix.core.runtime.ObservationServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(120)
final class LiveApplicationE2eTest {
    @TempDir Path directory;

    @Test
    void meshStatusLeavesBothLiveMetricReadSlotsAvailable() throws Exception {
        final var statusEntered = new CountDownLatch(1);
        final var metricsEntered = new CountDownLatch(2);
        final var release = new CountDownLatch(1);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(threads);
            server.createContext("/v1/status", exchange -> heldResponse(exchange, statusEntered, release,
                    "{\"mesh\":{\"status\":\"waiting\"}}"));
            server.createContext("/v1/metrics/application",
                    exchange -> heldResponse(exchange, metricsEntered, release, "{}"));
            server.start();
            final var process = ProcessHandle.current();
            final var connection = new ObservationServer.Connection("live", process.pid(),
                    process.info().startInstant().orElseThrow(), server.getAddress().getPort(), "test-token",
                    "revision", "digest", directory.resolve("application.jar"));
            final var project = new ProjectFiles.Snapshot(CreatorProjects.grouping(), RailixValue.object(Map.of()),
                    GeneratedApplicationFixture.catalog());
            try (var live = new LiveApplication(connection, project, HttpClient.newHttpClient())) {
                final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                final var status = threads.submit(() -> live.runtimeSnapshot(deadline));
                try {
                    assertThat(statusEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    final var first = threads.submit(() -> live.read("/v1/metrics/application", null, deadline));
                    final var second = threads.submit(() -> live.read("/v1/metrics/application", null, deadline));
                    assertThat(metricsEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    release.countDown();
                    assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
                    assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
                    assertThat(status.get(5, TimeUnit.SECONDS).values().get("mesh")).isInstanceOf(RailixValue.ObjectValue.class);
                } finally {
                    release.countDown();
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private static void heldResponse(final HttpExchange exchange, final CountDownLatch entered,
                                     final CountDownLatch release, final String body) throws IOException {
        try (exchange) {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) {
                return;
            }
            final byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void ordinaryJarPublishesOnlyAuthenticatedReadAccessAndRemovesDiscoveryOnExit() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        final int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Files.writeString(project, """
                {"format":1,"id":"live-shop","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"api","use":"railix.trigger.http","inputs":{"port":%d},
                   "examples":[{"name":"request","payload":{}}]}
                ],"links":[{"from":"app.start","to":"api"},{"from":"api.next","to":"end"}]}
                """.formatted(port));
        final var source = ProjectFiles.build(project, directory.resolve("home/.railix"), true);
        assertThat(source.result()).isInstanceOf(dev.nanonative.railix.core.project.CompileResult.Compiled.class);
        final Path jar = ApplicationBuilder.buildProduction(project, source).jar();
        final Process app = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Duser.home=" + directory.resolve("home"), "-jar", jar.toString()))
                .redirectErrorStream(true).redirectOutput(directory.resolve("app.log").toFile()).start();
        final Path discovery = directory.resolve("home/.railix/observations");
        try (var client = HttpClient.newHttpClient()) {
            await(() -> {
                assertThat(app.isAlive()).as(Files.readString(directory.resolve("app.log"))).isTrue();
                assertThat(ObservationServer.connections(directory.resolve("home/.railix"))).hasSize(1);
            });
            final Path record=discovery.resolve(ObservationServer.connections(directory.resolve("home/.railix"))
                    .getFirst().instance()+".json");
            final var connection = JsonParser.parseString(Files.readString(record)).getAsJsonObject();
            final URI base = URI.create("http://127.0.0.1:" + connection.get("port").getAsInt());
            final String credential = "Bearer " + connection.get("token").getAsString();
            final var denied = client.send(HttpRequest.newBuilder(base.resolve("/v1/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
            for (final String path : List.of("/v1/run", "/v1/trace", "/v1/stop", "/v1/examples")) {
                final var response = client.send(HttpRequest.newBuilder(base.resolve(path)).header("Authorization", credential)
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(404);
            }
            final var identity = client.send(HttpRequest.newBuilder(base.resolve("/v1/application"))
                    .header("Authorization", credential).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(identity.statusCode()).isEqualTo(200);
            assertThat(identity.body()).contains(connection.get("instance").getAsString(), "sha256-")
                    .doesNotContain(connection.get("token").getAsString(), "\"nodes\"", "\"links\"");
            final var listener = client.send(HttpRequest.newBuilder(base.resolve("/v1/metrics/nodes/api"))
                    .header("Authorization", credential).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(listener.statusCode()).as(listener.body()).isEqualTo(200);
            final int activePort = JsonParser.parseString(listener.body()).getAsJsonObject().getAsJsonArray("steps")
                    .get(0).getAsJsonObject().getAsJsonObject("metrics").get("railix.trigger.http:active_port").getAsInt();
            await(() -> {
                final var result = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + activePort + "/"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(result.statusCode()).isEqualTo(200);
            });
            final var metrics = client.send(HttpRequest.newBuilder(base.resolve("/v1/metrics"))
                    .header("Authorization", credential).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body()).contains("\"application_pid\":" + app.pid(), "\"executions\":1");
            app.destroy();
            assertThat(app.waitFor(35, TimeUnit.SECONDS)).isTrue();
            assertThat(record).doesNotExist();
        } finally { ProjectSourceArtifactsE2eTest.stop(app); }
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void creatorKeepsTheObservedBuildAndItsMetricsSeparateFromLocalChanges(final boolean include) throws Exception {
        final Path project=directory.resolve("railix.project.json"), home=directory.resolve("home/.railix");
        final String original=CreatorProjects.grouping().replace("railix.trigger.cli","railix.trigger.http")
                .replace("\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{}", "\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{\"port\":0}")
                .replace("\"payload\":[]", "\"payload\":{}");
        Files.writeString(project,original);
        final var built=ProjectFiles.build(project,home,include);
        assertThat(built.result()).isInstanceOf(dev.nanonative.railix.core.project.CompileResult.Compiled.class);
        final Path artifact=Files.copy(ApplicationBuilder.buildProduction(project,built).jar(),directory.resolve("running.jar"));
        final Process app=start(artifact,directory.resolve("home"),directory.resolve("app.log"));
        try {
            await(()->assertThat(ObservationServer.connections(home)).hasSize(1));
            final var connection=ObservationServer.connections(home).getFirst();
            try (CreatorServer creator=CreatorServer.start(0,project,home)) {
                final URI uri=creator.baseUri();
                final var listing=CreatorServerE2eSupport.request(uri,"GET","/api/live","");
                assertThat(listing.body()).contains(connection.instance()).doesNotContain(connection.token());
                final var attached=CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\"}");
                assertThat(attached.statusCode()).as(attached.body()).isEqualTo(200);
                assertThat(attached.body()).contains("\"readonly\":true").doesNotContain(connection.token());
                assertThat(readLive(uri,"/api/live/editor",connection.instance()).statusCode()).isEqualTo(409);
                final long localPid=JsonParser.parseString(CreatorServerE2eSupport.request(uri,"GET","/api/application","").body()).getAsJsonObject().get("pid").getAsLong();
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\",\"confirm\":true}").statusCode()).isEqualTo(200);
                assertThat(ProcessHandle.of(localPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
                final var ready=CreatorServerE2eSupport.request(uri,"GET","/api/application","");
                assertThat(ready.body()).contains("\"state\":\"ready\"", "\"pid\":0");
                final var before=readLive(uri,"/api/live/editor?node=lowercase-text",connection.instance());
                assertThat(before.statusCode()).as(before.body()).isEqualTo(200);
                final var scene=JsonParser.parseString(readLive(uri,"/api/live/scene",connection.instance()).body()).getAsJsonObject();
                final String query="/api/live/scene/observations?revision="+scene.get("revision").getAsString();
                final var observation=readLive(uri,query,connection.instance());
                assertThat(observation.statusCode()).as(observation.body()).isEqualTo(200);
                assertThat(observation.body()).contains("\"application_pid\":"+app.pid()).doesNotContain("coverage_revision");
                final var edit=CreatorServerE2eSupport.request(uri,"PATCH","/api/project","{\"revision\":0,\"changes\":{\"nodes\":{\"return-text\":null},\"links\":{\"lowercase-text.next\":[{\"from\":\"lowercase-text.next\",\"to\":\"end\"}],\"return-text.next\":null}}}");
                assertThat(edit.statusCode()).as(edit.body()).isEqualTo(200);
                assertThat(edit.body()).contains("\"state\":\"ready\"");
                final var differences=readLive(uri,"/api/live/changes",connection.instance());
                assertThat(differences.statusCode()).isEqualTo(200);
                assertThat(differences.body()).contains("\"state\":\"removed\"","\"state\":\"changed\"");
                final var after=readLive(uri,"/api/live/editor?node=return-text",connection.instance());
                assertThat(after.statusCode()).as(after.body()).isEqualTo(200);
                assertThat(after.body()).contains("return-text");
                final long revision=JsonParser.parseString(edit.body()).getAsJsonObject().get("revision").getAsLong();
                final var restored=editLive(uri,"{\"revision\":"+revision+",\"creator_revision\":0,\"command\":{\"kind\":\"restore\",\"node\":\"return-text\"}}",connection.instance());
                assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
                assertThat(readLive(uri,"/api/live/changes",connection.instance()).body()).isEqualTo("{}");
                final long restoredRevision=JsonParser.parseString(restored.body()).getAsJsonObject().get("revision").getAsLong();
                final var undone=CreatorServerE2eSupport.request(uri,"POST","/api/editor","{\"revision\":"+restoredRevision+",\"creator_revision\":0,\"command\":{\"kind\":\"undo\"}}");
                assertThat(undone.statusCode()).as(undone.body()).isEqualTo(200);
                assertThat(readLive(uri,"/api/live/changes",connection.instance()).body()).contains("removed");
                for(final String path:List.of("/api/live/project","/api/live/editor","/api/live/application"))
                    assertThat(CreatorServerE2eSupport.request(uri,"POST",path,"{}").statusCode()).isEqualTo(405);
                final var stillObserved=readLive(uri,query,connection.instance());
                assertThat(stillObserved.statusCode()).as(stillObserved.body()).isEqualTo(200);
                assertThat(stillObserved.body()).contains("\"application_pid\":"+app.pid());
                assertThat(app.isAlive()).isTrue();
            }
            assertThat(app.isAlive()).isTrue();
            assertThat(ObservationServer.connections(home)).hasSize(1);
        } finally {ProjectSourceArtifactsE2eTest.stop(app);}
    }

    @Test
    void connectedDraftBuildsWithoutOpeningPortsAndAnExplicitStartKeepsTheLiveAppUntouched() throws Exception {
        final Path project=directory.resolve("railix.project.json"),home=directory.resolve("home/.railix");
        final int port;
        try(var socket=new ServerSocket(0)) {port=socket.getLocalPort();}
        final String original="""
                {"format":1,"id":"live-start","nodes":[
                {"id":"app","use":"railix.app","inputs":{}},
                {"id":"api","use":"railix.trigger.http","inputs":{"port":%d,"next_free_port":false},
                 "examples":[{"name":"request","payload":{}}]}],
                "links":[{"from":"app.start","to":"api"},{"from":"api.next","to":"end"}]}
                """.formatted(port);
        Files.writeString(project,original);
        final Path jar=Files.copy(ApplicationBuilder.buildProduction(project,ProjectFiles.build(project,home,true)).jar(),directory.resolve("live.jar"));
        Files.writeString(project,original.replace("\"port\":"+port,"\"port\":0"));
        final Process observed=start(jar,directory.resolve("home"),directory.resolve("live.log"));
        try {
            await(()->assertThat(ObservationServer.connections(home)).hasSize(1));
            final var connection=ObservationServer.connections(home).getFirst();
            try(CreatorServer creator=CreatorServer.start(0,project,home);var client=HttpClient.newHttpClient()) {
                final URI uri=creator.baseUri();
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\"}").statusCode()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\",\"confirm\":true}").statusCode()).isEqualTo(200);
                final var occupied=CreatorServerE2eSupport.request(uri,"POST","/api/project",original);
                assertThat(occupied.statusCode()).as(occupied.body()).isEqualTo(200);
                assertThat(occupied.body()).contains("\"state\":\"ready\"", "\"pid\":0");
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/application","{}").statusCode()).isEqualTo(422);
                assertThat(CreatorServerE2eSupport.request(uri,"GET","/api/examples/status","").statusCode()).isEqualTo(503);
                assertThat(CreatorServerE2eSupport.request(uri,"GET","/api/metrics","").statusCode()).isEqualTo(503);
                final var occupiedRevision=JsonParser.parseString(occupied.body()).getAsJsonObject().get("revision").getAsLong();
                final var failed=CreatorServerE2eSupport.request(uri,"POST","/api/application","{\"action\":\"start\",\"revision\":"+occupiedRevision+",\"creator_revision\":0}");
                assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
                assertThat(failed.body()).contains("\"state\":\"ready\"");
                assertThat(failed.body()).contains("Address already in use", "api");
                final var free=CreatorServerE2eSupport.request(uri,"POST","/api/project",original.replace("\"port\":"+port,"\"port\":0"));
                assertThat(free.statusCode()).as(free.body()).isEqualTo(200);
                final var before=JsonParser.parseString(free.body()).getAsJsonObject();
                assertThat(before.getAsJsonObject("application").get("state").getAsString()).isEqualTo("ready");
                final String start="{\"action\":\"start\",\"revision\":"+before.get("revision")+",\"creator_revision\":0}";
                final var stale=CreatorServerE2eSupport.request(uri,"POST","/api/application","{\"action\":\"start\",\"revision\":0,\"creator_revision\":0}");
                assertThat(stale.statusCode()).isEqualTo(409);
                final var started=CreatorServerE2eSupport.request(uri,"POST","/api/application",start);
                assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
                final var after=JsonParser.parseString(started.body()).getAsJsonObject();
                assertThat(after.get("revision")).isEqualTo(before.get("revision"));
                assertThat(after.get("history")).isEqualTo(before.get("history"));
                final long pid=after.getAsJsonObject("application").get("pid").getAsLong();
                assertThat(pid).isPositive().isNotEqualTo(observed.pid());
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/application",start).body()).contains("\"pid\":"+pid);
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\",\"confirm\":true}").statusCode()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.request(uri,"GET","/api/application","").body()).contains("\"pid\":"+pid);
                await(()->assertThat(CreatorServerE2eSupport.request(uri,"GET","/api/examples/status","").body()).contains("\"state\":\"completed\""));
                assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"))
                        .timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                assertThat(readLive(uri,"/api/live/application",connection.instance()).body()).contains("\"pid\":"+observed.pid());
            }
            assertThat(observed.isAlive()).isTrue();
        } finally {ProjectSourceArtifactsE2eTest.stop(observed);}
    }

    @Test
    void aFailedPausedEditReleasesItsUnusedBuildAndPreservesOutsideChanges() throws Exception {
        final Path project=directory.resolve("railix.project.json"),home=directory.resolve("home/.railix");
        Files.writeString(project,CreatorProjects.grouping().replace("railix.trigger.cli","railix.trigger.http")
                .replace("\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{}", "\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{\"port\":0}")
                .replace("\"payload\":[]","\"payload\":{}"));
        final var jar=Files.copy(ApplicationBuilder.buildProduction(project,ProjectFiles.build(project,home,true)).jar(),directory.resolve("live.jar"));
        final Process observed=start(jar,directory.resolve("home"),directory.resolve("live.log"));
        try {
            await(()->assertThat(ObservationServer.connections(home)).hasSize(1));
            final var connection=ObservationServer.connections(home).getFirst();
            try(CreatorServer creator=CreatorServer.start(0,project,home);
                var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                final URI uri=creator.baseUri();
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\"}").statusCode()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\",\"confirm\":true}").statusCode()).isEqualTo(200);
                final String original=Files.readString(project);
                final var changed=JsonParser.parseString(original).getAsJsonObject();
                changed.addProperty("id","paused-build");
                for(final var link:changed.getAsJsonArray("links"))
                    if(link.getAsJsonObject().get("from").getAsString().equals("lowercase-text.next"))
                        link.getAsJsonObject().addProperty("to","extra-0");
                for(int index=0;index<512;index++) {
                    changed.getAsJsonArray("nodes").add(JsonParser.parseString(
                            "{\"id\":\"extra-"+index+"\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}"));
                    changed.getAsJsonArray("links").add(JsonParser.parseString("{\"from\":\"extra-"+index
                            +".next\",\"to\":\""+(index==511?"return-text":"extra-"+(index+1))+"\"}"));
                }
                final Path builds=directory.resolve(".railix/build");
                final var pending=executor.submit(()->CreatorServerE2eSupport.request(uri,"POST","/api/project",changed.toString()));
                final var rejected=new java.util.concurrent.atomic.AtomicReference<Path>();
                await(()->{
                    try(var paths=Files.list(builds)) {
                        final var staging=paths.filter(path->path.getFileName().toString().startsWith(".building-")).findFirst();
                        assertThat(staging).as("The real build has entered staging: %s",pending.isDone()?pending.get().body():"pending").isPresent();
                        final String name=staging.orElseThrow().getFileName().toString();
                        rejected.set(builds.resolve(name.substring(name.lastIndexOf("sha256-"))));
                    }
                });
                final var outside=JsonParser.parseString(original).getAsJsonObject();
                outside.addProperty("id","outside-editor");
                Files.writeString(project,outside.toString());
                final var response=pending.get(45,TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                assertThat(response.body()).contains("\"state\":\"ready\"");
                assertThat(Files.readString(project)).isEqualTo(outside.toString());
                assertThat(rejected.get()).doesNotExist();
                assertThat(observed.isAlive()).isTrue();
            }
        } finally {ProjectSourceArtifactsE2eTest.stop(observed);}
    }

    @Test
    void oldCrashRecordsAndUnreadableCredentialsDoNotExposeOrBlockLiveApps() throws Exception {
        final Path project=directory.resolve("railix.project.json"),home=directory.resolve("home/.railix");
        Files.writeString(project,CreatorProjects.grouping().replace("railix.trigger.cli","railix.trigger.http")
                .replace("\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{}", "\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{\"port\":0}")
                .replace("\"payload\":[]","\"payload\":{}"));
        final var artifact=ApplicationBuilder.buildProduction(project,ProjectFiles.build(project,home,false)).jar();
        final Process app=start(artifact,directory.resolve("home"),directory.resolve("app.log"));
        try {
            await(()->assertThat(ObservationServer.connections(home)).hasSize(1));
            final var connection=ObservationServer.connections(home).getFirst();
            final Path file=home.resolve("observations").resolve(connection.instance()+".json");
            final var original=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for(int index=0;index<520;index++) {
                final String id=java.util.UUID.randomUUID().toString();
                final var stale=original.deepCopy();stale.addProperty("instance",id);stale.addProperty("application_pid",0);
                final Path target=Files.createFile(file.resolveSibling(id+".json"),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                Files.writeString(target,stale.toString());
            }
            assertThat(ObservationServer.connections(home)).hasSize(1);
            Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            assertThat(ObservationServer.connections(home)).isEmpty();
            Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            assertThat(ObservationServer.connections(home)).hasSize(1);
        } finally {ProjectSourceArtifactsE2eTest.stop(app);}
    }

    @Test
    void observationOptOutKeepsCliExitAndExecutionUnchanged() throws Exception {
        final Path project=directory.resolve("railix.project.json");
        Files.writeString(project,CreatorProjects.nestedLowercase());
        final var source=ProjectFiles.build(project,directory.resolve("home/.railix"),false);
        final Path artifact=ApplicationBuilder.buildProduction(project,source,false).jar();
        final Process app=start(artifact,directory.resolve("home"),directory.resolve("app.log"));
        try {
            assertThat(app.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(app.exitValue()).isZero();
            assertThat(directory.resolve("home/.railix/observations")).doesNotExist();
        } finally {ProjectSourceArtifactsE2eTest.stop(app);}
    }

    @Test
    void unconfirmedConnectionLeavesTheSelectedInstanceAndItsEvidenceUnchanged() throws Exception {
        final Path project=directory.resolve("railix.project.json"),home=directory.resolve("home/.railix");
        Files.writeString(project,CreatorProjects.grouping().replace("railix.trigger.cli","railix.trigger.http")
                .replace("\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{}", "\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{\"port\":0}")
                .replace("\"payload\":[]","\"payload\":{}"));
        final var jar=Files.copy(ApplicationBuilder.buildProduction(project,ProjectFiles.build(project,home,true)).jar(),directory.resolve("running.jar"));
        final Process first=start(jar,directory.resolve("home"),directory.resolve("first.log"));
        final Process second=start(jar,directory.resolve("home"),directory.resolve("second.log"));
        try {
            await(()->assertThat(ObservationServer.connections(home)).hasSize(2));
            final var a=ObservationServer.connections(home).stream().filter(connection->connection.pid()==first.pid()).findFirst().orElseThrow();
            final var b=ObservationServer.connections(home).stream().filter(connection->connection.pid()==second.pid()).findFirst().orElseThrow();
            try(CreatorServer creator=CreatorServer.start(0,project,home)) {
                final URI uri=creator.baseUri();
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+a.instance()+"\"}").statusCode()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+a.instance()+"\",\"confirm\":true}").statusCode()).isEqualTo(200);
                assertThat(readLive(uri,"/api/live/editor",a.instance()).statusCode()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+b.instance()+"\"}").statusCode()).isEqualTo(200);
                final var retained=readLive(uri,"/api/live/application",a.instance());
                assertThat(retained.statusCode()).as(retained.body()).isEqualTo(200);
                assertThat(retained.body()).contains("\"pid\":"+first.pid());
                assertThat(readLive(uri,"/api/live/editor",b.instance()).statusCode()).isEqualTo(409);
                assertThat(readLive(uri,"/api/live/application",a.instance()).statusCode()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+b.instance()+"\",\"confirm\":true}").statusCode()).isEqualTo(200);
                assertThat(readLive(uri,"/api/live/editor",b.instance()).statusCode()).isEqualTo(200);
                assertThat(readLive(uri,"/api/live/application",a.instance()).statusCode()).isEqualTo(409);
                assertThat(editLive(uri,"{\"revision\":0,\"creator_revision\":0,\"command\":{\"kind\":\"restore\",\"node\":\"return-text\"}}",a.instance()).statusCode()).isEqualTo(409);
                final var selected=readLive(uri,"/api/live/application",b.instance());
                assertThat(selected.body()).contains("\"pid\":"+second.pid());
                assertThat(first.isAlive()).isTrue();assertThat(second.isAlive()).isTrue();
            }
        } finally {ProjectSourceArtifactsE2eTest.stop(first);ProjectSourceArtifactsE2eTest.stop(second);}
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void restoringADeletedSectionAndADeclaredSecondaryExitUsesTheNormalEditor(final boolean adjacent) throws Exception {
        final Path project=directory.resolve("railix.project.json"),home=directory.resolve("home/.railix");
        final String original=CreatorProjects.grouping().replace("railix.trigger.cli","railix.trigger.http")
                .replace("\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{}", "\"id\":\"command\",\"use\":\"railix.trigger.http\",\"inputs\":{\"port\":0}")
                .replace("\"payload\":[]","\"payload\":{}");
        Files.writeString(project,adjacent?original:original.replace("\"id\":\"lowercase-text\",\"use\":\"railix.field-manipulation\",\"inputs\":{}", "\"id\":\"lowercase-text\",\"use\":\"railix.branch\",\"inputs\":{\"routes\":[{\"outcome\":\"match\",\"inputs\":{},\"groups\":[]}]}")
                .replace("\"lowercase-text.next\"","\"lowercase-text.otherwise\"")
                .replace("{\"from\":\"return-text.next\"", "{\"from\":\"lowercase-text.match\",\"to\":\"end\"},{\"from\":\"return-text.next\""));
        final var jar=Files.copy(ApplicationBuilder.buildProduction(project,ProjectFiles.build(project,home,true)).jar(),directory.resolve("running.jar"));
        final Process app=start(jar,directory.resolve("home"),directory.resolve("app.log"));
        try {
            await(()->assertThat(ObservationServer.connections(home)).hasSize(1));
            final var connection=ObservationServer.connections(home).getFirst();
            try(CreatorServer creator=CreatorServer.start(0,project,home)) {
                final URI uri=creator.baseUri();
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\"}").statusCode()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.request(uri,"POST","/api/live","{\"instance\":\""+connection.instance()+"\",\"confirm\":true}").statusCode()).isEqualTo(200);
                assertThat(readLive(uri,"/api/live/editor",connection.instance()).statusCode()).isEqualTo(200);
                long revision=0;
                for(final String id:adjacent?List.of("lowercase-text","return-text"):List.of("lowercase-text")) {
                    final var removed=CreatorServerE2eSupport.request(uri,"POST","/api/editor","{\"revision\":"+revision+",\"creator_revision\":0,\"command\":{\"kind\":\"delete\",\"node\":\""+id+"\"}}");
                    assertThat(removed.statusCode()).as(removed.body()).isEqualTo(200);
                    revision=JsonParser.parseString(removed.body()).getAsJsonObject().get("revision").getAsLong();
                }
                final var restored=editLive(uri,"{\"revision\":"+revision+",\"creator_revision\":0,\"command\":{\"kind\":\"restore\",\"node\":\"lowercase-text\"}}",connection.instance());
                assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
                assertThat(readLive(uri,"/api/live/changes",connection.instance()).body()).isEqualTo("{}");
                assertThat(app.isAlive()).isTrue();
            }
        } finally {ProjectSourceArtifactsE2eTest.stop(app);}
    }

    static Process start(final Path artifact,final Path userHome,final Path log) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Duser.home="+userHome,"-jar",artifact.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    static HttpResponse<String> readLive(final URI uri, final String path, final String instance) throws Exception {
        try(final var client=HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri.resolve(path)).timeout(Duration.ofSeconds(15))
                    .header("X-Railix-Creator-Token",uri.getFragment().substring("token=".length()))
                    .header("X-Railix-Live-Instance",instance).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
    }

    static HttpResponse<String> editLive(final URI uri, final String body, final String instance) throws Exception {
        try(final var client=HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri.resolve("/api/editor")).timeout(Duration.ofSeconds(20))
                    .header("X-Railix-Creator-Token",uri.getFragment().substring("token=".length()))
                    .header("X-Railix-Live-Instance",instance).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }

    static void await(final org.assertj.core.api.ThrowableAssert.ThrowingCallable check) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            try { check.call(); return; }
            catch (final Throwable failure) {
                if (failure instanceof Error && !(failure instanceof AssertionError)) throw (Error) failure;
                if (System.nanoTime() >= deadline) throw new AssertionError("Condition did not become true", failure);
                Thread.sleep(25);
            }
        }
    }
}
