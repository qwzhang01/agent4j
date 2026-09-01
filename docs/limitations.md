# v1 边界与已知限制

版本：`0.1.0`。下面是**有意不做**或**尚未具备**的能力，避免按 `notes/` 或仓库体量误判为已交付。

## Maven Central

坐标是 `io.github.qwzhang01:seven-agent:0.1.0`。发布走 `./mvnw -P release deploy`，`examples` 不进 bundle。Portal 尚未点 Publish 时，克隆仓库 → `./mvnw -B verify` 或 `mvn install`，再从本地仓库引用。

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
| HTTP A2A 传输 | 进程内 `InProcessA2AClient`，协议模型对齐，传输未换 HTTP |
| 真 Git | `agent-coding` 是工作区 + 补丁 + 命令白名单 + 有界修复环，不封装 Git |
| OpenTelemetry SDK | `agent-observability` 自管指标 / 预算 / 路由 / 评估 / 版本三元组 |
| Mini VERL 训练 | `agent-trace-export` 导出轨迹 JSONL 与 DPO 偏好，训练环不在库内 |
| LLM-as-judge | 评估走规则 / 失败样本回归集，不用模型当裁判 |

这些不是「下一阶段漏做」，是 v1 非目标。

## 其他诚实边界

- **模型覆盖窄**：Mock、OpenAI-compatible、Anthropic。没有厂商全家桶 connector。
- **记忆默认在进程内**：`InMemoryMemoryStore` 等实现适合单机与测试；持久化要自己接存储。
- **Checkpoint 同理**：内存 / 文件 store，没有托管工作流后端。
- **测试基线 1186**：全绿是回归契约；`notes/` 里的阶段叙事、公众号文章**不是**用户合同。
- **学习项目**：通过造 Runtime 学架构。生产使用前先读本页和 [comparison.md](comparison.md)。

## 相关文档

- 上手：[getting-started.md](getting-started.md)
- 模块：[modules.md](modules.md)
- 和别的库：[comparison.md](comparison.md)
