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

- [ ] 新建不可变 `RunContext`，至少包含：
  - [ ] `runId`
  - [ ] `tenantId`
  - [ ] `userId`
  - [ ] `agentId`
  - [ ] `workflowId`
  - [ ] `channelId`
  - [ ] `traceId`
  - [ ] `parentRunId`
  - [ ] `deadline`
  - [ ] `cancellationToken`
  - [ ] `budgetView`
  - [ ] `idempotencyScope`
  - [ ] `identity`
  - [ ] `capabilities`
- [ ] 明确哪些字段由系统生成，哪些字段允许入口适配器提供。
- [ ] 禁止下游通过普通字符串随意伪造 `tenantId`、`runId` 和身份。
- [ ] 为敏感字段提供安全日志格式，默认不输出完整用户身份和 Token。

### 1.2 贯穿核心执行边界

- [ ] `Agent.run` / `Agent.stream` 接受或绑定 `RunContext`。
- [ ] `AgentLoop` 在每个 Step 中传播同一个上下文。
- [ ] `ModelClient` 能获得当前 RunContext，不依赖单一 ThreadLocal。
- [ ] `ToolExecutor` 接收上下文并将身份、预算、幂等范围传给 Tool。
- [ ] `WorkflowNode` / `NodeContext` 与 `RunContext` 建立一一对应关系。
- [ ] Memory 查询和写入使用上下文中的 tenant、identity、scope 白名单。
- [ ] Sandbox 使用上下文中的 runId、tenantId、deadline 和 capability。
- [ ] MCP/A2A/Webhook 适配器保留 parent/trace/run 关联关系。

### 1.3 统一生命周期事件

- [ ] 建立 `RunStarted`、`StepStarted`、`StepCompleted`、`RunPaused`、`RunResumed`、`RunCanceled`、`RunFailed`、`RunCompleted` 事件。
- [ ] 为 Model、Tool、Memory、Approval、Sandbox 增加开始/结束/失败事件。
- [ ] 每个事件包含 `runId`、`stepId`、`attempt`、时间、耗时和失败分类。
- [ ] 明确事件是事实事件还是旁路遥测，禁止把指标对象当成执行真相。
- [ ] 增加事件版本号，为后续持久化和跨进程消费留接口。

### 1.4 取消和 Deadline

- [ ] 新建统一 `CancellationToken` 或等价接口。
- [ ] Model、Tool、Workflow、Sandbox、Scheduler 都支持上下文取消。
- [ ] Deadline 到期统一转为结构化 `TIMEOUT`，不依赖字符串前缀推断。
- [ ] 取消不应被错误地记录成业务失败。
- [ ] 验证 streaming、HTTP 调用、子进程和并行分支是否真的停止。

## 验收测试

- [ ] 同一个 Run 跨线程、并行节点、异步回调后，所有事件仍能关联同一 `runId`。
- [ ] 不同租户不能通过修改上下文字符串访问彼此 Memory 或 Tool。
- [ ] Deadline 到期后，Model、Tool、Workflow、Process 均进入可观测的终止状态。
- [ ] 取消一个并行 Workflow，所有子分支都收到取消信号。
- [ ] 不依赖 ThreadLocal 也能完成异步回调的 Trace 关联。

## 完成定义

- [ ] 任意一次执行只认一个统一 `RunContext`。
- [ ] 下游组件不再自己发明 `runId`、tenant 或 trace 传递方式。
- [ ] 取消、超时、权限和预算都可以从上下文进入执行边界。

---

## Stage 2：Tool Contract 与 Secure-by-Default 装配

**优先级：P0｜依赖 Stage 1｜建议时间盒：3–5 天**

## 目标

让 Tool 从“可以执行的 JSON 函数”升级为带契约、能力、风险和治理元数据的运行时资源；让业务方不容易装配出无治理 Agent。

## ToDo

### 2.1 结构化 Tool Definition

- [ ] 新建结构化 `ToolDefinition`，至少包含：
  - [ ] `name`
  - [ ] `description`
  - [ ] `inputSchema`
  - [ ] `outputSchema`
  - [ ] `version`
  - [ ] `sideEffectLevel`
  - [ ] `requiredCapabilities`
  - [ ] `timeout`
  - [ ] `maxInputBytes`
  - [ ] `maxOutputBytes`
- [ ] 保留旧 `Tool` API 的兼容适配器。
- [ ] 为 Schema 增加版本和指纹，Trace 中记录实际使用的版本。
- [ ] 明确未知字段策略：拒绝、忽略或透传，不能由每个 Tool 自行决定。

### 2.2 统一参数和结果校验

- [ ] 在 Tool 执行前统一解析 JSON。
- [ ] 使用统一 JSON Schema Validator 校验类型、必填字段、枚举、长度和深度。
- [ ] 限制参数总大小、字符串长度、数组长度和 JSON 嵌套深度。
- [ ] 对输出提供可选 Schema 校验。
- [ ] 将参数错误统一归类为 `INVALID_TOOL_ARGUMENTS`。
- [ ] Tool 不应再用任意字符串表达参数错误。
- [ ] 对 MCP、Plugin、内置 Tool 使用同一套校验链。

### 2.3 Tool 结果封装

- [ ] 新建统一 `ToolResult`，区分成功、业务拒绝、系统失败、超时、取消和结果被净化。
- [ ] 保留原始错误分类，不把所有异常转成 `Tool execution failed`。
- [ ] 记录 Tool 参数 Hash，不默认记录完整敏感参数。
- [ ] 记录结果大小、摘要和 redaction 状态。
- [ ] 规定模型可见结果与审计可见结果的差异。

### 2.4 Secure Agent Builder

- [ ] 新建 `SecureAgentBuilder` 或 `ProductionAgentFactory`。
- [ ] 默认使用治理 Tool Executor，而不是裸 `DefaultToolExecutor`。
- [ ] 默认拒绝未知 Tool。
- [ ] 有副作用 Tool 默认需要 Permission；高风险 Tool 默认需要 Approval。
- [ ] 默认接入 Input Guardrail、Tool Governance、Output Guardrail。
- [ ] 默认接入 Result Sanitizer、Audit、Metrics、Budget 和 Trace。
- [ ] 默认限制最大 Steps、最大 Tool Calls、最大单次结果大小。
- [ ] 对“无治理装配”增加显式 `UnsafeAgentBuilder`，名称中必须有 Unsafe。
- [ ] 在日志和启动信息中打印当前 Runtime Profile：secure / unsafe / test。

### 2.5 装饰器顺序契约

- [ ] 固化 Model Client 装饰器顺序及其原因。
- [ ] 固化 Tool Executor 装饰器顺序：校验、权限、审批、限流、执行、净化、审计、观测。
- [ ] 为错误顺序写集成测试，例如 Observing 放在 Governed 外层才能看见拒绝。
- [ ] 禁止不同模块各自复制一套“看起来类似”的治理链。

## 验收测试

- [ ] 缺少必填参数时 Tool 不执行，且产生 `INVALID_TOOL_ARGUMENTS` 事件。
- [ ] 未授权 Tool 不执行，且不会产生“执行成功”指标。
- [ ] Approval 未完成时 Run 进入 `WAITING`，而不是阻塞线程。
- [ ] Tool 输出包含注入内容时，模型只看到净化后的结果。
- [ ] 业务方只使用 `SecureAgentBuilder` 时，不需要手工拼 8 层装饰器。
- [ ] 使用裸 `DefaultToolExecutor` 执行有副作用 Tool 时，测试明确失败或被标记为 Unsafe。

## 完成定义

- [ ] Tool Contract 结构化并统一校验。
- [ ] 安全装配是默认路径，裸执行是显式危险路径。
- [ ] Tool 的失败、拒绝、审批和净化状态可以被统一观测。

---

## Stage 3：Durable Execution、幂等与持久化审批

**优先级：P0｜依赖 Stage 1、Stage 2｜建议时间盒：5–8 天**

## 目标

把“暂停点快照”升级为可解释、可重试、可去重的 Durable Execution，确保进程崩溃不会重复外部副作用。

## ToDo

### 3.1 Run Store 与 Checkpoint 版本

- [ ] 新建持久化 `RunStore`，保存 Run 元数据、状态、版本和最后事件位置。
- [ ] `Checkpoint` 增加 schema version。
- [ ] 记录 Workflow Definition ID、版本和 Hash。
- [ ] 记录 Agent/Prompt/Tool/Model 版本三元组或等价版本信息。
- [ ] 将 checkpoint、事件位置和 Run 状态建立一致性关系。
- [ ] `FileCheckpointStore` 使用临时文件、fsync 和 atomic rename。
- [ ] 对 `runId` 做合法字符校验，禁止直接拼接任意路径。
- [ ] 为 PostgreSQL 或其他生产后端设计 migration，而不是启动时隐式改表。

### 3.2 Step History 与副作用账本

- [ ] 为每个节点记录 `visitOrdinal`、attempt、开始、结束和结果摘要。
- [ ] 记录 Tool Call 的幂等键和参数 Hash。
- [ ] 新建 `SideEffectLedger` 或等价接口。
- [ ] 外部副作用成功后先落结果账本，再允许 Run 继续推进，或定义清晰的反向恢复语义。
- [ ] 恢复时先查历史结果，命中则复用，不重新调用外部系统。
- [ ] 区分可重试 Activity、不可重试 Activity 和需人工确认 Activity。
- [ ] 明确至少一次、至多一次、恰好一次的真实语义，禁止笼统写“幂等”。

### 3.3 原子恢复

- [ ] 将 Run 状态、Checkpoint 版本和最后完成节点用乐观锁保护。
- [ ] 两个 Worker 同时恢复同一 Run 时只能一个获得 Lease。
- [ ] 恢复失败可继续从上一个稳定 checkpoint 重试。
- [ ] 版本不匹配时拒绝静默恢复，返回 `DEFINITION_VERSION_MISMATCH`。
- [ ] 支持恢复前的人工诊断：当前节点、最后事件、上次错误、已完成副作用。

### 3.4 持久化 Approval Protocol

- [ ] 新建 `ApprovalRequest`，包含 approvalId、runId、stepId、toolCallHash、请求人、风险级别、过期时间。
- [ ] 新建 `ApprovalDecision`，包含决定人、决定时间、理由和版本。
- [ ] Approval ID 必须幂等，重复提交不能产生多次决定。
- [ ] Approval 等待期间 Run 状态为 `WAITING_APPROVAL`。
- [ ] 重启后能扫描待审批 Run 并恢复。
- [ ] 审批过期、拒绝、撤销和重复审批有独立失败语义。
- [ ] Workflow Approval 与 Tool Approval 复用同一持久化协议。

### 3.5 Scheduler 对接

- [ ] Scheduler 只调度持久化 Run，不以 JVM 内存 active map 作为唯一真相。
- [ ] 启动扫描 WAITING/RUNNING 恢复候选。
- [ ] 增加任务 Lease、租约过期和抢占规则。
- [ ] 事件恢复具备幂等消费和重复消息去重。
- [ ] 异步队列满时有 backpressure 和明确拒绝事件。

## 验收测试

- [ ] 节点完成后立刻 kill-9，恢复不重复已完成的副作用。
- [ ] 在审批等待期间 kill-9，重启后可继续审批，不重复发起审批。
- [ ] 两个 Worker 同时恢复同一 Run，只有一个实际执行。
- [ ] Workflow 定义版本变化后，恢复被显式拒绝而不是静默错跑。
- [ ] Checkpoint 文件在进程崩溃中不会留下不可解析的半文件。
- [ ] 重复事件、重复 webhook、重复 callback 不重复推进状态。

## 完成定义

- [ ] 能够解释任何一次恢复为什么从某个节点继续。
- [ ] 已完成副作用有历史凭据，恢复不会盲目重放。
- [ ] Approval、Run、Checkpoint、Scheduler 共享持久化生命周期。

---

## Stage 4：真正的 Sandbox 安全边界

**优先级：P0｜依赖 Stage 1、Stage 2｜建议时间盒：5–10 天**

## 目标

明确 ClassLoader/Process 只是执行层级，提供可选的 OS 级隔离，避免把 ProcessSandbox 误宣传成安全沙箱。

## ToDo

### 4.1 修复当前 ProcessSandbox 边界

- [ ] `className` 只允许合法 Java 标识符，禁止路径片段和分隔符。
- [ ] 所有 workspace 路径 canonicalize 后做 root containment 校验。
- [ ] 临时目录使用随机不可预测 ID，并限制权限。
- [ ] 环境变量采用 allowlist，不继承完整宿主环境。
- [ ] 明确 stdin、stdout、stderr 最大缓冲区和截断策略。
- [ ] 超时后递归清理整个 child process tree。
- [ ] 明确子进程的工作目录、用户、权限和文件可见性。
- [ ] 将 ProcessSandbox 的限制写进公开 `limitations.md`。

### 4.2 Sandbox Policy

- [ ] 将风险等级映射为可执行策略，而不是只记录枚举。
- [ ] 定义每个 Tier 的文件、网络、进程、CPU、内存、时间和输出限制。
- [ ] 默认禁止网络。
- [ ] 默认只读根文件系统，workspace 单独挂载可写目录。
- [ ] 高风险执行必须经过人工审批或升级到更强 Sandbox。
- [ ] 升级预算与租户、RunContext、成本预算关联。
- [ ] Sandbox 报告明确区分“被策略拦截”和“执行失败”。

### 4.3 OS 级实现

- [ ] 新建 Docker Sandbox Adapter，先覆盖 Linux CI。
- [ ] 评估 gVisor / Firecracker 作为高风险生产 Tier。
- [ ] 配置非特权 UID/GID。
- [ ] 配置 seccomp、capability drop、cgroup CPU/内存/IO。
- [ ] 配置网络 namespace 和域名 allowlist。
- [ ] 配置 read-only rootfs 和 workspace mount。
- [ ] 记录镜像 Digest、Sandbox Policy 版本和运行结果。
- [ ] 明确 macOS 本地开发与 Linux 生产执行的差异。

### 4.4 红队和逃逸测试

- [ ] 路径穿越写文件。
- [ ] 读取宿主环境变量。
- [ ] 读取 workspace 外文件。
- [ ] 访问网络和 metadata endpoint。
- [ ] 启动子进程和 fork 炸弹。
- [ ] CPU、内存、磁盘、stdout 洪泛。
- [ ] kill-9、超时、OOM、容器退出和残留清理。
- [ ] 所有攻击都必须有“阻断/未阻断/不适用”的诚实报告。

## 完成定义

- [ ] 文档明确区分 ClassLoader、Process、Container、gVisor/microVM 的安全等级。
- [ ] 生产高风险路径默认使用 OS 级 Sandbox。
- [ ] Sandbox 不能访问 allowlist 外的文件和网络。
- [ ] 所有进程、容器、临时目录都能在超时和取消后清理。

---

## Stage 5：上下文、Memory 与敏感数据治理硬化

**优先级：P1｜依赖 Stage 1、Stage 2、Stage 3｜建议时间盒：4–7 天**

## 目标

把已有 Memory 能力从“数据结构完整”推进到“租户可控、可删除、可审计、不会泄露”。

## ToDo

### 5.1 Context Budget 统一接线

- [ ] Model Request 边界统一计算系统提示、Tool Definition、历史、Memory、输出 headroom。
- [ ] 超预算时按明确优先级裁剪，不允许每个 Profile 自己截断。
- [ ] 记录裁剪原因、被裁剪来源、前后 token/字符数量。
- [ ] 将 cachedTokens、promptTokens、completionTokens 统一纳入成本和评估。
- [ ] 验证 compaction 与 prompt cache 前缀稳定性的交互。

### 5.2 Memory 访问治理

- [ ] 所有 Memory 读写带 tenant、identity、scope 和 purpose。
- [ ] Memory 查询写入审计：谁、何时、查了什么 scope、返回多少条。
- [ ] 高敏感 Memory 支持字段级脱敏。
- [ ] 用户删除、租户删除和 retention 到期能联动删除或匿名化。
- [ ] Memory 写入记录来源、模型版本、审批状态和人工修改人。
- [ ] 补 `MemoryAdmin` 更新字段保真测试，防止更新时丢失 embedding、lifecycle、双时间轴字段。

### 5.3 数据保护

- [ ] 为 Audit、Trace、Trajectory、Checkpoint、Memory 提供统一 Redaction Policy。
- [ ] 增加 Secret/PII Masker，并允许租户自定义规则。
- [ ] 明确原文、摘要、Hash、脱敏文的保存策略。
- [ ] 支持加密存储适配器或由宿主提供加密边界。
- [ ] 规定导出、Replay、DPO 数据的权限和保留期限。
- [ ] 记录数据删除的传播结果，不能只删主表。

## 完成定义

- [ ] Memory 和 Trace 不会默认把敏感原文无边界写入日志或导出文件。
- [ ] 所有 Memory 访问都可回溯到 RunContext 和身份。
- [ ] 更新 Memory 不丢失新字段，删除和 retention 有可验证结果。

---

## Stage 6：Provider、MCP、A2A 与 Plugin 生产化

**优先级：P1｜依赖 Stage 1、Stage 2、Stage 3｜建议时间盒：5–10 天**

## 目标

把已有协议和扩展能力从“自家两端能跑”提升到“有认证、可恢复、可互操作”。

## ToDo

### 6.1 Model Provider Contract

- [ ] 建立所有 Provider 共用的 Contract Test。
- [ ] 覆盖同步、流式、Tool Call、Structured Output、Usage、Reasoning、超时、取消和错误映射。
- [ ] 优先复核 Anthropic Streaming Tool Use 的完整累积链路。
- [ ] 对 OpenAI-compatible 的不同厂商 extra body 和响应差异做 capability 声明。
- [ ] Provider 错误统一分类：认证、限流、参数、服务端、网络、解析、取消。
- [ ] 实现 Retry-After、Circuit Breaker、Credential Rotation 接口。
- [ ] Provider 资源和 Key 不写入普通日志。

### 6.2 MCP

- [ ] 设计 HTTP/SSE Transport Adapter。
- [ ] 增加远程 MCP Server allowlist 和信任等级。
- [ ] 增加 OAuth 或由宿主提供认证适配器。
- [ ] MCP Tool 接入统一 ToolDefinition 和 Schema Validator。
- [ ] 处理 resources、prompts、sampling、elicitation 的能力声明。
- [ ] MCP 连接重启、退避、冷却和任务取消与 RunContext 对齐。
- [ ] 增加第三方 MCP Server 互操作测试。

### 6.3 A2A

- [ ] Agent Card 增加身份、能力和版本可信来源。
- [ ] HTTP 请求增加认证、授权和签名验证。
- [ ] Push Notification 增加签名和重放保护。
- [ ] Task Store 从内存升级为可持久化接口。
- [ ] 增加 Task Lease、续跑、取消、过期和去重。
- [ ] 跨实例恢复 A2A Task。
- [ ] 用第三方实现做真实互操作，不只验证自家 Client/Server 方言一致。

### 6.4 Plugin

- [ ] 明确当前 SPI Plugin 的安全边界，不再暗示隔离。
- [ ] 设计外部 JAR ClassLoader 和版本隔离。
- [ ] 增加插件签名、Checksum 和来源校验。
- [ ] Plugin Manifest 声明 Tool、Model、Memory 和 Security 权限。
- [ ] 加载失败时回滚已注册 Tool、线程、连接和资源。
- [ ] `PluginRegistry` 做并发安全和命名空间隔离。
- [ ] 卸载后验证旧 Tool 不再可调用。

## 完成定义

- [ ] Provider 有统一 Contract Test。
- [ ] MCP/A2A 有认证、授权、签名或明确的宿主接入边界。
- [ ] Plugin 的加载、失败回滚和卸载行为可测试。
- [ ] 至少完成一次第三方 A2A 或 MCP 互操作验证。

---

## Stage 7：标准 Observability、成本与在线评估

**优先级：P1｜依赖 Stage 1、Stage 2、Stage 3｜建议时间盒：4–7 天**

## 目标

让成本、质量、安全和延迟指标进入真实运营系统，而不是只存在于 JVM 内存和单元测试。

## ToDo

### 7.1 Trace 标准化

- [ ] 增加 OpenTelemetry Span Adapter。
- [ ] Model、Tool、Workflow、Memory、Sandbox、MCP、A2A 都创建有层级关系的 Span。
- [ ] 使用显式 Context Propagation，ThreadLocal 只作为兼容便利层。
- [ ] 对 prompt、tool args、tool result 默认脱敏或只记录 Hash/摘要。
- [ ] 记录 model/tool/prompt/workflow/sandbox 版本。
- [ ] 允许按 tenant、agent、run、错误类型查询。

### 7.2 Metrics 和成本

- [ ] 增加 Micrometer/Prometheus Adapter。
- [ ] 增加持久化 Run Registry 和 Metrics Sink。
- [ ] Model 和 Tool boundary 自动接入预算，不依赖业务方主动调用 `requireBudget`。
- [ ] 支持 Run、Agent、Tenant、Channel、Provider 五个维度的预算。
- [ ] 区分预估成本、实际成本、缓存命中成本和失败成本。
- [ ] 增加 dropped event、sink failure、orphan event 指标。
- [ ] 增加 P50/P95/P99 延迟、Tool 拒绝率、恢复率、取消率、重复副作用率。

### 7.3 在线评估

- [ ] 定义 task completion rate、cost per task、latency、safety violation、fallback rate、memory hit rate。
- [ ] 支持线上采样，不默认保存所有敏感内容。
- [ ] 支持 shadow run 和版本对照。
- [ ] Prompt、Model、Tool、Workflow 版本自动关联评测结果。
- [ ] 增加 drift detection 和阈值告警。
- [ ] 将离线黄金集、在线指标和红队结果放入同一评估报告。

### 7.4 运营闭环

- [ ] 指标异常可以定位到具体 Run、Step、Tool、Provider 和版本。
- [ ] 告警包含建议动作，而不是只有数值。
- [ ] 预算超限、Sandbox 升级、Guardrail 命中、A2A 拒绝都能进入同一事件总线。

## 完成定义

- [ ] 能从 Prometheus/OTel 或持久化后端看到一次完整 Agent Run。
- [ ] 预算超限会自动阻断，而不是只记录一条日志。
- [ ] 线上能回答“成本上涨来自哪个模型、哪个 Tool、哪个 Prompt 版本”。

---

## Stage 8：分布式 Runtime 与发布质量

**优先级：P1/P2｜依赖 Stage 3、Stage 6、Stage 7｜建议时间盒：7–14 天**

## 目标

让 agent4j 能从单 JVM 学习型 Runtime 进入可部署的多实例 Runtime。

## ToDo

### 8.1 分布式执行

- [ ] RunStore 使用数据库或可靠外部存储作为真相源。
- [ ] Scheduler 使用外部队列或可持久化任务表。
- [ ] Worker 使用 Lease、Heartbeat、抢占和重试。
- [ ] 支持跨实例 Resume、Cancel、Approval Callback。
- [ ] 明确并发执行、顺序执行和分支合并语义。
- [ ] 增加网络分区、Worker 崩溃、数据库短暂不可用测试。
- [ ] 增加 backpressure、队列容量和租户级隔离。

### 8.2 Spring Boot Starter 生产 Profile

- [ ] Starter 提供 `secure`、`test`、`unsafe` 明确 Profile。
- [ ] Secure Profile 自动接入 RunContext、治理链、Budget、Trace、Audit。
- [ ] 启动时检查高风险配置：裸 Tool、无 Sandbox、无持久化 Store、无密钥脱敏。
- [ ] 提供健康检查：Model、Store、Scheduler、MCP、A2A、Sandbox。
- [ ] 提供优雅停机：停止接收新 Run，等待或持久化已有 Run。
- [ ] 提供运行时配置版本和热更新边界。

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

- [ ] 两个 Runtime 实例可以安全接管同一个等待中的 Run。
- [ ] 发布包具备 SBOM、兼容性报告、限制清单和集成测试结果。
- [ ] 新用户通过 Starter 可以走 Secure Profile，而不是自己拼装安全链。

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
- [ ] **第二件：** Stage 1.1–1.4，落 `RunContext`、取消和统一生命周期事件。
- [ ] **第三件：** Stage 2.1–2.4，落结构化 Tool Contract 和 `SecureAgentBuilder`。
- [ ] **第四件：** Stage 3.1–3.4，补 Durable RunStore、幂等账本和持久化 Approval。
- [ ] **第五件：** Stage 4.1，先修现有 ProcessSandbox 的路径、环境变量和子进程清理问题。

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

- [ ] 任意 Run 都有统一且可传播的 `RunContext`。
- [ ] Tool 有结构化 Schema、输入限制、输出限制和统一错误语义。
- [ ] 有副作用 Tool 默认经过 Permission、Approval、Audit 和 Sanitizer。
- [ ] Agent 崩溃恢复不会盲目重复已完成副作用。
- [ ] Approval、Checkpoint、Run、Scheduler 可以跨进程恢复。
- [ ] 高风险代码执行使用真正的 OS 级 Sandbox。
- [ ] Memory、Trace、Audit、Checkpoint 有敏感数据治理。
- [ ] Model、Tool、Workflow、Memory、Sandbox 的成本和 Trace 可关联。
- [ ] MCP/A2A 有认证、授权、签名、去重或清晰的宿主边界。
- [ ] CI 有集成、安全、兼容性和发布质量门槛。
- [ ] 文档对已实现、部分实现和明确不支持的能力保持诚实一致。

## 一句话收口

> **agent4j 下一阶段不是继续把“能做的事”加多，而是把“不能越权、不能乱花钱、不能重复副作用、不能恢复错、不能泄露数据”变成 Runtime 默认保证。**
