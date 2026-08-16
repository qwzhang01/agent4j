# Stage 1-2 执行流程详解

> Java Agent Framework 完整执行流程，从入口到出口，每一步谁触发谁
> 对应阶段：Stage 1（模型调用层）+ Stage 2（最小 Agent Loop）

---

## 一句话概括

```
用户问一个问题
  -> Agent 循环调用大模型
  -> 模型说"我要用工具"
  -> 执行工具把结果喂回模型
  -> 模型说"我知道答案了"
  -> 返回
```

---

## 三个角色

```
用户（你）  ──问问题──>  Agent（管家）  ──请教-->  大模型（大脑）
                                |
                           执行工具（手）
```

- **Agent**：管家，负责调度，不做决策
- **大模型**：大脑，负责决策"该干嘛"，但不亲自动手
- **工具**：手，负责执行具体动作（查时间、回显文本等）

---

## 一次完整流程（6 步）

假设用户问："现在几点？"

```
第 1 步：用户 -> Agent
  "现在几点？"

第 2 步：Agent 把问题打包成请求
  请求 = [系统提示词, "现在几点？"] + 可用工具列表

第 3 步：Agent -> 大模型（经过装饰器链）
  大模型看到问题，想了一想：
  "我需要调用 get_current_time 工具"
  返回：toolCalls = [{name: "get_current_time"}]

第 4 步：Agent 执行工具
  Agent 拿到模型的指令 -> 执行 get_current_time
  工具返回："当前时间: 14:30"
  Agent 把结果追加到对话历史

第 5 步：Agent -> 大模型（第二轮）
  Agent 再问一次，这次对话历史变成了：
  [系统提示词, "现在几点？", 模型说"调工具", 工具结果"14:30"]
  大模型看到工具结果，给出最终答案：
  "现在是 14:30"

第 6 步：Agent -> 用户
  "现在是 14:30"
```

---

## 为什么要"循环"？

因为模型可能需要**连续调多个工具**才能回答。

比如用户问"北京天气怎样，需要带伞吗？"：

```
第 1 轮：模型说"调 get_weather(北京)" -> 执行 -> "晴，25℃"
第 2 轮：模型说"调 check_rain(北京)" -> 执行 -> "未来 24h 无雨"
第 3 轮：模型说"不需要带伞" -> 最终答案，退出循环
```

每次循环 = 1 次模型调用 + 0 或多次工具执行。

---

## 装饰器链执行流程

大模型 API 不稳定，会超时、限流、挂掉。装饰器就是给模型调用**套了 4 层保险**：

```
请求进来
  ↓
┌─────────────────────────────────────┐
│ 第 1 层：StructuredOutput            │  返回的 JSON 对不对？不对就重试
├─────────────────────────────────────┤
│ 第 2 层：Fallback                    │  主模型挂了？切备用模型
├─────────────────────────────────────┤
│ 第 3 层：Timeout                     │  30 秒没返回？杀掉，报超时
├─────────────────────────────────────┤
│ 第 4 层：Retry                       │  网络抖了？等 500ms 重试，最多 3 次
├─────────────────────────────────────┤
│ 真正的 ModelClient（OpenAI/Claude）   │  发 HTTP 请求到 LLM
└─────────────────────────────────────┘
  ↓
返回结果
```

**从外到内**调用，**从内到外**返回。每一层只做一件事，互相不知道对方存在。

### 装饰器工作原理 = 套娃

> 装饰器 = "我也是 ModelClient，但我自己不干活，我包着另一个 ModelClient 干活，我在中间加点料"

```
你调 client.chat(request)
  ↓
第 4 层 Fallback 说："我先试主模型，挂了切备用"
  -> 调 layer3.chat()
    ↓
  第 3 层 Timeout 说："我设个 30 秒闹钟"
    -> 调 layer2.chat()
      ↓
    第 2 层 Retry 说："失败了我重试"
      -> 调 layer1.chat()
        ↓
      第 1 层 OpenAi 真正发 HTTP 请求
        -> LLM 返回结果
      ← 返回给第 2 层
    ← 返回给第 3 层
  ← 返回给第 4 层
← 返回给你
```

### 四个装饰器各自职责

| 装饰器 | 什么时候出手 | 做什么 |
|--------|------------|--------|
| **Retry** | 内层抛异常时 | 看错误码：超时/限流/网络错误 -> 重试；认证错误 -> 不重试 |
| **Timeout** | 内层调用耗时过长时 | 30 秒还没返回 -> 杀线程，抛 TIMEOUT |
| **Fallback** | 内层抛异常时 | 主模型挂了 -> 切备用模型 |
| **StructuredOutput** | 内层返回结果后 | 检查 JSON 合法性，不合法追加修正提示重试 |

Retry 和 Fallback 都在"内层失败时"出手，但策略不同：

```
Retry:    "同一个模型再试几次"  ← 适合瞬时故障
Fallback: "换一个模型"          ← 适合持续性故障
```

### 真实异常场景走一遍

假设 `Fallback(Timeout(Retry(OpenAI)), Mock)`：

```
1. 调 chat(request)

2. Fallback -> 让主模型 Timeout 去干

3. Timeout -> 开异步线程，设 30s 超时，调 Retry

4. Retry 第 1 次尝试 -> OpenAI 发 HTTP -> 500 错误

5. Retry 检查错误码：MODEL_ERROR -> 可重试，等 500ms

6. Retry 第 2 次尝试 -> 又 500

7. Retry 第 3 次尝试 -> 又 500，重试耗尽 -> 抛异常

8. Timeout 线程收到异常（非超时）-> 直接抛给上层

9. Fallback 收到异常："主模型挂了" -> 切到 Mock
   Mock.chat() -> 返回 "Fallback response"

10. 你收到：ModelResponse("Fallback response")
```

---

## 数据流转过程

一个 `String userInput` 如何变成 `String response`，中间经历了哪些数据结构变换：

```
String "现在几点？"               <- 用户输入
  |
  v
AgentState                        <- Agent 的记忆
  messages = [
    {SYSTEM, "你是助手"},
    {USER, "现在几点？"}
  ]
  |
  v
ModelRequest                      <- 打包发给模型
  messages = [...上面两条...]
  tools = [get_current_time 的 schema]
  |
  v
ModelResponse                     <- 模型返回
  content = null
  toolCalls = [{id:"call_1", name:"get_current_time"}]
  finishReason = "tool_calls"      <- 意思是"我要调工具"
  |
  v
执行工具 -> "当前时间: 14:30"
  |
  v
AgentState（更新后）               <- 把工具结果加到记忆里
  messages = [
    {SYSTEM, "你是助手"},
    {USER, "现在几点？"},
    {ASSISTANT, toolCalls},        <- 模型说过的话
    {TOOL, "当前时间: 14:30"}      <- 工具执行结果
  ]
  |
  v
ModelRequest（第二轮）              <- 再问一次模型
  |
  v
ModelResponse                     <- 模型看到工具结果，给出最终答案
  content = "现在是 14:30"
  finishReason = "stop"           <- 意思是"我说完了"
  |
  v
String "现在是 14:30"              <- 提取文本，返回给用户
```

---

## ReAct 循环内部流程

每一步标注"谁触发的"和"触发条件"：

```
SimpleAgent.run(userInput)
  |
  v
ReActAgentLoop.execute(config, state)
  status = RUNNING
  |
  v
+---> 循环条件：hasStepsRemaining && !isTerminal？
|     |
|     v
|     currentStep++
|     |
|     v
|     1. 构建 ModelRequest
|        从 state.messages 拷贝
|        + registry.getToolSchemas()
|     |
|     v
|     2. modelClient.chat(request)
|        （经过装饰器链 -> 真实模型）
|     |
|     v
|     modelClient.chat() 抛异常？
|     |-- Yes --> status = ERROR, return state
|     |-- No  --> 继续
|     |
|     v
|     3. response.hasToolCalls()？
|     |
|     +-- Yes（要调工具）：
|     |     addMessage(assistantWithTools)
|     |     status = EXECUTING_TOOL
|     |     for each toolCall:
|     |       toolExecutor.execute(toolCall)
|     |         -> registry.getTool(name)
|     |         -> tool.execute(args)
|     |         -> 返回文本
|     |       addMessage(tool result)
|     |     status = RUNNING
|     |     回到循环顶部
|     |
|     +-- No（最终答案）：
|           addMessage(assistant content)
|           status = DONE
|           return state
|
+---> 循环退出（步数超限）
      status = MAX_STEPS_EXCEEDED
      return state

SimpleAgent 从 state.messages 末尾找最后一条 ASSISTANT 消息
  -> 返回 content 字符串给用户
```

---

## 出错处理流程

### 模型调用失败

```
模型调用失败
  |
  v
Retry 拦截
  |-- 是超时/限流/网络错误？ -> 等 500ms，重试（最多 3 次）
  |-- 是认证错误/请求格式错误？ -> 不重试，直接往上抛
  |
  v
重试耗尽
  |
  v
Timeout 拦截（不处理，往上抛）
  |
  v
Fallback 拦截
  |-- 有备用模型？ -> 切备用模型重试
  |-- 没有了？ -> 抛异常
  |
  v
异常到达 Agent Loop
  |
  v
Agent 不让异常传给用户
  -> status = ERROR
  -> lastError = "模型调用失败: xxx"
  -> 返回 "[Agent error: 模型调用失败: xxx]"
```

### 工具执行失败

工具出错和模型出错处理方式不同：

```
工具执行失败（比如工具内部抛异常）
  |
  v
DefaultToolExecutor 捕获
  -> 不抛异常！
  -> 包装成文本："[ERROR] Tool 'get_current_time' failed: ..."
  -> 这个文本会喂回给模型
  -> 模型自己决定怎么办（换个工具？换种问法？直接道歉？）
```

**为什么不抛异常**：工具失败是"正常的业务情况"，模型应该知道失败了并决定怎么办。如果抛异常，Agent 循环就断了。

---

## 完整调用链路（带参与者标注）

```
用户
  | agent.run("现在几点？")
  v
SimpleAgent
  | 创建 AgentState（systemPrompt + userInput）
  | loop.execute(config, state)
  v
ReActAgentLoop
  |
  +---> 第 1 轮循环
  |     |
  |     | buildRequest(messages + toolSchemas)
  |     v
  |     ModelClient.chat(request)
  |       |
  |       v
  |     装饰器链（从外到内）：
  |       StructuredOutput -> Fallback -> Timeout -> Retry -> OpenAiModelClient
  |       |
  |       v
  |     LLM Provider（OpenAI / Claude / ...）
  |       |
  |       v
  |     返回 ModelResponse(toolCalls=[get_current_time])
  |     |
  |     | hasToolCalls() == true
  |     | addMessage(assistantWithTools)
  |     | status = EXECUTING_TOOL
  |     v
  |     ToolExecutor.execute(ToolCall)
  |       |
  |       v
  |     DefaultToolExecutor
  |       | registry.getTool("get_current_time")
  |       v
  |     CurrentTimeTool.execute()
  |       |
  |       v
  |     返回 "当前时间: 14:30"
  |     |
  |     | addMessage(tool result)
  |     | status = RUNNING
  |
  +---> 第 2 轮循环
        |
        | buildRequest(messages + toolSchemas)
        v
        ModelClient.chat(request)
          -> 装饰器链 -> LLM Provider
          -> 返回 ModelResponse(content="现在是 14:30", finishReason="stop")
        |
        | hasToolCalls() == false
        | addMessage(assistant content)
        | status = DONE
        |
        v
        return state

SimpleAgent
  | 从 state.messages 末尾找 ASSISTANT 消息
  | 返回 "现在是 14:30"
  v
用户
```

---

## Agent 状态机

```
                +-------+
                | IDLE  |  <-- new AgentState()
                +-------+
                    |
                    | loop.execute()
                    v
                +--------+
        +------>| RUNNING |  <-- 调模型
        |       +--------+
        |           |
        |     +-----+-----+
        |     |           |
        |     v           v
        | +--------+  +-----------+
        | |EXECUTING|  |   DONE    |  <-- 模型给最终答案
        | | _TOOL  |  +-----------+
        | +--------+        |
        |     |             | isTerminal = true
        |     | 工具执行完   |
        +-----+             v
                            返回

     +-----------------+
     |MAX_STEPS_EXCEEDED|  <-- 步数超限
     +-----------------+
              |
              | isTerminal = true
              v
            返回

     +-------+
     | ERROR |  <-- 模型调用抛异常
     +-------+
        |
        | isTerminal = true
        v
      返回
```

---

## 三种入口路径

```
入口 1：MockAgentExample（无装饰器）
  main()
    -> MockModelClient.scripted()（预设返回）
    -> InMemoryToolRegistry + CurrentTimeTool + EchoTool
    -> SimpleAgent
    -> agent.run()
    -> ReActAgentLoop（2 步：调工具 + 返回答案）

入口 2：DecoratedModelClientExample（带装饰器链）
  main()
    -> 检查 OPENAI_API_KEY 环境变量
       |-- 有 -> OpenAiModelClient
       |-- 无 -> MockModelClient
    -> 装饰器链：Retry -> Timeout -> Fallback -> StructuredOutput
    -> SimpleAgent
    -> agent.run()

入口 3：直接调 ModelClient（不经 Agent，未来场景）
  业务代码
    -> 装饰器链
    -> modelClient.chat() 或 modelClient.stream()
```

---

## 最简心智模型

记住这张图就够了：

```
用户 --> SimpleAgent --> ReActAgentLoop
                            |
                  +---------+----------+
                  |  循环：            |
                  |  1. 问模型        |--> 装饰器链 --> 真实模型
                  |  2. 要工具？       |
                  |     是 -> 执行工具 |--> ToolExecutor --> Tool
                  |     否 -> 返回答案 |      |
                  |  3. 步数超限？     |      +-- 结果加到对话历史
                  |     是 -> 报错退出 |
                  +--------------------+
                            |
                  SimpleAgent 提取最后一条
                  ASSISTANT 消息的文本
                            |
用户 <-- 返回 String <-------+
```

就这么简单：**问模型 -> 要工具就执行 -> 再问模型 -> 直到模型给最终答案**。装饰器和异常处理都是保险机制，不影响主流程的理解。
