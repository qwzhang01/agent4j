# 决策 5：用装饰器而不是继承做 ModelClient 增强

> 对应《agent4j 架构立场》骨架的决策 5。
> 一句话：**正交关注点用装饰器组合，而不是用继承做笛卡尔积。**

## 实现

```java
ModelClient client = new RetryModelClient(
    new TimeoutModelClient(
        new FallbackModelClient(primary, backup), Duration.ofSeconds(30)));
```

`Retry / Timeout / Fallback / StructuredOutput` 都 `implements ModelClient`，持有 `delegate`，转发 + 加行为。

## 为什么不用继承

| 继承 | 装饰器 |
|---|---|
| 单一扩展轴 | 多轴自由叠加，任意顺序 |
| `RetryTimeoutFallbackClient` 组合爆炸 | N 个能力 = N 个类，运行时拼 |
| 绑死基类 | 包任何 ModelClient，包括 Mock |

## 代价

1. **顺序有语义**：`retry(timeout(chat))` ≠ `timeout(retry(chat))`。前者单次调用受限，后者整个重试链受限。
2. **样板代码**：每个装饰器都要实现 chat() + stream()（还有 image/video）。
3. **隐藏状态**：Timeout 藏线程池（须 daemon），Retry 藏计数器。
4. **层数多难排查**：嵌套失败时日志要能定位是哪层抛的。

## 什么场景会改

- 关注点不正交时别硬装饰：鉴权、请求构造属于基础 client，不该做成装饰器。
- 增强需要改请求本身（统一 header / 改参数）时，装饰器可做，但要明确它成了"请求变换器"。

## 架构师洞察：统一扩展语言

同一原则在框架里出现三次：

```text
ModelClient   → Retry/Timeout/Fallback 装饰器   （决策 5）
ToolExecutor  → GovernedToolExecutor 装饰器     （决策 7）
AgentLoop     → ContextBuilder 挂载点           （决策 6）
```

"接口插槽 + 装饰器叠加 + null 透传向后兼容"是 agent4j 的设计基因。

## 面试表述

> ModelClient 的能力增强（重试/超时/降级/结构化输出）全用装饰器，不用继承。
> 因为是正交关注点，装饰器能任意叠加，继承会组合爆炸；且不改接口，Agent 只依赖 ModelClient 一个抽象。
> 代价是顺序有语义、样板多、Timeout 藏线程池生命周期。
> 这和我工具治理的 GovernedToolExecutor 是同一套扩展语言。

## 关联

- 证据：`ModelClient` 接口 + `RetryModelClient` / `TimeoutModelClient` / `FallbackModelClient` / `StructuredOutputModelClient`；`ModelClientDecoratorsTest` 12 测试。
- 决策 7 同源；决策 6 是这套扩展语言在 Loop 上的挂载点。
