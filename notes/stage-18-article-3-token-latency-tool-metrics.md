# Agent 的 Token、延迟和工具调用指标

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) D2（指标在边界不在路径）· 对应实现：`ObservingModelClient` / `ObservingToolExecutor` / `MetricsCollector`
> 上一篇：[stage-18-article-2-how-to-evaluate.md](stage-18-article-2-how-to-evaluate.md)
> 状态：✅ Stage 18 已完成（M18.1，metrics 7 类；全仓 1148 全绿）
> 这是 Stage 18 系列的第 3 篇：值班工程师要的三件事——花了多少、等多久、工具成了没。

---

## 1. 我今天要解决什么问题

上一篇把评估的主语钉成「任务结果」，而结果里的 `totalTokens` / `toolCallCount` 不是评的时候猜的，是跑的时候采的。值班工程师凌晨那三问，对应三个运营指标：

```text
花了多少？  Token（prompt / completion / total）以及后面才会接上的成本
等多久？    延迟（模型调用墙钟 + 工具执行墙钟 + Run 总时长）
成了没？    工具成功率，以及被治理链拒绝的次数（denied 单列）
```

问题不是「要不要打点」，是**打在哪**。打在 `ReActAgentLoop` 里，每多一条路径就要埋一次；打在 `ModelClient` / `ToolExecutor` 边界上，所有路径自动拥有同一套指标。这篇文章把 D2 落成可核对的类和字段。

---

## 2. 为什么会有这个认知冲突

路径埋点看起来最直观：loop 里走进模型前记 `t0`，走出来记 `t1`。生产里这条路立刻分叉：

```text
路径会分叉，边界会收口：

  ReActAgentLoop 里的 Agent          —— 主路径
  workflow 的 AgentNode              —— 图里嵌套的 Agent
  scheduler 恢复的 Run               —— 断点续跑
  channel 共享会话                   —— 同一 Agent，多用户交替

埋在路径 = 追着每条路径埋。漏一条，值班就缺一块。
埋在边界 = ModelClient.chat/stream、ToolExecutor.execute
           所有路径最终都过这两个口。
```

冲突的另一面是「指标会不会改变行为」。如果装饰器吞了异常、改了返回值、或在 stream 上提前消费，观测本身就成了 bug 源。所以指标必须是旁路：看见，但不改语义。

---

## 3. 它解决了什么问题

三个指标，三个落点，全部在装饰器边界完成：

```text
Token   -> ObservingModelClient 读 ModelResponse.TokenUsage
           usage 未报告诚实记 0（没报 ≠ 免费；缺价是 CostMeter 的 fail-loud）
延迟    -> 调用前后 System.nanoTime()，写入 ModelCallMetrics.latencyMs
           / ToolCallMetrics.latencyMs；Run 级 durationMs 由 beginRun~endRun 墙钟
工具    -> ObservingToolExecutor 记 success / denied / error
           denied 检测是文本契约，不是异常类型
```

「denied 单列」是 Stage 9 的运营回声：被拒也是可观测信号。denied 飙升预示注入攻击或 prompt 退化；`[ERROR]` 前缀是工具跑了之后的失败，是质量信号，不是治理信号。两种 prefix 的语义分界写在 `ObservingToolExecutor` 的 javadoc 里，测试分别锁定。

---

## 4. 核心抽象和架构

装饰器第四代，谱系要写全，否则「又包一层」会被看成重复劳动：

```text
Stage 1   Retry / Timeout / FallbackModelClient     可用性装饰器
Stage 9   GovernedToolExecutor                      治理装饰器
Stage 14  RecordingModelClient / RecordingToolExecutor  训练数据装饰器
Stage 18  ObservingModelClient / ObservingToolExecutor  运营指标装饰器
```

同一哲学第四次实体化：**能力加在边界上，路径保持愚蠢。** `ReActAgentLoop` 零改动。

### 4.1 模型边界：`ObservingModelClient`

- 工厂：`ObservingModelClient.wrap(delegate, sink)`
- `chat`：测延迟，成功走 `ModelCallMetrics.from(model, response, latencyMs)`，失败走 `failure(...)` 后**原样抛出**
- `stream`：`peek` + `AtomicBoolean`，仅在 `StreamEvent.Done` / `Error` 恰发一次；无终止事件零发射（lazy 语义保真）
- 双向异常：delegate 异常记完照抛；sink 异常吞 + warn

`ModelCallMetrics` 字段：`model` / `latencyMs` / `promptTokens` / `completionTokens` / `totalTokens` / `finishReason` / `error`。`success()` 就是 `error == null`。

推荐接线：观察层做**最外层**模型装饰器，延迟包含内侧 Retry/Timeout 的开销——那才是调用方感知到的延迟。蓝图字面写 `Observing(Routing(Fallback(...)))`；示例的候选层实际是 `Named(Observing(mock))`，原因第 1 篇 T5 已经说过：loop 不设 model 名，命名层必须在观察层之外把计价键盖上。两条「最外」不矛盾：对**调用方**，路由仍包在观察之外或与命名层配合，让 `ModelCallMetrics.model` 是能进 `PricingTable` 的键；对**延迟**，观察层要包住会重试、会切备份的那些装饰器。装配顺序是契约，写反了不是「也能跑」，是数字全错。

`modelCallErrors` 单独计数：一次抛错的 `chat` 仍计入 `modelCallCount`，同时 `modelCallErrors+1`，token 三项为 0。值班看错误率用现成字段，不必事后用 `error != null` 再扫一遍事件。`deniedToolCalls` 同理——拒绝计入 `toolCallCount`，再单列 denied。总数含失败，失败再开口，是运营行的常规形状。

### 4.2 工具边界：`ObservingToolExecutor`

- 工厂：`ObservingToolExecutor.wrap(delegate, sink)`
- 治理拒绝前缀：`"[DENIED] "` / `"[RATE_LIMITED] "` → `denied=true, success=false`，文本 verbatim 返回
- `"[ERROR] "`（Stage 2 `DefaultToolExecutor` 包装）→ **不算 denied**，`success=true`（工具跑了，失败文本是模型看见的观察）
- 异常：记 `success=false, denied=false, error=e.toString()`，然后 rethrow
- 接线契约：必须 `Observing(Governed(delegate))`。包在内侧，denial 全部丢失

`ToolCallMetrics` 字段：`toolName` / `latencyMs` / `success` / `denied` / `error`。

### 4.3 聚合：`MetricsCollector`

实现 `MetricsSink`，额外拥有 run 生命周期：

```text
beginRun(runId, agentName)   ThreadLocal 打开上下文
onModelCall / onToolCall     归到当前 run；无上下文 → 孤儿计数
endRun(status, lastError)    物化 RunMetrics，清 ThreadLocal
runMetrics / byAgent / agentStats   只返回已结束的 run
```

守卫：blank id 拒、嵌套 run 拒、runId 复用拒、无 active run 就 `endRun` 拒。活跃中的 run 查 `runMetrics` 得 empty——未结束无汇总，诚实。

`RunMetrics` 汇总行：

```text
runId / agentName / status / lastError / durationMs
modelCallCount / modelCallErrors
toolCallCount / deniedToolCalls
tokenUsage（三项累加）
costMicros（无 CostMeter = 0；有则边到边计价）
```

`succeeded()` 仅 `status == DONE`。`AgentStats.successRate` 用的就是这个定义。`MAX_STEPS_EXCEEDED` 在 loop 里常常看起来「也给了终答」，但运营口径把它算失败——步数打满不是成功收敛。这和评估门禁的「看起来有文本 ≠ 任务完成」是同一判断，只是一个在线上聚合，一个在离线 dataset。

`beginRun` 的三条守卫值得单独记，因为它们全是装配期就能踩到的坑：runId / agentName 空白拒、同一线程嵌套 run 拒、runId 全局复用拒。复用 runId 等于改写历史，对齐 13 的版本追加和 14 的轨迹纪律。活跃 run 尚未 `endRun` 时 `runMetrics(id)` 回 empty，不回半成品行——值班拿半行数字做判断，比没有更糟。

接了 `new MetricsCollector(costMeter)` 之后，`addModelCall` 调用 `costMeter.costMicros(m)`。缺价模型在**聚合路径** catch `IllegalArgumentException` → warn + 记 0：旁路不炸 run。`CostMeter` 被直接调用时仍然 fail-loud（第 5 篇）。两纪律冲突时，聚合选旁路，直接计价选诚实——测试双双锁定。

---

## 5. 一次完整数据流

T1 正常 Run，数字以示例断言为准：

```text
alice："帮我总结这份报告"
  premiumMock:
    第 1 轮 respondToolCalls(echo)
    第 2 轮 respond(text, TokenUsage(800, 200, 1000))

ObservingModelClient
  → onModelCall × 2（含 echo 那一轮的 usage，按 Mock 实际返回）
ObservingToolExecutor
  → onToolCall(echo, success=true, denied=false)

MetricsCollector.endRun(DONE, null)
  → RunMetrics:
       modelCallCount = 2
       toolCallCount  = 1
       tokenUsage.totalTokens 含两轮之和
       costMicros = 5400
         （示例里第二轮 800×$3/M + 200×$15/M；
          计价键是 Named 盖上的 "premium"）
```

T5 的 denied 路径把工具指标的第二种形态跑出来：`dangerous_tool` 被 `ToolPolicy.DENY` 拦下，治理链返回 `[DENIED] ...`，观察层记 `denied=true`，`RunMetrics.deniedToolCalls=1`。同一事件进 `AuditEvent.DENIED` 和轨迹 observation——三种投影，零重复埋点。

---

## 6. 最小代码或实验

指标准确性靠 Mock 可控，不靠真 LLM。`ObservingModelClientTest` 锁的是这些不变量：

```text
chat 透传：assertSame(response)，request 恒等
指标精确：tokens / finishReason / latencyMs >= 0
usage == null → 三项 token 诚实 0
异常记完照抛：同类型同消息
stream：事件序列逐项透传；Done 恰一次含 usage；Error 终止走失败指标
无终止流：零发射
sink 抛异常：主流程仍返回正常 response
多 sink：同一事件广播到每个实现
```

工具侧 `ObservingToolExecutorTest`：

```text
成功透传
异常 rethrow，denied=false
[DENIED] / [RATE_LIMITED] → denied=true, success=false, 文本 verbatim
[ERROR] → 不算 denied
sink 异常隔离
```

聚合侧最有说服力的一条：双装饰器 + 真 `SimpleAgent` + 真 `ReActAgentLoop`，不动 loop 一行，`agentStats` 成功率 1.0。这就是「即插即用」的证据，不是口号。

`MetricsSink` 本身是出口接口，实现方决定去哪：`MetricsCollector` 内存聚合、示例里的 console 打印、未来 JSONL / OTLP adapter。`onAlarm` 是 default method，M18.1 的实现者在 M18.2 加预警时零改动编译通过。接口演进不破坏实现者，和「先稳定自有四事件、OTel 是薄壳」是同一条顺序：语义先收敛，出口后翻译。

示例的 `fanOut` 把同一边界事件广播到 collector、dashboard、console，每个 sink 单独 try-catch。一条旁路挂了，另外两条继续。这是 12 的 listener 隔离在观测出口上的再一次落地：指标可以有多个读者，读者之间不得连坐。

---

## 7. 常见误区

1. **「在 loop 里埋点更准」** —— loop 看到的是自己的步骤，看不到 Timeout 重试、看不到 Fallback 切到备份花的时间。观察层放最外，测的是调用方感知延迟。准不准，先问读者是谁。
2. **「工具抛错和治理拒绝是一回事」** —— 不是。拒绝：工具没跑，`[DENIED]` / `[RATE_LIMITED]`，`denied=true`。抛错：执行器炸了，`error != null`。`[ERROR]` 文本：工具跑了，模型吃到失败观察，`success=true`。三种信号三种处置。
3. **「孤儿事件可以丢」** —— 裸装饰器、异步回调、忘记 `beginRun`，事件仍然进 `totalModelCalls`。运营记账不挑 run。
4. **「costMicros=0 表示免费」** —— 没接 `CostMeter`、或缺价被聚合路径吞掉，都是 0。0 的含义是「这里没算出账」，不是「供应商没收费」。直接问 `CostMeter` 缺价会 IAE。

---

## 8. 和相邻概念的区别

```text
ObservingXxx（18）vs RecordingXxx（14）
  同一装饰器手法，投影目标不同
  Recording 绑定轨迹 / ReplayView
  Observing 绑定 MetricsSink（接口化，不绑 JSONL 格式）

ObservingToolExecutor vs AuditLogger
  审计管"该不该调"（APPROVED / DENIED / ...）
  指标管"快不快、成了没、拒了几次"
  读者不同，不合并

ModelCallMetrics vs ModelResponse.TokenUsage
  TokenUsage 是供应商报告的三项数字
  ModelCallMetrics 是运营事实：三项 + 延迟 + finishReason + error
```

和评估的衔接：`Expectation.Outcome.totalTokens` / `toolCallCount` 由装配层从这些边界事件桥过去。评估包不 import metrics——M18.4 里程碑独立——桥在 `EvaluationRunner.Subject` 的实现里，一行接线。

和成本的衔接也在边界：`CostMeter.costMicros(ModelCallMetrics)` 吃的就是观察层吐出的 record。没有 `ObservingModelClient`，后面的单价、账本、路由余量都没有可信输入。第 1 篇说三种投影共享边界，本篇把「运营投影在边界上到底采了哪几列」摊开——采全了，第 4、5、6 篇才有东西可算。

---

## 9. 我的设计判断

最重的一条：**指标在边界，是零存量改动能持续到第 18 阶段的原因。**

如果从 Stage 1 就在 loop 里打点，到 Stage 5 的图、Stage 7 的调度、Stage 12 的频道，每次都要回头补埋点。装饰器把「看见」从路径里抽出来，18 周规划收官时仍能对存量模块零 diff。这不是事后省事，是一开始就选对了收口。

第二条：旁路纪律必须写进类型行为，不能只写在注释里。delegate 异常照抛、sink 异常吞掉，测试分别锁。少锁一边，下一次「指标系统挂了导致线上全挂」或「模型挂了被指标吞掉」就会出现。

第三条：`denied` 是智能，不是噪音。Stage 9 说 denied is intelligence，Stage 18 把它变成可聚合的计数。没有这个字段，注入试探和 prompt 退化都淹没在「工具失败率」里。值班的处置也因此不同：denied 飙升先查策略和 prompt，error 飙升先查工具实现，两者不能共用一张告警。指标采全，评估和预算才有输入。

---

## 10. 面试表达

> 「Agent 的 Token、延迟、工具指标不埋在 ReActAgentLoop 里，埋在 ModelClient 和 ToolExecutor 两个边界上。ObservingModelClient / ObservingToolExecutor 是装饰器第四代：1 管可用性，9 管治理，14 管训练数据，18 管运营。延迟在调用前后测，usage 在响应上读，denied 按 [DENIED] / [RATE_LIMITED] 文本契约识别，[ERROR] 不算拒绝。指标是旁路：业务异常照抛，sink 异常吞掉。RunMetrics 是值班的一屏答案，MetricsCollector 聚合，loop 一行不改。」

---

## 11. 下一篇连接什么

指标能告诉你「这次走了哪个模型、花了多少」。接下来的问题是：**调用前该选谁？** 大模型、小模型、本地模型不是写死在配置里，而是策略在每次调用前给出带理由的决定。选谁，和挂了换谁，是两层。

→ [stage-18-article-4-model-routing.md](stage-18-article-4-model-routing.md)
