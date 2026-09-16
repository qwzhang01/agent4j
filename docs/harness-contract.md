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

- `WAITING`（审批等待）目前走 `PAUSED` 语义（`PauseException` + `ResumeToken`），无独立 `WAITING` 状态。Stage 3.4 持久化 Approval 引入 `WAITING_APPROVAL` 时再收敛。
- `CREATED` 前置态目前不存在：Run 创建即进入 RUNNING（内存态），持久化 RunStore 是 Stage 3.1 的活。
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
- **gap**：Run 元数据无持久化 RunStore（恢复依赖 checkpoint 文件目录扫描）。Stage 3.1。

### 2.3 Tool 边界

- `Tool` 接口 = name + description + parametersSchema + execute(JsonNode) -> String。schema 是模型可见契约，执行结果统一文本。
- **gap**：无结构化 `ToolDefinition`（sideEffectLevel、requiredCapabilities、timeout、maxInputBytes、maxOutputBytes）。无统一参数校验（INVALID_TOOL_ARGUMENTS 分类缺失）。无统一 `ToolResult` 封装。Stage 2.1-2.3。

### 2.4 Model 边界

- `ModelClient` 端口 + 装饰器族（Retry/Timeout/Fallback/StructuredOutput/Routing/Cascade）。
- **gap**：ModelClient 无 RunContext 视角（谁在调、什么预算）；provider 错误无统一分类（认证/限流/参数/服务端/网络/解析/取消）。Stage 6.1。

### 2.5 Memory 边界

- `MemoryScope`（agent/user/session/task/channel/tenant 六 kind）按关系隔离，store 面：`InMemoryMemoryStore` / `PgMemoryStore`。
- 读进 prompt 走 `MemorySource` / `MemoryContextBuilder`（分层注入：core 常驻头部 / archival 跟 query 坐尾部）。
- **gap**：读写无 tenant/identity/purpose 治理（读写不落审计），高敏感字段无脱敏。Stage 5.2/5.3。

### 2.6 Sandbox 边界

- 四层防御已完成（L1 spotlighting / L2 Stage 9 sanitizer / L3 pre-request sanitization / L4 scoped identity）。
- `SandboxTier` 五档（CLASS_LOADER/PROCESS/DOCKER/MICROVM/WASM），后三档是占位，SandboxReport 对占位档诚实报告零保证。
- `SandboxPolicy`（risk -> tier 映射）+ 升级预算（per-runId）。
- **gap**：ProcessSandbox 边界硬化（路径 canonicalize、环境变量 allowlist、子进程树清理）未做。Stage 4.1。

---

## 3. 验收矩阵（Stage 0.3）

### 3.1 P0 能力 x happy/failure/restart 验收路径

| P0 能力 | 责任模块 | happy path | failure path | restart path | 现状 |
|---------|---------|-----------|--------------|--------------|------|
| RunContext 统一运行上下文 | agent-core | 跨线程/并行节点/异步回调同 runId | 伪造 tenant 字符串被拒 | — | [x] done（Stage 1，2026-09-16：record 不可变 + deriveChild + 六边界 ctx 重载，RunContextTest/ContextAwareLoopTest/ParallelCancelTest 覆盖） |
| 取消 & Deadline | agent-core | 所有子分支收到取消 | Deadline 到期统一 TIMEOUT | — | [x] done（Stage 1.4，2026-09-16：CancellationSource/CancellationToken + RunDeadlineException + CANCELLED 终态，ParallelCancelTest 验证全分支停止） |
| Tool Contract 结构化定义 | agent-core | schema 校验通过执行 | INVALID_TOOL_ARGUMENTS 拒绝 | — | [ ] planned（Stage 2.1） |
| Secure 默认装配 | agent-core / starter | SecureAgentBuilder 默认治理 | 裸 DefaultToolExecutor 副作用工具被拒/标记 Unsafe | — | [ ] 默认 unsafe（Stage 2.4） |
| Durable Checkpoint | agent-workflow | pause 点快照恢复 | 版本不匹配拒绝恢复 | kill-9 后恢复不重复副作用 | [-] kill-9 已真实验证，RunStore/幂等账本缺 |
| 持久化 Approval | agent-workflow | 重启后继续审批 | 重复审批幂等 | 重启扫描待审批 Run | [ ] planned（Stage 3.4） |
| Sandbox 边界硬化 | agent-sandbox | 合法代码跑通 | 路径穿越被挡 | 超时后子进程树清理 | [-] FailureKind/预算已有，Process 硬化缺 |
| Memory 治理 | agent-memory | 读写带 tenant/scope | 跨租户访问被拒 | — | [-] scope 隔离已有，审计/脱敏缺 |
| 统一失败分类 | agent-core | 各模块映射到统一枚举 | — | — | [-] FailureKind 十类已定义（Stage 1），各模块映射接线 Stage 2.2 |
| 统一生命周期事件 | agent-core | 事件含 runId/step/attempt | — | — | [x] done（Stage 1.3，2026-09-16：RunEvent sealed 族 8 事件 + SCHEMA_VERSION=1；发射接线延后到 Stage 5 遥测统一） |

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
| MCP/A2A 入站 | A2A 入站净化器（throwing -> rejected，任务先拒再跑） | HttpA2AServerProtocolTest |
| LLM 红队 | RedTeamHarness（Mock 攻击者） | demo 已跑通；真实 LLM 红队留给 Moonlit 黄金集 |
| 沙箱资源洪泛/逃逸矩阵 | — | [ ] Stage 4.4 |

### 3.5 真实基础设施测试清单

| 依赖 | 现状 | 计划 |
|------|------|------|
| PostgreSQL | `AGENT4J_PG_TEST=true` 21/21（PgMemoryStore 契约测试） | [-] 已有；checkpoint RunStore 复用同一模式 |
| 容器 Sandbox | 无 | [ ] Stage 4.3 Docker Adapter（Linux CI） |
| MCP | stdio 官方 filesystem server 已验 | [-] 第三方 server 互操作 Stage 6.2 |
| A2A | 自家两端回环（零 mock 真实 socket） | [-] 第三方对端 Stage 6.3 |
| OpenTelemetry | 无 SDK | [ ] Stage 7.1 Span Adapter |
| GPG/Central 发布 | 流程已定（RELEASING.md） | [x] 已有 |

---

## 4. 四态能力矩阵（Stage 0.1 第 4 项）

按模块 x P0 能力，四态标记见 0.1.3。

| 模块 | 能力 | 状态 | 依据 |
|------|------|------|------|
| agent-core | ReAct loop / streaming / maxSteps | [x] | ReActAgentLoop + 14 测试文件 |
| agent-core | Guardrail 双门（输入/输出） | [x] | GuardrailLoopTest 5 例 |
| agent-core | ContextWindowBudget 四本账 | [-] | 已实现 opt-in，未默认接线 |
| agent-core | Handoff 三件套 + 续跑身份 | [x] | HandoffLoopTest + core 92/92 |
| agent-core | RunContext / 统一事件 | [x] | Stage 1（2026-09-16）：run 包 10 文件 + RunContextTest 7 / RunEventTest 2 / ContextAwareLoopTest 5 / ParallelCancelTest 1；发射接线见 Stage 5 |
| agent-core | ToolDefinition / ToolResult / FailureTaxonomy | [ ] | Stage 2 |
| agent-model | 装饰器族 Retry/Timeout/Fallback/Structured/Routing/Cascade | [x] | E2/E3 实验验证 |
| agent-model | Provider 错误统一分类 | [ ] | Stage 6.1 |
| agent-workflow | 图运行时 + 7 节点 + Checkpoint | [x] | workflow 9 测试文件 + E8 |
| agent-workflow | kill-9 跨进程恢复 | [x] | KillNineCrashRecoveryTest 真 fork 子进程 |
| agent-workflow | RunStore / 幂等账本 / 持久化 Approval | [ ] | Stage 3 |
| agent-memory | Scope 隔离 / 生命周期 / 对账环 / 双时间轴 / 分层注入 | [x] | 15 测试文件 + 174/174 |
| agent-memory | PG 持久化 | [-] | 真库线 21/21，但裸 JDBC 无池 |
| agent-memory | tenant 审计 / 字段脱敏 | [ ] | Stage 5 |
| agent-security | Permission/Approval/Audit/Sanitizer/Guardrail 桥 | [x] | 9 测试文件 |
| agent-security | InjectionNormalizer + 三态 Judge 槽位 | [-] | Judge v2 语义槽空着，regex 墙为主 |
| agent-sandbox | ClassLoader/Process 双档 + FailureKind + 升级预算 | [x] | sandbox 9 测试文件 73/73 |
| agent-sandbox | DOCKER/MICROVM/WASM | [ ] | 占位，诚实报告零保证 |
| agent-mcp | MCP stdio 客户端 | [x] | 可连官方 filesystem server |
| agent-mcp | A2A HTTP 双向 + v2 SSE/推送/续跑 | [-] | 79/79，但无第三方对端验证 |
| agent-mcp | MCP HTTP/SSE Transport | [ ] | Stage 6.2 |
| agent-chat | 房间引擎（选人/拼上下文/流式） | [x] | 16 测试文件，Moonlit 166/166 消费验证 |
| agent-observability | 五指标 HealthPipeline | [x] | E7 19/19 |
| agent-observability | OTel Span / Micrometer | [ ] | Stage 7 |
| agent-spring-boot-starter | 自动配置 | [-] | Profile（secure/test/unsafe）Stage 8.2 |
| agent-plugin | SPI 加载/卸载/重载 | [x] | 无 JAR ClassLoader 隔离（v1 边界） |

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
