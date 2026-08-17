# 执行流程图 - Stage 1-2

> Java Agent Framework 代码执行流程全景
> 从入口到出口，谁触发谁，数据怎么流

---

## 1. 全局调用图（Call Graph）

> 谁调用谁。箭头 = "触发/调用"。

```mermaid
graph TB
    %% ============ 入口层 ============
    subgraph Entry["入口（examples 模块）"]
        MockMain["MockAgentExample.main()<br/>━━━━━━━━━━━━━━━━<br/>脚本模式 Demo"]
        DecoMain["DecoratedModelClientExample.main()<br/>━━━━━━━━━━━━━━━━<br/>装饰器组合 Demo"]
    end

    %% ============ 装配阶段（在 main 中手动组装）============
    subgraph Assembly["装配阶段（main 方法内）"]
        direction LR
        AsmModel["创建 ModelClient<br/>Mock / OpenAI / Anthropic"]
        AsmRegistry["创建 ToolRegistry<br/>注册 Tool"]
        AsmConfig["创建 AgentConfig<br/>name + prompt + client + registry"]
        AsmAgent["new SimpleAgent(config)"]
    end

    %% ============ 运行阶段 ============
    subgraph Run["运行阶段"]
        RunAgent["agent.run(userInput)"]
    end

    %% ============ Agent 层 ============
    subgraph AgentLayer["Agent 层"]
        SimpleRun["SimpleAgent.run()<br/>━━━━━━━━━━━━━━━━<br/>1. 创建/更新 AgentState<br/>2. 委托 loop.execute()<br/>3. 提取最终回答"]
        LoopExec["ReActAgentLoop.execute()<br/>━━━━━━━━━━━━━━━━<br/>while 循环：<br/>  buildRequest -> callModel<br/>  -> handleResponse"]
        BuildReq["buildRequest()<br/>━━━━━━━━━━━━━━━━<br/>messages + toolSchemas<br/>-> ModelRequest"]
    end

    %% ============ ModelClient 装饰器链 ============
    subgraph Decorators["ModelClient 装饰器链"]
        D1["StructuredOutputModelClient.chat()<br/>━━━━━━━━━━━━━━━━<br/>验证 JSON 有效性<br/>失败追加修正提示重试"]
        D2["FallbackModelClient.chat()<br/>━━━━━━━━━━━━━━━━<br/>主模型失败 -> 遍历 fallbacks"]
        D3["TimeoutModelClient.chat()<br/>━━━━━━━━━━━━━━━━<br/>CompletableFuture.orTimeout"]
        D4["RetryModelClient.chat()<br/>━━━━━━━━━━━━━━━━<br/>指数退避 + 错误码判断"]
    end

    %% ============ 实际 ModelClient ============
    subgraph RealClient["实际 ModelClient"]
        Mock["MockModelClient.chat()<br/>━━━━━━━━━━━━━━━━<br/>脚本模式：poll 队列<br/>规则模式：关键词匹配"]
        OpenAI["OpenAiModelClient.chat()<br/>━━━━━━━━━━━━━━━━<br/>HttpClient POST<br/>+ JSON 解析"]
        Anthropic["AnthropicModelClient.chat()<br/>━━━━━━━━━━━━━━━━<br/>HttpClient POST<br/>+ content blocks 解析"]
    end

    %% ============ Tool 层 ============
    subgraph ToolLayer["Tool 层"]
        ExecTool["DefaultToolExecutor.execute()<br/>━━━━━━━━━━━━━━━━<br/>1. registry.getTool()<br/>2. tool.execute()<br/>3. 异常包装为文本"]
        Registry["InMemoryToolRegistry<br/>━━━━━━━━━━━━━━━━<br/>Map<String, Tool>"]
        Echo["EchoTool.execute()"]
        Time["CurrentTimeTool.execute()"]
    end

    %% ============ 外部 ============
    LLM["LLM Provider<br/>OpenAI / Azure / Claude / Ollama"]

    %% ============ 装配流程 ============
    MockMain --> AsmModel
    MockMain --> AsmRegistry
    MockMain --> AsmConfig
    MockMain --> AsmAgent

    DecoMain --> AsmModel
    DecoMain --> AsmRegistry
    DecoMain --> AsmConfig
    DecoMain --> AsmAgent
    DecoMain --> D1

    %% ============ 运行流程 ============
    AsmAgent --> RunAgent
    RunAgent --> SimpleRun

    %% ============ Agent 层调用 ============
    SimpleRun --> LoopExec
    LoopExec --> BuildReq
    LoopExec --> D1

    %% ============ 装饰器链委托 ============
    D1 -->|delegate.chat| D2
    D2 -->|primary.chat| D3
    D2 -->|fallback.chat| D3
    D3 -->|delegate.chat| D4
    D4 -->|delegate.chat| Mock
    D4 -->|delegate.chat| OpenAI
    D4 -->|delegate.chat| Anthropic

    %% ============ 外部调用 ============
    OpenAI -->|HTTP POST| LLM
    Anthropic -->|HTTP POST| LLM

    %% ============ 工具调用 ============
    LoopExec -->|response.hasToolCalls| ExecTool
    ExecTool --> Registry
    ExecTool --> Echo
    ExecTool --> Time
    Registry --> Echo
    Registry --> Time

    %% ============ 样式 ============
    classDef entry fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef asm fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px
    classDef run fill:#fce4ec,stroke:#c62828,stroke-width:3px
    classDef agent fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef deco fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef real fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
    classDef tool fill:#fff3e0,stroke:#ef6c00,stroke-width:2px
    classDef ext fill:#fafafa,stroke:#999,stroke-dasharray: 5 5

    class MockMain,DecoMain entry
    class AsmModel,AsmRegistry,AsmConfig,AsmAgent asm
    class RunAgent run
    class SimpleRun,LoopExec,BuildReq agent
    class D1,D2,D3,D4 deco
    class Mock,OpenAI,Anthropic real
    class ExecTool,Registry,Echo,Time tool
    class LLM ext
```

---

## 2. 场景 A：带工具调用的完整执行（时序图）

> `MockAgentExample` 运行 "What time is it now?"
> Mock 脚本预置：第 1 轮返回 tool_call，第 2 轮返回最终文本

```mermaid
sequenceDiagram
    autonumber
    participant User as 👤 用户
    participant Main as MockAgentExample<br/>main()
    participant Agent as SimpleAgent
    participant Loop as ReActAgentLoop
    participant Client as MockModelClient<br/>(脚本模式)
    participant Exec as DefaultToolExecutor
    participant Reg as InMemoryToolRegistry
    participant Tool as CurrentTimeTool

    Note over Main: ━━ 装配阶段 ━━

    Main->>Client: MockModelClient.scripted()<br/>.respondToolCalls(get_current_time)<br/>.respondText("final answer")
    Main->>Reg: new InMemoryToolRegistry()
    Main->>Reg: register(CurrentTimeTool)
    Main->>Reg: register(EchoTool)
    Main->>Agent: new SimpleAgent(config)

    Note over Main: ━━ 运行阶段 ━━

    User->>Main: "What time is it now?"
    Main->>Agent: agent.run("What time is it now?")

    Note over Agent: 创建 AgentState<br/>messages = [system, user]<br/>status = IDLE

    Agent->>Loop: loop.execute(config, state)
    Note over Loop: status = RUNNING

    %% ============ Step 1: 模型返回 tool_call ============
    rect rgb(255, 243, 224)
        Note over Loop: Step 1
        Loop->>Loop: buildRequest(messages + toolSchemas)
        Loop->>Client: chat(request)
        Note over Client: scriptedResponses.poll()<br/>返回第 1 个预设响应
        Client-->>Loop: ModelResponse(toolCalls=[get_current_time])

        Note over Loop: response.hasToolCalls() == true
        Loop->>Loop: addMessage(assistantWithTools)
        Note over Loop: status = EXECUTING_TOOL

        Loop->>Exec: execute(ToolCall("get_current_time"))
        Exec->>Reg: getTool("get_current_time")
        Reg-->>Exec: CurrentTimeTool
        Exec->>Tool: execute(arguments)
        Tool-->>Exec: "当前时间: 2026-08-13 14:30"
        Exec-->>Loop: "当前时间: 2026-08-13 14:30"

        Loop->>Loop: addMessage(tool result)
        Note over Loop: status = RUNNING
    end

    %% ============ Step 2: 模型返回最终答案 ============
    rect rgb(232, 245, 233)
        Note over Loop: Step 2
        Loop->>Loop: buildRequest(messages + toolSchemas)
        Loop->>Client: chat(request)
        Note over Client: scriptedResponses.poll()<br/>返回第 2 个预设响应
        Client-->>Loop: ModelResponse(content="final answer")

        Note over Loop: response.hasToolCalls() == false
        Loop->>Loop: addMessage(assistant content)
        Note over Loop: status = DONE
    end

    Loop-->>Agent: return state (DONE)
    Note over Agent: 遍历 messages 找最后的<br/>ASSISTANT 消息，提取 content
    Agent-->>Main: "final answer"
    Main-->>User: 打印结果
```

---

## 3. 场景 B：装饰器链执行（时序图）

> `DecoratedModelClientExample` 装配了完整装饰器链。
> 展示一次 `chat()` 调用如何穿过多层装饰器。

```mermaid
sequenceDiagram
    autonumber
    participant Loop as ReActAgentLoop
    participant SO as StructuredOutput<br/>ModelClient
    participant FB as Fallback<br/>ModelClient
    participant TO as Timeout<br/>ModelClient
    participant RT as Retry<br/>ModelClient
    participant Real as OpenAiModelClient<br/>(或 Mock/Anthropic)
    participant LLM as LLM Provider

    Loop->>SO: chat(request)

    Note over SO: 检查 request.responseFormat<br/>有 JSON 格式？→ 走验证逻辑

    SO->>FB: delegate.chat(request)

    Note over FB: 尝试 primary（TO）

    rect rgb(232, 245, 233)
        Note over FB,RT: 正常路径（primary 成功）
        FB->>TO: primary.chat(request)
        TO->>RT: delegate.chat(request)
        RT->>Real: delegate.chat(request)
        Real->>LLM: HTTP POST /chat/completions
        LLM-->>Real: 200 OK + JSON body
        Real-->>RT: ModelResponse(content, "stop")
        RT-->>TO: ModelResponse
        TO-->>FB: ModelResponse
        FB-->>SO: ModelResponse
    end

    Note over SO: 验证 JSON 有效性<br/>有效 → 返回<br/>无效 → 追加修正提示重试

    SO-->>Loop: ModelResponse

    %% ============ 异常路径 ============
    rect rgb(255, 235, 238)
        Note over FB,LLM: 异常路径（primary 失败 → fallback）

        FB->>TO: primary.chat(request)
        TO->>RT: delegate.chat(request)
        RT->>Real: attempt 1: delegate.chat()
        Real->>LLM: HTTP POST
        LLM-->>Real: 500 Server Error
        Real-->>RT: ModelException(MODEL_ERROR)

        Note over RT: shouldRetry(MODEL_ERROR) = true<br/>退避 500ms
        RT->>Real: attempt 2: delegate.chat()
        Real->>LLM: HTTP POST
        LLM-->>Real: 500 Server Error
        RT-->>TO: ModelException（重试耗尽）

        Note over TO: 不拦截 ModelException<br/>直接抛出
        TO-->>FB: ModelException

        Note over FB: primary 抛异常<br/>→ 尝试 fallbacks[0]
        FB->>Real: fallback.chat(request)
        Note over Real: 这里 fallback 通常是 MockModelClient
        Real-->>FB: ModelResponse("Fallback response")
        FB-->>SO: ModelResponse
    end
```

---

## 4. 装饰器链逐层展开

> 每个装饰器做什么、什么时候拦截、什么时候放行

```mermaid
flowchart TD
    Call["ReActAgentLoop 调用<br/>modelClient.chat(request)"]

    %% ============ StructuredOutput ============
    SO_Entry["StructuredOutputModelClient"]
    SO_Check{"request.responseFormat != null?"}
    SO_Pass["直接 delegate.chat()<br/>放行"]
    SO_Validate["进入验证模式<br/>循环最多 maxRetries+1 次"]
    SO_Loop["delegate.chat(request)"]
    SO_Valid{"返回内容是<br/>有效 JSON?"}
    SO_Retry["追加修正提示<br/>「上次不是有效 JSON，<br/>请只返回 JSON」<br/>重试"]
    SO_Done["返回 ModelResponse"]

    %% ============ Fallback ============
    FB_Entry["FallbackModelClient"]
    FB_TryPrimary["primary.chat(request)"]
    FB_Check{"primary 成功<br/>且 finishReason != error?"}
    FB_Fallback["遍历 fallbacks 列表<br/>逐个尝试"]
    FB_Exhausted["抛出 ModelException<br/>「All fallback exhausted」"]
    FB_Done["返回 ModelResponse"]

    %% ============ Timeout ============
    TO_Entry["TimeoutModelClient"]
    TO_Async["CompletableFuture.supplyAsync<br/>() -> delegate.chat(request)"]
    TO_Wait["future.orTimeout(timeout).join()"]
    TO_Check{"超时?"}
    TO_Cancel["future.cancel(true)<br/>抛 ModelException(TIMEOUT)"]
    TO_Done["返回 ModelResponse"]

    %% ============ Retry ============
    RT_Entry["RetryModelClient"]
    RT_Loop["attempt 0..maxRetries"]
    RT_Call["delegate.chat(request)"]
    RT_Check{"抛 ModelException?"}
    RT_ShouldRetry{"shouldRetry(code)?"}
    RT_Backoff["指数退避<br/>delay = initial × mult^attempt<br/>cap 30s"]
    RT_Throw["抛出 ModelException<br/>（不可重试 or 重试耗尽）"]
    RT_Done["返回 ModelResponse"]

    %% ============ Real Client ============
    Real_Entry["实际 ModelClient<br/>(OpenAI / Mock / Anthropic)"]
    Real_Send["发送 HTTP 请求<br/>或 从 Mock 队列取"]
    Real_Receive["解析响应"]
    Real_Return["返回 ModelResponse"]

    %% ============ 连接 ============
    Call --> SO_Entry
    SO_Entry --> SO_Check
    SO_Check -->|No| SO_Pass
    SO_Check -->|Yes| SO_Validate
    SO_Validate --> SO_Loop
    SO_Loop --> FB_Entry
    SO_Valid -->|No, 有重试次数| SO_Retry
    SO_Retry --> SO_Loop
    SO_Valid -->|Yes| SO_Done
    SO_Valid -->|No, 重试耗尽| SO_Done
    SO_Pass --> FB_Entry

    FB_Entry --> FB_TryPrimary
    FB_TryPrimary --> TO_Entry
    FB_Check -->|Yes| FB_Done
    FB_Check -->|No| FB_Fallback
    FB_Fallback --> TO_Entry
    FB_Fallback -->|全部失败| FB_Exhausted

    TO_Entry --> TO_Async
    TO_Async --> RT_Entry
    TO_Wait --> TO_Check
    TO_Check -->|Yes| TO_Cancel
    TO_Check -->|No| TO_Done

    RT_Entry --> RT_Loop
    RT_Loop --> RT_Call
    RT_Call --> Real_Entry
    RT_Check -->|No| RT_Done
    RT_Check -->|Yes| RT_ShouldRetry
    RT_ShouldRetry -->|Yes, 有重试次数| RT_Backoff
    RT_Backoff --> RT_Loop
    RT_ShouldRetry -->|No| RT_Throw

    Real_Entry --> Real_Send
    Real_Send --> Real_Receive
    Real_Receive --> Real_Return

    %% ============ 样式 ============
    classDef entry fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef deco fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef decision fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px
    classDef error fill:#ffcdd2,stroke:#c62828,stroke-width:2px
    classDef success fill:#c8e6c9,stroke:#388e3c,stroke-width:2px
    classDef real fill:#e8f5e9,stroke:#388e3c,stroke-width:2px

    class Call entry
    class SO_Entry,FB_Entry,TO_Entry,RT_Entry deco
    class SO_Check,FB_Check,TO_Check,RT_Check,RT_ShouldRetry,SO_Valid decision
    class TO_Cancel,FB_Exhausted,RT_Throw error
    class SO_Done,FB_Done,TO_Done,RT_Done,Real_Return success
    class Real_Entry,Real_Send,Real_Receive real
```

---

## 5. ReAct 循环内部流程（带触发者标注）

> 每一步标注「谁触发谁」和「触发条件」

```mermaid
flowchart TD
    Entry["🔔 触发者: SimpleAgent.run()<br/>调用 loop.execute(config, state)"]

    Init["ReActAgentLoop.execute()<br/>━━━━━━━━━━━━━━━━<br/>status = RUNNING"]

    LoopCheck{"🔄 循环条件<br/>hasStepsRemaining &&<br/>!isTerminal()?<br/>━━━━━━━━━━━━━━━━<br/>触发者: ReActAgentLoop 自身"}

    StepInc["currentStep++<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop 循环体"]

    %% ============ Step 1: 构建请求 ============
    BuildReq["📦 构建 ModelRequest<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop → buildRequest()<br/>━━━━━━━━━━━━━━━━<br/>从 state.messages 拷贝<br/>+ registry.getToolSchemas()"]

    %% ============ Step 2: 调用模型 ============
    CallModel["🤖 调用模型<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop → modelClient.chat()<br/>━━━━━━━━━━━━━━━━<br/>进入装饰器链<br/>→ 最终到达 Real Client"]

    CallModelCatch{"modelClient.chat()<br/>是否抛异常?"}

    ErrorPath["❌ 错误路径<br/>━━━━━━━━━━━━━━━━<br/>status = ERROR<br/>lastError = e.getMessage()<br/>return state"]

    %% ============ Step 3: 处理响应 ============
    CheckToolCalls{"response.hasToolCalls()?<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop 判断响应"}

    %% ============ Tool 路径 ============
    AddAssistantTool["📝 记录 assistant 消息<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop → addMessage()<br/>━━━━━━━━━━━━━━━━<br/>ChatMessage.assistantWithTools()"]

    SetExecTool["status = EXECUTING_TOOL<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop"]

    ToolLoop["🔄 遍历 response.toolCalls<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop for 循环"]

    ExecTool["🔧 执行工具<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop → toolExecutor.execute()<br/>━━━━━━━━━━━━━━━━<br/>→ DefaultToolExecutor<br/>  → registry.getTool()<br/>    → tool.execute()"]

    AddToolResult["📝 记录 tool 结果<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop → addMessage()<br/>━━━━━━━━━━━━━━━━<br/>ChatMessage.tool(id, name, result)"]

    SetRunning["status = RUNNING<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop"]

    BackToLoop["回到循环顶部<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop while"]

    %% ============ Final Answer 路径 ============
    AddAssistantFinal["📝 记录最终答案<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop → addMessage()<br/>━━━━━━━━━━━━━━━━<br/>ChatMessage.assistant(content)"]

    SetDone["status = DONE<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop"]

    ReturnState["返回 state<br/>━━━━━━━━━━━━━━━━<br/>触发者: Loop → return"]

    %% ============ Max Steps ============
    MaxSteps["⚠️ 超出最大步数<br/>━━━━━━━━━━━━━━━━<br/>status = MAX_STEPS_EXCEEDED<br/>触发者: Loop 循环退出后"]

    %% ============ 提取答案 ============
    Extract["SimpleAgent 提取最终回答<br/>━━━━━━━━━━━━━━━━<br/>触发者: SimpleAgent<br/>━━━━━━━━━━━━━━━━<br/>从后往前找 ASSISTANT 消息<br/>提取 content"]

    ReturnAnswer["返回 String 给调用者<br/>━━━━━━━━━━━━━━━━<br/>触发者: SimpleAgent → return"]

    %% ============ 连接 ============
    Entry --> Init --> LoopCheck
    LoopCheck -->|Yes| StepInc --> BuildReq
    BuildReq --> CallModel
    CallModel --> CallModelCatch
    CallModelCatch -->|Yes| ErrorPath
    CallModelCatch -->|No| CheckToolCalls

    CheckToolCalls -->|Yes| AddAssistantTool
    AddAssistantTool --> SetExecTool
    SetExecTool --> ToolLoop
    ToolLoop --> ExecTool
    ExecTool --> AddToolResult
    AddToolResult --> ToolLoop
    ToolLoop -->|遍历完| SetRunning
    SetRunning --> BackToLoop
    BackToLoop --> LoopCheck

    CheckToolCalls -->|No| AddAssistantFinal
    AddAssistantFinal --> SetDone
    SetDone --> ReturnState

    LoopCheck -->|No| MaxSteps
    MaxSteps --> ReturnState

    ReturnState --> Extract
    Extract --> ReturnAnswer

    %% ============ 样式 ============
    classDef trigger fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef loop fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef decision fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px
    classDef tool fill:#fff3e0,stroke:#ef6c00,stroke-width:2px
    classDef model fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef error fill:#ffcdd2,stroke:#c62828,stroke-width:2px
    classDef success fill:#c8e6c9,stroke:#388e3c,stroke-width:2px

    class Entry trigger
    class Init,StepInc,SetExecTool,SetRunning,SetDone,BuildReq,BackToLoop loop
    class LoopCheck,CallModelCatch,CheckToolCalls decision
    class AddAssistantTool,AddToolResult,AddAssistantFinal,ExecTool,ToolLoop tool
    class CallModel model
    class ErrorPath,MaxSteps error
    class ReturnState,Extract,ReturnAnswer success
```

---

## 6. 流式调用执行流

> `stream()` 的执行路径与 `chat()` 不同
> 装饰器对流式的处理方式也不同

```mermaid
sequenceDiagram
    autonumber
    participant Caller as 调用者<br/>(AgentLoop / Example)
    participant SO as StructuredOutput
    participant FB as Fallback
    participant TO as Timeout
    participant RT as Retry
    participant Real as OpenAiModelClient
    participant LLM as LLM Provider

    Caller->>SO: stream(request)

    Note over SO: 流式模式：直接 delegate<br/>（无法中途验证 JSON）

    SO->>FB: delegate.stream(request)

    Note over FB: 流式：尝试 primary<br/>失败则 fallback

    FB->>TO: primary.stream(request)

    Note over TO: 流式：只对连接建立设超时<br/>流开始后不限制时长

    TO->>RT: delegate.stream(request)

    Note over RT: 流式：只重试连接阶段<br/>流开始后不再重试

    RT->>Real: delegate.stream(request)
    Real->>LLM: HTTP POST (stream=true)<br/>Accept: text/event-stream

    Note over LLM: SSE 事件流

    loop SSE 事件
        LLM-->>Real: data: {"choices":[{"delta":{"content":"Hello"}}]}
        Real-->>RT: StreamEvent.ContentDelta("Hello")
        RT-->>TO: StreamEvent.ContentDelta("Hello")
        TO-->>FB: StreamEvent.ContentDelta("Hello")
        FB-->>SO: StreamEvent.ContentDelta("Hello")
        SO-->>Caller: StreamEvent.ContentDelta("Hello")
    end

    LLM-->>Real: data: [DONE]
    Note over Real: 解析最后 chunk 的 finish_reason<br/>构建 ModelResponse
    Real-->>RT: StreamEvent.Done(finalResponse)
    RT-->>TO: StreamEvent.Done(finalResponse)
    TO-->>FB: StreamEvent.Done(finalResponse)
    FB-->>SO: StreamEvent.Done(finalResponse)
    SO-->>Caller: StreamEvent.Done(finalResponse)

    Note over Caller: Stream 结束<br/>消费完毕
```

---

## 7. 数据流转图

> 一个 `String userInput` 如何变成 `String response`<br/>
> 中间经历了哪些数据结构变换

```mermaid
flowchart LR
    Input["👤 String userInput<br/>━━━━━━━━━━━━━━━━<br/>'What time is it?'"]

    State1["AgentState<br/>━━━━━━━━━━━━━━━━<br/>messages: [<br/>  ChatMessage(SYSTEM, prompt),<br/>  ChatMessage(USER, input)<br/>]"]

    Request1["ModelRequest<br/>━━━━━━━━━━━━━━━━<br/>model: null<br/>messages: [...]<br/>tools: [schema, schema]"]

    Response1["ModelResponse<br/>━━━━━━━━━━━━━━━━<br/>content: null<br/>toolCalls: [ToolCall(id, name, args)]<br/>finishReason: 'tool_calls'"]

    State2["AgentState<br/>━━━━━━━━━━━━━━━━<br/>messages: [<br/>  system,<br/>  user,<br/>  assistantWithTools,<br/>  tool(result)<br/>]"]

    Request2["ModelRequest<br/>━━━━━━━━━━━━━━━━<br/>messages: [...4 条...]"]

    Response2["ModelResponse<br/>━━━━━━━━━━━━━━━━<br/>content: 'final answer'<br/>toolCalls: null<br/>finishReason: 'stop'"]

    State3["AgentState<br/>━━━━━━━━━━━━━━━━<br/>messages: [...5 条...]<br/>status: DONE"]

    Output["👤 String response<br/>━━━━━━━━━━━━━━━━<br/>'final answer'"]

    Input -->|"SimpleAgent.run()<br/>构造 State"| State1
    State1 -->|"buildRequest()<br/>拷贝 messages + 注入 tools"| Request1
    Request1 -->|"modelClient.chat()<br/>经装饰器链 -> LLM"| Response1
    Response1 -->|"hasToolCalls() == true<br/>执行工具 + addMessage"| State2
    State2 -->|"buildRequest()<br/>第二轮循环"| Request2
    Request2 -->|"modelClient.chat()"| Response2
    Response2 -->|"hasToolCalls() == false<br/>addMessage(assistant)"| State3
    State3 -->|"从后往前找<br/>ASSISTANT 消息"| Output

    classDef io fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef state fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef req fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef resp fill:#e8f5e9,stroke:#388e3c,stroke-width:2px

    class Input,Output io
    class State1,State2,State3 state
    class Request1,Request2 req
    class Response1,Response2 resp
```

---

## 8. 三种入口的触发路径对比

```mermaid
flowchart LR
    %% ============ 入口 1: MockAgentExample ============
    subgraph Entry1["入口 1: MockAgentExample"]
        E1_Main["main()"]
        E1_Mock["MockModelClient.scripted()<br/>无装饰器"]
        E1_Registry["InMemoryToolRegistry<br/>+ CurrentTimeTool + EchoTool"]
        E1_Agent["SimpleAgent"]
        E1_Run["agent.run()"]
    end

    %% ============ 入口 2: DecoratedModelClientExample ============
    subgraph Entry2["入口 2: DecoratedModelClientExample"]
        E2_Main["main()"]
        E2_Env{"OPENAI_API_KEY<br/>存在?"}
        E2_OpenAI["OpenAiModelClient"]
        E2_Mock["MockModelClient"]
        E2_Decorators["装饰器链:<br/>Retry → Timeout → Fallback → StructuredOutput"]
        E2_Registry["InMemoryToolRegistry<br/>+ EchoTool"]
        E2_Agent["SimpleAgent"]
        E2_Run["agent.run()"]
        E2_Structured["decoratedClient.chat()<br/>直接调用（不经 Agent）"]
    end

    %% ============ 入口 3: 直接使用 ModelClient ============
    subgraph Entry3["入口 3: 直接调用 ModelClient<br/>(未来场景)"]
        E3_Code["业务代码"]
        E3_Decorated["装饰器链"]
        E3_Direct["modelClient.chat()<br/>或 .stream()"]
    end

    E1_Main --> E1_Mock --> E1_Agent
    E1_Main --> E1_Registry --> E1_Agent
    E1_Agent --> E1_Run

    E2_Main --> E2_Env
    E2_Env -->|Yes| E2_OpenAI --> E2_Decorators
    E2_Env -->|No| E2_Mock --> E2_Decorators
    E2_Main --> E2_Registry --> E2_Agent
    E2_Decorators --> E2_Agent
    E2_Agent --> E2_Run
    E2_Main --> E2_Structured
    E2_Decorators --> E2_Structured

    E3_Code --> E3_Decorated --> E3_Direct

    classDef entry fill:#fff9c4,stroke:#f57f17,stroke-width:2px
    classDef client fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
    classDef deco fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef agent fill:#e3f2fd,stroke:#1565c0,stroke-width:2px
    classDef decision fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px

    class E1_Main,E2_Main,E3_Code entry
    class E1_Mock,E2_OpenAI,E2_Mock client
    class E2_Decorators,E3_Decorated deco
    class E1_Agent,E2_Agent agent
    class E2_Env decision
```

---

## 9. 组件触发关系矩阵

> 横轴 = 触发者，纵轴 = 被触发者
> ✅ = 会触发，❌ = 不会触发，🔀 = 条件触发

| 触发者 ↓ \ 被触发者 →           | SimpleAgent | ReActAgentLoop | ModelClient           | ToolExecutor   | ToolRegistry | Tool        | LLM Provider |
|--------------------------|-------------|----------------|-----------------------|----------------|--------------|-------------|--------------|
| **main()**               | ✅ 创建+调用     | ❌              | ✅ 创建                  | ❌              | ✅ 创建         | ❌           | ❌            |
| **SimpleAgent**          | -           | ✅ 调用 execute() | ❌                     | ❌              | ❌            | ❌           | ❌            |
| **ReActAgentLoop**       | ❌           | -              | ✅ 调用 chat()           | ✅ 调用 execute() | ❌            | ❌           | ❌            |
| **StructuredOutput**     | ❌           | ❌              | ✅ delegate            | ❌              | ❌            | ❌           | ❌            |
| **Fallback**             | ❌           | ❌              | ✅ primary + fallbacks | ❌              | ❌            | ❌           | ❌            |
| **Timeout**              | ❌           | ❌              | ✅ delegate            | ❌              | ❌            | ❌           | ❌            |
| **Retry**                | ❌           | ❌              | ✅ delegate            | ❌              | ❌            | ❌           | ❌            |
| **DefaultToolExecutor**  | ❌           | ❌              | ❌                     | -              | ✅ getTool()  | ✅ execute() | ❌            |
| **OpenAiModelClient**    | ❌           | ❌              | ❌                     | ❌              | ❌            | ❌           | ✅ HTTP POST  |
| **AnthropicModelClient** | ❌           | ❌              | ❌                     | ❌              | ❌            | ❌           | ✅ HTTP POST  |
| **MockModelClient**      | ❌           | ❌              | ❌                     | ❌              | ❌            | ❌           | ❌ (无外部调用)    |

---

## 10. 异常处理流程图

> 不同层捕获什么异常、怎么处理

```mermaid
flowchart TD
    LLMError["LLM 返回错误<br/>━━━━━━━━━━━━━━━━<br/>4xx / 5xx / 网络超时"]

    ModelEx["ModelClient 抛出<br/>ModelException(ErrorCode)<br/>━━━━━━━━━━━━━━━━<br/>NETWORK_ERROR / TIMEOUT /<br/>RATE_LIMITED / AUTH_ERROR /<br/>INVALID_REQUEST / MODEL_ERROR"]

    RetryCatch{"RetryModelClient<br/>捕获"}
    RetryCheck{"shouldRetry(code)?"}
    RetryYes["退避后重试<br/>━━━━━━━━━━━━━━━━<br/>maxRetries 次<br/>指数退避 500ms × 2^n<br/>cap 30s"]
    RetryNo["向上抛出"]
    RetryExhausted["重试耗尽<br/>向上抛出"]

    TimeoutCatch{"TimeoutModelClient<br/>捕获"}
    TimeoutCheck{"是 TimeoutException?"}
    TimeoutYes["包装为<br/>ModelException(TIMEOUT)"]
    TimeoutNo["解包原异常<br/>向上抛出"]

    FallbackCatch{"FallbackModelClient<br/>捕获"}
    FallbackCheck{"还有 fallback?"}
    FallbackYes["尝试下一个 fallback<br/>━━━━━━━━━━━━━━━━<br/>fallbacks[index+1].chat()"]
    FallbackNo["抛出<br/>ModelException(MODEL_ERROR)<br/>'All fallback exhausted'"]

    StructuredCatch{"StructuredOutput<br/>捕获?"}
    StructuredNo["向上抛出"]

    LoopCatch{"ReActAgentLoop<br/>捕获"}
    LoopHandle["status = ERROR<br/>lastError = e.getMessage()<br/>return state"]
    LoopThrow["异常不向上传播<br/>转为 state.status = ERROR"]

    AgentHandle{"SimpleAgent<br/>处理"}
    AgentError["status == ERROR<br/>return '[Agent error: ...]'"]

    ToolError["Tool 执行异常"]
    ToolExCatch["DefaultToolExecutor<br/>捕获"]
    ToolWrap["包装为文本<br/>━━━━━━━━━━━━━━━━<br/>'[ERROR] Tool xxx failed: ...'<br/>返回给 Loop（非异常）"]

    %% ============ 连接 ============
    LLMError --> ModelEx
    ModelEx --> RetryCatch

    RetryCatch --> RetryCheck
    RetryCheck -->|Yes| RetryYes
    RetryCheck -->|No| RetryNo
    RetryYes -->|重试成功| OK["正常返回"]
    RetryYes -->|重试失败| RetryExhausted
    RetryExhausted --> TimeoutCatch
    RetryNo --> TimeoutCatch

    TimeoutCatch --> TimeoutCheck
    TimeoutCheck -->|Yes| TimeoutYes
    TimeoutCheck -->|No| TimeoutNo
    TimeoutYes --> FallbackCatch
    TimeoutNo --> FallbackCatch

    FallbackCatch --> FallbackCheck
    FallbackCheck -->|Yes| FallbackYes
    FallbackCheck -->|No| FallbackNo
    FallbackYes -->|成功| OK
    FallbackNo --> StructuredCatch

    StructuredCatch --> StructuredNo
    StructuredNo --> LoopCatch

    LoopCatch --> LoopHandle
    LoopHandle --> LoopThrow
    LoopThrow --> AgentHandle
    AgentHandle --> AgentError

    ToolError --> ToolExCatch
    ToolExCatch --> ToolWrap
    ToolWrap --> LoopAsText["Loop 收到文本<br/>（不是异常）<br/>addMessage(tool result)"]

    classDef error fill:#ffcdd2,stroke:#c62828,stroke-width:2px
    classDef handle fill:#fff3e0,stroke:#ef6c00,stroke-width:2px
    classDef decision fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px
    classDef success fill:#c8e6c9,stroke:#388e3c,stroke-width:2px

    class LLMError,ModelEx,FallbackNo,AgentError error
    class RetryYes,FallbackYes,TimeoutYes,ToolWrap,LoopHandle handle
    class RetryCatch,RetryCheck,TimeoutCatch,TimeoutCheck,FallbackCatch,FallbackCheck,StructuredCatch,LoopCatch,AgentHandle decision
    class OK,RetryNo,TimeoutNo,StructuredNo,LoopThrow,LoopAsText success
```

---

## 总结：触发链路一览

```
main()
  │
  ├── 装配阶段 ──────────────────────────────────────────
  │    ├── new MockModelClient / OpenAiModelClient / AnthropicModelClient
  │    ├── new InMemoryToolRegistry() + register(Tool)
  │    ├── new AgentConfig(name, prompt, client, registry, maxSteps)
  │    └── new SimpleAgent(config)
  │         └── 内部创建 ReActAgentLoop(DefaultToolExecutor(registry))
  │
  └── 运行阶段 ──────────────────────────────────────────
       └── agent.run(userInput)
            │
            ├── SimpleAgent.run()
            │    ├── 创建 AgentState（systemPrompt + userInput）
            │    ├── loop.execute(config, state)
            │    │
            │    └── ReActAgentLoop.execute()
            │         │
            │         └── while (hasStepsRemaining && !isTerminal)
            │              │
            │              ├── buildRequest() → ModelRequest
            │              │
            │              ├── modelClient.chat(request)
            │              │    │
            │              │    └── 装饰器链（由外到内）
            │              │         ├── StructuredOutput → 验证 JSON
            │              │         ├── Fallback → 主备切换
            │              │         ├── Timeout → 超时控制
            │              │         ├── Retry → 重试退避
            │              │         └── Real Client → HTTP/Mock
            │              │              └── LLM Provider（OpenAI / Claude / ...）
            │              │
            │              ├── if hasToolCalls:
            │              │    ├── addMessage(assistantWithTools)
            │              │    ├── for each toolCall:
            │              │    │    └── toolExecutor.execute()
            │              │    │         └── DefaultToolExecutor
            │              │    │              ├── registry.getTool(name)
            │              │    │              └── tool.execute(arguments)
            │              │    ├── addMessage(tool result)
            │              │    └── continue loop
            │              │
            │              └── if final answer:
            │                   ├── addMessage(assistant)
            │                   ├── status = DONE
            │                   └── return state
            │
            └── 提取最后 ASSISTANT 消息 → return String
```
