# 决策 8：Checkpoint 存"实际发送给模型的 state"，而不是存"请求副本"

> 对应《agent4j 架构立场》骨架的决策 8。
> 一句话：**持久化"源"（AgentState），不持久化"投影"（ModelRequest）——request = f(state)，存输入不存输出。**

## 实现

```java
// 每步 buildRequest 从 state 派生请求
ModelRequest request = buildRequest(config, state);  // 投影，临时
// Checkpoint 存 AgentState（消息/步数/状态），Jackson 可序列化
state.snapshot() → workflow 黑板 agentState:{nodeId}
```

## 为什么存 state 不存 request

1. **state 是源，request 是派生**：request = messages + tool schemas + 记忆注入，都能从 state/config/store 重算。存派生副本引入第二事实源，漂移。
2. **恢复需要 state**：续跑要的是"第几步、什么状态、消息到哪"，不是冻结的请求快照。
3. **每步都重算 request**：Loop 每轮 buildRequest()，存 request 无续跑价值。

## 关键一致性约束

"存 state" 成立的前提是 state 能还原"模型看到的东西"，两条规则必须配合：

```text
① 派生可重算的（记忆注入）→ 不回写 state，恢复时从记忆库重注入
② 对会话本身的有损变换（compaction）→ 必须就地改写 state
```

若 ② 只压 request 副本不改 state，恢复后 state 还是未压缩完整历史，模型所见与压缩前不一致——这是决策 9，决策 8/9 是一对。

## 代价（必须答）

1. **重算依赖派生源稳定**：恢复时记忆库/注册表变了，重放上下文略异——"快照源 + 重投影"的固有代价。
2. **state 必须全序列化**：新字段忘序列化，恢复丢数据。
3. **精确回放仍需额外记录**：字节级审计要额外在模型边界记录 request——这是 agent-trace-export 的职责，审计件不是 Checkpoint。

## 什么场景会改

- 字节级精确回放（DPO 训练 / 合规）→ 边界额外记录 request，Checkpoint 仍存 state，分工。
- 派生源非确定性（记忆检索带随机性）→ 恢复前连派生内容一起快照。

## 架构师洞察

```text
Checkpoint 存 state（真相）
ModelRequest = f(state)（投影）
→ 存输入，不存输出
```

与决策 0/2 连成线：state 是一等公民 → 才谈得上"存 state 不存 request"。
决策 9 是配套规则：有损变换必须作用于 state，否则"存 state"存了假真相。

## 面试表述

> Checkpoint 存 AgentState 不存 ModelRequest，因为 request 是每步从 state 派生的投影，存输入不存输出，避免双事实源。
> 前提：派生的要么可重算（记忆注入恢复时重查）、要么有损变换直接改 state（compaction），保证 state 能还原模型所见。
> 代价是恢复依赖派生源稳定；字节级精确回放另有 trace-export 在模型边界记录。

## 关联

- 证据：`AgentState` Jackson 可序列化 + `snapshot()`；`ReActAgentLoop.buildRequest()` 每步派生；`agent-trace-export` 在模型边界记录轨迹。
- 决策 0/2 是前提；决策 9 是配套一致性规则。
