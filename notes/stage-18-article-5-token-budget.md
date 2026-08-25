# Token 预算管理：单次 Run 和用户月度预算

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) D3（预算是事前闸）· D4（五维逃逸面）· 对应实现：`BudgetBook` / `BudgetCheck` / `CostMeter` / `PricingTable`
> 上一篇：[stage-18-article-4-model-routing.md](stage-18-article-4-model-routing.md)
> 状态：✅ Stage 18 已完成（M18.2，cost 包；全仓 1148 全绿）
> 这是 Stage 18 系列的第 5 篇：预警给人看，阻断守底线，估算闸够快，真实账够准。

---

## 1. 我今天要解决什么问题

上一篇的 `BudgetAwareRouter` 读 `remainingOf` 做降级。余量不是魔术数字，是一本账算出来的。这篇文章把账打开，先讲值班最常碰到的两维：

```text
RUN   —— 单次 Run 的经济闸：这次任务最多烧多少 token
USER  —— 用户月度配额：这个人这个月还能花多少
```

Demo 里 token 免费（五假设之二）。生产里一个没被 Stage 17 `[LIMIT]` 拦住的修复死循环、一个被注入诱导的刷量循环，都是真实账单。预算必须**事前有闸**；事后看账单已经是事故复盘。

本篇不把五维一次讲完。TENANT / CHANNEL / AGENT 与 RUN / USER 共用同一本 `BudgetBook`，频道维是下一篇的主角——它兑现的是 Stage 12 就埋下的占位。

---

## 2. 为什么会有这个认知冲突

三件事经常被揉成「做个限流」：

```text
预警   80% 发信号，Run 继续 —— 给人看，让管理员还能挽回
降级   余量低于阈值切便宜模型 —— 上一篇，自动降低成本密度
阻断   即将透支则拒绝 —— fail-closed，宁可拒绝也不赊账
```

冲突在于：如果预警也阻断，管理员只会把预警线调到 99% 消除打扰，预警形同虚设。如果阻断只发告警不拒绝，那就不是预算，是仪表盘。两个职责两种机制，混在一起两个都做不好。对照 Stage 12 `NoisePolicy`：噪音控制的敌人是打扰，预算控制的敌人是失控。

另一个冲突是「先估后记」。调用前你不知道供应商最后报多少 token；调用后你才有 `TokenUsage`。用真实值做事前闸，闸就太晚；用估算当最终账，账就不准。

---

## 3. 它解决了什么问题

`BudgetBook` 把两阶段纪律写成方法：

```text
requireBudget(dimension, key, estimatedTokens) → BudgetCheck
  事前闸，快。DENIED 看投影（used + est > limit）
  WARN 看已发生（used >= warnAtPercent）
  恰好 used + est == limit 放行（拒绝透支，不拒绝踩线）

recordUsage(dimension, key, actualTokens)
  事后诚实账。估算与实际的差值保留，不抹平
```

`BudgetCheck` 是 sealed 三态，预警与阻断在**类型层**分离：

```text
Ok()                                  健康，静默继续
Warn(percentUsed, usedTokens, limit)  越线但不阻断；同时发 BudgetAlarmEvent
Denied(usedTokens, limitTokens)       投影击穿，调用方必须拒绝
```

`Warn` 永不阻断——这是类型的承诺，不是约定。装配层看到 `Denied` 再决定抛 `BudgetExhaustedException`（示例 T4）；账本自己只返回状态，不擅自抛。路由器在余量 == 0 时自己抛，那是路由层的耗尽语义，和账本的 `Denied` 对齐但不合并。

未配置的 `(dimension, key)` 视为不限：`requireBudget` 回 `Ok`，`limitOf` 回 `-1`（对齐 Stage 12 `ServiceAccount.UNLIMITED_BUDGET`），`remainingOf` 回 `Long.MAX_VALUE`（路由算术安全，永不降级）。已配置时 `remainingOf = max(0, limit - used)`，不会回负数。`usedOf` 在从未记过账时是 0，不是 null。未配置也 `recordUsage`——仪表盘可以在封顶之前先看见消耗，先看见再决定要不要加闸。

查询三个方法的读者不同：`requireBudget` 给调用前的闸，`recordUsage` 给调用后的账，`remainingOf` 给路由器。不要用 `usedOf` 自己算一遍百分数去做路由——`BudgetSnapshot.remainingPercent()` 已经按向下取整约定算过，对账和决策应认同一套数字。

---

## 4. 核心抽象和架构

```text
cost/
  PricingTable          model → Price(input/output microUSD per 1M)
  CostMeter             TokenUsage → microUSD；缺价 IAE fail-loud
  BudgetDimension       enum：RUN / USER / TENANT / CHANNEL / AGENT
  BudgetCheck           sealed：Ok / Warn / Denied
  BudgetAlarmEvent      流向 MetricsSink.onAlarm 的预警事件
  BudgetBook            多维账本：事前闸 + 事后账
  BudgetExhaustedException  路由耗尽 / 装配层把 Denied 转异常
  ChannelQuota          下一篇：频道数字容器
```

### 4.1 五维先立界，本篇只用两维

`BudgetDimension` 的 javadoc 把每个逃逸面写死。本篇展开前两个：

```text
RUN   单次失控的经济闸
      Stage 17 [LIMIT] 是行为闸（数失败修复轮）
      RUN 预算是经济闸（数 token）
      同一条战壕，两个哨位

USER  月度个人配额
      Stage 15 CostLedger 已有这维语义
      BudgetBook 是模式通用化，不 import enterprise（D4）
```

其余三维下一篇和后续文章会回到：TENANT 防一个客户拖垮平台，CHANNEL 防 50 人分辩「我自己没用完」，AGENT 是身份隔离的经济面。

### 4.2 单价与 fail-loud

`PricingTable` 不可变，单价是整数 microUSD / 百万 token，会计路径不碰浮点。示例 T0：

```text
premium  in 3_000_000  ($3/M)    out 15_000_000 ($15/M)
cheap    in   500_000  ($0.5/M)  out  1_500_000 ($1.5/M)
```

`CostMeter.costMicros` 纯整数、四舍五入到最近的 microUSD：`(tokens * price + 500_000) / 1_000_000`。缺价抛 `IllegalArgumentException`，消息含 `"no pricing for model: " + model`——不算假账。F6 在示例里直接打这条 IAE。

纪律分界（实现记录偏差，必须写进文章以免误用）：

```text
CostMeter 被直接调用     → fail-loud，缺价即配置 bug
MetricsCollector 聚合路径 → catch IAE，warn + 该次 cost 记 0
                              （旁路不炸正在跑的 Run）
```

### 4.3 账本装配

```java
BudgetBook book = BudgetBook.builder()
        .budget(BudgetDimension.USER, "alice", 10_000)
        .budget(BudgetDimension.RUN, "run-f1", 2_000)
        .warnAtPercent(80)          // 1-99，默认 80
        .alarmSink(console)         // WARN 时 onAlarm；sink 异常吞
        .build();
```

`budget(...)` 的 limit 必须 > 0；想不限就**不要配置**，不要传 0 或 -1。告警每次越线都发，v1 无频控——`NoisePolicy` 那课是 sink 的关切，不是账本的关切。账本若自己做去重，值班反而会以为「过了 80% 只响一次，后面安全了」。每次 `requireBudget` 都重新判定、每次越线都再发，趋势才能被看见。

`warnAtPercent` 默认 80，合法区间 1–99，装配期越界 IAE。预警线是给人看的旋钮，不是运行中随请求改的参数。`alarmSink` 可空：没接线时 WARN 仍返回 `Warn`，只是没有 `BudgetAlarmEvent` 飞出去——类型状态和通知通道再次分开，没接 sink 不等于没预警。

---

## 5. 一次完整数据流

示例把 USER 维跑完三段，RUN 维留给 F1：

```text
T1 健康
  requireBudget(USER, alice, est) → Ok
  跑完 recordUsage(USER, alice, 真实 totalTokens)
  同时记 TENANT / CHANNEL / AGENT（同一笔真实用量，四维入账）

T2 预警不阻断
  先 recordUsage 把 USER 推到 8300/10000
  requireBudget(USER, alice, 600) → Warn(percentUsed=83, used=8300, limit=10000)
  控制台：[BUDGET-WARN] USER=alice at 83% of 10000
  Run 继续 —— 预警的职责是被看见

T3 路由降级（上一篇）
  余量 17% < 25%，BudgetAwareRouter 切 cheap
  账本本身仍是 Warn/Ok，不负责选模型

T4 阻断
  把 USER 用尽
  requireBudget(USER, alice, 1) → Denied
  装配层不再开新任务；经路由直调 → BudgetExhaustedException

F1 RUN 维
  已 recordUsage(RUN, "run-f1", 2000)，limit=2000
  requireBudget(RUN, "run-f1", 100) → Denied
  投影 2100 > 2000，长任务第 N 次模型调用被拦
  已耗成本照常在账上（半途而废也是成本）
```

WARN 判的是 `used`（已发生），DENIED 判的是 `used+est`（投影）。预算尾部可能保守浪费——这是永不透支的代价，javadoc 写明。

---

## 6. 最小代码或实验

`BudgetBookTest` 把边界钉死，写文章时按测试而不是按印象：

```text
未配置 → Ok，limitOf=-1，但 recordUsage 仍累加
健康 → Ok，无告警
83% → Warn，alarm 六字段齐全
投影击穿 → Denied
恰好用尽 used+est==limit → 放行
五维闸各自独立；同维不同 key 独立
warnAt=50 可提前告警
每次越线重发告警
无 sink / sink 抛异常 → 闸本身不炸
builder：limit<=0、warnAt 越界、blank key 装配期 IAE
```

换算侧 `CostMeterTest`：800+200 在 premium 价上的量纲要先写对再写数字——单价是「每百万 token 的 microUSD」，不是「每个 token 的美元」。800 × 3_000_000 / 1_000_000 + 200 × 15_000_000 / 1_000_000 = 2400 + 3000 = 5400，这是示例 T1 的数；单测里另有一条 800+200=4000 的分价夹具，读测试时不要和示例价表混用。零 token → 0。缺价 IAE 含模型名。负数 token 拒。round-half-up 有单测：0.5 microUSD 进位到 1。

溢出边界写在 javadoc 里：token 是 int，现实单价远低于 ~4.3e9 microUSD/M（约 $4,300/百万 token）时，`tokens * price` 落在 long 内。这不是「不会溢」的保证，是「在可信单价下不会溢」的诚实范围。超现实天价应在装配 `PricingTable` 时就被正价校验拦住（非正价 IAE；同模型重复定价后写覆盖）。

`MetricsSink.onAlarm` 以 default method 加入，M18.1 的 sink 实现不用改。`BudgetAlarmEvent` 字段：`dimension` / `key` / `usedTokens` / `limitTokens` / `percentUsed`。

示例 `bookUsage` 把一次 `RunMetrics.tokenUsage().totalTokens()` 写入 USER / TENANT / CHANNEL / AGENT 四维。RUN 维通常按 runId 单独设上限、单独记账，不和月度维混在一个 key 上。

---

## 7. 常见误区

1. **「到 80% 就拒」** —— 那就没有预警了。`Warn` 的类型含义是继续。拒绝只认 `Denied`。
2. **「用真实 usage 做事前闸」** —— 真实值来得太晚。闸用估算，账用真实。差值是信息，抹平是造假。
3. **「RUN 预算 = maxSteps」** —— `maxSteps` 数的是模型步，分不清健康探索和烧钱循环。`[LIMIT]` 数的是失败修复轮（行为）。RUN 预算数的是 token（经济）。三道闸，三种计数单位。
4. **「CostLedger 扩一下就行」** —— 依赖方向反了。enterprise 不该被通用观测层反向依赖。`BudgetBook` 内建五维，企业装配层可以桥两本账，模块依赖绝不反向。
5. **「缺价当 0 成本」** —— 直接问 `CostMeter` 会炸。只有聚合旁路记 0，并且打 warn。静默 0 是假账。

---

## 8. 和相邻概念的区别

```text
BudgetBook vs CostLedger（15）
  五维通用账本 vs TENANT/USER 企业域特化
  下层不认识上层；同语义可以桥，不能 import

requireBudget vs BudgetAwareRouter
  账本：Ok / Warn / Denied（检查）
  路由：premium / cheap / Exception（选择）
  降级不是账本的第三态，是另一层读 remainingOf 的动作

WARN vs DENIED
  被看见 vs 守住底线
  一个发 BudgetAlarmEvent，一个让调用方拒绝
```

配额排队、削峰、等月切重置，是产品决策，不是框架语义。v1 耗尽即拒绝。费率、税率、合同价是财务域；`CostMeter` 是治理估算（microUSD），不是账单系统。多币种、汇率是展示层的事，账本只认一种单位。

`MetricsCollector` 接上 `CostMeter` 之后，`RunMetrics.costMicros` 是 **run 内**已计价调用的和。孤儿事件（没 `beginRun` 就调了模型）可以进全局次数，但不进这一行的成本——run 汇总和 `BudgetBook` 记账职责分离。run 外烧的钱走账本维，不假装写进某个没开始的 run。这避免了「仪表盘上有一笔钱，却指不出是哪次任务」和「把别人的调用算进我的 run」两种假账。

---

## 9. 我的设计判断

最重的一条：**预警与阻断分离，必须落在类型上。**

布尔值 `allowed` 会逼着你把 80% 和 100% 挤进同一个返回。`BudgetCheck` 三态让「看见」和「拦住」无法被一个 if 写错。管理员可以忽略告警，但不能把告警理解成拒绝；调用方必须面对 `Denied`，没有第三种「差不多行」。

第二条：RUN 维是 17 的经济双胞胎。行为闸拦「修不好还在修」，经济闸拦「这次已经烧过上限」。只做其中一个，另一个逃逸面还在。

第三条：整数 microUSD 是会计纪律。浮点进账本，对账测试会变成「差不多等于」。`(tokens * price + 500_000) / 1_000_000` 写进测试，比写「约等于 0.0054 美元」值钱。单次 Run 闸和用户月度闸共用这本账、同一套三态，只是 key 和 limit 不同——不要为 Run 另写一套限流器。下一篇把同一本账的 CHANNEL 维打开，回收 Stage 12 的占位。

---

## 10. 面试表达

> 「预算是事前闸，不是事后账单。BudgetBook.requireBudget 用估算做投影：要透支回 Denied，已用过预警线回 Warn——Warn 绝不阻断。recordUsage 回填真实 token，估算和实际的差不抹平。RUN 维防单次失控，USER 维是月度个人配额；和 Stage 17 的 [LIMIT]、Stage 15 的 CostLedger 是同构不同层。单价走 CostMeter，缺价 fail-loud，不算假账。路由读 remainingOf 做降级，账本自己不选模型。」

---

## 11. 下一篇连接什么

USER 闸得住一个人，闸不住一个频道。50 人共享一个 Agent 时，每个人都能说「我自己的额度没用完」。下一篇兑现 Stage 12 留下的伏笔：`ServiceAccount.monthlyTokenBudget` 如何变成 `ChannelQuota`，以及观测层为什么坚决不 import channel。

→ [stage-18-article-6-channel-quota.md](stage-18-article-6-channel-quota.md)
