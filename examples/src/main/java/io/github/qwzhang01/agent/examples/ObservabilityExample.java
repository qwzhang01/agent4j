package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.channel.identity.AgentIdentity;
import io.github.qwzhang01.agent.channel.identity.IdentityScope;
import io.github.qwzhang01.agent.channel.identity.ServiceAccount;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.FallbackModelClient;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.observability.cost.BudgetBook;
import io.github.qwzhang01.agent.observability.cost.BudgetCheck;
import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;
import io.github.qwzhang01.agent.observability.cost.ChannelQuota;
import io.github.qwzhang01.agent.observability.cost.CostMeter;
import io.github.qwzhang01.agent.observability.cost.PricingTable;
import io.github.qwzhang01.agent.observability.eval.EvalCase;
import io.github.qwzhang01.agent.observability.eval.EvalDataset;
import io.github.qwzhang01.agent.observability.eval.EvalReport;
import io.github.qwzhang01.agent.observability.eval.EvaluationRunner;
import io.github.qwzhang01.agent.observability.eval.Expectation;
import io.github.qwzhang01.agent.observability.metrics.MetricsCollector;
import io.github.qwzhang01.agent.observability.metrics.MetricsSink;
import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import io.github.qwzhang01.agent.observability.metrics.ObservingModelClient;
import io.github.qwzhang01.agent.observability.metrics.ObservingToolExecutor;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.metrics.ToolCallMetrics;
import io.github.qwzhang01.agent.observability.routing.BudgetAwareRouter;
import io.github.qwzhang01.agent.observability.routing.ModelRouter;
import io.github.qwzhang01.agent.observability.routing.RouteDecision;
import io.github.qwzhang01.agent.observability.routing.RoutingModelClient;
import io.github.qwzhang01.agent.observability.version.ComponentVersion;
import io.github.qwzhang01.agent.observability.version.CostDashboard;
import io.github.qwzhang01.agent.observability.version.RunRegistry;
import io.github.qwzhang01.agent.product.prompt.PromptManager;
import io.github.qwzhang01.agent.product.prompt.PromptVersion;
import io.github.qwzhang01.agent.security.AuditEvent;
import io.github.qwzhang01.agent.security.GovernedToolExecutor;
import io.github.qwzhang01.agent.security.InMemoryAuditLogger;
import io.github.qwzhang01.agent.security.PermissionChecker;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import io.github.qwzhang01.agent.trace.record.RecordingAgent;
import io.github.qwzhang01.agent.trace.record.RecordingModelClient;
import io.github.qwzhang01.agent.trace.record.RecordingToolExecutor;
import io.github.qwzhang01.agent.trace.record.TrajectoryRecorder;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Stage 18 acceptance script (M18.5): an operations day in the life of one
 * observable, budget-gated, routed, evaluated, version-recorded agent - the
 * whole T0-T7 blueprint play plus F-series failure branches, ZERO LLM
 * (premium/cheap are two scripted {@link MockModelClient}s).
 * <p>
 * The wiring demonstrated here IS the deliverable: metrics at the decorator
 * boundary (loop untouched), budgets as pre-flight gates (warn and block
 * separated), routing as an explainable strategy composed over fallback,
 * failure trajectories mined into a regression gate, and the version triple
 * answering "what served this run".
 * <p>
 * Assembly glue highlights (D5, numbers-not-identities):
 * {@code ServiceAccount.monthlyTokenBudget} becomes a {@link ChannelQuota};
 * {@code PromptManager} versions become {@link ComponentVersion}s -
 * observability never imports channel/product. Tier wiring follows the M18.3
 * deviation-5 ruling: each candidate is {@code Named(Observing(...))} - the
 * naming wrapper stamps the tier onto the request (providers need their model
 * string anyway), the observing decorator inside it sees the priced model
 * name; the router itself stays zero-touch.
 * <p>
 * Run: {@code mvn -pl examples exec:java -Dexec.mainClass=io.github.qwzhang01.agent.examples.ObservabilityExample}
 */
public final class ObservabilityExample {

    private static final String USER_KEY = "alice";
    private static final String TENANT_KEY = "acme";
    private static final String CHANNEL_KEY = "eng";
    private static final String AGENT_KEY = "assist";

    public static void main(String[] args) throws Exception {
        // ================= T0: assembly (one-time) =================
        section("T0 assembly: pricing, four-dimension budgets, routing, observing decorators");

        PricingTable pricing = PricingTable.builder()
                .price("premium", 3_000_000L, 15_000_000L)   // $3 / $15 per M tokens
                .price("cheap", 500_000L, 1_500_000L)         // $0.5 / $1.5 per M tokens
                .build();
        CostMeter meter = new CostMeter(pricing);

        // console sink first: the budget book wires it as its alarm outlet (warn is "be SEEN")
        MetricsSink console = new MetricsSink() {
            @Override
            public void onModelCall(ModelCallMetrics m) {
                printf("    [METRICS] model=%s tokens=%d/%d/%d %s", m.model(),
                        m.promptTokens(), m.completionTokens(), m.totalTokens(),
                        m.success() ? "" : "FAILED");
            }

            @Override
            public void onToolCall(ToolCallMetrics t) {
                printf("    [METRICS] tool=%s %s%s", t.toolName(),
                        t.denied() ? "DENIED" : (t.success() ? "ok" : "failed"),
                        t.denied() ? " (governance signal, not a quality signal)" : "");
            }

            @Override
            public void onRun(RunMetrics r) {
                printf("    [RUN] %s -> %s tokens=%d cost=%d microUSD", r.runId(), r.status(),
                        r.tokenUsage().totalTokens(), r.costMicros());
            }

            @Override
            public void onAlarm(io.github.qwzhang01.agent.observability.cost.BudgetAlarmEvent a) {
                printf("    [BUDGET-WARN] %s=%s at %d%% of %d (run continues - warnings are seen, not enforced)",
                        a.dimension(), a.key(), a.percentUsed(), a.limitTokens());
            }
        };

        // Stage 12 placeholder cashed in (D5): the budget NUMBER travels, never the identity
        ServiceAccount engAccount = new ServiceAccount("svc-eng-bot-01",
                new AgentIdentity("eng-bot", "Engineering Bot", "team-eng-leads"),
                IdentityScope.capabilities("git.read"),
                50_000L, null, null);
        check("service account has a budget cap", engAccount.hasBudgetCap());
        ChannelQuota engQuota = new ChannelQuota(CHANNEL_KEY, engAccount.monthlyTokenBudget());
        printf("    ServiceAccount.monthlyTokenBudget -> ChannelQuota(%s, %d) [Stage 12 hook cashed]",
                engQuota.channelId(), engQuota.monthlyTokenBudget());

        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, USER_KEY, 10_000)
                .budget(BudgetDimension.TENANT, TENANT_KEY, 100_000)
                .budget(BudgetDimension.CHANNEL, engQuota.channelId(), engQuota.monthlyTokenBudget())
                .budget(BudgetDimension.AGENT, AGENT_KEY, 200_000)
                .budget(BudgetDimension.RUN, "run-f1", 2_000)   // F1 below
                .warnAtPercent(80)
                .alarmSink(console)
                .build();

        // prompt as an asset (Stage 13 reused, not rebuilt): v1 stable
        PromptManager prompts = new PromptManager();
        prompts.publish("support-system", "You are a careful support agent. Answer concisely.");

        // tiers: Named(Observing(mock)) - the name is what pricing keys on (M18.3 deviation 5)
        MockModelClient premiumMock = MockModelClient.scripted();
        MockModelClient cheapMock = MockModelClient.scripted();
        MockModelClient backupMock = MockModelClient.scripted();
        MetricsCollector collector = new MetricsCollector(meter);
        CostDashboard.AttributionSink dashboardSink = CostDashboard.attributionSink(meter,
                Map.of(BudgetDimension.TENANT, TENANT_KEY,
                        BudgetDimension.CHANNEL, CHANNEL_KEY,
                        BudgetDimension.AGENT, AGENT_KEY,
                        BudgetDimension.USER, USER_KEY));
        MetricsSink fanOut = fanOut(collector, dashboardSink, console);

        ModelClient premiumTier = named("premium", ObservingModelClient.wrap(premiumMock, fanOut));
        ModelClient cheapTier = named("cheap",
                ObservingModelClient.wrap(new FallbackModelClient(cheapMock, backupMock), fanOut));
        RoutingModelClient routed = new RoutingModelClient(
                Map.of("premium", premiumTier, "cheap", cheapTier),
                printing("routing", new BudgetAwareRouter("premium", "cheap", 25)),
                () -> ModelRouter.BudgetSnapshot.of(
                        book.remainingOf(BudgetDimension.USER, USER_KEY),
                        book.limitOf(BudgetDimension.USER, USER_KEY)));

        // governance (Stage 9) INSIDE the observing tool decorator: [DENIED] texts get observed
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool("echo", "echoes its argument", "echo:"));
        registry.register(tool("dangerous_tool", "side-effect tool", "did something scary"));
        InMemoryAuditLogger audit = new InMemoryAuditLogger();
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("dangerous_tool", ToolPermission.DENY);
        ToolExecutor governed = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(policy))
                .auditLogger(audit)
                .build();

        PromptVersion v1 = prompts.resolve("support-system", null, null).orElseThrow();
        AgentConfig config = new AgentConfig("assist", v1.content(), routed, registry, 5);
        SimpleAgent agent = new SimpleAgent(config,
                new ReActAgentLoop(ObservingToolExecutor.wrap(governed, fanOut)));

        RunRegistry runRegistry = new RunRegistry();
        printf("    assembled: Routing(premium=NAMED(OBS(mock)), cheap=NAMED(OBS(FALLBACK(cheap, backup))))");
        printf("              + OBS(GOVERNED(tools)) - the loop itself: untouched");

        // ================= T1: normal run, fully visible =================
        section("T1 normal run: metrics at the boundary, honest ledger after");
        premiumMock.respondToolCalls(ToolCall.of("c1", "echo", "{}"));
        premiumMock.respond(new ModelResponse("Summary: the report covers three items.",
                null, "stop", new ModelResponse.TokenUsage(800, 200, 1_000)));

        RunMetrics t1 = runAgent(agent, collector, runRegistry, "run-t1", prompts, "premium");
        check("T1 two model calls (tool turn + answer turn)", t1.modelCallCount() == 2);
        check("T1 one tool call", t1.toolCallCount() == 1);
        check("T1 cost 5400 microUSD (800 x $3/M + 200 x $15/M)", t1.costMicros() == 5_400);
        bookUsage(book, t1);
        printf("    ledger: user=%d/%d tenant=%d/%d channel=%d/%d agent=%d/%d",
                book.usedOf(BudgetDimension.USER, USER_KEY), book.limitOf(BudgetDimension.USER, USER_KEY),
                book.usedOf(BudgetDimension.TENANT, TENANT_KEY), book.limitOf(BudgetDimension.TENANT, TENANT_KEY),
                book.usedOf(BudgetDimension.CHANNEL, CHANNEL_KEY), book.limitOf(BudgetDimension.CHANNEL, CHANNEL_KEY),
                book.usedOf(BudgetDimension.AGENT, AGENT_KEY), book.limitOf(BudgetDimension.AGENT, AGENT_KEY));

        // ================= T2: warning fires, run proceeds =================
        section("T2 warn at 83%: the alarm is SEEN, nothing is blocked");
        book.recordUsage(BudgetDimension.USER, USER_KEY, 7_300);   // -> 8300/10000 = 83%
        BudgetCheck t2 = book.requireBudget(BudgetDimension.USER, USER_KEY, 600);
        check("T2 WARN, not DENIED - the warning line never blocks", t2 instanceof BudgetCheck.Warn);
        printf("    (the [BUDGET-WARN] line above was printed by the alarm sink, not by this printf)");

        // ================= T3: budget-driven downgrade =================
        section("T3 downgrade: 17% remaining < 25% threshold -> cheap, with a reason");
        cheapMock.respond(new ModelResponse("Cheap model: summary ready.",
                null, "stop", new ModelResponse.TokenUsage(500, 150, 650)));
        RunMetrics t3 = runAgent(agent, collector, runRegistry, "run-t3", prompts, "cheap");
        check("T3 cheap-tier pricing (500 x $0.5/M + 150 x $1.5/M = 475)",
                t3.costMicros() == 475);
        bookUsage(book, t3);
        printf("    downgrade is not denial of service - it is lower cost density for what remains");

        // ================= T4: exhausted -> honest refusal =================
        section("T4 exhausted: fail-closed, refuse rather than overdraft");
        book.recordUsage(BudgetDimension.USER, USER_KEY,
                book.limitOf(BudgetDimension.USER, USER_KEY) - book.usedOf(BudgetDimension.USER, USER_KEY));
        BudgetCheck denied = book.requireBudget(BudgetDimension.USER, USER_KEY, 1);
        check("T4 the gate says DENIED", denied instanceof BudgetCheck.Denied);
        // through the loop the refusal surfaces as an honestly-failed run (the loop's
        // catch-all converts boundary exceptions to ERROR status - by design, and the
        // budget message survives in lastError)
        AgentState refused = new AgentState();
        agent.run("one more, please", refused);
        check("T4 the run fails honestly", refused.getStatus() == AgentState.Status.ERROR);
        check("T4 the failure says why", refused.getLastError() != null
                && refused.getLastError().contains("budget exhausted"));
        printf("    through the loop: status=%s (\"%s\")", refused.getStatus(), refused.getLastError());
        // at the client boundary the exception TYPE is preserved (routing is the main path)
        try {
            routed.chat(ModelRequest.builder().model("premium")
                    .addMessage(ChatMessage.user("direct call")).build());
            check("T4 direct client call must refuse", false);
        } catch (BudgetExhaustedException e) {
            printf("    at the client boundary: BudgetExhaustedException remaining=%d limit=%d",
                    e.remaining(), e.limit());
        }
        printf("    honest failure: cheap cannot help when the budget is GONE - any call overdrafts");

        // ================= T5: three projections + failure mining =================
        section("T5 one denied tool call, three projections (governance / operations / training)");
        TrajectoryRecorder recorder = new TrajectoryRecorder();
        MockModelClient projMock = MockModelClient.scripted();
        projMock.respondToolCalls(ToolCall.of("c1", "echo", "{}"));             // allowed
        projMock.respondToolCalls(ToolCall.of("c2", "dangerous_tool", "{}"));   // denied by policy
        projMock.respond(new ModelResponse("Done, but I could not run everything.",
                null, "stop", new ModelResponse.TokenUsage(300, 100, 400)));
        ModelClient projModel = named("premium",
                ObservingModelClient.wrap(RecordingModelClient.wrap(projMock, recorder), fanOut));
        SimpleAgent projAgent = new SimpleAgent(
                new AgentConfig("assist", config.getSystemPrompt(), projModel, registry, 5),
                new ReActAgentLoop(ObservingToolExecutor.wrap(
                        RecordingToolExecutor.wrap(governed, recorder), fanOut)));
        collector.beginRun("run-proj", "assist");
        RecordingAgent.wrap(projAgent, recorder).run("summarize the incident");
        RunMetrics proj = collector.endRun(AgentState.Status.DONE, null);
        runRegistry.record(versionTriple(prompts, "premium", "f1"), proj);

        AuditEvent denial = audit.getAll().stream()
                .filter(e -> e.status() == AuditEvent.AuditStatus.DENIED)
                .findFirst().orElseThrow();
        printf("    projection 1 (governance, reader=auditor): %s %s -> %s",
                denial.status(), denial.toolName(), denial.reason());
        printf("    projection 2 (operations, reader=on-call): deniedToolCalls=%d of %d tool calls",
                proj.deniedToolCalls(), proj.toolCallCount());
        check("T5 the denial is a metric (denied spike = injection or regression signal)",
                proj.deniedToolCalls() == 1);
        Trajectory goodRun = recorder.last().orElseThrow();
        printf("    projection 3 (training, reader=RL): trajectory done=%s steps=%d messages=%d",
                goodRun.doneReason(), goodRun.steps().size(), goodRun.messages().size());
        printf("    same boundaries, three projections - zero duplicated instrumentation (D1)");

        // a FAILED run (mock outage -> loop ERROR) becomes regression material (D7)
        TrajectoryRecorder failureRecorder = new TrajectoryRecorder();
        MockModelClient outage = MockModelClient.scripted();   // empty: chat throws -> loop ERROR
        SimpleAgent failingAgent = new SimpleAgent(
                new AgentConfig("assist", config.getSystemPrompt(),
                        RecordingModelClient.wrap(outage, failureRecorder), registry, 5),
                new ReActAgentLoop(RecordingToolExecutor.wrap(governed, failureRecorder)));
        AgentState failState = new AgentState();
        RecordingAgent.wrap(failingAgent, failureRecorder).run("summarize the report", failState);
        check("T5 the outage run failed", failState.getStatus() == AgentState.Status.ERROR);
        Trajectory failed = failureRecorder.last().orElseThrow();
        printf("    failed run %s done=%s -> regression material", failed.runId(), failed.doneReason());

        EvalDataset dataset = EvalDataset.empty();
        dataset.add(EvalCase.of("case-hand-1", "what is the answer?", new Expectation.Contains("answer")));
        int imported = dataset.importFailures(List.of(failed), -0.4,
                t -> new Expectation.Contains("summary"));
        check("T5 failure mined into the dataset (fix one bug = dataset +1 case)", imported == 1);
        EvalCase mined = dataset.cases().get(1);
        check("T5 lineage intact (originRunId points at the incident)",
                failed.runId().equals(mined.originRunId()));
        printf("    dataset: %d cases; mined case originRunId=%s", dataset.size(), mined.originRunId());

        // ================= T6: regression gate =================
        section("T6 gate: publish the fix, replay the dataset, the verdict decides promotion");
        PromptVersion fix = prompts.publish("support-system",
                "You are a careful support agent. Answer concisely, always include a summary.",
                "canary");
        printf("    published %s (the fix under test)", fix);
        EvaluationRunner runner = new EvaluationRunner();
        EvaluationRunner.Subject fixedSubject =
                prompt -> new Expectation.Outcome("the answer, in summary form", 400, 1);
        EvalReport baseline = runner.evaluate(dataset, fixedSubject, null, 1.0);
        check("T6 first run honestly says BASELINE_ABSENT (establish, don't fake a comparison)",
                baseline.verdict() == EvalReport.Verdict.BASELINE_ABSENT);
        EvalReport promoted = runner.evaluate(dataset, fixedSubject, baseline, 1.0);
        check("T6 the fix passes against its baseline", promoted.verdict() == EvalReport.Verdict.PASS);
        printf("    first eval: %s; rerun: %s rate=%.2f -> canary may be promoted (13's rollback stays armed)",
                baseline.verdict(), promoted.verdict(), promoted.passRate());
        // counterfactual: above the 0.5 floor, but below the baseline - regression, not floor
        EvalReport regressed = runner.evaluate(dataset,
                prompt -> "case-hand-1".equals(prompt)
                        ? new Expectation.Outcome("I do not know", 10, 0)
                        : new Expectation.Outcome("the summary", 10, 0),
                promoted, 0.5);
        check("T6 one case regressed -> FAIL even above the floor "
                + "(fixing one thing while breaking another is the textbook case)",
                regressed.verdict() == EvalReport.Verdict.FAIL);
        printf("    counterfactual: %s (0.50 >= floor 0.50, but < baseline 1.00) - promotion blocked",
                regressed.verdict());

        // ================= T7: closure =================
        section("T7 closure: time-travel query + four-angle cost dashboard");
        Optional<io.github.qwzhang01.agent.observability.version.RunRecord> runT1 =
                runRegistry.byRunId("run-t1");
        check("T7 the version triple answers 'what served this run'", runT1.isPresent());
        printf("    byRunId('run-t1') -> %s", runT1.orElseThrow().combination());
        printf("    byRunId('run-t3') -> %s",
                runRegistry.byRunId("run-t3").orElseThrow().combination());

        CostDashboard dashboard = dashboardSink.dashboard();
        long total = dashboard.totalCost();
        check("T7 tenant angle == total", dashboard.totalOf(BudgetDimension.TENANT) == total);
        check("T7 channel angle == total", dashboard.totalOf(BudgetDimension.CHANNEL) == total);
        check("T7 agent angle == total", dashboard.totalOf(BudgetDimension.AGENT) == total);
        check("T7 user angle == total", dashboard.totalOf(BudgetDimension.USER) == total);
        printf("    total=%d microUSD | tenant=%d channel=%d agent=%d user=%d (one account, four angles)",
                total,
                dashboard.totalOf(BudgetDimension.TENANT), dashboard.totalOf(BudgetDimension.CHANNEL),
                dashboard.totalOf(BudgetDimension.AGENT), dashboard.totalOf(BudgetDimension.USER));
        Path csv = Path.of("target", "cost-dashboard-user.csv");
        dashboard.exportCsv(BudgetDimension.USER, csv);
        dashboard.exportJsonl(BudgetDimension.TENANT, Path.of("target", "cost-dashboard-tenant.jsonl"));
        printf("    exported %s (+ tenant JSONL) - the dashboard UI is a frontend concern",
                csv.toAbsolutePath());

        // ================= F-series: failure branches =================
        section("F1/F3/F6 branches (F2 reasons print at every [routing] line; F7 shown in T5)");
        // F1: single-run budget gate - the economic twin of Stage 17's behavioral [LIMIT]
        book.recordUsage(BudgetDimension.RUN, "run-f1", 2_000);
        BudgetCheck runGate = book.requireBudget(BudgetDimension.RUN, "run-f1", 100);
        check("F1 RUN gate fails closed on projection (used 2000 + est 100 > limit 2000)",
                runGate instanceof BudgetCheck.Denied);

        // F3: cheap dies mid-tier -> the Stage 1 fallback chain catches, zero-change reuse
        backupMock.respondText("backup model answers after the cheap tier died");
        RoutingModelClient f3 = new RoutingModelClient(
                Map.of("cheap", new FallbackModelClient(MockModelClient.scripted() /* dead: empty */, backupMock)),
                printing("F3", (req, budget) -> new RouteDecision("cheap", "forced for the demo")));
        String f3Answer = f3.chat(ModelRequest.builder().model("any")
                .addMessage(ChatMessage.user("hi")).build()).content();
        check("F3 fallback chain recovered", "backup model answers after the cheap tier died".equals(f3Answer));

        // F6: unpriced model -> CostMeter refuses to fake an account
        try {
            meter.costMicros(new ModelCallMetrics("mystery-model", 1L, 100, 40, 140, "stop", null));
            check("F6 must fail loud", false);
        } catch (IllegalArgumentException e) {
            printf("    F6 %s (fail-loud beats a silent 0 cost)", e.getMessage());
        }

        section("done: T0-T7 + F-series all green - the Stage 18 acceptance script");
    }

    // ============ assembly helpers ============

    /** Stamp a tier name onto every request - providers need it, pricing keys on it (M18.3 deviation 5). */
    private static ModelClient named(String modelName, ModelClient delegate) {
        return new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                return delegate.chat(withModel(request, modelName));
            }

            @Override
            public Stream<StreamEvent> stream(ModelRequest request) {
                return delegate.stream(withModel(request, modelName));
            }
        };
    }

    private static ModelRequest withModel(ModelRequest request, String modelName) {
        return new ModelRequest(modelName, request.messages(), request.tools(), request.temperature(),
                request.maxTokens(), request.stream(), request.responseFormat());
    }

    /** Broadcast boundary events to collector + dashboard + console, each isolated (side channels). */
    private static MetricsSink fanOut(MetricsCollector collector,
                                      CostDashboard.AttributionSink dashboard,
                                      MetricsSink console) {
        return new MetricsSink() {
            @Override
            public void onModelCall(ModelCallMetrics m) {
                for (MetricsSink sink : List.of(collector, dashboard, console)) {
                    try {
                        sink.onModelCall(m);
                    } catch (RuntimeException isolated) {
                        // a broken side channel must not break the run
                    }
                }
            }

            @Override
            public void onToolCall(ToolCallMetrics t) {
                for (MetricsSink sink : List.of(collector, console)) {
                    try {
                        sink.onToolCall(t);
                    } catch (RuntimeException isolated) {
                        // side channel isolation
                    }
                }
            }

            @Override
            public void onRun(RunMetrics r) {
                console.onRun(r);
            }

            @Override
            public void onAlarm(io.github.qwzhang01.agent.observability.cost.BudgetAlarmEvent a) {
                console.onAlarm(a);
            }
        };
    }

    /** Run one agent turn under the observing run context and register the version triple. */
    private static RunMetrics runAgent(SimpleAgent agent, MetricsCollector collector,
                                       RunRegistry registry, String runId,
                                       PromptManager prompts, String modelUsed) {
        collector.beginRun(runId, "assist");
        agent.run("summarize this for me");
        RunMetrics metrics = collector.endRun(AgentState.Status.DONE, null);
        registry.record(versionTriple(prompts, modelUsed, "f1"), metrics);
        printf("    [REGISTRY] %s -> %s", runId, registry.byRunId(runId).orElseThrow().combination());
        return metrics;
    }

    private static List<ComponentVersion> versionTriple(PromptManager prompts,
                                                        String model, String tools) {
        PromptVersion pv = prompts.resolve("support-system", null, null).orElseThrow();
        return List.of(
                new ComponentVersion(ComponentVersion.Kind.PROMPT, pv.name(), "v" + pv.version(), pv.channel()),
                ComponentVersion.of(ComponentVersion.Kind.MODEL, model, "2026-08"),
                ComponentVersion.of(ComponentVersion.Kind.TOOL, "core", tools));
    }

    /** Post-hoc honest ledger: real usage booked to every dimension. */
    private static void bookUsage(BudgetBook book, RunMetrics metrics) {
        long tokens = metrics.tokenUsage().totalTokens();
        book.recordUsage(BudgetDimension.USER, USER_KEY, tokens);
        book.recordUsage(BudgetDimension.TENANT, TENANT_KEY, tokens);
        book.recordUsage(BudgetDimension.CHANNEL, CHANNEL_KEY, tokens);
        book.recordUsage(BudgetDimension.AGENT, AGENT_KEY, tokens);
    }

    /** A router that prints every decision - reasons are the audit trail (D6/F2). */
    private static ModelRouter printing(String tag, ModelRouter delegate) {
        return (request, budget) -> {
            RouteDecision decision = delegate.route(request, budget);
            printf("    [%s] -> %s (%s)", tag, decision.modelId(), decision.reason());
            return decision;
        };
    }

    private static Tool tool(String name, String description, String prefix) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public String getParametersSchema() {
                return null;
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return prefix + (arguments == null ? "" : arguments.toString());
            }
        };
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void printf(String format, Object... args) {
        System.out.printf(format + "%n", args);
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            throw new IllegalStateException("ACCEPTANCE FAILED: " + what);
        }
        System.out.println("    [ok] " + what);
    }

    private ObservabilityExample() {
    }
}
