# 模块一览

父工程：`io.github.qwzhang01:seven-agent:0.1.3`（Central 最新，`packaging=pom`）。
库模块按需依赖，**不要**把整个父工程当 jar 引进业务。

用 `seven-agent-bom`（`type=pom` / `scope=import`）统一版本（Central 最新 `0.1.3`），再声明具体 `artifactId`。

`examples` 只演示，**不发布**（`release` profile 的 `excludeArtifacts`）。

| artifactId | 职责 | 典型依赖方 |
|------------|------|------------|
| `agent-core` | 接口与数据：`ChatMessage`、`ModelClient`、`Tool`、`Agent`、`AgentLoop`；表达层（Stage 9）：`ReflectiveAgent` 有界反思（PASS/REVISE/GIVE_UP 协议词裁决、critique 不入用户可见输出）、`ParallelToolExecutor` 声明序并行工具 + `ReActAgentLoop.withParallelTools` 装配、`AgentEvent` 五新事件（ModelCall/ToolValidationRejected/Reflection）；治理拒绝事件（gap-closure）：`[DENIED]`→approval、`[RATE_LIMITED]`→permission 接线，恢复事件留白（`[DENIED]` 三来源串不可区分统一归 approval） | 几乎所有模块 |
| `agent-model` | `MockModelClient`；OpenAI-compatible / Anthropic 客户端；Retry / Timeout / Fallback / StructuredOutput 装饰器 | 需要真实或 Mock 模型的模块与示例 |
| `agent-plugin` | SPI 插件加载 / 卸载 / 重载 + 外部 JAR 加载（checksum 门、每 jar 独立 classloader、manifest 权限声明、注册回滚、命名空间隔离）；**无**多版本共存 / module layer 禁闭 | 自进化 Tool、`PluginExample` |
| `agent-sandbox` | ClassLoader 沙箱 + Process 沙箱（**无** Docker / WASM） | `agent-coding`、沙箱示例 |
| `agent-workflow` | 图运行时、7 种节点、Checkpoint；durable 执行：RunStore/Lease/Ledger/CheckpointStore（内存 + JDBC 后端，纯 ANSI SQL）、`DurableRunManager`（心跳续租 + 行监视）、`DistributedRunControl` 跨实例 Cancel/Resume 守卫/Approval Callback；计划层（Stage 9 `plan` 包）：`Plan`（DAG 校验：未知/前向/自环/重复依赖全拒 + `planVersion` + `readySteps` 前沿）、`PlanExecutor`（编译为拓扑序线性链复用 GraphRuntime——单游标 runtime 不允许扇出无条件边）、`MultiAgentPlanner`（子任务状态隔离 + `deriveChild` 预算/Trace 传播 + 同输出去重）、`EventReplayer`（事件历史只读重放，工具不重复执行；gap-closure：`replayPrefix` 前缀折叠——交互式 time-travel，mid-run 世界保留部分历史、Done 窗内判定 doneWithinPrefix、破碎录制 anomaly 保留、越界 fail-loud）；plan 级恢复（gap-closure）：`StepExecutor` 异常直达 + `Plan.rebuildWith` 版本演进 + `RunManager` `DEFINITION_VERSION_MISMATCH` 守卫，恢复粒度 last pause 非 last node | `agent-scheduler`、`agent-product`、`agent-enterprise`、`agent-trace-export` |
| `agent-scheduler` | 定时 / 事件唤醒 + 任务队列（内存 + `JdbcTaskQueue` 持久化任务表，孤儿回查 `requeueOrphaned`） | `agent-channel`、调度示例 |
| `agent-memory` | Working / Session / Long-term + `MemoryScope`。包：根接线面 + `extract/` `store/` `context/` `session/` `tools/` | `agent-channel`、`agent-enterprise`、`agent-tavern`、`agent-chat`（`MemorySource`） |
| `agent-security` | 权限 / 审批 / 净化 / 审计 | `agent-mcp`、`agent-coding`、企业 / 酒馆 / 频道 |
| `agent-mcp` | MCP 客户端（stdio + HTTP/SSE transport，server 信任等级 / allowlist / 宿主认证适配器 / schema 校验）+ A2A 双向：`HttpA2AClient`（规范方言：卡片发现 / `message/send` / `tasks/get` / `message/stream` SSE / webhook 推送 / input-required 续跑）、`HttpA2AServer`（Agent 包装成端点：入站净化 + 可选 bearer 门 + HMAC 签名推送 + 重放窗 + 可插拔 `A2ATaskStore`）。无 PKI 卡签名；互操作验证为回环 + JDK HttpServer 模拟方言 | `agent-orchestrator`、MCP / A2A 示例 |
| `agent-orchestrator` | Supervisor / Worker / 并行派发 | 多 Agent 示例 |
| `agent-channel` | 身份、共享会话、任务接力、Ambient | `agent-product`、频道示例 |
| `agent-product` | YAML Agent 定义、模板、Prompt 版本、Webhook、DAG | 声明式 / Webhook 示例 |
| `agent-trace-export` | 轨迹 S-A-O-R-D、JSONL、DPO 偏好 | `agent-observability`、轨迹示例 |
| `agent-enterprise` | 租户 / RAG / 成本账本 / 业务任务 | 企业助手示例 |
| `agent-tavern` | 游戏 Profile：角色 / 世界 / 回合 | 酒馆示例 |
| `agent-chat` | 房间对话引擎：选人 / 拼上下文 / 流式 / 通知。可选 `MemorySource` / `LoreSource` / `RelationSource`；可选 `ConsistencyGuard`（默认 no-op）；群聊 `RoundRobinSpeaker`；`PersonaRenderer` 挂钩。**不是**酒馆游戏 | Moonlit / SillyTavern 一类；`ChatRoomExample` |
| `agent-coding` | 工作区 / 补丁 / 命令白名单 / 修复环 | 编码 Agent 示例 |
| `agent-observability` | 指标（Prometheus 文本 / JSONL sink）、五维预算、路由（`RiskAwareRouter` gap-closure：`SideEffectLevel` 风险信号——DESTRUCTIVE/SIDE_EFFECT/UNKNOWN 暴露即升 premium，NONE/READ_ONLY 走 cheap，装配未分类 = 无信号跳过不伪造风险；pre-call 代理信号方向性安全）、评估（黄金集 + 在线五指标 / 采样 / 版本对照 / 漂移告警 / 统一报告）、版本三元组、ops 事件总线 | 可观测示例 |
| `agent-otel-export` | RunEvent → OTel span 薄壳（`agent.run`/`agent.step`/`agent.model`/`agent.tool`）；SDK 仅测试域，不进核心 | OTel 示例 |
| `agent-spring-boot-starter` | **可选** Spring Boot 自动配置：`ModelClient` + profile 感知 `AgentFactory`（Stage 8.2：`agent4j.profile` 默认 secure——治理装配自动接；test 同栈自动放行；unsafe 显式裸奔）+ 启动高风险配置检查（SECURE 矛盾即炸启动）+ 六面健康检查 + 优雅停机协调器 + 配置版本。**唯一依赖 Spring 的模块**。不自动依赖 `agent-chat` | Spring Boot 3.2 应用（如 Moonlit） |
| `examples` | 可运行示例（见 `examples/README.md`） | 无（消费以上模块） |

企业 / 酒馆 / 编码是**同一 Runtime 上的三个领域 Profile**，不是三套框架。

最小接入：`agent-core` + `agent-model`。图、治理、记忆按需加。Spring Boot 应用可再加 `agent-spring-boot-starter`（core / model 仍无 Spring）。

**JDBC 存储驱动约定**：`JdbcRunStore` / `JdbcRunLeases` / `JdbcSideEffectLedger` / `JdbcCheckpointStore` / `JdbcTaskQueue` / `JdbcApprovalStore` 只面向 `java.sql` 接口编程，框架不绑定任何数据库——生产部署自带相应 JDBC 驱动（如 PostgreSQL）并装配连接工厂即可，换库不改代码。`com.h2database:h2` 仅以 `test` scope 存在于仓库内三个模块的测试 JVM，用于分布式存储契约测试，不会进入任何使用方的传递依赖。

## 集成测试 Profile（Stage 8.3）

默认 `./mvnw verify` 跑单 JVM 全套（无外部服务假定）。传输级 / 数据库级 IT 走 JUnit 5 tag opt-in，CI 用 `-Dagent4j.surefire.excludedGroups= -Dgroups=<tag>` 打开：

| tag | 覆盖 | 前提 | 本机快速跑 |
|-----|------|------|-----------|
| `postgres` | `PostgresIT`（workflow durable 全链路真库验证） | 本地 PG，`AGENT4J_IT_PG_URL` 等环境变量启用探测 | `-Dgroups=postgres` + 导出 `AGENT4J_IT_PG_*` |
| `mcp-it` | `McpStdioIT`（真实 Python 子进程 MCP server 全链路：握手 / 工具发现 / 调用 / 崩溃自愈重启重试 / 协议错误不误重启） | `python3` 在 PATH（无则显式 skip 可审计） | `-Dgroups=mcp-it` |
| `a2a-it` | `HttpA2ARoundTripTest` / `HttpA2AServerProtocolTest` / `HttpA2AServerSecurityTest`（真实 loopback HTTP 全链路：往返 / 线协议 / 认证 / 签名推送 / 跨重启任务存活） | 无（本机 socket） | `-Dgroups=a2a-it` |
| `sandbox-escape` | `SandboxEscapeTest` + `ProcessSandboxRedTeamTest` + `DockerDaemonProbeIT`（逃逸回归 24 + 容器 daemon 探测 + DOCKER placeholder loud-fail 契约） | 无外部必需（无 docker 时 daemon 测试走可审计 skip） | 默认就跑 |

发布门槛另有两道：`-Papi-compat`（japicmp 对 0.1.3 基线做二进制兼容断言，无基线模块在自己 pom 显式 `japicmp.skip`）；SBOM / License（`target/bom.json` CycloneDX 1.5 + `THIRD-PARTY.txt`）随每次 `package`/`verify` 生成。CI 在 `main`/PR 上跑全部门槛（见 `.github/workflows/ci.yml`）。

## 角色引擎接线（Moonlit / SillyTavern 一类）

| 模块 | 职责 |
|------|------|
| `agent-chat` | `ChatRoom` / `ChatEngine`：选人、拼上下文、流式、`ChatListener`；`PersonaRenderer`；可选 `ConsistencyGuard` |
| `agent-memory` | Store / Extractor / Retriever；可选 `MemorySource` 在 chat 侧挂载 |

边界：`MemorySource` 只负责**读进 prompt**；写什么、何时抽、何时提醒在 Moonlit（Listener + Job）。`LoreSource` 只负责本轮关键词/正则命中后注入，词库在产品。`RelationSource` 只注入快照，不算分。`ConsistencyGuard` 默认 no-op，告警不改写。`DirectorSpeaker` 用独立 ModelClient + 业务 prompt 选人，可与 Mention 组合。角色向 eval：`CharacterEvalTest`。详见 `notes/architecture-agent-chat.md` §9 与 `notes/architecture-character-engine.md`（Wave 1–4 + T28 完成；T23 默认跳过）。
