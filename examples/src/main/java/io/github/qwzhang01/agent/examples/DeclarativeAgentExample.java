package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.product.AgentRegistry;
import io.github.qwzhang01.agent.product.ProductBootstrapper;
import io.github.qwzhang01.agent.product.dag.ConditionRegistry;
import io.github.qwzhang01.agent.product.dag.DagSpec;
import io.github.qwzhang01.agent.product.dag.WorkflowDagCodec;
import io.github.qwzhang01.agent.product.prompt.PromptChannel;
import io.github.qwzhang01.agent.product.prompt.PromptManager;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.AgentNode;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stage 13 acceptance: a complete agent defined by ONE YAML file - managed
 * prompt (promptRef), config-declared HTTP tool, windowed memory, tenant
 * overlay - started with zero agent-side Java, plus a DAG export of its
 * workflow for visualization.
 * <p>
 * Run: see the printed walkthrough. The platform main below is one-time
 * scaffolding; the "business side" is only the YAML file it drops into
 * the agents directory.
 */
public final class DeclarativeAgentExample {

    public static void main(String[] args) throws Exception {
        // ---- T0: platform pre-registration (one-time, platform team) ----
        PromptManager prompts = new PromptManager();
        prompts.publish("support-system", "你是七七商城的客服助手，称呼用户为「老板」。");
        prompts.publish("support-system", "你是七七商城的客服助手（灰度新版，更简洁）。",
                PromptChannel.CANARY);

        Workflow supportFlow = Workflow.builder("support-flow")
                .node(ActionNode.of("greet", ctx -> "您好，老板"))
                .edge(Workflow.START, "greet")
                .edge("greet", Workflow.END)
                .build();

        ProductBootstrapper bootstrapper = ProductBootstrapper.builder()
                .model("mock", MockModelClient.scripted()
                        .respondText("老板您好，订单 8899 已发货，预计明天到。"))
                .model("budget", MockModelClient.scripted().respondText("（预算模型）订单已发货。"))
                .tool("order-query", new OrderQueryTool())
                .promptManager(prompts)
                .workflow("support-flow", supportFlow)
                .tenantConfig(new io.github.qwzhang01.agent.product.tenant.TenantAgentConfig(
                        "acme", null, null, java.util.Set.of("refund-search"), null))
                .build();

        // ---- T1: business side - ONE YAML file, zero Java ----
        Path agentsDir = Files.createTempDirectory("agents");
        Files.writeString(agentsDir.resolve("support-bot.yaml"), """
                apiVersion: v1
                kind: Agent
                metadata:
                  name: support-bot
                  tenant: acme
                spec:
                  persona:
                    promptRef: { name: support-system }   # managed prompt (M13.4)
                    temperature: 0.3
                  model:
                    provider: mock
                    fallback: budget
                  tools:
                    - ref: order-query                   # registered tool (M13.1)
                  memory:
                    shortTerm: { strategy: window, maxMessages: 20 }
                  workflow: support-flow                  # DAG export target (M13.5)
                """);

        AgentRegistry agents = bootstrapper.startAll(agentsDir);

        // ---- Run the agent ----
        Agent bot = agents.get("support-bot").orElseThrow();
        String reply = bot.run("帮我查下订单 8899");
        System.out.println("[run] " + reply);

        // ---- T4: DAG export for visualization (M13.5) ----
        ConditionRegistry conditions = new ConditionRegistry();
        DagSpec dag = new WorkflowDagCodec().toDag(
                bootstrapper.context().workflow("support-flow").orElseThrow(), conditions);
        System.out.println("[dag] " + dag.toJson());

        System.out.println("[done] agent defined by YAML: persona + tools + memory + workflow");
    }

    private record OrderQueryTool() implements Tool {
        @Override
        public String getName() {
            return "order-query";
        }

        @Override
        public String getDescription() {
            return "查询订单状态";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}";
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "订单 " + arguments.path("orderId").asText() + " 已发货";
        }
    }

    private DeclarativeAgentExample() {
    }
}
