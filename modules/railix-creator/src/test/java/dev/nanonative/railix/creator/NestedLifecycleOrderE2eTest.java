package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thirdparty.conformance.LifecycleOrderStep;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
final class NestedLifecycleOrderE2eTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"candidate,false","pipeline,false","alias,false","independent,false","static-source,false",
            "candidate,true","pipeline,true","alias,true","independent,true","static-source,true"})
    void nestedDependenciesPrepareDownstreamFirstInInstalledArtifacts(final String scenario, final boolean development) throws Exception {
        final Path log=directory.resolve("lifecycle.log");
        final var nested=StepDefinition.named("test.lifecycle", "1").primaryOutcome("ok")
                .receive("value",ValueShape.ANY).returns("value",ValueShape.ANY)
                .input("log",StepDefinition.Input.json(ValueShape.STRING).defaultValue(RailixValue.string(log.toString())))
                .input("name",StepDefinition.Input.json(ValueShape.STRING))
                .implementation(LifecycleOrderStep.class);
        final var seed=StepDefinition.Input.option("seed").fromParent("raw");
        final var source=StepDefinition.Input.candidates(StepDefinition.Input.option("computed")
                .input("raw",StepDefinition.Input.json(ValueShape.STRING).defaultValue(RailixValue.string("VALUE")))
                .input("accepted",StepDefinition.Input.candidates(seed)).fromOwned("accepted"));
        final var parent=StepDefinition.named("test.parent","1")
                .input("field",StepDefinition.Input.path(StepDefinition.PathAccess.WRITE).defaultPath("context","result"))
                .input("value",scenario.equals("static-source")?StepDefinition.Input.json(ValueShape.STRING):source);
        if(scenario.equals("alias")) parent.input("alias",StepDefinition.Input.options(
                StepDefinition.Input.option("value").fromParent("value")).defaultOption("value"));
        if(List.of("independent","static-source").contains(scenario)) parent.input("other",source);
        parent.input("steps",StepDefinition.Input.steps(StepDefinition.ValueSource.from(scenario.equals("alias")?"alias":"value")));
        final var definitions=List.of(nested,parent.implementation(LifecycleOrderStep.Parent.class));
        final var catalog=GeneratedApplicationFixture.installedCatalog(directory,definitions,
                LifecycleOrderStep.class,LifecycleOrderStep.Parent.class);
        final String value="""
                [{"option":"computed","inputs":{"accepted":[{"option":"seed","inputs":{},
                  "when":{"transforms":[%s],"all":[[{"use":"value.equals","inputs":{"expected":"VALUE"}}]]}}]},
                  "when":{"transforms":[%s],"all":%s}}]
                """.formatted(step("source"),scenario.equals("candidate")?step("transform"):"",
                scenario.equals("candidate")?"[["+step("predicate")+",{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"VALUE\"}}]]":"[]");
        final String sourceJson="""
                {"format":1,"id":"nested-order","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"cli","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"default","payload":[]}]},
                  {"id":"parent","use":"test.parent","inputs":{"value":%s,"steps":[%s]%s}}
                ],"links":[{"from":"app.start","to":"cli"},{"from":"cli.next","to":"parent"},
                  {"from":"parent.next","to":"end"}]}
                """.formatted(scenario.equals("static-source")?"\"VALUE\"":value,
                List.of("pipeline","alias","static-source").contains(scenario)?step("process"):"",
                List.of("independent","static-source").contains(scenario)?",\"other\":"+value.replace("source","other"):"");
        final var result=ProjectCompiler.compileApplication(sourceJson,catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path project=directory.resolve("railix.project.json");
        Files.writeString(project,sourceJson);
        if(development) {
            try(var application=GeneratedApplicationFixture.start(directory.resolve("development"),sourceJson,definitions,
                    LifecycleOrderStep.class,LifecycleOrderStep.Parent.class)) {
                final var response=application.runTest("cli","{\"payload\":{\"arguments\":[]}}");
                assertThat(response.status()).as(response.body()).isEqualTo(200);
                final var body=CreatorServerE2eSupport.object(response.body());
                assertThat(body.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
                assertThat(((RailixValue.ObjectValue)body.values().get("context")).values().get("result"))
                        .isEqualTo(RailixValue.string("VALUE"));
            }
        } else {
            final var artifact=ApplicationBuilder.buildProduction(project,(CompileResult.Compiled)result);
            final Path output=directory.resolve("output");
            final Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-jar",artifact.jar().toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as(Files.readString(output)).isZero();
                assertThat(Files.readString(output).strip()).isEqualTo("\"VALUE\"");
            } finally {process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}
        }
        final List<String> pre=switch(scenario) {
            case "candidate"->List.of("predicate","transform","source");
            case "pipeline","alias"->List.of("process","source");
            case "static-source"->List.of("other","process");
            default->List.of("source","other");
        };
        final List<String> expected=new ArrayList<>(pre.stream().map(name->"pre:"+name).toList());
        expected.addAll(pre.reversed().stream().map(name->"post:"+name).toList());
        assertThat(Files.readAllLines(log)).containsExactlyElementsOf(expected);
    }

    private static String step(final String name) {
        return "{\"use\":\"test.lifecycle\",\"inputs\":{\"name\":"+RailixJson.write(RailixValue.string(name))+"}}";
    }
}
