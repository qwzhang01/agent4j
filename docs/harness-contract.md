# Harness Contract：agent4j 标准 Harness 最小契约

> 版本：`0.1.4-SNAPSHOT`（开发版）｜状态：Draft（Stage 0.2 产出，2026-09-16）
> 定位：本文档是 [todo-standard-harness-roadmap.md](../notes/todo-standard-harness-roadmap.md) Stage 0.2 的交付物，把"标准 Harness"从目标态口号变成可验收的契约。**契约按代码现状如实记录**：已兑现的写 implemented，没兑现的写 gap，不把目标伪装成事实。
> 纪律：后续 Stage 1–9 每项能力落地时，必须回写本文档对应条目并更新状态标记，禁止只改 roadmap 不改契约。

---

## 0. 版本与范围冻结（Stage 0.1）

### 0.1.1 版本

- 本轮开发版本：`0.1.4-SNAPSHOT`（23 处 pom 已对齐：根 pom 2 处 + BOM + 20 模块 + examples）。
- Central 最新发布版：`0.1.3`（docs 中"Central 最新"引用保持不动，直到 0.1.4 发版时统一刷新）。
- 版本纪律：发布时去 SNAPSHOT、打 tag、CHANGELOG 落档；发版后回灌下一版本 SNAPSHOT（0.1.3 发版时欠的"回灌 0.1.4-SNAPSHOT"动作本轮已补上）。

### 0.1.2 范围边界

- 本轮只建设**通用 Runtime Harness**：RunContext、Tool Contract、Durable Execution、Sandbox、Memory 治理、Provider/协议生产化、Observability。
- 不进框架的内容（产品判断，属宿主/产品侧）：Moonlit 的情绪/关系/角色规则、企业业务流程、酒馆游戏规则、编码 Agent 的领域 Prompt。
- 单一职责模块（P0 能力唯一责任模块，见 §5）：每个能力只允许一个模块实现，其他模块复用不复制。

### 0.1.3 状态标记（四态）

| 标记 | 含义 | 判定标准 |
|------|------|---------|
| `[x]` implemented | 已实现且有测试 | 正常路径 + 失败路径测试都在，CI 全绿 |
| `[-]` partial | 有原型/部分实现 | 接口存在但缺生产边界（内存实现、单 JVM、实验实现） |
| `[!]` planned | 计划中 | roadmap 有条目，无代码 |
| `[ ]` n/a | 有意不做 | limitations.md 明确声明 |

---

## 1. Run 生命周期契约

### 1.1 现状

`RunState`（agent-workflow）：

```text
RUNNING -> SUCCEEDED   （到达 END）
RUNNING -> FAILED      （节点错误且无 onError 边）
RUNNING -> PAUSED      （节点抛 PauseException）
RUNNING -> CANCELLED   （调用方 cancel()）
PAUSED  -> RUNNING     （resume）
```

`AgentState.Status`（agent-core，单 Agent 无 workflow 时）：

```text
IDLE -> RUNNING -> EXECUTING_TOOL -> DONE / MAX_STEPS_EXCEEDED / ERROR
```

### 1.2 契约声明（与 roadmap 草案的差异）

Roadmap Stage 0.2 草案写的生命周期是 `CREATED -> RUNNING -> WAITING -> SUCCEEDED / FAILED / CANCELED`。**本契约如实记录 gap**：

- `WAITING`（审批等待）：Stage 3.4 已引入 `WAITING_APPROVAL` 独立态（持久化审批协议消费），但 GraphRuntime 暂停路径仍统一落 `PAUSED`——WAITING_APPROVAL 目前由 RunStore 行状态与恢复候选消费，主循环内联接线是 Stage 8 gap。
- `CREATED` 前置态目前不存在：Run 创建即进入 RUNNING。Stage 3.1 RunStore 已落地（行先建再执行，崩溃首节点也留恢复候选），但内存 Run 对象仍无 CREATED 前置态。
- 取消语义各自成立（workflow CANCELLED / agent 侧 CANCELED），但跨层事件模型未统一——Stage 1.3 统一生命周期事件时对齐。
- **契约承诺**：Stage 1 RunContext 落地前，`RunState` 五态机保持稳定，不新增第六态；WorkflowState 黑板读写保持现有语义。

### 1.3 失败分类（现状 -> 目标）

**现状**：只有沙箱有结构化失败分类 `SandboxResult.FailureKind`（SANDBOX_FAILURE / BLOCKED_BY_POLICY / TIMEOUT / CODE_FAILURE）。其他边界全是自由字符串：`Tool not found: ...`（DefaultToolExecutor）、`ExecutionResult.errorMessage` 自由文本。

**契约目标（Stage 2.2 落地）**：统一 `FailureTaxonomy`，最少覆盖 roadmap 0.2 列的十类：输入失败 / 权限拒绝 / 审批等待 / 模型失败 / Tool 失败 / 超时 / 取消 / 资源耗尽 / 协议失败 / 恢复失败。落点在 agent-core（新枚举），各模块映射到统一分类，禁止再发明字符串前缀。

### 1.4 默认安全行为（现状 -> 目标）

**现状（诚实记录）**：

- 未知 Tool：`DefaultToolExecutor` **不拒绝**，返回 `"Tool not found: " + name` 字符串给模型。`GovernedToolExecutor` 可配置权限，未配置时未知 Tool 走默认 executor 行为。
- 有副作用 Tool：治理不是默认——需要业务方显式装配 GovernedToolExecutor + Permission + Approval + Sanitizer。
- 预算未配置：无默认行为（`ContextWindowBudget` 是 opt-in 装饰器，未挂载就不生效）。
- Sandbox 未配置：无默认行为（不挂 sandbox 就直接跑，无兜底隔离）。

**契约目标（Stage 2.4 SecureAgentBuilder 落地）**：安全装配成为默认路径——未知 Tool 默认拒绝、副作用 Tool 默认治理、无治理装配必须显式 `UnsafeAgentBuilder`。在落地前，**当前默认是 unsafe**，limitations.md 已如实声明。

### 1.5 可恢复的含义（现状 -> 目标）

**现状**：Checkpoint 只在 pause 点落盘（PAUSED 状态时 persist）。E8 实验已证明：恢复粒度是最后 pause 点，pause 点之后的节点会重放；三保护（cursor protection、isResuming guard、幂等键）覆盖间隙。

**契约承诺**：Stage 3 前不改变"pause 点快照 + 三保护"的恢复语义。副作用幂等键公式 `runId:nodeId:visitOrdinal` 是契约级不变量（E8 已证 attempt 不可用、visitOrdinal 跨崩溃稳定）。

### 1.6 最小 NFR（契约目标，Stage 7 验收）

| 维度 | 契约承诺 | 现状 |
|------|---------|------|
| 最大延迟 | P95 可配置，超时统一 TIMEOUT 分类 | Timeout 装饰器已有，分类未统一 |
| 最大 Tool 次数 | Run 级 maxToolCalls 可配置 | maxSteps 已有，Tool 次数未单列 |
| 最大上下文 | `ContextWindowBudget` 四本账 | [-] opt-in 装饰器，未默认接线 |
| 最大输出 | outputHeadroom 预留 | [-] 同上 |
| 最大成本 | Run 级预算 | [-] TokenBudget 在 scheduler 侧，Model 边界未自动接入 |
| 最大并发 | — | [ ] Stage 8 |

---

## 2. 六边界契约（Agent / Workflow / Tool / Model / Memory / Sandbox）

### 2.1 Agent 边界

- `Agent.run/stream` 是执行入口，输入输出通过 `AgentState` 黑板，事件通过 `AgentEvent`（ContentDelta/ToolStarted/ToolFinished/Done/Error/TurnTrace/Handoff/RetryStarted）。
- **gap**：Run 目前不持有 `RunContext`（身份、租户、预算、幂等范围通过参数各自传递）。Stage 1.1/1.2 落地前，agent4j 不具备"不可伪造运行上下文"承诺。
- 需要治理的工具必须走 `GovernedToolExecutor`，但装配治理是业务方责任（Stage 2.4 改为默认）。

### 2.2 Workflow 边界

- Workflow = 图运行时（7 种节点），黑板 `WorkflowState`，Checkpoint 于 pause 点，`RunState` 五态机。
- Workflow 不嵌套 Agent loop 语义：需要 Agent 的节点显式挂 AgentNode（或宿主包装），两者通过黑板交换数据。
- **gap（2026-09-17 batch 1 清偿后残余）**：账本未接入 GraphRuntime 节点执行链——真实节点执行不查账本，恢复重放保护依赖既有三保护。已清偿：RunStore 的数据库真相源（`JdbcRunStore` + 乐观锁行 + 恢复候选扫描，跨实例接管经 `DistributedRunControl` + 共享 `JdbcCheckpointStore` 达成 Stage 8 完成定义第一条——两个 Runtime 实例可安全接管同一等待中的 Run，`DistributedRunControlTest` 8/8 验证）；StepRecord visitOrdinal/startedAt/endedAt 已落（`WorkflowState.record` 统一分配 1-based 序号、恢复 trace 保留原序号、执行窗口由 GraphRuntime/ParallelNode 记录，`StepRecordVisitMetadataTest` 3/3）；backpressure/队列容量/租户隔离已落（`JdbcTaskQueue` 容量守卫 + `QueueFullException` 同 `[QUEUE_FULL]` 信号族 + `tenant_id` 列 + `listByTenant`，`RunRecord` 尾插 `tenantId`/`versions` 身份列 create 写入 transition 保留，`JdbcTaskQueueBackpressureTest`/`JdbcRunStoreTenantVersionsTest` 4+4）。

### 2.3 Tool 边界

- `Tool` 接口 = name + description + parametersSchema + execute(JsonNode) -> String。schema 是模型可见契约，执行结果统一文本。
- **gap**：无结构化 `ToolDefinition`（sideEffectLevel、requiredCapabilities、timeout、maxInputBytes、maxOutputBytes）。无统一参数校验（INVALID_TOOL_ARGUMENTS 分类缺失）。无统一 `ToolResult` 封装。Stage 2.1-2.3。

### 2.4 Model 边界

- `ModelClient` 端口 + 装饰器族（Retry/Timeout/Fallback/StructuredOutput/Routing/Cascade）。
- Provider 错误统一分类已落地（Stage 6.1，2026-09-16）：`ProviderCallException` 九类 taxonomy（AUTH_ERROR/RATE_LIMITED/INVALID_REQUEST/MODEL_ERROR/NETWORK_ERROR/PARSE_ERROR/CANCELED/TIMEOUT/UNKNOWN），`fromLegacy`/`toLegacy` 双向兼容；`ModelClientContract` 契约测试基类每 Provider 8 契约（同步/Tool Call/流式/流式 Tool Call 累积 + 4 错误映射，双 Provider 16/16）；`ResilientModelClient` 装饰器三合一（Retry-After 退避 + 熔断器 + CredentialRotation SPI，caller 侧错误不进熔断）；`ProviderCapabilities` 按 Flavor 声明厂商差异。**修复真 bug**：Anthropic 流式 tool use 从不累积（content_block/delta 解析了但 toolCalls 被丢弃）。
- **gap 清偿（Stage 7.2，2026-09-16）**：预算自动接线已落地——`BudgetedModelClient`（五维 RUN/USER/TENANT/CHANNEL/AGENT 从 RunContext 派生，pre-flight requireBudget chars/4 估算 fail-closed，DENIED 抛 BudgetExhaustedException 非_ModelException_，post-hoc recordUsage 实际 totalTokens；stream 在 Done/Error 终态记一次，Error 记估算作为诚实下限）+ `BudgetedToolExecutor`（RUN 维 1 call=1 unit 断路器，DENIED 走 `[DENIED] ` 文本前缀模型可转述）。**残余 gap**：无 ctx 的 legacy 单参调用不过闸（无身份不发明租户）；PROVIDER 维未单列枚举。

### 2.5 Memory 边界

- `MemoryScope`（agent/user/session/task/channel/tenant 六 kind）按关系隔离，store 面：`InMemoryMemoryStore` / `PgMemoryStore`。
- 读进 prompt 走 `MemorySource` / `MemoryContextBuilder`（分层注入：core 常驻头部 / archival 跟 query 坐尾部）。
- Memory 治理门面（Stage 5.2，2026-09-16）：`MemoryGovernance`——RunContext 派生 scope 白名单（tool 不可伪造）+ purpose 强制 + `MemoryAccessAuditRecord` 读写审计 + `RedactionPolicy` 内容脱敏（默认 rawPlusMasked：账本留原文、消费者拿脱敏副本）+ `purgeForUser/purgeForTenant` 删除传播（`DeletionPropagation` 记录被扫 scope 与 entry id 清单，可验证）。`MemoryAdmin.updateContent/setTtl` 字段保真已修复（16 参完整构造器，lifecycle/embedding/双时间轴不再丢失，两测试锚定）。
- **gap**：MemoryProvenance 无独立模型版本字段（经 actor 字符串承载）；删除传播仅覆盖 Memory store，Trace/Trajectory 导出物的删除传播待 Stage 8；retention 匿名化（删除改匿名）未做，现仅 hard delete + TTL。

### 2.6 Sandbox 边界

- 四层防御已完成（L1 spotlighting / L2 Stage 9 sanitizer / L3 pre-request sanitization / L4 scoped identity）。
- `SandboxTier` 五档（CLASS_LOADER/PROCESS/DOCKER/MICROVM/WASM），后三档是占位，SandboxReport 对占位档诚实报告零保证。
- `SandboxPolicy`（risk -> tier 映射）+ 升级预算（per-runId）+ `TierLimits`（tier x risk -> 十字段限制表，Stage 4.2）。
- PROCESS 层边界已硬化（Stage 4.1，2026-09-16）：className 白名单、canonicalize containment、env allowlist（默认不含 HOME）、每流 1MB 输出截断、超时进程树击杀、guard 源码注入（guest 内 SecurityManager 双阶段安装，host 类空间永不加载 guard）。
- **gap**：OS 级隔离（DOCKER adapter、UID/GID、seccomp、cgroup、网络 namespace）未做，macOS 本地开发无 Docker daemon 是 v1 既定边界；ADVERSARIAL `requiresApproval` 已入 TierLimits 表但审批接线留 Stage 8。

### 2.7 Plugin 边界

- 安全边界诚实声明（Stage 6.4，2026-09-16，`Plugin` javadoc）：SPI 插件进程内运行、持全 JVM 权限，框架隔离的是**注册面**（per-plugin 工具命名空间、manifest 门控、失败回滚），不是代码执行沙箱；外部 JAR 的 classloader 隔离是依赖卫生（插件依赖不可遮蔽框架类），不是禁闭——真正的禁闭需 module layer 或进程边界（后续 Stage）。
- `PluginManifest`（宿主侧 declare-to-grant）：tools/model/memory/security 四权限 + sha256 + source，由宿主声明、**绝不从插件工件自身解析**（恶意 jar 会撒谎）；缺省即拒绝。
- `PluginJarLoader`：SHA-256 checksum 门在 classloader 构建之前（篡改 jar 零类加载）；per-jar URLClassLoader framework-first；SPI 注册文件域隔离（loader 的 getResources 只见 jar 自己的 META-INF/services，宿主 classpath 插件不泄漏进 provider 列表）；manifest 名与 descriptor 名一致性校验。
- `PluginRegistry`（Stage 6.4 重写）：per-plugin 锁 + ConcurrentHashMap（不同插件并行、同插件串行）；load 失败回滚（onLoad 抛出前注册的 tool 全清）；manifest tools=false 在注册边界拒绝；命名空间隔离（插件 unregister 只能删自己注册过的名字，敌意插件删不掉宿主工具）；unload 后旧 tool 不可调用（测试锚定）。
- **gap**：model/memory/security 权限已声明但无可执行注册面（对应 registry 面不存在，属 Stage 8+）；插件自有线程/连接框架无法回收（javadoc 声明）；checksum 是摘要不是 GPG 签名。

---

## 3. 验收矩阵（Stage 0.3）

### 3.1 P0 能力 x happy/failure/restart 验收路径

| P0 能力 | 责任模块 | happy path | failure path | restart path | 现状 |
|---------|---------|-----------|--------------|--------------|------|
| RunContext 统一运行上下文 | agent-core | 跨线程/并行节点/异步回调同 runId | 伪造 tenant 字符串被拒 | — | [x] done（Stage 1，2026-09-16：record 不可变 + deriveChild + 六边界 ctx 重载，RunContextTest/ContextAwareLoopTest/ParallelCancelTest 覆盖） |
| 取消 & Deadline | agent-core | 所有子分支收到取消 | Deadline 到期统一 TIMEOUT | — | [x] done（Stage 1.4，2026-09-16：CancellationSource/CancellationToken + RunDeadlineException + CANCELLED 终态，ParallelCancelTest 验证全分支停止） |
| Tool Contract 结构化定义 | agent-core | schema 校验通过执行 | INVALID_TOOL_ARGUMENTS 拒绝 | — | [x] done（Stage 2.1/2.2，2026-09-16：contract 包 5 文件 + ContractAwareToolExecutor 统一校验链，ToolContractTest 12 覆盖） |
| Secure 默认装配 | agent-core / starter | SecureAgentBuilder 默认治理 | 裸 DefaultToolExecutor 副作用工具被拒/标记 Unsafe | — | [x] done（Stage 2.4，2026-09-16：SecureAgentBuilder 默认治理栈 + UnsafeAgentBuilder 显式危险路径 + [RuntimeProfile] 日志；审批的非阻塞 WAITING 语义留 Stage 3） |
| Durable Checkpoint | agent-workflow | pause 点快照恢复 | 版本不匹配拒绝恢复 | kill-9 后恢复不重复副作用 | [x] done（Stage 3.1/3.2/3.3，2026-09-16：RunStore 乐观锁 + Checkpoint schemaVersion=2 + 定义指纹 mismatch 拒绝 + SideEffectLedger 命中重放 + RunLease 单赢家 + RecoverySnapshot 诊断；kill-9 用同 store 新实例模拟，DurableExecutionTest 14 覆盖） |
| 持久化 Approval | agent-workflow | 重启后继续审批 | 重复审批幂等 | 重启扫描待审批 Run | [x] done（Stage 3.4，2026-09-16：approval 包五态协议 + PersistentApprovalService + WAITING_APPROVAL 状态 + 重启扫描恢复候选；agent-security Tool 侧接线留 Stage 4/8） |
| Sandbox 边界硬化 | agent-sandbox | 合法代码跑通 | 路径穿越被挡 | 超时后子进程树清理 | [x] done（Stage 4.1/4.2/4.4，2026-09-16：className 白名单 + containment + env allowlist + CappedBuffer + 进程树击杀 + guard 注入 + TierLimits 限制表；红队 11 例全绿，八条攻击全 BLOCKED） |
| Memory 治理 | agent-memory | 读写带 tenant/scope | 跨租户访问被拒 | — | [x] done（Stage 5.2，2026-09-16：MemoryGovernance 门面——RunContext 派生 scope 白名单不可伪造 + purpose 强制 + MemoryAccessAuditRecord 审计 + RedactionPolicy 默认 rawPlusMasked 脱敏 + purgeForUser/Tenant 删除传播 DeletionPropagation 可验证；MemoryAdmin 字段保真修复，updateContent/setTtl 不再丢 lifecycle/embedding/双时间轴，两测试锚定） |
| 统一失败分类 | agent-core | 各模块映射到统一枚举 | — | — | [-] FailureKind 十类已定义（Stage 1），Tool 边界已映射 INPUT_INVALID/TOOL_FAILURE/TIMEOUT/CANCELLED（Stage 2.2，ContractAwareToolExecutor），Provider 边界已映射九类 taxonomy（Stage 6.1，ProviderCallException，fromLegacy/toLegacy 双向兼容）；Memory/Approval/Sandbox 侧映射 Stage 7 |
| 统一生命周期事件 | agent-core | 事件含 runId/step/attempt | — | — | [x] done（Stage 1.3，2026-09-16：RunEvent sealed 族 8 事件 + SCHEMA_VERSION=1；2026-09-17 batch 2 补齐四族边界事件：agent-core `BoundaryEvent` sealed 四族（Memory/Approval/Sandbox/Model）+ MemoryGovernance/SandboxEscalator/ObservingApprovalStore 三处发射接线 + `OpsEventDeduplicator` 消费侧结构键去重（5min 窗口 + 10k LRU fail-open），27 新测试全绿；旁路遥测红线：throwing sink 吞掉计数、结构属性红线无内容） |
| Provider Contract | agent-model | 契约测试双 Provider 全绿 | 4 类错误映射到九类 taxonomy | — | [x] done（Stage 6.1，2026-09-16：ModelClientContract 基类 8 契约/Provider 16/16，含流式 tool call 累积；Anthropic 流式 tool use 真 bug 修复——content_block 链解析但 toolCalls 从不累积；ResilientModelClient 三合一 + ProviderCapabilities；Key 卫生 16 处日志核查干净） |
| MCP 生产边界 | agent-mcp | SSE transport 连通 | 未信任 server 拒绝 | — | [x] done（Stage 6.2，2026-09-16：SseTransport 2024-11-05 方言 5/5 含 JDK HttpServer 第三方言互操作；McpServerTrust/McpAllowlist 三级缺席即拒 7/7；McpAuthConfig 宿主认证适配器；McpSchemaValidator 接入 McpToolAdapter 8/8；gap：Streamable-HTTP、完整 OAuth 流、resources/prompts 能力声明。batch 3 更新 2026-09-17：能力协商已落——McpServerCapabilities 类型化解析 + supportsTools 双向守卫 + 客户端能力对象诚实留空（9 测试）；CancellationToken 经 McpToolAdapter ctx 路径接线到 wire（8+5 测试），取消为协作式轮询检查 + IOException 出口改写，RunCancelledException 结构化信号绝不包装；gap：MCP 协议级 cancellation notification 未实现、resources/prompts 已解析未消费） |
| A2A 认证与持久化 | agent-mcp | bearer 200 / 签名 push 可验证 | 错 bearer 401 / 篡改签名拒 | 共享 store 跨重启任务存活 | [x] done（Stage 6.3，2026-09-16：A2ATaskStore + StoredA2ATask + InMemoryA2ATaskStore（renewLease 过期租约真 bug 修复）+ A2ASecurity（恒时 bearer + HMAC-SHA256 + 时间窗 + 重放缓存）+ HttpA2AServer 7 参构造器接线；测试 21/21，agent-mcp 120/120；batch 4 更新 2026-09-17：卡身份密码学落地——`A2ACardIdentity`（Ed25519/RSA-2048，算法从 key 族派生；SunEC 报 `EdDSA` 家族名的映射 bug 由新测试抓住修复）+ `X-Agent-Card-Signature` 响应头覆盖 card body 原始字节（body 保持 spec 纯净）+ `HttpA2AServer` 9 参构造器收 KeyPair + `HttpA2AClient` `Set<PublicKey>` 信任库 fail-closed 验签（无签名/畸形头/未知 keyId/验签失败四路全拒；空信任库保持 D7 legacy），wire 测试摘 a2a-it tag 进本地默认套件（纯 loopback），18 新测试、agent-mcp 144/144；gap：key 分发无 PKI/目录、无吊销，跨进程第三方互操作仍为回环+模拟方言） |
| Plugin 生产边界 | agent-plugin | manifest 授权后加载注册 | 未授权/篡改/错名拒绝 | — | [x] done（Stage 6.4，2026-09-16：PluginManifest 宿主侧 declare-to-grant + PluginJarLoader（checksum 门先于 classloader + per-jar classloader + SPI 域隔离）+ PluginRegistry（并发 per-plugin 锁 + 失败回滚 + 命名空间隔离）；PluginJarLoaderTest 5（真实 javac+jar）+ PluginRegistryHardeningTest 8；agent-plugin 42/42；gap：仅 tools 权限有注册面，checksum 非 GPG） |
| 分布式执行（跨实例接管） | agent-workflow / agent-scheduler | 实例 B 从共享 DB 接管 A 留下的 Run 并完成 | 终态行拒绝盲目复活；分区期间所有 store 操作 fail-loud；lease 竞争单赢家 | worker 崩溃后行可恢复，B 取 lease 完成 | [x] done（Stage 8.1，2026-09-16：JDBC 持久化脊柱 7 后端——JdbcRunStore/JdbcRunLeases/JdbcSideEffectLedger/JdbcCheckpointStore/JdbcApprovalStore/JdbcTaskQueue，纯 ANSI SQL 受控行数即 CAS；DistributedRunControl 行即控制通道——跨实例 cancel CAS 行、心跳行监视一个轮询周期停机、迟到决策不作绿灯、终态行 resume 大声拒绝；DurableRunManager 心跳升级续租+行监视、TTL 可配置、cancel 门面；RunLeases 接口化；PartitionToleranceTest H2 SHUTDOWN 分区演练；测试 35+3+9+8+4；2026-09-17 batch 1 补齐：JdbcTaskQueue 容量守卫（`[QUEUE_FULL]` 拒绝 + totalRejected/activeCount 可观测）+ tenant_id 列 + listByTenant 租户视图，RunRecord tenantId/versions 身份列；gap：容量守卫为 enqueue 时点检查非并发安全配额、PostgreSQL profile 待 8.3 CI；batch 5 更新 2026-09-17：任务级 lease 补齐当日记录的两个真缺——① 独立租约钟：DDL 加 `heartbeat_at` 列（照 batch 1 tenant_id 先例），claim 打点、`heartbeat(taskId)` 原语（guard RUNNING，非 RUNNING 返 false=所有权判决）+ `heartbeatAt` 查询，`requeueOrphaned` 判活改 `COALESCE(heartbeat_at, started_at)`（不续约按 claim 时间判活=8.1 行为逐字节一致，续约者长任务不遭误收），requeue 重置 heartbeat_at；② 周期清扫：`TaskLeaseSweeper` 周期调 requeueOrphaned（首扫立即、存储错误计数重试不杀清扫器）；`ClaimedTaskHeartbeat` 持有者自动续约（存储故障 fail-open、所有权 fail-closed）。TaskLeaseHeartbeatTest 14/14、agent-scheduler 74/74、全仓 23 模块零回归；gap：每持有者一个心跳 daemon 线程、无优先级抢占、无时钟偏移补偿） |


### 3.2 模块依赖与禁止依赖矩阵

实际依赖（pom 实证，2026-09-16）：

```text
agent-core          -> （无兄弟依赖）
agent-model         -> core
agent-plugin        -> core
agent-sandbox       -> core
agent-workflow      -> core, model
agent-scheduler     -> core, model, workflow
agent-memory        -> core, model
agent-security      -> core, model
agent-mcp           -> core, model, security
agent-orchestrator  -> core, model, mcp
agent-channel       -> core, model, memory, scheduler, security
agent-product       -> core, model, security, channel, workflow
agent-trace-export  -> core, model, workflow
agent-enterprise    -> core, model, memory, security, workflow
agent-tavern        -> core, model, memory, security
agent-chat          -> core, model, memory
agent-coding        -> core, model, sandbox, security
agent-observability -> core, model, trace-export
agent-spring-boot-starter -> core, model
```

禁止依赖规则（契约，违反即架构债）：

- `agent-core` 禁止依赖任何兄弟模块（零依赖纯接口层）。
- `agent-security` 禁止依赖 sandbox/workflow/memory（治理层不耦合执行层）。
- `agent-chat` 禁止依赖 security/sandbox（chat 是引擎不是治理）。
- 任何模块禁止依赖 `agent-spring-boot-starter`（starter 是终端装配层）。
- 产品 Profile（tavern/enterprise/coding）禁止被其他库模块依赖。
- examples 禁止被任何模块依赖（且不发布）。

### 3.3 API 兼容性检查清单

- 既有构造器兼容策略（0.1.2-0.1.3 惯例）：新能力用装饰器/新构造器 opt-in，旧构造器保持编译通过且行为不变（A2ATask 多参构造器、MemoryEntry 四构造器、AgentConfig 8 参构造器）。
- 兼容性验证手段：全仓 22 模块 `./mvnw -B verify` 全绿是发布门槛；Moonlit 后端（真实消费方）166 测试是第二验证面。
- Stage 1-3 的 RunContext/ToolResult 改造**必须**走同一策略：旧 API 冻结、新 API opt-in，禁止破坏存量调用（A2A v1 时验证过 170 处规模）。

### 3.4 安全测试清单

| 攻击面 | 已有防线 | 已有测试 |
|--------|---------|---------|
| 路径穿越（沙箱） | ClassLoaderSandbox 阻断 File/ProcessBuilder | SandboxEscapeTest（11 例） |
| 间接注入（工具输出/记忆） | L1-L4 四层 + InjectionNormalizer | InjectionObfuscationDefenseTest（10）+ SanitizingContextBuilderTest（10） |
| 工具越权 | GovernedToolExecutor + Permission | agent-security 测试 9 文件 |
| 重复副作用 | 幂等键 visitOrdinal + cursor protection | E8（7 断言）+ KillNineCrashRecoveryTest |
| 敏感数据泄露 | SanitizerGuardrail 出口净化 | SanitizerGuardrailTest（3）+ RedTeamHarness |
| MCP/A2A 入站 | A2A 入站净化器（throwing -> rejected，任务先拒再跑）；A2A bearer 门（恒时比对）+ HMAC-SHA256 签名 push + 重放窗（Stage 6.3）；MCP server 信任三级缺席即拒（Stage 6.2） | HttpA2AServerProtocolTest + HttpA2AServerSecurityTest（4：401/200、公开卡、签名可验证、重启存活）+ A2ASecurityTest（8）+ McpServerTrust/McpAllowlist 7/7 |
| LLM 红队 | RedTeamHarness（Mock 攻击者） | demo 已跑通；真实 LLM 红队留给 Moonlit 黄金集 |
| 沙箱资源洪泛/逃逸矩阵 | guest guard（FilePermission/SocketPermission 白名单 + 输出截断 + 进程树击杀） | [x] ProcessSandboxRedTeamTest（11）：八条攻击全 BLOCKED + UNRESTRICTED 诚实 NOT-BLOCKED 记录；DOCKER 层留 Stage 4.3 |

### 3.5 真实基础设施测试清单

| 依赖 | 现状 | 计划 |
|------|------|------|
| PostgreSQL | `postgres` tag IT（`PostgresIT`，`AGENT4J_IT_PG_*` 环境变量探测）+ CI PG16 service（8.3） | [-] 已有；外部队列互操作不在 v1 |
| 容器 Sandbox | PROCESS 层 guard 已硬化（红队 11 例）；`DockerDaemonProbeIT`（8.3）：daemon 探测可审计 + DOCKER placeholder loud-fail 契约固化（daemon 在场仍宣称零保证） | [ ] Docker Adapter（Linux CI，Stage 4.3 诚实 gap） |
| MCP | stdio 官方 filesystem server 已验；SSE 方言 + JDK HttpServer 模拟对端 5/5；`mcp-it` tag（8.3）：真实 Python 子进程 server 全链路 `McpStdioIT` 3/3（握手/发现/调用/崩溃自愈重启重试/协议错误不误重启） | [-] 已有；官方参考实现互操作矩阵待补 |
| A2A | 自家两端回环（零 mock 真实 socket）+ JDK HttpServer 模拟方言；`a2a-it` tag（8.3）：RoundTrip 13 + Protocol 9 + Security 4 = 26 例挂 CI 矩阵 | [-] 已有；外部第三方 A2A 实现互操作待补 |
| SBOM / License | CycloneDX 聚合 SBOM（每模块 `target/bom.json`）+ `THIRD-PARTY.txt` 随构建生成（8.3） | [ ] grype 漏洞扫描接 CI（`vulnerability-scan` job，anchore/scan-action） |
| API 兼容 | japicmp `-Papi-compat` 对 0.1.3 基线断言（8.3 闭环：Checkpoint 兼容构造器 + starter shim + 无基线模块显式 skip + 0.1.3 字节码真机链接验证） | [-] 已有；CI `api-compat` job |
| OpenTelemetry | `agent-otel-export` 薄壳模块（Stage 7.1，2026-09-16）：RunEvent→span 四层（run/step/model/tool）+ 真实 SDK InMemorySpanExporter 测试 7/7；SDK 仅 test scope，核心零新依赖（D9）；2026-09-17 batch 2 补 `OtelBoundaryEventSpanAdapter`：BoundaryEvent 四族→span（agent.memory/agent.sandbox 含 escalate/refuse 变体/agent.approval/agent.model.serving），span 清单达十种，内容红线测试抓住 purpose/reason 自由文本违规并收紧 | [x] 已有；MCP 侧能力协商边界发射器随 batch 3（2026-09-17）具备协商事实，但 MCP/A2A span 仍待 Batch 4 A2A 可信来源批次的边界发射器（不在适配器伪造） |
| GPG/Central 发布 | 流程已定（RELEASING.md） | [x] 已有 |

---

## 4. 四态能力矩阵（Stage 0.1 第 4 项）

按模块 x P0 能力，四态标记见 0.1.3。

| 模块 | 能力 | 状态 | 依据 |
|------|------|------|------|
| agent-core | ReAct loop / streaming / maxSteps | [x] | ReActAgentLoop + 14 测试文件 |
| agent-core | Guardrail 双门（输入/输出） | [x] | GuardrailLoopTest 5 例 |
| agent-core | ContextWindowBudget 四本账 | [x] | Stage 2 实现 + Stage 5.1（2026-09-16）TrimRecord 遥测（ContextTrimRecordTest 6）+ CostMeter cache-aware 计价（CostMeterTest 4 新增）；装配仍为 opt-in，默认接线随 Stage 8 Profile |
| agent-core | Handoff 三件套 + 续跑身份 | [x] | HandoffLoopTest + core 92/92 |
| agent-core | RunContext / 统一事件 | [x] | Stage 1（2026-09-16）：run 包 10 文件 + RunContextTest 7 / RunEventTest 2 / ContextAwareLoopTest 5 / ParallelCancelTest 1；发射接线见 Stage 5 |
| agent-core | ToolDefinition / ToolResult / FailureTaxonomy | [x] | Stage 2（2026-09-16）：contract 包 + 统一校验链 + ToolResult 信封；outputSchema 校验与 Model 侧失败映射留 Stage 5 |
| agent-core | 表达层：Reflection / Tool 并行 / Typed Events | [x] | Stage 9（2026-09-17）：ReflectiveAgent（maxCycles 有界 + verdict 协议词 + ReflectionStarted/Finished，ReflectiveAgentTest 8）+ ParallelToolExecutor（声明序 join + 宽度钳制，ParallelToolExecutorTest 4）+ ReActAgentLoop.withParallelTools（ParallelToolLoopTest 4）+ AgentEvent 五新事件（ModelCallStarted/Finished 接线四路径、ToolValidationRejected 接治理链 stage="validation"）；gap-closure（2026-09-17）：[DENIED]→approval、[RATE_LIMITED]→permission 事件接线（GovernanceRejectionEventTest 5），恢复事件仍留白（[DENIED] 三来源串不可区分统一归 approval stage） |
| agent-model | 装饰器族 Retry/Timeout/Fallback/Structured/Routing/Cascade | [x] | E2/E3 实验验证 |
| agent-model | Provider 错误统一分类 | [x] | Stage 6.1（2026-09-16）：ProviderCallException 九类 taxonomy + ModelClientContract 16/16 + ResilientModelClient（Retry-After/熔断/凭证轮换）+ ProviderCapabilities；Anthropic 流式 tool use 累积真 bug 修复 |
| agent-workflow | 图运行时 + 7 节点 + Checkpoint | [x] | workflow 9 测试文件 + E8 |
| agent-workflow | kill-9 跨进程恢复 | [x] | KillNineCrashRecoveryTest 真 fork 子进程 |
| agent-workflow | RunStore / 幂等账本 / 持久化 Approval | [x] | Stage 3（2026-09-16）：durable 包 + approval 包 + DurableRunManager/PersistentApprovalService + DurableExecutionTest 14 / SchedulerDurabilityTest 3；账本接 GraphRuntime 与 Tool 侧接线留 Stage 8 |
| agent-workflow | 计划层：Plan / Multi-Agent / Replay | [x] | Stage 9（2026-09-17）：plan 包四类（Plan DAG 校验含自环 + PlanExecutor 拓扑序线性链复用 GraphRuntime + MultiAgentPlanner deriveChild 传播/隔离/去重 + EventReplayer 只读重放不执行副作用），PlanTest 5 / PlanExecutorTest 4 / MultiAgentPlannerTest 6 / EventReplayerTest 13 / PlanResumeTest 3 共 31/31；gap-closure（2026-09-17）：计划级恢复做实（StepExecutor 扩 throws Exception + Plan.rebuildWith 版本演进 + RunManager DEFINITION_VERSION_MISMATCH 守卫；恢复粒度 last pause 非 last node，E8 既有语义）+ 交互式 time-travel 以 replayPrefix 落地（PrefixReplay 前缀折叠：mid-run 世界保留部分历史、Done 窗内判定 doneWithinPrefix、破碎录制 anomaly 保留、越界 IndexOutOfBoundsException fail-loud）|
| agent-memory | Scope 隔离 / 生命周期 / 对账环 / 双时间轴 / 分层注入 | [x] | 15 测试文件 + 174/174 |
| agent-memory | PG 持久化 | [-] | 真库线 21/21，但裸 JDBC 无池 |
| agent-memory | tenant 审计 / 字段脱敏 | [x] | Stage 5.2（2026-09-16）：MemoryGovernance + MemoryAccessAuditRecord + RedactionPolicy + DeletionPropagation，MemoryGovernanceTest 11 例 + 字段保真 2 例 |
| agent-trace-export | v1 导出契约 + 导出面脱敏 | [x] | Stage 5.3（2026-09-16）：TrajectoryCodec 可配 SecretMasker（默认 null=旧行为逐字节不变），masked 构造器下 message/action/observation 三文本面全走 mask，TrajectoryCodecTest 新增 2 例锚定 |
| agent-security | Permission/Approval/Audit/Sanitizer/Guardrail 桥 | [x] | 9 测试文件 + Stage 2：SecureAgentBuilder/UnsafeAgentBuilder + 顺序契约（SecureAssemblyTest 6） |
| agent-security | InjectionNormalizer + 三态 Judge 槽位 | [-] | Judge v2 语义槽空着，regex 墙为主 |
| agent-sandbox | ClassLoader/Process 双档 + FailureKind + 升级预算 | [x] | Stage 4（2026-09-16）：12 测试文件 91/91（红队 11 + TierLimits 7 新增） |
| agent-sandbox | DOCKER/MICROVM/WASM | [ ] | 占位，诚实报告零保证；TierLimits 已落结构性限制表（STRUCTURAL_TIERS），实现待 Linux CI |
| agent-mcp | MCP stdio 客户端 | [x] | 可连官方 filesystem server |
| agent-mcp | A2A HTTP 双向 + v2 SSE/推送/续跑 | [x] | 120/120（Stage 6.3，2026-09-16：bearer 门 + HMAC-SHA256 签名 push + 重放窗 + A2ATaskStore 可插拔存储 + lease 跨实例语义 + 共享 store 重启存活；互操作为回环 + JDK HttpServer 模拟方言，外部第三方对端仍缺——记 [-] 于 §3.5） |
| agent-mcp | MCP HTTP/SSE Transport | [-] | Stage 6.2（2026-09-16）：SseTransport 2024-11-05 SSE 方言 + server 信任三级 + 宿主认证适配器 + schema 校验接入；Streamable-HTTP 方言未实现（诚实 gap） |
| agent-chat | 房间引擎（选人/拼上下文/流式） | [x] | 16 测试文件，Moonlit 166/166 消费验证 |
| agent-observability | 五指标 HealthPipeline | [x] | E7 19/19 |
| agent-observability | OTel Span / Prometheus 出口 | [x] | Stage 7（2026-09-16）：agent-otel-export span adapter（SDK 仅 test scope，D9）+ PrometheusTextSink 零依赖 0.0.4 + JsonlMetricsSink + PersistentRunRegistry + BudgetedModelClient/ToolExecutor 预算自动接线 + online 包（OnlineMetrics/OnlineSampler/VersionComparator/DriftDetector/UnifiedEvalReport）+ ops 包（OpsEventBus/OpsEventFactories/AnomalyLocalizer），模块 271/271；gap：Micrometer adapter、Memory/Sandbox/MCP/A2A span 未做 |
| agent-spring-boot-starter | 自动配置 + 生产 Profile | [x] | Stage 8.2（2026-09-16）：`agent4j.profile` 三档（secure 默认/test/unsafe）+ AgentFactory 三档装配（SECURE/TEST 走 SecureAgentBuilder，UNSAFE 裸装配点名）+ HighRiskConfigCheck 启动分级检查（SECURE+auto-approve=HIGH 阻断启动）+ AgentHealthIndicator 六面（absent≠unhealthy）+ GracefulShutdownCoordinator（gate→有界 drain→straggler cancel）+ config-version；29/29；gap：budget/trace 自动装配 bean、非 model 五面 starter 侧指示器未做（8.3 候选） |
| agent-plugin | SPI 加载/卸载/重载 | [x] | Stage 6.4（2026-09-16）：PluginJarLoader（checksum 门 + per-jar classloader + SPI 域隔离）+ PluginManifest 宿主权限声明 + registry 并发/回滚/命名空间隔离，42/42（PluginJarLoaderTest 5 真实 javac+jar + HardeningTest 8）；多版本共存与 module layer 禁闭未做（v1 边界） |

> 四态矩阵的可追溯纪律：每个 `[-]` 或 `[ ]` 条目都必须能指到 roadmap 的具体 Stage 条目；每个 `[x]` 都必须能指到具体测试类。本表与 roadmap 互为索引：roadmap 管"什么时候做"，矩阵管"现在是什么"。

---

## 5. 责任模块唯一性

P0 能力唯一责任模块（禁止同一能力多模块实现）：

| P0 能力 | 唯一责任模块 | 说明 |
|---------|------------|------|
| RunContext | agent-core | 身份/租户/预算/幂等范围唯一持有者 |
| FailureTaxonomy | agent-core | 统一失败分类枚举，各模块只映射不自建 |
| ToolDefinition / ToolResult | agent-core | 结构化工具契约 |
| SecureAgentBuilder | agent-core（装配面在 starter） | 默认安全装配 |
| RunStore / Checkpoint 版本 / SideEffectLedger | agent-workflow | 持久化执行真相 |
| 持久化 Approval | agent-workflow | 与 Checkpoint 同生命周期 |
| Sandbox Policy / OS 级隔离 | agent-sandbox | 风险->隔离映射唯一实现 |
| Memory 治理（tenant/purpose/audit） | agent-memory | 读写审计唯一实现 |
| Provider Contract / 错误分类映射 | agent-model | 各 provider 客户端统一分类 |
| MCP/A2A 认证与互操作 | agent-mcp | 协议公民职责 |
| OTel/Micrometer Adapter | agent-observability | 观测唯一出口 |

---

## 6. 后续阶段纪律

- Stage 1-9 每项落地 -> 回写 3 验收矩阵和 4 能力矩阵 + CHANGELOG。
- 新增功能不能绕过本契约矩阵直接进主线。
- 每 Stage 收口时检查：契约文档状态标记与代码事实一致（发现"文档领先于代码"即文档债，24 小时内修）。
- roadmap 的每任务完成纪律（契约/测试/失败语义/Javadoc/CHANGELOG/兼容性说明/限制声明）继续有效。
