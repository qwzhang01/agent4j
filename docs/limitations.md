# v1 边界与已知限制

版本：`0.1.3`（Central 最新）。下面是**有意不做**或**尚未具备**的能力，避免按 `notes/` 或仓库体量误判为已交付。

## Maven Central

坐标是 `io.github.qwzhang01:seven-agent`（Central 最新 `0.1.3`）。发布走 `./mvnw -DskipTests deploy`，`examples` 不进 bundle。直接从 Central 引用即可；要跟踪仓库开发版，克隆仓库 → `./mvnw -B verify` 或 `mvn install`，再从本地仓库引用。

用法见 [getting-started.md](getting-started.md)。

## 运行时不依赖 Spring

`agent-core` / `agent-model` 等运行时模块不引入 Spring Framework。父 POM 是独立聚合工程，不是 `spring-boot-starter-parent`。

可选模块 `agent-spring-boot-starter` 是**唯一**依赖 Spring 的模块：读 `agent4j.*` 配置、创建 `ModelClient`、提供 `AgentFactory`。Core 仍然 Spring-free。没有 Actuator 集成。

## v1 明确不做

| 能力 | v1 实际有什么 |
|------|----------------|
| JAR 插件 ClassLoader / 多版本共存 | Java SPI 加载 / 卸载 / 重载，同一 classpath |
| Docker / WASM 沙箱 | `ClassLoaderSandbox` + `ProcessSandbox` |
| MCP SSE | MCP **stdio** 客户端；可连官方 filesystem server |
| A2A `message/stream`（SSE）/ 推送通知 / 任务续跑 | A2A **HTTP** 双向：`HttpA2AClient`（`message/send` / `tasks/get` / 卡片发现）+ `HttpA2AServer`（把 Agent 包成端点，含入站净化防线）；任务同步执行，任务存储在内存 |
| 真 Git | `agent-coding` 是工作区 + 补丁 + 命令白名单 + 有界修复环，不封装 Git |
| OpenTelemetry SDK | `agent-observability` 自管指标 / 预算 / 路由 / 评估 / 版本三元组 |
| Mini VERL 训练 | `agent-trace-export` 导出轨迹 JSONL 与 DPO 偏好，训练环不在库内 |
| LLM-as-judge | 评估走规则 / 失败样本回归集，不用模型当裁判 |

这些不是「下一阶段漏做」，是 v1 非目标。

## 其他诚实边界

- **模型覆盖窄**：Mock、OpenAI-compatible、Anthropic。没有厂商全家桶 connector。
- **记忆持久化是裸 JDBC**：`PgMemoryStore` 用宿主自带 `DataSource`（无连接池、无 ORM、无框架托管迁移），schema 幂等自建；`InMemoryMemoryStore` 适合单机与测试。
- **Checkpoint 同理**：内存 / 文件 store，没有托管工作流后端。
- **测试基线**：全仓 22 模块全绿是回归契约（以 CI 为准）；`notes/` 里的阶段叙事、公众号文章**不是**用户合同。
- **学习项目**：通过造 Runtime 学架构。生产使用前先读本页和 [comparison.md](comparison.md)。

## 相关文档

- 上手：[getting-started.md](getting-started.md)
- 模块：[modules.md](modules.md)
- 和别的库：[comparison.md](comparison.md)
