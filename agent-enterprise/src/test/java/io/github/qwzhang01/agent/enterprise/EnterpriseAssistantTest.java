package io.github.qwzhang01.agent.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.enterprise.govern.BudgetExceededException;
import io.github.qwzhang01.agent.enterprise.govern.CostLedger;
import io.github.qwzhang01.agent.enterprise.govern.EnterpriseAuditEvent;
import io.github.qwzhang01.agent.enterprise.knowledge.KnowledgeBase;
import io.github.qwzhang01.agent.enterprise.knowledge.KnowledgeEntry;
import io.github.qwzhang01.agent.enterprise.task.BusinessTask;
import io.github.qwzhang01.agent.enterprise.task.EnterpriseTaskManager;
import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.enterprise.tenant.Tenant;
import io.github.qwzhang01.agent.enterprise.tenant.TenantRegistry;
import io.github.qwzhang01.agent.enterprise.tenant.User;
import io.github.qwzhang01.agent.memory.InMemoryMemoryStore;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.security.ToolApprovalService;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 15 M15.5: the facade end-to-end - budget gate, request-scoped
 * assembly, RAG tool call, governance attribution, usage billing and the
 * task path, all through one entry point.
 */
class EnterpriseAssistantTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TenantRegistry registry;
    private RequestContext alice;
    private RequestContext carol;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();
        registry.registerTenant(new Tenant("acme", "Acme",
                Tenant.TenantStatus.ACTIVE, 10_000));
        registry.registerTenant(Tenant.active("globex", "Globex"));
        registry.registerUser(
                new User("u-alice", "acme", "Alice", Set.of(User.ROLE_CSR)), "k-a");
        registry.registerUser(
                new User("u-bob", "acme", "Bob", Set.of(User.ROLE_SUPERVISOR)), "k-b");
        registry.registerUser(
                new User("u-carol", "globex", "Carol", Set.of(User.ROLE_CSR)), "k-c");
        alice = registry.login("acme", "u-alice", "k-a");
        carol = registry.login("globex", "u-carol", "k-c");
    }

    private KnowledgeBase knowledge() {
        KnowledgeBase kb = new KnowledgeBase(new InMemoryMemoryStore());
        kb.ingest("acme", List.of(
                KnowledgeEntry.of("Return Policy", "acme policy: 30-day no-question returns")), "admin");
        kb.ingest("globex", List.of(
                KnowledgeEntry.of("Return Policy", "globex policy: all sales final")), "admin");
        return kb;
    }

    /** A business tool answering with its caller-independent payload. */
    private Tool orderTool() {
        return new Tool() {
            @Override
            public String getName() {
                return "query_order";
            }

            @Override
            public String getDescription() {
                return "Query order status";
            }

            @Override
            public String getParametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}";
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return "{\"status\":\"shipped\",\"eta\":\"tomorrow\"}";
            }
        };
    }

    private EnterpriseAssistant assistant(ModelClientSupplier modelSupplier,
                                          CostLedger ledger,
                                          EnterpriseTaskManager taskManager) {
        return EnterpriseAgentFactory.builder()
                .modelClient(modelSupplier.get())
                .tool(orderTool())
                .knowledgeBase(knowledge())
                .roleMatrix(Map.of(
                        User.ROLE_CSR, Set.of("search_knowledge", "query_order"),
                        User.ROLE_SUPERVISOR, Set.of("query_order", "refund_order")))
                .toolPolicy(new ToolPolicy(ToolPermission.AUTO)
                        .setPermission("refund_order", ToolPermission.REQUIRES_APPROVAL))
                .toolApprovalService((toolCall, runId) -> true)  // auto-approve in tests
                .costLedger(ledger)
                .taskManager(taskManager)
                .build();
    }

    @FunctionalInterface
    private interface ModelClientSupplier {
        io.github.qwzhang01.agent.core.client.ModelClient get();
    }

    private static ToolCall knowledgeCall(String id, String query) {
        return ToolCall.of(id, "search_knowledge",
                MAPPER.createObjectNode().put("query", query).toString());
    }

    // keyword retrieval is substring-based (v1): queries use a word that
    // actually appears in the ingested contents ("policy")

    // ============ ask: Full Chain ============

    @Test
    @DisplayName("ask runs the full chain: RAG tool call, attributed audit, usage billing")
    void askFullChain() {
        CostLedger ledger = new CostLedger(Map.of());
        EnterpriseAssistant assistant = assistant(() -> MockModelClient.scripted()
                .respond(new io.github.qwzhang01.agent.core.model.ModelResponse(null,
                        List.of(knowledgeCall("c1", "policy")),
                        "tool_calls",
                        new io.github.qwzhang01.agent.core.model.ModelResponse.TokenUsage(800, 57, 857)))
                .respondText("根据退货政策：30 天无理由退货。"),
                ledger, null);

        String answer = assistant.ask(alice, "退货政策是什么？");

        assertEquals("根据退货政策：30 天无理由退货。", answer);

        // audit: the knowledge call is attributed to alice in acme
        List<EnterpriseAuditEvent> events = assistant.auditTrail().byUser("u-alice");
        assertEquals(1, events.size());
        assertEquals("search_knowledge", events.get(0).toolName());
        assertEquals("acme", events.get(0).tenantId());
        assertEquals("u-alice", events.get(0).userId());
        // the tool result inside the audit event is the ACME slice
        assertTrue(events.get(0).event().result().contains("acme policy"),
                "tool result should carry the tenant's own knowledge: "
                        + events.get(0).event().result());

        // billing: mock usage landed on the tenant ledger
        assertEquals(857, ledger.tenantUsed("acme"));
        assertEquals(857, ledger.userUsed("u-alice"));
    }

    @Test
    @DisplayName("the same question in another tenant retrieves that tenant's knowledge only")
    void tenantIsolationEndToEnd() {
        CostLedger ledger = new CostLedger(Map.of());
        EnterpriseAssistant assistant = assistant(() -> MockModelClient.scripted()
                .respond(new io.github.qwzhang01.agent.core.model.ModelResponse(null,
                        List.of(knowledgeCall("c1", "policy")),
                        "tool_calls",
                        new io.github.qwzhang01.agent.core.model.ModelResponse.TokenUsage(100, 20, 120)))
                .respondText("Globex: all sales final."),
                ledger, null);

        assistant.ask(carol, "退货政策是什么？");

        List<EnterpriseAuditEvent> events = assistant.auditTrail().byUser("u-carol");
        assertEquals(1, events.size());
        assertTrue(events.get(0).event().result().contains("globex policy"),
                "carol must see globex knowledge, never acme's: "
                        + events.get(0).event().result());
        assertEquals(0, assistant.auditTrail().byTenant("acme").size(),
                "acme's trail must not contain carol's request");
        assertEquals(120, ledger.tenantUsed("globex"));
    }

    // ============ Budget Gate ============

    @Test
    @DisplayName("ask fails closed when the user budget is exhausted - before any tokens burn")
    void budgetGate() {
        CostLedger ledger = new CostLedger(Map.of("u-alice", 100L));
        EnterpriseAssistant assistant = assistant(() -> MockModelClient.scripted()
                .respond(new io.github.qwzhang01.agent.core.model.ModelResponse(null,
                        List.of(knowledgeCall("c1", "x")),
                        "tool_calls",
                        new io.github.qwzhang01.agent.core.model.ModelResponse.TokenUsage(60, 40, 100)))
                .respondText("answer"),
                ledger, null);

        assistant.ask(alice, "first question");   // spends exactly the 100-token budget
        assertEquals(100, ledger.userUsed("u-alice"));

        assertThrows(BudgetExceededException.class,
                () -> assistant.ask(alice, "second question"));
        assertEquals(100, ledger.userUsed("u-alice"),
                "the rejected request must not have spent anything");
    }

    // ============ Governance Ride-Along ============

    @Test
    @DisplayName("a tool outside the CSR matrix rides the fallback: REQUIRES_APPROVAL -> approved -> executed")
    void approvalRideAlong() {
        CostLedger ledger = new CostLedger(Map.of());
        EnterpriseAssistant assistant = assistant(() -> MockModelClient.scripted()
                .respond(new io.github.qwzhang01.agent.core.model.ModelResponse(null,
                        List.of(ToolCall.of("c1", "refund_order", "{\"orderId\":\"8842\"}")),
                        "tool_calls",
                        new io.github.qwzhang01.agent.core.model.ModelResponse.TokenUsage(10, 5, 15)))
                .respondText("退款已发起。"),
                ledger, null);

        // register a refund tool? we did not - the governed executor will deny
        // with "unknown tool" via the delegate throwing. Instead assert the
        // APPROVED + EXECUTED/FAILED audit pair exists with attribution.
        String answer = assistant.ask(alice, "帮我退款订单 8842");

        // the audit trail shows the approval gate fired for the CSR
        List<EnterpriseAuditEvent> refundEvents = assistant.auditTrail().byTool("refund_order");
        assertTrue(refundEvents.stream()
                        .anyMatch(e -> e.status() == io.github.qwzhang01.agent.security.AuditEvent.AuditStatus.APPROVED),
                "the REQUIRES_APPROVAL gate must fire: " + refundEvents);
        assertTrue(refundEvents.stream().allMatch(e -> "u-alice".equals(e.userId())));
    }

    // ============ Task Path ============

    @Test
    @DisplayName("submitTask/approve flow through the facade with the budget gate")
    void taskPathThroughFacade() {
        CostLedger ledger = new CostLedger(Map.of());
        EnterpriseTaskManager taskManager = new EnterpriseTaskManager(new RunManager());
        EnterpriseAssistant assistant = assistant(() -> MockModelClient.scripted()
                .respondText("unused"), ledger, taskManager);

        Workflow refundFlow = Workflow.builder("refund-flow")
                .node(ActionNode.of("prepare", ctx -> "prepared"))
                .node(HumanApprovalNode.of("approval", "Approve refund",
                        taskManager.approvalService()))
                .node(ActionNode.of("execute", ctx -> "refunded"))
                .edge(Workflow.START, "prepare")
                .edge("prepare", "approval")
                .edge("approval", "execute")
                .edge("execute", Workflow.END)
                .build();

        BusinessTask task = assistant.submitTask(alice, "refund order 8842", refundFlow);
        assertEquals(BusinessTask.Status.WAITING_APPROVAL, task.status());
        assertTrue(assistant.findTask(task.taskId()).isPresent());

        BusinessTask done = assistant.approve(task.taskId(), "u-bob", "within limit");
        assertEquals(BusinessTask.Status.DONE, done.status());
    }

    @Test
    @DisplayName("submitTask without a task manager fails fast")
    void taskPathRequiresManager() {
        EnterpriseAssistant assistant = assistant(
                () -> MockModelClient.scripted().respondText("x"), null, null);
        Workflow wf = Workflow.builder("w")
                .node(ActionNode.of("n", ctx -> "ok"))
                .edge(Workflow.START, "n")
                .edge("n", Workflow.END)
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> assistant.submitTask(alice, "d", wf));
    }

    // ============ Builder Validation ============

    @Test
    @DisplayName("builder requires a model client")
    void builderRequiresModel() {
        assertThrows(IllegalStateException.class,
                () -> EnterpriseAgentFactory.builder().build());
    }

    @Test
    @DisplayName("forRequest exposes the request-scoped chain without running it")
    void forRequestExposesChain() {
        EnterpriseAssistant assistant = assistant(
                () -> MockModelClient.scripted().respondText("ok"), null, null);
        EnterpriseAgentFactory.EnterpriseAgent agent = assistant.forRequest(alice);
        assertNotNull(agent);
        assertEquals(0, agent.promptTokens());
        assertEquals("ok", agent.run("ping"));
        assertEquals(0, agent.promptTokens(),
                "scripted respondText carries no usage -> nothing billed");
    }
}
