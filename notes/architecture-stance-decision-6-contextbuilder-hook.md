# 决策 6：记忆通过 ContextBuilder 挂进 Loop，null 透传

> 对应《agent4j 架构立场》骨架的决策 6。
> 一句话：**框架提供"缝"（seam），不提供"功能"；null 透传 = 渐进式接入，老用户零破坏。**

## 实现

```java
// ReActAgentLoop.buildRequest()
List<ChatMessage> messages = config.getContextBuilder() != null
        ? config.getContextBuilder().build(config, state)   // 记忆/压缩策略
        : new ArrayList<>(state.getMessages());             // 透传，Stage 1-7 行为
```

`ContextBuilder` 在 agent-core；agent-memory 提供三个实现：`Passthrough / Compressing / MemoryContext`。

## 为什么这么挂

1. **关注点分离**：Loop 管"何时组请求"，ContextBuilder 管"塞什么"。token 预算、记忆注入、压缩都在 hook 后。
2. **策略可换**：一个挂载点，三种策略，换策略不动 Loop。
3. **向后兼容**：null 走透传，存量行为零变化——渐进式采用。

## 代价（必须答）

1. **静默 no-op**：null 不报错、不提示，直接没记忆/压缩。忘配就裸奔（呼应决策 3：不配 ContextBuilder 就无 token 保护）。
2. **接口契约有隐藏副作用**：签名返回 List<ChatMessage>，但 CompressingContextBuilder 就地改写 state.getMessages()——"读起来纯、实际改状态"的泄漏抽象。
3. **单点能力过大**：build(config, state) 拿到整个 config + state，类型系统管不住。

## 什么场景会改

- 需要链式变换（压缩 → 记忆注入 → 模板渲染）→ 单 hook 变 ContextBuilder 管道。
- 需要更多上下文（工具 schema / 预算 / 上轮响应）→ 签名要扩。
- "忘配记忆"是严重事故 → null 透传改 fail-closed（强制 builder 或默认 + 告警）。

## 架构师洞察

不做死的功能，做可插入的缝：新增能力 = 新增实现，不是改核心循环。
null 透传的本质是兼容性策略：新能力默认关闭，显式打开；零破坏换"忘了打开就静默失败"。

主动承认"build() 返回契约掩盖 state 改写副作用"，比夸设计干净更有说服力。

## 面试表述

> 记忆和上下文管理不写死在 Loop 里，而是通过 AgentConfig 上的 ContextBuilder 挂载点注入；null 就透传，保证向后兼容。
> Loop 管"何时组请求"，ContextBuilder 管"塞什么"，策略可换而不动循环。
> 代价是 null 时静默无记忆，且 build() 的返回契约掩盖了就地改写 state 的副作用。
> 这套"接口插槽 + 可换实现 + null 透传"是框架的统一扩展语言。

## 关联

- 证据：`ContextBuilder` 接口 + `AgentConfig.getContextBuilder()` nullable + `ReActAgentLoop.buildRequest()` null 检查 + agent-memory 三实现。
- 决策 3 的 token 预算由本挂载点承接；决策 5/7 同源扩展语言。
