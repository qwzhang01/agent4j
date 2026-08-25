# 降级策略：预算耗尽时怎么办

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) §4 D3 / D6 · 对应实现：`BudgetAwareRouter` / `RoutingModelClient` / `BudgetExhaustedException` · 全剧本：[ObservabilityExample.java](../examples/src/main/java/io/github/qwzhang01/agent/examples/ObservabilityExample.java) T2–T4 + F3
> 上一篇：[stage-18-article-6-channel-quota.md](stage-18-article-6-channel-quota.md)
> 状态：✅ Stage 18 已完成（M18.3 路由三档 + M18.5 示例实跑；全仓 1148 全绿）
> 这是 Stage 18 系列的第 7 篇。上一篇把 `CHANNEL` 维的数字注入讲清楚了；本篇回答配额快用完、用完时 Runtime 该怎么活下去。

---

## 1. 我今天要解决什么问题

上一篇把预算铺成了五维账本：`RUN` / `USER` / `TENANT` / `CHANNEL` / `AGENT`。账本只能回答「还剩多少」。生产里真正要命的是下一问：

> 余量吃紧、乃至归零时，Agent 是继续烧贵模型、直接拒绝，还是换一种活法？

三个选项都看起来合理，也都能在一个月内把账单或 SLA 烧穿。这篇的结论是：**降级不是拒绝服务，是降低剩余服务的成本密度；耗尽时切便宜也救不了，必须诚实拒绝。** 这两句话分别落在 `BudgetAwareRouter` 的中间档和 `BudgetExhaustedException` 上。

---

## 2. 为什么会有这个认知冲突

Stage 1 已经有一条降级链：`FallbackModelClient`——主模型抛错才切备选。它管的是**可用性**（挂了换谁），是被动、事后、异常路径。预算吃紧不是模型挂了，是钱快没了。把「余量 18%」当成「premium 故障」去走 Fallback，会得到一个看起来能跑、月底对不上账的系统。

Demo 里还有一个更隐蔽的假设：**token 永远够用，所以「选谁」可以写死。** 生产里这个假设在三个方向同时破裂：

```text
1. 余量健康时继续走 premium —— 用户付了钱，不该提前缩水
2. 余量低于阈值时仍走 premium —— 尾部几次贵调用就能把月度配额打穿
3. 余量归零后切 cheap —— 任何一次调用都是透支，便宜模型也要花钱
```

第 3 条是最容易写错的。工程师的直觉是「没额度了用便宜的顶一下」。但 cheap 不是免费：`PricingTable` 里它只是单价更低。余量已经是 0 时，再调一次就是透支。`BudgetAwareRouter` 在这一档不返回 `RouteDecision`，直接抛 `BudgetExhaustedException`。

和上一篇的频道配额叠在一起看：50 人共享一份 `CHANNEL` 配额时，每个人都能说「我自己的 USER 额度没用完」。降级策略必须读**被路由的那一维余量**（示例里是 `USER=alice`），而不是「系统里还有别的维度没满」。`RoutingModelClient` 的快照供应商每次调用只取一个 `BudgetSnapshot`——选哪一维是装配决策，路由器本身不认识频道、租户或用户。

还有一种错法更隐蔽：未配置预算时硬造一个「余量 0」快照。`BudgetSnapshot.unlimited()` 的 `limitTokens` 是 `-1`，`remainingPercent()` 恒为 100，`BudgetAwareRouter` 永不降级。这和 Stage 12 `ServiceAccount.UNLIMITED_BUDGET = -1`、`BudgetBook.limitOf` 未配置返回 `-1` 是同一套哨兵。不限不是「余量很大」，是「没有上限这个概念」。

---

## 3. 它解决了什么问题

预算闸的三段式（蓝图 D3）和路由策略（蓝图 D6）在这里交汇：

```text
80%   预警（WARN）  —— BudgetCheck.Warn + BudgetAlarmEvent 进 sink
                      run 继续。预警的职责是「被看见」
阈值  降级（DEGRADE）—— BudgetAwareRouter 切 cheap
                      不是拒绝服务，是降低单次成本密度
100%  阻断（DENIED） —— BudgetExhaustedException / BudgetCheck.Denied
                      fail-closed，宁可拒绝也不赊账
```

三段各有一个读者。预警给管理员（还能挽回时看见趋势）；降级给账本（自动把尾部流量换成便宜模型）；阻断给合同（配额是硬约束，不是建议）。混在一起，两个都做不好——预警若阻断，管理员会把预警线调到 99%；阻断若只告警，预算就不是闸。

`BudgetCheck` 把前两段和第三段拆成类型：`Ok` / `Warn` / `Denied`。`BudgetAwareRouter` 把后两段做成路由三档。装配层把 `Denied` 翻成同一种异常。预警、降级、阻断从此不是三个 if，是三条职责不同的机制。

两套词不要混用。`BudgetBook.requireBudget` 返回的是检查结果，不抛；抛的是装配层或路由器。`Warn` 带的是**已经用掉**的百分比（不是预测），`Denied` 看的是 **used + 估算** 会不会击穿。所以会出现「83% 已经 Warn，但这一单估算还装得下，run 继续」——T2 就是这一态。路由器不管估算，只看快照上的余量整数百分比：账本说还能花，路由才决定花在谁身上。

---

## 4. 核心抽象和架构

降级这条链只动四个类型，全部在 `agent-observability` 的 routing / cost 包：

```text
ModelRouter.route(request, BudgetSnapshot) → RouteDecision
        │
        ├── BudgetAwareRouter（v1 默认策略：只看余量）
        │     健康 >= 阈值     → premium + reason
        │     0 < 余量% < 阈值 → cheap   + reason（含百分数）
        │     余量 == 0        → BudgetExhaustedException
        │
RoutingModelClient（装饰器）
        每次调用前取一次 BudgetSnapshot
        按 RouteDecision.modelId 转发候选
        异常原样上抛（路由是主路径，不是旁路）
```

`BudgetSnapshot` 是纯数字：`remainingTokens` + `limitTokens`。路由器看不见 `BudgetBook`，也看不见 `ServiceAccount`——上一篇的「数字注入而非身份依赖」在这里变成类型边界。测试用 `BudgetSnapshot.of(1800, 10000)` 就能演完三档，不必构造频道或租户。

`RouteDecision` 的 `reason` 在构造期强制非空。blank reason 直接 `IllegalArgumentException`。这不是 javadoc 惯例：月底「30% 流量走了 cheap」必须能按 reason 分组，没有 reason 的切换是一场不可复现的玄学事故。

和 Stage 1 的组合形态写在 `RoutingModelClient` 的 javadoc 里：

```text
Routing(Fallback(premium, cheap))
  外层按预算选人（经济性，事前，常态路径）
  内层挂了兜底（可用性，事后，异常路径）
```

便宜模型也会挂。`ObservabilityExample` 的 F3 用空 scripted `MockModelClient` 让 cheap 天然抛 `ModelException`，`FallbackModelClient` 零改动接到 backup。预算耗尽不走这条链：`BudgetExhaustedException` 有意不是 `ModelException`，外层 Fallback 不该「换供应商回血」。

---

## 5. 一次完整数据流

`ObservabilityExample` T2–T4 是同一本 `BudgetBook` 上的连续剧本（`USER=alice`，月 10k tokens，降级阈值 25%）：

```text
T1 正常：余量 100% → RouteDecision(premium, "budget healthy: remaining 100%")
   一次工具轮 + 一次终答：800+200 tokens，CostMeter 算出 5400 microUSD
   （premium 单价 $3 / $15 per M）

T2 预警：手工记到 8300/10000 = 83%
   requireBudget → BudgetCheck.Warn（percentUsed=83）
   [BUDGET-WARN] 进 console sink，run 继续
   ——预警不阻断，阈值才有意义

T3 降级：余量 1700/10000 = 17% < 25%
   BudgetAwareRouter → cheap
   reason = "remaining 17% < 25% threshold - downgrading to cheap"
   500+150 tokens，cheap 单价 $0.5 / $1.5 per M → 475 microUSD
   同一条摘要，成本密度掉了一个数量级

T4 耗尽：把 USER 维补记到 limit
   requireBudget → BudgetCheck.Denied
   经 ReActAgentLoop：status=ERROR，lastError 含 "budget exhausted"
   直接调 RoutingModelClient.chat：BudgetExhaustedException
     remaining=0，limit=10000
```

T4 的双层语义是示例实跑抓出来的，不是蓝图事先写好的。`ReActAgentLoop` 的 `catch (Exception e)` 把边界异常收成 run 级失败——这是 loop 的职责。client 边界保留异常类型——这是路由主路径的职责。跨层断言必须先读中间层的 catch，否则会误判「异常丢了」。

恰好踩线走 premium：`percent < downgradeBelowPercent` 是严格小于。余量刚好 25% 不切 cheap，对齐 `BudgetBook`「拒绝透支不拒绝踩线」（`used + est == limit` 放行）。两处 javadoc 互为印证。

---

## 6. 最小代码或实验

三档决策的全部逻辑在 `BudgetAwareRouter.route`，请求本身 v1 不参与（复杂度路由是 v2 策略，不是本类的参数）：

```java
if (budget.isUnlimited()) {
    return new RouteDecision(premiumModel,
            "budget unlimited (no cap configured) - staying on " + premiumModel);
}
if (budget.remainingTokens() == 0) {
    throw new BudgetExhaustedException(
            "budget exhausted: remaining 0 of " + budget.limitTokens()
                    + " tokens - refusing to route (any call would overdraft)",
            0, budget.limitTokens());
}
int percent = budget.remainingPercent();  // 整数向下取整
if (percent < downgradeBelowPercent) {
    return new RouteDecision(cheapModel,
            "remaining " + percent + "% < " + downgradeBelowPercent
                    + "% threshold - downgrading to " + cheapModel);
}
return new RouteDecision(premiumModel,
        "budget healthy: remaining " + percent + "% - staying on " + premiumModel);
```

验收锁定的数字：`2499/10000 = 24%` 向下取整后低于 25%，走 cheap；`5/10000 = 0%` 仍有余量，降级不抛。0% 向下取整不等于耗尽——耗尽只认 `remainingTokens() == 0`。

快照从哪来：`RoutingModelClient` 每次调用前问一次 `Supplier<BudgetSnapshot>`。示例的装配是：

```java
() -> ModelRouter.BudgetSnapshot.of(
        book.remainingOf(BudgetDimension.USER, "alice"),
        book.limitOf(BudgetDimension.USER, "alice"))
```

同一只 `BudgetAwareRouter` 随账本消耗看到不同快照，中途耗尽翻转下一次决策。蓝图草图只写了 candidates + router；实现加的第三构造参数，是验收剧本「先 premium 后 cheap」能自动切换的原因。两参构造器默认 `unlimited()`，给「不按预算选人」的装配留门。

`of()` 在装配期 fail-fast：`limit` 必须为正，`remaining` 必须落在 `[0, limit]`。路由器永远看不见一本不可能的账。未知 `modelId` 则是调用期 `IllegalStateException`，消息带 id、reason 和候选集——路由策略返回了装配里没有的键，是接线 bug，该响。

F3 把纵深写实：强制 `RouteDecision("cheap", "forced for the demo")`，cheap 层是空 scripted Mock（一调就抛 `ModelException`），外面包 `FallbackModelClient(..., backupMock)`。backup 答出原文，Stage 1 的链零改动。全链耗尽时 Fallback 的 `"All fallback clients exhausted"` 原样穿透——那是可用性失败，不是预算失败，两套词不要并。

---

## 7. 常见误区

1. **「降级 = Fallback」** —— Fallback 等故障，Routing 等账单。组合是 `Routing(Fallback(...))`，谁也不替代谁。对照 Stage 9 治理链 + Stage 17 命令白名单：纵深防御的又一次分形。
2. **「耗尽了切 cheap 顶一下」** —— 余量是 0 时任何调用都透支。cheap 降低的是成本密度，不是把配额变成负数。诚实拒绝比赊账可运营。
3. **「预警线到了就该拦」** —— `BudgetCheck.Warn` 永不阻断。拦了，管理员只会把 80% 调到 99%，预警机制死亡。
4. **「reason 写一句『downgraded』就行」** —— 对账要的是数字。`remaining 17% < 25% threshold` 才能在月底按 reason 分组。`RouteDecision` 连 blank 都不让过。
5. **「BudgetExhaustedException 该继承 ModelException，好让 Fallback 救」** —— 换供应商不回血。这个类型有意不是 `ModelException`；它和 Stage 15 `BudgetExceededException` 同语义、不同名（观测层不能反向依赖 enterprise）。

---

## 8. 和相邻概念的区别

```text
RoutingModelClient（18）vs FallbackModelClient（1）
  Fallback：被动、事后、异常路径 —— 主模型抛错才切
  Routing：主动、事前、常态路径 —— 调用前就决定走贵的还是便宜的
  组合：Routing 外层选人，Fallback 内层兜底

BudgetExhaustedException（18 cost）vs BudgetExceededException（15 enterprise）
  语义相同：dimension/used/limit 风格的 fail-closed
  类型分开：D4 禁止 observability import enterprise
  装配层可以桥接两本账，import 歧义靠不同名避免

BudgetAwareRouter vs 未来的 TaskComplexityRouter / LatencyAwareRouter
  v1 只看预算余量，request 不参与决策
  换策略换 ModelRouter 实现，不改 RoutingModelClient
  「路由是策略不是框架」——选谁走是可插拔决策
```

和上一篇的 `ChannelQuota` 也不要混：配额是「有多少」，降级是「怎么花剩下的」。频道维可以单独设闸；路由读哪一维，是装配层选快照的事。

---

## 9. 我的设计判断

最重的一条：**耗尽时拒绝，比「再便宜也算一次」更诚实。** 降级的价值是把 25% 以下的尾部流量换成低单价，让服务继续；它不是透支许可。`BudgetAwareRouter` 把「还能花」和「已经没了」拆成两个出口——一个 `RouteDecision`，一个异常——就是为了不让这两种状态共用一条返回路径。

其次是「路由必须可解释」。`reason` 进构造契约，和 Stage 12 `IdentityDecision`、Stage 9 `AuditEvent` 是同一条裁决传统：denied / routed 都 is intelligence。没有 reason 的自动切换，值班工程师只能对着日志猜「为什么这批回答突然变蠢」。

再次是装配层级。蓝图字面写 `Observing(Routing(...))`；示例实跑发现 `ReActAgentLoop` 不设 `request.model()`，外置观察层拿到 null，`CostMeter` 全部落空。实际装配是候选内侧 `Named(Observing(mock))`：命名包装器盖上计价所需的模型名，路由层零触碰。**设计图会骗你，装配会还你真相**——这句话从 Stage 17 的 `run_tests` 暂存矛盾，原样适用到本阶段的路由栈。

---

## 10. 面试表达

> 「预算耗尽时我没有让 Runtime 继续赊账，也没有把『换便宜模型』当成万能药。三段式是预警、降级、阻断：80% 只发 `BudgetAlarmEvent` 不拦；余量低于阈值由 `BudgetAwareRouter` 切 cheap，reason 带余量百分数，降级是降低成本密度；余量归零抛 `BudgetExhaustedException`——便宜模型也要花钱，任何调用都是透支。路由和 Stage 1 的 Fallback 是两层：外层按预算选人，内层挂了兜底；预算异常有意不是 `ModelException`，Fallback 不该换供应商回血。」

---

## 11. 下一篇连接什么

降级决定了「这单走谁」，还没回答「这单记在谁头上」。下一篇拆 `CostDashboard`：总账与分账为什么必须分离，以及按租户、频道、Agent、用户四个视角拆开之后，各维合计如何等于总账。

→ [stage-18-article-8-cost-dashboard.md](stage-18-article-8-cost-dashboard.md)
