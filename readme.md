# Java Agent Framework

> A persistent, observable, governable, hot-pluggable Java Agent Runtime.
>
> **Learning project**: 通过构建一个 Java Agent Runtime，掌握 Agent 架构设计的全貌。

## 当前阶段：Stage 16 ✅ 全部完成（Tavern Game Profile）—— M16.1~M16.5 五里程碑全过（agent-tavern 111 测试全仓 885 全绿，**零存量改动兑现**；TavernGameExample 全剧本实跑验收：三角色人格 / 关系变化 / 事件触发 / 限幅自愈 / 存档续局 / 完整回放 / GM 审计）—— 上一步 Stage 15 ✅（五里程碑全过，agent-enterprise 89 测试）—— Stage 14 ✅（18 周规划验收 4 条全达成）

> Stage 1-14 已完成（2026-08-16 ~ 08-24）。README 的 ✅ 相对**各阶段架构笔记的简化验收**，不是 18 周规划全文。
> Stage 16 设计蓝图：[notes/architecture-stage-16.md](notes/architecture-stage-16.md)（新增 agent-tavern 模块：角色即 Agent / 世界即黑板 / 影响即工具 / 回合即管线 / 历史即事件流——三类场景同 Runtime 的第二个领域 Profile，**零存量改动** + 三处有意不复用）
> Stage 15 设计蓝图：[notes/architecture-stage-15.md](notes/architecture-stage-15.md)（新增 agent-enterprise 模块：租户与用户域 / 租户隔离 RAG / 角色权限与归属审计 / 成本账本 / 业务任务断点恢复——三类场景同 Runtime 的第一个领域 Profile）
> Stage 15 业务场景学习笔记：[notes/stage-15-business-scenario.md](notes/stage-15-business-scenario.md)（从企业客服退款场景出发，解释为什么通用 Runtime 还不能直接进企业、核心概念、实现映射、8 个设计决策、M15.1~M15.5 验收、工程坑与复盘速答）
> Stage 14 设计蓝图：[notes/architecture-stage-14.md](notes/architecture-stage-14.md)（新增 agent-trace-export 模块：边界捕获记录 / S-A-O-R-D 轨迹模型 / 可插拔奖励 / 确定性采样 / JSONL 契约导出 / 走录回放 / DPO 偏好；与 Mini VERL 闭环的交汇点）
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
> Stage 5 业务场景学习笔记：[notes/stage-5-business-scenario.md](notes/stage-5-business-scenario.md)（从企业退款流程出发，解释为什么需要 Workflow、核心概念、实现映射、设计权衡与复盘速答）

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
- [x] 单元测试：774 个（23 core + 24 model + 29 插件 + 14 沙箱 + 34 Workflow + 27 调度器 + 66 记忆 + 46 安全 + 55 MCP + 45 编排 + 82 channel + 167 product + 73 trace-export + 89 enterprise），全绿
- [x] 示例：`MockAgentExample` / `DecoratedModelClientExample` / `PluginExample` / `PluginSelfModificationExample` / `SandboxExample` / `SandboxAgentExample` / `WorkflowSupportFlowExample` / `CheckpointExample` / `SchedulerExample` / `LlmDrivenSchedulerExample` / `MemoryExample` / `CompressionExample` / `ChannelMemoryExample` / `SecurityExample` / `InjectionDefenseExample` / `McpExample` / `McpRealServerExample`（连官方 filesystem Server）/ `ManagedMcpExample`（崩溃自愈）/ `MultimodalExample`（2 内部 + 1 外部 A2A 编排）/ `ChannelAgentExample`（频道共享+接力+身份+看板）/ `AmbientExample`（Ambient 主动模式+噪音闸）/ `TrajectoryExample`（Stage 14：记录→奖励→采样→JSONL 导出→回放走查）/ `PreferenceAnnotationExample`（Stage 14：同 prompt 双 rollout→Console 标注→DPO preferences.jsonl）/ `EnterpriseAssistantExample`（Stage 15：登录→RAG→工具审批→任务审批断点恢复→租户隔离→预算拒绝全剧本）/ `scripts/consume_trajectory.py`（Python 跨语言消费证明）
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

### Stage 14 规划（RL 轨迹产出层，📐 2026-08-23 定稿）

> 设计蓝图：[notes/architecture-stage-14.md](notes/architecture-stage-14.md) · 新增 `agent-trace-export` 模块，
> 与 AI Infra 主线（Mini VERL）的交汇点：Run 结束不是终点，是数据的起点

- [x] M14.1 记录层：Trajectory/TrajectoryStep（S-A-O-R-D）数据模型 + TrajectoryRecorder + RecordingModelClient/RecordingToolExecutor 边界捕获（State=模型实见消息 post-ContextBuilder）+ RecordingAgent 糖衣（2026-08-24 完成，19 测试全绿）
- [x] M14.2 奖励与导出：RewardSource/RuleReward（可插拔，v1 规则+人工）+ TrajectoryMetadata + SamplingPolicy（hash(runId+seed) 确定性）+ JSONL 标准导出（v1 信封 + golden schema）+ consume_trajectory.py 消费证明（2026-08-24 完成，+25 测试，跨语言消费证明数字逐项一致）
- [x] M14.3 采样回放与 workflow 适配：TrajectoryReplayer/ReplayView（走录不重演 + 完整性校验）+ WorkflowTrajectoryAdapter（兑现 StepRecord javadoc 承诺）（2026-08-24 完成，+14 测试）
- [x] M14.4 人工反馈与收口：PreferencePair/TrajectoryPairBuilder/DpoExporter/ConsoleAnnotator（双 rollout → DPO JSONL）+ 2 验收示例 + README/笔记收口（2026-08-24 完成，+15 测试，Stage 14 ✅ 收口验收 4 条全过）

### Stage 14 完成记录

- [x] **M14.4 人工反馈与收口**（agent-trace-export，2026-08-24，+15 测试全仓 685 全绿，Stage 14 ✅）：
    - **feedback 包 5 类**：`HumanFeedback`（1-5 评分）/ `PreferencePair`（A/B 偏好**引用不内嵌**，preferred 限 A|B）/ `TrajectoryPairBuilder`（**prompt 前缀=至首条 USER 含**；不一致 IAE 带双方摘要）/ `DpoExporter`（物化 {prompt, chosen, rejected}：**悬空引用 IAE**+导出时重验前缀；空余段合法——「无响应 vs 好响应」是正当偏好）/ `ConsoleAnnotator`（可注入 IO：ReplayView.describeStep 走查渲染两侧+终答 → a/b/skip → sidecar）
    - **sidecar 纪律**：标注落独立 annotations.jsonl，**原轨迹文件字节不变**（测试 bytes 相等实证 append-only）
    - **双示例实跑**：`TrajectoryExample`（run→record→reward 1.0→回放 integrity verified→python 消费 avg 1.0/tokens 812·45·857）/ `PreferenceAnnotationExample`（双 rollout→标注 A→preferences.jsonl：prompt 2 条共享/chosen 3 条/rejected 1 条）
    - **验收 4 条全过**（蓝图 §9 已加 ✅ 对照）；两处小差异：DPO 行加信封（D2 纪律统一）、describeStep 复用为标注渲染
    - 构建踩坑：单模块 exec 从 .m2 拿旧 product jar 报误导性枚举错——跨模块示例先 install 依赖链
- [x] **M14.3 采样回放与 workflow 适配**（agent-trace-export，2026-08-24，+14 测试全仓 670 全绿）：
    - **`TrajectorySteps.logicalMessages`**：逻辑消息重建单一算法（RecordingSession 产出用 + ReplayView 校验重算，同一份代码——两处各写一份=静默腐化 bug 农场）
    - **replay 包 2 类**：`ReplayView`（构造即校验 + step-through stateAt/actionAt/observationsAt + describeStep 人读摘要）/ `TrajectoryReplayer`（loadAll/loadFirst，坏 JSON 带行号）
    - **D7 完整性校验四连**（比蓝图三连多一条）：index 连续 / done 恰一次在末步 / **非空轨迹无 done 也拒绝**（截断文件，蓝图未写）+ doneReason 对齐 / messages==steps 重建双通道自洽；坏文件实测五形态全 IAE
    - **`WorkflowTrajectoryAdapter`**：兑现 StepRecord Stage 5 javadoc 承诺；**诚实粗粒度**——节点级投影（step.state=黑板视图非模型实见，javadoc 明写语义差异）；终态映射 CANCELLED→doneReason 语义放 doneReason（AgentState.Status 无此值）、**PAUSED 直接 IAE**（暂停 run 非完整轨迹，resume 拼接是 v2）
    - **免费复用证明**：adapt → RuleReward(+1.0) → exporter.record → load → ReplayView 走查全链不改一行——workflow 轨迹进同一条训练数据管线
    - agent-workflow compile 依赖落地（依赖随用随加）
- [x] **M14.2 奖励与导出**（agent-trace-export，2026-08-24，+25 测试全仓 656 全绿）：
    - **reward 包 3 类**：`RewardSource` 接口（三槽位：规则 v1 / 人工 M14.4 / judge v2）/ `RewardResult`（`applyTo` 不可变 wither 回填）/ `RuleReward`（默认 +1.0/-0.5/-1.0/0，`withReward` 定制新实例，空 steps → 0.0 + "no steps recorded" 不造假）
    - **sample 包 2 类**：`SamplingPolicy`（rate+seed+状态集+步数区间+reward 阈值；默认全采含 ERROR 负样本；minReward 时未评分 fail-closed 拒绝）/ `TrajectorySampler`（`floorMod(runId.hashCode() ^ seed, 100) < rate`，String.hashCode 是 JLS 规范值跨 JVM 恒一致可审计）
    - **export 包 3 类**：`TrajectoryCodec`（**契约唯一实现点**——手写 JSON 树，字段显式 snake_case 不靠命名策略；信封 api_version=v1/kind=Trajectory；未知版本 IAE）/ `JsonlTrajectoryWriter`（append-only，坏行报错带 文件:行号 fail loud）/ `TrajectoryExporter`（record = score→sample→persist 门面 + skippedCount 可观测 + write 手动路径 + IO fail loud）
    - **consume_trajectory.py**（examples/scripts/，stdlib only）：信封校验+统计+SFT 样本物化；**跨语言消费证明实测数字逐项一致**（3 条/avg 0.3333/6 模型调用/echo×3/tokens 330·110·440）
    - **golden 字段名快照**：测试内联 40 字段 snake_case 清单，两形状并集==全集+单形状不越界；`custom`/`arguments` 自由数据容器不入契约——改字段名=升 api_version，此测试是 CI 绊线
    - **蓝图口径修正**：导出 steps 用 `state`（模型实见全量快照）非蓝图样例的 `state_delta`（写时压缩 delta 表达不了，§3.2 已加注记指向 §14）
    - 诚实边界：多模态 parts 不进 v1 契约；顶层 done_reason 冗余便利字段 load 时派生不盲信；null/空集合省略+双重 round-trip 树稳定锁
- [x] **M14.1 轨迹记录层**（agent-trace-export 模块，2026-08-24，+19 测试全仓 631 全绿）：
    - **trajectory 包 6 类**（S-A-O-R-D 数据模型）：`Trajectory`（messages 逻辑对话 + steps 逐步结构双通道）/ `TrajectoryStep`（state=该步模型实见全量快照 post-ContextBuilder）/ `StepAction`（content+toolCalls+finishReason+usage+durationMs，模型异常时 finishReason="error" 也成终步）/ `ToolObservation`（结果 VERBATIM，含 [ERROR]/[DENIED] 文本；success 仅指执行器未抛）/ `TrajectoryMetadata`（agentName + promptSha256 指纹 + 工具清单 + token 汇总 + lastError）/ `DoneReason`（终态枚举，非终态映射 null）
    - **record 包 6 文件**（5 公开抽象 + 1 包内实现）：`TrajectoryRecorder`（ThreadLocal 会话，runId 唯一性拒绝重复）/ `RunSession` 接口 + `RecordingSession` 实现（attach 配置一次、finish 恰一次、close 安全网兜底未显式完成）/ `RecordingModelClient`（ModelClient 边界捕获 State+Action，异常捕获后上抛不吞）/ `RecordingToolExecutor`（工具边界捕获 Observation）/ `RecordingAgent`（糖衣：自动 open/attach/finish + 嵌套降级）
    - **D1 压缩保真核心测试**：TrimmingContextBuilder(keepLast=2) 3 步 run + 独立 CapturingModelClient 非循环佐证——step.state == 模型实见 ≠ state.messages 全量（step2 只见 [ASSISTANT,TOOL] 窗口，state 存 7 条）+ messages 逻辑通道保持全量 7 条
    - 其余验证：并行多工具归一步（1 step 2 observations）/ 模型异常终步 ERROR + lastError 入 metadata / maxSteps 终步 MAX_STEPS_EXCEEDED / token 三项汇总（250/90/340）/ [ERROR] 文本 VERBATIM 且 success=true（执行器返回即观察）/ 执行器抛异常 success=false + rethrow + 非终态归一化 ERROR / runId 重复与嵌套 open 拒绝 / finish 二次拒绝 / 无会话透传
    - **与蓝图的两处偏差（诚实记录）**：① D3 的 state_delta 改为每步全量快照——任意 ContextBuilder（含写时压缩）都无法用 delta+dropCount 表达，精确性优先，delta 编码留给 M14.2 导出层；② 压缩保真测试用测试本地 TrimmingContextBuilder 而非 agent-product WindowContextBuilder——避免为一个测试助手拖整个 product 依赖链，被测契约是「任意裁剪 builder 下 State=模型实见」；agent-workflow compile 依赖推迟到 M14.3 adapter 落地时再加
    - **意外边界发现**：ReActAgentLoop 对 toolExecutor.execute 无 try-catch，执行器异常直接炸穿 loop（框架既有行为）——RecordingToolExecutor 记录后原样上抛，RecordingAgent 的 finally-finish 用非终态归一化 ERROR 兜住并诚实标注（"run aborted in non-terminal status EXECUTING_TOOL"）

### Stage 15 规划（Enterprise Agent Profile，📐 2026-08-24 定稿）

> 设计蓝图：[notes/architecture-stage-15.md](notes/architecture-stage-15.md) · 新增 `agent-enterprise` 模块，
> 三类场景同 Runtime 宣言的第一次实证：企业场景缺的不是新能力，是**归属层**（谁在问 / 属于哪个租户 / 花了谁的钱 / 出了事找谁）
> 依赖裁决：core + memory + security + workflow（不依赖 product/channel/scheduler，D1 正交）；存量改动仅 MemoryScope TENANT / MemoryType KNOWLEDGE 两处纯加法

- [x] M15.1 租户与用户域：Tenant/User/TenantRegistry/RequestContext（登录识别 + scope 白名单 SSOT）+ 两处存量纯加法 ✅（2026-08-24 完成，27 测试全仓 712 全绿，存量零影响）
- [x] M15.2 知识层（RAG）：KnowledgeEntry/KnowledgeBase/KnowledgeTool（知识即记忆：type=KNOWLEDGE + tenant scope 白名单隔离，跨租户零泄漏）✅（2026-08-24 完成，+18 测试全仓 730 全绿）
- [x] M15.3 治理接线：RoleBasedPermissionChecker（角色×工具矩阵，兑现 Stage 9 扩展点承诺）+ EnterpriseAuditTrail（归属审计）+ CostLedger（事前预算闸 + 事后记账 fail-closed）✅（2026-08-24 完成，+24 测试全仓 754 全绿）
- [x] M15.4 业务任务与恢复：BusinessTask/TaskApprovalRecord/EnterpriseTaskManager（任务级审批 + approve→resume 断点恢复 + FileCheckpointStore 崩溃恢复）+ TaskApprovalBridge（装配级审批通道，崩溃后跨 manager 代际共享）✅（2026-08-24 完成，+12 测试全仓 766 全绿）
- [x] M15.5 装配与收口：EnterpriseAgentFactory（请求作用域装配，身份显式传递非 ThreadLocal）+ EnterpriseAssistant + EnterpriseAssistantExample 全剧本验收 + README/笔记收口 ✅（2026-08-24 完成，+8 测试全仓 774 全绿，Stage 15 五里程碑收官）

### Stage 16 规划（Tavern Game Profile，📐 2026-08-24 定稿）

> 设计蓝图：[notes/architecture-stage-16.md](notes/architecture-stage-16.md) · 新增 `agent-tavern` 模块，
> 三类场景同 Runtime 宣言的第二次实证：游戏场景缺的不是新能力，是**世界层**（角色有灵魂 / 说话有后果 / 一局有历史）；
> **零存量改动**（对照 Stage 15 两处枚举加法——"当一个新场景能零存量改动落地时，才证明之前的抽象是对的"）；
> 三处有意不复用：RunManager（D6 存档≠run checkpoint）/ EventBroker（D5 事件=同步规则评估非 run 恢复）/ Workflow（D8 回合=顺序代码非图）；与 Stage 15 依赖正交（D9）

- [x] M16.1 角色域：CharacterCard / CharacterAgentFactory（persona→systemPrompt 翻译 + Interaction rules 预埋）+ CharacterMemory（agent:{charId} 跨局 + session:{gameId} 局内双 scope 白名单，**零新 scope kind**）✅（2026-08-24 完成，19 测试全仓 793 全绿，存量零影响：persona 注入模型实见实证 + 同输入两角色两人格 + 跨局记忆存活 + AgentState 跨回合续跑）
- [x] M16.2 世界与回合：WorldState / WorldEffect（sealed 变更即指令，apply 不可变返回新状态）+ SetWorldFlagTool（**纯指令提交器**——工具产出指令、引擎唯一 apply 点）+ Turn / TurnResult（sealed 两态）/ TurnEngine（mention 路由 + [world]/[relationship]/[player] 便签注入 + held AgentState 续跑 + submitEffect 唯一 apply+记录点）/ TurnLog（内存 append-only，JSONL 随 M16.4）✅（2026-08-24 完成，+28 测试全仓 821 全绿：mention 两态零模型空跑 / 便签注入模型实见实证 / 工具变世界全链 / 角色状态隔离 / 回合推进续跑）
- [x] M16.3 关系与事件：Relationship / Matrix / Policy（**净变幅回合累计限幅** fail-closed，防化整为零刷好感）+ AdjustRelationshipTool（拒绝=教练式 [REJECTED] 文本，模型读失败观察自愈）+ GameEvent / GameFacts / EventRule / EventEvaluator（同步评估恰一轮防风暴，fail-soft 坏条件）+ TriggerEventTool（登记不执行，结算点统一引爆）+ TurnEngine 事件结算点（effects 应用+respondCharacter 强制响应 eventDriven）+ **Turn 补 RelationshipChange 落账字段**（蓝图缺口：D7 回放要重演世界+关系）+ 治理链接线（executorFactory 注入，**审计流水=GM 后台**实证）✅（2026-08-24 完成，+37 测试全仓 858 全绿：限幅全语义/事件结算全链/无级联/手动引爆同回合/响应中登记顺延/治理审计）
- [x] M16.4 存档与回放：SaveGame / GameStore（局快照 + {gameId}/save.json + turn-log.jsonl 双文件布局）/ ReplayCodec（手写树编解码，读写单一契约点）/ GameReplayer + GameReplay（**走录不重演**：stateAt(n) 重演世界+关系时间线，模型零调用；describeTurn 人读复盘）/ TurnEngine+Matrix+Evaluator 扩展（initial 信封视图 / characterHistories / restoreHistories / restore 绕限幅系统操作）✅（2026-08-24 完成，+17 测试全仓 875 全绿：round-trip 全等 + 续局对话连续 + once 簿记跨重载 + **重演终态==存档终态** + 完整性三态带行号 fail-loud + **写后字节不变（前缀稳定）** + 多模态消息 fail-loud）
- [x] M16.5 装配与收口：TavernGame 门面 + Builder（新局 build / 续局 load / `governance(logger)` 一行装配 GM 后台 / storeRoot 持久化）+ TurnEngine.resume 工厂（**拆分 game-initial 与 current-world 语义**——load 恢复完整历史，续局 save 仍写从 turn 1 起的连续日志）+ examples 依赖 + TavernGameExample 全剧本实跑 ✅（2026-08-24 完成，+10 测试全仓 885 全绿：门面全链 / builder 校验 / [relationship] 默认注入 / governance 一行审计 / save-load 续局 turnNo 接续+历史可见 / replay 双视图 / 无治理兼容）——**示例实跑揪出续存档日志断裂真缺口**，完整性校验 fail-loud 拦截后修复

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
├── agent-trace-export/ # RL 轨迹产出层（记录/奖励/采样/导出/回放/DPO，Stage 14）
├── agent-enterprise/   # 企业 Agent Profile（租户用户域/RAG/治理/成本/业务任务，Stage 15 ✅）
├── agent-tavern/       # 酒馆游戏 Agent Profile（角色/世界/回合/关系/事件/回放，Stage 16 ✅）
├── examples/            # 示例代码
├── notes/               # 学习笔记（按阶段组织）
└── pom.xml              # 父 POM
```

### 后续模块（尚未创建）

```
agent-runtime/           # 阶段 6 已并入 agent-workflow/runtime，不再单独立项
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
| 14. RL 轨迹产出层 | agent-trace-export      | ✅ 已完成 |
| 15. Enterprise Agent Profile | agent-enterprise  | ✅ 完成 |
| 16. Tavern Game Profile | agent-tavern  | ✅ 完成（111 测试，零存量改动） |
