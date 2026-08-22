# Stage 12 代码走读（agent-channel 设计落点）

> 对应阶段：Stage 12 - 频道级共享 Agent、Agent Identity 与 Ambient 模式
> 定位：设计如何落地为代码——从概念到实现（承接 [stage-12-channel-scenarios-concepts.md](stage-12-channel-scenarios-concepts.md)）
> 配套：架构设计见 [architecture-stage-12.md](architecture-stage-12.md)，源码可读 `agent-channel` 模块 + `examples/ChannelAgentExample` / `AmbientExample`
> 状态：已实现（2026-08-22）。channel 78 测试全绿
> 本笔记以 `ChannelAgentExample` 为主线逐行走读，验证命令：`mvn test -pl agent-channel`

---

## 一、代码地图（先建立全局）

```
agent-channel/src/main/java/.../channel/
├── ChannelContext.java         # 数据：channelId + members（成员资格 SSOT）
├── ChannelMessage.java         # 数据：说话人消息 + mention 检测
├── SharedAgentSession.java     # ★ 核心容器：包 Agent + 路由 + 看板 + 事件流（D1）
├── identity/  (8 类)           # M12.1 身份层：AgentIdentity/ServiceAccount/IdentityScope/
│                               #   ChannelRolePermissions/IdentityResolver/ResolvedIdentity/
│                               #   IdentityDecision/IdentityResolutionException
├── collab/    (5 类)           # M12.3 协作：VisibilityEvent/ExecutionVisibility/
│                               #   ChannelTask/TaskBoard/TaskHandoff
└── ambient/   (4 类)           # M12.4 主动：AmbientInstruction/AmbientEngine/
                                #   NoisePolicy/ProactiveNotification
```

依赖方向：`agent-channel -> agent-core（Agent 接口）+ agent-memory（channel scope）+ agent-scheduler（TaskStatus）`。security 不依赖——身份审计用 `Consumer` sink 注入，装配层桥接。

### 模块设计手法规格（贯穿始终）

- **数据类全是 record**：不可变、构造器防御性拷贝（`Set.copyOf`）、自带校验
- **行为类用接口/函数式注入**：`ChannelRolePermissions`、`Consumer<IdentityDecision>`、`Consumer<ProactiveNotification>`——跨模块边界只传"回调"，不传"实现"（同 orchestrator 的模块边界纪律）
- **fail-closed 哲学**：身份/权限问题一律拒绝启动，绝不降级通过
- **sink/listener 异常隔离**：坏订阅者只记日志，不炸其他订阅者

---

## 二、主线 A：部署 + 一次 speak 的完整旅程（T0-T1）

### 第 1 步：部署（T0）

```java
// ① 服务账户：eng-bot 被显式授予的最小权限集（D4）
ServiceAccount account = ServiceAccount.of("svc-eng-bot-01",
        new AgentIdentity("eng-bot", "Engineering Bot", "team-eng-leads"),
        new IdentityScope(Set.of("chat"),
                Set.of("channel:team-eng", "agent:eng-bot"),   // memory 命名空间
                Set.of("internal")));

// ② 包装任意 Agent（D1 容器）：一行构造，零改动
var agent = new SimpleAgent(new AgentConfig("eng-bot", "...",
        MockModelClient.scripted().respondText("收到。注意到频道记忆：本周发布窗口冻结..."),
        null, 10,
        SharedAgentSession.channelMemoryContext(memory, "team-eng", "agent:eng-bot")));

// ③ 建 session：角色映射只是一个 lambda
SharedAgentSession session = new SharedAgentSession(agent, account,
        ChannelContext.of("team-eng", "alice", "bob", "carol"),
        (ch, uid) -> roles.getOrDefault(uid, Set.of()),
        null);
```

要点：
- `IdentityScope` 三维 = capabilities / memoryScopes / dataClassifications，**授予制最小集**（eng-bot 只有 `chat`，没有 `git.read`）
- `channelMemoryContext` 是 D2 落地：`MemoryScope.channel(c1)` 挂进 Stage 8 `MemoryContextBuilder` 检索列表——共享记忆不是新系统

### 第 2 步：构造器里最精妙的一处设计

`SharedAgentSession` 构造器把「成员资格」和「角色权限」两个输入源**组合成一个函数式接口**：

```java
ChannelRolePermissions gated = (ch, uid) -> {
    if (!channel.isMember(uid)) {
        return null;                              // 非成员 → null → USER_NOT_IN_CHANNEL
    }
    Set<String> caps = rolePermissions.capabilities(ch, uid);
    return caps != null ? caps : Set.of();        // 成员无角色 → 空集 → EMPTY_PERMISSION_INTERSECTION
};
this.resolver = new IdentityResolver(gated, auditSink).register(account);
```

为什么这样设计：`IdentityResolver` 只面对**一个**统一的用户侧权限提供者，就能区分两种拒绝语义——`null` 与空集是两个不同的 fail-closed 原因。成员判断与角色判断解耦成两个独立输入，在容器层合成，身份层保持简单。

### 第 3 步：alice speak（T1）——`speak` 的执行顺序

```java
public synchronized String speak(ChannelMessage message) {
    // 1) 频道一致性 fail-fast
    if (!message.channelId().equals(channel.channelId())) throw new IllegalArgumentException(...);

    // 2) 身份解析 fail-closed（mention 和 plain 都过闸）
    ResolvedIdentity identity = resolver.resolve(channel.channelId(), message.userId(), account.identity().agentId());

    history.add(message);                                   // 3) 所有消息进历史

    if (!message.mentionsAgent()) return null;              // 4) 非 mention：只进历史，不唤醒

    // 5) mention：加说话人归属 → 写入【共享】AgentState
    String prompt = "[from " + message.userId() + "] " + message.textWithoutMention(...);
    String reply = agent.run(prompt, sharedState);          // 6) 包着的 Agent 干活，state 是共享的
    visibility.publish(VisibilityEvent.of(channel.channelId(),
            VisibilityEvent.Type.AGENT_REPLIED, null,       // 7) 发布事件，全频道可见
            account.identity().agentId(), message.userId(), preview(reply)));
    return reply;
}
```

两个设计点：
- **为什么 synchronized**：`AgentState.messages` 是裸 `ArrayList`（agent-core 从 Stage 1 起的契约），`SharedAgentSession` 是框架里第一个"多人共享一个 state"的场景，并发 speak 会竞态（架构笔记 §13 修复的 bug #1，4 线程 × 2 轮并发回归测试验证）。串行化也符合频道语义：一个对话轮一次，像人队友不抢话。
- **为什么先 resolve 再进 history**：陌生人连进频道历史都不行——fail-closed 发生在任何状态变更之前。

### 第 4 步：展开 `IdentityResolver.resolve`（D4 核心）

```java
public ResolvedIdentity resolve(String channelId, String userId, String agentId) {
    ServiceAccount account = accountsByAgentId.get(agentId);
    if (account == null)  return deny(...UNKNOWN_AGENT...);             // 1. 没注册
    Set<String> granted = account.grantedScope().capabilities();
    if (now.isBefore(account.validFrom()))   return deny(...ACCOUNT_NOT_YET_VALID...);
    if (!now.isBefore(account.validUntil())) return deny(...ACCOUNT_EXPIRED...);   // 2. 有效期
    Set<String> role = rolePermissions.capabilities(channelId, userId);
    if (role == null) return deny(...USER_NOT_IN_CHANNEL...);           // 3. 非成员
    Set<String> effective = intersect(granted, role);                   // 4. 交集！
    if (effective.isEmpty()) return deny(...EMPTY_PERMISSION_INTERSECTION...);
    // 5. 成功 → ResolvedIdentity（actor="svc:..."）
    emit(IdentityDecision.allowed(...));
    return new ResolvedIdentity(channelId, userId, account.identity(), account.accountId(),
            effective, account.grantedScope(), now);
}
```

三个实现细节：
- **权限交集就一行**：`granted.stream().filter(role::contains).collect(toUnmodifiableSet())`——取交集非并集，单侧存在的能力活不下来
- **fail-closed 是"抛异常带证据"**：`deny()` 构造 `IdentityDecision` → 喂审计 sink → 抛 `IdentityResolutionException(decision)`。异常携带完整 decision（granted/role/原因），测试断言不用字符串匹配
- **审计 sink 容错**：`emit()` 用 try-catch 包裹——坏 sink 只记日志，不改解析语义（"审计可以坏，权限语义不能坏"）

### 第 5 步：看板怎么跟上（D6 事件流）

```
session.waitingHuman(taskId, "选保守方案还是激进方案")
  → visibility.publish(VisibilityEvent(WAITING_HUMAN, taskId, actor=alice, target="选保守..."))
  → TaskBoard.onEvent()   // board 是 Listener，和外部订阅者一样订阅同一个流
  → switch(WAITING_HUMAN) → mutate(taskId, t -> t.withStatus(TaskStatus.WAITING_HUMAN))
```

TaskBoard 不是独立存储，是**事件流的物化视图**（"The board never invents state"）。状态机复用 Stage 7 `TaskStatus` 枚举，不造第二个。

---

## 三、主线 B：handoff 三件套落成代码（T2）

```java
session.handoff(taskId, "alice", "bob", "迁移约束在频道记忆里，按保守方案继续");
```

四守卫 + 三件套：

```java
public synchronized TaskHandoff handoff(String taskId, String fromUser, String toUser, String note) {
    requireMember(fromUser); requireMember(toUser);
    ChannelTask task = board.task(taskId).orElseThrow(() -> new IllegalArgumentException("Unknown task"));
    if (task.isTerminal()) throw new IllegalArgumentException("终态任务不能移交");
    if (!task.owner().equals(fromUser)) throw new IllegalArgumentException("不能移交别人的任务");

    // 三件套 ① 对话连续：共享 state 不重建，注入 [handoff] system 便签让模型知道换手
    sharedState.addMessage(ChatMessage.system(
            "[handoff] task " + taskId + " owner " + fromUser + " -> " + toUser
                    + (note != null ? " | note: " + note : "")));

    // 三件套 ② 工作记忆：零动作（channel/task scope 天然共享，代码注释说明）

    // 三件套 ③ 看板 owner 变更：发布 TASK_HANDOFF 事件
    visibility.publish(VisibilityEvent.of(channel.channelId(),
            VisibilityEvent.Type.TASK_HANDOFF, taskId, fromUser, toUser, note));
    //  → TaskBoard 收到事件 → t.withOwner(toUser)

    TaskHandoff record = new TaskHandoff(taskId, fromUser, toUser, note, Instant.now());
    handoffs.add(record);                    // 审计轨迹
    return record;
}
```

理解要点：**移交的是"同一个对象"不是"一份拷贝"**。三件套里只有 ① 和 ③ 有动作，② 零动作——scope 记忆本来就共享。代码的克制本身就是设计。

bob 说 `"继续刚才的迁移调研"` 后，`[from bob] 继续刚才的迁移调研` 追加进**同一个** `sharedState`。模型看到的 messages = alice 的发言 + `[handoff]` 便签 + bob 的发言。`SharedAgentSessionCollabTest` 用 `RecordingModelClient` 装饰器捕获模型实见消息来结构性证明。

---

## 四、主线 C：Ambient 触发管线（AmbientExample）

### 1. 默认 disabled（安全默认值）

```java
AmbientEngine engine = new AmbientEngine(session, openPolicy);
engine.register(AmbientInstruction.onEvent("pr-silence", "跟踪沉默 PR", "pr-silent", WARN,
        p -> ((int) p) >= 3,                 // condition：值不值得说
        p -> "PR 已沉默 " + p + " 天，要不要跟进？"));
engine.fireEvent("pr-silent", 4);            // disabled → 只记录，不武装 → sent()==0
```

`register` 里 `if (enabled) arm(...) else log("Recorded (engine disabled)")`——**enable() 才是武装动作**。

### 2. enable 后触发一次：`onTriggered` 管线（四段）

```java
// 1) 条件判定：不满足 → 全静音（连 digest 都不进）
try { worthIt = instruction.condition().test(payload); }
catch (Exception e) { return; }              // condition 抛异常 = 视为不满足，不炸引擎
if (!worthIt) return;

// 2) 噪音闸（NoisePolicy.admit 一次调用 = 决策 + 记录）
NoisePolicy.Verdict verdict = noise.admit(instruction.instructionId(), instruction.importance(), now);
if (verdict == SUPPRESS) return;

// 3) 生产通知：actor 是 Agent 身份（不是事件源）
String content = instruction.message().apply(payload);
ProactiveNotification notification = new ProactiveNotification(null, instruction.instructionId(),
        session.channel().channelId(), session.identity().agentId(), content, importance, now);

if (verdict == DIGEST) { noise.enqueueDigest(notification); return; }

// 4) 实时推：sinks（异常隔离）+ NOTIFICATION_SENT 进事件流（全频道可见）
sent.add(notification);
for (Consumer<ProactiveNotification> sink : sinks) {
    try { sink.accept(notification); } catch (Exception e) { log.error(...); }
}
session.visibility().publish(VisibilityEvent.of(..., NOTIFICATION_SENT, ...));
```

### 3. 四道闸的闸序（NoisePolicy.admit，D7 精髓）

```java
// Gate 1：频控（先于一切；digest 也计入——digest 刷屏也是刷屏）
Instant last = lastEmission.get(instructionId);
if (last != null && now.isBefore(last.plus(minInterval))) return SUPPRESS;

boolean critical = (importance == CRITICAL);

// Gates 3+4：先判"意图"（静默窗 + 分级）
Verdict verdict;
if (inQuietWindow(now)) verdict = critical ? NOTIFY : DIGEST;          // CRITICAL 豁免静默窗
else                    verdict = (importance == INFO) ? DIGEST : NOTIFY;  // INFO 永远 digest

// Gate 2：预算只拦 realtime（digest 不占预算）；CRITICAL 再豁免预算
if (verdict == NOTIFY && !critical && consumedToday(now) >= dailyBudget) return SUPPRESS;

lastEmission.put(instructionId, now);        // digest 也更新频控时间戳
if (verdict == NOTIFY) realtimeCountByDay.merge(...);
return verdict;
```

> ⚠️ 这个闸序是**测试抓出来的真 bug**（架构笔记 §13.1）：首版把预算闸放在分级判定之前，导致预算耗尽后连 INFO 的 DIGEST 都被吞（与"digest 不占预算"语义矛盾）。修正后顺序：频控（无条件）→ 判意图 → 预算只拦 realtime。

### 4. 静默窗的跨午夜数学

```java
if (quietFrom.isAfter(quietTo)) {   // 22:00-08:00 跨午夜
    return !t.isBefore(quietFrom) || t.isBefore(quietTo);   // [22:00,24:00) ∪ [00:00,08:00)
}
return !t.isBefore(quietFrom) && t.isBefore(quietTo);
```

测试技巧：`AmbientEngineTest` 用退化静默窗 `00:00-00:00`（永不静默）保证任意墙钟时间判定确定。

---

## 五、设计 → 代码落点速查表

| 设计决策 | 代码位置 | 一句话实现 |
|---|---|---|
| D1 容器不继承 | `SharedAgentSession` 持有 `Agent agent` | 构造注入 + `agent.run(prompt, sharedState)` 一行调用 |
| D2 复用 channel scope | `channelMemoryContext()` 静态工厂 | `MemoryScope.channel(c1)` 加进 `MemoryContextBuilder` 检索列表 |
| D3 Ambient 复用机制 | `AmbientEngine.arm()` | `ScheduledExecutorService` + 自建 eventKey 注册表（if-instanceof 区分两种 Trigger） |
| D4 三方身份交集 | `IdentityResolver.resolve()` | granted ∩ role，五条件 fail-closed，actor="svc:..." |
| D5 三件套移交 | `SharedAgentSession.handoff()` | 注入 system 便签 + 零动作记忆 + TASK_HANDOFF 事件 |
| D6 事件流物化视图 | `TaskBoard implements Listener` | 事件是唯一写入路径，构造时 `visibility.subscribe(board)` |
| D7 噪音四道闸 | `NoisePolicy.admit()` | 频控→意图→预算，CRITICAL 双豁免 |
| D8 v1 单 JVM | 全模块 | 进程内事件流 + ConcurrentHashMap，无外部依赖 |

---

## 六、五个"代码里最值得品"的细节

1. **`gated` 闭包**（构造器）：把成员闸门合成进 `ChannelRolePermissions`，身份层不用知道"成员资格"这个概念——分层最干净的一行
2. **record 构造器的防御性拷贝**：`ChannelContext` 里 `members = Set.copyOf(members)`、`IdentityScope` 三维全部 `immutableCopy`——record 字段可变集合的标配防篡改
3. **fail-closed 异常带证据**：`IdentityResolutionException` 携带完整 `IdentityDecision`，测试断言不靠字符串（`SharedAgentSessionTest` 双重断言：异常 + 不进历史 + 不调模型）
4. **listener/sink 异常隔离**：`ExecutionVisibility.publish`、`IdentityResolver.emit`、`AmbientEngine.onNotification` 三处都是 try-catch 记日志跳过——一个坏订阅者不炸其他订阅者与看板
5. **`autoDetect` 的分隔符边界**：`"@eng-bots"` 不算 mention `eng-bot`（`text.startsWith(prefix) && isSeparator(text.charAt(prefix.length()))`）——首版被测试打穿后修正的经典 case

---

## 七、验证路径（看完代码怎么确认理解）

1. `mvn test -pl agent-channel`（78 测试全绿）
2. 重点测试对照：
   - `SharedAgentSessionCollabTest`：handoff 上下文连续（B 首轮请求实见 A 发言 + [handoff] 便签）+ 四守卫 + board 与事件流同源
   - `NoisePolicyTest`：四道闸矩阵（频控含 CRITICAL 风暴守卫 / 预算吞第 3 条 + digest 不占 / CRITICAL 豁免 / 跨午夜）
   - `AmbientEngineTest`：默认 disabled 零推送 + 坏 sink 隔离 + 定时触发
3. 跑示例看真实输出：`mvn compile exec:java -pl examples -Dexec.mainClass=...ChannelAgentExample`（先 `mvn install -DskipTests -pl agent-channel -am`）
