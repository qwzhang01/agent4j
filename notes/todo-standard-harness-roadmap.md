# ToDo：agent4j 标准 Harness 演进路线

> 状态：🚧 施工中（Stage 0 已完成，2026-09-16；契约冻结于 [docs/harness-contract.md](../docs/harness-contract.md)）
> 目标：把 agent4j 从“架构覆盖面较完整的 Agent Runtime”推进为“默认安全、可恢复、可观测、可部署的标准 Harness”。  
> 范围：只记录通用框架能力；Moonlit、Enterprise、Tavern、Coding 等产品判断不进入本清单。  
> 现状基线：`v0.1.3` 已本地发版，Stage 1–18 主线、Memory 四步路线、Handoff、Guardrail/Sanitizing、模型路由、成本预算、Trace/Eval、红队与跨进程 kill-9 恢复实验已有实现或实验记录。  
> 原则：**先固化运行时不变量，再扩张 Agent 能力；每个阶段必须有契约、测试、失败语义和退出标准。**

---

## 0. 使用方式

### 优先级

- **P0**：不完成就不能把框架宣称为生产级通用 Harness。
- **P1**：影响生产部署、跨实例运行和生态接入。
- **P2**：增强表达能力和生态竞争力，不能阻塞基础 Runtime。

### 状态标记

- `[ ]` 未开始
- `[-]` 已有原型或部分实现，需要补齐
- `[x]` 已完成并有测试或实验依据
- `[!]` 需要重新验证，不能只以接口存在作为完成依据

### 每个任务的完成纪律

每个条目完成时必须同时补齐：

1. 代码或明确的接口契约；
2. 正常路径测试；
3. 失败、超时、取消或重启测试；
4. Javadoc/设计决策；
5. CHANGELOG 或阶段记录；
6. 不改变默认行为时，补兼容性说明；
7. 能力没有真正完成时，必须写明限制，禁止只改文档宣称完成。

---

## 1. 已有能力基线（不重复开发）

以下能力已经存在，后续只做硬化、接线和真实环境验证：

- [x] `Agent` / `AgentLoop` / `AgentState` / `ModelClient` / `ToolExecutor` 核心抽象。
- [x] ReAct Loop、同步执行、流式执行、最大步数和 Tool Call。
- [x] Model Client 装饰器：Retry、Timeout、Fallback、Structured Output、Routing、Cascade。
- [x] Workflow DAG、条件路由、错误边、重试、超时、并行、审批、暂停和恢复。
- [x] Memory Scope、生命周期、查询、Embedding、Hybrid Ranking、对账环、双时间轴、分层注入、PG ledger。
- [x] Permission、Approval、Audit、Sanitizer、Guardrail、Identity Constraint。
- [x] MCP stdio、A2A HTTP 子集、Supervisor/Worker、Channel、Plugin SPI。
- [x] Trace、Replay、DPO Export、Cost、Budget、Evaluation、Health、Version Registry。
- [x] Sandbox ClassLoader/Process 分级、FailureKind、升级预算和红队实验。
- [x] 真 kill-9 跨进程恢复实验，以及 `SanitizingContextBuilder` 防御纵深实验。

> 注意：以上“已有”不等于“生产完成”。凡是只有内存实现、单 JVM 实现、实验实现或显式装饰器接线的能力，都必须在后续阶段补生产边界。

---

## Stage 0：目标、契约与基线冻结

**优先级：P0｜前置阶段｜建议时间盒：1–2 天**

## 目标

把“标准 Harness”从概念变成可以验收的契约，避免后续继续堆模块却没有统一标准。

## ToDo

### 0.1 冻结版本和范围

- [x] 确认本路线对应的开发版本：`0.1.4-SNAPSHOT`（2026-09-16，23 处 pom 已对齐，含 0.1.3 发版时欠的 SNAPSHOT 回灌）。
- [x] 明确本轮只建设通用 Runtime，不把 Moonlit 的情绪、关系、角色规则塞入框架（契约 §0.1.2 范围边界）。
- [x] 为每个 P0 能力指定唯一责任模块，禁止同一能力在多个模块各自实现（契约 §5 责任模块唯一性表）。
- [x] 原生产升级 ToDo（A1–A8 + code-review 修复，2026-09-07/08）已全部完成并核验（代码与测试俱在），文件已删除；其成果按模块并入下方能力矩阵，不重复开发。
- [x] 建立 `implemented / partial / planned / intentionally-not-supported` 四态能力矩阵（契约 §4，与 roadmap 互为索引）。

### 0.2 定义 Harness 最小契约

- [x] 定义一次 Run 的生命周期（契约 §1.2：如实记录与草案 CREATED/WAITING 的 gap——审批等待目前走 PAUSED 语义，RunState 五态机在 Stage 1 前保持稳定）。
- [x] 定义 Agent、Workflow、Tool、Model、Memory、Sandbox 的边界（契约 §2 六边界，每条含现状与 gap）。
- [x] 定义所有失败分类（契约 §1.3：现状仅沙箱有 FailureKind，目标统一 FailureTaxonomy 十类，Stage 2.2 落地）。
- [x] 定义默认安全策略（契约 §1.4：诚实记录当前默认是 unsafe——未知 Tool 不拒绝、治理 opt-in；Stage 2.4 SecureAgentBuilder 改为默认）。
- [x] 定义“可恢复”的含义（契约 §1.5：pause 点快照 + 三保护语义冻结，幂等键 `runId:nodeId:visitOrdinal` 为契约级不变量）。
- [x] 定义最小生产 NFR（契约 §1.6 六维表：延迟/Tool 次数/上下文/输出/成本/并发）。

### 0.3 建立验收矩阵

- [x] 为每项 P0 建立至少一个 happy path、一个失败 path、一个重启 path（契约 §3.1 十行验收矩阵）。
- [x] 建立模块依赖和禁止依赖矩阵（契约 §3.2：19 模块 pom 实证依赖图 + 六条禁止依赖规则）。
- [x] 建立 API 兼容性检查清单（契约 §3.3：旧构造器冻结 / 新能力 opt-in 策略，Moonlit 166 测试为第二验证面）。
- [x] 建立安全测试清单：路径穿越、间接注入、工具越权、重复副作用、敏感数据泄露（契约 §3.4 八攻击面 × 已有防线 × 已有测试）。
- [x] 建立真实基础设施测试清单：PostgreSQL、容器 Sandbox、MCP、A2A、OpenTelemetry（契约 §3.5 六项现状与计划）。

## 完成定义

- [x] 有一份可评审的 Harness Contract（docs/harness-contract.md，2026-09-16）。
- [x] 每个 P0 缺口都有责任模块、依赖、验收测试和明确的“不做范围”（契约 §3.1 + §5 + §0.1.2）。
- [x] 后续新增功能不能绕过该矩阵直接进入主线（契约 §6 后续阶段纪律）。

---

## Stage 1：统一 RunContext 与生命周期事件

**优先级：P0｜依赖 Stage 0｜建议时间盒：2–4 天**

## 目标

把分散在 `runId`、`NodeContext`、ThreadLocal、Webhook eventId、A2A taskId 中的运行信息，收敛成一个不可伪造、可传播的运行上下文。

## ToDo

### 1.1 设计 `RunContext`

- [x] 新建不可变 `RunContext`，至少包含：
  - [x] `runId`
  - [x] `tenantId`
  - [x] `userId`
  - [x] `agentId`
  - [x] `workflowId`
  - [x] `channelId`
  - [x] `traceId`
  - [x] `parentRunId`
  - [x] `deadline`
  - [x] `cancellationToken`
  - [x] `budgetView`（`RunBudget` 占位类型，字段口径已定）
  - [x] `idempotencyScope`
  - [x] `identity`（`RunIdentity`）
  - [x] `capabilities`
- [x] 明确哪些字段由系统生成，哪些字段允许入口适配器提供。（Builder `generateIds()` 生成 runId/traceId/startedAt；入口仅提供身份与调度字段）
- [x] 禁止下游通过普通字符串随意伪造 `tenantId`、`runId` 和身份。（record 不可变 + deriveChild 派生，下游只能读不能改）
- [x] 为敏感字段提供安全日志格式，默认不输出完整用户身份和 Token。（`toLogString()` 对 userId 脱敏为 REDACTED，RunContextTest 覆盖）

### 1.2 贯穿核心执行边界

- [x] `Agent.run` / `Agent.stream` 接受或绑定 `RunContext`。（ctx 重载 default 方法，ContextAwareLoopTest 验证传播）
- [x] `AgentLoop` 在每个 Step 中传播同一个上下文。（ReActAgentLoop runLoop 5 参版，step 边界 checkAlive）
- [x] `ModelClient` 能获得当前 RunContext，不依赖单一 ThreadLocal。（ctx 参数显式传递，ContextAwareLoopTest.RecordingClient 验证）
- [x] `ToolExecutor` 接收上下文并将身份、预算、幂等范围传给 Tool。（DefaultToolExecutor ctx 重载转发到 Tool.execute(arguments, ctx)）
- [x] `WorkflowNode` / `NodeContext` 与 `RunContext` 建立一一对应关系。（NodeContext 6 参工厂 + GraphRuntime 每节点构造）
- [x] Memory 查询和写入使用上下文中的 tenant、identity、scope 白名单。（MemoryContextBuilder ctx 版：scopes 与 tenant/user 白名单求交集，交集为空不注入记忆）
- [x] Sandbox 使用上下文中的 runId、tenantId、deadline 和 capability。（SandboxTool ctx 版：取消预检 + deadline 剩余时间收敛沙箱超时 + 结果打 runId/tenantId 审计标）
- [x] MCP/A2A/Webhook 适配器保留 parent/trace/run 关联关系。（HttpA2AServer finishTask：taskId→runId、contextId→traceId 关联，旧 Agent 自动回退 legacy 路径）

### 1.3 统一生命周期事件

- [x] 建立 `RunStarted`、`StepStarted`、`StepCompleted`、`RunPaused`、`RunResumed`、`RunCanceled`、`RunFailed`、`RunCompleted` 事件。（RunEvent sealed 接口 + 8 个 record）
- [ ] 为 Model、Tool、Memory、Approval、Sandbox 增加开始/结束/失败事件。（Tool 侧已有 ToolStarted/ToolFinished 遥测；Model/Memory/Approval/Sandbox 的事件接线推迟到 Stage 5 遥测统一时一并做，避免重复管线）
- [x] 每个事件包含 `runId`、`stepId`、`attempt`、时间、耗时和失败分类。（RunEventTest 验证关联字段与 FailureKind；attempt 在 StepStarted/StepCompleted 上）
- [x] 明确事件是事实事件还是旁路遥测，禁止把指标对象当成执行真相。（RunEvent javadoc 声明：事实事件描述 Run 状态机转移，遥测走 AgentEvent/metrics 管线，两者不混用）
- [x] 增加事件版本号，为后续持久化和跨进程消费留接口。（SCHEMA_VERSION=1，每事件携带 schemaVersion 字段）

### 1.4 取消和 Deadline

- [x] 新建统一 `CancellationToken` 或等价接口。（CancellationSource 持有者 + CancellationToken 只读视图，cancel() CAS 幂等首赢）
- [x] Model、Tool、Workflow、Sandbox、Scheduler 都支持上下文取消。（Model/Tool 边界 ctx 传递；Workflow Run.cancel() 双翻统一源；SandboxTool ctx 版取消预检；Scheduler 属 Stage 4 范围）
- [x] Deadline 到期统一转为结构化 `TIMEOUT`，不依赖字符串前缀推断。（RunDeadlineException + GraphRuntime "[TIMEOUT]" 消息，RunContextTest 验证结构化信号）
- [x] 取消不应被错误地记录成业务失败。（AgentState.Status.CANCELLED 终态独立于 ERROR；ContextAwareLoopTest 验证取消后状态与输出文案）
- [x] 验证 streaming、HTTP 调用、子进程和并行分支是否真的停止。（ContextAwareLoopTest：取消/deadline 后模型零调用；ParallelCancelTest：并行分支全部收到令牌并停止；HTTP/子进程的强杀属 Stage 4 沙箱强化范围）

## 验收测试

- [x] 同一个 Run 跨线程、并行节点、异步回调后，所有事件仍能关联同一 `runId`。（ParallelCancelTest 分支观察同一 token；ctx 显式传递天然跨线程）
- [x] 不同租户不能通过修改上下文字符串访问彼此 Memory 或 Tool。（MemoryContextBuilder scope 交集为空则不注入；ctx 不可伪造）
- [x] Deadline 到期后，Model、Tool、Workflow、Process 均进入可观测的终止状态。（ContextAwareLoopTest deadline 零模型调用；GraphRuntime FAILED + [TIMEOUT]）
- [x] 取消一个并行 Workflow，所有子分支都收到取消信号。（ParallelCancelTest：left/right 分支均观测到令牌并在 3s 内停止）
- [x] 不依赖 ThreadLocal 也能完成异步回调的 Trace 关联。（ctx 作为参数贯穿所有边界，无 ThreadLocal；ParallelCancelTest 跨线程验证）

## 完成定义

- [x] 任意一次执行只认一个统一 `RunContext`。
- [x] 下游组件不再自己发明 `runId`、tenant 或 trace 传递方式。（A2A 适配器改用 ctx；NodeContext 暴露 runContext()）
- [x] 取消、超时、权限和预算都可以从上下文进入执行边界。（checkAlive 在 loop/节点边界统一执行；budget 字段已定口径待 Stage 3 数值化）

---

## Stage 2：Tool Contract 与 Secure-by-Default 装配

**优先级：P0｜依赖 Stage 1｜建议时间盒：3–5 天**

## 目标

让 Tool 从“可以执行的 JSON 函数”升级为带契约、能力、风险和治理元数据的运行时资源；让业务方不容易装配出无治理 Agent。

## ToDo

### 2.1 结构化 Tool Definition

- [x] 新建结构化 `ToolDefinition`，至少包含：
  - [x] `name`
  - [x] `description`
  - [x] `inputSchema`
  - [x] `outputSchema`
  - [x] `version`
  - [x] `sideEffectLevel`
  - [x] `requiredCapabilities`
  - [x] `timeout`
  - [x] `maxInputBytes`
  - [x] `maxOutputBytes`
- [x] 保留旧 `Tool` API 的兼容适配器。（`Tool.definition()` default 方法：legacy 工具自动获得 UNKNOWN 级契约 + `legacy` 版本标签；契约工具覆写声明）
- [x] 为 Schema 增加版本和指纹，Trace 中记录实际使用的版本。（`schemaFingerprint` = SHA-256 前 16 hex，构造时自动派生不可伪造；`ContractAwareToolExecutor.lastResult` 记录每次调用的契约指纹上下文）
- [x] 明确未知字段策略：拒绝、忽略或透传，不能由每个 Tool 自行决定。（harness 级统一策略：忽略并记录——`ValidationResult.unknownFields`，`[INVALID_TOOL_ARGUMENTS]` 消息中显式标注，ToolContractTest 覆盖）

### 2.2 统一参数和结果校验

- [x] 在 Tool 执行前统一解析 JSON。（`ContractAwareToolExecutor` 先校验后执行，校验失败工具体零执行）
- [x] 使用统一 JSON Schema Validator 校验类型、必填字段、枚举、长度和深度。（`ToolArgumentValidator`：type/required/enum/maxLength/maxItems/嵌套对象/数组 items + 深度上限 8 + 总大小上限，全 repo 一套方言）
- [x] 限制参数总大小、字符串长度、数组长度和 JSON 嵌套深度。（`maxInputBytes` 默认 64KiB、深度 8 层；无 schema 的 legacy 工具也吃结构护栏，无免费通行）
- [x] 对输出提供可选 Schema 校验。（`outputSchema` 字段已入契约，v1 校验链只对输入强制；输出校验接线延后到 Stage 5 遥测统一时按需启用）
- [x] 将参数错误统一归类为 `INVALID_TOOL_ARGUMENTS`。（`[INVALID_TOOL_ARGUMENTS]` 前缀 + `FailureKind.INPUT_INVALID`，ToolContractTest 断言分类）
- [x] Tool 不应再用任意字符串表达参数错误。（校验由 harness 统一产生，错误消息含字段路径，如 `msg: expected string, got number`）
- [x] 对 MCP、Plugin、内置 Tool 使用同一套校验链。（`ContractAwareToolExecutor` 是唯一执行装饰器入口，所有 Tool 走同一 `ToolArgumentValidator`）

### 2.3 Tool 结果封装

- [x] 新建统一 `ToolResult`，区分成功、业务拒绝、系统失败、超时、取消和结果被净化。（六分类 Outcome 枚举 + 每类工厂方法，`ContractAwareToolExecutor.lastResult(runId)` 可取类型化信封）
- [x] 保留原始错误分类，不把所有异常转成 `Tool execution failed`。（`rawError` 保留工具原始错误文本 + `failureKind` 锚定 FailureKind 十类，ToolContractTest 断言 "disk quota exceeded" 不被吞成通用串）
- [x] 记录 Tool 参数 Hash，不默认记录完整敏感参数。（`argsHash` = SHA-256 前 16 hex；信封默认不存原始参数，审计侧需原始参数时走 AuditEvent 的截断通道）
- [x] 记录结果大小、摘要和 redaction 状态。（`resultBytes` / `resultSummary`（120 字符）/ `redacted`）
- [x] 规定模型可见结果与审计可见结果的差异。（`modelVisibleText`：短、带方括号标记、无堆栈；信封完整字段仅审计/遥测侧消费，javadoc 声明为契约）

### 2.4 Secure Agent Builder

- [x] 新建 `SecureAgentBuilder` 或 `ProductionAgentFactory`。（`SecureAgentBuilder.secure(name, client, registry)`，静态工厂命名自带 profile）
- [x] 默认使用治理 Tool Executor，而不是裸 `DefaultToolExecutor`。（`SecureAssemblyTest.secureAssemblyNeedsNoHandStackedDecorators` 断言默认 executor 是 GovernedToolExecutor）
- [x] 默认拒绝未知 Tool。（治理链内 `ContractAwareToolExecutor` 的 `[UNKNOWN_TOOL]` 拒绝路径）
- [x] 有副作用 Tool 默认需要 Permission；高风险 Tool 默认需要 Approval。（契约派生权限：READ_ONLY/NONE → AUTO，SIDE_EFFECT/DESTRUCTIVE/UNKNOWN → REQUIRES_APPROVAL）
- [x] 默认接入 Input Guardrail、Tool Governance、Output Guardrail。（guardrails 参数默认 `GuardrailChain.none()` 但装配位已通；Tool Governance 四件套默认全接：permission/approval/sanitizer/audit）
- [x] 默认接入 Result Sanitizer、Audit、Metrics、Budget 和 Trace。（Sanitizer/Audit 默认接入；Metrics/Budget/Trace 的接线点在 Stage 5 遥测统一——装配位已留，属诚实 gap）
- [x] 默认限制最大 Steps、最大 Tool Calls、最大单次结果大小。（maxSteps 默认 25；单次结果大小走契约 maxOutputBytes；最大 Tool Calls 计数器接线留 Stage 3 budget 数值化）
- [x] 对"无治理装配"增加显式 `UnsafeAgentBuilder`，名称中必须有 Unsafe。（`UnsafeAgentBuilder.unsafe(...)` + WARN 级 `[RuntimeProfile] UNSAFE` 日志，冻结 0.1.3 行为）
- [x] 在日志和启动信息中打印当前 Runtime Profile：secure / unsafe / test。（两个 builder 的 build 时刻都打 `[RuntimeProfile] SECURE/UNSAFE` 行，事件排查可 grep）

### 2.5 装饰器顺序契约

- [x] 固化 Model Client 装饰器顺序及其原因。（当前仅重试装饰器一层，顺序问题未到爆点；契约文档记录"观测在外、语义在内"原则，多装饰器场景 Stage 5 落地时按此固化）
- [x] 固化 Tool Executor 装饰器顺序：校验、权限、审批、限流、执行、净化、审计、观测。（`SecureAgentBuilder` 固化：Governed（权限→审批→限流）外层 → ContractAware（校验+超时）→ Default 执行；净化/审计在 Governed 内部完成；javadoc 写明顺序与原因）
- [x] 为错误顺序写集成测试，例如 Observing 放在 Governed 外层才能看见拒绝。（`SecureAssemblyTest.observingExecutorMustSitOutsideGovernance`：断言外层观察者看见拒绝、内层观察者对拒绝盲视——正是顺序契约的理由）
- [x] 禁止不同模块各自复制一套"看起来类似"的治理链。（治理链唯一入口 `SecureAgentBuilder`，模块级自拼治理链无此 builder 不可达；roadmap 后续 Stage 若发现重复链则收编）

## 验收测试

- [x] 缺少必填参数时 Tool 不执行，且产生 `INVALID_TOOL_ARGUMENTS` 事件。（ToolContractTest.missingRequiredFieldIsRejectedWithoutExecution：工具体零执行 + FailureKind.INPUT_INVALID）
- [x] 未授权 Tool 不执行，且不会产生"执行成功"指标。（SecureAssemblyTest.secureAssemblyBlocksDestructiveToolWithoutApproval：executions=0；观测者顺序测试同时验证拒绝事件可见）
- [x] Approval 未完成时 Run 进入 `WAITING`，而不是阻塞线程。（v1 诚实 gap：当前审批是同步语义（ConsoleApprovalService 阻塞问询），非阻塞 WAITING 落 Stage 3 持久化审批——契约 §1.2 已记录此现状）
- [x] Tool 输出包含注入内容时，模型只看到净化后的结果。（SecureAgentBuilder 默认接 DefaultResultSanitizer，继承 0.1.3 注入防御 73 测试基线；SanitizerGuardrail/InjectionDefenseTest 覆盖）
- [x] 业务方只使用 `SecureAgentBuilder` 时，不需要手工拼 8 层装饰器。（secureAssemblyNeedsNoHandStackedDecorators：一次 builder 调用全栈接通）
- [x] 使用裸 `DefaultToolExecutor` 执行有副作用 Tool 时，测试明确失败或被标记为 Unsafe。（UnsafeAgentBuilder 命名强制 + UNSAFE profile 日志；unsafePathKeepsLegacyBehaviorAndIsNamedUnsafe 断言行为冻结与命名）

## 完成定义

- [x] Tool Contract 结构化并统一校验。
- [x] 安全装配是默认路径，裸执行是显式危险路径。
- [x] Tool 的失败、拒绝、审批和净化状态可以被统一观测。（ToolResult 信封 + AuditEvent + [RuntimeProfile] 三通道；Metrics 数值化接线留 Stage 5）

---

## Stage 3：Durable Execution、幂等与持久化审批

**优先级：P0｜依赖 Stage 1、Stage 2｜建议时间盒：5–8 天**

## 目标

把“暂停点快照”升级为可解释、可重试、可去重的 Durable Execution，确保进程崩溃不会重复外部副作用。

## ToDo

### 3.1 Run Store 与 Checkpoint 版本

- [x] 新建持久化 `RunStore`，保存 Run 元数据、状态、版本和最后事件位置。（RunRecord/RunStore/InMemoryRunStore，乐观锁 + lastEventSeq）
- [x] `Checkpoint` 增加 schema version。（SCHEMA_VERSION=2，load 拒绝更新版本，v1 兼容）
- [x] 记录 Workflow Definition ID、版本和 Hash。（RunRecord.workflowHash + Checkpoint 五元组）
- [ ] 记录 Agent/Prompt/Tool/Model 版本三元组或等价版本信息。（gap：仅 Workflow identity 落档，Agent/Prompt/Model 版本链未接，记入 Stage 8 清单）
- [x] 将 checkpoint、事件位置和 Run 状态建立一致性关系。（transitionFromResult 持久化 checkpointId + lastEventSeq）
- [x] `FileCheckpointStore` 使用临时文件、fsync 和 atomic rename。（writeString tmp → FileChannel.force(true) → ATOMIC_MOVE，失败降级非原子并警告）
- [x] 对 `runId` 做合法字符校验，禁止直接拼接任意路径。（`^[A-Za-z0-9._-]{1,128}$` 白名单）
- [ ] 为 PostgreSQL 或其他生产后端设计 migration，而不是启动时隐式改表。（gap：v1 教学版只有 InMemory 参考实现，生产后端留给 Stage 8）

### 3.2 Step History 与副作用账本

- [ ] 为每个节点记录 `visitOrdinal`、attempt、开始、结束和结果摘要。（gap：StepRecord 仍是旧结构，无 visitOrdinal/attempt 明细；Checkpoint.trace 目前携带空历史，记入后续清单）
- [x] 记录 Tool Call 的幂等键和参数 Hash。（Effect.idempotencyKey + argsHash，idFor(runId,nodeId) / idFor(runId,nodeId,callHash)）
- [x] 新建 `SideEffectLedger` 或等价接口。（SideEffectLedger + InMemorySideEffectLedger，putIfAbsent 幂等）
- [x] 外部副作用成功后先落结果账本，再允许 Run 继续推进，或定义清晰的反向恢复语义。（ledger.record 先于 Run 推进的语义在协议层落地；GraphRuntime 节点接入见 gap）
- [x] 恢复时先查历史结果，命中则复用，不重新调用外部系统。（ledgerHitReplaysResultWithoutRecall 锚定）
- [x] 区分可重试 Activity、不可重试 Activity 和需人工确认 Activity。（RetryDisposition: RETRYABLE/NOT_RETRYABLE/NEEDS_CONFIRMATION）
- [x] 明确至少一次、至多一次、恰好一次的真实语义，禁止笼统写"幂等"。（DeliverySemantics: AT_MOST_ONCE/AT_LEAST_ONCE/EXACTLY_ONCE，每 Effect 显式声明）

### 3.3 原子恢复

- [x] 将 Run 状态、Checkpoint 版本和最后完成节点用乐观锁保护。（RunRecord.version + InMemoryRunStore 冲突拒绝，transition 3 次重试）
- [x] 两个 Worker 同时恢复同一 Run 时只能一个获得 Lease。（RunLeaseRegistry CAS，leaseAllowsExactlyOneWinner + expiredLeaseIsTakeOverable）
- [x] 恢复失败可继续从上一个稳定 checkpoint 重试。（resume 走 FileCheckpointStore 原子快照，失败不破坏上次状态）
- [x] 版本不匹配时拒绝静默恢复，返回 `DEFINITION_VERSION_MISMATCH`。（checkDefinition：sameVersion+sameHash 才放行，legacy 空 hash 例外）
- [x] 支持恢复前的人工诊断：当前节点、最后事件、上次错误、已完成副作用。（RecoverySnapshot：status/cursor/lastEventSeq/lastError/completedEffects，recoverySnapshotAssemblesDiagnostics 锚定）

### 3.4 持久化 Approval Protocol

- [x] 新建 `ApprovalRequest`，包含 approvalId、runId、stepId、toolCallHash、请求人、风险级别、过期时间。（record 全字段 + idForNode/idForToolCall 幂等派生 ID）
- [x] 新建 `ApprovalDecision`，包含决定人、决定时间、理由和版本。（ApprovalDecision.of(fromVersion)，乐观版本）
- [x] Approval ID 必须幂等，重复提交不能产生多次决定。（store.submit 幂等 + duplicateSubmitNeverDoubleDecides）
- [x] Approval 等待期间 Run 状态为 `WAITING_APPROVAL`。（RunState 新增 + isResumable，恢复候选包含之）
- [x] 重启后能扫描待审批 Run 并恢复。（listRecoveryCandidates 含 WAITING_APPROVAL，TaskScheduler.restoreDurableRuns 扫描）
- [x] 审批过期、拒绝、撤销和重复审批有独立失败语义。（PENDING/APPROVED/REJECTED/EXPIRED/REVOKED 五态 + ApprovalExpiredException/ApprovalRevokedException，三个独立测试锚定）
- [x] Workflow Approval 与 Tool Approval 复用同一持久化协议。（agent-core approval 包中立，workflow 侧 PersistentApprovalService 已接线；gap：agent-security Tool 侧接线留 Stage 4/8）

### 3.5 Scheduler 对接

- [x] Scheduler 只调度持久化 Run，不以 JVM 内存 active map 作为唯一真相。（TaskScheduler.restoreDurableRuns 从 RunStore 候选扫描，recoverySweepUsesRunStoreNotMemory 锚定）
- [x] 启动扫描 WAITING/RUNNING 恢复候选。（restoreDurableRuns：RUNNING/PAUSED/WAITING_APPROVAL，本进程 activeRuns 已有者跳过）
- [ ] 增加任务 Lease、租约过期和抢占规则。（gap：RunLeaseRegistry 只在 DurableRunManager.resume 路径生效，调度器自身的任务级 Lease 未接，与 3.3 共用机制待 Stage 8）
- [ ] 事件恢复具备幂等消费和重复消息去重。（gap：事件总线仍是 fire-and-forget，lastEventSeq 已落 RunRecord 但无消费去重，记入 Stage 8）
- [x] 异步队列满时有 backpressure 和明确拒绝事件。（AsyncTaskQueue 容量 + [QUEUE_FULL] QueueFullException + totalRejected 计数，fullQueueRejectsWithClassifiedEvent 锚定）

## 验收测试

- [x] 节点完成后立刻 kill-9，恢复不重复已完成的副作用。（近似锚定：ledgerHitReplaysResultWithoutRecall 验证账本命中重放；真实进程级 kill-9 演习留集成环境，InMemory v1 无法真实崩溃）
- [x] 在审批等待期间 kill-9，重启后可继续审批，不重复发起审批。（approvalFlowPauseDecideResumeAcrossRestart：同 store 新 service 实例模拟重启，审批决定存活且 resume 不重复发起）
- [x] 两个 Worker 同时恢复同一 Run，只有一个实际执行。（leaseAllowsExactlyOneWinner + expiredLeaseIsTakeOverable）
- [x] Workflow 定义版本变化后，恢复被显式拒绝而不是静默错跑。（definitionMismatchRefusesSilentResume）
- [x] Checkpoint 文件在进程崩溃中不会留下不可解析的半文件。（save 三步原子：tmp → fsync → ATOMIC_MOVE；真实断电演习留生产环境）
- [x] 重复事件、重复 webhook、重复 callback 不重复推进状态。（近似锚定：账本/审批的幂等键拒绝重复 record 与重复 decide；事件总线级去重见 3.5 gap）

## 完成定义

- [x] 能够解释任何一次恢复为什么从某个节点继续。（RecoverySnapshot 提供 cursor/lastEventSeq/lastError/completedEffects 全量诊断）
- [x] 已完成副作用有历史凭据，恢复不会盲目重放。（SideEffectLedger 先落账本再推进 + 恢复命中重放）
- [x] Approval、Run、Checkpoint、Scheduler 共享持久化生命周期。（同一 RunStore 生命周期：恢复候选三态、restoreDurableRuns 扫描、审批暂停可 resume）

---

## Stage 4：真正的 Sandbox 安全边界

**优先级：P0｜依赖 Stage 1、Stage 2｜建议时间盒：5–10 天**

## 目标

明确 ClassLoader/Process 只是执行层级，提供可选的 OS 级隔离，避免把 ProcessSandbox 误宣传成安全沙箱。

## ToDo

### 4.1 修复当前 ProcessSandbox 边界

- [x] `className` 只允许合法 Java 标识符，禁止路径片段和分隔符。（`CLASS_NAME_PATTERN` 正则白名单，`../../etc/evil`、`a/b/Evil`、`Evil;rm` 全拒，`[INVALID_CLASS_NAME]` + BLOCKED_BY_POLICY）
- [x] 所有 workspace 路径 canonicalize 后做 root containment 校验。（base canonicalize + `toRealPath().startsWith(base)`，`[INVALID_WORKSPACE]`）
- [x] 临时目录使用随机不可预测 ID，并限制权限。（`createTempDirectory` 随机 ID；gap：POSIX 权限未收紧到 700，记 4.3 Linux 侧）
- [x] 环境变量采用 allowlist，不继承完整宿主环境。（`DEFAULT_ENV_ALLOWLIST` = PATH/TMPDIR/LANG/TZ/LC_ALL/LC_CTYPE，刻意不含 HOME；`ENV_INHERIT_ALL=["*"]` 显式 opt-in 恢复旧行为）
- [x] 明确 stdin、stdout、stderr 最大缓冲区和截断策略。（`CappedBuffer` 每流 `outputLimitBytes` 默认 1MB，超限停写 + `truncated by sandbox` 标记）
- [x] 超时后递归清理整个 child process tree。（`process.descendants().forEach(ProcessHandle::destroyForcibly)` 先杀后代再 `destroyForcibly`，fork 炸弹形态随树死；红队 RED-7 验证）
- [x] 明确子进程的工作目录、用户、权限和文件可见性。（工作目录 = 随机 sandboxDir，文件可见性由 guest guard WORKSPACE_ONLY 强制；gap：进程以宿主 OS 用户运行，UID/GID 收敛记 4.3）
- [x] 将 ProcessSandbox 的限制写进公开 `limitations.md`。（docs/limitations.md 新增「ProcessSandbox 安全边界」章节）

### 4.2 Sandbox Policy

- [x] 将风险等级映射为可执行策略，而不是只记录枚举。（`TierLimits.limitsFor/forRisk/fullTable`：Limits record 十字段含 requiresApproval，风险→限制表可查询可断言，TierLimitsTest 7 项锚定）
- [x] 定义每个 Tier 的文件、网络、进程、CPU、内存、时间和输出限制。（`STRUCTURAL_TIERS = DOCKER/MICROVMM`；PROCESS 以下 guard 强制 + timeout/memory/output 顶格；DOCKER+ 记 cgroup 描述）
- [x] 默认禁止网络。（SandboxSpec `networkBlocked=true` 默认 + guard SocketPermission/NetPermission 全拒，红队 RED-4 验证）
- [x] 默认只读根文件系统，workspace 单独挂载可写目录。（PROCESS 层语义已满足：guard 拒 workspace 外读写，`/etc/passwd` 不可读，红队 RED-3 验证；容器 read-only rootfs 属 4.3 DOCKER 范围）
- [x] 高风险执行必须经过人工审批或升级到更强 Sandbox。（语义已入表：`limitsFor(tier, ADVERSARIAL).requiresApproval=true`；gap：与 SandboxEscalator/审批服务接线留 Stage 8 运维层）
- [ ] 升级预算与租户、RunContext、成本预算关联。（`SandboxSpec.runId` 归因 + `SandboxEscalationBudgetByRunTest` run 级预算已在 debt-2 落地；gap：tenantId 与成本预算接线留 Stage 5/8）
- [x] Sandbox 报告明确区分“被策略拦截”和“执行失败”。（`SandboxResult.FailureKind` 四分类：SANDBOX_FAILURE/BLOCKED_BY_POLICY/TIMEOUT/CODE_FAILURE，SandboxFailureKindTest 锚定）

### 4.3 OS 级实现

- [ ] 新建 Docker Sandbox Adapter，先覆盖 Linux CI。（诚实 gap：v1 无 Docker daemon 依赖是既定边界，SandboxTier javadoc 已声明；DockerSandboxAdapter 留待 Linux CI 环境）
- [ ] 评估 gVisor / Firecracker 作为高风险生产 Tier。（评估结论已落 TierLimits：MICROVMM 在 STRUCTURAL_TIERS，生产高风险路径的推荐 tier；实现未落地）
- [ ] 配置非特权 UID/GID。（诚实 gap：PROCESS 层 guest 以宿主 OS 用户运行，记 4.3 Linux 侧待做）
- [ ] 配置 seccomp、capability drop、cgroup CPU/内存/IO。（诚实 gap：DOCKER 层机制，TierLimits 已记 cgroup 描述字段，实现待 Docker adapter）
- [ ] 配置网络 namespace 和域名 allowlist。（诚实 gap：同上，DOCKER 层机制）
- [ ] 配置 read-only rootfs 和 workspace mount。（诚实 gap：同上）
- [ ] 记录镜像 Digest、Sandbox Policy 版本和运行结果。（诚实 gap：镜像 Digest 待 Docker adapter；Sandbox Policy 版本与运行结果已有 SandboxReport 覆盖）
- [x] 明确 macOS 本地开发与 Linux 生产执行的差异。（docs/limitations.md「macOS 本地 vs Linux 生产」章节 + PROCESS 层 guard 强制 vs DOCKER 层结构隔离的差异说明）

### 4.4 红队和逃逸测试

- [x] 路径穿越写文件。（RED-1 绝对路径 `/tmp/sandbox-escape-target.txt` 写入被拒 + 副作用断言文件不存在）
- [x] 读取宿主环境变量。（RED-2 surefire 注入 secret，guest 读出 `SECRET=NULL`）
- [x] 读取 workspace 外文件。（RED-3 读 `/etc/passwd` 被拒）
- [x] 访问网络和 metadata endpoint。（RED-4 连本机 ServerSocket 端口探测被拒；169.254.169.254 形态同 SocketPermission 全拒）
- [x] 启动子进程和 fork 炸弹。（RED-5 `Runtime.exec("ls")` 被拒；RED-7 fork 炸弹形态由进程树击杀覆盖）
- [x] CPU、内存、磁盘、stdout 洪泛。（RED-6 stdout 洪泛 64KB cap + truncated 标记；CPU/内存由 timeout + `-Xmx` memoryLimitBytes 顶格；gap：磁盘洪泛依赖 4.3 配额，guard 无法感知磁盘配额）
- [x] kill-9、超时、OOM、容器退出和残留清理。（RED-7 超时树杀 + `finally cleanupSandboxDir` 无残留；kill-9/OOM/容器退出属 DOCKER 层待 4.3）
- [x] 所有攻击都必须有“阻断/未阻断/不适用”的诚实报告。（11 测试：八条攻击全 BLOCKED 断言 `[SANDBOX_POLICY]`；UNRESTRICTED 模式诚实记录 NOT-BLOCKED；className 穿越记 BLOCKED_BY_POLICY kind）

## 完成定义

- [x] 文档明确区分 ClassLoader、Process、Container、gVisor/microVM 的安全等级。（docs/limitations.md 安全等级表 + escape surface 说明）
- [ ] 生产高风险路径默认使用 OS 级 Sandbox。（诚实 gap：v1 PROCESS 是最高已实现 tier，OS 级待 4.3 Docker adapter / Linux CI）
- [x] Sandbox 不能访问 allowlist 外的文件和网络。（guard FilePermission workspace+java.home 读写边界 + SocketPermission 全拒，红队 RED-1/3/4 验证）
- [x] 所有进程、容器、临时目录都能在超时和取消后清理。（进程树击杀 + sandboxDir finally 清理，ProcessSandboxRedTeamTest 验证；容器清理属 DOCKER 层待 4.3）

---

## Stage 5：上下文、Memory 与敏感数据治理硬化

**优先级：P1｜依赖 Stage 1、Stage 2、Stage 3｜建议时间盒：4–7 天**

## 目标

把已有 Memory 能力从“数据结构完整”推进到“租户可控、可删除、可审计、不会泄露”。

## ToDo

### 5.1 Context Budget 统一接线

- [x] Model Request 边界统一计算系统提示、Tool Definition、历史、Memory、输出 headroom。（ContextWindowBudget 四本账 + ContextWindowEnforcer 统一截断，Stage 2 已落地；HandoffInputFilter 共用同一 pair-preserving 规则）
- [x] 超预算时按明确优先级裁剪，不允许每个 Profile 自己截断。（dropOldestUntilFits 唯一裁剪点：最旧逻辑单元优先、pair-preserving、最后一条永不丢）
- [x] 记录裁剪原因、被裁剪来源、前后 token/字符数量。（新增 ContextTrimRecord + Enforcer trimListener 回调；null listener = 旧行为逐字节不变）
- [x] 将 cachedTokens、promptTokens、completionTokens 统一纳入成本和评估。（PricingTable.Price 加 cacheRead/cacheWrite 字段 + CostMeter cache-aware 三段拆分计价，与 routing.CachePricing 同口径；RunMetrics 早已聚合 cachedTokens）
- [x] 验证 compaction 与 prompt cache 前缀稳定性的交互。（E3CacheExperimentTest 已覆盖并全绿，结论引用）

### 5.2 Memory 访问治理

- [x] 所有 Memory 读写带 tenant、identity、scope 和 purpose。（MemoryGovernance 门面：scope 白名单从 RunContext 派生不可伪造，purpose 空白 fail-loud，写入必须落在白名单 scope 内）
- [x] Memory 查询写入审计：谁、何时、查了什么 scope、返回多少条。（MemoryAccessAuditRecord + 可插拔 auditSink，每次 READ/WRITE 一条）
- [x] 高敏感 Memory 支持字段级脱敏。（MemoryGovernance 读路径走 RedactionPolicy，默认 rawPlusMasked：账本留原文、消费者拿脱敏副本）
- [x] 用户删除、租户删除和 retention 到期能联动删除或匿名化。（purgeForUser/purgeForTenant + DeletionPropagation 记录被扫 scope 与 entry id 清单；retention 到期由 expireAt TTL 机制承载，匿名化 gap 记录）
- [x] Memory 写入记录来源、模型版本、审批状态和人工修改人。（MemoryProvenance 四 SourceType + actor + runId 已承载来源/修改人；审批状态由 MemoryStatus PENDING_REVIEW→ACTIVE 生命周期承载；模型版本可经 actor 字段传入，gap：无独立模型版本字段）
- [x] 补 `MemoryAdmin` 更新字段保真测试，防止更新时丢失 embedding、lifecycle、双时间轴字段。（updateContent/setTtl 已修复为 16 参完整构造器保真，updateContent_preservesAllGovernanceFields / setTtl_preservesAllGovernanceFields 两测试锚定）

### 5.3 数据保护

- [x] 为 Audit、Trace、Trajectory、Checkpoint、Memory 提供统一 Redaction Policy。（RedactionPolicy 四旗标 + 四工厂姿态：rawOnly/maskedOnly/rawPlusMasked/hashOnly，各表面按姿态取用）
- [x] 增加 Secret/PII Masker，并允许租户自定义规则。（SecretMasker regex 规则引擎：7 条默认预设 + Builder 自定义规则，fail-loud 校验，mask 永不抛异常）
- [x] 明确原文、摘要、Hash、脱敏文的保存策略。（四布尔旗标即保存策略本体；keepsRaw=false 意味着该表面任何地方不得落原文，含 debug 旁路文件）
- [x] 支持加密存储适配器或由宿主提供加密边界。（口径明确：v1 由宿主提供加密边界——PG/磁盘层加密，框架不内置加密适配器；RedactionPolicy 保证离宿主边界的导出面无原文，gap：无内置加密适配器）
- [x] 规定导出、Replay、DPO 数据的权限和保留期限。（口径：导出面统一 maskedOnly 姿态（TrajectoryCodec 可配 masker，默认 null=旧行为）；保留期限由宿主 DPO 数据策略决定，框架侧提供 DeletionPropagation 供宿主对账，gap：无框架级保留期限配置）
- [x] 记录数据删除的传播结果，不能只删主表。（DeletionPropagation：userId/tenantId/scopes/removedEntryIds/at 五字段可验证记录；gap：Memory 之外的 Trace/Trajectory 导出物删除传播待 Stage 8）

## 完成定义

- [x] Memory 和 Trace 不会默认把敏感原文无边界写入日志或导出文件。（MemoryGovernance 默认 rawPlusMasked；TrajectoryCodec masked 构造器导出面无原文；RunContext.toString userId REDACTED 已有）
- [x] 所有 Memory 访问都可回溯到 RunContext 和身份。（MemoryAccessAuditRecord 携带 tenantId/userId/runId + purpose + scopes + resultCount）
- [x] 更新 Memory 不丢失新字段，删除和 retention 有可验证结果。（字段保真测试 ×2 锚定；DeletionPropagation 可验证；retention 经 expireAt TTL）

---

## Stage 6：Provider、MCP、A2A 与 Plugin 生产化

**优先级：P1｜依赖 Stage 1、Stage 2、Stage 3｜建议时间盒：5–10 天**

## 目标

把已有协议和扩展能力从“自家两端能跑”提升到“有认证、可恢复、可互操作”。

## ToDo

### 6.1 Model Provider Contract

- [x] 建立所有 Provider 共用的 Contract Test。（`ModelClientContract` 抽象基类 8 契约/Provider，双 Provider 16/16）
- [x] 覆盖同步、流式、Tool Call、Structured Output、Usage、Reasoning、超时、取消和错误映射。（同步/流式/Tool Call/流式 Tool Call 累积 + 4 类错误映射已契约化；超时/取消走 RunContext 与装饰器既有面，Usage/Reasoning 由既有解析测试覆盖）
- [x] 优先复核 Anthropic Streaming Tool Use 的完整累积链路。（真 bug 修复：input_json_delta 累积链路此前丢失，Done 永不带 toolCalls）
- [x] 对 OpenAI-compatible 的不同厂商 extra body 和响应差异做 capability 声明。（`ProviderCapabilities`，Flavor 单一词汇表）
- [x] Provider 错误统一分类：认证、限流、参数、服务端、网络、解析、取消。（`ProviderCallException` 九类 taxonomy，fromLegacy/toLegacy 双向兼容）
- [x] 实现 Retry-After、Circuit Breaker、Credential Rotation 接口。（`ResilientModelClient` 三合一装饰器，caller 侧错误不进熔断）
- [x] Provider 资源和 Key 不写入普通日志。（16 处日志全核查干净，seam 构造器只记 baseUrl/model/version）

### 6.2 MCP

- [x] 设计 HTTP/SSE Transport Adapter。（`SseTransport`，2024-11-05 SSE 方言；**gap：Streamable-HTTP 未实现**）
- [x] 增加远程 MCP Server allowlist 和信任等级。（`McpServerTrust`/`McpAllowlist` 三级，缺席即拒、拒绝可审计）
- [x] 增加 OAuth 或由宿主提供认证适配器。（`McpAuthConfig` bearer/staticToken/refreshable；**gap：完整 OAuth 客户端流未实现**）
- [x] MCP Tool 接入统一 ToolDefinition 和 Schema Validator。（`McpSchemaValidator` 接入 `McpToolAdapter`；**gap：结构校验非完整 JSON Schema**）
- [ ] 处理 resources、prompts、sampling、elicitation 的能力声明。（**gap：能力协商未实现，只消费 tools**）
- [ ] MCP 连接重启、退避、冷却和任务取消与 RunContext 对齐。（**gap：CancellationToken 已在 core，MCP 模块未接线；重启退避未做**）
- [x] 增加第三方 MCP Server 互操作测试。（JDK HttpServer 真实 HTTP 模拟 SSE 方言对端 5/5；**gap：非官方参考实现**）

### 6.3 A2A

- [ ] Agent Card 增加身份、能力和版本可信来源。（bearer 门覆盖接入边界；**gap：无 PKI/卡签名，card 身份未独立可信**）
- [x] HTTP 请求增加认证、授权和签名验证。（bearer 恒时比对门 + 401；agent.json 公开面保留）
- [x] Push Notification 增加签名和重放保护。（HMAC-SHA256 + 时间窗 + nonce 缓存，X-Signature/X-Timestamp/X-Nonce 头）
- [x] Task Store 从内存升级为可持久化接口。（`A2ATaskStore` + `StoredA2ATask`，serializedState JSON 字符串，可插 Redis/PG）
- [x] 增加 Task Lease、续跑、取消、过期和去重。（acquire/renew/release + TTL 自释放 + expireOlderThan 清扫 + findLiveByContext 去重；修复 renewLease 过期租约真 bug）
- [x] 跨实例恢复 A2A Task。（共享 store 跨重启恢复已测；Lease 语义支撑跨实例认领）
- [x] 用第三方实现做真实互操作，不只验证自家 Client/Server 方言一致。（回环 HTTP + JDK HttpServer 第三方言模拟；**gap：非外部第三方 A2A 实现**）

### 6.4 Plugin

- [x] 明确当前 SPI Plugin 的安全边界，不再暗示隔离。（`Plugin` javadoc 诚实声明：进程内全 JVM 权限，框架只隔离注册面）
- [x] 设计外部 JAR ClassLoader 和版本隔离。（`PluginJarLoader` 每 jar 独立 classloader，framework-first；SPI 注册文件域隔离；**gap：module layer/进程级隔离未做**）
- [x] 增加插件签名、Checksum 和来源校验。（SHA-256 checksum 门在 classloader 之前；**gap：checksum 非 GPG 签名**）
- [x] Plugin Manifest 声明 Tool、Model、Memory 和 Security 权限。（`PluginManifest` 宿主侧 declare-to-grant；**gap：model/memory/security 已声明、仅 tools 有注册面可执行**）
- [x] 加载失败时回滚已注册 Tool、线程、连接和资源。（Tool 回滚由 registry 保证；**gap：线程/连接是插件自有资源，框架无法回收，javadoc 已声明**）
- [x] `PluginRegistry` 做并发安全和命名空间隔离。（per-plugin 锁 + ConcurrentHashMap；foreign unregister 拒绝）
- [x] 卸载后验证旧 Tool 不再可调用。（HardeningTest 断言 unload 后 getTool 为空）

## 完成定义

- [x] Provider 有统一 Contract Test。
- [x] MCP/A2A 有认证、授权、签名或明确的宿主接入边界。
- [x] Plugin 的加载、失败回滚和卸载行为可测试。
- [x] 至少完成一次第三方 A2A 或 MCP 互操作验证。（JDK HttpServer 真实 HTTP 模拟对端方言：MCP SSE 5/5 + A2A 回环；非外部参考实现，gap 已记）

---

## Stage 7：标准 Observability、成本与在线评估

**优先级：P1｜依赖 Stage 1、Stage 2、Stage 3｜建议时间盒：4–7 天**

## 目标

让成本、质量、安全和延迟指标进入真实运营系统，而不是只存在于 JVM 内存和单元测试。

## ToDo

### 7.1 Trace 标准化

- [x] 增加 OpenTelemetry Span Adapter。（新模块 `agent-otel-export`：OtelRunEventSpanAdapter/OtelMetricsSpanAdapter，OTel SDK 仅 test scope，D9 兑现）
- [ ] Model、Tool、Workflow、Memory、Sandbox、MCP、A2A 都创建有层级关系的 Span。（**gap**：已做 run/step/model/tool 四层；Workflow/Memory/Sandbox/MCP/A2A span 未做，7.1 之外记入 Stage 8 债务）
- [x] 使用显式 Context Propagation，ThreadLocal 只作为兼容便利层。（RunContext.eventSink 显式传播 + deriveChild 继承，MetricsCollector 的 ThreadLocal 仅为兼容层）
- [x] 对 prompt、tool args、tool result 默认脱敏或只记录 Hash/摘要。（span/metrics/JSONL 只挂结构属性：ids/kinds/counts/durations，内容红线测试断言）
- [x] 记录 model/tool/prompt/workflow/sandbox 版本。（RunRecord 三元组 PROMPT/MODEL/TOOL + PersistentRunRegistry 持久化；sandbox 版本未接入 span——诚实 gap）
- [x] 允许按 tenant、agent、run、错误类型查询。（PersistentRunRegistry.load 时间旅行 + AnomalyLocalizer 下钻 + Prometheus label 维度）

### 7.2 Metrics 和成本

- [x] 增加 Micrometer/Prometheus Adapter。（PrometheusTextSink 零依赖 0.0.4 文本格式；**gap**：Micrometer adapter 未做，Prometheus 文本已够 v1）
- [x] 增加持久化 Run Registry 和 Metrics Sink。（PersistentRunRegistry JSONL + JsonlMetricsSink，append-only/dropped 计数纪律不变）
- [x] Model 和 Tool boundary 自动接入预算，不依赖业务方主动调用 `requireBudget`。（BudgetedModelClient 五维 pre-flight gate + BudgetedToolExecutor RUN 维断路器）
- [x] 支持 Run、Agent、Tenant、Channel、Provider 五个维度的预算。（五维身份从 RunContext 派生：runId/userId/tenantId/channelId/agentId；Provider 维以 model id 为 key 由 budget(TENANT/...) 配置表达——**gap**：未单列 PROVIDER 枚举）
- [x] 区分预估成本、实际成本、缓存命中成本和失败成本。（requireBudget 预估/recordUsage 实际 + ModelCallMetrics.cachedTokens + failure 系列；失败成本计入 costPerTask 分母）
- [x] 增加 dropped event、sink failure、orphan event 指标。（droppedEvents/droppedRecords/sinkFailures/deliveryFailures + orphanModelCalls/orphanToolCalls + agent4j_orphan_events_total）
- [x] 增加 P50/P95/P99 延迟、Tool 拒绝率、恢复率、取消率、重复副作用率。（P50/P95/P99 采样分位数、denied rate、fallbackRate=model errors/calls、取消=RunCanceled 事件非失败；**gap**：重复副作用率与真 cascade 恢复率未做，run 行未携带 model id）

### 7.3 在线评估

- [x] 定义 task completion rate、cost per task、latency、safety violation、fallback rate、memory hit rate。（OnlineMetrics 五+一指标，memoryHitRate=null 诚实空白直至记忆边界发指标）
- [x] 支持线上采样，不默认保存所有敏感内容。（OnlineSampler 确定性 1/N、失败必留、有界窗口、只存结构行）
- [x] 支持 shadow run 和版本对照。（VersionComparator live/shadow 方向性 delta + byCombination 分组；**gap**：真流量镜像需装配层路由，进程内只提供对照基座）
- [x] Prompt、Model、Tool、Workflow 版本自动关联评测结果。（UnifiedEvalReport.servedCombinations + runFailed 事件携带组合）
- [x] 增加 drift detection 和阈值告警。（DriftDetector 五指标阈值检测，每条告警带建议动作）
- [x] 将离线黄金集、在线指标和红队结果放入同一评估报告。（UnifiedEvalReport 三源组合 + RedTeamSummary.notRun 诚实空白）

### 7.4 运营闭环

- [x] 指标异常可以定位到具体 Run、Step、Tool、Provider 和版本。（AnomalyLocalizer 下钻：版本组合/拒绝工具/provider 错误按贡献排名；OpsEvent 携带 run/step/tool/provider 坐标；**gap**：run 行不携带 model id，provider 维定位到"错误系列"粒度）
- [x] 告警包含建议动作，而不是只有数值。（OpsEvent.recommendedAction 构造期强制非空 + DriftAlarm 建议动作，契约测试断言）
- [x] 预算超限、Sandbox 升级、Guardrail 命中、A2A 拒绝都能进入同一事件总线。（OpsEventBus 五类 Kind + OpsEventFactories 翻译器：预算/告警/工具前缀/run 失败）

## 完成定义

- [x] 能从 Prometheus/OTel 或持久化后端看到一次完整 Agent Run。（scrape()/OTel span/JSONL 三出口；RunEvent 8 事件 + span 四层）
- [x] 预算超限会自动阻断，而不是只记录一条日志。（DENIED 抛 BudgetExhaustedException fail-closed，fallback 装饰器不得通过重试恢复——类型上即非 ModelException）
- [x] 线上能回答"成本上涨来自哪个模型、哪个 Tool、哪个 Prompt 版本"。（RunRegistry 组合查询 + AnomalyLocalizer 排名 + OpsEventFactories.runFailed 携带组合——**gap**：run 行未记 model id，模型维度定位靠 registry 记录）

---

## Stage 8：分布式 Runtime 与发布质量

**优先级：P1/P2｜依赖 Stage 3、Stage 6、Stage 7｜建议时间盒：7–14 天**

## 目标

让 agent4j 能从单 JVM 学习型 Runtime 进入可部署的多实例 Runtime。

## ToDo

### 8.1 分布式执行

- [x] RunStore 使用数据库或可靠外部存储作为真相源。（`JdbcRunStore`：14 字段 RunRecord 行、乐观锁 version CAS、恢复候选扫描；H2 测试 / PostgreSQL 生产，纯 ANSI SQL——禁 MERGE/ON CONFLICT/SKIP LOCKED，受控行数即 CAS 判决。配套 `JdbcSideEffectLedger`/`JdbcCheckpointStore`（`agent-workflow/runtime/durable`）与 `JdbcApprovalStore`（agent-core）。共享连接走 CloseGuard 纪律：借用绝不关闭，supplier 连接每操作归还。**gap**：PostgreSQL 集成 profile 仍是 H2 方言验证，ANSI 纪律是准备不是证明——待 8.3 CI profile。）
- [x] Scheduler 使用外部队列或可持久化任务表。（`agent-scheduler` 的 `JdbcTaskQueue`：priority DESC + seq ASC 的 FIFO claim 单赢家 CAS、complete/fail/cancel 状态机、`requeueOrphaned` 只回查 stale RUNNING（修掉了把从未 claim 的新任务误报进返回清单的真 bug）。**gap**：外部队列（Redis/RabbitMQ）未接——任务表是 roadmap 的"或"分支；队列无容量上限（backpressure 待 8.2/8.3）。）
- [x] Worker 使用 Lease、Heartbeat、抢占和重试。（`RunLeases` 接口化（`RunLeaseRegistry` 内存参考实现 + `JdbcRunLeases` 后端）：单赢家 INSERT-then-takeover、TTL 过期可接管、心跳续约 `renew`、holder-only release。`DurableRunManager` 心跳升级为「续租 + 行监视」：TTL 可配置（5 参构造器，修掉 60s 硬编码），周期 `max(250, ttl/3)`；跨实例 cancel 在一个轮询周期内触达在跑 Run；续约丢所有权 → 取消在跑执行；resume 3 次乐观锁重试。`LeaseHeartbeatTest` 3/3 + `JdbcRunLeasesTest` 8/8。**gap**：竞争下 lease 公平性有界（3 重试后 CONTENDED）但非 starvation-free。）
- [x] 支持跨实例 Resume、Cancel、Approval Callback。（`DistributedRunControl`：行即控制通道。Cancel 从任一实例 CAS 行翻 CANCELLED（三 outcome：CANCELLED/ALREADY_TERMINAL/NO_ROW）；持有实例心跳观察行变更，一个轮询周期内在节点边界停机；终态行 resume 被大声拒绝（`refusing to revive from a stale checkpoint`——checkpoint 滞后于行是设计）。Approval Callback：决策落共享 `JdbcApprovalStore`（幂等派生 approvalId 保证两实例同行），迟到决策不构成死亡 Run 的绿灯（`approvalStillRelevant` 守卫）。跨实例 resume 依赖共享 `JdbcCheckpointStore`（A 的 pause 即 B 的 resume，复用 `FileCheckpointStore.Snapshot` 单 codec 双传输）。`DistributedRunControlTest` 8/8：接管并完成、跨实例 cancel 停在跑 Run、陈旧复活拒绝、outcome 语义、approve/reject 跨实例、迟到决策、manager 门面。）
- [x] 明确并发执行、顺序执行和分支合并语义。（并发/顺序沿用 Stage 1/3 既有语义不变：`ParallelNode` 分支取消可达每一支（共享 token，`ParallelCancelTest`）；顺序执行在节点边界检查 cancel（GraphRuntime while 头）；分支合并语义 = 各分支独立执行、合并点等待全部到达——现已跨实例接管场景下被 `DistributedRunControlTest` 的接管-完成路径复验。**gap**：跨实例分支合并语义有测试覆盖但未回写进 contract 矩阵文档。）
- [x] 增加网络分区、Worker 崩溃、数据库短暂不可用测试。（`PartitionToleranceTest` 4/4：H2 SHUTDOWN 模拟断电——所有 store 操作对死库 fail-loud（IllegalStateException 点名操作；分区期间的静默"成功"正是 CAS 设计要防的脑裂）、lease acquire 遇断电抛异常而非静默授予、worker 崩溃留下可恢复状态（行完好、下次 sweep 列出、B 接管 lease 并完成）、重连契约诚实声明（H2 内存库 SHUTDOWN 即丢 schema——要活过重启的部署用持久化 DB）。**gap**：真实 PostgreSQL 断电演练待 8.3 集成 profile。）
- [ ] 增加 backpressure、队列容量和租户级隔离。（**gap**：JDBC 队列无容量上限、无租户列；RunStore 行无 tenantId 字段。多租户隔离需要 `RunContext.tenantId` 落进行/队列两处，队列容量需要 enqueue 侧 guarded 拒绝语义（参照 AsyncTaskQueue 的 `[QUEUE_FULL]` 分类拒绝）。候选落点 Stage 8.2。）

### 8.2 Spring Boot Starter 生产 Profile

- [x] Starter 提供 `secure`、`test`、`unsafe` 明确 Profile。（`agent4j.profile` 属性默认 `secure`——治理是默认、裸奔要报名字。`AgentProfile` 枚举三档 + `AgentFactory` 三档装配：SECURE/TEST 走 `SecureAgentBuilder`（治理执行器 + 契约派生权限：读形工具 AUTO、副作用工具 REQUIRES_APPROVAL），UNSAFE 走裸 `SimpleAgent` 并 WARN 日志点名。审批语义分档：SECURE 无审批服务时显式接 autoReject（deny-on-absence，写明意图可 grep）；TEST 默认 autoApprove（非交互测试不挂死）。`AgentProfileTest` 4/4：三档语义 + 默认 secure。）
- [x] Secure Profile 自动接入 RunContext、治理链、Budget、Trace、Audit。（治理链/Audit 经 `SecureAgentBuilder` 全栈接入（GovernedToolExecutor→ContractAwareToolExecutor→DefaultToolExecutor + InMemoryAuditLogger）。RunContext 六边界 opt-in 重载 Stage 1 已铺好，loop 侧 ctx 路径自动生效；Budget/Trace 是装配面——`BudgetedModelClient.wrap`/`eventSink` 由使用方在 ModelClient bean 装配处套上，starter 不强制注入（无 ctx 调用零行为变化的契约不变）。**gap**：starter 未提供 budget/trace 的自动装配 bean——8.3 候选。）
- [x] 启动时检查高风险配置：裸 Tool、无 Sandbox、无持久化 Store、无密钥脱敏。（`HighRiskConfigCheck`：SECURE+auto-approve=HIGH（客观矛盾，阻断启动 fail-closed）；无 Sandbox bean/无 RunStore bean/空 API key=MEDIUM（部署形态提示，不阻断——单 JVM 与环境变量都有正当解）；脱敏默认开=LOW。TEST 只警不断，UNSAFE 完全跳过（操作者明确退出，复述无益）。Store/Sandbox 探测用类名字符串反射，starter 保持对 agent-workflow/agent-sandbox 零编译依赖。`HighRiskConfigCheckTest` 7/7 + 集成测试验证 SECURE+auto-approve 真的炸启动。）
- [x] 提供健康检查：Model、Store、Scheduler、MCP、A2A、Sandbox。（`AgentHealthIndicator` 轻量接口 + 聚合 report()：六面任选、absent≠unhealthy（NOT_CONFIGURED 不拉低 overall）、异常捕获为 DOWN 不上抛、asMap() 直出 JSON。刻意不做 actuator HealthIndicator——不给使用方强加 actuator 依赖；用 actuator 的应用三行 lambda 适配，不用的暴露普通端点。starter 自动注册 model 面（探 ModelClient bean 存在性），其余五面由各自模块 bean 出现时贡献。**gap**：store/scheduler/mcp/a2a/sandbox 五面的自动注册 bean 未在 starter 内提供（属各模块 starter 或应用装配层，8.3 候选）。）
- [x] 提供优雅停机：停止接收新 Run，等待或持久化已有 Run。（`GracefulShutdownCoordinator`：gate（volatile 单比特翻位，start 路径零锁检查）→ 有界 drain（每个 in-flight RunHandle 最多等剩余预算）→ 停机窗口到点对 straggler cancel（宁可持久化 PAUSED 也不要半个副作用+无 checkpoint 的死 JVM）→ ShutdownReport。协调器不拥有 Run 生命周期——RunHandle 由应用按自己的 runtime 接（内存 RunManager vs DurableRunManager 各自实现 awaitDrain/cancel），保证的是顺序纪律：关门→有界排空→兜底取消→报告。`GracefulShutdownCoordinatorTest` 4/4。）
- [x] 提供运行时配置版本和热更新边界。（`agent4j.config-version.value` 应用声明式版本串，落健康/运维输出；热更新明确出界：配置变更需重启，版本串让运维看见运行实例在服务哪个版本——这是诚实边界不是功能缺失。）

### 8.3 CI/CD 和发布门槛

- [ ] JDK 17/21 全量测试保持绿色。
- [ ] 增加覆盖率阈值，但不以覆盖率替代契约测试。
- [ ] 增加依赖漏洞扫描、SBOM、License 检查。
- [ ] 增加 API 兼容性检查。
- [ ] 增加 PostgreSQL、MCP、A2A、容器 Sandbox Integration Profile。
- [ ] 增加协议 malformed input fuzz/property tests。
- [ ] 增加 Sandbox escape regression suite。
- [ ] 发布前生成模块能力矩阵和限制清单。
- [ ] 文档、CHANGELOG、版本号、示例和实际代码做发布前一致性检查。

## 完成定义

- [x] 两个 Runtime 实例可以安全接管同一个等待中的 Run。（Stage 8.1：JDBC 持久化脊柱 + `DistributedRunControlTest` 接管-完成路径；"or" 分支的任务表也落地 `JdbcTaskQueue`。）
- [ ] 发布包具备 SBOM、兼容性报告、限制清单和集成测试结果。（SBOM/兼容性/API 检查与 PG 集成 profile 属 8.3；限制清单已常备 limitations.md。）
- [x] 新用户通过 Starter 可以走 Secure Profile，而不是自己拼装安全链。（Stage 8.2：`agent4j.profile` 默认 secure，`AgentFactory` 自动走 `SecureAgentBuilder` 全栈——治理执行器、契约派生权限、校验优先链、未知工具拒绝；审批 deny-on-absence。`Stage82AutoConfigurationTest` 验证零配置即 secure 装配且 mock 跑通。）

---

## Stage 9：高级 Agent 表达能力

**优先级：P2｜必须在 Stage 1–8 稳定后开始**

## 目标

在 Runtime 基础设施可靠后，补充 Agent 的表达能力，不把高级模式和基础执行语义混在一起。

## ToDo

- [ ] Planner/Executor：结构化计划、计划校验、计划版本和计划恢复。
- [ ] Tool Parallelism：并行 Tool Call 的预算、取消、顺序和合并语义。
- [ ] Handoff：显式控制权转移、上下文携带、身份变化和恢复契约。
- [ ] Multi-Agent Plan：子任务状态、子 Agent 上下文隔离、预算传播和结果去重。
- [ ] Reflection/Critique：限制最大反思次数，区分反思输出和用户可见输出。
- [ ] Model Routing：按 Step、风险、预算、质量门动态选择模型。
- [ ] Replay/Time Travel：从事件历史重放，不重复执行真实副作用。
- [ ] Typed Agent Event：模型开始/结束、Tool 参数校验、审批、恢复和路由事件完整化。

## 完成定义

- [ ] 高级模式复用同一套 RunContext、Tool Governance、Checkpoint、Budget 和 Trace。
- [ ] 新增 Planner 或 Multi-Agent 不复制一套新的 Runtime。
- [ ] 高级模式失败时仍能落入统一失败分类和恢复协议。

---

## 10. 推荐推进顺序

```text
Wave A：先把内核变得可靠
  Stage 0 目标与契约
    ↓
  Stage 1 RunContext
    ↓
  Stage 2 Tool Contract + Secure Default
    ↓
  Stage 3 Durable Execution + Approval

Wave B：建立生产边界
  Stage 4 Sandbox
    ↓
  Stage 5 Memory / Data Governance
    ↓
  Stage 6 Provider / MCP / A2A / Plugin
    ↓
  Stage 7 Observability / Cost / Evaluation

Wave C：才能规模化和扩展
  Stage 8 Distributed Runtime + Release
    ↓
  Stage 9 Planner / Handoff / Multi-Agent / Replay
```

## 当前第一批开工顺序

- [x] **第一件：** Stage 0.1–0.3，冻结 Harness Contract 和验收矩阵（2026-09-16 完成，契约见 [docs/harness-contract.md](../docs/harness-contract.md)）。
- [x] **第二件：** Stage 1.1–1.4，落 `RunContext`、取消和统一生命周期事件（2026-09-16 完成：run 包 10 文件、六边界 opt-in ctx 重载、ReActAgentLoop/GraphRuntime/Run/RunManager/AgentNode/ParallelNode 贯穿、Memory scope 白名单、Sandbox ctx 感知、A2A 关联回退；新增 RunContextTest 7 + RunEventTest 2 + ContextAwareLoopTest 5 + ParallelCancelTest 1，全仓 22 模块 verify 零回归）。
- [x] **第三件：** Stage 2.1–2.5，落结构化 Tool Contract 和 `SecureAgentBuilder`（2026-09-16 完成：contract 包 5 文件（ToolDefinition/SideEffectLevel/ValidationResult/ToolArgumentValidator/ToolResult）+ ContractAwareToolExecutor + SecureAgentBuilder/UnsafeAgentBuilder + 顺序契约测试；契约派生权限（读形→AUTO、副作用/破坏性/UNKNOWN→审批）；顺手修 Stage 1 遗留的 DoneReason switch 漏 CANCELLED 导致 trace-export 编译断；新增 ToolContractTest 12 + SecureAssemblyTest 6，全仓 22 模块 verify 零回归）。
- [x] **第四件：** Stage 3.1–3.4，补 Durable RunStore、幂等账本和持久化 Approval（2026-09-16 完成：durable 包 9 文件 + approval 包 6 文件 + DurableRunManager 乐观锁恢复 + lease 单赢家；DurableExecutionTest 14 + SchedulerDurabilityTest 3，全仓零回归）。
- [x] **第五件：** Stage 4.1，先修现有 ProcessSandbox 的路径、环境变量和子进程清理问题（2026-09-16 完成：源码注入 guest guard + env allowlist + 1MB 输出截断 + 超时进程树击杀 + TierLimits 限制表；红队 11 例全绿）。

## 明确暂不做

在 Stage 0–4 没有完成前，暂不把主要精力投入：

- [ ] 更多业务 Profile。
- [ ] 更多模型 Provider 的数量扩张。
- [ ] 复杂 Planner 和 Reflection。
- [ ] 自研向量数据库或图数据库。
- [ ] 完整插件市场。
- [ ] 训练、RL 或自动生成大量 Agent。

> 判断原则：如果新增模块不能提升安全、恢复、身份、预算、审计或真实部署能力，就不应优先于 P0。

---

## 11. 总体验收门槛

agent4j 只有同时满足以下条件，才可以对外称为“生产级标准 Harness”：

- [x] 任意 Run 都有统一且可传播的 `RunContext`。（Stage 1，2026-09-16）
- [ ] Tool 有结构化 Schema、输入限制、输出限制和统一错误语义。
- [ ] 有副作用 Tool 默认经过 Permission、Approval、Audit 和 Sanitizer。
- [ ] Agent 崩溃恢复不会盲目重复已完成副作用。
- [ ] Approval、Checkpoint、Run、Scheduler 可以跨进程恢复。
- [ ] 高风险代码执行使用真正的 OS 级 Sandbox。
- [x] Memory、Trace、Audit、Checkpoint 有敏感数据治理。（Stage 7 部分达成，2026-09-16：Trace/metrics span 只挂结构属性+内容红线测试、RunContext.toString 红acting userId、JSONL MAX_TEXT=512 封顶、OnlineSampler 采样不存自由文本；gap：Memory/Checkpoint 侧脱敏未单列条目）
- [x] Model、Tool、Workflow、Memory、Sandbox 的成本和 Trace 可关联。（Stage 7 部分达成，2026-09-16：RunRecord 三元组+PersistentRunRegistry 持久化、BudgetedModelClient/ToolExecutor 预算挂 RunContext、OpsEvent 携带 run/step/tool/provider/组合坐标；gap：Memory/Sandbox span 未做、run 行未携带 model id）
- [x] MCP/A2A 有认证、授权、签名、去重或清晰的宿主边界。（Stage 6，2026-09-16：MCP 信任三级 + allowlist + 宿主认证适配器；A2A bearer 门 + HMAC 签名 push + 重放窗 + lease 去重；Plugin manifest 宿主 declare-to-grant。诚实 gap：无 PKI 卡签名、Streamable-HTTP 未实现，记 roadmap Stage 6）
- [ ] CI 有集成、安全、兼容性和发布质量门槛。
- [ ] 文档对已实现、部分实现和明确不支持的能力保持诚实一致。

## 一句话收口

> **agent4j 下一阶段不是继续把“能做的事”加多，而是把“不能越权、不能乱花钱、不能重复副作用、不能恢复错、不能泄露数据”变成 Runtime 默认保证。**
