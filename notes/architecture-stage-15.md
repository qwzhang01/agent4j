# Stage 15 架构设计：Enterprise Agent Profile

> 对应阶段：Stage 15 - Enterprise Agent Profile（企业场景第一个领域 Profile：多租户 / 用户身份 / RAG / 治理接线 / 业务任务与恢复 / 成本）
> 状态：✅ 全部完成（2026-08-24）—— M15.1 租户与用户域 / M15.2 知识层 RAG / M15.3 治理接线 / M15.4 业务任务与恢复 / M15.5 装配收口（tenant + knowledge + govern + task 四包 19 文件 + 装配 2 文件，89 测试全仓 774 全绿，EnterpriseAssistantExample 全剧本验收）；实现记录见 §13-§17
> 模块：新增 `agent-enterprise` Maven 模块，依赖 `agent-core`（Agent/Tool/ModelClient/ToolCall）+ `agent-memory`（MemoryStore/MemoryScope/MemoryRetriever）+ `agent-security`（治理四件套/PermissionChecker 可扩展点）+ `agent-workflow`（RunManager/HumanApprovalNode/长任务恢复）；`agent-model`（MockModelClient）test scope。**不依赖 agent-product / agent-channel / agent-scheduler**（见 D1 依赖正交裁决）
> 前置：Stage 1-14 已完成（全仓 685 测试全绿；治理 / 检查点 / 记忆 / 装配底座齐备）
> 定位：18 周规划「同一 Runtime 支撑三类场景」宣言的**第一次实证**——企业 Agent、Tavern Game（16）、Coding Agent（17）是同一 Runtime 上的不同领域 Profile。Stage 15 验证：把 Runtime 当底座而非当产品，企业场景缺的不是新能力，是**归属层**（谁在问 / 属于哪个租户 / 花了谁的钱 / 出了事找谁）

---

## 1. 核心命题：Runtime「能跑」≠ 企业「能用」

Stage 1-14 造好的 Runtime 有一个隐含共识：**调用方是一个无面的程序**。`agent.run("...")` 的字符串背后没有「谁」——权限按工具名判定（Stage 9）、记忆按 scope 取值（Stage 8）、恢复按 runId 索引（Stage 6）、成本无人记账。这个共识在企业场景全线破裂：

```text
Runtime 的五个隐含假设，在企业场景全部破裂：
1. 调用方假设 -- 假设调用方是"某个程序"，没有"谁在问"的概念
   企业里每个请求背后是具体员工：有角色（客服 vs 主管）、有归属（哪个租户）、
   有权限边界（普通客服不能发起大额退款）——同样一句话，不同人问，答案边界不同
2. 边界假设 -- 假设单租户自用，数据天然属于"我"
   企业 SaaS 是多租户：A 公司的知识库、用户偏好、审计流水绝不能漏给 B 公司；
   隔离机制必须显式、可测试、fail-closed
3. 知识假设 -- 假设模型预训练知识够用，或调用方自己拼 prompt
   企业回答必须"先检索企业知识再开口"（RAG）：退货政策、内部流程、产品参数——
   这些知识按租户沉淀、按权限检索，不是 prompt 里的静态字符串
4. 失败假设 -- 假设 run 失败就失败，调用方自己重试
   企业长任务横跨审批等待（数小时到数天）：审批通过后必须从断点恢复，
   重跑 = 重复执行已发生副作用的业务操作（二次退款），不可接受
5. 成本假设 -- 假设调用方自己管成本
   企业要按租户/用户记账：预算是合同义务（SLA），预算耗尽要 fail-closed 拒绝，
   而不是默默烧钱到月底才发现
```

Stage 15 的答案：**身份一以贯之**（RequestContext 从登录贯穿到审计/权限/记忆/成本）、**隔离靠 scope 白名单**（不新增隔离机制，兑现 Stage 8 D3 设计意图）、**知识即记忆**（KnowledgeEntry 是 MemoryStore 的一种 type，租户 scope 隔离）、**审批双层**（工具级 Stage 9 透明搭车 + 任务级 workflow 暂停恢复 Stage 6 兑现）、**成本 fail-closed**（事前预算闸 + 事后记账）。

一句话（接 Stage 6-14 的递进叙事）：

```text
Stage 6  让 Run 能暂停-恢复
Stage 7  让 Run 能自动恢复
Stage 8  让 Agent 能记住
Stage 9  让 Agent 能被信任
Stage 10 让 Agent 能连接
Stage 11 让 Agent 能协作
Stage 12 让 Agent 能入驻团队
Stage 13 让 Agent 能被"搭出来"
Stage 14 让 Agent 的经验能变成训练数据
Stage 15 让 Agent 能进企业 -- 第一个领域 Profile：
         每个请求有主人，每个租户有边界，每次回答有出处，每分钱有归属
```

### 与相邻概念的四条边界（面试高频）

```text
Profile vs Runtime vs 产品层（三层职责）：
  Runtime（1-12）-- 机制层：loop / 治理 / 恢复 / 记忆，不知道任何业务概念
  产品层（13）-- 声明层：YAML 定义 Agent，治理经装配接线，不新增能力
  Profile（15-17）-- 领域层：为某类场景引入领域模型（Tenant/User/BusinessTask），
    把 Runtime 机制"翻译"成领域语义；Profile 的 bug 不该需要改 Runtime 才能修
  判断标准：Tenant 在 Runtime 里不存在，在 Enterprise Profile 里是一等公民

用户身份（本阶段）vs Agent 身份（Stage 12）：
  AgentIdentity/ServiceAccount 回答"谁在执行"（svc:eng-bot，权限=授予 scope ∩ 频道角色）
  RequestContext/User 回答"谁在要求"（u-alice，角色=客服，归属=acme 租户）
  企业链路两者同时在场：Agent 以服务身份执行、代表用户行动——
  审计双归属（actor=svc:xxx, onBehalfOf=u-alice），权限取交集（Stage 12 交集哲学的企业版）

工具审批（Stage 9）vs 任务审批（本阶段）：
  工具审批是"执行中的一道闸"：loop 不停，REQUIRES_APPROVAL 工具调用前问一次
  任务审批是"流程中的一个节点"：run 暂停（checkpoint），审批通过后从断点恢复
  前者保安全（这个调用能不能做），后者保流程（这单业务能不能继续）——
  企业场景两层都要：退款工具要审批（闸），退款流程要主管放行（节点）

知识检索（RAG）vs 记忆检索（Stage 8）：
  记忆是"对话的沉淀"：MemoryExtractor 从交互中提取，PENDING_REVIEW 治理
  知识是"业务的输入"：管理员批量导入（政策文档/FAQ），检索注入上下文
  同一 store 同一 retriever（KnowledgeEntry 是 MemoryType 的一种）——
  "知识即记忆"不是偷懒，是 Stage 8 scope 白名单机制的直接兑现
```

---

## 2. 复用清单：Stage 15 是第三次「组装阶段」（预检先行）

延续 Stage 12 教训、13/14 制度化的做法：**规划时就做复用预检**。本清单每行标注预检结论，含三处「预检发现的 gap」与两处「预排的存量最小改动」。

| 能力需求 | 已有设施（阶段） | Stage 15 做什么 | 复用预检 |
|---|---|---|---|
| 模型/循环/工具注册 | `ModelClient`/`ReActAgentLoop`/`ToolRegistry`（1/2） | 照常装配 | ✅ 直接兑现 |
| 工具治理四件套 | `GovernedToolExecutor` + `ToolPolicy` + `ToolApprovalService` + `AuditLogger`（9） | 企业层提供角色感知 PermissionChecker + 归属感知 AuditLogger，注入既有治理链 | ✅ 挂点已预留：`PermissionChecker` 非 final，javadoc 自 Stage 9 起写着 "future stages can add context-aware logic (e.g. user role X can call tool Y)"——本阶段兑现该承诺 |
| 审批 | 工具级 `ConsoleApprovalService`（9）+ 流程级 `HumanApprovalNode` + `ApprovalService`（5） | 双层审批直接复用；企业层补审批事件归属 | ✅ 直接兑现 |
| 长任务与恢复 | `RunManager` start/pause/resume + `CheckpointStore`（InMemory/File）（6） | BusinessTask 关联 runId；审批暂停→resume 断点恢复；FileCheckpointStore 兑现崩溃恢复 | ✅ 直接兑现（`resume(runId, workflow)` 从磁盘恢复的 API 已在） |
| 人工接管 | `HumanApprovalNode`（5）+ `waitingHuman`（12） | 任务暂停即人工接管点 | ✅ 直接兑现 |
| 记忆与检索 | `MemoryStore` + `MemoryRetriever` + `MemoryContextBuilder` + scope 白名单（8） | 知识层建在其上：KnowledgeEntry 存 store，检索走 scope 白名单 | ⚠️ gap：`MemoryScope.Kind` 无 TENANT、`MemoryType` 无 KNOWLEDGE——预排两处**存量纯加法**（见下「存量改动清单」） |
| 租户配置 | `TenantAgentConfig`（13） | **不依赖**：那是产品层「租户对 YAML 定义的覆盖」，企业 Profile 走 Java 装配（D1 正交裁决） | ✅ 有意不复用，蓝图显式记录 |
| Agent 服务身份 | `ServiceAccount`/`IdentityResolver`（12） | v1 不引入 channel 三方身份解析（企业场景无频道概念）；审计归属用 RequestContext | ⚠️ 范围裁决：服务身份 wiring 留 v2 对接（D4 双归属先立数据模型，actor 字段先记用户） |
| 成本 | `TokenBudget`（7，javadoc 明写 per-user/time-window 是 Stage 18 scope）+ `TokenUsage`（1） | CostLedger 扩展为租户/用户维度账本（语义对齐 TokenBudget 的 fail-closed） | ⚠️ gap：无记账实体、无事前闸——本阶段补 v1 轻量版，完整成本治理（路由降级/仪表盘）仍归 18 |
| 业务工具 | `HttpApiTool`（13）+ `ToolNode`（5） | 示例用 mock 业务系统（订单查询/退款）+ HttpApiTool 演示真实 API 接入 | ✅ 直接兑现（Profile 不做新工具机制） |
| Mock 验收 | `MockModelClient` scripted（1） | 全链路验收零 LLM 依赖 | ✅ 同 Stage 8-14 手法 |

### 存量改动清单（预检裁决：仅两处，纯加法向后兼容）

1. `agent-memory` `MemoryScope.Kind` 增加 `TENANT` + `tenant(String)` 工厂方法——Stage 8 javadoc 本就写着 "Multi-tenant isolation is enforced by the store"（设计意图先于枚举值存在，本阶段补上）；加枚举值 + 工厂是纯加法，Stage 8 存量测试零影响
2. `agent-memory` `MemoryType` 增加 `KNOWLEDGE`——同上，type 过滤器按枚举值匹配，新值不被存量路径引用

除这两处外**零存量改动**（agent-core / agent-security / agent-workflow / agent-product / agent-channel 均不动——组合优于修改的纪律第 N 次兑现）。

**依赖方向**：`agent-enterprise -> agent-core + agent-memory + agent-security + agent-workflow`（compile）；`agent-model`（test scope）。零新第三方依赖。

---

## 3. 核心抽象（16 个，五组）

### 第一组：租户与用户（tenant 包，M15.1）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `Tenant` | 数据 | 租户实体：tenantId + 名称 + 预算上限 + 状态（ACTIVE/SUSPENDED） |
| `User` | 数据 | 用户实体：userId + tenantId + roles + displayName；角色决定工具权限矩阵 |
| `TenantRegistry` | 核心 | 租户/用户注册与登录识别：`login(tenantId, userId, apiKey)` → RequestContext；fail-closed（未知租户/用户/停用全拒绝） |
| `RequestContext` | 数据 | 一次请求的身份快照：tenant + user + roles + sessionId（贯穿审计/权限/记忆/成本的唯一载体） |

### 第二组：知识层（knowledge 包，M15.2）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `KnowledgeEntry` | 数据 | 一条知识：title + content + source + tags（存 MemoryStore，type=KNOWLEDGE，scope=tenant:{tid}） |
| `KnowledgeBase` | 核心 | 知识库门面：`ingest(tenantId, entries)` 批量导入 + `search(tenantId, query)` scope 白名单检索（隔离由 store 保证，不自己造） |
| `KnowledgeTool` | 核心 | `implements Tool`：search_knowledge——模型可调用的知识检索入口，租户 ID 在工具实例构造时绑定（模型无法跨租户检索） |

### 第三组：治理接线（govern 包，M15.3）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `RoleBasedPermissionChecker` | 核心 | `extends PermissionChecker`（Stage 9 预留扩展点）：角色×工具矩阵 + 兜底 ToolPolicy；deny 优先 fail-closed |
| `EnterpriseAuditEvent` | 数据 | 组合不修改：包 AuditEvent + tenantId + userId（onBehalfOf）+ agentName——归属补全的审计事实 |
| `EnterpriseAuditTrail` | 核心 | `implements AuditLogger`：治理事件实时归属补全（从请求作用域注入的 RequestContext 取），按租户/用户/工具查询 |
| `CostLedger` | 核心 | 成本账本：`checkBudget(ctx)` 事前闸（超限 fail-closed 拒绝新请求）+ `record(ctx, tokens, cost)` 事后记账；租户/用户两级额度（语义对齐 Stage 7 TokenBudget） |

### 第四组：业务任务（task 包，M15.4）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `BusinessTask` | 数据 | 企业任务：taskId + 发起人 + 描述 + 状态机（SUBMITTED→RUNNING→WAITING_APPROVAL→RUNNING→DONE/FAILED/CANCELLED）+ runIds 历史 + 审批记录 |
| `TaskApprovalRecord` | 数据 | 任务级审批留痕：taskId + approver + decision + reason + 时间——「谁放行了这单业务」的 SSOT |
| `EnterpriseTaskManager` | 核心 | 任务生命周期：`submit`（创建+关联 run）→ `approve`（记录+resume 断点）→ `reject`（记录+cancel run）→ 状态查询；内部委托 RunManager |

### 第五组：装配（M15.5）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `EnterpriseAgentFactory` | 核心 | 请求作用域装配：`forRequest(RequestContext)` 返回绑定了该用户权限/审计/记忆 scope 的执行链（ModelClient/ToolRegistry 共享，执行链按请求克隆——见 D2） |
| `EnterpriseAssistant` | 核心 | 门面：`ask(ctx, question)` 同步问答（预算闸→装配→run→记账）+ `submitTask(ctx, description, workflow)` 长任务入口——企业场景的统一入口 |

### 3.1 关键接口草图

```java
// ---- 租户与用户（第一组）----
public final class TenantRegistry {
    public void registerTenant(Tenant tenant);
    public void registerUser(User user);                 // 归属校验：租户必须已注册且 ACTIVE
    public RequestContext login(String tenantId, String userId, String apiKey);
    // fail-closed：未知租户 / 未知用户 / apiKey 不匹配 / 租户 SUSPENDED → EnterpriseAuthException
}

public record RequestContext(Tenant tenant, User user, String sessionId) {
    public List<String> memoryScopes();   // [tenant:{tid}, user:{uid}] —— 记忆/知识检索的白名单
    public String actor();                // "user:{uid}" —— 审计归属
}

// ---- 知识层（第二组）----
public final class KnowledgeBase {
    public void ingest(String tenantId, List<KnowledgeEntry> entries);  // 批量导入（管理员操作）
    public List<KnowledgeEntry> search(String tenantId, String query, int topK);
    // 检索 = MemoryQuery(scopes=[tenant:{tid}], type=KNOWLEDGE, keyword=query, limit=topK)
    // 隔离由 store 白名单保证：跨租户查询在机制上不可能（不是约定，是不可能）
}

public final class KnowledgeTool implements Tool {
    // name="search_knowledge"；schema: {query: string}
    // 实例构造时绑定 tenantId → 模型即使被注入恶意指令也拿不到别人的租户 ID
}

// ---- 治理接线（第三组）----
public final class RoleBasedPermissionChecker extends PermissionChecker {
    // 矩形矩阵：role -> Set<toolName>（显式白名单）；未命中走兜底 ToolPolicy；deny 优先
    // check(toolName) 之外加 check(toolName, RequestContext) 重载供装配层显式调用
}

public final class EnterpriseAuditTrail implements AuditLogger {
    public void log(AuditEvent event);                  // 治理链回调：用当前 RequestContext 补归属
    public List<EnterpriseAuditEvent> byTenant(String tenantId);
    public List<EnterpriseAuditEvent> byUser(String userId);   // 「这个员工让 Agent 干过什么」
}

public final class CostLedger {
    public void requireBudget(RequestContext ctx);      // 事前闸：超限抛 BudgetExceededException（fail-closed）
    public void record(RequestContext ctx, long promptTokens, long completionTokens);
    public long tenantUsed(String tenantId);            // 账单查询（仪表盘数据源，渲染归 18）
}

// ---- 业务任务（第四组）----
public final class EnterpriseTaskManager {
    public BusinessTask submit(RequestContext ctx, String description, Workflow workflow);
    // start → 跑到 HumanApprovalNode 暂停 → WAITING_APPROVAL + runId 入 task
    public BusinessTask approve(String taskId, String approverId, String reason);
    // TaskApprovalRecord 留痕 + runManager.resume(runId) —— 从断点继续，不重跑已完成节点
    public BusinessTask reject(String taskId, String approverId, String reason);
    // 留痕 + cancel —— 已发生副作用的节点不回滚（诚实边界：补偿事务 v2）
    public Optional<BusinessTask> find(String taskId);
}

// ---- 装配（第五组）----
public final class EnterpriseAgentFactory {
    public static Builder builder();
    // Builder: modelClient / knowledgeBase / roleMatrix / toolPolicy / costLedger / auditTrail ...
    public EnterpriseAgent forRequest(RequestContext ctx);   // 请求作用域：权限/审计/记忆按 ctx 克隆装配
}

public final class EnterpriseAssistant {
    public String ask(RequestContext ctx, String question);
    // requireBudget → forRequest(ctx) → agent.run → costLedger.record → 返回答案
    // 治理链内嵌：RoleBasedPermissionChecker(ctx) + EnterpriseAuditTrail(ctx) + KnowledgeTool(tenantId)
    public BusinessTask submitTask(RequestContext ctx, String description, Workflow workflow);
}
```

---

## 4. 关键设计决策（8 个）

### D1. 依赖正交：Profile 不依赖产品层与频道层

```text
agent-enterprise -> core + memory + security + workflow（不依赖 product / channel / scheduler）

裁决理由：
  产品层（13）的 TenantAgentConfig 是「租户对 YAML 定义的覆盖」——它服务声明式装配路径；
  企业 Profile 走 Java 装配路径，租户/用户是领域模型不是配置覆盖，两套租户概念不同源
  频道层（12）的 SharedAgentSession/IdentityResolver 服务多人协作频道——企业 v1 是
  「用户↔助手」一对一入口，无频道语义；服务身份 wiring 留 v2 对接（D4 数据模型先立）
  scheduler（7）的 TokenBudget 是 per-Run 计数器——CostLedger 语义对齐但不依赖实现
    （依赖一个类拖一个模块不值；两处 fail-closed 哲学一致，实现各自独立演化）

好处：Profile 层的依赖面 = 它真正消费的机制面；企业 Profile 不小心用上产品层能力时，
  编译期就会暴露「领域层泄漏进声明层」的坏味道
```

### D2. 请求作用域装配：身份显式传递，不用 ThreadLocal

```text
企业多用户共享同一个 EnterpriseAssistant —— 请求上下文无法在装配期固定

做：EnterpriseAgentFactory.forRequest(ctx) 每请求克隆执行链
    ModelClient / ToolRegistry / KnowledgeBase 共享（无状态/线程安全）
    RoleBasedPermissionChecker / EnterpriseAuditTrail / 记忆 scope 按请求构造（携带 ctx）
不做：ThreadLocal RequestContext（Stage 14 TrajectoryRecorder 已踩过同款边界——
    线程绑定在装饰器跨线程时静默失效；Web 容器的 ThreadLocal 是框架魔法不是库语义）

权衡诚实记录：每请求构造执行链有微量分配开销（几个小对象），换来的是身份流动
    全程显式可追踪——「这个权限判定为什么是 deny」的答案在调用栈上，不在 ThreadLocal 里
```

### D3. 租户隔离 = scope 白名单，不是新机制

```text
隔离的唯一实现点：MemoryStore 的 scope 白名单（Stage 8 D3 原文 "the store will
never return entries outside this list"）

企业层做三件事让机制落地：
  1) MemoryScope.Kind.TENANT + tenant() 工厂（存量纯加法，设计意图补枚举值）
  2) RequestContext.memoryScopes() = [tenant:{tid}, user:{uid}] —— 检索白名单的 SSOT
  3) KnowledgeTool 构造时绑定 tenantId —— 模型侧无法指定别人的租户

隔离测试即验收：acme 租户检索拿不到 globex 的知识条目（跨租户泄漏 = 最严重的
    企业安全事故，测试先行）；用户 scope 不含其他用户的 user scope（隐私边界）
fail-closed 哲学：白名单为空 = 检索空结果，不是全库检索
```

### D4. 审计归属 = 组合不修改（双归属数据模型，v1 先记单边）

```text
AuditEvent 是通用治理事实（runId + toolName + status），归属是企业概念——不改 record，
企业层 EnterpriseAuditEvent 组合补全：tenantId + onBehalfOf(userId) + agentName

EnterpriseAuditTrail implements AuditLogger 挂进治理链：
  治理链产生 AuditEvent（DENIED/APPROVED/EXECUTED/...）→ trail 补当前 ctx 归属 → 落账
  → 查询 API 按租户/用户切面：「这个月 acme 的拒绝事件」「alice 让 Agent 干过什么」

双归属的诚实边界：完整形态是 actor=svc:{accountId}(服务身份) + onBehalfOf=user（用户身份），
  v1 不接 channel 的 IdentityResolver（D1 正交裁决），agentName 先记装配名；
  字段位留好，v2 对接服务身份时零 schema 变更
```

### D5. 知识即记忆：KnowledgeEntry 是 MemoryStore 的一种 type

```text
知识层不建新存储：KnowledgeEntry -> MemoryEntry(type=KNOWLEDGE, scope=tenant:{tid})
  ingest = 管理员写入（免 PENDING_REVIEW——知识是受控导入不是对话沉淀，治理语义不同）
  search = MemoryRetriever + MemoryQuery(type=KNOWLEDGE, scopes=[tenant:{tid}], keyword)
  注入 = KnowledgeTool（模型自主检索）而非 ContextBuilder 预拼（检索时机交给模型决策）

为什么不是独立 KnowledgeStore：
  「同一机制」哲学第三次兑现（Stage 12 共享记忆=scope 取值 / Stage 14 workflow 轨迹
  =adapter 投影 / 本阶段知识=记忆的一种 type）——新存储 = 新隔离边界 = 新 bug 面
  租户隔离因此免费获得（scope 白名单已在），管理界面（MemoryAdmin）也因此免费复用

诚实边界：v1 keyword 检索（无向量嵌入无重排）——学习项目的 RAG v1 形态；
  嵌入与 ANN 检索留 v2（接口形态不变，换 retriever 实现）
```

### D6. 审批双层：工具级闸门 + 任务级节点

```text
工具级（Stage 9 兑现，零新代码）：GovernedToolExecutor 链内
  query_order=AUTO / refund_order=REQUIRES_APPROVAL —— loop 不停，调用前问一次
任务级（Stage 5/6 兑现，新装配）：Workflow 内 HumanApprovalNode
  submitTask → 跑到审批节点 PAUSED（checkpoint 落盘）→ WAITING_APPROVAL
  → approve → resume(runId) 从断点继续 —— 不重跑已完成节点（副作用安全）

两层的分工（面试高频）：
  闸门保安全：这次工具调用越不越权（权限视角）
  节点保流程：这单业务能不能往下走（业务视角）
  企业剧本里两层同时出现：退款任务跑到退款工具时，工具级审批先拦（角色不够），
  主管审批任务后 resume，再过工具级审批（主管角色）—— 两层叠加不是冗余是纵深
```

### D7. BusinessTask = run 的业务投影（1:N + 状态映射）

```text
task ≠ run：一个任务可能对应多个 run（提交 run / 审批后 resume 同一 run / 失败重试新 run）
  BusinessTask.runIds 是历史列表，当前 run = 最后一个
  状态映射：workflow ExecutionResult.status -> BusinessTask.Status
    SUCCEEDED→DONE / FAILED→FAILED / PAUSED→WAITING_APPROVAL / CANCELLED→CANCELLED

为什么需要这层投影：
  RunManager 索引的是 runId（技术标识），企业用户与主管索引的是业务标识（"退款单 T-1032"）
  TaskApprovalRecord 挂在 task 上不是 run 上——审批的是业务不是执行
  崩溃恢复的入口也在 task 维度：taskManager.recover(taskId) -> resume(runId, workflow)
```

### D8. 成本 fail-closed：事前闸 + 事后记账，账单是查询不是推送

```text
事前：ask/submitTask 入口 requireBudget(ctx) —— 租户或用户额度耗尽直接拒绝
  （拒绝是诚实行为：SLA 承诺的是额度内服务，不是无限服务）
事后：run 结束 record(ctx, tokens) —— TokenUsage 累加进租户/用户账本
不做：run 中途强制熔断（模型调用已发出，半途熔断只省一半钱还留下半成品状态——
  v1 边界：预算闸粒度=请求级，run 级熔断的语义设计留给 Stage 18 与 TokenBudget 合流）

与 Stage 7 TokenBudget 的关系：哲学同源（fail-closed 计数器）、维度不同
  （TokenBudget=per-Run，CostLedger=per-tenant/per-user 跨 run 累计）——
  javadoc 原文 "Complex per-user/per-model/time-window limiting is Stage 18 scope"，
  本阶段先落 per-tenant/per-user 两维，时间窗与降级路由归 18
```

---

## 5. 分层架构图

```text
┌───────────────────────────────────────────────────────────────────────┐
│ examples: EnterpriseAssistantExample（全链路验收剧本）                    │
└───────────────────────────────────┬───────────────────────────────────┘
                                    │
┌───────────────────────────────────▼───────────────────────────────────┐
│ agent-enterprise（Stage 15 新增）                                       │
│                                                                       │
│  tenant/    Tenant / User / TenantRegistry / RequestContext            │
│             —— D2：身份显式传递；D3：memoryScopes() 白名单 SSOT          │
│  knowledge/ KnowledgeEntry / KnowledgeBase / KnowledgeTool             │
│             —— D5：知识即记忆（type=KNOWLEDGE, scope=tenant:{tid}）      │
│  govern/    RoleBasedPermissionChecker / EnterpriseAuditEvent          │
│             / EnterpriseAuditTrail / CostLedger                        │
│             —— D3/D4/D8：角色矩阵 / 归属审计 / 预算闸与账本              │
│  task/      BusinessTask / TaskApprovalRecord / EnterpriseTaskManager  │
│             —— D6/D7：任务级审批 + run 的业务投影                        │
│  (root)     EnterpriseAgentFactory / EnterpriseAssistant               │
│             —— D2：请求作用域装配 + 企业统一入口                          │
└────┬──────────────┬──────────────────┬──────────────────┬──────────────┘
     │ compile      │ compile          │ compile          │ compile
┌────▼─────────┐ ┌──▼───────────────┐ ┌▼──────────────┐ ┌─▼───────────────────┐
│ agent-core   │ │ agent-memory     │ │ agent-security │ │ agent-workflow      │
│ Agent/Config │ │ MemoryStore      │ │ GovernedTool   │ │ RunManager/Checkpt  │
│ /ToolExecutor│ │ /MemoryScope     │ │ Executor       │ │ /HumanApprovalNode  │
│ /ChatMessage │ │ /MemoryRetriever │ │ /Permission    │ │ /Workflow/Graph     │
│              │ │  (+TENANT+       │ │  Checker(扩展点)│ │  Runtime            │
│              │ │   KNOWLEDGE 加法) │ │ /AuditLogger   │ │                     │
└──────────────┘ └──────────────────┘ └────────────────┘ └─────────────────────┘
   （agent-model MockModelClient = test scope；product/channel/scheduler 不依赖 = D1）
```

数据流（一次企业请求的旅程）：

```text
员工 alice ──login(tenant, user, apiKey)──▶ TenantRegistry ──▶ RequestContext
                                                                        │
alice ──ask(ctx, "订单 8842 查不到就发起退款")──▶ EnterpriseAssistant      │
                                                    │                    │
                       ┌────────────────────────────┤                    │
                       ▼                            ▼                    ▼
                 CostLedger.requireBudget     forRequest(ctx)      EnterpriseAuditTrail
                 （事前闸：超限拒绝）          （请求作用域克隆执行链）   （等待归属补全）
                                                    │
                                    ┌───────────────▼────────────────┐
                                    │ ReActAgentLoop + 治理链         │
                                    │  ├ RoleBasedPermissionChecker  │
                                    │  ├ GovernedToolExecutor        │
                                    │  └ MemoryContextBuilder        │
                                    │      scopes=[tenant:acme,      │
                                    │                user:u-alice]    │
                                    └───────┬───────────┬────────────┘
                                            │           │
                            search_knowledge│           │query_order / refund_order
                             (KnowledgeTool │           │(业务工具：AUTO / REQUIRES_APPROVAL)
                              tenant:acme)  ▼           ▼
                                    KnowledgeBase    mock 业务系统
                                    (scope 白名单)   (审批 → [APPROVED] → 执行)
                                            │
                                            ▼
                              run 结束：CostLedger.record(ctx, tokens)
                              审计流水：byTenant("acme") / byUser("alice") 可查
```

---

## 6. 完整时序：一个「检索→审批→恢复」的企业剧本

```text
T0: 装配（管理员一次性）
    TenantRegistry：注册 acme / globex 两租户 + 用户（alice=客服, bob=主管, carol=客服）
    KnowledgeBase：ingest(acme, [退货政策, 订单流程 FAQ...])；globex 各自导入
    CostLedger：acme 月度预算 100_000 tokens；alice 个人额度 10_000
    治理矩阵：客服={search_knowledge, query_order}，主管=+refund_order
    工厂：modelClient(Mock scripted) + 工具注册 + 上述全部

T1: 登录识别
    ctx = registry.login("acme", "alice", "key-***")     // 未知用户/停用租户 → IAE fail-closed

T2: 同步问答（RAG + 审计 + 记账）
    assistant.ask(ctx, "退货政策里说拆封后还能退吗？")
    -> requireBudget：acme 已用 1.2k / 100k，alice 已用 300 / 10k —— 放行
    -> forRequest(ctx)：权限矩阵（客服）+ 审计 trail（归属 alice）+ 记忆 scope 白名单
    -> 模型调 search_knowledge("拆封 退货") → KnowledgeBase → acme 的政策条目（拿不到 globex）
    -> 终答引用知识条目；record(ctx, 812+45) 入账

T3: 敏感工具审批（工具级闸门）
    assistant.ask(ctx, "帮我把订单 8842 退款")   // alice 是客服
    -> 模型决定调 refund_order → RoleBasedPermissionChecker：客服矩阵无此工具
       → 但 ToolPolicy 兜底 = REQUIRES_APPROVAL → 审批服务（v1 console/callback）
       → 主管放行 → 执行 → 审计流水 [APPROVED + EXECUTED, onBehalfOf=alice]

T4: 长任务（任务级审批 + 断点恢复）
    task = taskManager.submit(ctx, "处理订单 8842 退款工单", refundWorkflow)
    // workflow: 查单(AgentNode) -> 校验(ActionNode) -> 主管审批(HumanApprovalNode) -> 执行退款(ToolNode)
    -> 跑到审批节点 PAUSED（checkpoint 落 FileCheckpointStore）→ task=WAITING_APPROVAL
    -> taskManager.approve(taskId, "bob", "金额在授权内")     // bob 是主管
       → TaskApprovalRecord 留痕 + resume(runId)：从审批节点之后继续，查单/校验不重跑
    -> 执行退款（敏感工具层再过一次治理）→ task=DONE

T5: 失败恢复（崩溃场景）
    模拟：审批前进程崩溃 → 重启 → EnterpriseTaskManager.recover(taskId)
       → RunManager.resume(runId, workflow) 从 FileCheckpointStore 载入断点
       → 从暂停位置继续（已完成节点的副作用不重复执行——幂等由 checkpoint 边界保证）

失败分支：
    F1 登录失败：未知租户/用户/key 不匹配/租户 SUSPENDED → EnterpriseAuthException（fail-closed）
    F2 预算耗尽：requireBudget 抛 BudgetExceededException → 用户收到诚实拒绝而非慢速烧钱
    F3 跨租户检索：KnowledgeTool 只持有本租户 ID + store 白名单双重拦截 → 机制上不可能
    F4 审批拒绝：reject → 留痕 + run CANCELLED → task=CANCELLED（不回滚已发生副作用，补偿 v2）
    F5 任务崩溃：checkpoint 在盘 → recover 从断点续跑（T5）
```

---

## 7. 模块结构

```text
agent-enterprise/                                 # 新增 Maven 模块（父 POM <modules> 增补）
└── src/main/java/io/github/qwzhang01/agent/enterprise/
    ├── tenant/                                   # 4 类（M15.1）
    │   ├── Tenant.java / User.java
    │   ├── TenantRegistry.java / RequestContext.java
    │   └── EnterpriseAuthException.java
    ├── knowledge/                                # 3 类（M15.2）
    │   ├── KnowledgeEntry.java / KnowledgeBase.java
    │   └── KnowledgeTool.java
    ├── govern/                                   # 4 类 + 1 异常（M15.3）
    │   ├── RoleBasedPermissionChecker.java
    │   ├── EnterpriseAuditEvent.java / EnterpriseAuditTrail.java
    │   └── CostLedger.java / BudgetExceededException.java
    ├── task/                                     # 3 类（M15.4）
    │   ├── BusinessTask.java / TaskApprovalRecord.java
    │   └── EnterpriseTaskManager.java
    ├── EnterpriseAgentFactory.java               # 装配（M15.5）
    └── EnterpriseAssistant.java
```

```text
存量改动（仅 2 处纯加法，§2 预检裁决）：
agent-memory/.../MemoryScope.java    Kind + TENANT；+ tenant(String) 工厂
agent-memory/.../MemoryType.java     + KNOWLEDGE
```

```text
examples/（新增 1 个）
└── EnterpriseAssistantExample.java  # 验收剧本：T1-T5 全景（登录→RAG→工具审批→任务审批→恢复）
                                     # + 跨租户隔离演示 + 预算拒绝演示
```

不改动其他任何存量模块（§2 存量改动清单是完整清单）。

---

## 8. 实现里程碑（5 个，节奏对齐 Stage 13/14）

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M15.1 | 租户与用户域 | tenant 5 文件 + MemoryScope/MemoryType 两处纯加法 + 单测 | 登录成功产出 RequestContext（scope 白名单正确）；五类 fail-closed（未知租户/用户/key 错/停用/用户归属未注册租户）全 IAE；`tenant:acme` scope 的记忆条目对 globex 检索不可见（隔离测试先行）；MemoryScope 存量测试零影响 |
| M15.2 | 知识层（RAG） | knowledge 3 类 + 单测 | ingest→search round-trip；**跨租户零泄漏**（acme 检索拿不到 globex 条目，双向验证）；KnowledgeTool 返回 JSON 格式知识切片供模型消费；type=KNOWLEDGE 过滤不混入 PREFERENCE/FACT |
| M15.3 | 治理接线 | govern 5 文件 + 单测 | 角色矩阵：alice(客服) 调 query_order=AUTO、refund_order 兜底 REQUIRES_APPROVAL、delete_order=DENY；审计归属：byUser("alice") 能查到她触发的全部工具事件（含 DENIED）；CostLedger：预算内放行/超限拒绝/记账累加/两级额度独立核算 |
| M15.4 | 业务任务与恢复 | task 3 类 + 单测 | submit→PAUSED→WAITING_APPROVAL；approve→resume 后**已完成节点不重跑**（用节点计数器断言查单节点只执行 1 次）；reject→CANCELLED + 留痕；崩溃恢复：新 RunManager 实例 + FileCheckpointStore 从盘上恢复续跑；task 状态映射四态全测 |
| M15.5 | 装配与收口 | 工厂 + 门面 + EnterpriseAssistantExample + README/笔记收口 | 示例实跑 T1-T5 全剧本（两租户三用户：登录→RAG 引用知识→客服触发工具审批→主管放行任务→断点恢复→账单查询）；**carol 问 globex 的问题拿不到 acme 知识**（隔离贯穿演示）；预算耗尽拒绝演示；全仓存量零影响 |

依赖：M15.2 ← M15.1（scope）；M15.3 ← M15.1（RequestContext）；M15.4 ← M15.1；M15.5 ← 全部。主路径串行，M15.2/M15.3/M15.4 相互独立可交叉推进。

---

## 9. 验收标准（对齐 18 周规划原文）

```text
规划原文：完成一个企业客服或内部知识助手，至少具备：
登录用户识别、知识检索、业务工具调用、敏感操作审批、审计记录、运行失败恢复。

1. 登录用户识别
   -> M15.1 TenantRegistry.login → RequestContext（五类 fail-closed）
2. 知识检索
   -> M15.2 KnowledgeBase/KnowledgeTool（租户隔离的 RAG v1，keyword 检索）
3. 业务工具调用
   -> 复用 Tool 注册 + HttpApiTool 路径 + M15.3 角色权限矩阵（AUTO 档）
4. 敏感操作审批
   -> 工具级（Stage 9 治理链 + 角色矩阵）+ 任务级（M15.4 approve/reject）双层
5. 审计记录
   -> M15.3 EnterpriseAuditTrail：全工具事件带租户/用户归属，可按切面查询
6. 运行失败恢复
   -> M15.4 断点恢复（审批后 resume）+ 崩溃恢复（FileCheckpointStore 重启续跑）

「需要支持」九项对照：多租户=M15.1/15.2 / 用户权限=M15.3 / 业务工具=装配 /
审批=M15.3+M15.4 / 审计=M15.3 / RAG=M15.2 / 长任务=M15.4 / 人工接管=M15.4
（HumanApprovalNode 暂停即接管点）/ 成本和 SLA=M15.3（CostLedger v1 轻量，
完整 SLA 治理归 Stage 18）
```

---

## 10. 测试策略

- **隔离（最高优先级）**：跨租户知识/记忆零泄漏（双向）；用户 scope 隐私边界；白名单空 = 空结果（fail-closed 不放大）
- **fail-closed 全覆盖**：登录五形态 / 预算两级超限 / 权限矩阵未知工具走兜底策略
- **归属正确性**：同一治理事件在 trail 中的 tenantId/userId 与触发请求的 ctx 一致；DENIED 事件也归属（「谁被拒了」同是情报，对齐 Stage 9 D6）
- **恢复语义**：resume 后已完成节点不重跑（节点执行计数断言——副作用安全的核心证明）；崩溃恢复用新 RunManager 实例模拟进程重启
- **记账正确性**：多请求累加 / 租户与用户两级独立 / 事前闸与事后记账的时序（闸在前）
- **向后兼容**：MemoryScope/MemoryType 纯加法——agent-memory 存量测试全绿；其余模块零 diff
- **Mock 验收**：全链路零 LLM 依赖（MockModelClient scripted，同 Stage 8-14 手法）

---

## 11. 文章规划（规划原文 6 篇全收）

| 文章（规划原文） | 写作时机 | 素材来源 |
|---|---|---|
| 《企业 Agent 为什么必须区分 Agent 和 Workflow》 | M15.4 | D6 审批双层 + D7 task/run 投影（闸门保安全/节点保流程） |
| 《企业 Agent 的完整请求链路》 | M15.5 | §6 时序 T2：预算闸→装配→run→记账→审计全链 |
| 《企业 Agent 如何接入真实业务系统》 | M15.5 | HttpApiTool 路径 + mock 业务系统 + KnowledgeTool |
| 《企业 Agent 的权限、审批和审计模型》 | M15.3 | D3/D4/D6：角色矩阵 / 归属审计 / 双层审批 |
| 《多租户 Agent 的状态隔离》 | M15.1/M15.2 | D3 scope 白名单——「隔离不是新机制是白名单兑现」 |
| 《从 Demo 到企业 Agent，需要补齐哪些控制面》 | 收口 | §1 五假设破裂 + M8 里程碑叙事（三类场景同 Runtime） |

**系列衔接**：文章 1 与 agent-arch 系列边界主题呼应；文章 5 兑现 Stage 13 遗留的「租户记忆隔离 v2」承诺；文章 6 是 Stage 16/17（另两个 Profile）的引子——「同一 Runtime 第一个 Profile 讲完，换领域模型再讲两遍」。

---

## 12. 本阶段不做（范围控制）

- **向量检索 / 嵌入 / 重排** —— v1 keyword 检索（MemoryRetriever 现状）；接口形态不变，换 retriever 实现即可升级
- **真实 SSO / OAuth / OIDC** —— v1 apiKey + 内存用户表（登录识别的机制验证，不是身份供应商集成）
- **Web 前端 / REST 服务化** —— v1 库形态 + 示例剧本；HTTP 壳是装配层（同 Stage 13 WebhookController 的传输无关纪律）
- **Agent 服务身份完整对接** —— D4 数据模型（actor/onBehalfOf 双归属）先立，IdentityResolver wiring 留 v2（企业无频道场景，D1 正交裁决）
- **补偿事务 / 副作用回滚** —— reject 只取消不回滚（诚实边界）；Saga 补偿留 v2
- **完整成本治理** —— 时间窗额度 / 模型路由降级 / 成本仪表盘渲染归 Stage 18（CostLedger 只做账本与两级额度）
- **分布式多实例 / 数据库持久化** —— v1 单进程；TenantRegistry/CostLedger 内存实现，CheckpointStore 复用 File 实现
- **审批人路由（找谁审批）** —— v1 审批服务由装配层提供（console/callback）；按角色/金额动态路由审批人留 v2
- **PII 脱敏 / 数据合规管道** —— 知识与审计含业务数据，生产使用需自行合规审查（同 Stage 14 §12 口径）

---

## 13. M15.1 实现记录（2026-08-24，租户与用户域）

### 交付

- 新增 `agent-enterprise` Maven 模块（父 POM `<modules>` + dependencyManagement 注册；compile 依赖仅 `agent-memory`——「依赖随用随加」纪律，agent-core/security/workflow 按 M15.3-M15.5 里程碑落地时再加）
- **存量两处纯加法（预检裁决兑现）**：`MemoryScope.Kind + TENANT` + `tenant(String)` 工厂（javadoc 补租户 scope 语义）/ `MemoryType + KNOWLEDGE`（admin 导入的业务知识，区别于对话沉淀）。agent-memory 存量 66 测试零影响
- **tenant 包 5 文件**：
  - `Tenant`（record：tenantId + displayName + ACTIVE/SUSPENDED + monthlyTokenBudget，`UNLIMITED_BUDGET=-1` 沿用 Stage 12 ServiceAccount 惯例；`suspended()` wither 派生新实例）
  - `User`（record：userId + tenantId + displayName + roles；**凭证刻意不入 record**——apiKey 存 TenantRegistry 内部表，身份与凭证分离，`toString()` 永不泄密；null roles 归一化空集）
  - `RequestContext`（record：tenant + user + sessionId 自动生成；**`memoryScopes()` = [tenant:{tid}, user:{uid}] 检索白名单 SSOT**，直接喂 MemoryQuery；`actor()` = "user:{uid}" 审计归属）
  - `TenantRegistry`（注册 + 登录闸门；注册校验：归属租户已注册且 ACTIVE / userId 全局唯一 / apiKey 非空；**登录五类 fail-closed**：未知租户 / 租户 SUSPENDED / 未知用户 / 用户归属不一致（evidence 带双租户名）/ key 不匹配；`suspendTenant` 管理员停用入口——已发 context 持有不可变快照不受影响）
  - `EnterpriseAuthException`（fail-closed 家族，对齐 Stage 12 IdentityResolutionException 风格）
- 测试 27 个：`TenantRegistryTest` 20（登录成功字段 / sessionId 每次新鲜 / memoryScopes 白名单 / actor / **五类登录拒绝逐条 evidence 断言** / 注册四类拒绝 / record 校验 / lookups 不泄凭证）+ `TenantIsolationTest` 7（**跨租户零泄漏双向**：keyword 命中条件下 acme/globex 各只见自己的 / 空白名单=空结果 fail-closed 不放大 / 用户隐私边界 / **login 白名单端到端**：alice 的 recall 恰好=acme 知识+alice 记忆，bob 记忆与 globex 知识均不可见 / KNOWLEDGE type 过滤不混 FACT）
- **全仓 712 全绿**（存量 685 零影响 + 27 新增）

### 隔离测试的构造要点（写测试时的坑，记入防复发）

1. **keyword 双向对照的前提是两边都命中**：初版 globex 条目内容 "all sales final" 不含关键词 "returns"，用同一关键词检索时 globex 恒为 0——测试失败但**不是隔离生效而是数据不可检索**，隔离证明失效。修正：两侧内容都含共同关键词 "policy"，让「唯一能造成差异的变量」只剩 scope（对照实验的基本功：控制变量）
2. **测试 suspended 租户的登录拒绝**：不能走「重复 registerTenant 同名租户」路径（注册天然拒绝重名，抛的是 already registered 不是 SUSPENDED）——正确路径是 `suspendTenant` 生命周期 API + 已注册用户再 login。为此 TenantRegistry 增补 `suspendTenant(String)`（超出蓝图 5 文件的第 6 个公开方法，领域语义：管理员停用）
3. 环境备忘：JAVA_HOME 默认指向 JDK 8，跑 mvn 需 `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/...`；跨模块依赖改了存量（agent-memory 加法）后先 `mvn install -pl agent-memory` 再单模块测试，否则 .m2 旧 jar 报「找不到符号」（Stage 14 M14.4 同款坑重现）

### 与蓝图的一致性

- 16 抽象中的第一组（tenant 4 类）+ 异常落地，签名与 §3.1 草图一致（RequestContext.actor()/memoryScopes() 命名未变）
- D3 租户隔离=scope 白名单：`TenantIsolationTest.contextWhitelistSeesOwnDataOnly` 是蓝图论点「隔离是机制不是约定」的可执行证明
- 诚实边界：apiKey 单表凭证（v1）；`Tenant.monthlyTokenBudget` 字段已立、CostLedger 消费在 M15.3

---

## 14. M15.2 实现记录（2026-08-24，知识层 RAG）

### 交付

- **pom 增补**：agent-core compile 依赖落地（KnowledgeTool implements Tool；「依赖随用随加」纪律第二次执行）
- **knowledge 包 3 类**：
  - `KnowledgeEntry`（record：title/content/source/tags）——**存储投影** `toMemoryEntry(tenantId, adminId)`：type=KNOWLEDGE + scope=tenant:{tid} + **ACTIVE 直接生效**（受控导入免 PENDING_REVIEW——治理语义与对话沉淀刻意区分：review 已在管理员决定导入时发生）/ `fromMemoryEntry` 反向投影
  - `KnowledgeBase`（门面：`ingest` 批量导入 + `search(tenantId, query, topK)` + `count`）——**隔离由 store 白名单保证非本类**（javadoc 明写：search 恰好查一个 tenant scope，共享 store 多租户部署形态安全由构造保证）；检索 = MemoryQuery(type=KNOWLEDGE + keyword)，v1 keyword 诚实边界（向量 v2 换 retriever 实现接口形状不变）
  - `KnowledgeTool`（`implements Tool`：search_knowledge）——**租户绑定构造期注入且不可变**：tenantId 不出现在参数 schema（测试断言 schema 无 tenant 参数）——即使注入的恶意 prompt 指定 `{"tenant": "globex"}` 也被无视（参数没有此槽位）+ store 白名单双保险；输出 JSON 契约 `{"count": n, "results": [{title, content}]}`（结构化切片供模型引用；空结果 count=0 + message 诚实空非异常）
- 测试 +18（KnowledgeBaseTest 11 + KnowledgeToolTest 7... 共 18）：ingest→search round-trip / **跨租户零泄漏双向** / **共享 store 多租户**（一 store 三租户各搜各的，标准部署形态）/ **KNOWLEDGE type 纯净性**（tenant scope 里直接写 FACT 的 legacy 数据不冒充知识）/ topK 截断与默认 / **租户绑定不可变**（恶意 tenant 参数实证无效）/ JSON 输出契约解析验证 / 空结果诚实形态 / 缺 query ToolException
- **全仓 730 全绿**（存量 712 零影响 + 18 新增）

### 关键裁决：source/tags 的诚实边界（D5 纪律的延伸）

`MemoryEntry` 没有自由元数据槽位，KnowledgeEntry 的 source/tags **导入侧有、检索侧无**——`fromMemoryEntry` 返回空而非编造（如从 content 里猜出处）。宁可缺失不可造假：source/tags 在 MemoryEntry 长出 custom 字段时（v2）跟进持久化。javadoc 立住边界 + 测试 `honestMetadataBoundary` 锁住行为。

### 与蓝图的一致性

- 蓝图 §3.1 草图的三类签名全部兑现（ingest/search/forTenant）；D5「知识即记忆」落为 toMemoryEntry 投影 + type=KNOWLEDGE 过滤（同一机制第三次兑现）
- D5「注入=KnowledgeTool 模型自主检索而非 ContextBuilder 预拼」兑现：检索时机是模型决策，检索边界（租户）是装配决策——两者分离
- 与 M15.1 的衔接：knowledge 隔离测试与 tenant 隔离测试互相独立但共享同一 store 机制；M15.5 装配时 RequestContext.memoryScopes()（tenant+user）与 KnowledgeBase.search（仅 tenant）形成「知识=租户共享、记忆=租户+个人」的双层检索形态

---

## 15. M15.3 实现记录（2026-08-24，治理接线）

### 交付

- **pom 增补**：agent-security compile 依赖（PermissionChecker/ToolPolicy/AuditLogger/AuditEvent；「依赖随用随加」第三次执行）
- **govern 包 5 类**：
  - `RoleBasedPermissionChecker extends PermissionChecker`——**正式兑现 Stage 9 预留的扩展点**（ToolPermission javadoc 原文 "intentionally NOT fine-grained RBAC... RBAC is Stage 15 (Enterprise Profile)"，本里程碑落笔）。组合规则 deny-first 三步：①兜底 policy DENY → DENY（**硬禁不可被角色矩阵豁免**——矩阵收缩权限，永不覆盖治理）②任一角色矩阵命中 → AUTO ③兜底值。**请求作用域**（D2）：roles 构造期绑定，GovernedToolExecutor 零改动（它调 check(toolName) 无用户参数——绑定发生在更早的装配时刻）
  - `EnterpriseAuditEvent`（record：组合 AuditEvent + tenantId + userId + agentName）——D4「组合不修改」：不改 Stage 9 不可变 record，包装补归属；字段槽位已为 v2 服务身份（svc:{accountId}）预留，schema 不再变
  - `EnterpriseAuditTrail`——**双角色一类**（对齐 KnowledgeBase.forTenant 模式）：装配级共享台账（byTenant/byUser/byTool/all 切面查询）+ `forRequest(ctx, agentName)` 返回请求作用域 AuditLogger（内部类补归属后入账；getAll/getByRun/getByTool 只看本请求事件——AuditLogger 契约的 drop-in 替换）。**DENIED 事件同样归属**（「哪个租户的哪个用户被拦了」是安全情报，Stage 9 D6 延伸）
  - `CostLedger`——D8 两操作严格有序：`requireBudget(ctx)` 事前闸（租户额度读 ctx.tenant() 登录快照 + 用户额度装配注入 map，任一耗尽抛 BudgetExceededException 带 dimension/used/limit 证据）/ `record(ctx, prompt, completion)` 事后记账（两级累加）。哲学对齐 Stage 7 TokenBudget（fail-closed 计数器）维度不同（per-Run → per-tenant/per-user 跨 run 累计）
  - `BudgetExceededException`（维度/已用/上限证据齐全——拒绝本身是诚实行为）
- 测试 +24（PermissionChecker 8 + AuditTrail 7 + CostLedger 9）：**蓝图三档验证案例**（客服 query_order=AUTO / refund_order=REQUIRES_APPROVAL / delete_order=DENY）/ **deny-first 实证**（越权矩阵给客服配 delete_order 仍 DENY）/ 多角色并集 / **归属审计**（byUser 查到 alice 全部事件含 DENIED；byTool 事故切面保留归属）/ **请求视图隔离**（alice 的 logger 看不到 bob 的 run）/ 预算闸翻转（199 过 200 拒）/ 两级独立核算 / unlimited 永不抛
- **全仓 754 全绿**（存量 730 零影响 + 24 新增）

### 实现期坑（记入防复发）

1. **字段与查询方法同名**：`tenantUsed` 字段（Map）与 `tenantUsed(String)` 查询方法同 namespace 冲突——方法体内 `tenantUsed(ctx.tenantId()).get()` 解析到查询方法（返回 long）再 .get() 报「无法取消引用 long」。修复：字段改名 `tenantCounters/userCounters` + 统一走 `counter(map, key)` helper。教训：**查询方法名（tenantUsed）与内部存储字段名必须错开**
2. 测试断言算术错两起（tenant 账单含两用户之和 465≠450；alice 个人 150+300=450≠350）与语义错一起（tenantOnly 台账记录到租户上限后租户闸仍关——用户维度缺席≠租户豁免）——测试失败先核对自己的算式和语义再怀疑实现，本轮 4 个失败全部是测试侧 bug，实现零改动

---

## 16. M15.4 实现记录（2026-08-24，业务任务与恢复）

### 交付

- **pom 增补**：agent-workflow compile 依赖（RunManager/HumanApprovalNode/Workflow/ExecutionResult；「依赖随用随加」第四次执行）
- **task 包 4 类**（蓝图 3 类 + 审批通道，超出理由见下「架构发现」）：
  - `BusinessTask`（record + wither：taskId/tenantId/submitterId/description/status/runIds/approvals/createdAt/updatedAt）——D7 兑现：**task ≠ run**，runIds 是 1:N 历史、currentRunId() 取末位；状态机 SUBMITTED→RUNNING→WAITING_APPROVAL→DONE/FAILED/CANCELLED，四态映射自 ExecutionResult.Status（PAUSED→WAITING_APPROVAL 的 v1 语义：本 Profile 里唯一会暂停 run 的就是审批节点）
  - `TaskApprovalRecord`（record：taskId/approverId/Decision/reason/at）——D6「节点保流程」的留痕 SSOT：审批挂在 task 上不是 run 上——主管审批的是业务不是执行
  - `TaskApprovalBridge`（**装配级审批通道**，ApprovalService 实现）——工作流侧问（requestApproval→pause）、管理侧答（decide→resume）；**刻意仅内存**：重启后决策表为空=未消费的决策必须重新给出（recover 正是依赖这个语义）
  - `EnterpriseTaskManager`——submit（创建 task + start run + **runId 差集捕获**）→ approve（decide+resume 断点续跑 + 留痕）/ reject（cancel 标记 + resume 落地 CANCELLED + 留痕）/ recover（快照重登 + resume(runId, workflow) 从盘拉回）/ find/byTenant 查询
- 测试 +12：submit→WAITING_APPROVAL（prepare 已执行 execute 未执行的中间态断言）/ **approve→resume 完成节点不重跑**（prepare 恰好 1 次——副作用安全的核心证明）/ **双审批闸门**（第一闸 approve 后仍 WAITING_APPROVAL，check 节点跨两次 approve 仍只执行 1 次）/ reject→CANCELLED（execute 计数 0 + 留痕）/ FAILED 映射（审批后节点失败）/ **崩溃恢复**（新 RunManager + 同 FileCheckpointStore + 共享 bridge：recover 再暂停→approve→DONE，prepare 全生命周期恰好 1 次）/ **非 WAITING_APPROVAL 拒绝决策**（fail-closed）/ 无审批直通 DONE（terminal run 的 runId 差集捕获路径）/ bridge 拒 sync 模式 / **私 bridge 服务不了外部 run 的回归测试**
- **全仓 766 全绿**（存量 754 零影响 + 12 新增）

### 架构发现：审批通道必须比 manager 活得久（本轮最有价值的教训）

初版把 ApprovalBridge 做成 manager 的私有内部类，崩溃恢复测试当场翻车：mgr2.approve 写进**自己新 bridge 的决策表**，而 workflow 的 HumanApprovalNode 在构造时绑定的还是**死掉的 mgr1 的 bridge**——checkDecision 永远 null，恢复后的 run 无限再暂停。

根因：**workflow 是不可变定义，节点在构造期捕获 service 实例；manager 却是可死可换的运行期对象**。把「决策表」放进 manager 的私有状态，等于把跨代际状态放进了最短命的对象里。

修复：`TaskApprovalBridge` 提升为装配级公开类——构造双签名（默认私有 bridge 便捷单代生命周期 / 注入共享 bridge 服务崩溃恢复代际）。教训固化为回归测试 `privateBridgeCannotServeForeignRuns`：用错通道的 approve 永远卡 WAITING_APPROVAL。这与 D2「请求作用域装配」是同一哲学的两面：**绑定什么要看对象的生命周期，不是看谁用起来方便**——权限/审计绑请求（每请求一份），审批通道绑装配（跨代际一份）。

### 实现要点

- **runId 差集捕获**：RunManager.start 只在 PAUSED 时经 resumeToken 暴露 runId（terminal 无路径）——同步 start 前后 listRuns() 快照差集是零存量改动纪律下的唯一通用捕获法，PAUSED 时优先 token（权威源）。蓝图预告过这处妥协
- **reject = cancel 标记 + resume 落地**：RunManager.cancel 是协作式标记（PAUSED run 上只置 flag），需要一次 resume 让 run 走到 CANCELLED 终态——已执行节点不回滚（Saga 补偿 v2），拒绝记录是「什么被谁拦下」的证据
- **recover 语义**：resume(runId, workflow) 会重新进入暂停的审批节点，checkDecision 见空表→再 PAUSED（安全幂等）——恢复后 task 回到 WAITING_APPROVAL，主管重新审批即可




---

## 17. M15.5 实现记录（2026-08-24，装配与收口）· Stage 15 收官

### 交付

- **装配 2 文件**（agent-enterprise 根包）：
  - `EnterpriseAgentFactory`（D2 请求作用域装配核心 + Builder）——`forRequest(ctx)` 每请求克隆五件：①per-request 工具注册表（共享无状态业务工具 + **租户绑定的 KnowledgeTool**）②`TrackingModelClient` 装饰器（ModelClient 组合，逐次把 TokenUsage 累进 UsageTracker——**run 结束后 CostLedger 记账的数据源**，组合优于修改不动 agent-core）③`RoleBasedPermissionChecker.forRequest`（roles 构造期绑定）④`EnterpriseAuditTrail.forRequest`（归属审计视图）⑤`MemoryContextBuilder`（scopes=ctx.memoryScopes() 记忆注入）；共享件=ModelClient/业务工具/角色矩阵/兜底 policy/审计台账。Builder 产出 `EnterpriseAssistant` 门面
  - `EnterpriseAssistant`（企业统一入口）——`ask(ctx, question)` 同步链：**requireBudget 事前闸 → forRequest 装配 → run → finally 记账**（花掉的 token 无论成败都记——tokens burned are tokens burned）；`submitTask/approve/reject/findTask` 委托 EnterpriseTaskManager（同一预算闸）；`forRequest/auditTrail()/costLedger()/taskManager()` 高级访问
- **测试 +8**（EnterpriseAssistantTest）：ask 全链（RAG 工具调用 + **审计事件里的 result 即本租户知识切片**——用审计证据断言端到端隔离 + 857 tokens 记账入租户/用户两级）/ **跨租户端到端**（carol 的审计事件 result 含 globex 不含 acme；acme 台账不含 carol 请求）/ **预算闸零消耗证明**（拒绝后 userUsed 不变）/ **审批搭车**（CSR 触发 refund_order → APPROVED+EXECUTED 事件全归属 alice）/ 门面任务路径（submitTask→WAITING_APPROVAL→approve→DONE）/ 无 taskManager 快速失败 / Builder 校验 / forRequest 暴露链
- **EnterpriseAssistantExample 全剧本**（examples 模块，pom 增补 agent-enterprise）：T1 登录（scopes/actor 可见）→ T2 RAG+审计+记账 → T3 工具审批搭车（打印审批事件对）→ T4 任务审批断点恢复（T-0001 WAITING_APPROVAL→bob 批准→DONE 留痕）→ T5 租户隔离（carol 拿 globex 知识 / acme 审计不含她）+ **dave 预算诚实拒绝**（used=60 ≥ limit=50 → BudgetExceededException fail-closed 零 token 消耗）
- **全仓 774 全绿**（存量 766 零影响 + 8 新增）

### 示例剧本的两个演示坑（记入防复发）

1. **预算拒绝演示必须共享同一个 CostLedger**：初版 dave 两次 ask 各自 new CostLedger——第二本空账没有第一次的记账，闸门永不触发。预算闸的语义是「事前读事后写」，前提是读写落在**同一本账**上；演示/装配都要复用装配级实例
2. **`mvn exec:java` 不触发编译**：改完示例直接 exec 跑的是 target/classes 旧字节码（症状：新打印语句不出现，疑似「异常没抛」假象）——先 `compile` 再 `exec:java`

### 与蓝图的一致性（最终核对）

- §3.1 草图签名全兑现：`forRequest(ctx)`/`ask(ctx, question)`/`submitTask(ctx, description, workflow)`；Builder 汇总蓝图 §6 T0 装配的全部组件
- §6 时序 T1-T5 逐段落地为 Example 剧本（T1 登录/T2 RAG/T3 工具审批/T4 任务审批恢复/T5 隔离+预算拒绝）；F2 预算失败分支真实验证
- §9 验收 6 条全达成：登录识别=T1 / 知识检索=T2 / 业务工具调用=orderTool+治理 / 敏感操作审批=T3+T4 双层 / 审计记录=归属台账可查 / 失败恢复=T4 断点+M15.4 崩溃恢复
- 「需要支持」九项对照：多租户/用户权限/业务工具/审批/审计/RAG/长任务/人工接管/成本——全部落地（成本为 v1 轻量版，完整治理归 18）

### Stage 15 收官总结

- **模块**：agent-enterprise（tenant/knowledge/govern/task 四包 19 文件 + 根包装配 2 文件；compile 依赖 core+memory+security+workflow，D1 正交裁决全程未破）
- **存量改动**：仅 M15.1 预检裁决的两处纯加法（MemoryScope.TENANT / MemoryType.KNOWLEDGE），其余全程零存量改动
- **测试**：89 个（tenant 27 + knowledge 18 + govern 24 + task 12 + assistant 8），全仓 774 全绿
- **三次「同一机制」兑现**：知识即记忆（D5）/ 审计归属组合不修改（D4）/ 审批通道装配级生命周期（M15.4 架构发现）
- **递进叙事收官**：Stage 15 让 Agent 能进企业——每个请求有主人，每个租户有边界，每次回答有出处，每分钱有归属。三类场景同 Runtime 的第一个 Profile 落地，Stage 16（Tavern Game）在同一底座上验证「零存量改动也能长出新领域」
