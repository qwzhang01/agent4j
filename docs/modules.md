# 模块一览

父工程：`io.github.qwzhang01:seven-agent:0.1.3`（Central 最新，`packaging=pom`）。
库模块按需依赖，**不要**把整个父工程当 jar 引进业务。

用 `seven-agent-bom`（`type=pom` / `scope=import`）统一版本（Central 最新 `0.1.3`），再声明具体 `artifactId`。

`examples` 只演示，**不发布**（`release` profile 的 `excludeArtifacts`）。

| artifactId | 职责 | 典型依赖方 |
|------------|------|------------|
| `agent-core` | 接口与数据：`ChatMessage`、`ModelClient`、`Tool`、`Agent`、`AgentLoop` | 几乎所有模块 |
| `agent-model` | `MockModelClient`；OpenAI-compatible / Anthropic 客户端；Retry / Timeout / Fallback / StructuredOutput 装饰器 | 需要真实或 Mock 模型的模块与示例 |
| `agent-plugin` | SPI 插件加载 / 卸载 / 重载 + 外部 JAR 加载（checksum 门、每 jar 独立 classloader、manifest 权限声明、注册回滚、命名空间隔离）；**无**多版本共存 / module layer 禁闭 | 自进化 Tool、`PluginExample` |
| `agent-sandbox` | ClassLoader 沙箱 + Process 沙箱（**无** Docker / WASM） | `agent-coding`、沙箱示例 |
| `agent-workflow` | 图运行时、7 种节点、Checkpoint；durable 执行：RunStore/Lease/Ledger/CheckpointStore（内存 + JDBC 后端，纯 ANSI SQL）、`DurableRunManager`（心跳续租 + 行监视）、`DistributedRunControl` 跨实例 Cancel/Resume 守卫/Approval Callback | `agent-scheduler`、`agent-product`、`agent-enterprise`、`agent-trace-export` |
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
| `agent-observability` | 指标（Prometheus 文本 / JSONL sink）、五维预算、路由、评估（黄金集 + 在线五指标 / 采样 / 版本对照 / 漂移告警 / 统一报告）、版本三元组、ops 事件总线 | 可观测示例 |
| `agent-otel-export` | RunEvent → OTel span 薄壳（`agent.run`/`agent.step`/`agent.model`/`agent.tool`）；SDK 仅测试域，不进核心 | OTel 示例 |
| `agent-spring-boot-starter` | **可选** Spring Boot 自动配置：`ModelClient` + `AgentFactory`。**唯一依赖 Spring 的模块**。不自动依赖 `agent-chat` | Spring Boot 3.2 应用（如 Moonlit） |
| `examples` | 可运行示例（见 `examples/README.md`） | 无（消费以上模块） |

企业 / 酒馆 / 编码是**同一 Runtime 上的三个领域 Profile**，不是三套框架。

最小接入：`agent-core` + `agent-model`。图、治理、记忆按需加。Spring Boot 应用可再加 `agent-spring-boot-starter`（core / model 仍无 Spring）。

## 角色引擎接线（Moonlit / SillyTavern 一类）

| 模块 | 职责 |
|------|------|
| `agent-chat` | `ChatRoom` / `ChatEngine`：选人、拼上下文、流式、`ChatListener`；`PersonaRenderer`；可选 `ConsistencyGuard` |
| `agent-memory` | Store / Extractor / Retriever；可选 `MemorySource` 在 chat 侧挂载 |

边界：`MemorySource` 只负责**读进 prompt**；写什么、何时抽、何时提醒在 Moonlit（Listener + Job）。`LoreSource` 只负责本轮关键词/正则命中后注入，词库在产品。`RelationSource` 只注入快照，不算分。`ConsistencyGuard` 默认 no-op，告警不改写。`DirectorSpeaker` 用独立 ModelClient + 业务 prompt 选人，可与 Mention 组合。角色向 eval：`CharacterEvalTest`。详见 `notes/architecture-agent-chat.md` §9 与 `notes/architecture-character-engine.md`（Wave 1–4 + T28 完成；T23 默认跳过）。
