# 决策 2：状态和执行分离（AgentState 可变、Loop 无状态）

> 对应《agent4j 架构立场》骨架的决策 2。
> 一句话：**Loop 只持有"配置期依赖"，不持有"每次 run 的会话状态"；状态是一等公民对象 AgentState，可保存、恢复、重放。**

## 一、先澄清误解：无状态 ≠ 无字段

`ReActAgentLoop` 有一个字段 `toolExecutor`，但它是依赖，不是状态。

| | 依赖（dependency） | 状态（state） |
|---|---|---|
| 例子 | `toolExecutor`、`ModelClient` | 消息列表、当前步数、任务状态 |
| 在一次 run 内会变吗 | 不会 | 会 |
| 放哪 | Loop 字段，`final` | `AgentState` |
| 生命周期 | 配置一次，所有 run 共用 | 每次 run 一份 |

判断标准：**在一次 run 里会变的东西 = 状态，放 AgentState；不变的东西 = 依赖，留在 Loop 字段。**

## 二、对比：错误的做法（Loop 持有状态）

```java
class BadAgentLoop {
    private List<ChatMessage> messages = new ArrayList<>();  // 状态藏在循环里
    private int step = 0;

    AgentState run(...) {
        // 问题1：第二次调用带第一次残留
        // 问题2：多用户并发互相串
        // 问题3：存盘/恢复得序列化整个 loop 对象
    }
}
```

## 三、agent4j 的做法

```java
class ReActAgentLoop {
    private final ToolExecutor toolExecutor;   // 依赖，配置一次

    AgentState execute(AgentConfig config, AgentState state) {
        state.addMessage(...);      // 改传入的 state
        state.incrementStep();
        return state;               // 传出去
    }
}
```

状态从外面来，改完传出去。Loop 自己不留任何"这次跑"的记忆。

## 四、为什么这么分

1. **一个 Loop 实例服务无数个 run**：无状态才能安全复用和并发。
2. **状态成为一等公民**：可检查、可保存、可恢复、可重放。
3. **可测**：构造 state → execute → 断言输出 state，不窥探内部。
4. **并发安全**：Loop 无可变字段，唯一被写的是调用方独占的 state，无共享可变状态。

## 五、代价（必须答）

1. **调用方要自己"接着传"**：Loop 不替你记住上一轮，忘了传 state 就断连续性。魔法变少，责任变多。
2. **长任务 state 膨胀**：整个对话历史在 state 里全程在场，token 会堆——这是 compaction 存在的理由。
3. **可变的代价**：`AgentState` mutable，没有自动每步快照，Checkpoint 要自己决定序列化时机。

## 六、刁钻追问：为什么 mutable，不 immutable

| | 可变 AgentState（选了） | 不可变（每步 new） |
|---|---|---|
| 快照 | 手动序列化 | 天然每步有历史快照 |
| 并发安全 | 依赖"一次 run 单线程"约定 | 天生安全 |
| 分配/GC | 低 | 高（或引持久化数据结构） |
| Java 习惯 | 惯用 | 反直觉、啰嗦 |
| 复杂度 | 低 | 高 |

选择逻辑：v1 Loop 单线程跑完，没有边跑边并发改 state 的需求；需要快照时 Checkpoint 主动序列化即可；Java 里不可变成本高收益小。

什么场景会改：要做事件溯源式每步审计（每步状态独立回放、可 diff），或一个 run 被多观察者并发读取时，值得上不可变 + 持久化数据结构。那是架构升级，不是 v1 该做的。

## 七、架构师级洞察：所有 durable 系统的共同底座

```text
agent4j        → AgentState（状态） + AgentLoop（执行）
Redux          → store（状态） + reducer（执行）
Event Sourcing → event log（状态） + fold（执行）
状态机         → state（状态） + transition（执行）
Workflow 引擎  → 黑板（状态） + GraphRuntime（执行）
```

共同规律：执行函数相对自己的字段是纯的；可变状态是一等公民对象；这样才能持久化、检查、重放。

agent4j 里这个模式出现两次：

```text
内层：AgentState（可变，传进传出）
外层：WorkflowState 黑板 + Checkpoint（持久化）
```

内外层同原则、不同持久化策略——这就是架构一致性。

## 八、面试表述

> 会话状态和执行逻辑分离：AgentState 是一等公民对象（消息、步数、状态），Loop 只持有配置期依赖，不持有 run 的会话状态。
> 这样同一个 Loop 实例服务任意多 run，状态可保存/恢复/重放——这是 Checkpoint 和断点续跑的前提。
> 代价是调用方要自己持有并传递 state；可变 AgentState 没有自动快照，需要 Checkpoint 层主动序列化。
> 选可变不选不可变，因为 v1 Loop 单线程跑完、不需要每步快照，Java 里不可变成本高收益小；将来做事件溯源式审计时再升级。

## 关联

- 证据：`ReActAgentLoop.execute(config, state) -> state`；`AgentState` 的 addMessage/incrementStep 可变方法；`agent-workflow` 的 `WorkflowState` + `Checkpoint`。
- 决策 0「Loop 是函数，不是线程」讲 Loop 的形态；本决策讲状态住在哪——两者是一体两面。
