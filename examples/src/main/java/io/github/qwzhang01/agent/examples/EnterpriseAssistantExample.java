package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.enterprise.EnterpriseAgentFactory;
import io.github.qwzhang01.agent.enterprise.EnterpriseAssistant;
import io.github.qwzhang01.agent.enterprise.govern.CostLedger;
import io.github.qwzhang01.agent.enterprise.govern.EnterpriseAuditEvent;
import io.github.qwzhang01.agent.enterprise.knowledge.KnowledgeBase;
import io.github.qwzhang01.agent.enterprise.knowledge.KnowledgeEntry;
import io.github.qwzhang01.agent.enterprise.task.BusinessTask;
import io.github.qwzhang01.agent.enterprise.task.EnterpriseTaskManager;
import io.github.qwzhang01.agent.enterprise.task.TaskApprovalBridge;
import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.enterprise.tenant.Tenant;
import io.github.qwzhang01.agent.enterprise.tenant.TenantRegistry;
import io.github.qwzhang01.agent.enterprise.tenant.User;
import io.github.qwzhang01.agent.memory.InMemoryMemoryStore;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.FileCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 15 M15.5 acceptance demo: the full enterprise script (blueprint §6).
 * <p>
 * T1 login - T2 RAG answer (tenant-scoped retrieval, attributed audit,
 * usage billing) - T3 tool-level approval ride-along - T4 task-level approval
 * with checkpoint resume - T5 cross-tenant isolation + the honest budget
 * rejection. Zero LLM needed (MockModelClient scripted).
 * <p>
 * Run: {@code mvn -pl examples exec:java -Dexec.mainClass=...EnterpriseAssistantExample}
 */
public final class EnterpriseAssistantExample {

    private static final Map<String, String> ORDERS = Map.of(
            "8842", "{\"orderId\":\"8842\",\"status\":\"shipped\",\"eta\":\"tomorrow\"}",
            "9917", "{\"orderId\":\"9917\",\"status\":\"refunded\"}");

    public static void main(String[] args) throws Exception {
        System.out.println("=== Stage 15: Enterprise Agent Profile - Full Script ===\n");

        // --------------------------------------------
        // T0: assembly (admin, once)
        // --------------------------------------------
        TenantRegistry registry = new TenantRegistry();
        registry.registerTenant(new Tenant("acme", "Acme Corp",
                Tenant.TenantStatus.ACTIVE, 100_000));   // tenant budget: 100k tokens
        registry.registerTenant(Tenant.active("globex", "Globex Inc"));
        registry.registerUser(new User("u-alice", "acme", "Alice",
                Set.of(User.ROLE_CSR)), "key-alice");
        registry.registerUser(new User("u-bob", "acme", "Bob",
                Set.of(User.ROLE_SUPERVISOR)), "key-bob");
        registry.registerUser(new User("u-carol", "globex", "Carol",
                Set.of(User.ROLE_CSR)), "key-carol");
        registry.registerUser(new User("u-dave", "acme", "Dave",
                Set.of(User.ROLE_CSR)), "key-dave");

        KnowledgeBase knowledge = new KnowledgeBase(new InMemoryMemoryStore());
        knowledge.ingest("acme", List.of(
                KnowledgeEntry.of("Return Policy", "acme policy: 30-day no-question returns"),
                KnowledgeEntry.of("Invoice Policy", "acme policy: invoices within 24 hours")), "admin");
        knowledge.ingest("globex", List.of(
                KnowledgeEntry.of("Return Policy", "globex policy: all sales final")), "admin");

        AtomicInteger refundCount = new AtomicInteger();
        EnterpriseTaskManager taskManager = new EnterpriseTaskManager(
                new RunManager(new FileCheckpointStore(Path.of("/tmp/m15-example-checkpoints"))),
                new TaskApprovalBridge());

        EnterpriseAssistant assistant = EnterpriseAgentFactory.builder()
                .modelClient(MockModelClient.scripted()
                        .respond(new ModelResponse(null,
                                List.of(ToolCall.of("c1", "search_knowledge",
                                        "{\"query\":\"policy\"}")),
                                "tool_calls", new ModelResponse.TokenUsage(812, 45, 857)))
                        .respondText("根据《退货政策》：30 天无理由退货。"))
                .tool(orderQueryTool())
                .tool(refundTool(refundCount))
                .knowledgeBase(knowledge)
                .roleMatrix(Map.of(
                        User.ROLE_CSR, Set.of("search_knowledge", "query_order"),
                        User.ROLE_SUPERVISOR, Set.of("search_knowledge", "query_order", "refund_order")))
                .toolPolicy(new ToolPolicy(ToolPermission.AUTO)
                        .setPermission("refund_order", ToolPermission.REQUIRES_APPROVAL))
                .toolApprovalService((toolCall, runId) -> {
                    System.out.println("  [审批] 工具级审批放行: " + toolCall.name());
                    return true;
                })
                .costLedger(new CostLedger(Map.of("u-dave", 50L)))   // dave: tiny budget
                .taskManager(taskManager)
                .agentName("support-bot")
                .build();

        // --------------------------------------------
        // T1: login identification
        // --------------------------------------------
        System.out.println("--- T1: 登录识别 ---");
        RequestContext alice = registry.login("acme", "u-alice", "key-alice");
        System.out.println("alice 登录成功: tenant=" + alice.tenantId()
                + " scopes=" + alice.memoryScopes() + " actor=" + alice.actor());
        RequestContext bob = registry.login("acme", "u-bob", "key-bob");
        RequestContext carol = registry.login("globex", "u-carol", "key-carol");

        // --------------------------------------------
        // T2: RAG answer (knowledge -> attributed audit -> billed)
        // --------------------------------------------
        System.out.println("\n--- T2: RAG 问答（知识检索 + 归属审计 + 记账） ---");
        String answer = assistant.ask(alice, "退货政策是什么？");
        System.out.println("答: " + answer);
        System.out.println("审计: " + describe(assistant.auditTrail().byUser("u-alice")));
        System.out.println("账单: acme 已用 " + assistant.costLedger().tenantUsed("acme")
                + " tokens, alice 个人 " + assistant.costLedger().userUsed("u-alice"));

        // --------------------------------------------
        // T3: tool-level approval rides along
        // --------------------------------------------
        System.out.println("\n--- T3: 工具级审批搭车（CSR 触发 refund_order → REQUIRES_APPROVAL） ---");
        EnterpriseAssistant refundAssistant = EnterpriseAgentFactory.builder()
                .modelClient(MockModelClient.scripted()
                        .respond(new ModelResponse(null,
                                List.of(ToolCall.of("c1", "refund_order",
                                        "{\"orderId\":\"8842\"}")),
                                "tool_calls", new ModelResponse.TokenUsage(300, 80, 380)))
                        .respondText("订单 8842 退款已发起。"))
                .tool(refundTool(refundCount))
                .knowledgeBase(knowledge)
                .roleMatrix(Map.of(
                        User.ROLE_CSR, Set.of("search_knowledge", "query_order")))
                .toolPolicy(new ToolPolicy(ToolPermission.AUTO)
                        .setPermission("refund_order", ToolPermission.REQUIRES_APPROVAL))
                .toolApprovalService((tc, rid) -> true)
                .agentName("support-bot")
                .build();
        System.out.println("答: " + refundAssistant.ask(alice, "帮我把订单 8842 退款"));
        System.out.println("审计(含审批事件): " + describe(refundAssistant.auditTrail().byUser("u-alice")));

        // --------------------------------------------
        // T4: task-level approval with checkpoint resume
        // --------------------------------------------
        System.out.println("\n--- T4: 任务级审批（暂停→批准→断点恢复） ---");
        Workflow refundFlow = Workflow.builder("refund-flow")
                .node(ActionNode.of("prepare", ctx -> "prepared"))
                .node(HumanApprovalNode.of("approval", "主管审批退款工单",
                        taskManager.approvalService()))
                .node(ActionNode.of("execute", ctx -> "refunded"))
                .edge(Workflow.START, "prepare")
                .edge("prepare", "approval")
                .edge("approval", "execute")
                .edge("execute", Workflow.END)
                .build();
        BusinessTask task = assistant.submitTask(alice, "refund order 8842", refundFlow);
        System.out.println("提交工单 " + task.taskId() + ": " + task.status());
        BusinessTask done = assistant.approve(task.taskId(), "u-bob", "金额在授权内");
        System.out.println("bob 批准后: " + done.status()
                + " 审批留痕=" + done.approvals().get(0).decision()
                + " by " + done.approvals().get(0).approverId());

        // --------------------------------------------
        // T5: cross-tenant isolation + honest budget rejection
        // --------------------------------------------
        System.out.println("\n--- T5: 租户隔离 + 预算诚实拒绝 ---");
        EnterpriseAssistant globexAssistant = EnterpriseAgentFactory.builder()
                .modelClient(MockModelClient.scripted()
                        .respond(new ModelResponse(null,
                                List.of(ToolCall.of("c1", "search_knowledge",
                                        "{\"query\":\"policy\"}")),
                                "tool_calls", new ModelResponse.TokenUsage(90, 30, 120)))
                        .respondText("Globex: all sales final."))
                .knowledgeBase(knowledge)
                .roleMatrix(Map.of(User.ROLE_CSR, Set.of("search_knowledge")))
                .agentName("globex-bot")
                .build();
        System.out.println("carol(globex) 答: " + globexAssistant.ask(carol, "退货政策？"));
        System.out.println("acme 审计事件数=" + assistant.auditTrail().byTenant("acme").size()
                + " (carol 的请求不在其中)");
        System.out.println("globex 审计: " + describe(globexAssistant.auditTrail().byUser("u-carol")));

        RequestContext dave = registry.login("acme", "u-dave", "key-dave");
        // the SAME ledger must serve both requests: the pre-gate reads what the
        // previous post-recording wrote - two fresh ledgers would never trip
        CostLedger daveLedger = new CostLedger(Map.of("u-dave", 50L));
        try {
            EnterpriseAssistant daveAssistant = EnterpriseAgentFactory.builder()
                    .modelClient(MockModelClient.scripted()
                            .respond(new ModelResponse(null,
                                    List.of(ToolCall.of("c1", "search_knowledge",
                                            "{\"query\":\"policy\"}")),
                                    "tool_calls", new ModelResponse.TokenUsage(40, 20, 60)))
                            .respondText("答案"))
                    .costLedger(daveLedger)
                    .knowledgeBase(knowledge)
                    .build();
            daveAssistant.ask(dave, "第一次提问（预算 50，将花 60）");
            System.out.println("dave 第一次提问完成: 已用 " + daveLedger.userUsed("u-dave"));
        } catch (Exception e) {
            System.out.println("dave 被拒绝: " + e.getMessage());
        }
        try {
            EnterpriseAssistant daveAssistant2 = EnterpriseAgentFactory.builder()
                    .modelClient(MockModelClient.scripted().respondText("x"))
                    .costLedger(daveLedger)
                    .build();
            daveAssistant2.ask(dave, "第二次提问");
        } catch (Exception e) {
            System.out.println("dave 第二次提问: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage() + "（fail-closed，零 token 消耗）");
        }

        System.out.println("\n=== 剧终：每个请求有主人，每个租户有边界，每次回答有出处，每分钱有归属 ===");
    }

    // ============ Demo Tools ============

    private static Tool orderQueryTool() {
        return new Tool() {
            @Override
            public String getName() {
                return "query_order";
            }

            @Override
            public String getDescription() {
                return "Query an order's status by orderId";
            }

            @Override
            public String getParametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}";
            }

            @Override
            public String execute(JsonNode arguments) {
                String orderId = arguments.path("orderId").asText();
                return ORDERS.getOrDefault(orderId,
                        "{\"orderId\":\"" + orderId + "\",\"status\":\"unknown\"}");
            }
        };
    }

    private static Tool refundTool(AtomicInteger refundCount) {
        return new Tool() {
            @Override
            public String getName() {
                return "refund_order";
            }

            @Override
            public String getDescription() {
                return "Refund an order (sensitive operation)";
            }

            @Override
            public String getParametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}";
            }

            @Override
            public String execute(JsonNode arguments) {
                int n = refundCount.incrementAndGet();
                return "{\"refunded\":true,\"attempt\":" + n + "}";
            }
        };
    }

    private static String describe(List<EnterpriseAuditEvent> events) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < events.size(); i++) {
            EnterpriseAuditEvent e = events.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(e.toolName()).append('/').append(e.status())
                    .append(" by ").append(e.userId()).append('@').append(e.tenantId());
        }
        return sb.append(']').toString();
    }

    private EnterpriseAssistantExample() {
    }
}
