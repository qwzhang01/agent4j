# Checkpoint、幂等性和重复执行

> Stage 6 文章草稿 4/4

---

## 三层幂等性

```
层 1：节点级幂等 -- 同一个节点重复执行，结果相同（或可接受不同）
层 2：Run 级幂等 -- Resume 不重跑已完成节点（cursor 机制）
层 3：副作用幂等 -- 崩溃后重跑节点，外部副作用不重复（idempotency key）
```

## 层 1：节点级幂等

### 哪些节点天然幂等

```
ActionNode（纯 Java 逻辑）：
  ctx -> "A(" + ctx.input() + ")"
  重复执行结果相同 -> 天然幂等

ToolNode（只读工具）：
  query_order(orderId) -> 订单状态
  重复执行结果相同（假设订单状态没变） -> 天然幂等

AgentNode（LLM 决策）：
  agent.run(input) -> LLM 输出
  重复执行结果可能不同（LLM 有温度） -> 不幂等
  但如果 AgentState 被正确恢复，LLM 看到的上下文相同，输出大概率相近
```

### 哪些节点不幂等

```
有副作用的工具：
  send_email(to, subject) -> 发了就是发了，重发两封
  refund(orderId) -> 退了就是退了，重退两次
  create_resource() -> 创建了就是创建了，重创两个

LLM 调用：
  花钱，重跑 = 重花 token
```

## 层 2：Run 级幂等（cursor 机制）

### 机制

```
Checkpoint 存 cursor = 下一个待执行节点
Resume 从 cursor 开始，不从 START 开始
已完成节点的 output 在黑板里，不重跑

结果：已完成节点不会被重新执行 -> 天然 Run 级幂等
```

### 例外：暂停节点会被重跑

```
暂停节点抛了 PauseException，没产出 output
Resume 时必须重跑这个节点（走 isResuming 恢复路径）

trace 里的记录：
  prepare     SUCCESS   // 已完成，不重跑
  approval    PAUSED    // 暂停记录
  approval    SUCCESS   // 恢复后重跑，这次成功
  execute     SUCCESS   // 恢复后正常执行
```

暂停节点重跑是安全的--恢复路径不重新发审批请求，只检查已有决策。

### 验证（我们的测试）

```java
// resumeDoesNotReplayCompletedNodes 测试
long prepareCount = r2.trace().stream()
        .filter(r -> "prepare".equals(r.nodeId()))
        .count();
assertEquals(1, prepareCount, "prepare node should not be re-executed on resume");
```

## 层 3：副作用幂等（崩溃后重跑）

### 问题

```
节点执行到一半崩溃（如发了邮件但没写黑板）-> 恢复后重跑 -> 再发一封邮件
```

Run 级幂等（cursor）解决了「已完成节点不重跑」，但解决不了「执行到一半崩溃的重跑」。这需要副作用幂等。

### 解法：idempotency key

```
节点执行副作用操作时，带一个幂等键
  send_email(idempotencyKey="runId:nodeId:attempt", ...)

接收方（邮件服务）记住这个 key
  第一次收到 -> 发邮件
  第二次收到相同 key -> 返回第一次的结果，不重发
```

### 代码层面

```java
// 节点从 ctx 拿到 runId，构造幂等键
public NodeResult execute(NodeContext ctx) {
    String idempotencyKey = ctx.runId() + ":" + this.id;
    emailService.send(idempotencyKey, to, subject, body);
    return NodeResult.of("sent");
}
```

dsh 的 README 也提到了这个思路：
> The policy durably records execution intent, not generic exactly-once effects.
> Side-effecting tools should forward exec.callId as an idempotency key when their provider supports one.

### 我们的现状

当前框架不强制要求节点传 idempotency key--这是节点实现者的责任。框架提供 `ctx.runId()` 供节点构造幂等键，但用不用是节点的事。

**这是合理的边界**：框架管 Run 级幂等（cursor），节点管副作用幂等（idempotency key）。框架不能替节点知道「这个操作有没有副作用」。

## 幂等性 vs at-least-once vs exactly-once

```
at-most-once：节点最多执行一次，崩溃可能丢执行
  -> 不安全，Agent 不能接受丢步骤

at-least-once（我们的选择）：节点至少执行一次，崩溃可能重复执行
  -> Run 级幂等保证已完成节点不重跑
  -> 崩溃重跑的节点靠 idempotency key 保证副作用不重复
  -> 实际效果 ≈ exactly-once（如果节点正确使用了 idempotency key）

exactly-once：节点恰好执行一次
  -> 分布式系统理论上的圣杯，实际靠 at-least-once + 幂等实现
  -> 我们走的就是这条路
```

## 一句话

**Run 级幂等（cursor 不重跑已完成节点）是框架的活；副作用幂等（idempotency key 防重复副作用）是节点的活。** 框架提供 `ctx.runId()` 让节点能构造幂等键，但不替节点管副作用。at-least-once + 幂等 = 实际的 exactly-once。
