# Stage 1-2 核心概念速查

> Java Agent Framework 的 12 个核心概念，从代码层面理解每个"是什么、有什么、谁用它、为什么这么设计"
> 对应阶段：Stage 1（模型调用层）+ Stage 2（最小 Agent Loop）

---

## 概念全景图

```
用户
  ↓
Agent ──持有──> AgentConfig（静态蓝图）
  │               ├── systemPrompt（人格）
  │               ├── ModelClient（用哪个大脑）
  │               ├── ToolRegistry（有哪些工具）
  │               └── maxSteps（最多几步）
  │
  ├──创建──> AgentState（运行时状态）
  │            ├── messages: List<ChatMessage>
  │            ├── currentStep
  │            ├── status
  │            └── lastError
  │
  └──委托──> AgentLoop
               │
               ├──循环──> 1. 构建 ModelRequest（messages + toolSchemas）
               │          2. 调用 ModelClient.chat()
               │             → 返回 ModelResponse
               │          3. 有 ToolCall？→ ToolExecutor.execute()
               │                          → 返回结果文本
               │          4. 无 ToolCall？→ 最终答案，退出循环
```

---

## 1. ChatRole -- 消息角色

**是什么**：枚举，标记一条消息是谁说的。

```java
public enum ChatRole {
    SYSTEM,     // 系统提示词（设定 Agent 人格）
    USER,       // 用户说的话
    ASSISTANT,  // 模型说的话
    TOOL        // 工具执行结果
}
```

**为什么需要**：大模型 API 要求每条消息标记角色。模型需要知道"这句是用户问的"还是"这句是我上次说的"。

**谁用它**：`ChatMessage` 的 `role` 字段。

---

## 2. ChatMessage -- 对话消息

**是什么**：一条对话消息，record 类型。

```java
public record ChatMessage(
    ChatRole role,           // 谁说的
    String content,          // 说了什么
    List<ToolCall> toolCalls, // 模型要调的工具（仅 ASSISTANT 有）
    String toolCallId,       // 工具结果对应的调用 ID（仅 TOOL 有）
    String name              // 工具名（仅 TOOL 有）
) {}
```

**怎么创建**：用工厂方法，不用直接 new：

```java
ChatMessage.system("你是助手")              // 系统提示词
ChatMessage.user("现在几点？")              // 用户输入
ChatMessage.assistant("现在是 14:30")       // 模型最终回答
ChatMessage.assistantWithTools(null, calls) // 模型说要调工具
ChatMessage.tool("call_1", "14:30")         // 工具执行结果
```

**一次对话的 messages 列表长这样**：

```
[
  {SYSTEM,    "你是助手"},
  {USER,      "现在几点？"},
  {ASSISTANT, null, toolCalls=[get_current_time]},  ← 模型要调工具
  {TOOL,      "当前时间: 14:30", toolCallId="call_1"}, ← 工具结果
  {ASSISTANT, "现在是 14:30"}                         ← 模型最终回答
]
```

**谁用它**：`AgentState.messages` 存它，`ModelRequest.messages` 把它发给模型。

---

## 3. ToolCall -- 工具调用

**是什么**：模型说"我要调工具"时的指令，record 类型。

```java
public record ToolCall(
    String id,           // 模型分配的调用 ID（用于匹配工具结果）
    String name,         // 要调哪个工具（如 "get_current_time"）
    JsonNode arguments   // 参数（JSON 格式，如 {"input": "hello"}）
) {}
```

**生命周期**：

```
模型返回 ToolCall(id="call_1", name="get_current_time", args={})
  ↓
ToolExecutor 拿到，执行工具
  ↓
结果包装成 ChatMessage.tool("call_1", "当前时间: 14:30")
  ↓
模型的下一轮会看到 toolCallId="call_1" 的结果
```

**为什么有 id**：模型可能同时调多个工具，id 用来把"哪个调用"对应"哪个结果"。

**谁用它**：`ModelResponse.toolCalls` 返回它，`ChatMessage.toolCalls` 存它，`ToolExecutor` 消费它。

---

## 4. ModelRequest -- 模型请求

**是什么**：发给大模型的一次请求，record + Builder。

```java
public record ModelRequest(
    String model,           // 用哪个模型（null = 用默认）
    List<ChatMessage> messages,  // 对话历史
    List<String> tools,     // 可用工具的 schema 列表
    Double temperature,     // 温度（0-2，越高越随机）
    Integer maxTokens,      // 最大输出 token
    boolean stream,         // 是否流式
    ResponseFormat responseFormat  // 结构化输出格式
) {}
```

**怎么创建**：用 Builder（因为字段多）：

```java
ModelRequest request = ModelRequest.builder()
    .messages(state.getMessages())
    .tools(registry.getToolSchemas())
    .temperature(0.7)
    .build();
```

**谁创建它**：`ReActAgentLoop.buildRequest()` 每轮循环构建一次。

**谁消费它**：`ModelClient.chat(request)`。

---

## 5. ModelResponse -- 模型响应

**是什么**：大模型返回的结果，record 类型。

```java
public record ModelResponse(
    String content,           // 文本回答（可能为 null，如果只返回工具调用）
    List<ToolCall> toolCalls, // 要调的工具（null = 不要调工具）
    String finishReason,      // 为什么停："stop" / "tool_calls" / "length" / "error"
    TokenUsage usage          // token 用量
) {
    public boolean hasToolCalls() { ... }  // 有没有工具调用
    public boolean isFinished() { ... }    // 是否最终答案
}
```

**finishReason 是关键**：

| finishReason | 含义 | Agent Loop 行为 |
|---|---|---|
| `"stop"` | 模型说完了 | 提取 content，结束循环 |
| `"tool_calls"` | 模型要调工具 | 执行工具，继续循环 |
| `"length"` | 超过 max_tokens | 结束循环（可能答案不完整） |
| `"error"` | 出错了 | 结束循环 |

**谁创建它**：`ModelClient.chat()` 返回它（真实实现如 OpenAiModelClient 解析 HTTP 响应构造）。

**谁消费它**：`ReActAgentLoop` 判断 `hasToolCalls()` 决定下一步。

---

## 6. StreamEvent -- 流式事件

**是什么**：流式调用时逐个返回的事件，sealed interface。

```java
public sealed interface StreamEvent {
    record ContentDelta(String delta) implements StreamEvent {}      // 一段文本
    record ToolCallEvent(ToolCall toolCall) implements StreamEvent {} // 一个工具调用
    record Done(ModelResponse finalResponse) implements StreamEvent {} // 流结束
    record Error(String message, Throwable cause) implements StreamEvent {} // 出错
}
```

**为什么用 sealed interface**：

1. 事件类型封闭：只有这 4 种，不会有第五种
2. Java 21 模式匹配时编译器保证穷尽性（漏处理一个会编译报错）
3. 比"一个基类 + N 个子类"更安全

**流式 vs 同步的区别**：

```
同步：chat() → 等几秒 → 一次拿到完整 ModelResponse
流式：stream() → 立即开始 → 逐个吐 StreamEvent：
  ContentDelta("你")
  ContentDelta("好")
  ContentDelta("！")
  Done(finalResponse)
```

**谁创建它**：`ModelClient.stream()` 返回 `Stream<StreamEvent>`。

**谁消费它**：调用者（如未来 Stage 的 SSE 推送到前端）。

---

## 7. ModelClient -- 模型调用接口

**是什么**：调用大模型的统一接口。

```java
public interface ModelClient {
    ModelResponse chat(ModelRequest request);      // 同步
    Stream<StreamEvent> stream(ModelRequest request); // 流式
}
```

**为什么是接口不是类**：Agent 代码依赖接口，不依赖具体模型 SDK。切换 OpenAI → Claude 不改 Agent 逻辑。

**三种实现**：

```
ModelClient（接口）
├── MockModelClient     ← 假模型，测试用（脚本模式 / 规则模式）
├── OpenAiModelClient   ← 调 OpenAI / Azure / Ollama / 火山方舟
└── AnthropicModelClient ← 调 Claude
```

**四种装饰器**（也是 ModelClient，包着另一个 ModelClient）：

```
RetryModelClient         ← 失败重试
TimeoutModelClient       ← 超时控制
FallbackModelClient      ← 主备切换
StructuredOutputModelClient ← JSON 验证
```

**谁用它**：`AgentConfig.modelClient` 持有它，`ReActAgentLoop` 调它的 `chat()`。

---

## 8. AgentConfig -- Agent 静态配置

**是什么**：创建 Agent 时的"蓝图"，不可变。

```java
public class AgentConfig {
    String name;              // Agent 名字（日志/追踪用）
    String systemPrompt;      // 系统提示词（Agent 人格）
    ModelClient modelClient;  // 用哪个模型
    ToolRegistry toolRegistry;// 有哪些工具
    int maxSteps;             // 最多循环几步（默认 10）
}
```

**怎么用**：

```java
AgentConfig config = new AgentConfig(
    "time-agent",
    "你是时间助手，需要时调用 get_current_time 工具",
    modelClient,
    registry,
    10
);
Agent agent = new SimpleAgent(config);
```

**为什么叫"静态"**：创建后不变。运行时的动态状态在 AgentState 里。

**谁用它**：`SimpleAgent` 持有它，`ReActAgentLoop.execute(config, state)` 接收它。

---

## 9. AgentState -- Agent 运行时状态

**是什么**：一次 Agent 执行的运行时状态，mutable。

```java
public class AgentState {
    List<ChatMessage> messages;  // 对话历史（不断追加）
    int currentStep;             // 当前第几步
    int maxSteps;                // 最大步数
    Status status;               // 当前状态（状态机）
    String lastError;            // 最后一个错误

    enum Status {
        IDLE,               // 未开始
        RUNNING,            // 运行中（调模型）
        EXECUTING_TOOL,     // 执行工具中
        DONE,               // 正常完成
        MAX_STEPS_EXCEEDED, // 超出步数
        ERROR               // 出错
    }
}
```

**状态机流转**：

```
IDLE → RUNNING → EXECUTING_TOOL → RUNNING → ... → DONE
                                          ↘ ERROR
                                          ↘ MAX_STEPS_EXCEEDED
```

**为什么是 mutable**：每轮循环要往 messages 里追加消息，改 currentStep。如果 immutable 每轮都要复制整个列表，Stage 1-2 优先简洁。

**snapshot() 方法**：复制当前状态。Stage 6 做 Checkpoint 时会用。

**谁创建它**：`SimpleAgent.run()` 创建（new AgentState(systemPrompt, userInput)）。

**谁修改它**：`ReActAgentLoop.execute()` 每轮循环修改（addMessage、incrementStep、setStatus）。

---

## 10. Agent -- Agent 入口

**是什么**：用户调用的入口接口。

```java
public interface Agent {
    String run(String userInput);                    // 一次性调用
    String run(String userInput, AgentState state);   // 多轮对话（传入已有状态）
    AgentConfig getConfig();
}
```

**SimpleAgent 实现**：

```java
public class SimpleAgent implements Agent {
    // run() 内部流程：
    // 1. 创建 AgentState（systemPrompt + userInput）
    // 2. 调 loop.execute(config, state)
    // 3. 从 state.messages 末尾找最后一条 ASSISTANT 消息
    // 4. 返回它的 content
}
```

**为什么接口这么简单**：调用者只需要"问问题、得答案"。复杂的循环逻辑藏在 AgentLoop 里。

**多轮对话怎么用**：

```java
AgentState state = new AgentState();
agent.run("你好", state);      // 第 1 轮
agent.run("刚才我说了什么？", state);  // 第 2 轮，state 保留了第 1 轮的历史
```

---

## 11. AgentLoop -- Agent 循环

**是什么**：Agent 的核心执行逻辑，一个函数。

```java
public interface AgentLoop {
    AgentState execute(AgentConfig config, AgentState state);
}
```

**为什么是"函数"不是"线程"**：

```
不是: class ReActAgentLoop extends Thread { run() { ... } }
而是: AgentState execute(AgentConfig, AgentState) { ... }
```

- 可测试：传输入，看输出，不需要启动线程
- 可组合：多个 Loop 可以串联
- 可暂停：Stage 6 在任意步骤 checkpoint
- 不绑定线程模型：调用者决定同步还是异步

**ReActAgentLoop 的循环逻辑**：

```java
while (state.hasStepsRemaining() && !state.isTerminal()) {
    // 1. 从 state 构建 ModelRequest（messages + toolSchemas）
    // 2. modelClient.chat(request) -> ModelResponse
    // 3. if response.hasToolCalls():
    //      执行每个 ToolCall -> 结果加入 state.messages
    //      continue 循环
    // 4. else:
    //      把 response.content 加入 state.messages
    //      status = DONE, 退出循环
}
```

**ReAct = Reason + Act**：

```
Reason: 模型思考"我要调 get_current_time"
Act:    执行工具
Reason: 模型看到结果，思考"现在可以回答了"
Act:    返回最终答案
```

---

## 12. Tool / ToolRegistry / ToolExecutor -- 工具三件套

### Tool -- 工具接口

```java
public interface Tool {
    String getName();              // 工具名（模型用它来调用）
    String getDescription();       // 描述（告诉模型什么时候用）
    String getParametersSchema();  // 参数 JSON Schema（告诉模型怎么传参）
    String execute(JsonNode args); // 执行，返回文本结果
}
```

**已有实现**：

```
EchoTool          ← 回显输入（测试用）
CurrentTimeTool   ← 返回当前时间（测试用）
```

### ToolRegistry -- 工具注册表

```java
public interface ToolRegistry {
    void register(Tool tool);        // 注册
    void unregister(String name);     // 注销
    Optional<Tool> getTool(String name);  // 按名查找
    List<Tool> listTools();           // 列出所有
    List<String> getToolSchemas();    // 导出 schema 列表（发给模型）
}
```

**实现**：`InMemoryToolRegistry`（一个 `Map<String, Tool>`）

**为什么 Registry 和 Executor 分开**：

```
Registry = "有什么工具"（元数据管理）
Executor = "怎么安全执行"（行为控制）
```

后续 Executor 会变成 Pipeline：

```
ToolCall → Policy Check → Timeout → Sandbox → Execute → Audit Log
```

如果合在一起，Policy/Sandbox/Audit 很难插进去。

### ToolExecutor -- 工具执行器

```java
public interface ToolExecutor {
    String execute(ToolCall toolCall);  // 执行工具调用，返回文本
}
```

**DefaultToolExecutor 做了什么**：

```java
1. registry.getTool(toolCall.name())  // 从注册表找工具
2. tool.execute(toolCall.arguments()) // 执行
3. 如果异常：
     不抛异常！
     包装成文本："[ERROR] Tool 'xxx' failed: ..."
     → 模型会看到这个错误文本，自己决定怎么办
```

**为什么异常不抛出而是包装成文本**：工具失败是"正常的业务情况"，模型应该知道失败了并决定怎么办（换个工具？换个参数？直接道歉？）。如果抛异常，Agent 循环就断了。

---

## 概念之间的关系总结

```
AgentConfig（静态蓝图）
  ├── ModelClient ──→ 装饰器链 ──→ 真实模型（OpenAI / Claude / Mock）
  ├── ToolRegistry ──→ Tool（EchoTool / CurrentTimeTool）
  └── maxSteps

SimpleAgent.run(userInput)
  │
  ├── 创建 AgentState（messages = [system, user]）
  │
  └── ReActAgentLoop.execute(config, state)
        │
        └── 循环：
            ├── 构建 ModelRequest（从 state.messages + registry.getToolSchemas）
            ├── ModelClient.chat(request) → ModelResponse
            │   ├── content = "文本" 或 null
            │   ├── toolCalls = [ToolCall] 或 null
            │   └── finishReason = "stop" / "tool_calls"
            │
            ├── 有 ToolCall？
            │   ├── ToolExecutor.execute(toolCall)
            │   │   └── Registry.getTool(name) → Tool.execute(args) → "结果文本"
            │   ├── state.addMessage(assistantWithTools)
            │   └── state.addMessage(tool result)
            │
            └── 无 ToolCall？
                ├── state.addMessage(assistant content)
                └── status = DONE, 退出

SimpleAgent 从 state.messages 末尾提取 ASSISTANT 消息 → 返回 String
```

---

## 面试一句话

> 我的 Agent 框架把"决策"（AgentLoop 调模型）、"执行"（ToolExecutor 调工具）、"状态"（AgentState 存对话历史）三者分离。
> ModelClient 是接口，底层可切 OpenAI / Claude / Mock，上层通过装饰器链实现重试、超时、降级。
> AgentLoop 是函数不是线程，可测试、可暂停、可组合。
> ToolRegistry 和 ToolExecutor 分离，为后续 Policy / Sandbox / Audit 留扩展位。
