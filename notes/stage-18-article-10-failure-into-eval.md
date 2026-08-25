# Agent 失败样本如何进入回归测试集

> 配套蓝图：[architecture-stage-18.md](architecture-stage-18.md) §4 D7 · 对应实现：`EvalDataset` / `EvalCase` / `Expectation` / `EvaluationRunner` / `EvalReport` · 轨迹来源：`TrajectoryReplayer` / `DoneReason`（Stage 14）
> 上一篇：[stage-18-article-9-version-management.md](stage-18-article-9-version-management.md)
> 状态：✅ Stage 18 已完成（M18.4 评估回归 + M18.5 门禁剧本；全仓 1148 全绿）
> 这是 Stage 18 系列的第 10 篇。上一篇能查到「当时用的哪一版」，本篇回答「修完这一版，用什么证明没把别的 case 弄坏」。

---

## 1. 我今天要解决什么问题

传统软件修 bug：先写一条单测，再改代码。Agent 的 bug 多数不在 Java 里，在 prompt / 模型 / 工具组合里。`ReActAgentLoop` 的单测断言不了「这次回答蠢不蠢」。

本篇的命题是：

> 失败样本即回归测试集。修一个 bug = 数据集 +1 条用例。`originRunId` 把用例和事故钉在同一条谱系上。

落点是 `EvalDataset.importFailures`。门禁读 `EvalReport.verdict`：`PASS` / `FAIL` / `BASELINE_ABSENT`。和上一篇合起来，才是「改得动、退得回、发得稳」。

---

## 2. 为什么会有这个认知冲突

全仓 1148 个单测护的是框架代码：装饰器透传、预算闸边界、JSONL 契约。它们是确定性的，Mock 可控，毫秒级。prompt 一改，这些测试全绿，线上却可能静默退化——单测看不见模型行为。

另一个冲突是「用 LLM 评 LLM」。LLM-as-judge 自己非确定、自己烧 token。门禁若今天 PASS、明天同输入 FAIL，发布线就废了。v1 判定器只做确定性断言：`ExactMatch` / `Contains` / `MaxTokens` / `ToolCallCount`。judge 槽位留在 `Expectation` 接口，本阶段不开。

「槽位」的意思是：接口可以长出第五种实现，不需要改 `EvaluationRunner` 的循环。今天不开，是因为一开就必须回答「judge 自己的失败怎么进回归集」——套娃一旦开始，门禁的可复现承诺先破。等确定性四断言把发布线站住，再让 judge 当辅助分，不当一票否决。

第三个冲突是「失败形态谁来翻译成断言」。蓝图草图的 `importFailures(trajectories)` 两参版无法诚实构造期望——「终答应含道歉」是领域知识，框架通译要么伪造要么空洞。实现加了第三参 `expectationFor`：翻译函数由人提供。D7 自己举例「终答不应包含 X / 应包含 Y」，恰好证明翻译者是人。

---

## 3. 它解决了什么问题

评估集补上单测盖不住的那一层：

```text
单元测试     断言 Java 逻辑（Mock 可控，结果确定）
EvalDataset  断言 prompt/模型/工具组合（同 Mock 可复现；真模型可评估）
门禁         跌破阈值或低于基线 → FAIL → 阻止 canary 升 stable
```

失败回收的过滤三源（`EvalDataset.isFailure`）：

```text
DoneReason.ERROR
DoneReason.MAX_STEPS_EXCEEDED
reward != null && reward < minReward
```

健康的 `DONE`、没有 reward 的 `DONE` 不进集。prompt 取轨迹 `messages` 里第一条非空 USER；没有 USER 的轨迹诚实跳过，计入返回值时不算导入——返回值是「真正加进去的条数」，不是「看过的失败条数」。`EvalCase.originRunId = trajectory.runId()`。手工用例走 `EvalCase.of(...)`，`originRunId=null`——缺席是诚实，伪造谱系才是错。

为什么必须是第一条 USER，不是最后一条、也不是全部拼接：回归要复现的是「当时用户问了什么」，不是整段被 ContextBuilder 裁过的窗口。窗口会变，用户原话不应变。这和 Stage 14 的教训同构：模型实见可以裁，逻辑对话通道要留全量；评估集站在逻辑通道这一侧。

`EvaluationRunner.Subject` 是注入的函数接口：测试用 Mock lambda，生产用真 Agent + M18.1 装饰器填 `Expectation.Outcome`。runner 不建 Agent，M18.4 才能独立于 metrics/cost 交付。subject 崩溃 = 该用例失败，评估继续——第 1 个 case 炸了不剥夺其余 7 个的判决。null outcome 走同一条失败路径，不当成 NPE 穿透。空数据集直接 IAE：空评估不是评估，门禁不能对零用例放行。

---

## 4. 核心抽象和架构

```text
Expectation（sealed）
  ExactMatch / Contains / MaxTokens / ToolCallCount
  嵌套 Outcome(finalText, totalTokens, toolCallCount)
EvalCase(caseId, prompt, expectation, originRunId)
EvalDataset
  add / importFailures / save / load（JSONL，snake_case + api_version/kind 信封）
EvaluationRunner.evaluate(dataset, subject, baseline, minPassRate) → EvalReport
EvalReport(passRate, results, baseline, verdict)
  Verdict = PASS | FAIL | BASELINE_ABSENT
```

门禁是三级瀑布（实现期坑 1 纠正过初版）：

```text
1. passRate < minPassRate     → FAIL     绝对地板，首跑也生效
2. baseline == null           → BASELINE_ABSENT  高于地板，无可比对象
3. passRate < baseline.passRate → FAIL   相对回归
否则 PASS
```

初版把「无基线」一票先决，导致首跑 0% 也标 `BASELINE_ABSENT`，地板被吞。三测齐红后改成地板优先。教训：先写清每个状态为谁存在，再写判定顺序。

三个状态各自的读者也不一样。`FAIL` 给发布流水线：挡住提升，列出 `failureDetails()`。`PASS` 给下一轮当基线：这次报告存下来，下次比它。`BASELINE_ABSENT` 给装配策略：人决定「首跑 100% 能不能当基线提升」，框架不替你点头。把三态收成布尔 `passed`，首跑和回归、地板和相对，全挤进一个 bit，值班只能再去翻日志。

`MaxTokens` 含上界（恰好用尽放行），对齐 `BudgetBook` 踩线约定。`ToolCallCount` 精确相等——脆性是确定性断言的代价，javadoc 写明。`EvalReport` 无时间戳、无环境态：同 dataset + 同确定性 subject = record 字段相等。可复现是结构性保证，不是纪律承诺。

JSONL 契约按 Stage 14 `TrajectoryCodec` 纪律手建树：坏行带行号 fail-loud，改字段名升 `api_version`，不靠 Jackson 命名策略猜。信封是 `api_version=v1` + `kind=EvalCase`，字段 `case_id` / `prompt` / `origin_run_id` / `expectation.type`。未知期望类型、重复 id、缺字段三类违约在 `EvalDatasetTest` 里各有用例。空行跳过，不把空白当坏行。

四类期望覆盖四种失败形态，不是四种文风：

```text
Contains      终答必须出现某词（道歉 / summary）——修「说了不该说的」
ExactMatch    整段相等 —— 只适合 Mock 脚本，真模型几乎不用
MaxTokens     总量含上界 —— 防膨胀 run（17 的修复死循环经济面）
ToolCallCount 次数精确相等 —— 防「修一个 bug 多调十五次工具」
```

`Outcome` 是嵌套 record：`finalText` + `totalTokens` + `toolCallCount`。判定对象不只是文本。装配层用 `ObservingModelClient` / `ObservingToolExecutor` 把真 Agent 的 usage 和工具次数填进 Outcome；runner 自己不接线，所以 M18.4 能在 metrics 之前独立交付。

---

## 5. 一次完整数据流

`ObservabilityExample` T5–T6：

```text
T5 失败样本
  空 scripted Mock → chat 抛错 → loop status=ERROR
  RecordingAgent 产出 Trajectory（doneReason=ERROR）
  dataset.add(手工 Contains("answer"))
  importFailures([failed], minReward=-0.4, t -> Contains("summary"))
  imported == 1
  mined.originRunId == failed.runId()     // 谱系成立

T6 门禁
  PromptManager.publish(..., "canary")    // 上一篇的 v2
  Subject 固定返回 "the answer, in summary form"
  首跑 baseline：verdict=BASELINE_ABSENT（建基线，不假对比）
  复跑同一 subject：verdict=PASS，canary 可提升
  反例 subject：手工 case 答 "I do not know"，失败 case 仍含 summary
    passRate=0.50，地板 0.50 → 不因地板 FAIL
    但 0.50 < 基线 1.00 → FAIL，提升阻断
```

失败明细走 `failureDetails()`：`caseId` + `expected ... got text="..." tokens=... toolCalls=...`。长文本截断 80 字符，复现拿全文——报告是清单，不是语料库。截断是为了让 `EvalReport` 还能 `equals`：全文进报告，一次换行差异就会让「同输入同报告」破产。要对照原文，去重放 subject，或打开 `originRunId` 对应的轨迹文件。

T5 还有另一条投影戏：一次 `[DENIED]` 工具调用同时进 `AuditEvent`、`RunMetrics.deniedToolCalls`、轨迹 observation。那是「三种投影」的演示，不是本篇的导入源。导入用的是 ERROR run——只有失败轨迹才变成用例。

---

## 6. 最小代码或实验

导入 + 谱系，三行：

```java
EvalDataset dataset = EvalDataset.empty();
int imported = dataset.importFailures(
        List.of(failedTrajectory),
        -0.4,
        t -> new Expectation.Contains("summary"));
EvalCase mined = dataset.cases().get(0);
// mined.originRunId().equals(failedTrajectory.runId())
// mined.prompt() == 首条 USER
```

门禁三态：

```java
EvaluationRunner runner = new EvaluationRunner();
EvalReport first = runner.evaluate(dataset, subject, null, 1.0);
// first.verdict() == BASELINE_ABSENT

EvalReport second = runner.evaluate(dataset, subject, first, 1.0);
// second.verdict() == PASS   （同 subject，不低于基线）

EvalReport bad = runner.evaluate(dataset, brokenSubject, second, 0.5);
// 地板过了但低于基线 → FAIL
```

`caseId` 生成 `case-%04d`，撞上手工占用号就跳号。重复 `add` 同一 id 直接 IAE——门禁按 caseId 对齐，重复键比静默覆盖更诚实。

轨迹从哪来：Stage 14 的 `TrajectoryReplayer.loadAll` 读 JSONL，坏行带行号；`SamplingPolicy` 可以先按状态集滤一圈，再交给 `importFailures`。示例为了零 LLM、零落盘，当场用空 scripted Mock 跑出 ERROR 轨迹，不走文件。生产路径是「值班看见 ERROR run → 轨迹已在 JSONL → 翻译函数写一条 Contains → 数据集 +1」。

`save` / `load` 双重 round-trip 后 record 相等。门禁生命线的另一半：不仅同 subject 两跑报告相等，数据集本身也能进出磁盘不失真。改了 expectation 字段名却不升 `API_VERSION`，加载端会按旧契约读炸——这是故意的，和轨迹 golden 字段快照同一类绊线。

---

## 7. 常见误区

1. **「评估集可以替代单测」** —— 单测护框架，评估护组合。互相替代会两头空。
2. **「上 LLM-as-judge，断言更聪明」** —— v1 门禁的生命线是同输入同报告。judge 的非确定性会让发布线抖动。槽位留着，本阶段不开。
3. **「框架自动从失败文本生成期望」** —— 失败形态到断言是领域知识。`expectationFor` 是人，不是默认通译。
4. **「首跑没有基线就放行」** —— 地板每次都生效。0% 也 FAIL。`BASELINE_ABSENT` 只表示「高于地板但无可比对象」，接不接受首跑提升是装配策略。
5. **「subject 一崩，整次评估作废」** —— 评估的职责是全量判决。崩在 case 1 对其余 7 个一无所知，才是失败。和 Fallback「换供应商重试」刻意相反：评估不重试，重试会掩盖被评估对象。
6. **「originRunId 可以事后补」** —— 手工用例一开始就是 null。事后猜「大概是那场事故」写进去，谱系比没有更糟。有轨迹再 import，没有就保持空。

---

## 8. 和相邻概念的区别

```text
EvalDataset（18）vs 全仓单元测试
  单测：确定性、护 Java、毫秒、免费
  评估：可复现的行为断言，护 prompt/模型/工具，按 token 计费（真模型时）

EvalReport.verdict vs PromptManager.rollback
  verdict 决定「能不能升」
  rollback 决定「升坏了怎么退」
  门禁不过不上 stable——13 + 18 的最小发布闭环

importFailures vs SamplingPolicy（14）
  SamplingPolicy：导出前按状态/reward/哈希采样
  importFailures：事后从已有轨迹挖回归用例
  一个管「进训练集」，一个管「进回归集」

Expectation vs RewardSource（14）
  Reward 给轨迹打分（训练信号）
  Expectation 给重放结果做门禁（发布信号）
  低 reward 可以成为导入条件，但判定器本身不是 reward
```

---

## 9. 我的设计判断

最重的一条：**Agent 的回归防线必须长在失败现场上，而不是长在作者的想象力上。** 手工 case 有用（示例里的 `case-hand-1`），但线上真实 ERROR / MAX_STEPS 才是分布里会再出现的坑。`originRunId` 让「这条用例为什么存在」可追溯——修 bug 的人和半年后看数据集的人，看见的是同一场事故。

其次是可复现优先于「评得像人」。确定性四断言覆盖不了文采，覆盖得了「修死循环却多调了 15 次工具」「终答不再含必现词」。门禁先活下来，再谈 judge。

再次是里程碑独立。`EvaluationRunner` 不依赖 `MetricsCollector` / `CostMeter`。评估读轨迹，只在 compile 依赖里加 `agent-trace-export`。这是蓝图写过的「M18.4 独立」，也是和 Stage 17 能交错实施的原因之一：17 线用 `Tool` 契约写编码工具，18 线用轨迹读失败样本，交集仍只有已经稳定的 core / 已收口的 trace-export。

FAIL 有两个来源，比较在 `passRate` 级，不在 case 级。修好 case-0007 却弄坏 case-0003，总通过率下降，门禁照样红。case 级 diff（「哪一条相对基线翻转」）蓝图列为 v2。v1 的 `failureDetails()` 已经够值班列修复清单：id、期望描述、实际摘要。

---

## 10. 面试表达

> 「Agent 的 bug 多数不在 Java 代码里。我把失败轨迹当成回归用例来源：`EvalDataset.importFailures` 收下 `ERROR` / `MAX_STEPS_EXCEEDED` / 低 reward，prompt 取首条 USER，`originRunId` 指回事故。判定器 v1 只做确定性断言，门禁才可复现。`EvalReport` 三态：跌破阈值或低于基线是 `FAIL`，无基线且高于地板是 `BASELINE_ABSENT`，不假装对比。和 Stage 13 的 canary 合在一起：门禁不过，不上 stable。」

---

## 11. 下一篇连接什么

运营层的五块——指标、预算、路由、版本、评估——到此收齐。下一篇不再拆模块，而是把 18 周整条线收回来：同一 Runtime、三个 Profile、一层运营，加上 M9 的 5 / 15 / 30 分钟面试叙事。那是 v1.0 的收官，也是本系列的最后一篇。

→ [stage-18-article-11-v1-architecture-review.md](stage-18-article-11-v1-architecture-review.md)
