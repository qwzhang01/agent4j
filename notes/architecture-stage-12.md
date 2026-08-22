# Stage 12 架构设计：频道级共享 Agent、Agent Identity 与 Ambient 模式

> 对应阶段：Stage 12 - 频道级共享 Agent、Agent Identity 与 Ambient 模式
> 状态：✅ 已实现（2026-08-22）。M12.1-M12.5 全部完成：agent-channel 模块 20 类（identity 8 + 频道根包 3 + collab 6 + ambient 4）+ 2 验收示例，channel 模块 76 测试全绿，全仓 424 测试零影响。规划验收 5 条全过（对照见 §9）。分布式多实例 / 真实 IM 接入 / 自然语言指令 / 频道配额（归 Stage 18）留 v2
> 模块：新增 `agent-channel` Maven 模块，依赖 `agent-core`（Agent 接口）+ `agent-memory`（channel scope）+ `agent-scheduler`（EventBroker / 定时）+ `agent-security`（权限与审计，可选注入）
> 前置：Stage 1-11 已完成（348 测试全绿；Multi-Agent 编排与 A2A 进程内互通已落地）

---

## 1. 核心命题：从「单人助手」到「频道成员」

Stage 1-11 造好的 Agent 有一个隐含假设：**一次 Run 服务一个用户**。`Agent.run(userInput, state)` 的 state 是一个人的对话状态，权限是发起人的权限，记忆是用户的记忆。这个假设在团队场景下全线失效：

```text
单人 Agent 的四个隐含假设，在频道场景全部破裂：
1. 会话假设 -- 一个 state 一个主人，但频道里 Agent 是"公共的"，A 和 B 都要跟它说话
2. 身份假设 -- "Agent 以用户身份干活"在个人助手是对的，在组织里是权限蠕变
   （销售 Agent 借用销售总监的账户 = 拿着总监权限干销售的活）
3. 触发假设 -- Agent 只在被 @ 时响应，但"每天检查工单"这类需求没有"发起人"
4. 可见性假设 -- 只有发起人看得到过程，团队想看"它现在干到哪了"只能问
```

Stage 12 的答案：Agent 从「一用户一对话」升级为「频道级共享的团队成员」——有自己的服务身份、被多人共享、能主动工作、工作过程对团队可见。

一句话（接 Stage 6-11 的递进叙事）：

```text
Stage 6  让 Run 能暂停-恢复
Stage 7  让 Run 能自动恢复
Stage 8  让 Agent 能记住（含 channel scope）
Stage 9  让 Agent 能被信任
Stage 10 让 Agent 能连接
Stage 11 让 Agent 能协作
Stage 12 让 Agent 能入驻团队 -- 不再是"谁的助手"，是"频道的一员"
```

### 与相邻概念的三条边界（面试高频）

```text
共享 Agent vs 多开几个 Agent：
  多开 = 每人一个私有 Agent，上下文割裂，A 不知道 B 的 Agent 干了什么
  共享 = 一个 Agent 一个频道上下文，A 发起的任务 B 能接续，进度全员可见

Agent Identity vs 用户身份代理（on-behalf-of）：
  身份代理 = Agent 拿着用户 token 干活，权限 = 用户权限（审计里"谁干的"是糊涂账）
  服务身份 = Agent 有自己的 account，权限 = 显式授予的最小集合，审计里行为归属清晰

Ambient vs Cron 定时任务：
  Cron = 系统管理员配的哑触发器，跑的是固定脚本
  Ambient = Agent 语义的常驻指令（检查什么、什么条件算异常、找谁、怎么说），
           底座可以是同一个调度器，但指令有身份、有权限、有记忆、有噪音预算
```

---

## 2. 复用清单：Stage 12 是「组装阶段」

这个阶段最大的特点：**四大底座已在前序阶段造好，新代码只写"频道语义层"**。

| 能力需求 | 已有设施（阶段） | Stage 12 做什么 |
|---|---|---|
| 频道共享记忆 | `MemoryScope.channel(c1)` + channel scope 默认 PENDING_REVIEW + `MemoryAdmin`（Stage 8） | **直接复用，零新代码**。`SharedAgentSession` 的 ContextBuilder 把 channel scope 挂进检索列表 |
| 定时唤醒 | `TaskScheduler.scheduleResume(runId, Duration)`（Stage 7） | Ambient 的 ScheduledTask 包装它 |
| 事件订阅 | `EventBroker.subscribe / fire`（Stage 7） | Ambient 的 EventSubscription 包装它 |
| 任务状态机 | `AsyncTask` / `TaskStatus`（pending/running/waiting/done/failed，Stage 7） | TaskBoard 直接聚合 AsyncTask 视图，不另造状态机 |
| 权限三档 | `PermissionChecker` AUTO / REQUIRES_APPROVAL / DENY（Stage 9） | IdentityScope 解析出的权限集喂给它 |
| 审计 | `AuditLogger` + `AuditEvent`（Stage 9） | 所有频道行为以 AgentIdentity 记审计 |
| 会话状态移交 | `AgentState`（Stage 2）+ RunManager checkpoint（Stage 6） | TaskHandoff 移交的就是这些对象 |
| 多 Agent 编排 | `AgentSupervisor`（Stage 11） | v1 不强制；频道 Agent 内部想编排随时可挂 |

**依赖方向**：`agent-channel -> agent-core + agent-memory + agent-scheduler`（security 可选注入，同 orchestrator 的模块边界纪律）。

---

## 3. 核心抽象（14 个，三组）

### 第一组：频道共享与协作（Multiplayer）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `ChannelContext` | 数据 | 频道元数据：channelId / 成员清单 / 频道偏好 / 关联的 memory scope |
| `SharedAgentSession` | 核心 | 一个频道 Agent 的运行时容器：包 Agent + ChannelContext + 成员发言路由 + TaskBoard |
| `ChannelMessage` | 数据 | 带说话人的消息：channelId / userId / text / 是否 @agent |
| `TaskHandoff` | 协作 | 任务接力记录：taskId / fromUser / toUser / 移交说明 / 移交时间 |
| `ExecutionVisibility` | 可见性 | 执行事件流接口：订阅/发布 Agent 工作进度事件 |
| `TaskBoard` | 可见性 | 频道当前任务看板：pending / running / waiting / done 的只读视图 |

### 第二组：Agent Identity（身份）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `AgentIdentity` | 核心 | Agent 的服务身份：agentId / displayName / ownerId（对谁负责） |
| `ServiceAccount` | 数据 | 身份凭证与配置：accountId / 授予的 scope / 预算 / 有效期 |
| `IdentityScope` | 数据 | 身份能访问的资源范围：工具白名单 / memory scope 白名单 / 数据分类 |
| `IdentityResolver` | 核心 | 三方身份解析：channelId + userId + agentId -> 本次 Run 的有效身份 |

### 第三组：Ambient（主动模式）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `AmbientInstruction` | 数据 | 常驻指令：干什么 / 定时还是事件 / 触发后说什么 / 噪音等级 |
| `AmbientEngine` | 核心 | Ambient 运行器：注册指令 -> 包装 scheduler -> 条件判定 -> 产出推送 |
| `NoisePolicy` | 治理 | 噪音控制：频控 / 静默窗口 / digest 汇总 / 每日推送上限 |
| `ProactiveNotification` | 数据 | 一次主动推送：instructionId / 频道 / 内容 / 重要度 |

### 3.1 关键接口草图

```java
// ---- 频道共享会话（第一组的载体）----
public class SharedAgentSession {
    private final Agent agent;                  // 复用 agent-core，组合不继承
    private final ChannelContext channel;
    private final TaskBoard taskBoard;
    private final List<ExecutionVisibility.Listener> listeners;

    // 任何人都能对频道 Agent 说话；@mention 路由给 Agent，其余进频道历史
    public String speak(ChannelMessage message);
    // 任务接力：B 接手 A 的任务，state + 记忆 + 进度一并移交
    public String handoff(String taskId, String fromUser, String toUser, String note);
    // 团队看板：当前所有任务状态
    public TaskBoard board();
    // 订阅执行可见性事件（前端/审计用）
    void subscribe(ExecutionVisibility.Listener listener);
}

// ---- 三方身份解析（第二组的核心）----
public class IdentityResolver {
    // 有效权限 = AgentIdentity 的 IdentityScope（授予制，最小集）
    //           ∩ 发起人在频道内的角色权限
    // 绝不 = 直接取 userId 的全部权限
    public ResolvedIdentity resolve(String channelId, String userId, String agentId);
}

// ---- Ambient（第三组的核心）----
public class AmbientEngine {
    private final TaskScheduler scheduler;      // 复用 Stage 7：定时 + 事件底座
    private final NoisePolicy noise;            // 噪音治理

    // 默认 disabled；管理员显式 enable 才会注册任何触发器
    public AmbientEngine enable();
    public AmbientEngine register(AmbientInstruction instruction);
    // 条件满足 -> 判噪音预算 -> 以 AgentIdentity 说话 -> 记审计
    void onTriggered(AmbientInstruction instruction, Object payload);
}
```

---

## 4. 关键设计决策（8 个）

### D1. 频道 Agent 不是新 Agent 类型，是「Agent + 频道容器」

```text
不做：class ChannelAgent implements Agent（继承/实现新类型）
做：  SharedAgentSession 包住任意 Agent 实现（组合）

-> 复用 Stage 11 的统一抽象哲学：orchestrator 包 Agent、channel 也包 Agent
-> 任何现有 Agent（Mock / OpenAI / Anthropic / 编排型）零改动即可入驻频道
-> 频道语义（多人路由 / 接力 / 可见性）全部在容器层，不污染 Agent 接口
```

### D2. 共享记忆复用 channel scope，不另造系统

```text
SharedAgentSession 的记忆 = MemoryContextBuilder 检索列表里加 MemoryScope.channel(c1)
channel scope 的治理（PENDING_REVIEW / MemoryAdmin / 溯源）Stage 8 全部已有
-> 共享记忆的"防污染"不是 Stage 12 的新问题，是 Stage 8 已交付的能力
-> 隐私边界（哪些只对发起人可见）：task scope 默认仅发起人+接手人可检索，
   channel scope 全员可检索 -- 用 scope 区分可见性，单一机制（Stage 8 D3 重申）
```

### D3. Ambient 底座复用 Stage 7，Ambient 层只写「指令语义 + 权限门 + 噪音控制」

```text
ScheduledTask（每天 9 点检查）
  -> AmbientEngine.register(instruction)
  -> scheduler.scheduleResume(runId, Duration)      // Stage 7 已有

EventSubscription（PR 沉寂 3 天提醒）
  -> eventBroker.subscribe(trigger)                  // Stage 7 已有
  -> 外部系统 fireEvent("pr-silent:123")             // Stage 7 已有

Ambient 新增的三件事：
  1) 指令语义：AmbientInstruction 是"Agent 能理解的常驻指令"，不是哑脚本
  2) 权限门：触发后的 Run 以 AgentIdentity 执行（不是触发者身份）
  3) 噪音控制：NoisePolicy 决定"这次要不要说、说多少"
```

### D4. 身份模型：三方身份，权限取交集，绝不借用用户账户

```text
一次频道内 Agent Run 的身份三元组：
  channelId（在哪）+ userId（谁发起）+ agentId（哪个 Agent）

有效权限 = IdentityScope（管理员显式授予 Agent 的最小集）
         ∩ 发起人当前频道角色权限
         -> fail-closed：任一侧无权限即拒绝（对齐 Stage 9 哲学）

身份隔离架构：
  sales-agent 的 IdentityScope: [crm.read, calendar.read]
  eng-agent  的 IdentityScope: [git.read, ci.trigger]
  -> 一个 Agent 的记忆和权限天然不泄漏给另一个（scope 白名单 + memory namespace）
```

### D5. TaskHandoff = 状态 + 记忆 + 进度三件套移交，不丢上下文

```text
A 发起任务 -> A 下班 -> B 接手：
  1) AgentState（对话历史）移交 -- 不新建 state
  2) task scope 记忆移交 -- 工作记忆连续
  3) TaskBoard 条目 owner 变更 + TaskHandoff 记录（who/when/why）

-> 底座是 Stage 6 的洞察：状态可序列化才能恢复；这里是同一命题的团队版：
   状态可移交才能协作
-> v1 不做"移交审批"（谁都能接），权限语义留给 v2
```

### D6. ExecutionVisibility = 事件流，不是轮询接口

```text
Agent 干活的每个里程碑 -> 发布 VisibilityEvent（task-started / step-done /
                        waiting-human / task-done / notification-sent）
频道成员（人或前端）-> subscribe 收推送；TaskBoard 是同一事件流的物化视图

-> 不做"团队成员轮询 GET /progress"：推比拉省，且事件流可以直接进
   AuditLogger（可见性和审计共用一条事实源）
```

### D7. 噪音控制是 Ambient 的一等公民，不是事后补丁

```text
NoisePolicy 四道闸（按序检查，任一不过就静默）：
  1) 频控：同一 instruction 的最小触发间隔（如 1 小时内不重复推）
  2) 静默窗口：频道免打扰时段（如 22:00-08:00 事件缓存到 digest）
  3) 每日预算：每频道每日主动推送上限（默认 5 条）
  4) 重要度分级：INFO 默认进 digest 汇总，WARN+ 实时推

-> 设计判断：Ambient 的失败模式不是"不工作"，是"太吵被全员静音"--
   一次误判毁掉整个频道的信任
```

### D8. v1 单 JVM 频道 Agent，分布式一致性留 v2

```text
v1：一个 SharedAgentSession 一个 JVM，EventBroker 进程内（Stage 7 D3 已定）
v2（如需要）：多实例 = 频道状态外部化（Redis/DB）+ 分布式事件 + 抢占锁

理由：v1 的教学价值在"共享/身份/主动"三个语义，不在分布式。
     EventBroker/TaskScheduler 的抽象边界已为 v2 留缝（换实现不动调用方）。
```

---

## 5. 分层架构图

```text
┌────────────────────────────────────────────────────────────────┐
│ examples: ChannelAgentExample / AmbientExample                  │
└───────────────────────────┬────────────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────────────┐
│ agent-channel（Stage 12 新增）                                   │
│                                                                │
│  SharedAgentSession ── speak / handoff / board                  │
│       │            └── ChannelContext / ChannelMessage          │
│       ├── TaskBoard + ExecutionVisibility（事件流，D6）           │
│       │       └── TaskHandoff（D5：state+记忆+进度移交）           │
│       ├── IdentityResolver ── AgentIdentity / ServiceAccount    │
│       │                     / IdentityScope（D4：交集+fail-closed）│
│       └── AmbientEngine ── AmbientInstruction（D3：语义层）       │
│                ├── NoisePolicy（D7：四道闸）                      │
│                └── ProactiveNotification                        │
└──────┬──────────────────┬─────────────────────┬────────────────┘
       │ 依赖              │ 依赖                 │ 依赖
┌──────▼───────┐  ┌───────▼────────┐  ┌─────────▼──────────────┐
│ agent-core   │  │ agent-memory   │  │ agent-scheduler        │
│ Agent/State  │  │ channel scope  │  │ TaskScheduler(定时)     │
│ （D1：被包装）│  │ MemoryAdmin(8) │  │ EventBroker(事件, 7)    │
└──────────────┘  └────────────────┘  │ AsyncTask/TaskStatus(7)│
                                      └────────────────────────┘
           （agent-security 可选注入：PermissionChecker / AuditLogger，
             与 orchestrator 同款模块边界纪律）
```

依赖链：`agent-channel -> agent-core + agent-memory + agent-scheduler`（+ security 可选）。

---

## 6. 完整时序：一次「A 发起、B 接力、Ambient 巡更」

```text
T0: 部署（管理员）
    identity = AgentIdentity("eng-bot", scope=[git.read, ci.read])
    session  = SharedAgentSession(agent, ChannelContext("team-eng", [A, B, C]))
    ambient  = AmbientEngine(session, scheduler).enable()   // 默认 disabled
    ambient.register(AmbientInstruction(
        "每天 18:00 检查沉默 PR，超 3 天 @owner 提醒", noise=WARN))

T1: A 发起任务（多人共享，D1/D2）
    session.speak(ChannelMessage("team-eng", "A", "@eng-bot 调研 X 库的迁移方案"))
    -> IdentityResolver.resolve("team-eng", "A", "eng-bot")   // D4 三方身份
    -> 记忆检索：user:a1 + channel:team-eng（共享记忆 channel scope）
    -> agent.run(...) + ExecutionVisibility 发布 task-started
    -> TaskBoard: [task-1 running, owner=A]

T2: A 下班，B 接力（D5 三件套移交）
    session.handoff("task-1", "A", "B", "迁移方案初稿在 task 记忆里")
    -> AgentState 不重建 + task:r42 scope 记忆连续 + board owner=B
    -> B speak("@eng-bot 继续刚才的调研") 时上下文完整

T3: 团队看进度（D6 事件流）
    C: session.board() -> [task-1 running, waiting-human on "选方案", ...]
    C subscribe 了 ExecutionVisibility -> 实时收到 waiting-human 推送

T4: Ambient 巡更（D3/D7）
    18:00 scheduler 定时唤醒 -> AmbientEngine.onTriggered
    -> 条件判定：PR #77 已沉默 4 天 > 3 天阈值
    -> NoisePolicy：非静默窗口 / 频控通过 / WARN 级实时推 / 今日预算 2/5
    -> 以 AgentIdentity（非触发者身份）发 ProactiveNotification
    -> 审计：AuditEvent(actor=eng-bot/service, action=notify, channel=team-eng)

T5: 失败分支
    - Ambient 条件不满足 -> 静默（连 INFO digest 都不进，无事发生）
    - 噪音闸不过 -> 降级进 digest，次日 9:00 汇总一条
    - 权限解析失败（B 已退频道）-> fail-closed 拒绝，审计 DENIED
```

---

## 7. 模块结构

```text
agent-channel/                                # 新增 Maven 模块
└── src/main/java/io/github/qwzhang01/agent/channel/
    ├── ChannelContext.java                   # 频道元数据（成员/偏好/scope）
    ├── ChannelMessage.java                   # 带说话人的消息 record
    ├── SharedAgentSession.java               # 频道 Agent 容器（D1 核心）
    ├── identity/
    │   ├── AgentIdentity.java                # 服务身份
    │   ├── ServiceAccount.java               # 身份凭证配置
    │   ├── IdentityScope.java                # 权限范围（白名单集）
    │   ├── IdentityResolver.java             # 三方身份解析（D4 交集）
    │   └── ResolvedIdentity.java             # 解析结果（有效权限+归属）
    ├── collab/
    │   ├── TaskHandoff.java                  # 接力记录 record
    │   ├── ExecutionVisibility.java          # 事件流（发布/订阅）
    │   └── TaskBoard.java                    # 任务看板只读视图
    └── ambient/
        ├── AmbientInstruction.java           # 常驻指令 record
        ├── AmbientEngine.java                # 运行器（包装 scheduler，D3）
        ├── NoisePolicy.java                  # 四道闸（D7）
        └── ProactiveNotification.java        # 推送 record

examples/（新增 2 个）
├── ChannelAgentExample.java                  # 验收：共享 + 接力 + 身份 + 看板
└── AmbientExample.java                       # 验收：定时 + 事件 + 噪音闸 + 推送
```

父 POM `<modules>` 增补 `agent-channel`；不改动任何存量模块代码。

---

## 8. 实现里程碑（5 个，节奏对齐 Stage 11）

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M12.1 | 身份层 ✅ | `AgentIdentity` / `ServiceAccount` / `IdentityScope` / `IdentityResolver`（交集 + fail-closed）+ 单测 | ✅ 三方解析正确（resolver 3 用例）；权限交集语义（union 反证）；越权拒绝并产生 DENIED 审计（五条件 + 双向留痕 + 坏 sink 不改语义） |
| M12.2 | 共享会话 ✅ | `ChannelContext` / `ChannelMessage` / `SharedAgentSession`（speak 路由 + channel scope 记忆挂接）+ 单测 | ✅ 多用户交替 speak 共享一个 Agent 上下文（RecordingModelClient 捕获模型实见消息证明）；记忆来自 channel scope（注入 + 跨频道隔离双证） |
| M12.3 | 协作与可见 ✅ | `TaskHandoff`（三件套移交）+ `ExecutionVisibility` 事件流 + `TaskBoard` + 单测 | ✅ A 发起 B 接力上下文完整（B 的请求实见 A 的发言 + [handoff] 便签）；看板 owner 变更 + handoff 记录可查；事件按时序到达且 board 与事件流同源 |
| M12.4 | Ambient ✅ | `AmbientInstruction` / `AmbientEngine`（复用调度机制，见 D3 偏差记录）+ `NoisePolicy` 四道闸 + 单测 | ✅ 定时触发判定推送（150ms 间隔 ≥2 次）；事件触发判定推送；频控/静默窗口/预算/digest 各闸生效；默认 disabled 零调度行为 |
| M12.5 | 验收示例 + 收口 ✅ | `ChannelAgentExample` + `AmbientExample` + README/笔记更新 | ✅ 规划验收 5 条全过（§9 逐条对照）；全仓 424 测试全绿 |

依赖：M12.2 依赖 M12.1（speak 要过身份解析）；M12.3 依赖 M12.2；M12.4 依赖 M12.2（Ambient 产出要进频道）；M12.5 收口。M12.1 与 M12.4 的 NoisePolicy 可并行先行。

### M12.1 实现记录（2026-08-22）

新增 `agent-channel` Maven 模块（父 POM 注册 + dependencyManagement），identity 包 8 类：

- `AgentIdentity`：agentId / displayName / ownerId（对谁负责）
- `IdentityScope`：三维资源范围（capabilities / memoryScopes / dataClassifications），`intersect()` 元素级交集；字符串形式与 agent-memory `MemoryScope` 格式兼容，身份层保持零内部依赖
- `ServiceAccount`：accountId + grantedScope + 有效期窗口（validUntil 独占边界）+ 预算占位（`UNLIMITED_BUDGET=-1`，Stage 18 接线）
- `ChannelRolePermissions`：函数式接口（用户侧输入；null=非成员，空集=成员无角色——两种 reason 语义精确区分）
- `IdentityDecision` + `DenialReason` 五条件枚举：决策审计 record，**允许与拒绝都发**（denied is intelligence，对齐 Stage 9 D6），携带双方权限集让"为什么拒"可审计
- `IdentityResolutionException`：fail-closed 拒绝异常，携带完整 decision（测试断言不靠字符串匹配）
- `ResolvedIdentity`：`actor()="svc:{accountId}"`（审计归属服务身份而非用户）；`allows()` 只认交集存活的能力
- `IdentityResolver`：register（重复注册 fail-loud）+ resolve（有效期 → 成员 → 交集三段校验，任一失败 deny+throw）；审计 sink 为 `Consumer<IdentityDecision>` 注入（模块不依赖 agent-security，同 orchestrator D5 纪律）；坏 sink 只记日志不改解析语义

测试 25 个全绿：IdentityScopeTest 8（防御性拷贝/谓词/三维交集/不相交为空）+ ServiceAccountTest 5（窗口倒挂拒绝/开区间/独占边界/预算占位）+ IdentityResolverTest 12（交集非并集/身份隔离双向断言（sales-bot 摸不到 git.read，eng-bot 摸不到 crm.read）/五拒绝各一例/审计双向留痕/坏 sink/重复注册/NPE 快速失败）。

v1 诚实边界（javadoc 已写明）：memoryScopes 与 dataClassifications 仅授予侧生效，只有 capabilities 走交集（用户侧暂无对应输入）；跨 Agent 记忆隔离靠 scope 白名单字符串精确匹配。

### M12.2 实现记录（2026-08-22）

频道根包 3 类（`io.github.qwzhang01.agent.channel`）：

- `ChannelContext`：channelId + members record，`isMember()` 是成员资格唯一权威；v1 不可变（成员变更重建），动态成员 v2
- `ChannelMessage`：channelId / userId / text / mentionsAgent / timestamp record。三个工厂（`of` plain / `mention` 显式 / `autoDetect` 智能检测）；`autoDetect` 要求 `@agentId` 后跟分隔符（空格/冒号/全角逗号/全角空格）或文本结束——**首版 startsWith 被 "@eng-bots" 打穿**，测试抓出后修正为分隔符边界匹配；`textWithoutMention(agentId)` 剥前缀与一个分隔符
- `SharedAgentSession`：D1 容器。构造时组合「成员闸门 + 角色映射」喂给 M12.1 的 IdentityResolver（非成员 → null → USER_NOT_IN_CHANNEL；成员无角色 → 空集 → EMPTY_PERMISSION_INTERSECTION，两种拒绝语义精确分层）。speak 流程：channelId 一致性 fail-fast → 身份解析 fail-closed（**mention 与 plain 都过闸门**，陌生人连进频道历史都不行）→ 历史记录 → mention 才路由给 Agent（`[from userId] text` 进共享 AgentState），非 mention 返回 null 不唤醒 Agent（频道里人说人话不是 Agent 的事）。`channelMemoryContext` 静态工厂 = D2 落地：channel scope 挂进 Stage 8 `MemoryContextBuilder` 检索列表（治理/溯源免费搭车），不是新记忆系统

测试 16 个新增全绿：ChannelMessageTest 7（工厂 flag/timestamp、autoDetect 前缀+边界（"@eng-bots 不算"、子串不算）、分隔符剥离矩阵（空格/冒号/全角逗号/无分隔符）、他 Agent mention 不剥、防御性拷贝）+ SharedAgentSessionTest 9（**多用户共享上下文**：A/B 交替 speak 断言共享 state 两条 `[from]` USER 消息 + 第二次模型请求含 A 的发言（RecordingModelClient 装饰器捕获模型实见消息，结构性证明上下文连续）；plain 不唤醒 Agent 且请求计数为零；mention 剥前缀+加归属；**非成员 fail-closed**：异常+不进历史+不调模型三重断言；成员无角色精确 EMPTY_INTERSECTION；跨频道消息 IAE；history 全记录；**channel 记忆注入**：channel:team-eng 的 FACT 记忆经 [Known memories] 到达模型；**跨频道隔离**：channel:sales 的记忆完全不泄漏）。

RecordingModelClient（测试内装饰器）：委托 scripted MockModelClient 并捕获每次请求的 messages——MockModelClient 不暴露请求，要证明"模型看到了什么"必须在 ModelClient 边界截获。

### M12.3 实现记录（2026-08-22）

collab 子包 5 类 + SharedAgentSession 扩展：

- `VisibilityEvent`：八类里程碑事件 record（TASK_STARTED/PROGRESS/WAITING_HUMAN/RESUMED/COMPLETED/FAILED/HANDOFF/AGENT_REPLIED），带 actor/target 结构化归属（handoff 的 target=toUser）
- `ExecutionVisibility`：发布/订阅总线（D6 推不打轮询）。listener 异常隔离（抛异常记日志跳过，一个坏订阅者不炸其他订阅者与 board——同 IdentityResolver audit sink 纪律）；不重放历史（board 持有物化视图供后来者）
- `ChannelTask`：看板任务轻量视图（taskId/description/owner/status/createdAt/updatedAt），**状态机复用 Stage 7 TaskStatus 原枚举**（PENDING/RUNNING/WAITING_*/终态 + isTerminal）——一个状态词汇表跨框架，不造第二个枚举
- `TaskBoard`：事件流物化视图（D6 核心落地）。实现 `ExecutionVisibility.Listener`，**事件是唯一写入路径**：TASK_STARTED 建 RUNNING 任务、WAITING_HUMAN/RESUMED/COMPLETED/FAILED 转状态、TASK_HANDOFF 转 owner、AGENT_REPLIED 忽略（对话级）。未知任务事件 debug 忽略（投影不是执法点，校验在 session 层）
- `TaskHandoff`：接力审计 record（taskId/from/to/note/handedOffAt）
- SharedAgentSession 扩展：构造时 board 订阅事件流；任务 API（startTask/waitingHuman/resumeTask/completeTask/failTask，每个动作发布事件，startTask 校验 owner 成员资格）；**handoff 三件套**（D5）：① 共享 AgentState 不重建 + 注入 `[handoff] task X owner A -> B | note` system 便签（模型知道接力棒换手）② 工作记忆零动作（channel/task scope 天然共享，文档写明）③ board owner 经 TASK_HANDOFF 事件变更。handoff 四守卫全 IAE fail-fast：未知任务 / from 非当前 owner（不能移交别人的任务）/ to 非成员 / 终态任务。speak 回复后发布 AGENT_REPLIED（团队实时看见 Agent 工作）

测试 16 新增全绿：TaskBoardTest 6（纯投影：TASK_STARTED 建任务 / 生命周期状态流转 / HANDOFF 转 owner / AGENT_REPLIED 不碰 board / 未知任务不炸 / 读视图按创建序）+ SharedAgentSessionCollabTest 10（**handoff 上下文连续**：B 的首轮请求实见 A 的发言 + [handoff] 便签 + 自己的发言三段；board owner 变更 + handoffs() 审计记录；**四守卫**各一例；任务生命周期驱动 board；未知任务 API fail-fast；**事件按发布时序精确到达**（TASK_STARTED → AGENT_REPLIED → TASK_HANDOFF → AGENT_REPLIED）；**board 与外部订阅者同源**（3 个 TASK_STARTED 事件 == board.size）；坏订阅者隔离（healthy 订阅者与 board 均收到））。

### M12.4 实现记录（2026-08-22）

ambient 子包 4 类 + VisibilityEvent 增补：

- `AmbientInstruction`：常驻指令 record。sealed Trigger（SCHEDULED(interval) / OnEvent(eventKey)）+ Importance（INFO/WARN/CRITICAL）+ condition（Predicate：值不值得说）+ message（Function：说什么）。**Agent 语义不是哑脚本**：cron 有 schedule 和 command，AmbientInstruction 有触发、判断和声音。v1 诚实边界：condition/message 是 Java 函数（自然语言指令解析归 Stage 13 声明式层）
- `NoisePolicy`：四道闸。**首版闸序被测试抓出真 bug**：预算闸放在分级判定之前，预算耗尽后连 INFO 的 DIGEST 都被吞——与"digest 不占预算"语义矛盾。修正闸序：频控（全级别含 CRITICAL 防风暴）→ 分级判定意图（静默窗口：CRITICAL→NOTIFY 其余→DIGEST；窗口外：INFO→DIGEST、WARN/CRITICAL→NOTIFY）→ 预算只拦 realtime 意图。**CRITICAL 双豁免**（预算+静默窗口：凌晨服务挂了值得叫醒人），但频控不豁免（重复 critical 也是风暴）。digest 计入频控不计入预算（digest 刷屏也是刷屏，但不吃实时额度）。drainDigest() 由装配层择机（如次日 9:00 汇总）调用
- `ProactiveNotification`：推送 record，actor 归属 Agent 身份
- `AmbientEngine`：**默认 disabled**（register 只登记不武装，enable 才挂调度/挂订阅——"未经管理员显式开启就开始推"是 bug 不是 feature）。管线：trigger → condition（不满足→全静音连 digest 都不进；condition 抛异常→视为不满足而非炸引擎）→ NoisePolicy → NOTIFY 则以 AgentIdentity 推送（sinks 异常隔离 + NOTIFICATION_SENT 进 session 事件流全频道可见）；DIGEST 入队。定时用 ScheduledExecutorService（可注入），事件自带 eventKey 注册表 + fireEvent 外部入口
- **D3 复用诚实偏差**（面试素材）：Stage 7 EventBroker 的 fire 回调绑死 `RunManager.resume(runId)`——Ambient 指令不是 run，直接复用需要伪造 runId 且 resume 必失败。v1 复用**机制**（调度执行器 + 订阅/触发语义同构）而非绑 run 的实现；EventBroker 支持非 run 订阅者后 v2 统一
- VisibilityEvent.Type 增 NOTIFICATION_SENT（TaskBoard 忽略，对话级）；JDK 17 的 switch 模式匹配是预览功能，arm() 用 if-instanceof

测试 19 新增全绿：NoisePolicyTest 8（频控间隔内全级别吞含 CRITICAL 风暴守卫/预算第 3 条吞+digest 不占预算+CRITICAL 豁免+跨天重置/静默窗 WARN 转 digest CRITICAL 照推/INFO 永远 digest/跨午夜窗口数学/digest 队列 drain 清空/零预算拒绝）+ AmbientEngineTest 11（**默认 disabled**：register 后 fireEvent 零推送零 digest/事件触发推送且 actor=agentid/条件不满足全静音/无订阅者 no-op/**INFO 引擎级走 digest** sink 零调用/**引擎级频控** 1h 间隔第二次吞/**定时触发** 150ms×600ms≥2 次/NOTIFICATION_SENT 进事件流/坏 sink 隔离/重复注册 IAE/enable 幂等+shutdown）。engine 测试用退化静默窗（00:00-00:00=永不静默）保证任何墙钟时间判定确定。

---

## 9. 验收标准（对齐 18 周规划）

```text
1. 在一个频道中部署一个共享 Agent，多人可见其工作进度
   ✅ M12.2 + M12.3 + M12.5（ChannelAgentExample：visibility 流全员订阅 + TaskBoard）

2. A 发起任务后，B 可以接续并看到完整上下文
   ✅ M12.3 + M12.5（handoff 三件套；示例中 bob 的回复"接续 alice 的调研"实证上下文连续）

3. Agent 使用独立服务身份执行操作，而非借用用户账户
   ✅ M12.1 + M12.5（示例日志 actor=svc:svc-eng-bot-01；carol/stranger 两种拒绝演示）

4. Agent 能定时检查某状态并在条件满足时主动推送
   ✅ M12.4 + M12.5（AmbientExample：事件触发条件判定 + 定时巡检 + 四道闸全部演示）

5. 任务状态对所有频道成员可见
   ✅ M12.3 + M12.5（示例 final board + byStatus 查询）
```

### M12.5 实现记录（2026-08-22）

- `ChannelAgentExample`：架构笔记 §6 时序 T0-T3 + T5 的可运行版。三个成员共享一个 eng-bot；channel 记忆注入在响应里直接可见（scripted 回复主动引用"频道记忆：本周发布窗口冻结"——channelMemoryContext 检索列表生效的活证据）；handoff 后 bob 的回复以"接续 alice 的调研"开场（共享 state + [handoff] 便签的模型可见性）；carol（成员无角色）与 stranger（非成员）拿到两种不同 reason 的 fail-closed 拒绝
- `AmbientExample`：六小节演示。默认关闭（fireEvent 零推送）→ enable 后事件触发（条件判定 4 天沉默 ≥3 才推，1 天沉默全静音）→ 频控吞间隔内重复 → 静默窗（策略窗口动态包住当前时刻保证任意时间运行都在"夜里"：CRITICAL 实时突破、WARN 进 digest）→ 定时 INFO 巡检（300ms 间隔 sleep 1.1s，**digest=1 正是"digest 计入频控"设计语义的实证**——1h 频控内后续巡检全部静音）→ 收尾归属打印（actor=eng-bot 非事件源）
- examples POM 增补 agent-channel 依赖；`mvn install -DskipTests -pl agent-channel -am` 后 exec:java 跑通

---

## 10. 测试策略

- **身份解析**：scope 交集正确；用户无该权限时 fail-closed；审计事件 actor 是 service account 而非 userId
- **共享会话**：A、B 交替 speak，Agent 能引用对方说过的话（channel 记忆生效）；@mention 路由、非 mention 只进历史
- **任务接力**：handoff 后 B 的首轮对话能引用 A 阶段的结论；TaskBoard owner 变更；handoff 记录可查
- **可见性**：订阅者按序收到 task-started / waiting-human / task-done；TaskBoard 与事件流一致（同一事实源）
- **Ambient 定时**：注册"每 N 秒检查"指令 -> 触发 -> 条件满足 -> 推送；条件不满足 -> 静默无副作用
- **Ambient 事件**：fireEvent -> 订阅指令被唤醒 -> 判定 -> 推送；超时未触发不残留订阅
- **噪音闸**：频控（间隔内第二次触发被吞）；静默窗口（进 digest 不实时推）；每日预算（第 6 条被吞）；WARN 级绕过 digest 实时推
- **默认关闭**：不 enable 时 register 不产生任何调度行为（安全默认值）
- **向后兼容**：只新增模块，348 存量测试零影响

---

## 11. 文章规划（规划 10 篇 -> 优先 6 篇）

| 文章（规划原文） | 写作时机 | 素材来源 |
|---|---|---|
| 《从一用户一对话到频道级共享 Agent》 | M12.2 | §1 四个隐含假设破裂 + D1 容器哲学 |
| 《Agent Identity：Agent 为什么需要独立服务身份》 | M12.1 | D4 三方身份/交集/fail-closed + 审计归属 |
| 《TaskHandoff：任务在用户间传递，上下文不丢失》 | M12.3 | D5 三件套移交（Stage 6 命题的团队版） |
| 《Ambient 模式：Agent 主动监控和定时运行》 | M12.4 | D3 复用 Stage 7 底座（组装阶段的最佳例证） |
| 《主动行为的噪音控制：Ambient 不能变成打扰》 | M12.4 | D7 四道闸 + "失败模式是被静音"的设计判断 |
| 《ExecutionVisibility：让团队看见 Agent 在干什么》 | M12.3 | D6 事件流 + 可见性与审计共用事实源 |
| 备选：《身份隔离架构》《Standing Instructions 设计》《Claude Tag 架构拆解》《SharedAgentSession 内幕》 | 收口后按数据挑选 | 全阶段素材 |

**系列衔接**：文章 1 显式引用 Stage 11 文章 6 的"人肉 Supervisor"结论（多开窗口反模式在频道场景的变体）；文章 5 与 Stage 8 记忆污染防御呼应（信任是 Agent 产品的第一约束）。

---

## 12. 本阶段不做（范围控制）

- **多实例/分布式频道 Agent** -- v1 单 JVM（D8），状态外部化与分布式事件 v2
- **真实 IM 接入（Slack/飞书/钉钉 Webhook）** -- v1 频道是进程内抽象 + console 输出；IM 适配器是薄壳，留产品化阶段
- **移交审批流（谁能接任务的权限语义）** -- v1 任何人可接；接 Stage 9 审批语义 v2 再议
- **频道级 Token 配额** -- 规划归 Stage 18（TeamBudget/ChannelQuota），本阶段只在 ServiceAccount 里预留预算字段
- **Ambient 指令的自然语言配置（"帮我盯着 X"自动转指令）** -- v1 指令是代码/配置构造的 record；LLM 理解指令是 Stage 13 声明式层的题
- **细粒度隐私字段（消息级 ACL）** -- v1 隐私边界用 scope 粒度（task vs channel）；消息级 ACL v2
- **频道历史持久化与回放 UI** -- 事件流已可审计可重放，UI 不做
