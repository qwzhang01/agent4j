# 示例

可运行 `main` 全部在 `io.github.qwzhang01.agent.examples`（另有 SPI 插件类，不是独立入口）。

**从这里开始：`MockAgentExample`。** 不需要 API Key、不需要外部进程。

## 怎么跑

首次：

```bash
# 在仓库根目录（projects/java-agent-framework 或独立 clone 的 agent4j）
mvn install -DskipTests
```

之后（把 `NAME` 换成下表类名）：

```bash
mvn -pl examples compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.NAME
```

例如：

```bash
mvn -pl examples compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

IDE 里直接跑对应 `main` 即可。

## 零 LLM（优先跑这些）

用 `MockModelClient` 或纯本地组件，默认不访问外网。

| 类名 | 看什么 |
|------|--------|
| **`MockAgentExample`** | **最小 Agent：注册工具 + 脚本化模型 + `run`** |
| `StreamingAgentExample` | `stream`：边生成边打印 `ContentDelta` |
| `DecoratedModelClientExample` | Retry / Timeout / Fallback / StructuredOutput 叠装饰器 |
| `PluginExample` | SPI 发现、加载、卸载、重载 |
| `PluginSelfModificationExample` | 模型在对话里管理插件（inspect / load / unload） |
| `SandboxExample` | ClassLoader / Process 沙箱 |
| `SandboxAgentExample` | 模型触发 `sandbox_execute` 的完整链路 |
| `WorkflowSupportFlowExample` | 客服三路图：查询 / 退款审批 / 转人工 |
| `CheckpointExample` | 图执行断点保存与恢复 |
| `SchedulerExample` | 定时恢复、事件恢复、任务队列 |
| `MemoryExample` | 多轮记忆写入与回注 |
| `CompressionExample` | 超预算时压缩上下文 |
| `ChannelMemoryExample` | 频道共享记忆 + 审批 / 覆盖 |
| `SecurityExample` | 权限三档 + 审批 + 审计 |
| `InjectionDefenseExample` | 工具回包注入：SANITIZE / TRUNCATE / BLOCK |
| `McpExample` | 进程内 Mock MCP：发现工具 + 治理执行 |
| `MultiAgentExample` | Supervisor 并行派发内部 Worker + 进程内 A2A |
| `ChannelAgentExample` | 频道身份、共享会话、任务接力 |
| `AmbientExample` | Ambient 主动推送 + 噪音闸 |
| `TrajectoryExample` | 轨迹记录 → 奖励 → 采样 → JSONL → 回放 |
| `PreferenceAnnotationExample` | 双 rollout 标注，写出 DPO preferences |
| `EnterpriseAssistantExample` | 租户 / RAG / 审批断点 / 预算拒绝 |
| `TavernGameExample` | 角色 / 世界 / 回合 / 回放 |
| `ChatRoomExample` | 房间聊天：一对一流式 + 两人 `@` 点名（零 LLM） |
| `CodingAgentExample` | 工作区、补丁、命令白名单、有界修复环 |
| `ObservabilityExample` | 指标、五维预算、路由、评估、版本三元组 |
| `DeclarativeAgentExample` | YAML 定义 Agent + 模板 / Prompt 版本 / DAG |
| `WebhookExample` | HMAC + 幂等 Webhook 驱动 Agent |

## 需要额外环境 / 真实服务

跑之前看各类 javadoc 里的前置条件（Node / `npx`、模型端点、多模态服务等）。

| 类名 | 额外依赖 |
|------|----------|
| `LlmDrivenSchedulerExample` | 由模型输出驱动等待事件 / 延时（不是写死在图上的参数） |
| `MultimodalExample` | 读图 / 生图 / 生视频相关客户端与治理默认值 |
| `McpRealServerExample` | 官方 MCP filesystem server（`npx -y @modelcontextprotocol/server-filesystem`） |
| `ManagedMcpExample` | 同上真实 stdio server，演示崩溃后按预算重启 |

`McpRealServerExample` / `ManagedMcpExample` 典型准备：

```bash
mkdir -p /tmp/mcp-demo && echo "hello" > /tmp/mcp-demo/hello.txt
```

## 建议顺序

1. `MockAgentExample` — 确认 Loop 和 Tool 通了  
2. `DecoratedModelClientExample` — 再换真实 `OpenAiModelClient`（构造见该类 javadoc / 测试）  
3. `SecurityExample` — 工具默认要过治理  
4. `WorkflowSupportFlowExample` + `CheckpointExample` — 图与断点  
5. 按场景：企业 / 酒馆 / 编码 / 可观测 / 轨迹  

概念见 [../docs/concepts.md](../docs/concepts.md)，v1 做不到的事先看 [../docs/limitations.md](../docs/limitations.md)。
