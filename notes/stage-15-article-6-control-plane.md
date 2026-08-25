# 从 Demo 到企业 Agent，需要补齐哪些控制面

> 配套蓝图：[architecture-stage-15.md](architecture-stage-15.md) §1 / §9 / §12 · 业务场景：[stage-15-business-scenario.md](stage-15-business-scenario.md) §十一
> 对应实现：`agent-enterprise` 四包 + `EnterpriseAgentFactory` / `EnterpriseAssistant` · 89 个新增测试
> 全剧本：[EnterpriseAssistantExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/EnterpriseAssistantExample.java) T1–T5
> 这是 Stage 15 系列的第 6 篇，也是第一个领域 Profile 的收口。下一站是 Stage 16 Tavern Profile。

---

## 1. 我今天要解决什么问题

前五篇分别拆了 Agent/Workflow、请求链、业务接入、治理模型和租户隔离。拆开看都像「加了几个类」。合起来要回答的是规划原文那句话：

> 完成一个企业客服或内部知识助手，至少具备：登录识别、知识检索、业务工具、敏感审批、审计、失败恢复。

Demo Agent 通常具备其中零到两项：能问答，偶尔能调一个 mock 工具。企业能用，缺的不是更长的 system prompt，而是一组**控制面**——请求在组织里必须被识别、限制、记账、留痕、可恢复。

今天要解决的问题是：从 Demo 到企业 Agent，到底要补哪些控制面？哪些已经在 Stage 15 落地？哪些诚实留到后面？以及这件事如何证明「同一 Runtime 支撑三类场景」的第一跳成立。

---

## 2. 为什么会有这个概念

Stage 1-14 的 Runtime 能跑，隐含了五个对 Demo 无害、对企业致命的假设：

```text
1. 调用方假设  调用方是无面的程序，没有「谁在问」
2. 边界假设    单租户自用，数据天然属于「我」
3. 知识假设    预训练知识够用，或调用方自己拼 prompt
4. 失败假设    run 失败就失败，重跑即可
5. 成本假设    调用方自己管钱，框架不管预算
```

这五个假设一破，就会出现五种企业不可接受的行为：越权操作、跨租户泄漏、无出处回答、重复退款、月底才发现账单爆了。

控制面的意思是：在机制层之上，为每次请求补上主人、边界、出处、闸门、证据和归属。它不是新的 AgentLoop，也不是新的产品壳。判断标准在蓝图里写得很清楚：

```text
Runtime（1-12）  机制：loop / 治理 / 恢复 / 记忆，不知道 Tenant
产品层（13）     声明：YAML 装配，TenantAgentConfig 是配置覆盖
Profile（15-17） 领域：把机制翻译成场景对象
```

`Tenant` 在 Runtime 里不存在，在 Enterprise Profile 里是一等公民。Profile 的 bug 不该先去改 Runtime。Stage 15 用两处枚举加法验证了这条纪律。

---

## 3. 它解决了什么问题

对照规划验收六条，控制面和实现一一对应：

```text
登录用户识别   TenantRegistry.login → RequestContext
               测试：TenantRegistryTest 五类 fail-closed

知识检索       KnowledgeBase / KnowledgeTool
               测试：KnowledgeBaseTest / KnowledgeToolTest
               知识即记忆：MemoryType.KNOWLEDGE + tenant scope

业务工具调用   Factory.tool(...) + 角色矩阵 AUTO 档
               示例：query_order；真实 HTTP 在装配层可选 HttpApiTool

敏感操作审批   工具层 GovernedToolExecutor + 任务层 EnterpriseTaskManager
               测试：approvalRideAlong / approveResumesWithoutRerun

审计记录       EnterpriseAuditTrail + EnterpriseAuditEvent
               测试：denialsAttributed / tenantAndUserCuts

运行失败恢复   RunManager.resume + FileCheckpointStore + recover
               测试：crashRecoveryFromCheckpointFiles
```

「需要支持」的九项里，成本和 SLA 只做了 v1 轻量版：`CostLedger` 是企业域**两维**账本（租户 / 用户，跨 run 累计）。时间窗、模型路由、仪表盘、以及更完整的多维预算，属于 Stage 18 `BudgetBook`。不要把本阶段的账本说成五维预算系统。

依赖面本身也是控制面：`agent-enterprise` → core + memory + security + workflow，不依赖 product / channel / scheduler。少一条依赖，就少一条「领域层误用声明层」的路径。

---

## 4. 核心抽象和架构

把五篇的对象收成一张控制面图：

```text
控制面                 一等对象                         贯穿载体
─────────────────────────────────────────────────────────────
身份                   Tenant / User / TenantRegistry   RequestContext
隔离                   MemoryScope.TENANT               ctx.memoryScopes()
知识                   KnowledgeEntry / KnowledgeBase   KnowledgeTool(tid)
权限                   RoleBasedPermissionChecker       ctx.user().roles()
工具审批               ToolPolicy + ToolApprovalService GovernedToolExecutor
任务审批               BusinessTask / TaskApprovalRecord TaskApprovalBridge
审计                   EnterpriseAuditEvent             EnterpriseAuditTrail
成本                   CostLedger（两维）               requireBudget / record
恢复                   RunManager / CheckpointStore     task.currentRunId()
装配                   EnterpriseAgentFactory           forRequest(ctx)
入口                   EnterpriseAssistant              ask / submitTask
```

`RequestContext` 是所有控制面的显式总线：权限、审计、记忆、知识租户、成本都从它读，不从 ThreadLocal 读。`EnterpriseAgentFactory.forRequest` 是控制面的汇合点：共享无状态件，克隆有身份件。

里程碑和包的对应保持实现原样：

```text
M15.1 tenant/     身份与隔离白名单
M15.2 knowledge/  RAG v1（keyword）
M15.3 govern/     权限、归属审计、两维账本
M15.4 task/       任务投影与恢复
M15.5 根包        工厂 + 门面 + Example 全剧本
```

四包 19 文件 + 装配 2 文件，测试 89 个（tenant 27 + knowledge 18 + govern 24 + task 12 + assistant 8）。存量改动只有 `MemoryScope.TENANT` 和 `MemoryType.KNOWLEDGE`。

蓝图 §12 的「本阶段不做」也是控制面清单的一部分，写出来避免把 v1 说成平台：

```text
向量 / 嵌入 / 重排          接口形状不变，换 retriever 即可
真实 SSO / OIDC            v1 是 apiKey + 内存表，验证的是「每请求有主人」
Web / REST 壳              库形态 + Example；HTTP 是更外的装配圈
服务身份完整接线            D4 字段位已留，IdentityResolver 留 v2
补偿事务                   reject 只取消不回滚
完整成本治理               归 Stage 18 BudgetBook
分布式与 DB                TenantRegistry / CostLedger 内存；checkpoint 复用 File
审批人路由                 v1 由装配提供 console/callback，不按金额找人
```

没做不等于没设计。每一条都标了「机制验证了什么、升级换哪一层」。

---

## 5. 一次完整数据流

T1–T5 就是控制面联调，不再拆成单点。

```text
T0 装配
  注册 acme / globex，用户 alice=CSR / bob=主管 / carol=globex CSR / dave=窄额度
  ingest 两套政策，角色矩阵 + refund_order=REQUIRES_APPROVAL
  CostLedger、FileCheckpointStore、TaskApprovalBridge、MockModelClient

T1 身份控制面
  login → scopes / actor 可见；错误 key 进不来

T2 知识 + 审计 + 成本
  ask → 预算闸 → 检索本租户政策 → 审计归属 → 857 tokens 入两维账本

T3 工具审批控制面
  CSR 触发 refund_order → APPROVED + EXECUTED，loop 不停

T4 任务审批 + 恢复控制面
  submitTask → WAITING_APPROVAL → bob 批准 → resume → DONE
  prepare 不重跑（副作用安全）

T5 隔离 + 预算控制面
  carol 只见 globex；dave 第二问 BudgetExceededException，零消耗
```

一条请求同时穿过身份、预算、装配、权限、知识、审计、记账。长任务再叠加任务状态机和 checkpoint。Demo 通常只留「模型说出一句话」这一段。

---

## 6. 最小代码或实验

收口实验是门面测试，不是再写新机制。`EnterpriseAssistantTest` 八个用例覆盖控制面交叉：

| 测试 | 锁住的控制面 |
|---|---|
| `askFullChain` | 知识出处 + 审计归属 + 两维记账 |
| `tenantIsolationEndToEnd` | 跨租户知识与审计 |
| `budgetGate` | 事前闸、拒绝零消耗 |
| `approvalRideAlong` | 工具级审批搭车 |
| `taskPathThroughFacade` | 任务级审批与门面委托 |
| `taskPathRequiresManager` | 未装配任务路径时 fail-fast |
| `builderRequiresModel` | 工厂最小必备件 |
| `forRequestExposesChain` | 可装配、可不执行 |

崩溃恢复不在门面里，而在 `EnterpriseTaskManagerTest.crashRecoveryFromCheckpointFiles`：新 `RunManager` + 同一 `FileCheckpointStore` + 共享 `TaskApprovalBridge`，`recover` 后再 `approve`，`prepare` 全生命周期仍为 1。这是「失败假设破裂」之后的控制面：恢复入口在 task，执行状态在 run。

`EnterpriseAssistantExample.main` 把上述路径打成可跑剧本。防复发有两条：预算演示必须共享同一 `CostLedger`；`mvn exec:java` 前先 `compile`，否则跑的是旧字节码。

规划原文的递进句在收口处仍然成立，只是主语从 Runtime 换成了控制面：

```text
Stage 6  让 Run 能暂停-恢复
Stage 8  让 Agent 能记住
Stage 9  让 Agent 能被信任
Stage 13 让 Agent 能被搭出来
Stage 15 让 Agent 能进企业
         每个请求有主人，每个租户有边界，每次回答有出处，每分钱有归属
```

---

## 7. 常见误区

1. **「企业版 = 再写一个 Runtime」** —— 第一个 Profile 的反证：loop / 治理 / checkpoint / memory 全部复用。缺的是归属层。
2. **「控制面就是加鉴权和日志」** —— 鉴权只是身份和权限。还缺隔离、知识出处、双层审批、任务投影、两维账本、断点恢复。
3. **「CostLedger 已经是完整 FinOps」** —— 两维累计 + 请求级闸。五维预算、时间窗、降级路由在 Stage 18 `BudgetBook`。`TokenBudget` 仍是 per-Run。
4. **「ThreadLocal 也能做控制面」** —— 控制面必须可追踪。身份丢了，后面所有闸都在给错误的人放行。
5. **「没做的能力可以先假装做了」** —— 向量检索、SSO、Web 壳、服务身份接线、Saga 补偿、多实例持久化，蓝图 §12 全部列为不做。接口留缝，比假完成更重要。

---

## 8. 和相邻概念的区别

```text
Demo Agent vs Workflow Agent vs Enterprise Agent
  Demo：能回答问题
  Workflow：能按流程办事
  Enterprise：能在组织边界内负责任地办事

控制面 vs 机制 vs 产品壳
  机制：ReActAgentLoop / RunManager / MemoryStore / GovernedToolExecutor
  控制面：身份、隔离、审批、审计、成本、恢复的领域翻译
  产品壳：HTTP / 前端 / YAML（Stage 13），本阶段刻意不做

CostLedger vs TokenBudget vs BudgetBook
  TokenBudget：单次 Run
  CostLedger：租户 + 用户，跨 Run
  BudgetBook（Stage 18）：更完整的多维预算与治理

Stage 15 vs Stage 16 vs Stage 17
  15 Enterprise：归属层（谁在问 / 哪个租户 / 花谁的钱）
  16 Tavern：世界层（角色 / 关系 / 一局历史）
  17 Coding：变更层（补丁 / 工作区 / 测试裁判）
  三次都是 Profile，不是三次重写 Runtime
```

用户身份和 Agent 身份仍要分开：本阶段控制面先管「谁在要求」；「谁在执行」的 `ServiceAccount` 接线留 v2，但 `EnterpriseAuditEvent.agentName` 已占住字段位。

---

## 9. 我的设计判断

最重的一条：**从 Demo 到企业，是控制面补齐，不是模型升级。** 模型可以仍然是 `MockModelClient.scripted()`。全剧本零 LLM 依赖能验收，说明企业可用性首先是工程约束，其次才是生成质量。

第二条：**控制面要显式、可测、fail-closed。** 登录失败没有匿名用户，预算耗尽没有「先跑再算」，空白名单没有全库降级，硬禁不能被角色矩阵掀开。拒绝带证据（`EnterpriseAuthException` / `BudgetExceededException` 的 dimension、used、limit）。

第三条：**第一个 Profile 的价值是证明装配纪律。** 两处纯加法、四条 compile 依赖、请求作用域克隆、审批通道装配级生命周期——这些比 `EnterpriseAssistant` 这个类名更重要。下一个领域如果还要改 Runtime 才能表达自己的一等公民，说明机制层被污染了；如果只换领域对象，说明底座稳了。

本阶段明确没做的，也是控制面设计的一部分：v1 keyword RAG、apiKey 内存表、单进程、拒绝不回滚、成本不中途熔断。知道缺口在哪，Stage 18 才接得上，而不是在企业模块里偷偷长成第二个平台。

最终记忆句可以收成九个控制面动词：

```text
RequestContext     负责「谁」
Memory scope       负责「边界」
Knowledge          负责「依据」
Permission         负责「能不能」
Approval           负责「放不放行」（工具闸 + 任务节点）
Workflow           负责「怎么继续」
Audit              负责「留下证据」
CostLedger         负责「花了多少」（两维，不是五维）
BusinessTask       负责「这单办到哪」
```

门面 `EnterpriseAssistant` 只是把它们串成 `ask` / `submitTask`。真正的产物是这套责任模型。

---

## 10. 面试表达

> 「从 Demo 到企业 Agent，我补的是控制面而不是新 Runtime。Runtime 的五个假设——无身份、单租户、无企业知识、失败重跑、成本不管——在企业里全部破裂。Enterprise Profile 用 RequestContext 贯穿身份，用 scope 白名单做隔离，用 KnowledgeTool 做有出处的检索，用角色矩阵加双层审批做闸，用组合审计和两维 CostLedger 做证据与账单，用 BusinessTask 投影 Run 做恢复入口。依赖只有 core、memory、security、workflow。存量只加了 TENANT 和 KNOWLEDGE。完整五维预算留给 Stage 18。这是『同一 Runtime、三类场景』的第一跳。」

---

## 11. 下一篇连接什么

第一个领域 Profile 讲完了：归属层成立，Runtime 不必为企业概念开洞。下一阶段换领域模型再验证一遍——酒馆游戏要的不是 Tenant / CostLedger，而是角色、关系和世界状态。若 Stage 16 仍能零存量改动长出新领域，控制面和机制层的分工就被第二条证据钉住。

→ [stage-16-tavern-concepts.md](stage-16-tavern-concepts.md) · 蓝图：[architecture-stage-16.md](architecture-stage-16.md)
