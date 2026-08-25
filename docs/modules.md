# 模块一览

父工程：`io.github.qwzhang01:seven-agent:0.1.0-SNAPSHOT`（`packaging=pom`）。  
库模块按需依赖，**不要**把整个父工程当 jar 引进业务。

`0.1.0` 上 Central 之后，用 `seven-agent-bom`（`type=pom` / `scope=import`）统一版本，再声明具体 `artifactId`。在此之前：本仓库 `mvn install`，下游写相同 `groupId` + `0.1.0-SNAPSHOT`。

`examples` 只演示，不是发布库。

| artifactId | 职责 | 典型依赖方 |
|------------|------|------------|
| `agent-core` | 接口与数据：`ChatMessage`、`ModelClient`、`Tool`、`Agent`、`AgentLoop` | 几乎所有模块 |
| `agent-model` | `MockModelClient`；OpenAI-compatible / Anthropic 客户端；Retry / Timeout / Fallback / StructuredOutput 装饰器 | 需要真实或 Mock 模型的模块与示例 |
| `agent-plugin` | Java SPI 插件加载 / 卸载 / 重载（**无** JAR ClassLoader 多版本） | 自进化 Tool、`PluginExample` |
| `agent-sandbox` | ClassLoader 沙箱 + Process 沙箱（**无** Docker / WASM） | `agent-coding`、沙箱示例 |
| `agent-workflow` | 图运行时、7 种节点、Checkpoint | `agent-scheduler`、`agent-product`、`agent-enterprise`、`agent-trace-export` |
| `agent-scheduler` | 定时 / 事件唤醒 + 任务队列 | `agent-channel`、调度示例 |
| `agent-memory` | Working / Session / Long-term + `MemoryScope`。包：根接线面 + `extract/` `store/` `context/` `session/` `tools/` | `agent-channel`、`agent-enterprise`、`agent-tavern`、`agent-chat`（`MemorySource`） |
| `agent-security` | 权限 / 审批 / 净化 / 审计 | `agent-mcp`、`agent-coding`、企业 / 酒馆 / 频道 |
| `agent-mcp` | MCP stdio 客户端 + 进程内 A2A（SSE / HTTP 传输未做） | `agent-orchestrator`、MCP 示例 |
| `agent-orchestrator` | Supervisor / Worker / 并行派发 | 多 Agent 示例 |
| `agent-channel` | 身份、共享会话、任务接力、Ambient | `agent-product`、频道示例 |
| `agent-product` | YAML Agent 定义、模板、Prompt 版本、Webhook、DAG | 声明式 / Webhook 示例 |
| `agent-trace-export` | 轨迹 S-A-O-R-D、JSONL、DPO 偏好 | `agent-observability`、轨迹示例 |
| `agent-enterprise` | 租户 / RAG / 成本账本 / 业务任务 | 企业助手示例 |
| `agent-tavern` | 游戏 Profile：角色 / 世界 / 回合 | 酒馆示例 |
| `agent-chat` | 房间对话引擎：选人 / 拼上下文 / 流式 / 通知。可选 `MemorySource`；群聊 `RoundRobinSpeaker`。**不是**酒馆游戏 | Moonlit / SillyTavern 一类；`ChatRoomExample` |
| `agent-coding` | 工作区 / 补丁 / 命令白名单 / 修复环 | 编码 Agent 示例 |
| `agent-observability` | 指标、五维预算、路由、评估、版本三元组 | 可观测示例 |
| `agent-spring-boot-starter` | **可选** Spring Boot 自动配置：`ModelClient` + `AgentFactory`。**唯一依赖 Spring 的模块**。不自动依赖 `agent-chat` | Spring Boot 3.2 应用（如 Moonlit） |
| `examples` | 可运行示例（见 `examples/README.md`） | 无（消费以上模块） |

企业 / 酒馆 / 编码是**同一 Runtime 上的三个领域 Profile**，不是三套框架。

最小接入：`agent-core` + `agent-model`。图、治理、记忆按需加。Spring Boot 应用可再加 `agent-spring-boot-starter`（core / model 仍无 Spring）。

## 角色引擎接线（Moonlit / SillyTavern 一类）

| 模块 | 职责 |
|------|------|
| `agent-chat` | `ChatRoom` / `ChatEngine`：选人、拼上下文、流式、`ChatListener` |
| `agent-memory` | Store / Extractor / Retriever；可选 `MemorySource` 在 chat 侧挂载 |

边界：`MemorySource` 只负责**读进 prompt**；写什么、何时抽、何时提醒在 Moonlit（Listener + Job）。详见 `notes/architecture-agent-chat.md` §9 与 [`todo-moonlit-memory-chat.md`](../notes/todo-moonlit-memory-chat.md)（下一项 T08 表结构）。
