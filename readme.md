# Java Agent Framework

> A persistent, observable, governable, hot-pluggable Java Agent Runtime.
>
> **Learning project**: 通过构建一个 Java Agent Runtime，掌握 Agent 架构设计的全貌。

## 当前阶段：Stage 13 ✅ 已完成（上层产品搭建层：声明式 Agent 定义，2026-08-23）—— 下一步 Stage 14 RL 轨迹产出层

> Stage 1-12 已完成（2026-08-16 ~ 08-22），426 测试全绿（含 Stage 12 完成后自查修复，详见架构笔记 §13 审查记录）。README 的 ✅ 相对**各阶段架构笔记的简化验收**，不是 18 周规划全文。
> Stage 13 设计蓝图：[notes/architecture-stage-13.md](notes/architecture-stage-13.md)（新增 agent-product 模块：声明式定义 / 模板 / 配置驱动 Tool / Prompt 管理 / Webhook / DAG / 多租户）
> Stage 3 插件 = SPI + Tool 热插拔（无 JAR ClassLoader / 无多版本共存）。
> Stage 4 沙箱 = ClassLoader + Process（无 Docker / WASM / 资源池）。
> Stage 10 MCP = stdio v1 + 真实官方 Server 互通 + 进程管理自愈（SSE 留 v2）。
> Stage 11 编排 = 静态并行派发 + A2A 进程内实现（LLM 驱动分派 / HTTP 传输留 v2）。
> 多模态接入说明：[notes/architecture-multimodal.md](notes/architecture-multimodal.md)
> Stage 12 设计文档：[notes/architecture-stage-12.md](notes/architecture-stage-12.md)（新增 agent-channel 模块蓝图）
> Stage 11 设计文档：[notes/architecture-stage-11.md](notes/architecture-stage-11.md)
> Stage 10 设计文档：[notes/architecture-stage-10.md](notes/architecture-stage-10.md)
> Stage 9 设计文档：[notes/architecture-stage-9.md](notes/architecture-stage-9.md)
> Stage 8 设计文档：[notes/architecture-stage-8.md](notes/architecture-stage-8.md)
> Stage 7 设计文档：[notes/architecture-stage-7.md](notes/architecture-stage-7.md)
> Stage 6 设计文档：[notes/architecture-stage-6.md](notes/architecture-stage-6.md)
> Stage 5 设计文档：[notes/architecture-stage-5.md](notes/architecture-stage-5.md)

### 已完成

- [x] Maven 多模块项目骨架（agent-core / agent-model / agent-plugin / agent-sandbox / examples）
- [x] 核心数据结构：`ChatMessage` / `ContentPart` / `ModelRequest` / `ModelResponse` / `ToolCall` / `StreamEvent`
- [x] 核心接口：`ModelClient` / `Tool` / `ToolRegistry` / `ToolExecutor` / `Agent` / `AgentLoop`
- [x] 默认实现：`InMemoryToolRegistry` / `DefaultToolExecutor` / `ReActAgentLoop` / `SimpleAgent`
- [x] Mock 实现：`MockModelClient`（脚本模式 + 规则模式）/ `EchoTool` / `CurrentTimeTool`
- [x] **OpenAiModelClient**：Java HttpClient + SSE 流式，兼容 OpenAI / Azure / Ollama / 火山方舟
- [x] **AnthropicModelClient**：Claude Messages API 适配器
- [x] **RetryModelClient** 装饰器：指数退避 + 错误码分类
- [x] **TimeoutModelClient** 装饰器：CompletableFuture.orTimeout
- [x] **FallbackModelClient** 装饰器：多级链式降级
- [x] **StructuredOutputModelClient** 装饰器：JSON schema 强制 + 验证重试
- [x] **插件系统**：`Plugin` / `ToolPlugin` / `PluginRegistry` / `PluginManager`
    - Java SPI 发现机制（ServiceLoader）
    - 插件生命周期：load -> unload -> reload
    - 故障隔离：一个插件失败不影响其他
- [x] **Agent 自进化**：4 个插件管理 Tool（inspect / list / load / unload）
    - 模型在对话中自我管理能力
- [x] **沙箱系统**：`Sandbox` / `ClassLoaderSandbox` / `ProcessSandbox`
    - 内存编译（Java Compiler API，零磁盘 IO）
    - ClassLoader 隔离（拦截 File/Runtime/ProcessBuilder/Network/反射）
    - 进程隔离（ProcessBuilder + 超时 + 工作目录限制）
    - 超时自动终止（死循环 2 秒被 kill）
- [x] 单元测试：602 个（23 core + 24 model + 29 插件 + 14 沙箱 + 34 Workflow + 27 调度器 + 66 记忆 + 46 安全 + 55 MCP + 45 编排 + 82 channel + 157 product），全绿
- [x] 示例：`MockAgentExample` / `DecoratedModelClientExample` / `PluginExample` / `PluginSelfModificationExample` / `SandboxExample` / `SandboxAgentExample` / `WorkflowSupportFlowExample` / `CheckpointExample` / `SchedulerExample` / `LlmDrivenSchedulerExample` / `MemoryExample` / `CompressionExample` / `ChannelMemoryExample` / `SecurityExample` / `InjectionDefenseExample` / `McpExample` / `McpRealServerExample`（连官方 filesystem Server）/ `ManagedMcpExample`（崩溃自愈）/ `MultimodalExample`（2 内部 + 1 外部 A2A 编排）/ `ChannelAgentExample`（频道共享+接力+身份+看板）/ `AmbientExample`（Ambient 主动模式+噪音闸）
- [x] 内容产出（08-14 ~ 08-17）：公众号发布 5 篇（DeepSeek Harness 架构拆解 / 九模块自进化 / Java SPI 自进化 / Agent
  沙箱技术全景 / java-agent-06 进程级沙箱原理）
- [x] **Workflow 图引擎**（agent-workflow 模块，Stage 5）：6 核心抽象（`Workflow` 不可变图定义 / `WorkflowNode` / `Edge`
  条件路由 / `WorkflowState` 黑板 / `GraphRuntime` 解释器 / `ExecutionResult`）+ 7 种节点（`ActionNode` / `AgentNode` 复用
  agent-core Agent / `ToolNode` / `RouterNode` / `HumanApprovalNode` / `ParallelNode` fork-join / `JoinPolicy`）+
  治理（节点级 `RetryPolicy`、`onError` 失败路由、maxSteps 环保护、路由二义性/死端检测）+ 可插拔 `ApprovalService`
  （Mock/Console）+ `StepRecord` trace（Stage 14 trajectory 数据源）
- [x] **Memory 与共享记忆治理**（agent-memory 模块，Stage 8）：三横一纵记忆模型（Working=AgentState /
  Session=ChatSession / Long-term=MemoryStore + MemoryScope 一纵：agent/user/session/task/channel，共享=scope
  取值不另造系统）+ 记忆流水线（写入端 `MemoryExtractor` -> `MemoryPolicy` 闸门 -> `MemoryStore` / 读取端
  `MemoryRetriever` -> `MemoryContextBuilder`，轮中 `ContextCompressor` pi 式 compaction）+ 16 核心抽象
  （`MemoryEntry` / `MemoryScope` / `MemoryProvenance` / `MemoryType` / `MemoryStatus` / `MemoryQuery` /
  `MemoryStore` / `InMemoryMemoryStore` / `MemoryRetriever` / `MemoryExtractor` / `MemoryPolicy` / `MemoryAdmin` /
  `ContextBuilder` / `PassthroughContextBuilder` / `ContextBudget` / `ContextCompressor`）+ `CompressingContextBuilder`
  / `MemoryContextBuilder` + `MemoryTools`（save_memory / search_memory 模型自决存取）+ 治理闭环（channel scope
  默认 PENDING_REVIEW + `MemoryAdmin` approve/reject/supersede/update/delete/setTtl + 污染防御三道闸：
  importance 门槛 + 频控 + supersede 不物理删）+ `AgentConfig`/`ReActAgentLoop` 挂接 `ContextBuilder`（向后兼容，
  null 透传）+ 3 验收示例（`MemoryExample` 多轮记忆 / `CompressionExample` 压缩 / `ChannelMemoryExample` 频道治理）
- [x] **Tool Governance 与安全审计**（agent-security 模块，Stage 9）：治理四件套（执行前 `PermissionChecker`
  权限三档 AUTO/REQUIRES_APPROVAL/DENY + `ToolApprovalService` 审批 / 执行后 `ResultSanitizer` 注入防御 +
  `AuditLogger` 全链路审计含拒绝事件）+ `GovernedToolExecutor` 装饰器包装 `DefaultToolExecutor`（不替换，向后兼容
  null 透传）+ `ToolPolicy` 策略（运行时可改权限）+ `ConsoleApprovalService`（autoApprove/autoReject/console/callback
  四模式）+ `InjectionPattern` 三特征（角色伪造/指令覆盖/敏感外发）+ `DefaultResultSanitizer` 三净化策略
  （SANITIZE 替换/TRUNCATE 截断/BLOCK 整体替换）+ `SimpleRateLimiter` 计数窗口限流（可选）+ `AuditEvent` 全状态
  （APPROVED/DENIED/EXECUTED/FAILED/SANITIZED）+ 2 验收示例（`SecurityExample` 权限三档+审批+审计 /
  `InjectionDefenseExample` 三净化策略）
- [x] **MCP 与外部生态集成**（agent-mcp 模块，Stage 10）：MCP 是 Agent 的 USB 接口（统一协议连上 Server 自动
  获得工具，不再写 Java 注册）· `McpClient` 协议层（connect initialize 握手 / listTools 发现 / callTool 调用 /
  disconnect 关闭）· `McpTransport` 接口 + `StdioTransport` 子进程实现（v1，SSE v2）· JSON-RPC 2.0 三 record
  （`JsonRpcRequest`/`JsonRpcResponse`/`JsonRpcNotification`）· `McpToolAdapter` implements `Tool`（D1 治理透明性：
  MCP 工具注册后被 `GovernedToolExecutor` 自动治理，权限/审批/审计/净化零额外代码）· `McpServerDescriptor` 连接配置 +
  `McpToolSchema` 工具定义 · A2A 协议 v1 接口引入（`AgentCard`/`A2ATask`/`A2AMessage`/`A2AClient`，编排留 Stage 11）
  · `McpExample` 验收示例（连接 Mock MCP Server + 发现 echo 工具 + 治理执行 + 审计链 APPROVED+EXECUTED）
- [x] **多模态接入治理与长任务**（2026-08-22）：读图 `ContentPart` + `SimpleAgent.run(ChatMessage)` + `VisionTool`；生图 `ImageGenerationClient` + Retry/Timeout 装饰器 + `ImageGenerationTool`；生视频默认不阻塞，`GenerationTaskCoordinator` 轮询后 `fire("video-done:{id}")`，`WaitEventNode.fromState` 自动恢复。`ToolPolicy.applyGenerationDefaults()` 三工具默认 REQUIRES_APPROVAL。验收：`MultimodalExample`

- [x] **Multi-Agent 与 A2A 编排**（agent-orchestrator 模块，Stage 11）：统一 Worker 抽象（`AgentWorker` 接口 -- 内外 Agent 无差别，装饰器哲学第三次兑现）+
    `InternalAgentWorker`（包 agent-core Agent，读结构化 `AgentState` 判失败）+ `ExternalAgentWorker`（A2A 委托 + D5 信任降级：`UnaryOperator<String>`
    净化注入，组装层接 Stage 9 `DefaultResultSanitizer`）+ `AgentSupervisor` 编排器（并行派发 wall clock ≈ max / FAIL_FAST 取消短路 /
    BEST_EFFORT 失败隔离 / Worker 级重试与超时）+ `ResultAggregator` 聚合策略（Concat / FirstSuccess）+ skills 路由（注册序确定性 + fail-closed）
    + `McpRestartPolicy` 防风暴 · `InProcessA2AClient`（agent-mcp，补上 Stage 10 遗留的 `A2AClient` 实现：协议数据模型 100% 对齐，传输 v2 换
    HTTP 不动调用方）· `MultiAgentExample` 验收（2 内部 + 1 外部 A2A 三路并行 + 聚合 + 注入净化 + 失败重试全演示）
- [x] **MCP 真实生态与进程管理**（2026-08-22 增强）：`McpRealServerExample` 连官方 `@modelcontextprotocol/server-filesystem` 协议互通
    （握手 3s / 14 工具 / 3 调用全通）· `StdioTransport` stderr drainer（防管道死锁）· `ManagedMcpClient` 自愈装饰器（崩溃检测 ->
    `McpRestartPolicy` 预算 -> 重启重握手 -> 单次重试，强杀后 2.5s 自动复活）· `McpClient` 工厂化 + `reconnect`/`ping`

- [x] **Agent Identity 身份层**（agent-channel 模块，Stage 12 M12.1）：三方身份解析（channelId + userId + agentId ->
    有效身份）· `IdentityResolver` 核心规则：有效权限 = 授予 scope ∩ 发起人频道角色权限（**交集**，绝不是并集或用户全集）·
    fail-closed 五条件（UNKNOWN_AGENT / ACCOUNT_NOT_YET_VALID / ACCOUNT_EXPIRED / USER_NOT_IN_CHANNEL /
    EMPTY_PERMISSION_INTERSECTION，任一命中拒绝启动 Run 并抛 `IdentityResolutionException`）· `IdentityScope` 三维资源范围
    （capabilities / memoryScopes / dataClassifications，字符串形式与 MemoryScope 兼容保持身份层零依赖）·
    `ServiceAccount`（授予 scope + 有效期 + 预算占位 UNLIMITED=-1 留 Stage 18）· `IdentityDecision` 审计双向留痕
    （允许与拒绝都发 decision，denied is intelligence 对齐 Stage 9 D6；`Consumer<IdentityDecision>` sink 注入，
    模块不依赖 agent-security，同 orchestrator D5 边界纪律）· `ResolvedIdentity`（actor = "svc:{accountId}"
    审计归属服务身份而非用户；v1 诚实边界：memoryScopes/dataClassifications 仅授予侧、只 capabilities 走交集）
- [x] **频道共享会话**（agent-channel 模块，Stage 12 M12.2）：`SharedAgentSession` 容器（D1 组合不继承——包任意现有 Agent 零改动入驻，
    频道语义全在容器层不污染 Agent 接口）· `ChannelContext`（成员名单=成员资格 SSOT）+ `ChannelMessage`（说话人归属 + mention 检测，
    `autoDetect` 要求 `@agentId` 后跟分隔符防 `@eng-bots` 误触发）· speak 路由：mention -> 身份解析（M12.1 fail-closed 闸门，
    非成员连进历史都不行）-> `[from userId] text` 进**共享 AgentState**；非 mention -> 只进频道历史不唤醒 Agent · 多人交替 speak
    共享一个对话上下文（B 的请求能看到 A 的发言，测试用 RecordingModelClient 捕获模型实见消息证明）· `channelMemoryContext` 工厂
    （D2：channel scope 挂进 Stage 8 MemoryContextBuilder 检索列表，共享记忆不是新系统；跨频道记忆隔离实证 channel:sales 不泄漏）
- [x] **任务接力与执行可见**（agent-channel collab 包，Stage 12 M12.3）：`ExecutionVisibility` 事件流（D6 推不打轮询：八类
    VisibilityEvent 发布/订阅，listener 异常隔离）· `TaskBoard` 事件流**物化视图**（订阅同一事件流是唯一写入路径，board 与外部订阅者
    同源；复用 Stage 7 `TaskStatus` 状态机不另造枚举）· `ChannelTask` 轻量视图 + `TaskHandoff` 接力审计 record · `handoff` 三件套
    移交（D5：共享 AgentState 不重建 + 注入 `[handoff]` system 交接便签让模型知道接力棒换了手 / channel+task scope 记忆天然共享零动作 /
    board owner 经事件变更；守卫：未知任务/非 owner 移交他人任务/接手人非成员/终态任务全部 IAE fail-fast）· 任务生命周期 API
    （startTask/waitingHuman/resumeTask/completeTask/failTask，每个动作发布事件）· speak 回复后发布 AGENT_REPLIED（团队实时看到 Agent
    在干什么）
- [x] **Ambient 主动模式**（agent-channel ambient 包，Stage 12 M12.4）：`AmbientInstruction` 常驻指令（sealed Trigger =
    SCHEDULED(interval) | OnEvent(eventKey) + condition 判定 + message 产出 + Importance 三级——是"有判断有声音的 Agent 指令"不是哑
    cron 脚本）· `AmbientEngine` 运行器（**默认 disabled 安全默认值**：register 只登记不武装，enable 才挂调度/订阅；管线 = 条件不满足→
    全静音连 digest 都不进 → NoisePolicy 闸 → NOTIFY 以 **AgentIdentity** 推送（actor=agentId 非事件源）+ NOTIFICATION_SENT 进
    事件流全频道可见；sink 异常隔离）· `NoisePolicy` 四道闸（D7：频控防风暴含 CRITICAL 重复 / 分级判定 INFO 永远 digest、WARN+ 实时 /
    每日预算只拦 realtime（digest 不占预算）/ 静默窗口 22-08 跨午夜 WARN 转 digest、**CRITICAL 双豁免**（预算+静默窗——服务挂了凌晨也推）；
    digest 队列 drainDigest 由装配层择机汇总）· `ProactiveNotification` 推送 record（归属 = Agent 服务身份）· **D3 复用诚实边界**：
    Stage 7 EventBroker 回调绑死 RunManager.resume(runId)，Ambient 指令不是 run——复用机制（ScheduledExecutorService + 订阅/触发
    语义）而非绑 run 的实现，统一到 EventBroker 待 v2 支持 非 run 订阅者
- [x] **Stage 12 验收示例与收口**（M12.5）：`ChannelAgentExample`（T0-T3+T5 全景：部署→alice 发起（身份解析+频道记忆
    注入实证——响应主动引用"频道记忆：发布窗口冻结"）→handoff 三件套→bob 接续（"接续 alice 的调研"上下文连续）→看板与
    handoff 审计→carol 成员无角色/stranger 非成员两种 fail-closed 拒绝→SUCCEEDED 终态）· `AmbientExample`（默认关闭零推送→
    enable 事件触发条件判定推送→频控吞重复→静默窗 CRITICAL 突破/WARN 进 digest→定时 INFO 巡检进 digest（**digest=1 正是
    频控对 digest 也生效的实证**）→全部推送 actor=eng-bot）· 规划验收 5 条全过，12/18 阶段，下一步 Stage 13 上层产品搭建层

### Stage 13 完成记录（上层产品搭建层 ✅ 2026-08-23，一天五里程碑）

> 设计蓝图：[notes/architecture-stage-13.md](notes/architecture-stage-13.md) · 新增 `agent-product` 模块，
> 复用 Stage 1-12 全部底座（第二次组装阶段），核心元模式：「定义存名字，注册表存实现」

- [x] M13.1 声明式定义层：AgentDefinition + Parser（YAML/JSON）+ Validator（位置化错误）+ Binder + ProductBootstrapper ✅（agent-product 模块 53 测试全绿，2026-08-23）
- [x] M13.2 模板系统：AgentTemplate（fork 快照语义）+ TemplateRegistry（内置客服 / 知识助手 2 模板）✅（+23 测试，2026-08-23）
- [x] M13.3 配置驱动工具：HttpApiTool（implements Tool，治理免费搭车）+ HttpApiToolFactory ✅（+25 测试含治理接管实证，2026-08-23）
- [x] M13.4 Prompt 管理：PromptManager + PromptVersion（实例级 pin 热切换 + stable/canary 双通道 + 租户路由 + 指针回滚）✅（+28 测试含 pin 实证，2026-08-23）
- [x] M13.5 事件接入 + DAG + 多租户收口：WebhookController（HMAC 验签/幂等/202）+ DagSpec/WorkflowDagCodec/ConditionRegistry（双向转换）+ TenantAgentConfig 覆盖 + ambient 段接线（bindChannel）+ DeclarativeAgentExample/WebhookExample ✅（+28 测试，2026-08-23）
- [ ] 文章：java-agent-02~10 存量草稿按节奏补发（不急）

## 模块结构

```
java-agent-framework/
├── agent-core/          # 核心接口与数据结构（仅 Jackson + SLF4J）
├── agent-model/          # 模型适配器（Mock, OpenAI, Anthropic）
├── agent-plugin/         # 插件系统（SPI 发现 + 热加载/卸载）
├── agent-sandbox/        # 沙箱系统（ClassLoader + 进程隔离）
├── agent-workflow/      # 工作流图引擎（Workflow/GraphRuntime/7 种节点）
├── agent-scheduler/     # 异步任务调度器（定时/事件恢复 + 任务队列）
├── agent-memory/        # 记忆与上下文（三横一纵 + 共享记忆治理）
├── agent-security/      # 工具治理（权限/审批/审计/注入防御）
├── agent-mcp/          # MCP 客户端与 A2A 协议基础（连外部工具服务器）
├── agent-orchestrator/ # 多 Agent 编排（Supervisor/Worker/A2A 桥接）
├── agent-channel/      # 频道级共享 Agent（身份/共享会话/Ambient，Stage 12）
├── agent-product/      # 声明式产品层（YAML 定义/模板/配置工具/Prompt 管理，Stage 13）
├── examples/            # 示例代码
├── notes/               # 学习笔记（按阶段组织）
└── pom.xml              # 父 POM
```

### 后续模块（尚未创建）

```
agent-runtime/           # 阶段 6 已并入 agent-workflow/runtime，不再单独立项
agent-trace-export/      # 阶段 14：RL 轨迹导出
agent-observability/     # 阶段 18：可观测性（含 OpenTelemetry）
```

## 快速开始

> 需要 JDK 17（若 `JAVA_HOME`
> 指向低版本，命令前加 `JAVA_HOME=$(/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home)`）。

```bash
# 编译
cd projects/java-agent-framework
mvn clean compile

# 运行示例（首次需先 mvn install -DskipTests 安装 SNAPSHOT）
mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.WorkflowSupportFlowExample

# 运行测试
mvn test
```

## 核心接口速览

```
ModelClient            # 统一模型调用入口（sync + streaming，含 vision parts）
  └─ MockModelClient   # 不依赖真实 LLM 的测试实现
ImageGenerationClient  # 生图（Retry/Timeout 装饰器）
VideoGenerationClient  # 生视频（submit/status；默认不阻塞 ReAct）

Tool                   # 工具接口（name + schema + execute）
ToolRegistry           # 工具注册表（in-memory 实现）
ToolExecutor           # 工具执行器（错误包装 + 日志）

Agent                  # Agent 入口
AgentConfig            # Agent 静态配置（prompt + model + tools）
AgentState             # Agent 运行时状态（messages + steps + status）
AgentLoop              # ReAct 循环（核心执行逻辑）
  └─ ReActAgentLoop    # 默认实现

Workflow               # 不可变图定义（nodes + edges，定义一次执行 N 次）
WorkflowBuilder        # fluent API（node / edge.when / otherwise / onError）
WorkflowNode           # 节点接口（id + execute）
WorkflowState          # 黑板：全图共享可变状态 + trace
GraphRuntime           # 解释器：START 走到 END（路由 -> 执行 -> 写黑板）
ExecutionResult        # 终态（status + output + error + state）
```

## 设计决策

### 为什么不直接用 LangChain4j / Spring AI？

> 不是重复造轮子，是通过造轮子理解轮子。
>
> Java 生态三大框架（LangChain4j / Spring AI / AgentScope Java）在故障恢复和持久化执行上均有空白。
> 自己实现一遍是理解 Agent 架构全貌的最佳路径。

### 为什么 ToolRegistry 和 ToolExecutor 分开？

- Registry 是元数据（"什么工具存在"）
- Executor 是行为（"如何安全执行"）
- 后续 Executor 会增加 timeout、policy check、audit log、sandbox

### 为什么 AgentState 是 mutable？

- 简化 stage 1-2 的实现
- Stage 6 会增加 snapshot/checkpoint 机制
- Stage 14 会增加 trajectory 导出

## 学习路线

对应 [18 周学习规划](../seven-we-meida/01-inbox/2026-08-11_Java-Agent-Framework学习规划.md)：

| 阶段                  | 模块                       | 状态    |
|---------------------|--------------------------|-------|
| 1. 模型调用层            | agent-core / agent-model | ✅ 完成  |
| 2. 最小 Agent Loop    | agent-core               | ✅ 完成  |
| 3. 插件化与热插拔          | agent-plugin             | ✅ 完成  |
| 4. 沙箱与隔离执行          | agent-sandbox            | ✅ 完成  |
| 5. Workflow Graph   | agent-workflow           | ✅ 完成  |
| 6. State/Checkpoint | agent-workflow/runtime   | ✅ 完成  |
| 7. 异步任务调度器     | agent-scheduler          | ✅ 完成  |
| 8. Memory/记忆治理   | agent-memory             | ✅ 完成  |
| 9. Tool Governance  | agent-security           | ✅ 完成  |
| 10. MCP 集成        | agent-mcp                | ✅ 完成  |
| 11. Multi-Agent    | agent-orchestrator       | ✅ 完成  |
| 12. 频道共享 Agent/Identity/Ambient | agent-channel | ✅ 完成  |
| 13. 上层产品搭建层 | agent-product            | ✅ 已完成 |
