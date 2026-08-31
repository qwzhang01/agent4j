# 决策 0：Loop 是函数，不是线程

> 对应《agent4j 架构立场》骨架的决策 0。
> 一句话：**Agent 的"思考循环"是逻辑，写成一个函数；Agent 的"存活方式"是线程，交给外层调度。不要把这两件事搅在一起。**

## 一、先分清两个 "loop"

中文都叫"循环"，但它们是两回事：

### Loop ①：Agent 的"思考循环"（ReAct 循环）

Agent 干活的方式：

```text
思考 → 调工具 → 看结果 → 再思考 → 再调工具 → ... → 给出最终答案
```

一次"处理用户请求"内部可能转好几圈。这是**逻辑上的循环**。

### Loop ②：程序的"运行线程"（服务生命周期）

程序怎么活着：

```text
服务启动 → 常驻内存 → 等待请求 → 处理 → 继续等待 → ... → 服务关闭
```

一个进程/线程一直在跑。这是**物理上的循环**。

## 二、这句话说的是什么

> 我实现的是 Loop ①（ReAct 循环），但我把它写成一个函数，而不是一个常驻线程/服务。

agent4j 的 `AgentLoop.execute` 是一个函数，调用一次，跑完 ReAct 循环，返回结果：

```java
AgentState execute(AgentConfig config, AgentState state) {
    while (还有步数 && 没结束) {
        组请求 → 调模型 → 有工具就执行 → 继续
    }
    return state;   // 关键：状态进，状态出
}
```

三个特点：

1. **同步调用**：调它就跑完 ReAct 循环，返回。不启动线程，不常驻。
2. **状态是参数**：`state` 从外面传进来，跑完传出去。
3. **没有隐藏状态**：Agent 对象自己不留"上一次说到哪了"，所有状态都在 `AgentState` 里。

## 三、对比：线程 / 服务模型

大多数人（包括很多框架）的做法：

```java
class AgentService {
    BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    Thread worker;                 // 常驻线程
    AgentState internalState;      // 藏在对象内部

    void start() {
        worker = new Thread(() -> {
            while (true) {         // 这是"线程"循环
                Message m = inbox.take();
                process(m);        // 边处理边改 internalState
            }
        });
        worker.start();
    }
}
```

特征：

- Agent 是一个**活着的对象**，有线程、有内部状态。
- 给它发消息（往 inbox 塞），它自己在后台处理。
- 想知道"它现在处理到哪了"？得去看它藏在内部的状态。

## 四、为什么函数式更好

### 1. 可测

函数式：

```java
AgentState result = loop.execute(config, state);
assertEquals(..., result.getMessages());
```

不起线程、不等异步、不 mock 时钟，输入输出都是显式的。

线程式要先把服务起起来、喂消息、等它跑完，再想办法把内部状态抠出来断言——又慢又脆。

### 2. 可续跑（最关键）

状态是传进传出的，所以续跑天然免费：

```java
AgentState s1 = loop.execute(config, s0);  // 第一次跑
AgentState s2 = loop.execute(config, s1);  // 带着上次结果继续跑
```

线程式要续跑，得先存下内部状态，恢复时重建对象、恢复线程——这就是它天然难做 Checkpoint 的原因。

### 3. 可保存 / 可回放

函数式里 `state` 就是全部真相：

- 序列化存盘 = Checkpoint
- 重新加载 = 恢复
- 重放 = 从某个历史 state 再 execute 一次

**函数式是 durable execution（可持久化执行）的地基。** Checkpoint、Scheduler、断点恢复全都建立在这个基础上。如果 Loop 是线程，这些全都要重新设计。

### 4. 无隐藏并发问题

函数式没有后台线程，就没有 race condition。谁调用谁负责，状态争用一目了然。

## 五、代价（必须会答）

函数式不是免费的：

1. **所有历史都压在 `AgentState` 参数里**。长任务跑 100 步，`state.messages` 就有 100 条消息，每次 execute 都把这个大对象传来传去——这是后面要做 compaction 的原因之一。
2. **服务级能力全没了**：背压、取消、超时、并发限流、事件驱动唤醒，这些线程/服务模型自带的能力，函数式全不管，推给调用方。
3. **不能"后台自己继续"**：需求是"用户关了窗口，Agent 还要在后台继续干活"，函数式 Loop 做不到——必须有人调它。解决方式是**在外面包一层 scheduler**：scheduler 是常驻线程，在合适时机调用 `execute`。

## 六、什么场景会改（边界条件）

当需要「常驻 + 事件驱动 + 后台自动继续」时，**不是改 Loop，而是在 Loop 外面包一层常驻运行时**。

这正是 agent4j 实际做的事：

```text
agent-scheduler（常驻，有线程，事件驱动）
        ↓ 在合适的时机调用
ReActAgentLoop.execute（纯函数，跑一次 ReAct）
        ↓ 返回 state
agent-scheduler 存 state / 等下一个事件
```

**函数负责"怎么跑一次"，调度器负责"什么时候跑"。** 这个分层就是决策 0 的全部含义。

## 七、面试表述

> Agent 的"思考循环"是逻辑，写成一个函数；Agent 的"存活方式"是线程，交给外层调度。
> 代价是 state 膨胀和服务级能力缺失，解决方式是在外面包 scheduler。

补上"代价"和"边界条件"，这道题就是架构师级别的回答。

## 关联

- 决策 2「状态和执行分离」是决策 0 的另一面。
- 证据：`ReActAgentLoop` + `SimpleAgent` 的委托结构；`notes/` 中"Loop 是函数，不是线程"的表述。
