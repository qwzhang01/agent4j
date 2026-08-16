# Architecture Diagram — Stage 1-2

> Java Agent Framework 已完成阶段架构图
> 阶段：Stage 1（模型调用层）+ Stage 2（最小 Agent Loop）
> 时间：2026-08-12 ~ 2026-08-13

---

## 1. 整体分层架构（Layered Architecture）

```mermaid
graph TB
    subgraph Examples["📦 examples 模块"]
        MockAgentExample["MockAgentExample"]
        DecoratedExample["DecoratedModelClientExample"]
    end

    subgraph AgentLayer["🤖 Agent 层（agent-core / agent 包）"]
        Agent["Agent<br/><i>interface</i>"]
        SimpleAgent["SimpleAgent<br/><i>impl</i>"]
        AgentConfig["AgentConfig<br/><i>静态配置</i>"]
        AgentState["AgentState<br/><i>运行时状态</i>"]
        AgentLoop["AgentLoop<br/><i>interface</i>"]
        ReActAgentLoop["ReActAgentLoop<br/><i>impl</i>"]
    end

    subgraph ModelLayer["🔌 模型调用层（agent-core / client 包）"]
        ModelClient["ModelClient<br/><i>interface</i>"]
        RetryClient["RetryModelClient"]
        TimeoutClient["TimeoutModelClient"]
        FallbackClient["FallbackModelClient"]
        StructuredClient["StructuredOutputModelClient"]
    end

    subgraph ToolLayer["🛠️ 工具层（agent-core / tool 包）"]
        Tool["Tool<br/><i>interface</i>"]
        ToolRegistry["ToolRegistry<br/><i>interface</i>"]
        ToolExecutor["ToolExecutor<br/><i>interface</i>"]
        InMemoryRegistry["InMemoryToolRegistry"]
        DefaultExecutor["DefaultToolExecutor"]
    end

    subgraph DataLayer["📊 数据模型（agent-core / model 包）"]
        ChatMessage["ChatMessage<br/><i>record</i>"]
        ModelRequest["ModelRequest<br/><i>record + Builder</i>"]
        ModelResponse["ModelResponse<br/><i>record</i>"]
        ToolCall["ToolCall<br/><i>record</i>"]
        StreamEvent["StreamEvent<br/><i>sealed interface</i>"]
        ChatRole["ChatRole<br/><i>enum</i>"]
    end

    subgraph ModelImpl["📦 agent-model 模块"]
        MockModelClient["MockModelClient"]
        OpenAiModelClient["OpenAiModelClient"]
        EchoTool["EchoTool"]
        CurrentTimeTool["CurrentTimeTool"]
    end

    %% 调用关系
    Examples --> SimpleAgent
    Examples --> ModelLayer

    SimpleAgent -.->|implements| Agent
    SimpleAgent --> AgentConfig
    SimpleAgent --> AgentState
    SimpleAgent --> AgentLoop
    ReActAgentLoop -.->|implements| AgentLoop
    ReActAgentLoop --> ModelClient
    ReActAgentLoop --> ToolExecutor

    %% 装饰器委托关系
    StructuredClient -->|wraps| FallbackClient
    FallbackClient -->|wraps| TimeoutClient
    TimeoutClient -->|wraps| RetryClient
    RetryClient -->|wraps| ModelClient

    %% 实现关系
    MockModelClient -.->|implements| ModelClient
    OpenAiModelClient -.->|implements| ModelClient
    InMemoryRegistry -.->|implements| ToolRegistry
    DefaultExecutor -.->|implements| ToolExecutor
    DefaultExecutor --> ToolRegistry
    ReActAgentLoop --> DefaultExecutor

    %% 工具实现
    EchoTool -.->|implements| Tool
    CurrentTimeTool -.->|implements| Tool
    InMemoryRegistry --> Tool

    %% 数据模型依赖
    ModelClient --> ModelRequest
    ModelClient --> ModelResponse
    ModelClient --> StreamEvent
    ModelRequest --> ChatMessage
    ModelRequest --> ToolCall
    ModelResponse --> ToolCall
    ChatMessage --> ChatRole
    AgentState --> ChatMessage
    AgentConfig --> ModelClient
    AgentConfig --> ToolRegistry

    %% 样式
    classDef interface fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    classDef impl fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef data fill:#fff3e0,stroke:#ef6c00,stroke-width:2px
    classDef mock fill:#e8f5e9,stroke:#388e3c,stroke-width:2px

    class Agent,ModelClient,Tool,ToolRegistry,ToolExecutor,AgentLoop interface
    class SimpleAgent,ReActAgentLoop,InMemoryRegistry,DefaultExecutor impl
    class ChatMessage,ModelRequest,ModelResponse,ToolCall,StreamEvent,ChatRole,AgentConfig,AgentState data
    class MockModelClient,OpenAiModelClient,EchoTool,CurrentTimeTool mock
```

---

## 2. 模块依赖关系

```mermaid
graph LR
    subgraph Maven["Maven 多模块"]
        Pom["pom.xml<br/><i>父 POM</i>"]
        Core["agent-core<br/><i>零外部依赖</i>"]
        Model["agent-model<br/><i>依赖 agent-core</i>"]
        Examples["examples<br/><i>依赖 agent-core + agent-model</i>"]
    end

    Pom --> Core
    Pom --> Model
    Pom --> Examples
    Model --> Core
    Examples --> Core
    Examples --> Model

    classDef parent fill:#fce4ec,stroke:#c62828,stroke-width:2px
    classDef core fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef module fill:#f1f8e9,stroke:#33691e,stroke-width:2px

    class Pom parent
    class Core core
    class Model,Examples module
```

---

## 3. 装饰器链（Decorator Chain）

> ModelClient 装饰器组合是 Stage 1 的核心设计。
> 从外到内依次执行，每层只做一件事，可自由组合。

```mermaid
graph LR
    Caller["ReActAgentLoop<br/>.chat(request)"]
    Structured["StructuredOutputModelClient<br/>━━━━━━━━━━━━━━━━<br/>职责：JSON 验证 + 修正重试<br/>机制：responseFormat + 校验"]
    Fallback["FallbackModelClient<br/>━━━━━━━━━━━━━━━━<br/>职责：多级链式降级<br/>机制：主模型失败 → 备用1 → 备用2"]
    Timeout["TimeoutModelClient<br/>━━━━━━━━━━━━━━━━<br/>职责：强制超时<br/>机制：CompletableFuture.orTimeout"]
    Retry["RetryModelClient<br/>━━━━━━━━━━━━━━━━<br/>职责：重试瞬时故障<br/>机制：指数退避 + 错误码分类"]
    Real["实际 ModelClient<br/>━━━━━━━━━━━━━━━━<br/>OpenAiModelClient<br/>或 MockModelClient"]

    Caller --> Structured
    Structured -->|delegate| Fallback
    Fallback -->|primary / fallbacks| Timeout
    Timeout -->|delegate| Retry
    Retry -->|delegate| Real

    Real -.->|HTTP/SSE| Provider["OpenAI / Azure / Ollama / 火山方舟"]

    classDef caller fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef decorator fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    classDef real fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
    classDef external fill:#fafafa,stroke:#999,stroke-dasharray: 5 5

    class Caller caller
    class Structured,Fallback,Timeout,Retry decorator
    class Real real
    class Provider external
```

### 重试错误码分类

```mermaid
graph TD
    Error["ModelException"]
    Retryable{"shouldRetry?"}

    TIMEOUT["TIMEOUT<br/>✅ 重试<br/>网络抖动"]
    RateLimit["RATE_LIMITED<br/>✅ 重试<br/>等待限流解除"]
    Network["NETWORK_ERROR<br/>✅ 重试<br/>瞬时故障"]
    ModelErr["MODEL_ERROR<br/>✅ 重试<br/>服务端可能恢复"]
    Unknown["UNKNOWN<br/>✅ 重试<br/>兜底"]

    Auth["AUTH_ERROR<br/>❌ 不重试<br/>不会自己变好"]
    Invalid["INVALID_REQUEST<br/>❌ 不重试<br/>客户端错误"]

    Error --> Retryable
    Retryable -->|Yes| TIMEOUT
    Retryable -->|Yes| RateLimit
    Retryable -->|Yes| Network
    Retryable -->|Yes| ModelErr
    Retryable -->|Yes| Unknown
    Retryable -->|No| Auth
    Retryable -->|No| Invalid

    classDef retry fill:#c8e6c9,stroke:#388e3c
    classDef noretry fill:#ffcdd2,stroke:#c62828

    class TIMEOUT,RateLimit,Network,ModelErr,Unknown retry
    class Auth,Invalid noretry
```

---

## 4. ReAct 循环流程

> Stage 2 核心：ReActAgentLoop 是整个框架的心脏。
> AgentLoop 是函数不是线程——可测试、可组合、可暂停。

```mermaid
flowchart TD
    Start(["SimpleAgent.run(userInput)"])
    Init["初始化 AgentState<br/>systemPrompt + userMessage<br/>status = IDLE"]
    LoopEntry{"state.hasStepsRemaining()<br/>AND<br/>!state.isTerminal()?"}

    Build["1. 构建 ModelRequest<br/>messages + toolSchemas"]
    Call["2. modelClient.chat(request)"]
    CheckResp{"response.hasToolCalls()?"}

    ExecTools["3. 执行工具<br/>for each toolCall:<br/>  toolExecutor.execute(toolCall)<br/>  → addMessage(toolResult)<br/>status = EXECUTING_TOOL"]
    AddAssistant["addMessage(assistantWithTools)"]
    Continue["status = RUNNING<br/>→ 回到循环顶部"]

    AddFinal["4. addMessage(assistant)<br/>status = DONE"]
    Return["return finalAnswer"]

    MaxStep["status = MAX_STEPS_EXCEEDED<br/>return warning"]
    Error["status = ERROR<br/>lastError = e.message<br/>return error"]

    Start --> Init --> LoopEntry
    LoopEntry -->|Yes| Build
    Build --> Call
    Call --> CheckResp
    CheckResp -->|Yes| AddAssistant
    AddAssistant --> ExecTools
    ExecTools --> Continue
    Continue --> LoopEntry
    CheckResp -->|No| AddFinal
    AddFinal --> Return

    LoopEntry -->|No| MaxStep

    Call -->|Exception| Error

    classDef start fill:#c8e6c9,stroke:#388e3c,stroke-width:2px
    classDef process fill:#e3f2fd,stroke:#1565c0
    classDef decision fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef success fill:#a5d6a7,stroke:#2e7d32,stroke-width:2px
    classDef error fill:#ffcdd2,stroke:#c62828,stroke-width:2px

    class Start start
    class Build,Call,ExecTools,Continue,AddAssistant,AddFinal process
    class LoopEntry,CheckResp decision
    class Return success
    class MaxStep,Error error
```

---

## 5. Agent 状态机

```mermaid
stateDiagram-v2
    [*] --> IDLE: new AgentState()

    IDLE --> RUNNING: loop.execute()
    RUNNING --> EXECUTING_TOOL: response.hasToolCalls()
    EXECUTING_TOOL --> RUNNING: tools executed, results added

    RUNNING --> DONE: response is final answer
    RUNNING --> ERROR: model call throws exception
    RUNNING --> MAX_STEPS_EXCEEDED: currentStep >= maxSteps

    EXECUTING_TOOL --> ERROR: tool execution throws

    DONE --> [*]
    ERROR --> [*]
    MAX_STEPS_EXCEEDED --> [*]

    note right of RUNNING
        isTerminal() = false
        can continue loop
    end note

    note right of DONE
        isTerminal() = true
        final answer extracted
    end note
```

---

## 6. StreamEvent 类型体系（Sealed Interface）

```mermaid
classDiagram
    class StreamEvent {
        <<sealed interface>>
    }
    class ContentDelta {
        +String delta
        <<record>>
    }
    class ToolCallEvent {
        +ToolCall toolCall
        <<record>>
    }
    class Done {
        +ModelResponse finalResponse
        <<record>>
    }
    class Error {
        +String message
        +Throwable cause
        <<record>>
    }

    StreamEvent <|.. ContentDelta
    StreamEvent <|.. ToolCallEvent
    StreamEvent <|.. Done
    StreamEvent <|.. Error

    class ToolCall {
        +String id
        +String name
        +JsonNode arguments
        <<record>>
    }
    ToolCallEvent --> ToolCall
```

---

## 7. 数据模型关系

```mermaid
classDiagram
    class ChatRole {
        <<enum>>
        SYSTEM
        USER
        ASSISTANT
        TOOL
    }

    class ChatMessage {
        +ChatRole role
        +String content
        +List~ToolCall~ toolCalls
        +String toolCallId
        +String name
        <<record>>
    }

    class ModelRequest {
        +String model
        +List~ChatMessage~ messages
        +List~String~ tools
        +Double temperature
        +Integer maxTokens
        +boolean stream
        +ResponseFormat responseFormat
        <<record + Builder>>
    }

    class ModelRequest_ResponseFormat {
        +String type
        +String jsonSchema
        <<record>>
    }

    class ModelResponse {
        +String content
        +List~ToolCall~ toolCalls
        +String finishReason
        +TokenUsage usage
        <<record>>
    }

    class ModelResponse_TokenUsage {
        +int promptTokens
        +int completionTokens
        +int totalTokens
        <<record>>
    }

    class ToolCall {
        +String id
        +String name
        +JsonNode arguments
        <<record>>
    }

    class AgentConfig {
        +String name
        +String systemPrompt
        +ModelClient modelClient
        +ToolRegistry toolRegistry
        +int maxSteps
    }

    class AgentState {
        +List~ChatMessage~ messages
        +int currentStep
        +int maxSteps
        +Status status
        +String lastError
        +snapshot() AgentState
    }

    ModelRequest --> ChatMessage
    ModelRequest --> ModelRequest_ResponseFormat
    ModelResponse --> ToolCall
    ModelResponse --> ModelResponse_TokenUsage
    ChatMessage --> ChatRole
    ChatMessage --> ToolCall
    AgentConfig --> ModelClient
    AgentState --> ChatMessage
```

---

## 8. 工具子系统

```mermaid
graph TB
    subgraph Interfaces
        Tool["Tool&lt;i&gt;interface&lt;/i&gt;<br/>getName()<br/>getDescription()<br/>getParametersSchema()<br/>execute(JsonNode)"]
        Registry["ToolRegistry&lt;i&gt;interface&lt;/i&gt;<br/>register()<br/>unregister()<br/>getTool()<br/>listTools()<br/>getToolSchemas()"]
        Executor["ToolExecutor&lt;i&gt;interface&lt;/i&gt;<br/>execute(ToolCall)"]
    end

    subgraph Implementations
        InMemory["InMemoryToolRegistry<br/>Map&lt;String, Tool&gt;"]
        Default["DefaultToolExecutor<br/>错误包装为文本"]
    end

    subgraph MockTools["Mock 工具（agent-model）"]
        Echo["EchoTool<br/>回显输入"]
        Time["CurrentTimeTool<br/>当前时间"]
    end

    subgraph Future["未来扩展（尚未实现）"]
        Policy["Policy Check<br/>Stage 9"]
        Sandbox["Sandbox<br/>Stage 4"]
        Audit["Audit Log<br/>Stage 9"]
        Timeout["Tool Timeout<br/>Stage 6"]
    end

    InMemory -.->|implements| Registry
    Default -.->|implements| Executor
    Default -->|looks up| Registry
    Echo -.->|implements| Tool
    Time -.->|implements| Tool
    InMemory -->|stores| Tool

    Default -.->|future| Policy
    Policy -.->|future| Timeout
    Timeout -.->|future| Sandbox
    Sandbox -.->|future| Audit

    classDef iface fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    classDef impl fill:#f3e5f5,stroke:#7b1fa2
    classDef mock fill:#e8f5e9,stroke:#388e3c
    classDef future fill:#fafafa,stroke:#999,stroke-dasharray: 5 5

    class Tool,Registry,Executor iface
    class InMemory,Default impl
    class Echo,Time mock
    class Policy,Sandbox,Audit,Timeout future
```

---

## 9. 完整调用链路（End-to-End）

```mermaid
sequenceDiagram
    participant User
    participant Example as MockAgentExample
    participant Agent as SimpleAgent
    participant Loop as ReActAgentLoop
    participant ModelClient as ModelClient<br/>(装饰器链)
    participant ToolExec as DefaultToolExecutor
    participant Registry as InMemoryToolRegistry
    participant LLM as LLM Provider<br/>(OpenAI/Mock)

    User->>Example: main()
    Example->>Agent: run("现在几点？")

    Note over Agent: 创建 AgentState<br/>systemPrompt + userMessage

    Agent->>Loop: execute(config, state)
    Note over Loop: status = RUNNING

    loop ReAct 循环
        Loop->>Loop: buildRequest(messages + tools)
        Loop->>ModelClient: chat(request)

        Note over ModelClient: Structured → Fallback<br/>→ Timeout → Retry → Real

        ModelClient->>LLM: HTTP POST /chat/completions
        LLM-->>ModelClient: response (toolCalls)

        alt 有工具调用
            ModelClient-->>Loop: ModelResponse(toolCalls)
            Loop->>Loop: addMessage(assistantWithTools)
            Loop->>ToolExec: execute(toolCall)
            ToolExec->>Registry: getTool("get_current_time")
            Registry-->>ToolExec: CurrentTimeTool
            ToolExec->>ToolExec: tool.execute(arguments)
            ToolExec-->>Loop: "当前时间: 14:30"
            Loop->>Loop: addMessage(toolResult)
            Note over Loop: status = EXECUTING_TOOL → RUNNING
        else 最终答案
            ModelClient-->>Loop: ModelResponse(content, "stop")
            Loop->>Loop: addMessage(assistant)
            Note over Loop: status = DONE
        end
    end

    Loop-->>Agent: finalState
    Agent-->>Example: "现在是 14:30"
    Example-->>User: 打印结果
```

---

## 10. 文件清单与所属层

```
agent-core/src/main/java/com/seven/agent/core/
├── model/                          # ─── 数据模型层（零依赖，纯 record/enum）
│   ├── ChatRole.java              #     enum: SYSTEM / USER / ASSISTANT / TOOL
│   ├── ChatMessage.java           #     record: 对话消息
│   ├── ToolCall.java              #     record: 工具调用描述
│   ├── ModelRequest.java          #     record + Builder: 模型请求
│   ├── ModelResponse.java         #     record: 模型响应（含 TokenUsage）
│   ├── StreamEvent.java           #     sealed interface: 流式事件
│   └── Builder.java               #     ModelRequest.Builder
│
├── client/                         # ─── 模型调用层（接口 + 装饰器）
│   ├── ModelClient.java           #     interface: chat() + stream()
│   ├── ModelException.java        #     异常 + ErrorCode 枚举
│   ├── RetryModelClient.java      #     装饰器：重试 + 指数退避
│   ├── TimeoutModelClient.java    #     装饰器：超时控制
│   ├── FallbackModelClient.java   #     装饰器：多级降级
│   └── StructuredOutputModelClient.java  # 装饰器：JSON 验证
│
├── tool/                           # ─── 工具层（接口 + 默认实现）
│   ├── Tool.java                  #     interface: 工具契约
│   ├── ToolException.java         #     工具异常
│   ├── ToolRegistry.java          #     interface: 注册表
│   ├── ToolExecutor.java          #     interface: 执行器
│   ├── InMemoryToolRegistry.java  #     默认注册表
│   └── DefaultToolExecutor.java   #     默认执行器
│
└── agent/                          # ─── Agent 层（循环 + 状态）
    ├── Agent.java                  #     interface: run()
    ├── AgentConfig.java            #     静态配置
    ├── AgentState.java             #     运行时状态 + 状态机
    ├── AgentLoop.java              #     interface: execute()
    ├── ReActAgentLoop.java         #     ReAct 循环实现
    └── SimpleAgent.java            #     默认 Agent 实现

agent-model/src/main/java/com/seven/agent/model/
├── mock/                           # ─── Mock 实现
│   ├── MockModelClient.java       #     脚本模式 + 规则模式
│   ├── EchoTool.java              #     回显工具
│   └── CurrentTimeTool.java       #     时间工具
└── openai/                         # ─── OpenAI 实现
    └── OpenAiModelClient.java      #     Java 21 HttpClient + SSE

examples/src/main/java/com/seven/agent/examples/
├── MockAgentExample.java          #     基础 Agent 示例
└── DecoratedModelClientExample.java  #  装饰器组合示例
```

---

## 设计原则总结

| 原则 | 体现 |
|------|------|
| **接口优先** | ModelClient / Tool / ToolRegistry / ToolExecutor / Agent / AgentLoop 全是接口 |
| **装饰器模式** | Retry / Timeout / Fallback / StructuredOutput 四层装饰器可自由组合 |
| **Registry-Executor 分离** | 元数据管理 vs 安全执行分离，为 Policy/Sandbox 留口子 |
| **函数式 AgentLoop** | `execute(config, state) → state`，不是 Thread/Runnable |
| **Sealed Interface** | StreamEvent 封闭类型，编译器保证模式匹配穷尽性 |
| **Mutable State + Snapshot** | AgentState 可变但支持 snapshot()，为 Checkpoint 预留 |
| **Builder 模式** | ModelRequest 用 Builder 构建，支持流式 API |
| **错误码分类** | ModelException.ErrorCode 区分可重试/不可重试 |

---

## 后续阶段预览

```mermaid
graph LR
    S12["Stage 1-2 ✅<br/>模型调用 + Agent Loop"]
    S3["Stage 3 ⬜<br/>插件化热插拔"]
    S4["Stage 4 ⬜<br/>沙箱隔离"]
    S5["Stage 5 ⬜<br/>Workflow Graph"]
    S6["Stage 6 ⬜<br/>Checkpoint"]
    S7["Stage 7 ⬜<br/>异步调度"]
    S8["Stage 8 ⬜<br/>记忆系统"]
    S9["Stage 9 ⬜<br/>安全审计"]
    S10["Stage 10 ⬜<br/>MCP 集成"]
    S12["Stage 12 ⬜<br/>频道共享"]
    S14["Stage 14 ⬜<br/>RL 轨迹导出"]
    S18["Stage 18 ⬜<br/>可观测性"]

    S12 --> S3 --> S4 --> S5 --> S6
    S6 --> S7 --> S8 --> S9 --> S10
    S10 --> S12 --> S14 --> S18

    classDef done fill:#c8e6c9,stroke:#388e3c,stroke-width:3px
    classDef todo fill:#fafafa,stroke:#999,stroke-dasharray: 5 5

    class S12 done
    class S3,S4,S5,S6,S7,S8,S9,S10,S12,S14,S18 todo
```
