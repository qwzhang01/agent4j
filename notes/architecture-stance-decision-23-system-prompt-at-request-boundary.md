# 决策 23：人格属于 Config，每次模型请求现取，不属于历史

日期：2026-09-08

## 五字段立场卡

- 我的选择：`AgentConfig.systemPrompt` 是 ReAct 路径的人格来源。`SimpleAgent.prepare()` 只追加用户消息；`ReActAgentLoop.buildRequest()` 先获取历史与临时上下文，再新建请求列表并将当前 config 的非空白人格放到首部。同步与流式共用此路径。`AgentState` 和新 checkpoint 不自动保存人格。
- 对比替代：不采用 handoff 时重写 `messages[0]`。历史不应有一个需要特殊保护的人格槽位，也不能把 A 的身份改写成 B 来伪装历史。请求级轨迹仍完整记录每次模型实际看到的人格与上下文。
- 代价：移除 `AgentState(String systemPrompt, String userInput)` 和 `ChatSession.toAgentState(String systemPrompt)`，存在源码及二进制兼容性变化。旧 checkpoint 需要显式迁移；自定义上下文构建器必须停止注入人格或写 SYSTEM 到 state；压缩预算只覆盖历史，需要另外预留人格、工具 schema、输出与临时检索开销。
- 什么场景会改：动态人格可以演进为请求级 instructions provider，但不得重新进入历史。若需要完全重放，另存配置版本/指纹和完整模型边界轨迹。若需要结构化协作事件，增加独立事件模型，不把用户提供的交接文本提升为 system 指令。
- 证据：`SystemPromptIsolationTest` 覆盖同步/流式、多步工具调用、跨 config 复用历史、空人格、不可变/活列表、序列化与显式迁移；`WindowContextBuilderTest` 验证窗口不含人格而模型请求仍含人格；`RecordingFidelityTest` 验证每步真实请求；product YAML binder、chat、tavern 的回归验证跨模块接线。

## 正确理解“不进 messages”

这里的 messages 指 `AgentState.getMessages()`，不是 `ModelRequest.messages()`。

```text
AgentConfig.systemPrompt                 人格，不入历史
AgentState.messages                      历史，可 checkpoint
ContextBuilder 返回值                    当前请求的历史视图 + 临时上下文
ModelRequest.messages                    当前人格 + ContextBuilder 返回值
模型边界轨迹                              实际完整请求，包含人格
```

SYSTEM 是消息等级，不是“这一定是人格”的可靠标签。`ContextBuilder` 可以返回临时 SYSTEM 上下文，例如宿主编写的重试指导，但不能复制 config 人格，也不能把 SYSTEM 写回 state。对任意自定义 builder，框架无法从文本自动判断某条 SYSTEM 是否是假冒人格；这是扩展契约，不宣称具备语义识别能力。

运行时在 builder 前后检查 state，不允许 SYSTEM 历史进入模型请求；不会静默过滤、自动改写或删除旧快照。`AgentState` 仍是可变容器，这项检查不是类型级写入限制。

## 调用迁移

旧代码：

```java
AgentState state = new AgentState("You are A.", "hello");
AgentState resumed = session.toAgentState("You are A.");
```

新代码：

```java
AgentConfig config = new AgentConfig("A", "You are A.", modelClient, tools, 10);
AgentState state = new AgentState("hello");
AgentState resumed = session.toAgentState();
```

已有用户消息时，直接执行 loop；`agent.run("hello", state)` 会再追加一条用户消息，应避免重复。通常从空 state 开始最清晰：

```java
Agent agent = new SimpleAgent(config);
AgentState state = new AgentState();
agent.run("hello", state);
```

## 旧 checkpoint 迁移

先保留原始 checkpoint。反序列化后使用旧配置中已确认的人格做精确匹配迁移：

```java
AgentState restored = objectMapper.readValue(checkpointJson, AgentState.class);
restored.migrateLegacySystemPrompt(oldAgentConfig.getSystemPrompt());
loop.execute(newAgentConfig, restored);
```

- 只在第一条确实是旧人格时移除一条；文本不匹配就抛异常，消息不变。
- 保留原有 `currentStep`、`maxSteps`、status 等执行字段，不重置预算。
- 旧人格应从旧配置或已版本化配置取得，不能拿新 agent 的人格去猜。
- 中途的 SYSTEM 交接事件不被自动删除，必须单独审核迁移。新 `SharedAgentSession.handoff()` 将成员交接说明记录为 USER 历史，并保留结构化任务交接审计。
- 未迁移的 SYSTEM 历史会在调用模型前明确报错。不要用 `removeIf(role == SYSTEM)` 批量删除，因为会误伤无法辨认的历史事件。
- 这是显式迁移入口，不是自动扫描或修改磁盘中已有 checkpoint 的作业。

## Chat 路径适配

`ContextAssembler.prepare()` 按来源分离输入：`PersonaSource` 提供当前 config 人格，`HistorySource` 提供 state 历史，其他来源保持请求级临时上下文。已有 `assemble()` 保留作独立上下文预览，不再用于初始化 state。

`ChatEngine` 每次尝试创建 config 和干净 state，builder 将固定的当前轮上下文与本轮新增用户/助手/tool 消息组合。检索、场景、关系和重试提示不会写入 Done.state，也不会重复追加到第二次工具请求。`TurnTrace` 保留现有计数口径，包含被接受尝试的人格及重试上下文。

自定义 `ContextSource` 默认是临时上下文，不自动成为持久历史。重复配置两个 `PersonaSource` 会报配置错误。自定义 source 若自行复制人格，仍需调用方按上述契约迁移。

## 本次没有做的事

- 没有实现循环内 agent handoff、目标 registry 或可变 active config。跨 config 复用 state 的测试只证明人格与历史已解耦，不等于 handoff 已完成。
- 没有改变 maxSteps 生命周期策略或实现新的全局预算系统。
- 没有把纯条数窗口升级为 tool-call/result 成对裁剪，这仍是后续独立的上下文正确性问题。
- 没有改写旧审计轨迹或自动迁移磁盘 checkpoint，也没有发布 Maven 包。

## 验证记录

2026-09-08 23:43，使用 JDK 17 执行 `mvn -B -fae test`，全仓 1,354 项测试通过，0 failure/error/skipped。核心 42、memory 96、product 166、channel 82、trace 73、chat 129、tavern 111。`git diff --check` 通过。期间调度超时测试曾一次空值失败，复跑通过，未修改无关调度实现。
