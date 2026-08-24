# Stage 15 学习笔记：Enterprise Agent 要解决什么业务问题？

> 目标：回答三个问题——Stage 15 要解决什么企业业务场景？涉及哪些概念？这些概念如何落到实现和设计决策？
> 配套蓝图：[architecture-stage-15.md](architecture-stage-15.md) · Stage 5 流程基础：[stage-5-business-scenario.md](stage-5-business-scenario.md)
> 对应实现：`agent-enterprise/` · 全剧本：[EnterpriseAssistantExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/EnterpriseAssistantExample.java)
> 当前状态：✅ Stage 15 已完成（M15.1~M15.5，89 个新增测试，全仓 774 个测试全绿）。

---

## 一、先给结论：Stage 15 不是「再写一个 Agent」

前面的 Stage 1-14 已经具备了一个能运行的 Agent Runtime：

- Agent 可以调用模型和工具；
- Workflow 可以编排确定性流程；
- Run 可以暂停、恢复和崩溃恢复；
- Memory 可以保存和检索信息；
- Security 可以做权限、审批、审计；
- Channel 可以连接多人协作；
- Product 可以声明式组装 Agent；
- Trace 可以输出训练轨迹。

但这些机制还没有回答企业真正关心的四个问题：

```text
谁在使用？        哪个用户、哪个租户？
能看到什么？      知识、记忆和工具权限的边界是什么？
谁批准了什么？    这次业务操作是否经过了正确的审批？
花了谁的钱？      这次调用消耗了多少 Token，预算是否超限？
```

因此 Stage 15 的定位是：

> **把通用 Runtime 装配成第一个领域 Profile——Enterprise Agent Profile。**

它不是给 Runtime 增加一个企业版 Agent，而是把已有机制翻译成企业业务语义：

```text
Runtime 机制                         企业语义
--------------------------------------------------------------
Memory scope                         租户和用户的数据隔离
Tool permission                      角色能否调用业务工具
AuditEvent                           哪个租户的哪个用户做了什么
RunManager                           业务任务的技术执行
HumanApprovalNode                    业务审批节点
TokenUsage                           租户/用户成本账单
Workflow + Checkpoint                长任务暂停与断点恢复
```

Stage 15 的最终记忆句：

> **每个请求有主人，每个租户有边界，每次回答有出处，每分钱有归属。**

---

## 二、Stage 15 要解决的业务场景

### 2.1 主场景：企业客服或内部知识助手

规划原文要求完成一个企业客服或内部知识助手，至少具备：

1. 登录用户识别；
2. 企业知识检索；
3. 业务工具调用；
4. 敏感操作审批；
5. 审计记录；
6. 运行失败恢复。

以客服处理退款为例：

```text
员工 Alice 登录
  -> 识别 Alice 属于 Acme 租户，角色是客服
  -> 查询 Acme 的退货政策和订单信息
  -> 如果请求退款，先判断客服是否有退款权限
  -> 敏感退款操作经过工具级审批
  -> 退款工单进入任务级审批流程
  -> 主管 Bob 批准
  -> 进程即使重启，也从审批断点继续，不重做前置步骤
  -> 全部事件归属到 Acme / Alice / Bob
  -> Token 消耗记到 Acme 和 Alice 的账本
```

完整的企业流程不是一次 `agent.run()`，而是一条带身份、数据边界、审批、副作用和成本约束的请求链。

### 2.2 纯 ReAct 的五个隐含假设在企业场景中失效

#### 假设一：调用方是一个没有身份的程序

纯 ReAct 通常只接收一段字符串：

```text
agent.run("帮我把订单 8842 退款")
```

但企业需要知道：

- 谁发起了请求？
- 用户属于哪个租户？
- 用户是什么角色？
- Agent 是代表谁执行？
- 这个用户是否可以操作该订单？

客服、主管和普通访客输入同一句话，权限边界不能相同。

#### 假设二：所有数据都属于同一个“我”

单租户 Demo 中，知识库可以被视为全局数据；SaaS 企业场景必须保证：

```text
Acme 的知识    不可被 Globex 检索
Alice 的记忆   不可被 Bob 检索
Acme 的审计    不应混入 Globex 的审计
Acme 的账单     不应累计 Globex 的 Token
```

隔离不能只靠调用者“传对 tenantId”，而要让存储层的 scope 白名单直接拒绝越界数据。

#### 假设三：模型预训练知识足够回答企业问题

企业问题往往依赖私有资料：

- 退货政策；
- 内部审批流程；
- 产品参数；
- 订单和售后规则；
- 最新 FAQ。

这些信息不是模型预训练知识，而是企业知识。Agent 需要在回答前检索租户知识，并把检索结果作为可追溯出处。

#### 假设四：运行失败就重新运行

企业流程经常跨越数小时甚至数天：

```text
创建工单 -> 前置校验 -> 等待审批 -> 执行退款
```

如果审批后从头重跑，就可能重复执行已经发生的副作用。企业需要的是：

```text
暂停 -> 保存 checkpoint -> 审批 -> 从断点 resume
```

#### 假设五：调用方自行负责成本

模型调用的 Token 消耗必须有预算约束：

```text
请求开始前：预算是否允许？
请求结束后：实际花了多少？
预算耗尽：是否拒绝新请求？
```

Stage 15 采用 fail-closed：预算不够就拒绝，而不是继续烧钱到月底才发现。

---

## 三、先建立四条边界：Stage 15 到底新增了什么语义

### 3.1 Runtime、产品层和 Profile 的边界

```text
Runtime（Stage 1-12）
  负责机制：Agent loop / Tool / Workflow / Checkpoint / Memory / Governance
  不知道 Tenant、User、Order、Refund 等业务概念

Product（Stage 13）
  负责声明：用 YAML 或配置描述如何组装 Agent
  TenantAgentConfig 表示租户对 Agent 定义的配置覆盖

Profile（Stage 15-17）
  负责领域：把 Runtime 机制翻译为某个场景的领域对象
  Enterprise Profile 引入 Tenant / User / BusinessTask
  Tavern Profile 引入 Character / World / Turn
```

判断标准：

> `Tenant` 在 Runtime 中不存在，在 Enterprise Profile 中是一等公民。

### 3.2 用户身份和 Agent 身份

Stage 15 的 `RequestContext` 回答：

```text
谁在要求 Agent 做事？ -> user:u-alice
属于哪个租户？       -> tenant:acme
```

Stage 12 的服务身份回答：

```text
谁在执行？           -> svc:support-bot
```

完整企业审计将来应该是：

```text
actor = svc:support-bot
onBehalfOf = user:u-alice
tenant = acme
```

Stage 15 v1 先落用户侧归属和 `agentName`，服务身份接线留给后续版本，但数据模型已经预留扩展方向。

### 3.3 工具审批和任务审批

两层审批不是重复：

```text
工具级审批：这个工具调用能不能执行？
任务级审批：这单业务能不能继续？
```

例如退款：

```text
RoleBasedPermissionChecker
  -> refund_order 是否允许客服调用？

HumanApprovalNode
  -> 退款工单是否获得主管放行？
```

工具级是执行闸门，任务级是业务流程节点，企业场景需要纵深防御。

### 3.4 知识和记忆

二者共用存储机制，但业务语义不同：

```text
Memory：对话和交互的沉淀
Knowledge：管理员导入的业务输入
```

Stage 15 的设计是“知识即记忆的一种类型”，而不是再造一个 `KnowledgeStore`：

```text
KnowledgeEntry
  -> MemoryEntry
      type  = KNOWLEDGE
      scope = tenant:{tenantId}
```

这样可以复用已有的 scope 白名单和检索能力，同时把 `KNOWLEDGE` 与 `FACT`、`PREFERENCE` 等类型分开过滤。

---

## 四、Stage 15 涉及哪些核心概念

### 4.1 租户与用户域：一次请求的主人是谁

| 概念 | 实现 | 业务含义 |
|---|---|---|
| `Tenant` | `tenant/Tenant.java` | 企业租户，最高级隔离边界 |
| `User` | `tenant/User.java` | 租户内用户和角色集合 |
| `TenantRegistry` | `tenant/TenantRegistry.java` | 注册、登录、停用、凭证校验 |
| `RequestContext` | `tenant/RequestContext.java` | 一次请求的身份快照 |
| `EnterpriseAuthException` | `tenant/EnterpriseAuthException.java` | 登录和注册失败，fail-closed |

`RequestContext` 是 Stage 15 的身份 SSOT，衍生出：

```text
ctx.tenantId()       -> 审计和成本归属
ctx.userId()         -> 审计和用户额度
ctx.user().roles()   -> 角色权限矩阵
ctx.memoryScopes()   -> [tenant:acme, user:u-alice]
ctx.actor()          -> user:u-alice
```

登录失败的五类情况都拒绝创建有效上下文：

- 未知租户；
- 租户已停用；
- 未知用户；
- 用户不属于请求声明的租户；
- apiKey 不匹配。

核心原则：

> **没有验证过的身份，就没有降级的匿名上下文。**

### 4.2 知识层：企业回答必须有出处

| 概念 | 实现 | 业务含义 |
|---|---|---|
| `KnowledgeEntry` | `knowledge/KnowledgeEntry.java` | 标题、内容、来源、标签 |
| `KnowledgeBase` | `knowledge/KnowledgeBase.java` | 批量导入和租户限定检索 |
| `KnowledgeTool` | `knowledge/KnowledgeTool.java` | 模型调用的 `search_knowledge` |

v1 的检索链：

```text
管理员 ingest(tenantId, entries)
  -> KnowledgeEntry.toMemoryEntry()
  -> MemoryType.KNOWLEDGE
  -> MemoryScope.tenant(tenantId)

模型调用 search_knowledge(query)
  -> KnowledgeTool 内部绑定 tenantId
  -> KnowledgeBase.search(tenantId, query, topK)
  -> MemoryQuery(scopes=[tenant:{tenantId}], type=KNOWLEDGE)
  -> JSON { count, results: [{ title, content }] }
```

`KnowledgeTool` 的租户 ID 在工具装配时绑定，不出现在模型参数 schema 中。因此模型即使传入：

```json
{"query":"policy", "tenant":"globex"}
```

也不能把 Acme 的工具改指向 Globex。工具绑定和存储 scope 形成双重边界。

### 4.3 治理层：权限、审计和成本

| 概念 | 实现 | 业务含义 |
|---|---|---|
| `RoleBasedPermissionChecker` | `govern/RoleBasedPermissionChecker.java` | 角色 × 工具矩阵 |
| `EnterpriseAuditEvent` | `govern/EnterpriseAuditEvent.java` | 通用事件加租户/用户/Agent 归属 |
| `EnterpriseAuditTrail` | `govern/EnterpriseAuditTrail.java` | 按租户、用户、工具查询审计 |
| `CostLedger` | `govern/CostLedger.java` | 租户/用户 Token 账本和预算闸 |
| `BudgetExceededException` | `govern/BudgetExceededException.java` | 超额拒绝的证据 |

权限判定采用 deny-first：

```text
1. fallback policy 是 DENY -> 永远 DENY
2. 角色矩阵显式授予 -> AUTO
3. 否则使用 fallback：AUTO / REQUIRES_APPROVAL
```

角色矩阵只能增加明确的业务授权，不能推翻硬禁策略。

审计链：

```text
GovernedToolExecutor 产生 AuditEvent
  -> EnterpriseAuditTrail.forRequest(ctx, agentName)
  -> 包装成 EnterpriseAuditEvent
  -> ledger
  -> byTenant / byUser / byTool
```

DENIED 事件也必须入账，因为“谁尝试调用了什么但被拒绝”同样是安全情报。

成本链：

```text
EnterpriseAssistant.ask(ctx, question)
  -> CostLedger.requireBudget(ctx)       // 事前
  -> ModelClient 返回 TokenUsage
  -> TrackingModelClient 累计用量
  -> finally CostLedger.record(ctx, prompt, completion) // 事后
```

两级额度独立计算：

```text
tenantUsed(acme)   = Acme 所有用户的累计用量
userUsed(alice)    = Alice 自己的累计用量
```

### 4.4 业务任务层：task 不等于 run

| 概念 | 实现 | 业务含义 |
|---|---|---|
| `BusinessTask` | `task/BusinessTask.java` | 面向业务的任务对象 |
| `TaskApprovalRecord` | `task/TaskApprovalRecord.java` | 谁在什么时候批准/拒绝了什么 |
| `TaskApprovalBridge` | `task/TaskApprovalBridge.java` | Workflow 审批节点和任务管理器的通道 |
| `EnterpriseTaskManager` | `task/EnterpriseTaskManager.java` | 提交、审批、拒绝、恢复、查询 |

`BusinessTask` 和 Runtime 的 `Run` 是不同层级：

```text
BusinessTask：退款工单 T-0001，业务用户关心的对象
Run：某次 Workflow 技术执行，Runtime 关心的对象
```

一个业务任务可以有多个 run：

```text
BusinessTask.runIds = [run-1, run-2, ...]
currentRunId() = 最后一个 run
approvals       = 挂在 task 上，不挂在 run 上
```

任务状态：

```text
SUBMITTED
  -> RUNNING
  -> WAITING_APPROVAL
  -> DONE / FAILED / CANCELLED
```

ExecutionResult 到 BusinessTask 的映射：

```text
SUCCEEDED -> DONE
FAILED    -> FAILED
PAUSED    -> WAITING_APPROVAL
CANCELLED -> CANCELLED
```

### 4.5 装配层：把所有机制连成一条企业请求链

| 概念 | 实现 | 责任 |
|---|---|---|
| `EnterpriseAgentFactory` | 根包 | 请求作用域装配 |
| `EnterpriseAssistant` | 根包 | 企业统一入口 |
| `TrackingModelClient` | Factory 内部装饰器 | 累计模型 TokenUsage |
| `EnterpriseAssistantExample` | `examples` 模块 | T1-T5 全剧本验收 |

`EnterpriseAgentFactory` 的共享件与请求件分离：

```text
共享件：ModelClient、无状态业务工具、角色矩阵、ToolPolicy、审计台账
请求件：PermissionChecker、AuditLogger、KnowledgeTool、MemoryContextBuilder、UsageTracker
```

每个 `forRequest(ctx)` 都会创建请求作用域执行链，身份通过参数显式传递，不使用 ThreadLocal。

---

## 五、对应实现和设计思路

### 5.1 D1：Profile 依赖正交，不依赖产品层和频道层

`agent-enterprise` 的依赖方向是：

```text
agent-enterprise
  -> agent-core
  -> agent-memory
  -> agent-security
  -> agent-workflow
```

刻意不依赖：

```text
agent-product
agent-channel
agent-scheduler
```

原因：

- `TenantAgentConfig` 是产品层对声明式 Agent 定义的覆盖，不等于企业 Profile 的租户领域模型；
- `agent-channel` 解决多人频道协作，企业 v1 先解决用户到助手的一对一入口；
- `TokenBudget` 是 per-Run 预算，企业 `CostLedger` 是跨 Run 的租户/用户账本。

Profile 层只依赖真正消费的机制，避免领域概念反向污染 Runtime。

### 5.2 D2：请求作用域装配，不使用 ThreadLocal

错误做法：

```text
请求到达 -> ThreadLocal.set(ctx)
Agent 内部任意位置 -> ThreadLocal.get()
```

问题：

- 异步执行或跨线程后上下文可能丢失；
- 权限判定依赖隐式状态，不容易追踪；
- 多请求共享对象时可能发生身份串线。

Stage 15 的做法：

```text
EnterpriseAssistant.ask(ctx, question)
  -> factory.forRequest(ctx)
      -> RoleBasedPermissionChecker(ctx.user.roles)
      -> EnterpriseAuditTrail.forRequest(ctx)
      -> KnowledgeTool.forTenant(ctx.tenantId)
      -> MemoryContextBuilder(ctx.memoryScopes)
  -> agent.run(question)
```

身份在调用链中显式流动：

> **可追踪性优先于少创建几个小对象。**

### 5.3 D3：租户隔离复用 scope 白名单，不造新隔离机制

M15.1 只对存量 Memory 做两处纯加法：

```text
MemoryScope.Kind.TENANT
MemoryScope.tenant(String)
MemoryType.KNOWLEDGE
```

请求上下文生成白名单：

```text
[tenant:acme, user:u-alice]
```

知识库查询只允许：

```text
[tenant:acme]
```

存储层的关键承诺是：

```text
query(scopes=[tenant:acme])
  -> 永远不返回 tenant:globex

query(scopes=[])
  -> 空结果，不降级为全库扫描
```

这让隔离从“调用约定”升级为“存储机制”。

### 5.4 D4：审计归属采用组合，不修改通用 AuditEvent

Stage 9 的 `AuditEvent` 记录通用治理事实：

```text
runId / toolName / status / result / timestamp
```

Stage 15 不修改这个通用 record，而是组合包装：

```text
EnterpriseAuditEvent {
    event: AuditEvent,
    tenantId,
    userId,
    agentName
}
```

原因：

- 不破坏已有安全模块和存量消费者；
- 通用 Runtime 不需要知道租户和用户；
- 企业层获得自己的查询维度；
- v2 接入服务身份时可以继续补 `actor/onBehalfOf`。

### 5.5 D5：知识即记忆，但业务治理语义不同

不用新增 `KnowledgeStore`，而是投影到已有 `MemoryStore`：

```text
KnowledgeEntry.toMemoryEntry(tenantId, adminId)
  -> scope = tenant:{tenantId}
  -> type = KNOWLEDGE
  -> status = ACTIVE
  -> provenance = adminEdit(adminId)
```

为什么知识导入后直接 `ACTIVE`，不走对话记忆的 `PENDING_REVIEW`？

```text
知识：管理员主动筛选并导入，导入动作本身就是控制点
记忆：模型从交互中沉淀，需要后续治理审核
```

诚实边界：v1 的 `MemoryEntry` 没有自由元数据槽位，因此 `source/tags` 只保留在导入侧，检索反向投影时返回空，而不是从内容猜出处。宁可缺失，不可伪造。

检索仍然是 v1 keyword 匹配，向量、Embedding、重排留给后续版本。

### 5.6 D6：工具级审批和任务级审批纵深叠加

工具级审批在 `GovernedToolExecutor` 中：

```text
RoleBasedPermissionChecker
  -> AUTO：直接执行
  -> REQUIRES_APPROVAL：ToolApprovalService 放行后执行
  -> DENY：直接拒绝
```

任务级审批在 Workflow 中：

```text
BusinessTask.submit
  -> RunManager.start
  -> HumanApprovalNode
  -> PAUSED + checkpoint
  -> TaskApprovalManager.approve
  -> RunManager.resume
```

两层分别防护：

```text
工具级：防止某一次调用越权
任务级：防止某一单业务未经主管放行继续推进
```

### 5.7 D7：BusinessTask 是 Run 的业务投影

Runtime 只知道：

```text
runId = 技术执行标识
cursor = 当前节点
checkpoint = 运行快照
```

企业用户需要：

```text
taskId = 业务工单标识
submitter = 谁提交
approver = 谁批准
status = 这单业务做到哪一步
```

因此不能直接把 `Run` 当业务任务使用。`EnterpriseTaskManager` 负责两者之间的投影和生命周期映射。

### 5.8 D8：成本采用事前闸 + 事后记账

请求链严格保持顺序：

```text
1. requireBudget(ctx)
2. 通过后才调用模型
3. TrackingModelClient 收集 TokenUsage
4. finally record(ctx, promptTokens, completionTokens)
```

为什么用 `finally` 记账？

```text
模型调用失败 ≠ 没有消耗
花掉的 Token 仍然应该进入账本
```

v1 不做中途熔断，因为模型调用发出后再中断只能省一部分钱，还会留下半成品状态。完整的时间窗额度、模型降级、成本仪表盘归后续阶段。

---

## 六、一次完整企业请求：从登录到记账

### 6.1 T0：管理员装配

```text
注册租户：acme / globex
注册用户：alice=客服，bob=主管，carol=另一租户客服
导入知识：acme 退货政策，globex 自己的政策
配置角色矩阵：客服可以查，主管可以退款
配置 ToolPolicy：refund_order = REQUIRES_APPROVAL
配置 CostLedger：租户额度 + 用户额度
配置 ModelClient、业务工具、审计台账
```

### 6.2 T1：用户登录

```java
RequestContext ctx = registry.login("acme", "u-alice", "key-alice");
```

得到：

```text
tenantId = acme
userId = u-alice
roles = [agent:csr]
memoryScopes = [tenant:acme, user:u-alice]
actor = user:u-alice
```

### 6.3 T2：知识问答

```text
assistant.ask(ctx, "退货政策是什么？")
  -> budget gate
  -> forRequest(ctx)
  -> 模型调用 search_knowledge("policy")
  -> KnowledgeBase 只查询 tenant:acme
  -> 返回 Acme 政策
  -> 审计记录 search_knowledge / EXECUTED / acme / alice
  -> 857 tokens 记入 acme 和 alice
```

### 6.4 T3：敏感工具调用

```text
Alice 请求退款
  -> 模型调用 refund_order
  -> 客服角色未在矩阵中直接授予退款
  -> fallback policy = REQUIRES_APPROVAL
  -> 工具级审批服务批准
  -> 记录 APPROVED
  -> 执行退款
  -> 记录 EXECUTED
```

### 6.5 T4：长任务审批和恢复

```text
submitTask("处理订单 8842 退款工单", refundWorkflow)
  -> prepare
  -> HumanApprovalNode
  -> PAUSED
  -> WAITING_APPROVAL
  -> Bob approve
  -> RunManager.resume(runId)
  -> 从 checkpoint 继续执行退款
  -> DONE
```

关键证明：`prepare` 节点只执行一次，审批前已经发生的节点不会在 resume 时重复执行。

### 6.6 T5：跨租户和预算边界

```text
Carol 登录 Globex
  -> 同一个 search_knowledge 工具名
  -> 工具实例绑定 tenant:globex
  -> 只能看到 Globex 知识

Dave 预算 50 tokens
  -> 第一次请求消耗 60
  -> 第二次请求 requireBudget 直接抛 BudgetExceededException
  -> 第二次请求零 Token 消耗
```

---

## 七、M15.1~M15.5 如何把业务问题落地

| 里程碑 | 业务问题 | 主要实现 | 证明 |
|---|---|---|---|
| M15.1 | 谁在请求，数据属于谁 | `Tenant` / `User` / `TenantRegistry` / `RequestContext` + TENANT scope | 五类登录失败 fail-closed；跨租户、跨用户记忆不可见 |
| M15.2 | 企业知识从哪里来，能否越租户检索 | `KnowledgeEntry` / `KnowledgeBase` / `KnowledgeTool` | ingest→search；KNOWLEDGE 类型过滤；共享 store 多租户零泄漏 |
| M15.3 | 谁能调用工具，谁批准，花了多少钱 | `RoleBasedPermissionChecker` / `EnterpriseAuditTrail` / `CostLedger` | 三档权限、DENIED 审计归属、预算闸和两级账本 |
| M15.4 | 业务任务如何等待审批和恢复 | `BusinessTask` / `TaskApprovalRecord` / `TaskApprovalBridge` / `EnterpriseTaskManager` | WAITING_APPROVAL→approve→resume；完成节点不重跑；FileCheckpointStore 崩溃恢复 |
| M15.5 | 如何把所有能力组装成用户入口 | `EnterpriseAgentFactory` / `EnterpriseAssistant` / `EnterpriseAssistantExample` | T1-T5 全剧本；ask 全链；任务门面；全仓 774 测试全绿 |

Stage 15 的验收不是“多写了几个类”，而是六条业务能力被完整证明：

```text
登录识别 -> 知识检索 -> 业务工具 -> 敏感审批 -> 审计记录 -> 失败恢复
```

---

## 八、Stage 15 中最值得记住的工程教训

### 8.1 隔离测试必须控制变量

跨租户检索测试要保证两个租户的内容都命中同一个关键词，否则一边返回 0 可能只是测试数据没命中，而不是隔离机制生效。

正确对照：

```text
Acme：acme policy: 30-day returns
Globex：globex policy: all sales final
查询：policy
```

唯一变量应该是 scope，而不是内容是否能被检索。

### 8.2 测试停用租户不能重复注册同名租户

重复 `registerTenant("acme")` 验证不到 `SUSPENDED`，只会得到“already registered”。正确做法是提供生命周期 API：

```java
registry.suspendTenant("acme");
registry.login("acme", ...); // SUSPENDED -> fail-closed
```

### 8.3 `BusinessTask` 和 `Run` 的生命周期不同

任务是业务对象，Run 是执行对象。审批记录放在 Task 上，checkpoint 放在 Run 上。把二者混成一个类会导致：

- 业务查询被技术细节污染；
- 重试和恢复无法表达多个 run；
- 审批对象错误地绑定到一次执行，而不是一单业务。

### 8.4 审批通道必须比 manager 活得久

工作流节点在构造时捕获 `ApprovalService`，而 `EnterpriseTaskManager` 可能因进程重启被替换。如果审批决策表放在 manager 私有对象里：

```text
旧 Workflow -> 旧 bridge
新 manager  -> 新 bridge
新 manager 的 approve -> 旧 Workflow 永远看不到
```

所以 `TaskApprovalBridge` 必须提升为装配级对象，让多个 manager 代际共享同一通道。

这条教训可以推广到所有依赖注入：

> **绑定什么，要看对象生命周期；不要只看谁用起来方便。**

### 8.5 预算闸和账本必须是同一个实例

错误演示：

```text
第一次请求 -> CostLedger A.record(60)
第二次请求 -> 新建 CostLedger B.requireBudget()
```

第二本账不知道第一次的用量，预算永远不会触发。正确做法是让同一个装配级 `CostLedger` 同时承担：

```text
事前读取
事后写入
```

### 8.6 示例执行前先编译

`mvn exec:java` 可能直接运行 `target/classes` 中的旧字节码。修改示例后必须：

```text
mvn compile
mvn exec:java
```

否则容易误判为“新逻辑没有生效”。

### 8.7 诚实记录 v1 边界

Stage 15 明确没有假装完成以下能力：

- 向量检索、Embedding、重排；
- 真实 SSO/OAuth/OIDC；
- Web 前端和 REST 服务化；
- 完整服务身份接线；
- 补偿事务和副作用回滚；
- 时间窗成本治理和模型降级；
- 分布式多实例和数据库持久化。

学习框架时，**知道哪些没做、为什么没做、接口为未来留下什么缝**，比堆功能更重要。

---

## 九、Stage 15 和前后阶段的关系

```text
Stage 5  Workflow：流程有确定性骨架
Stage 6  Checkpoint：流程可以暂停和恢复
Stage 8  Memory：Agent 可以记住
Stage 9  Security：Agent 可以被治理
Stage 13 Product：Agent 可以被声明式组装
Stage 14 Trace：Agent 经验可以转成训练数据
Stage 15 Enterprise Profile：Agent 可以进入企业
```

Stage 15 不是替代前面能力，而是把它们组合为企业业务闭环：

```text
身份 -> 记忆/知识 -> Agent -> 工具治理 -> 审批 -> Workflow 恢复 -> 审计 -> 成本
```

它也为 Stage 16/17 做铺垫：

```text
Stage 15：Enterprise Profile，新增领域归属层
Stage 16：Tavern Profile，验证游戏场景能否零存量改动复用 Runtime
Stage 17：Coding Profile，验证工程场景的安全边界和任务流程
```

如果一个新 Profile 需要修改 Runtime 才能表达自己的领域对象，说明领域层和机制层耦合过深；如果只需组合已有机制，说明 Runtime 抽象足够稳定。

---

## 十、复盘速答

### Q1：Stage 15 解决什么问题？

> Stage 15 解决通用 Agent Runtime 进入企业业务时缺少归属层的问题：没有明确的用户身份、租户边界、企业知识、审批留痕、长任务恢复和成本账本。它通过 Enterprise Profile 把已有 Runtime 机制翻译成企业客服或知识助手的业务语义。

### Q2：为什么纯 ReAct 不够？

> 因为 ReAct 把下一步交给 LLM，而企业流程中有不能绕过的审批、确定性状态更新、数据隔离、审计和预算边界。Stage 15 不修改 ReAct 的推理机制，而是在其外围装配身份、知识、治理、任务和成本控制面。

### Q3：多租户隔离怎么做？

> 不让模型或调用方自由传租户 ID。`RequestContext` 产生 scope 白名单，`KnowledgeTool` 在构造时绑定 tenantId，`MemoryStore` 只返回白名单 scope 内的条目。隔离靠机制，不靠约定。

### Q4：知识为什么复用 MemoryStore？

> 知识和记忆都是可检索的持久化上下文，复用已有存储和 scope 隔离可以避免重复造隔离边界。通过 `MemoryType.KNOWLEDGE` 保持类型语义隔离；知识是管理员导入，记忆是交互沉淀，治理状态仍然不同。

### Q5：工具审批和任务审批有什么区别？

> 工具审批回答“这次工具调用能不能执行”，任务审批回答“这单业务能不能继续”。前者是执行闸门，后者是 Workflow 节点；退款场景中两层都可能存在。

### Q6：为什么需要 `BusinessTask`，不能直接用 `Run`？

> `Run` 是技术执行对象，`BusinessTask` 是用户和主管关心的业务对象。一个 Task 可以有多个 Run，审批记录挂 Task，checkpoint 挂 Run，二者职责不同。

### Q7：为什么请求上下文不用 ThreadLocal？

> ThreadLocal 隐藏了身份来源，并且在异步或跨线程场景容易丢失。Stage 15 选择每请求显式装配执行链，让权限、审计、知识 scope 和成本归属都能沿调用栈追踪。

### Q8：Stage 15 最重要的设计思想是什么？

> **Runtime 提供机制，Profile 提供领域语义；身份显式流动，边界由机制保证，副作用前有闸门，长任务从断点恢复。**

---

## 十一、最终理解

Stage 15 可以浓缩成下面这条工程判断：

```text
Demo Agent：能回答问题
Workflow Agent：能按流程办事
Enterprise Agent：能在真实组织边界内负责任地办事
```

“负责任”具体意味着：

```text
有身份       -> 知道谁在请求
有边界       -> 知道数据属于谁
有出处       -> 知道答案依据什么知识
有权限       -> 知道谁能调用什么工具
有审批       -> 敏感副作用不能自由发生
有恢复       -> 长任务失败后不重复副作用
有审计       -> 事后能回答谁做了什么
有成本       -> 知道花了谁的钱，超额会拒绝
```

因此 Stage 15 的真正产物不是 `EnterpriseAssistant` 这个门面，而是一套企业 Agent 的责任模型：

```text
RequestContext 负责“谁”
Memory scope    负责“边界”
Knowledge      负责“依据”
Permission     负责“能不能”
Approval       负责“放不放行”
Workflow       负责“怎么继续”
Audit          负责“留下证据”
CostLedger     负责“花了多少”
```

**最终记忆句：**

> **Stage 15 让 Agent 从“能完成一次调用”变成“能在企业边界内被信任地完成一项业务”。**
