# Prompt、模型和工具的版本如何管理

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) §4 D8 · 对应实现：`ComponentVersion` / `RunRecord` / `RunRegistry` · Prompt 灰度复用：[PromptManager](../agent-product/src/main/java/io/github/qwzhang01/agent/product/prompt/PromptManager.java)（Stage 13，不重做）
> 上一篇：[stage-18-article-8-cost-dashboard.md](stage-18-article-8-cost-dashboard.md)
> 状态：✅ Stage 18 已完成（M18.5 版本与收口；全仓 1148 全绿）
> 这是 Stage 18 系列的第 9 篇。上一篇能说出「这单花了多少、记在谁头上」，本篇回答「这单当时用的是哪一组 prompt / 模型 / 工具」。

---

## 1. 我今天要解决什么问题

复盘的标准问题只有一句：

> 昨天那批答错的 run，用的是哪个 prompt 版本、哪个模型、哪套工具？

没有版本记录，这个问题无解，一切归因都是猜：是 canary 上的 v4 写坏了，还是路由切到 cheap，还是工具集换了指纹。`RunRegistry.byRunId` 把这句话从考古变成查询。这篇讲版本三元组怎么记、记完怎么查，以及 **Prompt 的发布 / 灰度 / 回滚为什么必须复用 Stage 13，不许在观测层再造一套。**

---

## 2. 为什么会有这个认知冲突

Stage 1–17 有一个「版本透明」假设：代码就一份，跑的就是它。Demo 里成立。生产里 prompt 有版本（`PromptManager` 的 stable / canary）、模型有版本（premium / cheap / 供应商串）、工具有版本（注册表指纹）。一次「优化 prompt」让目标 case 变好的同时，可能让另外 30% 边角 case 变坏——你必须知道坏的那些 run 绑的是哪一版。

第二个冲突是「观测层要不要自己做灰度」。技术基线写过百分比 canary。Stage 13 已经有双通道 + 租户路由 + 指针回滚，缺的是「门禁不过不上 stable」。重做一套百分比灰度，既要 sticky session，又和 `PromptManager` 抢 SSOT。蓝图 D8 的裁决：**发布能力复用 13，观测层只记录当时 resolve 到的版本号。**

「抢 SSOT」不是修辞。如果观测层自己再做一份 prompt 表，值班按 `RunRecord` 回滚、产品同学按 `PromptManager.rollback` 回滚，两次操作会对不齐。事故当天一定有人改错那一份。所以 18 连 `PromptVersion` 类型都不引入：只抄四个标量 `kind / name / version / channel`。产品层升级字段，观测层的 record 不动。

第三个冲突是指纹 vs 可读版本。Stage 14 `TrajectoryMetadata` 存 `promptSha256` + 工具名清单，读者是训练系统。人值班要读的是 `PROMPT support-system@v2[canary]`。两份都要，职责不同：轨迹存哈希，`RunRecord` 存可读三元组。

哈希的好处是不依赖产品层：YAML 搭出来的 Agent 和手写 `AgentConfig` 都能算 sha256，`agent-trace-export` 因此零依赖 `agent-product`。可读版本的好处是复盘能说话：「v2 canary」比一串 hex 更像事故报告。两套并存，不要互相替代，也不要在观测层再算一遍哈希——那是 14 已经做完的事。

---

## 3. 它解决了什么问题

版本管理在 v1 拆成两层，故意不合成一个「发布中心」：

```text
变更层（Stage 13，已有，不重做）
  PromptManager.publish(name, content, channel)   追加不可变版本，移动通道指针
  PromptManager.resolve(name, tenantId, channel)  租户覆盖 > 声明通道 > stable
  PromptManager.rollback(name)                    只退 stable 指针，内容永不改写
  PromptChannel.STABLE / CANARY                   双通道，没有百分比

记录层（Stage 18，本篇）
  ComponentVersion(kind, name, version, channel)  三元组的原子
  RunRecord(runId, agentName, versions, metrics)  这一跑用了什么
  RunRegistry.add / byRunId / byAgent             append-only 时间旅行
```

「发布」验收 = 13 的 canary 通道 + 18 的回归门禁（下一篇）。门禁 `PASS` 才把 canary 指针提成 stable；`FAIL` 阻断提升，`rollback` 随时能把 stable 指回上一版。观测层不 import `agent-product`：装配层从 `resolve` 读出版本号，翻译成 `ComponentVersion`。和 `ChannelQuota` 同一手法——**版本号注入，而非管理器依赖。**

工具集指纹同样由装配声明，不扫描注册表。示例写 `TOOL core@f1`，意思是「这一跑挂的是核心工具集的第一枚指纹」，不是框架自动算出的哈希。自动指纹看起来省事，换一个工具描述字符串就会让所有历史 `RunRecord` 对不上。人声明的指纹可以稳定几个月，和 prompt 版本号一样是运营词汇，不是实现细节。

---

## 4. 核心抽象和架构

`ComponentVersion.Kind` 只有三个值：`PROMPT` / `MODEL` / `TOOL`。

```text
PROMPT  name=support-system  version=v2  channel=canary
MODEL   name=premium         version=2026-08  channel=null
TOOL    name=core            version=f1       channel=null
```

`channel` 可空是诚实：模型和工具没有 stable/canary 概念，缺席比写 `"n/a"` 好。PROMPT 的 channel 来自 `PromptVersion.channel()`。`ComponentVersion.of(kind, name, version)` 是无通道工厂。

`RunRecord.of(versions, metrics)` 从 `RunMetrics` 派生 `runId` / `agentName`——它们本来就同行走。蓝图草图另列了 `costMicros`；M18.2 接线后 `metrics.costMicros()` 已是精确和，再单列就是两处真相。`combination()` 渲染成人读串：

```text
PROMPT support-system@v1[stable], MODEL premium@2026-08, TOOL core@f1
```

`RunRegistry` append-only：重复 `runId` 拒（改写历史 = 伪造历史，对齐 13 版本追加与 14 轨迹纪律）。`byRunId` 是时间旅行；`byAgent` / `all` 保插入序。`size()` 给测试和值班一个「今晚记了几条」的整数，不提供 update / delete。v1 内存态，进程生命周期，JSONL 持久化留 v2。

`RunRecord` 构造会拷贝 versions 列表。外面再改 list，已经登记的记录不变。这和 `EvalDataset.cases()` 返回防御拷贝是同一纪律：登记之后的对象是事实，不是草稿。

装配翻译（`ObservabilityExample.versionTriple`）：

```java
PromptVersion pv = prompts.resolve("support-system", null, null).orElseThrow();
return List.of(
        new ComponentVersion(Kind.PROMPT, pv.name(), "v" + pv.version(), pv.channel()),
        ComponentVersion.of(Kind.MODEL, model, "2026-08"),
        ComponentVersion.of(Kind.TOOL, "core", tools));
```

`resolve` 的三个参数是 `(name, tenantId, declaredChannel)`。租户 canary 用 `setTenantChannel(tenantId, promptName, PromptChannel.CANARY)`，优先级高于声明通道；`clearTenantChannel` 撤掉覆盖，租户回到声明通道或 stable。通道非法值在 `PromptChannel.requireValid` 处拒，只认 `stable` / `canary` 两个常量——没有第三个「百分比通道」。

实例级 pin：`AgentDefinitionBinder` 在绑定时代入内容，跑着的对话不热切——13 的 javadoc 写明「instance-level pin IS conversation-level pin」。这正是百分比灰度留 v2 的原因：没有会话粘滞，中途切流量会让同一对话看见两版 prompt，复盘时 `RunRecord` 也说不清「当时」是哪一截。

`history(name)` 返回该 prompt 的不可变版本列表，就是审计轨迹。`rollback` 只在曾经发到 stable 的版本里往回找指针，找不到就 IAE「already at the earliest version」。canary 指针不被 rollback 碰——回滚的是生产通道，试验通道继续指着新版本，方便对照。

---

## 5. 一次完整数据流

示例 T0 / T6 / T7 把发布和记录串起来：

```text
T0  prompts.publish("support-system", "You are a careful support agent...")
    → PromptVersion v1 / stable
    AgentConfig 吃的是 v1.content()（绑定时代入，不是每次 resolve）

T1  run-t1 走 premium
    RunRegistry.record([PROMPT v1/stable, MODEL premium, TOOL core@f1], metrics)

T3  run-t3 走 cheap（上一篇的降级）
    同一 prompt 指针，MODEL 变成 cheap
    byRunId("run-t3") 能解释「这批变便宜」不是 prompt 回退

T6  prompts.publish(..., "canary")
    → v2 / canary（修复文案要求 always include a summary）
    EvaluationRunner 重放 dataset
    首跑 BASELINE_ABSENT → 复跑 PASS → 可以提升
    反例：0.50 >= 地板 0.50 但 < 基线 1.00 → FAIL，阻断提升

T7  byRunId("run-t1") → combination() 打出当时的三元组
```

T6 的「提升」在产品层是移动 stable 指针，不是改 v2 的内容。`rollback("support-system")` 把 stable 指回上一个曾经发到 stable 的版本，history 一行不删。百分比流量切分不存在：v1 只有双通道 + 租户覆盖。提升本身也不写进 `RunRegistry`——注册表记的是执行，不是发布动作。谁在几点把 canary 提成 stable，仍以 `PromptManager.history` 为准。

---

## 6. 最小代码或实验

时间旅行就是一次 map 查找。重复写入必须失败：

```java
RunRegistry registry = new RunRegistry();
registry.record(versions, metrics);          // runId 来自 metrics.runId()
Optional<RunRecord> thatNight = registry.byRunId("run-t1");
// thatNight.get().combination()
//   → "PROMPT support-system@v1[stable], MODEL premium@2026-08, TOOL core@f1"

registry.add(sameRunIdAgain);                // IAE: already recorded (append-only)
```

Prompt 灰度不新写类，只调 13：

```java
PromptManager prompts = new PromptManager();
prompts.publish("support-system", v1Text);                    // → stable
prompts.publish("support-system", v2Text, PromptChannel.CANARY);
prompts.setTenantChannel("acme", "support-system", PromptChannel.CANARY);
PromptVersion seen = prompts.resolve("support-system", "acme", null).orElseThrow();
// acme 看见 v2/canary；其他租户仍是 stable
prompts.rollback("support-system");                           // 只动 stable 指针
```

观测层要做的，是在 `endRun` 之后把 `seen.version()` / `seen.channel()` 写进 `ComponentVersion`。它不拥有发布状态机。

`RunRecord.combination()` 的渲染规则写死在 record 里：`KIND name@version`，有通道再接 `[channel]`。测试锁定方括号——`PROMPT support-system@v2[canary]` 和 `MODEL premium@2026-08` 一眼能分。`byAgent("assist")` 按插入序给出该 Agent 的全部记录，适合拉「昨晚这只机器人跑过哪些组合」，再按 `runId` 跳进单次。

v1 注册表不落盘。进程重启，时间旅行能力清零。这和 `MetricsCollector` 同边界：先把查询语义做对（重复拒、保序、三元组完整），JSONL 持久化跟 eval dataset 同款契约再开。不要在内存实现里偷偷写文件，那会让「append-only」和「进程生命周期」两句话同时失效。

---

## 7. 常见误区

1. **「观测模块应该自带 Prompt 灰度」** —— 13 已经是 SSOT。再做一套，两次 publish 会对不齐。18 只记录 resolve 结果。
2. **「改 prompt 内容做热更新」** —— `publish` 只追加。运行中的 Agent 拿的是绑定快照。要新版本，下一次 bind。
3. **「channel 一律填 stable」** —— 模型和工具没有通道。null 是诚实，伪造通道是脏数据。
4. **「byRunId 查不到就 upsert」** —— 重复 runId 拒。改历史等于伪造事故现场。
5. **「百分比 canary 才叫灰度」** —— 13 已声明百分比需要 sticky session，留 v2。双通道 + 租户路由 + 指针回滚 + 18 门禁，已经是发布最小闭环。
6. **「模型版本跟供应商走就行」** —— 供应商字符串会变，装配键（`premium` / `cheap`）相对稳定。`ComponentVersion` 记的是人能用来复盘的键，和 `PricingTable`、`RouteDecision.modelId` 同一个词。

---

## 8. 和相邻概念的区别

```text
ComponentVersion（18）vs PromptVersion（13）
  PromptVersion 是资产：内容 + 版本号 + 通道指针
  ComponentVersion 是引用：kind/name/version/channel，不含正文
  观测层拿引用，不拿内容

RunRecord（18）vs TrajectoryMetadata（14）
  metadata：sha256 指纹 + 工具清单，训练消费
  RunRecord：可读三元组 + RunMetrics 行，人消费
  同一 run 两份投影，不合并成一张大表

RunRegistry vs PromptManager.history
  history 是「这个 prompt 发过哪些版本」
  byRunId 是「这次 run 用了哪一版」
  前者是资产时间线，后者是执行时间线

instance pin（13）vs 百分比灰度（有意不做）
  pin 保证一轮对话内 prompt 不变
  百分比灰度要会话粘滞，v1 不做
```

---

## 9. 我的设计判断

最重的一条：**可复现性的前提是「当时用的什么」可查询，不是「现在线上是什么」。** 线上指针会动，run 当时的组合必须冻在 `RunRecord` 里。append-only 和 13/14 是同一哲学：历史只追加，不改写。

其次是依赖方向。`agent-observability` 的 compile 依赖只有 `agent-core` + `agent-trace-export`。Prompt 版本号从装配层进来，和 `ChannelQuota`、`BudgetSnapshot` 同一模式。观测层一旦 import `PromptManager`，产品层演进会拖着运营层重编译——这是 Stage 15 `RequestContext` 显式传递、Stage 16 `executorFactory` 注入已经否决过的方向。

再次是「三元组够不够」。v1 不记采样温度、不记 ContextBuilder 窗口、不记治理策略版本。那些是下一层复盘材料。先让「prompt / model / tool」三问有答案，再加字段。加字段等于升契约，和轨迹 JSONL 的 `api_version` 纪律一致。

模型名记的是装配键（`premium` / `cheap`），不是供应商字符串。`RoutingModelClient` v1 不改写 `request.model()`，候选按逻辑键编址；供应商要自己的模型串，在自己的 client 里改——示例用 `Named` 包装器盖名。所以 `ComponentVersion` 的 MODEL 行和路由 reason 对得上：人读「走了 cheap」，账本按 `cheap` 计价，版本记录也写 `cheap`，三处一个词。

和上一篇的仪表盘也不要抢职责：账单回答「花了多少、记在谁头上」，版本回答「当时用的哪一组」。同一 run 两问都要能答，才叫可复盘。钱对得上但组合对不上，只能说「这批贵了」；组合对得上但钱对不上，只能说「这版变蠢了」。两份档案缺一，值班仍在猜。

---

## 10. 面试表达

> 「Agent 答错之后，我要能回答当时用的哪一版 prompt、哪个模型、哪套工具。`RunRegistry.byRunId` 做时间旅行；`ComponentVersion` 是 PROMPT/MODEL/TOOL 三元组，prompt 的通道可空，模型和工具没有通道就诚实写 null。Prompt 灰度不重做：Stage 13 的 `PromptManager` 已经有 stable/canary、租户路由和指针回滚，观测层只记录 `resolve` 到的版本号。发布闭环是 13 的双通道加 18 的回归门禁——门禁不过，canary 不上 stable。」

---

## 11. 下一篇连接什么

版本能退回去，还缺「退之前用什么证明新版本没把别的 case 弄坏」。下一篇把失败轨迹变成回归用例：`EvalDataset.importFailures`、`originRunId` 谱系，以及门禁三态 `PASS` / `FAIL` / `BASELINE_ABSENT`。

→ [stage-18-article-10-failure-into-eval.md](stage-18-article-10-failure-into-eval.md)
