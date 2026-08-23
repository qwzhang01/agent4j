package io.github.qwzhang01.agent.product;

import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.product.definition.DefinitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.1 bootstrapper tests: "adding an agent = dropping a YAML file", with
 * all-or-nothing startup semantics.
 */
class ProductBootstrapperTest {

    @TempDir
    Path agentsDir;

    private ProductBootstrapper bootstrapper() {
        return ProductBootstrapper.builder()
                .model("primary", MockModelClient.scripted()
                        .respondText("answer-1")
                        .respondText("answer-2")
                        .respondText("answer-3"))
                .build();
    }

    private void write(String fileName, String content) {
        try {
            Files.writeString(agentsDir.resolve(fileName), content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ============ Happy path ============

    @Test
    void startAllStartsEveryDefinitionFile() {
        write("support-bot.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: support-bot
                  tenant: acme
                spec:
                  persona:
                    systemPrompt: "You are support."
                  model:
                    provider: primary
                """);
        write("kb-bot.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: kb-bot
                spec:
                  persona:
                    systemPrompt: "You know things."
                  model:
                    provider: primary
                """);

        AgentRegistry registry = bootstrapper().startAll(agentsDir);

        assertEquals(2, registry.size());
        assertNotNull(registry.get("support-bot").orElseThrow());
        assertNotNull(registry.get("kb-bot").orElseThrow());

        // And the bound agents actually run (M13.1 acceptance: runnable agent).
        assertEquals("answer-1", registry.get("support-bot").orElseThrow().run("hi"));
        assertEquals("answer-2", registry.get("kb-bot").orElseThrow().run("hi"));
    }

    @Test
    void jsonDefinitionFilesAreLoadedToo() {
        write("json-bot.json", """
                {
                  "apiVersion": "v1",
                  "kind": "Agent",
                  "metadata": {"name": "json-bot"},
                  "spec": {
                    "persona": {"systemPrompt": "hi"},
                    "model": {"provider": "primary"}
                  }
                }
                """);

        AgentRegistry registry = bootstrapper().startAll(agentsDir);

        assertEquals(1, registry.size());
        assertTrue(registry.get("json-bot").isPresent());
    }

    @Test
    void emptyDirectoryStartsNothing() {
        assertEquals(0, bootstrapper().startAll(agentsDir).size());
    }

    @Test
    void nonDefinitionFilesAreIgnored() {
        write("readme.txt", "not a definition");
        write("real-bot.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: real-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: primary
                """);

        assertEquals(1, bootstrapper().startAll(agentsDir).size());
    }

    // ============ All-or-nothing ============

    @Test
    void anyBrokenFileStopsTheWholeStartup() {
        write("good-bot.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: good-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: primary
                """);
        write("bad-bot.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: bad-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: ghost
                """);

        DefinitionException e = assertThrows(DefinitionException.class,
                () -> bootstrapper().startAll(agentsDir));

        // The error names the file and the offending path.
        assertTrue(e.getMessage().contains("bad-bot.yaml"), e.getMessage());
        assertTrue(e.getMessage().contains("spec.model.provider"), e.getMessage());
    }

    @Test
    void errorsAcrossFilesAreAggregated() {
        write("a-bot.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: a-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: ghost-a
                """);
        write("b-bot.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: b-bot
                spec:
                  persona:
                    systemPrompt: "hi"
                  model:
                    provider: ghost-b
                """);

        DefinitionException e = assertThrows(DefinitionException.class,
                () -> bootstrapper().startAll(agentsDir));

        assertEquals(2, e.getErrors().size(), () -> "both files' errors in one pass: " + e.getMessage());
    }

    @Test
    void duplicateNamesAcrossFilesAreRejected() {
        write("one.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: same-bot
                spec:
                  persona:
                    systemPrompt: "one"
                  model:
                    provider: primary
                """);
        write("two.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: same-bot
                spec:
                  persona:
                    systemPrompt: "two"
                  model:
                    provider: primary
                """);

        DefinitionException e = assertThrows(DefinitionException.class,
                () -> bootstrapper().startAll(agentsDir));
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    // ============ Argument guards ============

    @Test
    void missingDirectoryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> bootstrapper().startAll(agentsDir.resolve("does-not-exist")));
    }

    @Test
    void contextExposesRegisteredImplementations() {
        ProductBootstrapper bootstrapper = bootstrapper();
        assertEquals(1, bootstrapper.context().modelNames().size());
        assertTrue(bootstrapper.context().model("primary").isPresent());
    }

    // ============ M13.2: template directory ============

    @Test
    void templateDirLoadsTemplatesForLaterInstantiation() {
        write("my-template.yaml", """
                apiVersion: v1
                kind: AgentTemplate
                metadata:
                  name: my-template
                  version: "1.0"
                variables:
                  - name: tenantId
                    required: true
                spec:
                  persona:
                    systemPrompt: "租户 ${tenantId} 的助手。"
                  model:
                    provider: primary
                """);

        ProductBootstrapper bootstrapper = ProductBootstrapper.builder()
                .model("primary", MockModelClient.scripted().respondText("template answer"))
                .templateDir(agentsDir)
                .build();

        var def = bootstrapper.templates().instantiate("my-template", "inst-1", null, Map.of("tenantId", "acme"));
        assertEquals("租户 acme 的助手。", def.spec().persona().systemPrompt());

        // And the instantiated definition binds + runs through the normal path.
        var agent = new io.github.qwzhang01.agent.product.definition.AgentDefinitionBinder(
                bootstrapper.context()).bind(def);
        assertEquals("template answer", agent.run("hi"));
    }

    @Test
    void brokenTemplateFileFailsTheBuild() {
        write("broken-template.yaml", "kind: definitely-not-a-template");

        assertThrows(Exception.class, () -> ProductBootstrapper.builder()
                .model("primary", MockModelClient.scripted())
                .templateDir(agentsDir)
                .build());
    }

    @Test
    void noTemplateDirMeansEmptyTemplateRegistry() {
        assertEquals(0, bootstrapper().templates().names().size());
    }

    // ============ M13.4: prompt manager integration ============

    @Test
    void promptRefDefinitionsStartAndRunThroughStartAll() {
        var prompts = new io.github.qwzhang01.agent.product.prompt.PromptManager();
        prompts.publish("support-system", "你是托管人格。");
        write("managed-bot.yaml", """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: managed-bot
                spec:
                  persona:
                    promptRef: { name: support-system }
                  model:
                    provider: primary
                """);

        ProductBootstrapper bootstrapper = ProductBootstrapper.builder()
                .model("primary", MockModelClient.scripted().respondText("managed answer"))
                .promptManager(prompts)
                .build();

        AgentRegistry registry = bootstrapper.startAll(agentsDir);

        assertEquals(1, registry.size());
        assertEquals("managed answer", registry.get("managed-bot").orElseThrow().run("hi"));
    }
}
