# 决策 4：流式和非流式走同一条 Loop，用 AgentEvent sink 区分

> 对应《agent4j 架构立场》骨架的决策 4。
> 一句话：**流式不是第二个算法，是同一个状态转移过程的投影；正确抽象是一个循环 + 多种观测器（sink），而不是循环 × 观测方式的笛卡尔积。**

> 状态：**技术债已修复**（`ReActAgentLoop` 已收敛为单一 `runLoop`，`execute` = 无操作 sink，`stream` = live sink）。

## 一、决策在说什么

`ReActAgentLoop` 两个方法：

```java
AgentState execute(config, state)                     // 非流式：跑完返回
void stream(config, state, Consumer<AgentEvent> sink) // 流式：边跑边发事件
```

共享（同一个）：

```text
while 条件（maxSteps + isTerminal）
组请求 buildRequest
工具执行逻辑（ToolExecutor）
状态机转换（RUNNING / EXECUTING_TOOL / DONE / MAX_STEPS_EXCEEDED / ERROR）
maxSteps 耗尽处理
```

不同（只有两处）：

```text
调模型：modelClient.chat()  vs  modelClient.stream()
输出：  返回 state          vs  发 AgentEvent 给 sink
```

## 二、为什么必须同一条 Loop

**单一事实源。** 两套 Loop 必然行为漂移：改一处忘同步另一处，用户会发现"网页端和 API 端结果不一样"。同一条 Loop 保证工具执行、步数、状态、错误处理只写一份，改一处两边生效。

## 三、诚实的问题：语义统一，但代码是两份方法（已修复）

原实现里 `execute()` 和 `stream()` 是两个独立方法，while 循环体重复写了：

```java
// execute() 一份 while
while (state.hasStepsRemaining() && !state.isTerminal()) { ... }
// stream() 又一份几乎相同的 while
while (state.hasStepsRemaining() && !state.isTerminal()) { ... }
```

这是漂移风险，属于技术债。**已修复**：现在只有一份 `runLoop`，两个入口只是传入不同的 sink 与 model invoker。

## 四、正确的抽象：一个 runLoop，两种 sink

```java
// 只有一个 loop，统一发事件
void runLoop(config, state, Consumer<AgentEvent> sink) {
    while (...) {
        // 边跑边 sink.accept(...)
    }
}

// 非流式 = buffer sink，跑完取结果
AgentState execute(config, state) {
    List<AgentEvent> buffer = new ArrayList<>();
    runLoop(config, state, buffer::add);
    return state;
}

// 流式 = 透传 live sink
void stream(config, state, sink) {
    runLoop(config, state, sink);
}
```

`AgentEvent` 密封接口（ContentDelta / ToolStarted / ToolFinished / Done / Error）为统一模型提供了事件语义：`Done` 带 state，`Error` 带 cause。

修复后的实际结构：

```java
execute(config, state) -> runLoop(config, state, noopSink, chatInvoker)
stream(config, state, sink) -> runLoop(config, state, sink, streamInvoker)
```

`chatInvoker` 是 `client.chat(request)` 的 lambda；`streamInvoker` 是 `try (events) { return consumeStream(events, state, sink); }`。

## 五、代价（必须答）

1. **事件完成边界要设计清楚**：`Done` 是持久化边界，前面不能有重复完整答案的 delta（AgentEvent 注释已写明）。事件语义比"返回 String"复杂，要有约定。
2. **非流式 client 适配成本**：统一成 sink 后，只支持 chat 的 ModelClient 要在 client 层包装成单事件流，复杂度推给模型层。
3. **统一循环强制走事件路径**：即使非流式，循环内部也统一发事件（进无操作 sink），未来加第三种输出形态会更自然，但当前多了一层 sink 间接。

## 六、什么场景会改

- （已解决）循环逻辑只写一份；未来加"取消 / 循环检测"只需改 runLoop 一处。
- 出现第三种输出形态（轨迹流、重放模式）时 → 不统一就每加一种形态多一份循环体；统一后新形态只是新 sink。

## 七、架构师级洞察

> 流式不是第二个算法，是同一个状态转移过程的投影。ReAct 每步的状态转移是本质；"一次性返回"和"增量推事件"只是两种观测方式。

```text
错误：Loop × {chat, stream} = 2 份代码
正确：Loop × 1，观测器 × {buffer, live, trace} = 1 + N
```

可迁移：日志的 stdout/文件/网络、音视频录制/直播/回放、数据库变更流/快照——都是"一个过程，多种投影"，不该为每种投影复制过程。

## 八、面试表述（诚实版本）

> 流式和非流式共享同一套 ReAct 状态机语义——工具执行、步数、终态只有一套逻辑，通过 AgentEvent 区分输出。
> 实现上我只有一个 runLoop：execute 用无操作 sink 跑 chat，stream 用 live sink 跑 stream。
> 原来有过 execute/stream 两份重复循环体的债，我把它收敛成单一 runLoop，并保留了全部行为。

主动暴露曾经的债 + 说出收敛结果，比吹牛更可信。

## 关联

- 证据：`ReActAgentLoop.execute()` 与 `ReActAgentLoop.stream()` 的重复 while 循环；共享的 `buildRequest()`；`AgentEvent` sealed interface；`SimpleAgent` 的 `run`/`stream` 委托。
- 决策 6 的 ContextBuilder 挂载点在共享的 `buildRequest()` 里——两条路径都走它，这是当前少数真正共享的部分。
