# Stage 12 场景与概念（频道级共享 Agent、Agent Identity 与 Ambient 模式）

> 对应阶段：Stage 12 - 频道级共享 Agent、Agent Identity 与 Ambient 模式
> 定位：场景 -> 核心概念 -> 类映射 -> 数据流（与 Stage 5/6/7/11 笔记同体系）
> 配套：架构设计见 [architecture-stage-12.md](architecture-stage-12.md)，源码可读 `agent-channel` 模块（19 类 + 2 示例）
> 状态：已实现（2026-08-22）。channel 78 测试全绿，全仓 426 零影响；ChannelAgentExample / AmbientExample 验收跑通
> 本笔记聚焦"场景和概念"，代码细节以源码为准

---

## 一、场景：从「单人助手」到「频道成员」

### 总纲：四个破裂的隐含假设

Stage 1-11 造好的 Agent 有一个隐含假设：**一次 Run 服务一个用户**。`agent.run(userInput, state)` 的 state 是一个人的对话，权限是发起人的权限，记忆是用户的记忆。这个假设在团队场景下全线破裂：

| 隐含假设 | 单人场景成立 | 频道场景如何破裂 |
|---|---|---|
| **会话假设**：一个 state 一个主人 | ✅ | Agent 是"公共的"，A 和 B 都要跟它说话，上下文必须共享 |
| **身份假设**：Agent 以用户身份干活 | ✅ | 组织里是**权限蠕变**——销售 Agent 借用销售总监的账户 = 拿着总监权限干销售的活，审计是糊涂账 |
| **触发假设**：只在被 @ 时响应 | ✅ | "每天检查工单"这类需求**没有发起人**，Agent 必须能主动工作 |
| **可见性假设**：只有发起人看得到过程 | ✅ | 团队想看"它现在干到哪了"只能轮询问 |

Stage 12 的答案：Agent 从「一用户一对话」升级为「频道级共享的团队成员」——有自己的服务身份、被多人共享、能主动工作、工作过程对团队可见。

递进叙事（接 Stage 6-11）：

```
Stage 6  让 Run 能暂停-恢复
Stage 7  让 Run 能自动恢复
Stage 8  让 Agent 能记住（含 channel scope）
Stage 9  让 Agent 能被信任
Stage 10 让 Agent 能连接
Stage 11 让 Agent 能协作
Stage 12 让 Agent 能入驻团队 -- 不再是"谁的助手"，是"频道的一员"
```

### 场景故事：`team-eng` 频道里的 `eng-bot`

```
T0 部署：eng-bot 入驻 team-eng 频道 [A,B,C]，Ambient enable
T1 A 发起：speak @eng-bot -> 身份解析通过 -> 共享 AgentState + channel 记忆 -> 看板 [task-1 running, owner=A]
T2 接力：A 下班 handoff -> AgentState 不重建 + [handoff] 便签 + 看板 owner=B -> B 说"继续刚才的调研"上下文完整
T3 看进度：C 订阅事件流，实时收到 waiting-human 推送（推不打轮询）
T4 巡更：18:00 Ambient 触发 -> PR 沉默 4 天 > 3 天阈值 -> 噪音闸通过 -> 以 eng-bot 身份推送（非触发者身份）
T5 失败分支：条件不满足->静音 / 噪音闸不过->降级 digest / B 退出频道->fail-closed 拒绝并审计 DENIED
```

---

## 二、三条边界辨析（面试高频）

```
共享 Agent vs 多开几个 Agent：
  多开 = 每人一个私有 Agent，上下文割裂，A 不知道 B 的 Agent 干了什么
  共享 = 一个 Agent 一个频道上下文，A 发起的任务 B 能接续，进度全员可见

Agent Identity vs 用户身份代理（on-behalf-of）：
  身份代理 = Agent 拿着用户 token 干活，权限 = 用户权限（审计里"谁干的"是糊涂账）
  服务身份 = Agent 有自己的 account，权限 = 显式授予的最小集合，审计里行为归属清晰

Ambient vs Cron 定时任务：
  Cron = 系统管理员配的哑触发器，跑的是固定脚本（schedule + command）
  Ambient = Agent 语义的常驻指令（trigger + condition + message + importance），
           底座可以是同一个调度器，但指令有身份、有权限、有记忆、有噪音预算
```

---

## 三、核心概念（17 个，三组）

### 第一组：频道共享与协作（Multiplayer）— `channel/` + `collab/`

| # | 概念 | 一句话 |
|---|------|--------|
| 1 | **容器哲学（D1）** | `SharedAgentSession` 不是新 Agent 类型，是包住任意 Agent 的组合容器——频道语义全在容器层，不污染 Agent 接口 |
| 2 | **频道元数据** | `ChannelContext`：channelId + 成员清单（成员资格 SSOT，不可变 record） |
| 3 | **带说话人的消息** | `ChannelMessage`：channelId / userId / text / mentionsAgent；三个工厂 `of` / `mention` / `autoDetect` |
| 4 | **speak 路由** | mention -> 身份解析(fail-closed) -> `[from userId] text` 进共享 AgentState；非 mention -> 只进频道历史不唤醒 Agent |
| 5 | **共享上下文** | A/B 交替 speak 共享**同一个** AgentState——B 的请求能看到 A 的发言（频道不是 Agent 的全部世界，人说人话时它不掺和） |
| 6 | **事件流（D6）** | `ExecutionVisibility`：八类 `VisibilityEvent` 发布/订阅，推不打轮询；listener 异常隔离 |
| 7 | **物化视图** | `TaskBoard`：订阅同一事件流是唯一写入路径，board 与外部订阅者同源（单一事实源） |
| 8 | **任务接力（D5）** | `TaskHandoff` 三件套移交：① 共享 AgentState 不重建 + `[handoff]` system 便签 ② 工作记忆零动作（scope 天然共享）③ 看板 owner 经事件变更 |

关键细节：`autoDetect` 要求 `@agentId` 后跟分隔符（空格/冒号/全角逗号）或文本结束——`"@eng-bots"` 是另一个 Agent 的 mention，不算 `eng-bot` 的（首版 startsWith 被测试抓出打穿后修正）。

### 第二组：Agent Identity（身份）— `identity/`（8 个类）

| # | 概念 | 一句话 |
|---|------|--------|
| 9 | **服务身份** | `AgentIdentity`：agentId / displayName / ownerId（对谁负责）——Agent 以**这个**身份干活，绝不以用户身份 |
| 10 | **凭证与配置** | `ServiceAccount`：accountId + 授予的 scope + 有效期窗口 + 预算占位（`UNLIMITED_BUDGET=-1` 留 Stage 18） |
| 11 | **资源范围** | `IdentityScope`：三维——capabilities / memoryScopes / dataClassifications；v1 诚实边界：只有 capabilities 走交集 |
| 12 | **三方身份** | `IdentityResolver`：channelId + userId + agentId -> 本次 Run 的有效身份（`ResolvedIdentity`） |
| 13 | **权限交集** | 有效权限 = granted scope **∩** 发起人频道角色权限——绝不是并集、绝不是用户全集 |
| 14 | **fail-closed 五条件** | UNKNOWN_AGENT / ACCOUNT_NOT_YET_VALID / ACCOUNT_EXPIRED / USER_NOT_IN_CHANNEL / EMPTY_PERMISSION_INTERSECTION，任一命中拒绝启动 Run |
| 15 | **审计双向留痕** | `IdentityDecision`：允许**和**拒绝都发决策（denied is intelligence，拒绝是安全信号不是噪音）；`IdentityResolutionException` 携带完整 decision |

核心规则（D4）：

```
一次频道内 Agent Run 的身份三元组：
  channelId（在哪）+ userId（谁发起）+ agentId（哪个 Agent）

有效权限 = IdentityScope（管理员显式授予 Agent 的最小集）
         ∩ 发起人当前频道角色权限
         -> fail-closed：任一侧无权限即拒绝（对齐 Stage 9 哲学）

审计归属：actor = "svc:{accountId}" —— "Agent 干的，代表用户 X，带能力 Y"
身份隔离：sales-bot 摸不到 git.read，eng-bot 摸不到 crm.read
```

### 第三组：Ambient（主动模式）— `ambient/`（4 个类）

| # | 概念 | 一句话 |
|---|------|--------|
| 16 | **常驻指令** | `AmbientInstruction`：sealed Trigger（`Scheduled(interval)` / `OnEvent(eventKey)`）+ Importance（INFO/WARN/CRITICAL）+ condition（值不值得说）+ message（说什么）——有判断有声音，不是哑脚本 |
| 17 | **主动运行器** | `AmbientEngine`：默认 **disabled**（register 只登记不武装，enable 才挂调度/订阅）——"未经管理员显式开启就开始推"是 bug 不是 feature |
| 18 | **噪音控制（D7）** | `NoisePolicy` 四道闸：频控（含 CRITICAL 防风暴）/ 每日实时预算（digest 不占）/ 静默窗口（CRITICAL 豁免）/ 重要度分级（INFO 永远 digest） |
| 19 | **主动推送** | `ProactiveNotification`：actor 归属 **Agent 身份**（agentId 而非事件源），NOTIFICATION_SENT 进事件流全频道可见 |

触发管线（每 firing）：

```
trigger 触发 -> condition 判定
   │  false -> 全静音（连 digest 都不进，无事发生）
   ▼  true
NoisePolicy 四道闸（按序，任一不过就静默）：
   1) 频控：同指令最小间隔内重复 -> SUPPRESS（含 CRITICAL）
   2) 分级判定意图：静默窗内 WARN->DIGEST、CRITICAL->NOTIFY；窗外 INFO->DIGEST、WARN+->NOTIFY
   3) 每日实时预算：只拦 realtime（digest 不占预算）；CRITICAL 豁免
   -> NOTIFY：以 AgentIdentity 推送 + NOTIFICATION_SENT 进事件流
   -> DIGEST：入 digest 队列，drainDigest 由装配层择机汇总
```

设计判断：**Ambient 的失败模式不是"不工作"，是"太吵被全员静音"**——一次误判毁掉整个频道的信任，所以噪音控制是一等公民，不是事后补丁。

---

## 四、概念与类的映射总表（agent-channel 模块 19 类）

| 概念 | 类 | 类型 | 关键成员 | 一句话 |
|------|----|------|---------|--------|
| 频道元数据 | `ChannelContext` | record | channelId / members / `isMember()` | 成员资格唯一权威；v1 不可变 |
| 频道消息 | `ChannelMessage` | record | channelId / userId / text / mentionsAgent / `autoDetect()` | 说话人归属 + mention 边界检测 |
| 频道容器 | `SharedAgentSession` | class | `speak()` / `handoff()` / `board()` / `visibility()` / `channelMemoryContext()` | 组合包装任意 Agent（D1） |
| 事件 | `VisibilityEvent` | record | Type 八类 / actor / target / detail | 每个里程碑一条，全频道可见 |
| 事件流 | `ExecutionVisibility` | class | `subscribe()` / `publish()` | 推不打轮询，listener 异常隔离 |
| 看板 | `TaskBoard` | class | 实现 Listener / `tasks()` / `byStatus()` / `byOwner()` | 事件流物化视图（D6） |
| 任务视图 | `ChannelTask` | record | taskId / owner / status / isTerminal() | 轻量视图，状态机复用 Stage 7 `TaskStatus` |
| 接力记录 | `TaskHandoff` | record | from / to / note / handedOffAt | 移交审计轨迹 |
| 服务身份 | `AgentIdentity` | record | agentId / displayName / ownerId | 组织级身份，非用户身份 |
| 凭证配置 | `ServiceAccount` | record | grantedScope / 有效期 / 预算占位 | 管理员配置的对象 |
| 资源范围 | `IdentityScope` | record | 三维集合 + `intersect()` | 显式授予的最小集 |
| 用户侧权限 | `ChannelRolePermissions` | 接口 | `capabilities(channelId, userId)` | null=非成员，空集=成员无角色（两种 reason 精确分层） |
| 三方解析 | `IdentityResolver` | class | `register()` / `resolve()` | 交集 + fail-closed 五条件 |
| 解析结果 | `ResolvedIdentity` | record | effectiveCapabilities / `actor()`="svc:..." | 交集后存活的能力 |
| 决策审计 | `IdentityDecision` | record | allowed / DenialReason / granted / role | 允许与拒绝都发 |
| 拒绝异常 | `IdentityResolutionException` | class | 携带完整 decision | fail-closed，测试断言不靠字符串 |
| 常驻指令 | `AmbientInstruction` | record | Trigger / Importance / condition / message | Agent 语义的"常驻待办" |
| 运行器 | `AmbientEngine` | class | `register()` / `enable()` / `fireEvent()` / `onNotification()` | 默认 disabled，安全默认值 |
| 噪音闸 | `NoisePolicy` | class | `admit()` / `drainDigest()` | 四道闸，频控防风暴 |
| 主动推送 | `ProactiveNotification` | record | actor / content / importance | 归属 Agent 身份 |

---

## 五、关键设计决策速览（D1-D8）

| # | 决策 | 一句话 |
|---|------|--------|
| D1 | **容器而非新类型** | `SharedAgentSession` 组合包 Agent（复用 Stage 11 统一抽象哲学），任何 Agent 零改动入驻 |
| D2 | **共享记忆复用 channel scope** | 不另造记忆系统——Stage 8 channel scope 挂进检索列表，治理/溯源免费搭车 |
| D3 | **Ambient 底座复用 Stage 7** | 复用**机制**（ScheduledExecutorService + 订阅/触发语义）而非绑 run 的实现（EventBroker 回调绑死 `RunManager.resume(runId)`，Ambient 指令不是 run） |
| D4 | **三方身份取交集** | 有效权限 = granted ∩ role，绝不借用用户账户；审计归属服务身份 |
| D5 | **接力三件套** | AgentState 不重建 + scope 记忆共享 + 看板 owner 变更（状态可移交才能协作） |
| D6 | **事件流非轮询** | 推比拉省，可见性与审计共用一条事实源（TaskBoard = 物化视图） |
| D7 | **噪音控制一等公民** | 四道闸按序检查；CRITICAL 双豁免（预算+静默窗），频控不豁免（重复 critical 也是风暴） |
| D8 | **v1 单 JVM** | 分布式一致性留 v2；抽象边界已为 v2 留缝（换实现不动调用方） |

---

## 六、组装阶段：复用什么（Stage 12 最大的特点）

四大底座已在前序阶段造好，新代码只写"频道语义层"：

| 能力需求 | 已有设施（阶段） | 实际兑现 |
|---|---|---|
| 频道共享记忆 | `MemoryScope.channel()` + MemoryAdmin（Stage 8） | ✅ **零新代码**，`channelMemoryContext` 工厂挂检索列表 |
| 任务状态机 | `TaskStatus`（Stage 7） | ✅ 原枚举复用，不造第二个 |
| 会话状态 | `AgentState`（Stage 2）+ 组合包装 | ✅ `speak`/`handoff` 直接操作 |
| 权限三档 / 审计 | PermissionChecker / AuditLogger（Stage 9） | ⚠️ **未对接**——身份决策走 Consumer sink 桥接，工具权限链路留装配层/后续阶段 |
| 定时唤醒 | TaskScheduler.scheduleResume（Stage 7） | ⚠️ **未复用该类**——语义绑 run，Ambient 自建 ScheduledExecutorService（同款机制） |
| 事件订阅 | EventBroker（Stage 7） | ⚠️ **未复用该类**——回调绑死 runId，自带 eventKey 注册表（同构语义） |

> 依赖方向：`agent-channel -> agent-core + agent-memory + agent-scheduler`（security 可选注入，同 orchestrator 模块边界纪律）。

---

## 七、验收示例与学习路径

### 两个验收示例（`examples/`）

| 示例 | 演示内容 |
|------|---------|
| `ChannelAgentExample` | T0-T3+T5 全景：部署 -> alice 发起（身份解析 + 频道记忆注入实证——响应引用"频道记忆：发布窗口冻结"）-> handoff 三件套 -> bob 接续（"接续 alice 的调研"上下文连续）-> carol（成员无角色）/ stranger（非成员）两种 fail-closed 拒绝 -> SUCCEEDED 终态 |
| `AmbientExample` | 六小节：默认关闭零推送 -> enable 事件触发判定 -> 频控吞重复 -> 静默窗 CRITICAL 突破 / WARN 进 digest -> 定时 INFO 巡检进 digest（digest 计入频控的实证）-> 全部推送 actor=eng-bot |

### 学习路径

1. 读本笔记掌握场景和概念（现状）
2. 精读 `architecture-stage-12.md` 的 §1（四假设破裂）、§4（D1-D8）、§6（时序）、§13（审查记录——4 处已修 bug + 4 项 v2 缺口，面试加分项）
3. 跑测试验证概念：`mvn test -pl agent-channel`（78 个），重点看 `SharedAgentSessionCollabTest`（handoff 上下文连续的结构性证明）、`AmbientEngineTest`（默认关闭怎么测）
4. 跑示例看输出：`ChannelAgentExample` / `AmbientExample`
5. 面试重点：三条边界辨析 + 权限交集语义（为什么取交集不取并集）+ D1/D4/D5/D7 四个决策 + 组装阶段的复用与诚实偏差

### 关键 v2 缺口（面试可谈的诚实边界）

- **per-user 记忆检索未实现**：ContextBuilder 是静态配置，speak 无法按说话人动态切 scope——v1 装配纪律是频道 Agent 检索列表只放 channel/agent scope，不放 user scope
- **Visibility 事件 -> AuditLogger 无桥**：事件流与 Stage 9 审计之间没有桥（身份决策有 Consumer sink，事件流没有）
- **NoisePolicy.admit 与 drainDigest 非原子**：并发触发可能双超预算/丢 digest 项
- **handoff 无认证**：任何知道 owner 名字的调用方都能以 owner 名义移交（v1 不做移交审批，已声明）
