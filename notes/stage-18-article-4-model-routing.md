# 模型路由：大模型、小模型和本地模型如何自动选择

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) D6（路由是策略，决策必须可解释）· 对应实现：`routing/`（`ModelRouter` / `RouteDecision` / `RoutingModelClient` / `BudgetAwareRouter`）
> 上一篇：[stage-18-article-3-token-latency-tool-metrics.md](stage-18-article-3-token-latency-tool-metrics.md)
> 状态：✅ Stage 18 已完成（M18.3，routing 4 类；全仓 1148 全绿）
> 这是 Stage 18 系列的第 4 篇：选谁走是策略，挂了换谁是兜底——两层互补，谁也不替代谁。

---

## 1. 我今天要解决什么问题

上一篇采到了每次调用的模型名、token 和延迟。采到只是事后看见。生产里更早的问题发生在调用前：

> 这一单走贵的、便宜的，还是本地的？

「写死在配置里永远走 gpt-4o」在 Demo 成立，在账单上不成立。余量充足时用强模型换质量，余量吃紧时切便宜模型换密度，耗尽时诚实拒绝——这是经济性决策，不是可用性决策。Stage 1 的 `FallbackModelClient` 已经会「挂了换谁」。Stage 18 要补的是「一开始走谁」，并且**每一次选择必须留下 reason**。

标题里的「大模型、小模型、本地模型」在 v1 里对应候选 Map 的逻辑键（示例用 `premium` / `cheap`）。本地模型只是又一个 `ModelClient` 实现，路由层不认识部署形态。选谁是策略，键是装配层起的名字。

---

## 2. 为什么会有这个认知冲突

最容易混的两件事：

```text
Fallback（1）管可用性：主模型抛错才切备选
  被动、事后、异常路径
  「挂了换谁」

Routing（18）管经济性：调用前就决定走贵的还是便宜的
  主动、事前、常态路径
  「选谁走」
```

冲突来自一个错觉：既然 Fallback 已经能切模型，路由是不是重复建设？不是。Fallback 切的触发条件是**异常**；预算吃紧时贵模型仍然活着，Fallback 不会动。反过来，路由选中的便宜模型也会挂，没有内层 Fallback，这一单就直接死。

所以正确形态是纵深，不是二选一：

```text
Routing(Fallback(premium, cheap))
  外层按预算选人
  内层挂了兜底
```

对照 9 的治理链 + 17 的命令白名单：两层各守各的哨，谁也不替代谁。

---

## 3. 它解决了什么问题

`RoutingModelClient` 只做一件事：每次 `chat` / `stream` 前问 router，把选中的 candidate 原样转发。谁被选中是 `ModelRouter` 的事。

v1 默认策略 `BudgetAwareRouter` 按余量三档：

```text
健康（remaining% >= 阈值） → premium
  reason: "budget healthy: remaining N% - staying on premium"

吃紧（0 < remaining% < 阈值） → cheap
  reason: "remaining N% < T% threshold - downgrading to cheap"

耗尽（remaining == 0） → 抛 BudgetExhaustedException
  便宜也救不了：任何一次调用都透支，诚实拒绝
```

降级不是拒绝服务，是降低剩余服务的成本密度。耗尽才是拒绝。阈值默认在蓝图剧本里是 25%，构造参数 `downgradeBelowPercent` 取值 1–99。恰好踩线（`==` 阈值）走 premium——严格 `<` 比较，对齐 `BudgetBook`「拒绝透支不拒绝踩线」。

请求本身 v1 **不参与**决策——`route` 签名里有 `ModelRequest`，是给未来的复杂度策略留的，`BudgetAwareRouter` 读完预算就返回，不看消息长度、不看工具列表。复杂度路由、SLA 路由、健康度路由是 v2 的其他 `ModelRouter` 实现，不是本类上的参数。策略可插拔，框架结构不变。

`unlimited()` 快照永远走 premium，reason 写 `"budget unlimited (no cap configured) - staying on " + premiumModel`。没配预算不是「默认便宜」，是「没有需要节省的东西」。把「未配置」理解成「穷」，会在 Demo 和单测里莫名其妙切到 cheap。

---

## 4. 核心抽象和架构

```text
routing/
  ModelRouter           策略接口：route(request, BudgetSnapshot) → RouteDecision
                        嵌套 record BudgetSnapshot(remainingTokens, limitTokens)
  RouteDecision         record：modelId + reason（reason 类型层必填）
  RoutingModelClient    装饰器：候选 Map + router + 快照 Supplier
  BudgetAwareRouter     默认策略：按余量三档
cost/
  BudgetExhaustedException  余量恰好为 0 时抛出（非 ModelException）
```

### 4.1 `RouteDecision.reason` 必填的三个理由

compact constructor 拒 null/blank。这不是 javadoc 惯例，是构造契约：

1. **成本对账**：月底 30% 流量走了便宜模型，「为什么」必须行行可审计。`remaining 18% < 25% threshold` 是对账材料。
2. **事后归因**：某批回答质量差，回查发现那段时间切了 cheap。没有 reason，这就是不可复现的玄学事故。
3. **对齐裁决传统**：Stage 12 `IdentityDecision`、Stage 9 `AuditEvent`——决策必须留痕，denied 和 routed 都是智能。

`modelId` 是候选 Map 的逻辑键（`premium` / `cheap`），**不一定**是供应商模型串。v1 候选按键编址，`RoutingModelClient` **不改写** `request.model()`。需要自己模型串的供应商，在自己的 client 里改——示例用 `Named` 包装器盖名，正是这条偏差的装配兑现。

### 4.2 路由器只见数字不见账本

`BudgetSnapshot` 是 D5「数字注入」在路由侧的落点：

```text
BudgetSnapshot.unlimited()     limitTokens = -1，永不降级
BudgetSnapshot.of(remaining, limit)  校验 limit>0 且 0<=remaining<=limit
isUnlimited() / remainingPercent()   percent 整数向下取整；unlimited 视为 100
```

路由器永远看不见 `BudgetBook` / `ServiceAccount`。测试用纯数据构造快照。装配层从 `book.remainingOf` / `limitOf` 翻译。

`RoutingModelClient` 的第三构造参数是 `Supplier<BudgetSnapshot>`：每次调用前取新快照。同一 router 随账本消耗看到不同数字，中途耗尽会翻转下一次决策。两参重载默认 unlimited——适合还没接账本、或策略根本不看预算的装配。候选 Map 不能空，键不能 blank，client 不能 null，装配期 IAE。`candidateIds()` 给出当前能解析的键，供装配期自检：router 若返回不在这个集合里的 `modelId`，运行时 ISE，不要拖到对账月底。

选哪一维当路由信号，也是装配层的事。示例的 supplier 读的是 `USER` + `"alice"`。换成 `CHANNEL` + `"eng"`，同一 `BudgetAwareRouter` 就按频道余量切档，一行都不改。路由器不见维、不见人、不见频道。

### 4.3 路由是主路径，不是旁路

对照 `ObservingModelClient`：指标 sink 抛异常被吞。路由相反——router 异常（含 `BudgetExhaustedException`）和 delegate 异常全部原样上抛。调用方必须知道这一单被拒了。未知 `modelId` 抛 `IllegalStateException`，消息含 id + reason + 候选集：这是装配 bug 的正确死法。

`BudgetExhaustedException` 落在 cost 包，**有意不是** `ModelException`。预算耗尽不是瞬态模型故障；外层 `FallbackModelClient` 不该「换个供应商就恢复」——换供应商不回血。它和 Stage 15 的 `BudgetExceededException` 同语义不同名：D4 禁止 observability 反向依赖 enterprise，桥接两本账时还要避开 import 歧义。

---

## 5. 一次完整数据流

示例 T0 装配 + T3 降级，数字以代码为准：

```text
T0
  candidates = {premium: Named(Observing(mockA)),
                cheap:   Named(Observing(Fallback(mockB, backup)))}
  router     = BudgetAwareRouter("premium", "cheap", 25)
  budgetSource = () -> BudgetSnapshot.of(
                     book.remainingOf(USER, "alice"),
                     book.limitOf(USER, "alice"))   // alice 月 10k

T1 余量 100%
  → RouteDecision(premium, "budget healthy: remaining 100% - staying on premium")
  → 指标、账本按 premium 计价

T3 记账后余量 17% < 25%
  → RouteDecision(cheap, "remaining 17% < 25% threshold - downgrading to cheap")
  → 本单 cheap 定价：500×$0.5/M + 150×$1.5/M = 475 microUSD
  → 降级不是拒绝，是降低成本密度

T4 余量耗尽
  → BudgetAwareRouter 抛 BudgetExhaustedException(remaining=0, limit=10000)
  → 经 loop：status=ERROR，lastError 含 "budget exhausted"
  → 经 client 边界直调：异常类型原样穿透
  —— run 级看 status，client 级看异常类型，分层语义不同
```

F3 把「便宜模型也挂」跑通：空 scripted Mock 天然抛 `ModelException`，Stage 1 的 `FallbackModelClient` 零改动切到 backup，答案回来。全链耗尽时 `"All fallback clients exhausted"` 原样穿透——诚实失败，不伪装成功。

---

## 6. 最小代码或实验

零开销透传是测试锁定的契约，不是性能口号：选中 client 收到**原 request 实例**（`assertSame`），返回**原 response / 原 stream 实例**；未选中者调用次数为 0。router 每次调用都问——两次 `chat` 就是两次 `route`。

验收剧本（`RoutingModelClientTest`）用真 `BudgetBook` + 真 `BudgetAwareRouter` + supplier 读 `remainingOf`/`limitOf`：先记 8500，余量 15% < 25%，**同一 client 实例**下一次自动切 cheap。这是「至少 2 个模型自动切换」的规划验收原文落地。

```java
BudgetAwareRouter router = new BudgetAwareRouter("premium", "cheap", 25);
RoutingModelClient client = new RoutingModelClient(
        Map.of("premium", premiumTier, "cheap", cheapTier),
        router,
        () -> {
            long limit = book.limitOf(BudgetDimension.USER, "alice");
            return limit < 0
                    ? ModelRouter.BudgetSnapshot.unlimited()
                    : ModelRouter.BudgetSnapshot.of(
                            book.remainingOf(BudgetDimension.USER, "alice"), limit);
        });
```

`percent` 向下取整被单独锁住：2499/10000 = 24% → cheap；5/10000 = 0% 仍是「有余量」→ 降级不抛（0% 向下取整但仍 `remainingTokens > 0`）。只有 `remainingTokens == 0` 才耗尽。整数百分数会丢小数，所以阈值比较的真实输入是截断后的 percent，reason 里打印的也是这个截断值——对账时不要回头用浮点重算一遍再怪路由切早了。

`BudgetSnapshot.of` 在快照构造期 fail-fast：`limit <= 0` 拒，`remaining` 落在 `[0, limit]` 之外拒。路由器被保证永远看不见一本不可能的账。静态工厂曾和实例谓词都叫 `unlimited()`，javac 不允许同签名共存，谓词改名 `isUnlimited()`——Java 17 语法边界的又一次实锤，写 record 先查实例方法命名空间。

示例用 `printing` 包装 router，每个决策打 `[routing] -> cheap (reason...)`。F2「全部 RouteDecision.reason 非空」在控制台是可见的，不只在断言里。未知键的 ISE 也必须带上 reason：装配错了，值班至少看得见「本想选谁、为什么」。

---

## 7. 常见误区

1. **「路由 = Fallback 换个名字」** —— 触发条件不同：一个看预算余量（常态），一个看异常（事后）。组合是 `Routing(Fallback(...))`，不是互相替换。
2. **「耗尽了切最便宜的继续跑」** —— `BudgetAwareRouter` 在余量 == 0 时抛异常。便宜模型也要花 token，透支就是赊账。赊账是账单系统的事，不是框架语义。
3. **「reason 写在日志里就行」** —— 日志会丢、会轮转、不能按 reason 分组对账。reason 在 `RouteDecision` 上，构造期强制非空，才能进对账材料。
4. **「路由应该改写 request.model()」** —— v1 故意不改。候选按逻辑键编址；供应商模型串是那个 client 自己的事。观察层要看计价名，用 `Named` 盖，不让路由器兼做改写器。
5. **「本地模型需要特殊分支」** —— 不需要。本地模型是 Map 里的又一个 `ModelClient`。策略若要「优先本地」，写另一个 `ModelRouter`，不要改 `RoutingModelClient`。

---

## 8. 和相邻概念的区别

```text
RoutingModelClient vs FallbackModelClient
  选谁（事前、经济性、主路径） vs 挂了换谁（事后、可用性、异常路径）
  组合：Routing(Fallback(premium, cheap))

BudgetAwareRouter vs BudgetBook.requireBudget
  路由器读快照、给 RouteDecision 或抛 BudgetExhaustedException
  账本给 BudgetCheck 三态（Ok / Warn / Denied），本身不选模型
  降级发生在路由层；预警和阻断发生在账本层（下一篇）

RouteDecision vs IdentityDecision（12） / AuditEvent（9）
  同一传统：决策留痕，reason 是智能
  路由的 reason 面向成本对账，身份的 reason 面向权限，审计的 reason 面向治理
```

v2 可以有 `TaskComplexityRouter` / `LatencyAwareRouter` / `AvailabilityRouter`。它们实现同一个 `ModelRouter`，不改装饰器。路由是策略不是框架。

---

## 9. 我的设计判断

最重的一条：**可解释是路由的产品功能，不是日志开关。**

「为什么这单走了 cheap」如果只能靠猜，成本治理就废了。把 reason 放进类型，比写一篇「请记得打日志」有效得多。对齐 12 / 9 的裁决留痕，是同一条架构肌肉在经济面上的发力。

第二条：快照供应商是「同一 router 看不同账」的最小机关。蓝图草图只写了 candidates + router，没写预算视图从哪来。没有每次取新快照，自动切换测不出来，中途耗尽也翻不了下一单。

第三条：耗尽异常刻意不继承 `ModelException`。可用性层和预算层一旦共用异常类型，Fallback 就会在最不该恢复的时候「恢复」。类型边界就是产品边界。本地模型、小模型、大模型在这套类型里地位相等——都是候选键——自动选择靠的是策略读到的数字，不是模型名字里有没有「local」。

---

## 10. 面试表达

> 「模型路由管的是选谁走，Fallback 管的是挂了换谁。RoutingModelClient 调用前问 ModelRouter，转发选中候选，request 原样透传。v1 策略是 BudgetAwareRouter：余量健康走 premium，低于阈值走 cheap，耗尽抛 BudgetExhaustedException。RouteDecision.reason 类型层必填，因为对账和归因都靠它。组合形态是 Routing(Fallback(...))——外层按预算选人，内层挂了兜底。本地模型只是候选 Map 里的又一个 ModelClient，不需要特殊分支。」

---

## 11. 下一篇连接什么

路由读的是「还剩多少」。还剩多少从哪来？下一篇把账本打开：**单次 Run 的经济闸，和用户月度配额，如何事前检查、事后入账，以及 WARN 为什么绝不阻断。**

→ [stage-18-article-5-token-budget.md](stage-18-article-5-token-budget.md)
