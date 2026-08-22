package io.github.qwzhang01.agent.channel;

import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.IdentityScope;
import io.github.qwzhang01.agent.channel.identity.ResolvedIdentity;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.security.ConsoleApprovalService;
import io.github.qwzhang01.agent.security.GovernedToolExecutor;
import io.github.qwzhang01.agent.security.IdentityConstrainedPermissionChecker;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Assembly-layer Identity ↔ PermissionChecker bridge (Stage 12).
 * channel does not depend on security at compile time; the test wires them.
 */
class SharedAgentSessionIdentityBridgeTest {

    private static final String CHANNEL = "team-eng";
    private static final String AGENT_ID = "eng-bot";

    @Test
    @DisplayName("speak forwards ResolvedIdentity to the assembly binder")
    void speak_forwardsIdentityToBinder() {
        AtomicReference<ResolvedIdentity> bound = new AtomicReference<>();
        SharedAgentSession session = new SharedAgentSession(
                new SimpleAgent(new AgentConfig(AGENT_ID, "sys",
                        MockModelClient.scripted().respondText("ok"), null, 5)),
                ServiceAccount.of("svc-1",
                        new AgentIdentity(AGENT_ID, "Eng", "leads"),
                        IdentityScope.capabilities("echo", "chat")),
                ChannelContext.of(CHANNEL, "alice"),
                (ch, uid) -> Set.of("echo", "chat"),
                null,
                bound::set);

        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot hi"));

        assertNotNull(session.lastResolvedIdentity());
        assertSame(session.lastResolvedIdentity(), bound.get());
        assertTrue(bound.get().allows("echo"));
        assertFalse(bound.get().allows("delete_file"));
    }

    @Test
    @DisplayName("tool not in identity capabilities is denied even if policy is AUTO")
    void toolOutsideCapabilities_denied() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echo("delete_file"));

        IdentityConstrainedPermissionChecker checker = new IdentityConstrainedPermissionChecker(
                new ToolPolicy(ToolPermission.AUTO));
        GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(checker)
                .approvalService(ConsoleApprovalService.autoApprove())
                .build();

        SharedAgentSession session = sessionWithTools(
                checker, executor, registry,
                IdentityScope.capabilities("echo", "chat"),
                Set.of("echo", "chat"),
                MockModelClient.scripted()
                        .respondToolCalls(ToolCall.of("c1", "delete_file", "{\"input\":\"x\"}"))
                        .respondText("done"));

        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot delete it"));

        assertTrue(session.sharedState().getMessages().stream()
                        .anyMatch(m -> m.content() != null && m.content().startsWith("[DENIED]")),
                "delete_file is outside identity capabilities");
    }

    @Test
    @DisplayName("tool in identity capabilities still follows REQUIRES_APPROVAL")
    void grantedTool_keepsRequiresApproval() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echo("delete_file"));

        IdentityConstrainedPermissionChecker checker = new IdentityConstrainedPermissionChecker(
                new ToolPolicy(ToolPermission.AUTO)
                        .setPermission("delete_file", ToolPermission.REQUIRES_APPROVAL));
        GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(checker)
                .build(); // no approval service -> REQUIRES_APPROVAL is denied

        SharedAgentSession session = sessionWithTools(
                checker, executor, registry,
                IdentityScope.capabilities("delete_file", "chat"),
                Set.of("delete_file", "chat"),
                MockModelClient.scripted()
                        .respondToolCalls(ToolCall.of("c1", "delete_file", "{\"input\":\"x\"}"))
                        .respondText("done"));

        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot delete it"));

        String toolResult = session.sharedState().getMessages().stream()
                .filter(m -> m.role() == ChatRole.TOOL)
                .map(ChatMessage::content)
                .findFirst()
                .orElse("");
        assertTrue(toolResult.contains("requires approval"), toolResult);
    }

    @Test
    @DisplayName("tool in identity capabilities with AUTO still executes")
    void grantedAutoTool_executes() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(echo("echo"));

        IdentityConstrainedPermissionChecker checker = new IdentityConstrainedPermissionChecker(
                new ToolPolicy(ToolPermission.AUTO));
        GovernedToolExecutor executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(checker)
                .build();

        SharedAgentSession session = sessionWithTools(
                checker, executor, registry,
                IdentityScope.capabilities("echo", "chat"),
                Set.of("echo", "chat"),
                MockModelClient.scripted()
                        .respondToolCalls(ToolCall.of("c1", "echo", "{\"input\":\"hi\"}"))
                        .respondText("ok"));

        session.speak(ChannelMessage.mention(CHANNEL, "alice", "@eng-bot echo"));

        assertTrue(session.sharedState().getMessages().stream()
                .anyMatch(m -> m.content() != null && m.content().startsWith("echo:")));
    }

    private static SharedAgentSession sessionWithTools(
            IdentityConstrainedPermissionChecker checker,
            GovernedToolExecutor executor,
            InMemoryToolRegistry registry,
            IdentityScope granted,
            Set<String> roleCaps,
            MockModelClient model) {
        AgentConfig config = new AgentConfig(AGENT_ID, "sys", model, registry, 8);
        return new SharedAgentSession(
                new SimpleAgent(config, new ReActAgentLoop(executor)),
                ServiceAccount.of("svc-1", new AgentIdentity(AGENT_ID, "Eng", "leads"), granted),
                ChannelContext.of(CHANNEL, "alice"),
                (ch, uid) -> roleCaps,
                null,
                identity -> checker.bindCapabilities(identity.effectiveCapabilities()));
    }

    private static Tool echo(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(JsonNode args) { return "echo: " + args.path("input").asText(); }
        };
    }
}
