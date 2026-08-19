# Stage 7 专题：事件总线怎么理解

> 对应阶段：Stage 7 - 异步任务调度器
> 来源：理解 EventBroker 的本质--它不是消息队列，就是一个「谁在等什么」的登记本
> 配套：概念与数据流见 [stage-7-scheduler.md](stage-7-scheduler.md)，架构设计见 [architecture-stage-7.md](architecture-stage-7.md)

---

## 一、生活比喻：快递柜

EventBroker 就是一个**快递柜**：

```
你（节点）想等一个包裹（事件）：
  -> 你不站在门口等
  -> 你去快递柜登记：「我的手机号是 138xxxx，有我的包裹就通知我」
  -> 你走了（暂停），干别的事去

快递员（外部系统）送来包裹：
  -> 快递员把包裹放进快递柜
  -> 快递柜看登记本：138xxxx 有包裹 -> 通知你

你回来取包裹：
  -> 快递柜把包裹给你
  -> 你拿着包裹继续干你的事
```

对应到代码：

```
你 = 暂停的 Run（runId）
包裹 = 事件 payload
手机号 = eventKey（如 "ci-passed:pr-42"）
登记 = subscribe(eventKey, runId)
快递员投递 = fire(eventKey, payload)
取包裹 = resume 后 getEventPayload(eventKey)
```

---

## 二、EventBroker 就三个数据结构

```java
public class EventBroker {
    // 登记本：eventKey -> 谁在等
    Map<String, List<EventTrigger>> subscriptions;

    // 已投递的包裹：eventKey -> 包裹内容
    Map<String, Object> eventPayloads;

    // 已投递标记（Bug1 修复后加的）：哪些 key 被投递过
    Set<String> firedKeys;
}
```

就这三个 Map/Set，没有别的。**EventBroker 就是一个「谁在等什么 + 东西到了通知谁」的登记本。**

---

## 三、三个操作，对应快递柜的三个动作

### 1. subscribe：登记「我在等」

```java
// 节点首次执行时调用
scheduler.waitForEvent(runId, "ci-passed:pr-42")

// EventBroker 内部做的事：
subscriptions.computeIfAbsent("ci-passed:pr-42", k -> new ArrayList<>())
             .add(trigger);   // 登记本上加一行：这个 runId 在等这个 key
```

**就是往登记本写一条**：「runId=r1 在等 ci-passed:pr-42」。

### 2. fire：快递员投递

```java
// 外部系统调用
scheduler.fireEvent("ci-passed:pr-42", "all-green")

// EventBroker 内部做的事：
firedKeys.add("ci-passed:pr-42");                    // 标记：这个 key 来过了
eventPayloads.put("ci-passed:pr-42", "all-green");   // 存包裹
List<EventTrigger> triggers = subscriptions.remove("ci-passed:pr-42");  // 取出登记本
for (EventTrigger trigger : triggers) {
    trigger.markFired();          // 标记：这个 trigger 已投递
    runManager.resume(trigger.runId());  // 通知等待者
}
```

**就是查登记本 -> 把包裹存好 -> 通知所有等这个包裹的人。**

### 3. hasFired / getPayload：取包裹

```java
// 节点恢复后调用
if (scheduler.hasEventFired("ci-passed:pr-42")) {      // 包裹到了吗？
    Object payload = scheduler.getEventPayload(...);   // 取包裹内容
    return NodeResult.of(payload);                     // 拿着包裹继续
}
```

---

## 四、为什么不直接调函数，要搞个「快递柜」

因为**投递的时候你不在**。

```
没有 EventBroker 的世界：
  CI 通过了 -> 直接调 agent.continue("ci-passed")
  但 agent 此时是暂停的，没有线程在跑，你调谁？

有 EventBroker 的世界：
  CI 通过了 -> fire("ci-passed", payload)  // 投递
  EventBroker 查登记本 -> 发现 runId=r1 在等
  -> runManager.resume(r1)  // 把 agent 唤醒
  -> agent 恢复后取 payload 继续
```

**EventBroker 解耦了「投递」和「接收」**。投递方（CI 系统）不需要知道 agent 在哪、是什么状态，只管 fire。接收方（agent）不需要轮询，只管注册然后睡觉，有人叫就醒。

---

## 五、一个 eventKey 可以多人等

```java
// 两个 Run 都在等同一个 CI 结果
run1: scheduler.waitForEvent("ci-passed:pr-42")  // 登记本加一条
run2: scheduler.waitForEvent("ci-passed:pr-42")  // 登记本再加一条

// CI 通过
scheduler.fireEvent("ci-passed:pr-42", "green")
// 登记本里有两条 -> 两个 Run 都被 resume
```

登记本是 `Map<String, List<EventTrigger>>`--一个 key 对应一个**列表**，不是一个。这就是为什么用 List 而不是单个值。

---

## 六、和消息队列（Kafka/RabbitMQ）的区别

```
EventBroker（我们的）：
  - 进程内，Map + 回调
  - fire 时同步调 resume
  - 进程崩了，登记本丢了
  - 不持久化，不跨进程

Kafka/RabbitMQ：
  - 跨进程，独立服务
  - 消费者自己拉取
  - 持久化，进程崩了消息还在
  - 支持重试、死信、分区
```

我们的 EventBroker 是教学版--**理解「事件驱动恢复」的机制**。生产化换 Kafka，接口不变（subscribe/fire 的语义一样），只换实现。这就是 D3 的取舍：先理解机制，分布式留给 Stage 11。

---

## 七、一句话

**EventBroker 是一个「谁在等什么」的登记本：节点注册（subscribe）-> 外部投递（fire）-> 查登记本通知等待者（resume）。** 投递方不需要知道接收方在哪，接收方不需要轮询--解耦了「事件到达」和「Agent 恢复」两件事。本质就是一个 Map + 回调，和 Kafka 的区别只是进程内 vs 跨进程。
