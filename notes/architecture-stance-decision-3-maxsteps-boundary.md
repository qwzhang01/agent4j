# 决策 3：maxSteps 作为唯一安全边界，不做 token 预算硬截断

> 对应《agent4j 架构立场》骨架的决策 3。
> 一句话：**控制流边界（步数）用计数器管，内容边界（token）用 ContextBuilder/compaction 管——两者正交，不能混在一个 Loop 里。**

## 一、Agent 有两个正交的资源边界

```text
边界 A：步数上限（maxSteps）
  管：ReAct 循环最多转几圈
  防：无限循环、成本失控、延迟失控
  单位：次（模型调用次数）

边界 B：上下文上限（token 预算）
  管：每次发给模型的内容多大
  防：超窗口、指令遵循退化、成本
  单位：token
```

两者正交，谁也替代不了谁：

- `maxSteps=10` 限制"转几圈"，但每圈上下文可以无限大。
- token 预算限制"每圈多大"，但圈数可以无限多。

## 二、选择：Loop 只管步数

```java
while (state.hasStepsRemaining() && !state.isTerminal()) {
    state.incrementStep();
    // 组请求 → 调模型 → 执行工具
}
```

`hasStepsRemaining()` = `currentStep < maxSteps`，整数比较。Loop 里没有 token 计算、没有截断逻辑。token 的事在 `buildRequest()` 交给 `ContextBuilder`（决策 6），由 `ContextCompressor` 执行（决策 9）。

## 三、为什么 Loop 只用 maxSteps

1. **便宜且确定**：整数递增 + 比较，零依赖、零开销、可预测。
2. **控制流边界 vs 内容边界**：步数是循环的控制变量，属于 Loop 职责；token 是内容的有损决策，不属于控制流。
3. **可恢复**：`maxSteps` 存在 `AgentState` 里，而 `AgentState` 可序列化——跑爆步数的 run 可以带着 state 恢复、调大 maxSteps 继续跑（决策 2 的回报）。

## 四、为什么不做 token 硬截断

硬截断 = 超 token 直接砍最旧消息。便宜但在 Loop 里是错的：

1. **有损且无脑**：可能砍掉 system prompt、关键工具结果、用户约束，且静默执行。
2. **破坏状态一致性**（决策 8/9）：只截请求副本不回写 state，Checkpoint 存的就和模型看到的不一致。
3. **截断策略本身可选**：摘要、滑动窗口、归档、记忆注入是不同策略，硬编码进 Loop 就锁死了。

正确分层：

```text
Loop           → 只管"转几圈"（maxSteps）
ContextBuilder → 管"每圈塞什么、塞多少"（token 预算 + 压缩 + 记忆注入）
```

## 五、关键盲区（面试必问）

> `maxSteps=10`，第 3 步工具返回 100KB，上下文爆了。maxSteps 救得了吗？

**救不了。** maxSteps 只限圈数，不限每圈 token。第 3 步就爆窗口，走不到第 10 步就失败。

```text
maxSteps   防"跑不停"
compaction 防"装不下"
两个都要，缺一个就漏一类事故
```

这正是 agent4j 把两者放在两个模块的原因——架构一致性，不是巧合。

## 六、maxSteps 到了之后：优雅终止，不硬杀

```java
if (!state.hasStepsRemaining()) {
    state.setStatus(AgentState.Status.MAX_STEPS_EXCEEDED);
}
return state;   // 正常返回，不抛异常
```

- `MAX_STEPS_EXCEEDED` 是终端状态，与 `DONE`、`ERROR` 平级。
- 调用方拿到明确、可检查的结果，不是崩溃。
- state 保留中间进度，且可序列化 → "跑了一半"可续跑。

## 七、代价（必须答）

1. **不防 token 爆**：只解决"无限循环"，不解决"上下文溢出"。缺口靠 compaction 补；不配 ContextBuilder 就裸奔。
2. **安全阀不是质量阀**：跑到第 10 步才停，可能前 8 步原地打转。能保证"停"，不能保证"停在对的地方"。
3. **阈值拍脑袋**：默认 10，不同任务差异大。太低掐断正常任务，太高成本失控，阈值需要经验或动态调整。

## 八、什么场景会改

- 需要同时防 token 爆 → 加 ContextBuilder/compaction（补另一半，不是改 Loop）。
- 需要动态步数 → 任务复杂度自适应，或成本预算驱动的步数上限，把固定 maxSteps 升级为步数策略。
- 需要质量阀 → "原地打转"比"跑不停"更致命（如交易 Agent）时，加循环检测 / 目标达成检测，在 Loop 外再包一层。

## 九、架构师级洞察

> "控制流边界"和"内容边界"是两类资源约束，必须分开治理。
> 控制流用便宜、确定的计数器管；内容用有损、可选的策略层管。
> 混在一个 Loop 里，要么内容被无脑截断，要么循环失控。

可迁移：连接池的"连接数 vs 单查询数据量"、HTTP 的"并发数 vs 请求体大小"、队列的"任务数 vs 单任务负载"——数量维度和大小维度不该用同一参数管。

## 十、面试表述

> Loop 的默认安全边界只有 maxSteps——一个整数计数器，只管循环转几圈。
> 我不在 Loop 里做 token 硬截断，因为截断是有损的内容决策，属于 ContextBuilder/compaction 职责；而且截断不同步回写 state 会破坏 Checkpoint 一致性。
> 代价是 maxSteps 不防 token 爆，工具大结果会撑爆上下文，这个缺口靠 compaction 补。
> maxSteps 到了是优雅终止：状态置 MAX_STEPS_EXCEEDED、正常返回、可检查可续跑。
> 要动态步数或成本预算，就把固定 maxSteps 升级为步数策略；要防原地打转，就在 Loop 外再加质量阀。

## 关联

- 证据：`AgentState.hasStepsRemaining()` / `maxSteps` / `Status.MAX_STEPS_EXCEEDED`；`ReActAgentLoop.execute()` 的 while 条件与步数耗尽分支；`ContextBuilder` + `ContextCompressor` 在 agent-memory 不在 agent-core。
- 决策 6 是 token 预算的挂载点；决策 9 是 token 预算的具体实现。
