# Stage 14 RL 轨迹产出层 · 概念学习笔记

> 配合源码 `projects/java-agent-framework/agent-trace-export` 阅读。
> 本笔记从零建立「Agent 怎么从反馈里变聪明」的概念，并落到真实代码。
> 写作方式：先建概念全景，再读真实数据模型，逐包下钻。

---

## 设计原则代号速查（读源码注释会反复遇到）

代码里用 D1~D5、M14.x 给设计决策编号，先记下来，读代码时才知道「为什么这么写」：

| 代号 | 含义 |
|---|---|
| D1 | **边界记录**：记录策略（policy）真正看到的，不重写、不解释（如工具返回 `[ERROR]` 原样存） |
| D3 | **双通道**：同一条轨迹存两份视图 —— `messages`（给 SFT/DPO 训练器吃）与 `steps`（给过程分析/回放） |
| D4 | **失败是资产**：模型调用崩了、跑出 ERROR，照样记录，且当作训练素材 |
| D5 | **不编造数据**：没算出来的 reward / metadata 就是 `null`，记录器绝不替你编一个值 |
| M14.1 | 记录层（record/）：装饰器无侵入捕获边界 |
| M14.2 | 奖励与导出（reward/ + export/） |
| M14.3 | 采样回放（sample/ + replay/）与 workflow 适配 |
| M14.4 | 反馈与 DPO（feedback/） |

---

## 第 1 课：Agent 是怎么「变聪明」的

### 1.1 我们在聊什么：LLM Agent
- **LLM（大语言模型）**：给一段上文，续写下一段。本质是「文字接龙机器」。
- **Agent**：`LLM + 能调工具（查库/调 API/算数） + 能多轮循环（思考→行动→观察→再思考）`。
- 它解决一个任务从头到尾经历的步骤，叫一条**轨迹（Trajectory）**。

### 1.2 轨迹（Trajectory）= Agent 的「黑匣子记录」
一条轨迹 = Agent 干一件事的完整录像：
- 用户最初说了什么（输入）
- 每一轮：模型说了啥、调了什么工具、工具返回啥、环境给了啥反馈
- 最后结果（成功 / 失败 / 报错）

**为什么先记它**：只有先把「它当时怎么做的」完整存下来，后面才能分析、打分、拿去训练。

### 1.3 为什么要「学习」：从反馈到改进
靠人写死规则覆盖不了所有情况。更好思路：
> 让 Agent 跑很多任务 → 收集「哪些做法好、哪些差」的信号 → 用信号去调模型的「行为倾向」。

这就是**强化学习（RL）**核心：不告诉模型「标准答案是 X」，而是告诉它「这样做得分高/低」，它自己调整。

### 1.4 奖励（Reward）= 把「好/坏」变成数字
- `Reward` 给一条轨迹（或一步）打分：`+1` 好、`-1` 搞砸、`0` 无所谓。
- 谁给分（三槽位 `RewardSource`）：
  - **规则** `RuleReward`：如「答对 +1，调了不该调的工具 -0.5」（默认 +1/-0.5/-1/0，可定制）
  - **人工**：人看一眼说好/坏
  - **裁判模型** judge：用另一个 LLM 当评审
- 打分结果用 `RewardResult` 装（`applyTo` 不可变，记录不被悄悄改）。

### 1.5 偏好学习（Preference Learning）= 「A 比 B 好」比打绝对分更自然
给绝对分难（什么是 +0.7？），让人比较「A、B 哪个好」容易。
- **偏好对（Preference Pair）**：同一问题让 Agent 生成两个答案 A、B，人标注「A 比 B 好」→ 一对 `(chosen, rejected)`。
- 拿比较数据训练，让模型**倾向 chosen、远离 rejected** → 这就是 **DPO（Direct Preference Optimization，直接偏好优化）**，主流「对齐」方法。
- 代码：`PreferencePair` / `TrajectoryPairBuilder`（拼成对）/ `DpoExporter`（导出 DPO 格式）/ `HumanFeedback` / `ConsoleAnnotator`（人标注）。

### 1.6 采样（Sampling）= 别把所有轨迹都存
跑几千次任务，不可能每条都存、都训练。
- **采样策略**：按概率/规则挑一部分留下。**负样本（失败/ERROR）也必须留**，否则模型只见过成功、学不会避错。
- 代码：`SamplingPolicy`（默认全采且含 ERROR 负样本）/ `TrajectorySampler`（用 hash 决定留不留，跨 JVM 一致、可审计）。

### 1.7 导出（Export）= 存成别的系统能读的格式
训练框架（PyTorch 等）不认 Java 对象，要吃文件（JSONL）。
- **导出** = 把 Java 轨迹对象序列化成规范 JSON 文件，附「信封」（`api_version`、schema 说明）给下游消费。
- 代码：`TrajectoryCodec`（手写 JSON 树、40 字段 snake_case、信封 `api_version=v1` —— **契约唯一实现点**）/ `JsonlTrajectoryWriter`（一行一条，坏行报错）/ `TrajectoryExporter`（门面：打分→采样→落盘）。
- 跨语言验证：`examples/scripts/consume_trajectory.py`（纯 Python 标准库）读 JSONL 做统计、物化 SFT 样本，证明「Java 产出、Python 训练」链路通。

### 1.8 回放（Replay）= 把记录「重演」成训练样本
训练时只有最终答案不够，还要把当时的**上下文序列**重建出来喂给模型。
- **回放** = 把存下的轨迹，重新拼成「模型输入→输出」的训练样本（如把工具调用前的对话重建）。
- 见 M14.3 `replay/` 包。

### 1.9 一条流水线
```
Agent 运行
   │  RecordingSession 记录每一步
   ▼
Trajectory（一条完整轨迹）
   │  TrajectoryRecorder 收集
   ▼
Reward 打分（规则 / 人工 / judge）
   │  RewardSource → RewardResult
   ▼
Sampling 采样挑出要留的
   │  SamplingPolicy → TrajectorySampler
   ▼
Export 导出成 JSONL
   │  TrajectoryCodec + JsonlTrajectoryWriter + TrajectoryExporter
   ▼
├─→ Replay：重建训练样本（SFT / RL）
└─→ Feedback：人标注偏好对 → DpoExporter → DPO 训练
```

### 1.10 这套设计的「价值观」
- **轨迹是唯一真相源**：一切学习基于忠实记录。
- **打分与运行解耦**：Reward 不写死在 Agent 里，事后评。
- **契约稳定**：JSON 格式唯一实现点，跨语言不破。
- **可审计**：hash 采样、fail-loud，不静默丢数据。

---

## 第 2 课：一条轨迹在代码里长什么样（包 `trajectory/`）

`trajectory/` 是所有其他包的「共同语言」：record 造它、reward 评它、sample 筛它、export 序列化它、replay 重建它、feedback 配对它。不懂它，看其他包都是雾里看花。

### 2.1 顶层 `Trajectory`（不可变 record，8 字段）
`Trajectory.java:36`
```java
public record Trajectory(
        String trajectoryId,   // UUID 唯一标识
        String runId,          // 运行 id（调用方给或 recorder 生成，采样 hash 据此）
        TrajectoryMetadata metadata,
        AgentState.Status status,   // 复用 Stage 12 的词汇（一个词汇表）
        List<TrajectoryStep> steps, // 通道B：一步步过程
        List<ChatMessage> messages, // 通道A：完整对话（训练器吃）
        Double reward,              // 结果奖励，打分前是 null（D5）
        String rewardSource         // 奖励来源，打分前是 null
) {
```

**双通道（D3）**（`Trajectory.java:12`）：
- `messages` = 逻辑完整对话 `system→user→assistant(toolCalls)→tool→...→assistant`，SFT/DPO 训练器消费。**不是从 `AgentState` 来的**，是从边界记录拼的，保证一致。
- `steps` = 每次模型调用的 State/Action/Observation/Reward/Done，给过程分析与回放。
- 两视图都对，回答不同问题。

**不编造（D5）**（`Trajectory.java:24`）：`reward` / `rewardSource` 在 `RewardSource` 打分前是 `null`，记录器绝不编值。

### 2.2 一步 `TrajectoryStep` = 一次模型调用
`TrajectoryStep.java:36`
```java
public record TrajectoryStep(
        int index,                       // 从 1 开始的步序号
        List<ChatMessage> state,        // 这次请求时模型真正看到的消息（post-ContextBuilder）
        StepAction action,              // 模型这次做了什么决定
        List<ToolObservation> observations, // 这次响应后执行的工具结果（全部，并行调用属同一步）
        Double reward,                  // 步级奖励，v1 永远是 null（过程奖励会编造 → D5）
        boolean done,                   // 只最后一步 true
        DoneReason doneReason           // 终止原因，非末步为 null
) {
```
- `state` 是**每步完整快照**（非增量）：精确性胜过 O(n) 存储（v1 默认最多 10 步）。
- 压缩/窗口化时 `state` 可能 ≠ `AgentState.getMessages()`（完整历史）。重点：「策略真实输入是模型看到的，不是完整历史」。

### 2.3 Action 与 Observation：一步的两个半边
**`StepAction`**（`StepAction.java:25`）：模型的「决定」
```java
public record StepAction(
        String content,        // 文本输出（仅工具调用/错误时为 null）
        List<ToolCall> toolCalls,
        String finishReason,   // "stop" / "tool_calls" / "length" / "error"
        ModelResponse.TokenUsage usage,
        long durationMs
) {}
```
- **D4 失败是资产**（`StepAction.java:15`）：模型调用炸了 → 记 `finishReason="error"` + `null` content/toolCalls，**失败本身也是训练数据**。

**`ToolObservation`**（`ToolObservation.java:24`）：工具的「返回」
```java
public record ToolObservation(
        String toolCallId, String name, String content,
        boolean success, long durationMs
) {}
```
- **D1 边界记录**（`ToolObservation.java:7`）：`content` **逐字记录**（verbatim），含 `[ERROR]...` / `[DENIED]` 治理文本，轨迹从不重写或解释。
- `success` 的诚实含义：执行器**返回**而非抛异常。治理 `[DENIED]` 算 `success=true`（循环视角是正常观察）；只有执行器级异常才 `false`。

### 2.4 `DoneReason`：为什么停
`DoneReason.java:14` —— 4 值：`DONE` / `MAX_STEPS_EXCEEDED` / `ERROR` / `CANCELLED`。从 `AgentState.Status` 映射，非终止态（IDLE/RUNNING/EXECUTING_TOOL）映射 `null`。

### 2.5 `TrajectoryMetadata`：身份证 + 账单
`TrajectoryMetadata.java:38`：`agentName` / `promptSha256`（系统提示 sha256 指纹，平等对待代码 agent 与 YAML agent，不依赖 PromptManager）/ `tools` / `maxSteps` / `startedAt` / `finishedAt` / `durationMs` / `tokenUsage` / `lastError` / `custom`。未 attach 配置时字段 `null`（诚实，不编）。

### 2.6 `TrajectorySteps`：会被反复用到的「重建算法」
`TrajectorySteps.java:29` 的 `logicalMessages(steps)` 把 `steps` 重拼成完整对话（即 2.1 的 `messages` 通道来源）。
- **M14.3 关键**：`RecordingSession`（生产）与 `ReplayView`（校验）共用这**唯一算法**。若「写」和「校验」两套拼法，对不上就是静默数据损坏（bug 农场）。

### 2.7 一个贯穿例子
用户问「北京今天天气？」
- step1：`state`=系统提示+问题；`action`=`tool_calls` 请求 `getWeather("Beijing")`；`observations`=`ToolObservation(getWeather,"晴 26℃")`
- step2：`state`=上面+工具结果；`action`=`stop` + `content="北京今天晴，26℃"`；`done=true`, `doneReason=DONE`
- 顶层 `messages` = `[系统, 用户, assistant(调工具), tool(结果), assistant(答案)]`（训练器吃）
- 顶层 `steps` = [step1, step2]（分析/回放）
- `reward` 此时仍 `null`，等 `RewardSource` 打分后填

---

## 第 3 课：`record/` 包 —— 轨迹怎么被一点点填出来（M14.1）

### 3.1 核心问题
一条 `Trajectory` 怎么来的？最笨：在 Agent 业务代码插 `recorder.record(...)`（污染业务、易漏）。优雅：**装饰器模式 + 边界捕获**——三装饰器套外面「旁路偷看」，业务代码零改动。

### 3.2 三个装饰器，各守边界
- `RecordingAgent`（套 `Agent`）：开/关 session（`run` 前后 try/finally），`attach(config)` 填 metadata。
- `RecordingModelClient`（套 `ModelClient`）：捕获 `(request, response)` = (State, Action)。
- `RecordingToolExecutor`（套 `ToolExecutor`）：捕获 `(toolCall, result)` = Observation（逐字，D1）。
三者只调 `delegate` 干正事 + 顺手喂 session，不实现业务逻辑。

### 3.3 边界为什么选在这（D1）
`RecordingModelClient` 必须最外层 `ModelClient`：`ReActAgentLoop.buildRequest` 调模型前跑 ContextBuilder（压缩/窗口），只有此层能看到「模型真实输入（post-compression）」；从 `AgentState` 录会静默丢差异。工具结果逐字存（含 `[ERROR]`/`[DENIED]`）。

### 3.4 一次 run 的时间线
`open` → `attach(config)` → `delegate.run` → 每模型调用 `onModelCall` → 每工具调用 `onToolCall` → `finish(status, lastError)` 组装 `Trajectory`。

### 3.5 RecordingSession 内部「攒步」（pending 机制）
- `pendingState/pendingAction/pendingObservations` 三字段（`RecordingSession.java:47`）。
- `onModelCall`：先 flush 上一步（→正式 step），再开新 pending；下一个模型调用 = 上一步闭环。
- `onToolCall`：往 pending.observations 追加（并行工具调用属同一步）。
- `onModelError`：失败调用本身即终步，`finishReason="error"`、`done=true`、`DoneReason.ERROR`（D4）。
- `finish`：flush 末步 → 组 metadata（聚合 token usage、durationMs）→ `TrajectorySteps.logicalMessages(steps)` 重建 messages（呼应 2.6 唯一算法）→ `reward=null`（D5）。

### 3.6 诚实/安全设计
- 已 finished 再来事件 → `IllegalStateException`（防重复 finish）。
- `close()` 未 finish 自动 finish 成 ERROR（「丢一条轨迹比诚实标错的更糟」）。
- 非终止态到 finish → 归一成 ERROR（不编假终结）。
- `runId` 唯一：`usedRunIds` 防重（一个 run 一条轨迹，重复污染训练数据，`TrajectoryRecorder.java:50`）。

### 3.7 线程模型（v1 诚实边界）
session 用 `ThreadLocal` 绑开它的线程（`TrajectoryRecorder.java:31`）。ReAct loop 同步单线程串行跑模型+工具，捕获天然线程封闭——故装饰器必须最外层（内层 timeout 装饰器可能跳线程）。并发需每线程一个 recorder。

---

## 第 4 课：`export/` 包 —— 轨迹怎么变成文件（M14.2）

### 4.1 为什么需要 export（接 1.7）
训练框架不认 Java 对象，要吃 JSONL 文件。export = 序列化 + 落盘 + 门面编排。

### 4.2 TrajectoryCodec：契约唯一实现点（D8）
`TrajectoryCodec.java:25`：v1 导出契约唯一实现点，**手写 JSON 树**而非 record 上 Jackson 注解。
- 为什么手写：契约要明确到每个字段名（snake_case，训练生态约定），金标准字段名快照测试锁定；**改字段名 = 升 api_version，不是改一行**（D2：训练数据长期资产）。
- 信封 `TrajectoryCodec.java:47`：`api_version="v1"`、`kind="Trajectory"`。
- 顶层字段（toJson `:52`）：trajectory_id / run_id / metadata / status / done_reason / reward / reward_source / messages / steps（双通道都导出，呼应 D3）。
- 双向 round-trip：fromJson `:89` 校验 api_version，不支持/缺必填即抛。
- v1 刻意边界（注释 35-42）：多模态 parts 不导出（仅文本无损）；null/空集合省略加载再默认；done_reason 顶层写+加载从 steps 派生不盲信。

### 4.3 JsonlTrajectoryWriter：一行一条，append-only，fail loud
`JsonlTrajectoryWriter.java:14`：一目录一文件 `trajectories.jsonl`，一行一条，append-only。
- 失败语义 D4：IO / 坏行都抛（fail loud）——静默丢/跳轨迹比异常更糟（呼应 Stage 13 webhook 幂等）。
- loadAll `:55`：空文件→空列表；容忍末尾空行；坏行带行号抛 `:70`。

### 4.4 TrajectoryExporter：门面 score→sample→persist
`TrajectoryExporter.java:13`（D4+D5+D8）。
- record() `:50`：rewardSource.score(traj).applyTo(traj) 打分（不可变 wither）→ sampler.shouldExport 决定 → writer.append 落盘；拒绝 skippedCount++（从不静默）。
- write() `:63`：绕过 reward/sampling（手动/重导出/测试）。
- load() `:70`：round-trip。IO 失败传播（fail loud D4）。

### 4.5 WorkflowTrajectoryAdapter：workflow 映射成轨迹（M14.3 适配）
`WorkflowTrajectoryAdapter.java:19`：兑现 Stage 5 StepRecord 承诺「Stage 14 直接消费」。
- 诚实粒度：workflow 无模型调用 → NODE-LEVEL 投影（一 StepRecord = 一 step），state = 节点可见黑板视图（非 post-ContextBuilder 输入）。
- 终态映射 `:54`：SUCCEEDED→DONE、FAILED→ERROR、CANCELLED→doneReason CANCELLED（status 仍 ERROR，AgentState 无 CANCELLED，复用 loop 词汇）、PAUSED 拒绝（暂停非完成轨迹先 resume）。
- 收益：映射进 Trajectory 后 reward/sampling/JSONL/replay 对 workflow 免改复用。
- messages = TrajectorySteps.logicalMessages(steps) `:92`（复用 2.6 唯一算法）。

### 4.6 跨语言消费（呼应 1.7）
Java 写 → Python `consume_trajectory.py`（纯标准库）读：校验信封 api_version → 统计 → 物化 SFT 样本。正是「契约唯一实现点」价值：下游只认 JSON 形状不认 Java。

---

## 第 5 课：`reward/` + `sample/` —— 打分与采样怎么作用在轨迹上（M14.2）

### 5.1 共同点
`Trajectory.reward` 初始 null（D5）。reward 负责「打分填上」，sample 负责「填完决定留不留盘」。两者都**事后**（run 结束后）作用，不改运行过程。

### 5.2 RewardSource：可插拔打分器接口
`RewardSource.java:19`：单方法 `score(traj)->RewardResult`，必须确定性、无副作用（同轨迹同分）。
三槽位（`RewardSource.java:8`）：RuleReward(v1, 终态映射) / Human feedback(M14.4, 按 trajectoryId join 的标注 sidecar，append-only 不改写轨迹) / LLM-as-judge(v2 扩展点)。
红线：步级奖励不是本接口事，v1 保持 null（编造过程奖励毒化训练数据 D5）。

### 5.3 RuleReward：默认打分
`RuleReward.java:15` 默认：DONE +1.0 / MAX_STEPS_EXCEEDED -0.5 / ERROR -1.0 / CANCELLED 0.0。
映射是配置不是法律，`withReward(reason,value)` `:39` 返回定制副本（不可变）。
score() `:46` 诚实：doneReason==null（空 steps）→ (0.0,"rule","no steps recorded") 不编分（D5）；有 reason 无规则 → 0.0+"no rule for X"。

### 5.4 RewardResult：打分结果 + 不可变 wither
`RewardResult.java:12` record (reward, source, explanation)：source 不可 blank；explanation null→""。
applyTo `:30`：返回**新 Trajectory** 带 reward/source，原对象不动（record 不可变，同 Stage 6 Checkpoint）。即 TrajectoryExporter.record 里 `score(traj).applyTo(traj)` 用到的（呼应 4.4）。

### 5.5 SamplingPolicy：事后存储决策
`SamplingPolicy.java:7`：记录 always-on 且便宜，采样是**事后存储决策**（run 多好只有结束才知道）。
默认宽松 `all()` `:43` 全留**含 ERROR**（失败 run 是负样本资产，DPO 对的 rejected 半边）；过滤显式配置，从不静默默认。
字段 `:27`：sampleRate(0-100)/seed/statuses(空=全留)/minSteps/maxSteps/minReward(设了则未打分 null 被拒，fail-closed)。

### 5.6 TrajectorySampler：确定性、可审计 hash 采样
`TrajectorySampler.java:50`：`floorMod(runId.hashCode() ^ (int)seed, 100) < rate`。
**为什么用 String.hashCode 而非 Random**：hashCode 是 JLS 规定，同 runId+seed 跨 JVM/重跑一致；「为什么这条没导出」永远可重算 hash 回答（可审计）。
shouldExport `:29`：pass-through，结构检查先、rate 最后；minReward 设了但 reward==null 则拒（fail-closed）。

### 5.7 串回第 4 课
DONE → RuleReward +1.0 source="rule" → applyTo 出带分新轨迹 → sampler.shouldExport（默认 all 全留）→ writer.append。呼应 1 的 Reward→Sampling→Export 三步。

---

## 第 6 课：`replay/` + `feedback/` —— 回放重建与偏好对 / DPO 导出（M14.3/M14.4）

### 6.1 replay ≠ 重跑（D7）
`ReplayView.java:15`：LLM 非确定性，忠实重演不可能。replay 真义：①校验记录结构一致（fail-fast 不猜）；②暴露每步模型真实 state/action/observations（调试/标注浏览 M14.4）。

### 6.2 ReplayView：逐步校验视图
`ReplayView.java:35` 构造时校验（`:98 verify`）：step index 连续 1..n；done 恰一次且在末步（空 step 轨迹无 done 合法）；done 步须带 doneReason、非 done 步不能带；**messages == 从 steps 重建**（复用 TrajectorySteps.logicalMessages，第2课2.6 唯一算法被校验者使用，双通道篡改任一处此处暴露）。访问器 stateAt/actionAt/observationsAt + describeStep 摘要。

### 6.3 TrajectoryReplayer：load + 校验
`TrajectoryReplayer.java:18`：loadAll/loadFirst 经 JsonlTrajectoryWriter.loadAll → 每条 ReplayView.of 校验。坏 JSON 行带行号、结构不一致在 of 内失败。loud by design（回放靠猜的轨迹比拒绝更糟）。

### 6.4 PreferencePair：存引用不存对话（D6）
`PreferencePair.java:26`：两 rollout 间偏好（同 prompt）。存 ids 不嵌对话（一条轨迹可入多对，存一次投影多次，同 DagSpec）。校验 A/B distinct、preferred∈{A,B}。v1 来源 rejection-sampling pairing（同 prompt 两 rollout 取更好），不同 prompt 不可比（TrajectoryPairBuilder 强制）。

### 6.5 TrajectoryPairBuilder：只有同 prompt 才配对
`TrajectoryPairBuilder.java:19`：promptPrefix = system+首个 user message 共享前缀；responseSuffix = 前缀后实际回答；requireSharedPrompt 前缀须精确相等否则 fail-fast（苹果比橘子无意义）。

### 6.6 DpoExporter：物化 DPO 格式
`DpoExporter.java:33`：一行一对 {prompt, chosen, rejected} 消息序列（DPO trainer 直接输入）。解析引用到轨迹池；悬空引用 fail-loud；导出时再验 prompt 前缀（sidecar 与池可能独立演化）；responseSuffix 可空（模型崩无回答也是合法偏好）；落盘 preferences.jsonl。

### 6.7 人怎么标注：ConsoleAnnotator + HumanFeedback
`ConsoleAnnotator.java:33`：并排显示两同 prompt rollout（ReplayView 校验走查）+ 读 a/b/skip → 追加 SIDECAR。**轨迹文件从不碰**（标注分离 append-only，D5/D6）。`HumanFeedback.java:19`：单轨迹 1-5 评分，只进 sidecar 不进轨迹（publish-only 纪律同 Stage 13 PromptManager）。

### 6.8 串起流水线（呼应 1.9）
同 prompt 两 rollout → 人标更好者 → PreferencePair 进 sidecar → DpoExporter 物化 {prompt, chosen, rejected} → DPO 训练。即第1课图末两分支（Replay + Feedback→DPO）落地。

---

## Stage 14 概念地图总览（完结）

| 包 | 里程碑 | 一句话 |
|---|---|---|
| `trajectory/` | 地基 | 一条轨迹的数据模型：双通道 messages(训练) / steps(分析)，不编造 |
| `record/` | M14.1 | 装饰器在边界偷看，零侵入把 run 攒成 steps |
| `export/` | M14.2 | 手写 JSON 契约(v1) 序列化落盘，门面 score→sample→persist，workflow 也复用 |
| `reward/`+`sample/` | M14.2 | 确定性打分(默认看终态) + 跨 JVM 恒一致 hash 采样(含 ERROR 负样本) |
| `replay/` | M14.3 | 校验+暴露代替重跑，守住双通道一致性 |
| `feedback/` | M14.4 | 同 prompt 两 rollout 比偏好，存引用、导出 DPO 的 {prompt,chosen,rejected} |

**贯穿的设计纪律**：D1 边界记录 / D3 双通道 / D4 失败是资产+fail loud / D5 不编造 / D7 不重跑只校验 / D8 契约唯一实现点。

---

## 第 7 课：从业务角度理解：Agent Runtime 为什么需要轨迹产出组件

### 7.1 Agent 不依赖它也能运行，但上线后很难持续改进

一个最小 Agent Runtime 可以直接完成：

```text
接收任务 → 调用模型 → 调用工具 → 返回结果
```

它不接 `agent-trace-export` 也能工作。但生产环境还会遇到这些业务问题：

- 任务失败时，为什么失败？模型判断错、工具报错，还是参数错？
- 任务“完成”了，结果真的好吗？
- 每天产生大量 run，哪些值得人工分析？
- 新模型是否比旧模型好？
- 用户更喜欢哪种回答？
- 线上积累的经验怎样交给训练团队？

如果只保留最终回答，Agent 就是黑盒；轨迹组件把一次运行的过程变成可分析、可评估、可训练的数据。

### 7.2 它在公司业务链路中的位置

```text
线上业务
  ↓
Agent Runtime：执行任务
  ↓
agent-trace-export：记录、评分、筛选、导出
  ↓
数据/评估/模型训练团队
  ↓
训练新模型或优化 Prompt、工具、策略
  ↓
新版本回到 Agent Runtime
```

| 使用方 | 关心什么 | 主要消费能力 |
|---|---|---|
| Runtime/研发 | 哪里出错、如何调试 | `record/`、`replay/` |
| 业务/评估 | 结果好不好 | `reward/`、`feedback/` |
| 数据/训练团队 | 数据能否训练 | `export/`、`trajectory/` |
| Agent 平台 | 数据量和存储成本 | `sample/` |

### 7.3 这个组件到底产出什么

它不是训练器，而是**后训练数据生产层**：

```text
Runtime Run
  → Trajectory
  → Reward / 人工偏好
  → Sampling
  → JSONL
```

| 产物 | 下游用途 |
|---|---|
| `messages` | SFT / 行为克隆 / 监督式样本 |
| 轨迹级 `reward` | RL、GRPO 等训练的结果信号 |
| `{prompt, chosen, rejected}` | DPO / RLHF 偏好训练 |
| `steps` | 过程分析、错误定位、回放；v1 不提供步级 reward |

本模块不做：模型训练、权重更新、模型部署、长期记忆写入、Prompt 自动优化。

### 7.4 “自进化”要分三种

**记忆进化：不改模型。**

```text
失败轨迹 → 总结经验 → 写入长期记忆 → 下次检索
```

**策略进化：不改模型。**

```text
历史轨迹 → 调整 Prompt / 工具规则 / 路由 / 工作流 → 新运行
```

**模型进化：必须有训练系统和模型。**

```text
轨迹 + reward / 偏好
  → SFT / DPO / RL 等训练
  → 更新模型权重
  → 新模型接回 Runtime
```

`agent-trace-export` 只负责训练前的数据侧；训练和权重更新属于 AI Infra/模型训练系统。

### 7.5 组件的业务价值：把“能运行”变成“能运营”

- `record/`：让系统知道 Agent 做了什么。
- `reward/`：把业务上的好坏转成信号。
- `sample/`：控制哪些数据进入下游。
- `export/`：建立 Runtime 与训练系统之间的稳定契约。
- `replay/`：离线查看和校验过去的行为，不重新跑模型。
- `feedback/`：把人的偏好变成 DPO 可用数据。
- `trajectory/`：作为所有环节共享的数据语言。

### 7.6 最终边界

```text
Agent Runtime       = 执行任务
Trace/Trajectory    = 记录行为与过程
Reward/Feedback    = 判断好坏
Training System     = 根据数据更新模型
Model               = 生成内容并作出决策
Memory/Policy Layer = 不改模型时的经验复用
```

> 这个组件让 Agent 的运行经验不会在 run 结束时丢失，并把经验整理成训练团队可以消费的数据；它本身不能让 Agent 自动学习，真正改变能力需要额外的记忆、策略优化或模型训练机制。

学习完结：从第 1 课「Agent 怎么变聪明」的概念全景，到第 7 课的业务定位与自进化边界，再到逐包源码落地，你已能对照 `agent-trace-export` 理解「Runtime 执行 → 轨迹数据 → 训练/策略改进」整条链路。后续若要动手，可从 `examples/` 下的 `TrajectoryExample` / `PreferenceAnnotationExample` 实跑起步。
