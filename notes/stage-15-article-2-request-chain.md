# 企业 Agent 的完整请求链路

> 配套蓝图：[architecture-stage-15.md](architecture-stage-15.md) §5 / §6 T2 / D2 / D8 · 业务场景：[stage-15-business-scenario.md](stage-15-business-scenario.md)
> 对应实现：`EnterpriseAssistant` / `EnterpriseAgentFactory` / `RequestContext` / `CostLedger`
> 全剧本：[EnterpriseAssistantExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/EnterpriseAssistantExample.java) T1–T2
> 这是 Stage 15 系列的第 2 篇。上一篇分清了 Agent 路径和 Workflow 路径；本篇只拆同步问答这条链。

---

## 1. 我今天要解决什么问题

上一篇已经说明：企业里既有 `ask`，也有 `submitTask`。但大多数人第一次接触企业 Agent，问的仍是一句普通问题——「退货政策是什么？」这句话背后，Runtime 不能再只做 `agent.run(question)`。

企业要同时回答四件事：

```text
谁在问？     哪个租户的哪个员工
能不能花？   预算还够不够
能看什么？   知识、记忆、工具权限的边界
事后留下什么？审计归属和 Token 账单
```

今天要解决的问题是：一次同步问答，身份如何从登录贯穿到审计和记账，而且全程显式、可追踪。

---

## 2. 为什么会有这个概念

纯 ReAct 的请求模型是无面的：

```text
agent.run("退货政策是什么？")
```

字符串背后没有主人。Stage 9 的权限按工具名判定，Stage 8 的记忆按调用方传入的 scope 取值，Stage 7 的 `TokenBudget` 按单次 Run 计数。这些机制都能跑，但企业共享同一个助手实例时，请求上下文无法在装配期固定：Alice 和 Carol 打进同一进程，租户不同、角色不同、额度不同。

常见的「省事」做法是 ThreadLocal：

```text
请求到达 → ThreadLocal.set(ctx)
Agent 内部任意位置 → ThreadLocal.get()
```

这条路 Stage 14 的轨迹记录已经踩过：装饰器一旦跨线程，绑定静默失效。Web 容器的 ThreadLocal 是框架魔法，不是库语义。企业身份一旦丢，就会出现三种事故：权限串线、审计记错人、记忆跨租户泄漏。

所以 Stage 15 把「一次请求的身份快照」做成一等对象 `RequestContext`，并且规定它必须作为参数往下传。`EnterpriseAgentFactory.forRequest(ctx)` 每请求克隆执行链，而不是到隐式线程状态里取值。

---

## 3. 它解决了什么问题

完整同步链被收成一条门面方法。`EnterpriseAssistant.ask` 的 javadoc 把顺序写死了：

```text
requireBudget → forRequest(ctx) → agent.run → record usage
```

它同时兑现四条纪律：

1. **先闸后跑**。额度耗尽时抛 `BudgetExceededException`，请求根本不启动，这是最便宜的失败。
2. **身份显式流动**。`ctx` 从 `TenantRegistry.login` 产生，经过 `ask` 进入工厂，再绑到权限检查器、审计视图、知识工具和记忆白名单。
3. **共享件与请求件分离**。`ModelClient`、业务工具、角色矩阵、`ToolPolicy`、审计台账共享；权限、审计视图、`KnowledgeTool`、记忆注入、用量跟踪按请求构造。
4. **花掉的 Token 无论成败都记**。记账放在 `finally`：模型调用失败不等于没消耗。

Enterprise 仍然不是新 Runtime。`ask` 内部用的还是 `SimpleAgent` + `ReActAgentLoop` + `GovernedToolExecutor`。Profile 做的是把归属层接到这条已经存在的链上。

---

## 4. 核心抽象和架构

```text
员工 ──login(tenantId, userId, apiKey)──▶ TenantRegistry
                                              │
                                              ▼
                                        RequestContext
                                        tenant / user / sessionId
                                        memoryScopes() / actor()
                                              │
员工 ──ask(ctx, question)──▶ EnterpriseAssistant
                                │
                    ┌───────────┴───────────┐
                    ▼                       ▼
            CostLedger.requireBudget   factory.forRequest(ctx)
            （事前闸）                   （克隆请求作用域执行链）
                                        │
                    ┌───────────────────┴───────────────────┐
                    │ EnterpriseAgent                       │
                    │  TrackingModelClient                  │
                    │  KnowledgeTool.forTenant(tid)         │
                    │  RoleBasedPermissionChecker.forRequest│
                    │  EnterpriseAuditTrail.forRequest      │
                    │  MemoryContextBuilder(scopes)         │
                    │  ReActAgentLoop + GovernedToolExecutor│
                    └───────────────────┬───────────────────┘
                                        │ run(question)
                                        ▼
                              finally CostLedger.record
                              auditTrail.byUser / byTenant
```

关键类型都在真实包里：

| 抽象 | 包 | 在链上的职责 |
|---|---|---|
| `TenantRegistry` | `enterprise.tenant` | 登录闸门，产出 `RequestContext`；五类失败全部 fail-closed |
| `RequestContext` | `enterprise.tenant` | 身份快照；`memoryScopes()` 是检索白名单 SSOT，`actor()` 是审计归属 |
| `EnterpriseAgentFactory` | `enterprise` | `forRequest(ctx)` 克隆请求件，共享无状态件 |
| `EnterpriseAgentFactory.EnterpriseAgent` | 工厂内部类 | `run` + `promptTokens` / `completionTokens` |
| `TrackingModelClient` | 工厂内部装饰器 | 组合 `ModelClient`，把 `TokenUsage` 累进 `UsageTracker` |
| `CostLedger` | `enterprise.govern` | 租户/用户两维账本：`requireBudget` + `record` |
| `EnterpriseAssistant` | `enterprise` | 统一入口，把顺序锁死 |

`CostLedger` 只做企业域两维账本（per-tenant / per-user，跨 run 累计）。时间窗、模型路由、五维预算不在本阶段——那是 Stage 18 的 `BudgetBook`。`TokenBudget` 仍是 per-Run 计数器，哲学同源，维度不同，Enterprise 不依赖 `agent-scheduler`。

租户额度读的是登录快照：`ctx.tenant().monthlyTokenBudget()`，`Tenant.hasBudget()` 为真（`>= 0`）才闸；`UNLIMITED_BUDGET = -1` 与 Stage 12 `ServiceAccount` 同一惯例。用户额度不在 `User` record 上，而在装配注入的 `Map<String, Long>`——示例里只有 dave 被写成 `new CostLedger(Map.of("u-dave", 50L))`。`CostLedger.tenantOnly()` 是「只闸租户、用户无限」的工厂。`BudgetExceededException` 带 `Dimension.TENANT|USER`、`used()`、`limit()`，拒绝必须可对账。

`TrackingModelClient` 只装饰同步 `chat`：从 `ModelResponse.usage()` 取出 prompt/completion 累进 `UsageTracker`。v1 企业路径是同步问答，`stream` 原样转交、不记账。这是组合优于修改：`agent-core` 的 `ModelClient` 一行未改。

---

## 5. 一次完整数据流

对照 `EnterpriseAssistantExample` 的 T1 / T2。

**T1 · 登录识别**

```java
RequestContext alice = registry.login("acme", "u-alice", "key-alice");
// tenant=acme
// scopes=[tenant:acme, user:u-alice]
// actor=user:u-alice
```

`login` 失败的五条路径都抛 `EnterpriseAuthException`，不会降级出匿名上下文：未知租户、租户 `SUSPENDED`、未知用户、用户归属不一致、apiKey 不匹配。`TenantRegistryTest` 对这五类分别做了 evidence 断言（`unknownTenantRejected` / `suspendedTenantRejected` / `unknownUserRejected` / `tenantMismatchRejected` / `wrongKeyRejected`）。`sessionIdsAreFresh` 证明每次 login 的 `sessionId` 都是新的 `sess-` UUID；`memoryScopesWhitelist` 和 `actorString` 锁住白名单与 `user:{uid}` 格式。凭证不在 `User` 上：`lookups` 只能 `findUser`，拿不到 apiKey。

**T2 · RAG 问答 + 审计 + 记账**

```text
assistant.ask(alice, "退货政策是什么？")

1. CostLedger.requireBudget(alice)
   acme 月度预算 100_000，alice 个人额度未单独收紧 → 放行

2. factory.forRequest(alice)
   - 注册共享业务工具
   - KnowledgeTool.forTenant(knowledge, "acme")   // 模型改不了租户
   - RoleBasedPermissionChecker.forRequest(matrix, policy, [agent:csr])
   - auditTrail.forRequest(alice, "support-bot")
   - MemoryContextBuilder(scopes=[tenant:acme, user:u-alice])
   - TrackingModelClient 包住 MockModelClient

3. EnterpriseAgent.run("退货政策是什么？")
   模型调 search_knowledge({"query":"policy"})
   → KnowledgeBase.search("acme", "policy", topK)
   → 只命中 acme 的《退货政策》
   → 终答引用标题

4. finally record(alice, 812, 45)
   tenantUsed("acme") += 857
   userUsed("u-alice") += 857

5. auditTrail.byUser("u-alice")
   search_knowledge / EXECUTED by u-alice@acme
```

整条链没有 ThreadLocal。`ctx` 出现在 `ask` 的参数列表里，也出现在 `forRequest`、`requireBudget`、`record`、`auditTrail.forRequest(ctx, agentName)` 的参数列表里。「这个权限为什么是 deny」的答案在调用栈上。

`forRequest` 的五步克隆对应源码注释，建议对着 `EnterpriseAgentFactory` 读一遍：

```text
1. InMemoryToolRegistry：共享业务工具 + KnowledgeTool.forTenant(kb, ctx.tenantId())
2. TrackingModelClient + UsageTracker：给事后 record 提供 prompt/completion
3. RoleBasedPermissionChecker.forRequest(matrix, policy, ctx.user().roles())
4. auditTrail.forRequest(ctx, agentName) → 请求作用域 AuditLogger
5. MemoryContextBuilder(MemoryRetriever(store), ctx.memoryScopes(), ...)
   再交给 SimpleAgent + ReActAgentLoop(GovernedToolExecutor)
```

`submitTask` 走同一道 `requireBudget`，然后委托 `EnterpriseTaskManager.submit`。未配置 `taskManager` 时，`taskPathRequiresManager` 断言抛 `UnsupportedOperationException`。两条入口共享闸门，不共享执行器。`builderRequiresModel` 则锁住工厂最小必备件：没有 `ModelClient` 不能 `build()`。

`CostLedgerTest` 把账本从门面里拆出来单测：`withinBudgetPasses` / `tenantBudgetExhausted` / `userBudgetExhausted` 覆盖两级闸；`unlimitedNeverThrows` 锁住 `-1` 与缺席用户；`recordAccumulates` 与 `recordValidation` 锁累加和负数拒绝。门面的 `budgetGate` 是「闸在 ask 之前」的集成证明，账本测试是「两维独立」的单元证明，两层都要看。

记账口径也要说清：`record` 把 `promptTokens + completionTokens` 同一笔加进租户计数器和用户计数器，查询是 `tenantUsed` / `userUsed`。闸的比较是 `used >= limit`，没有「预估本次再加」——所以额度刚好用尽的下一请求才会被拒，当前请求一旦过闸就会跑完。这是请求级粒度的直接后果。

`CostLedger` 和 `EnterpriseTaskManager` 在门面上都可为 null：不配账本就跳过闸与记账，不配任务管理器就禁用长任务入口。这是有意的渐进装配，不是半成品。高级访问 `auditTrail()` / `costLedger()` / `taskManager()` / `forRequest(ctx)` 留给测试和排障，业务调用应走 `ask` / `submitTask`，以免绕过预算闸。

---

## 6. 最小代码或实验

`EnterpriseAssistant.ask` 本身就是最小完整链，顺序不要记错：

```java
public String ask(RequestContext ctx, String question) {
    if (costLedger != null) {
        costLedger.requireBudget(ctx);          // 事前闸
    }
    EnterpriseAgent agent = factory.forRequest(ctx);
    try {
        return agent.run(question);
    } finally {
        if (costLedger != null) {
            costLedger.record(ctx, agent.promptTokens(), agent.completionTokens());
        }
    }
}
```

端到端锁在 `EnterpriseAssistantTest.askFullChain`：

```text
答案 = 「根据退货政策：30 天无理由退货。」
byUser("u-alice") 恰有 1 条 search_knowledge，tenantId=acme
审计 result 含 "acme policy"（用审计证据证明检索隔离）
tenantUsed("acme") = userUsed("u-alice") = 857
```

预算闸锁在同文件的 `budgetGate`：alice 额度 100，第一次花完 100，第二次抛 `BudgetExceededException`，`userUsed` 仍是 100。拒绝后零消耗，是「闸在前」的可执行证明。

`forRequestExposesChain` 则证明工厂可以只装配不执行——测试和排障时能拿到请求作用域的 `EnterpriseAgent`，而不必先烧一次模型。

---

## 7. 常见误区

1. **「身份放 ThreadLocal，调用处干净」** —— 调用处干净，事故处不干净。异步、工作流恢复、装饰器换线程时，身份会丢或串。Stage 15 用微量对象分配换可追踪性。
2. **「预算闸可以在 run 中途熔断」** —— v1 粒度是请求级。模型调用已经发出后再砍，只省一半钱，还留下半成品状态。`CostLedger` 的 javadoc 把这条诚实边界写死了。
3. **「记账只记成功请求」** —— tokens burned are tokens burned。`finally` 不是风格问题，是账本正确性。
4. **「每请求 new 一个 CostLedger」** —— `EnterpriseAssistantExample` T5 的防复发记录：闸读的是上一笔 `record` 写进去的数，必须是同一本账。两本新账永远扣不响闸门。
5. **「CostLedger 就是完整成本治理」** —— 它只做租户/用户两维累计。五维预算、时间窗、路由降级属于 Stage 18 `BudgetBook`。

---

## 8. 和相邻概念的区别

```text
RequestContext vs AgentConfig
  RequestContext：一次请求的主人（租户、用户、角色、session）
  AgentConfig：一次装配的机制（模型、工具、maxSteps、记忆注入器）
  前者每请求变，后者工厂级共享 + 请求级克隆

ask vs submitTask
  ask：同步问答，走 Agent loop，工具审批在 loop 内
  submitTask：长任务，走 RunManager，任务审批会暂停 Run
  两者共用 requireBudget，但后半段执行器不同

CostLedger vs TokenBudget
  TokenBudget（Stage 7）：per-Run 计数器
  CostLedger（Stage 15）：per-tenant / per-user，跨 run 累计
  BudgetBook（Stage 18）：更完整的多维预算
  三者 fail-closed 哲学一致，不要把类名混用

forRequest 克隆 vs 全局单例 Agent
  Demo 可以一个 Agent 打天下
  企业必须按请求绑角色、审计、知识租户和记忆白名单
  共享的是无状态件，不是身份
```

依赖正交也在这条链上可见：`EnterpriseAssistant` 不引用 `agent-product` 的 `TenantAgentConfig`，也不引用 `agent-channel` 的 `IdentityResolver`。产品层的「租户」是 YAML 覆盖，企业层的「租户」是领域实体，两套概念不同源。

---

## 9. 我的设计判断

最重的一条：**身份必须出现在方法签名上。** 看不见的上下文不是封装，是债务。`RequestContext` 作为参数往下传，审查代码时能回答「这个 `search_knowledge` 为什么只能看到 acme」——因为它是 `KnowledgeTool.forTenant(kb, ctx.tenantId())` 构造出来的，不是从线程里摸出来的。

第二条：**装配边界按生命周期切，不按调用方便切。** 和上一篇的 `TaskApprovalBridge` 是同一哲学的另一面。权限检查器、请求审计视图、知识工具跟请求同生共死；`ModelClient`、角色矩阵、审计台账、`CostLedger` 跟进程同生共死。

第三条：**事前闸是产品承诺，不是计费优化。** SLA 承诺的是额度内服务。默默烧过预算再在月底对账，是把合同义务推给财务。`BudgetExceededException` 带着 `dimension` / `used` / `limit`，拒绝本身必须可解释。

零存量改动除 `MemoryScope.Kind.TENANT` 和 `MemoryType.KNOWLEDGE` 两处纯加法外，这条请求链没有改 `agent-core`。`TrackingModelClient` 用组合拿到用量，而不是去改 `ModelClient` 接口。

---

## 10. 面试表达

> 「企业同步问答不是 `agent.run(question)`，而是一条带主人的请求链。登录产出 `RequestContext`，`EnterpriseAssistant.ask` 严格按 requireBudget → forRequest → run → finally record 执行。身份显式传递，不用 ThreadLocal；每请求克隆权限、审计、知识工具和记忆白名单，共享无状态的模型客户端和业务工具。CostLedger 是租户/用户两维账本，和 Stage 7 的 per-Run TokenBudget 不是同一个东西，完整五维预算在 Stage 18。这条链证明 Enterprise 是归属层，不是新 Runtime。」

---

## 11. 下一篇连接什么

请求链已经能跑通问答。下一篇问：模型调用的 `search_knowledge`、`query_order`、`refund_order` 到底怎么接到真实业务系统？知识工具和业务工具的边界在哪？为什么 Enterprise 不依赖 `HttpApiTool` 所在的产品模块，却仍能接入 HTTP API？

→ [stage-15-article-3-business-systems.md](stage-15-article-3-business-systems.md)
