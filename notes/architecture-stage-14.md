# Stage 14 架构设计：RL 轨迹产出层

> 对应阶段：Stage 14 - RL 轨迹产出层（轨迹记录 / 奖励 / 采样 / 标准导出 / 回放 / 人工反馈与 DPO）
> 状态：✅ 已实现（2026-08-24 收口）—— M14.1~M14.4 全部完成：agent-trace-export 模块 29 类 73 测试，全仓 685 全绿；18 周规划验收 4 条全过（§9 对照）；实现记录见 §13-§16
> 模块：新增 `agent-trace-export` Maven 模块（模块名沿用 18 周规划与 README 既定命名），依赖 `agent-core`（ModelClient/ToolExecutor/Agent/ChatMessage/AgentState）+ `agent-workflow`（仅 WorkflowTrajectoryAdapter 用 StepRecord/WorkflowState/ExecutionResult）；`agent-model`（MockModelClient）/ `agent-memory`（压缩保真测试）为 test scope；零新第三方依赖（Jackson 已有）
> 前置：Stage 1-13 已完成（product 167 测试全绿；`StepRecord` javadoc 自 Stage 5 起就写着 "Stage 14 consumes these records directly"，本阶段兑现该承诺）
> 定位：**Java Agent Framework 与 AI Infra 主线（Mini VERL）的交汇点**——Agent Runtime 产出的运行轨迹成为 RL 训练系统可消费的标准化数据，形成「执行 → 数据 → 训练 → 更好的模型回到 Agent」闭环

---

## 1. 核心命题：让 Agent 的经验变成训练数据

Stage 1-13 造好的 Runtime 有一个隐含共识：**Run 结束就结束了**。状态留着供恢复（Stage 6）、审计留着供追责（Stage 9），但 Run 本身作为「数据」的价值从未被系统性收割。这个共识在数据闭环场景全线失效：

```text
Runtime 的四个隐含假设，在「数据闭环」场景全部破裂：
1. 用完即弃 -- Run 结束后消息历史只为下一轮对话服务；但每条 Run 都是真金白银
   （token 花了、人审了、工具副作用发生了），丢弃 = 把已支付的成本再付一遍
2. 内部格式自足 -- StepRecord/AuditEvent/VisibilityEvent 是框架私有格式（异构、
   面向调试），训练系统（VERL 类）读不懂；「能看」不等于「能学」
3. 结束即终点 -- DONE 只表示「跑完了」，不表示「跑好了」；没有 Reward 就没有 RL，
   没有区分度就没有梯度
4. 一次性消费 -- 一次 Run 只服务一次交互；两条 Run 的优劣对比没有留痕，
   就没有 RLHF/DPO 的偏好数据
```

Stage 14 的答案：**运行可记录**（State/Action/Observation/Reward/Done 五元组）、**好坏可度量**（可插拔 RewardSource：规则 / 人工 / 未来的 LLM judge）、**格式有契约**（版本化 JSONL，跨仓库消费）、**偏好可积累**（PreferencePair → DPO 训练格式）。

一句话（接 Stage 6-13 的递进叙事）：

```text
Stage 6  让 Run 能暂停-恢复
Stage 7  让 Run 能自动恢复
Stage 8  让 Agent 能记住
Stage 9  让 Agent 能被信任
Stage 10 让 Agent 能连接
Stage 11 让 Agent 能协作
Stage 12 让 Agent 能入驻团队
Stage 13 让 Agent 能被"搭出来"
Stage 14 让 Agent 的经验能变成训练数据 -- Run 结束不是终点，是数据的起点
```

### 与 AI Infra 主线的闭环（本阶段最高叙事价值）

```text
Java Agent Framework 产出轨迹（本阶段）
    ↓
轨迹文件（JSONL，标准契约）
    ↓
Mini VERL 消费轨迹（AI Infra 主线，另一仓库）
    ↓
RL 后训练（SFT / DPO / RLHF）
    ↓
优化后的模型回到 Agent（换 ModelClient 实现，框架零改动）
    ↓
更好的轨迹 → 持续优化
```

两条学习线在这里交汇：Agent Framework 负责产出合格数据，Mini VERL 负责消化数据产出模型。**本阶段只做左半边**——不实现任何训练逻辑（见 §12 范围控制）。

### 与相邻概念的三条边界（面试高频）

```text
Trace vs Trajectory（规划原文点名要讲清）：
  Trace 面向人和调试：StepRecord/AuditEvent/日志，框架私有格式、异构、无兼容性承诺
  Trajectory 面向训练系统：S/A/O/R/D 五元组，版本化信封、字段契约、长期资产
  同一次 Run 两者并存：trace 解释「发生了什么」，trajectory 回答「能学到什么」
  方向：Trajectory 从 trace + 边界事件派生；反向不承诺（不保证从 Trajectory 重建 Trace）

轨迹 vs 日志：
  日志是 append 的文本流（人读，无 schema 承诺，可以丢）
  轨迹是结构化的状态-动作序列（机器读，字段有语义契约，是训练资产）
  日志的重启丢失可接受；轨迹的静默丢失不可接受（fail loud，见 D4/D8）

录制 vs 采样：
  录制是运行时行为（内存 append，便宜，永远开）
  采样是存储决策（run 结束后裁决哪些值得持久化）
  为什么先录后采：结果好坏 run 结束才知道，事前采样必然丢掉「没想到会好」的轨迹
```

---

## 2. 复用清单：Stage 14 是第三次「组装阶段」（预检先行）

延续 Stage 12 教训、Stage 13 制度化的做法：**规划时就做复用预检**。本清单每行标注预检结论，含一处「预检发现的语义陷阱」。

| 能力需求 | 已有设施（阶段） | Stage 14 做什么 | 复用预检 |
|---|---|---|---|
| 消息历史 | `AgentState.messages`（2） | 与边界捕获对账的辅助源，**不是唯一来源** | ⚠️ 预检发现语义陷阱：`ReActAgentLoop.buildRequest` 先过 ContextBuilder 再发模型（Stage 8 压缩 / Stage 13 窗口生效时，**模型实见 ≠ state.messages 全量**），post-hoc 从 AgentState 派生轨迹在压缩场景失真 → 轨迹的 State 必须取模型边界捕获（D1） |
| 模型边界捕获 | RecordingModelClient 测试手法（12/13，测试内装饰器） | 生产化为 record 包一等公民 | ✅ 手法第四次复用（12 共享会话实证 / 13 persona 注入实证 / 本阶段升级为产品代码） |
| 工具结果 | `ToolExecutor.execute` 返回 String + `ChatMessage.tool` 入 history（2） | ToolObservation 从 ToolExecutor 边界捕获 | ✅ 直接兑现；含 `[ERROR]`/`[DENIED]` 文本（Stage 2/9 契约：模型看到什么轨迹就记什么，治理语义不二次加工） |
| run 生命周期 | `RunManager`/`Run`/`Checkpoint`（6） | 轨迹 runId 与 RunManager runId 对齐 | ⚠️ v1 单进程内存 session；checkpoint 跨进程 resume 的轨迹拼接（同 runId 续写）v2 |
| workflow 轨迹源 | `StepRecord` + `WorkflowState.trace`（5） | WorkflowTrajectoryAdapter 粗粒度映射 | ✅ 兑现 StepRecord javadoc「Stage 14 consumes these records directly」的承诺（javadoc 从 Stage 5 写到现在） |
| token 统计 | `ModelResponse.TokenUsage`（1） | metadata 汇总（prompt/completion/total 三项累加） | ✅ 直接兑现（字段已随每次响应携带，Mock 场景为 null 时记 0） |
| 审计对账 | `AuditEvent`（9） | observation 已含治理结果文本，对账校验器 v2 | ✅ 无需依赖 agent-security（messages 已是事实源）；跨源对账（轨迹 step ↔ AuditEvent）列为 v2 增强非验收项 |
| 配置指纹 | `PromptVersion`（13）/ `AgentConfig`（1） | metadata：agentName + systemPrompt sha256 指纹 + 工具名清单 + maxSteps | ⚠️ 弱耦合决策：不依赖 agent-product/PromptManager（保持模块依赖最小），sha256 指纹方案对代码构建与 YAML 构建的 Agent 一视同仁；PromptVersion 名字可经 metadata.custom 附加 |
| append-only 哲学 | `PromptManager` publish-only（13）/ Webhook eventId 幂等（13） | 轨迹文件 append-only；人工标注 sidecar 独立文件不回写原轨迹 | ✅ 哲学复用（标注数据与轨迹数据分文件，同「版本列表即审计轨迹」精神） |
| JSON 序列化 | `ChatMessage`/`ToolCall` Jackson 注解（1） | 直接 JSON 化 + 显式 snake_case 契约注解 | ✅ 兑现（@JsonInclude NON_NULL 已就位；ToolCall.arguments 本就是 JsonNode） |
| Mock 验收 | `MockModelClient` scripted（1） | 示例与测试零 LLM 依赖 | ✅ 同 Stage 8-13 验收手法；双 rollout 用两个不同 script 模拟好坏轨迹 |

**依赖方向**：`agent-trace-export -> agent-core + agent-workflow`（compile；workflow 仅 adapter 一个类用）；`agent-model` / `agent-memory` test scope。**零新第三方依赖**。

**装配顺序红线（预检新增）**：Recording 装饰器必须包在**最外层**（最贴近 ReAct 循环）——`Recording(Temperature(Fallback(primary, secondary)))` 而不是反过来。原因：内层装饰器（如 Timeout 的 CompletableFuture 路径）可能跨线程，session 线程绑定（v1）要求捕获发生在循环线程上。

---

## 3. 核心抽象（27 个，六组）

### 第一组：轨迹数据模型（trajectory 包，M14.1）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `Trajectory` | 数据 | 一条 run 的完整轨迹 record：trajectoryId + runId + metadata + steps + messages（全量对话）+ reward + status |
| `TrajectoryStep` | 数据 | 一步 = state_delta（该步新增消息）+ action + observations + reward + done + doneReason |
| `StepAction` | 数据 | 模型决策：content + toolCalls + finishReason + tokenUsage + durationMs |
| `ToolObservation` | 数据 | 工具结果：toolCallId + name + content + success + durationMs |
| `TrajectoryMetadata` | 数据 | agentName + promptSha256 + 工具名清单 + maxSteps + token 汇总 + 起止时间 + durationMs + custom kv |
| `DoneReason` | 数据 | DONE / MAX_STEPS_EXCEEDED / ERROR / CANCELLED（对齐 AgentState.Status 终态集） |

### 第二组：记录层（record 包，M14.1）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `TrajectoryRecorder` | 核心 | 会话管理与已完结轨迹持有者：open(runId) → RunSession |
| `RunSession` | 核心 | 一次 run 的录制会话（AutoCloseable）：接收边界事件，close(status, lastError) 组装 Trajectory |
| `RecordingModelClient` | 核心 | ModelClient 装饰器：捕获 (request, response) = (State, Action)——post-ContextBuilder 的模型实见 |
| `RecordingToolExecutor` | 核心 | ToolExecutor 装饰器：捕获 (toolCall, result) = Observation |
| `RecordingAgent` | 核心 | Agent 装饰器（糖衣）：自动 open/close session 并回填终态，装配方少写三行 |

### 第三组：奖励（reward 包，M14.2）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `RewardSource` | 接口 | score(Trajectory) → RewardResult（可插拔：规则 / 人工 / 未来的 LLM judge） |
| `RewardResult` | 数据 | reward 值 + source 标记（rule/human/model）+ 说明 |
| `RuleReward` | 核心 | 终态→分数规则映射，默认 DONE=+1.0 / MAX_STEPS_EXCEEDED=-0.5 / ERROR=-1.0 / CANCELLED=0，可配置 |

### 第四组：采样与导出（sample + export 包，M14.2/M14.3）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `SamplingPolicy` | 数据 | sampleRate(0-100) + seed + 状态过滤集 + 步数区间 + reward 阈值（全部可选，默认全采） |
| `TrajectorySampler` | 核心 | hash(runId+seed) 确定性裁决 shouldExport（不用 Random，可复现可审计） |
| `TrajectoryExporter` | 核心 | write(Trajectory) → JSONL 追加一行；load(Path) round-trip（供回放与测试） |
| `JsonlTrajectoryWriter` | 核心 | snake_case 显式契约序列化 + apiVersion/kind 信封（对齐 Stage 13 AgentDefinition 信封纪律） |
| `TrajectoryCodec` | 核心 | Trajectory ↔ JSON 树双向（exporter/replayer/测试共用，schema 契约唯一实现点） |
| `WorkflowTrajectoryAdapter` | 核心 | WorkflowState.trace（StepRecord 列表）+ ExecutionResult → 粗粒度 Trajectory |

### 第五组：回放（replay 包，M14.3）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `TrajectoryReplayer` | 核心 | load + 完整性校验（步连续 / done 恰一次 / delta 可重建 messages）+ 产出 ReplayView |
| `ReplayView` | 核心 | step-through 浏览：stateAt(i) 重建该步全量模型实见 / actionAt(i) / observationsAt(i) |

### 第六组：人工反馈（feedback 包，M14.4）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `HumanFeedback` | 数据 | 单条轨迹质量标注（trajectoryId + rating + notes + annotator）→ 独立 sidecar 文件 |
| `PreferencePair` | 数据 | (trajA, trajB, preferred) 引用不内嵌——存 ID 对，导出时物化 |
| `TrajectoryPairBuilder` | 核心 | 同 prompt 双 rollout 配对 + prompt 前缀一致性校验（不一致拒绝配对 fail-fast） |
| `DpoExporter` | 核心 | PreferencePair 集合 → DPO JSONL：{prompt, chosen, rejected} 消息序列物化 |
| `ConsoleAnnotator` | 核心 | 控制台标注器（展示两条轨迹终答 + 关键步骤，stdin 读 a/b/skip） |

### 3.1 关键接口草图

```java
// ---- 记录层（第二组的装配三件套 + 糖衣）----
public final class TrajectoryRecorder {
    public RunSession open(String runId);        // runId 可 null（自动生成）；同 runId 重复 open 拒绝
    public List<Trajectory> completed();         // 已完结轨迹（内存持有，v1 不落库）
}

public interface RunSession extends AutoCloseable {
    void onModelCall(ModelRequest req, ModelResponse resp, long durationMs);   // 装饰器回调：State+Action
    void onToolCall(ToolCall call, String result, boolean error, long durationMs); // 装饰器回调：Observation
    Trajectory close(AgentState.Status status, String lastError);              // 显式关（带终态），幂等拒绝
}

public final class RecordingModelClient implements ModelClient {
    public static RecordingModelClient wrap(ModelClient delegate, TrajectoryRecorder recorder);
}
public final class RecordingToolExecutor implements ToolExecutor {
    public static RecordingToolExecutor wrap(ToolExecutor delegate, TrajectoryRecorder recorder);
}
public final class RecordingAgent implements Agent {   // 糖衣：装配方不想手写 try-with-resources 时用
    public static RecordingAgent wrap(Agent delegate, TrajectoryRecorder recorder);
    // run(ChatMessage, AgentState) 内部：open → delegate.run → close(state.status, state.lastError)
}

// ---- 装配形态（v1 接线，agent-core 零改动）----
TrajectoryRecorder recorder = new TrajectoryRecorder();
ModelClient model = RecordingModelClient.wrap(            // recording 包最外层（线程绑定，见 §2 红线）
    TemperatureModelClient.wrap(FallbackModelClient.of(primary, secondary)), recorder);
ToolExecutor exec = RecordingToolExecutor.wrap(new DefaultToolExecutor(registry), recorder);
Agent agent = new SimpleAgent(cfg(model), new ReActAgentLoop(exec));
Agent recorded = RecordingAgent.wrap(agent, recorder);    // 可选糖衣

recorded.run("帮我查订单 8842");                            // run 结束即有一条完整 Trajectory

// ---- 奖励（第三组）----
public interface RewardSource {
    RewardResult score(Trajectory trajectory);
}

// ---- 采样与导出（第四组）----
public final class TrajectorySampler {
    public boolean shouldExport(Trajectory trajectory);   // rate: hash(runId+seed)%100 < rate
}
public final class TrajectoryExporter {
    public Path write(Trajectory trajectory);             // JSONL 追加；IO 失败抛异常不静默
    public Trajectory load(Path jsonlLineSource);         // round-trip
}

// ---- 回放（第五组）----
public final class TrajectoryReplayer {
    public ReplayView load(Path file);                    // 完整性校验 fail-fast
}
public final class ReplayView {
    public int stepCount();
    public List<ChatMessage> stateAt(int i);              // delta 1..i 重建该步模型实见全量
    public StepAction actionAt(int i);
    public List<ToolObservation> observationsAt(int i);
}

// ---- 反馈（第六组）----
public final class TrajectoryPairBuilder {
    public PreferencePair pair(Trajectory a, Trajectory b);  // prompt 前缀不一致 IAE
}
public final class DpoExporter {
    public Path export(Collection<PreferencePair> pairs);    // {prompt, chosen, rejected} JSONL
}
```

### 3.2 导出格式样例（v1 契约基准，验收以此为准）

> ⚠️ 口径修正（2026-08-24，见 §14 M14.2 实现记录）：下样例 steps 中的 `state_delta` 在实现中为 **`state`（该步模型实见全量快照）**——写时压缩类 builder 无法用 delta+dropCount 表达，v1 精确性优先；delta 编码留 v2（若轨迹步数显著增长）。其余字段与实现一致。

轨迹文件（`trajectories.jsonl`，一行一条轨迹）：

```json
{"api_version":"v1","kind":"Trajectory","trajectory_id":"traj-0192-0001","run_id":"run-8842",
 "metadata":{"agent_name":"support-bot","prompt_sha256":"a1b2c3...","tools":["order-query","weather-query"],
   "max_steps":10,"started_at":"2026-08-24T10:00:00Z","finished_at":"2026-08-24T10:00:04Z",
   "duration_ms":4210,"token_usage":{"prompt_tokens":1834,"completion_tokens":212,"total_tokens":2046},
   "custom":{"tenant":"acme"}},
 "status":"DONE","done_reason":"DONE","reward":1.0,"reward_source":"rule",
 "messages":[
   {"role":"system","content":"你是客服..."},
   {"role":"user","content":"帮我查订单 8842"},
   {"role":"assistant","tool_calls":[{"id":"c1","name":"order-query","arguments":{"orderId":"8842"}}]},
   {"role":"tool","tool_call_id":"c1","name":"order-query","content":"{\"status\":\"shipped\"}"},
   {"role":"assistant","content":"订单 8842 已发货"}],
 "steps":[
   {"index":1,
    "state_delta":[{"role":"system","content":"你是客服..."},{"role":"user","content":"帮我查订单 8842"}],
    "action":{"content":null,"tool_calls":[{"id":"c1","name":"order-query","arguments":{"orderId":"8842"}}],
      "finish_reason":"tool_calls","usage":{"prompt_tokens":812,"completion_tokens":45},"duration_ms":620},
    "observations":[{"tool_call_id":"c1","name":"order-query","content":"{\"status\":\"shipped\"}","success":true,"duration_ms":85}],
    "reward":null,"done":false},
   {"index":2,
    "state_delta":[{"role":"assistant","tool_calls":[...]},{"role":"tool","tool_call_id":"c1","content":"..."}],
    "action":{"content":"订单 8842 已发货","finish_reason":"stop","usage":{"prompt_tokens":1022,"completion_tokens":167},"duration_ms":810},
    "observations":[],"reward":1.0,"done":true,"done_reason":"DONE"}]}
```

DPO 偏好文件（`preferences.jsonl`，一行一个偏好对）：

```json
{"pair_id":"pair-0007",
 "prompt":[{"role":"system","content":"你是客服..."},{"role":"user","content":"帮我查订单 8842"}],
 "chosen":[{"role":"assistant","tool_calls":[...]},{"role":"tool",...},{"role":"assistant","content":"订单已发货"}],
 "rejected":[{"role":"assistant","content":"抱歉我不知道"}],
 "metadata":{"preferred":"A","annotator":"console","created_at":"2026-08-24T11:00:00Z",
   "traj_a":"traj-0192-0001","traj_b":"traj-0192-0002"}}
```

约定：`prompt` = 两轨迹共享的消真前缀（至首条 user 消息为止）；`chosen`/`rejected` = 各自余下完整消息序列；`reward` 仅终步携带（outcome reward，步级恒 null——不造假数据，见 D5）。

---

## 4. 关键设计决策（8 个）

### D1. 边界捕获而非循环改造：State = 模型实见消息（post-ContextBuilder）

```text
不做：改 ReActAgentLoop 加录制钩子（动 agent-core，破坏组装阶段纪律）
不做：只从 AgentState.messages post-hoc 派生（压缩场景失真——见 §2 预检陷阱行）
做：  RecordingModelClient/RecordingToolExecutor 边界装饰器 + RunSession 显式会话
      + RecordingAgent 糖衣，agent-core 零改动（装饰器哲学第 N 次兑现）

核心论点：RL 训练的策略输入是「模型实际看到的消息」，不是「Agent 记录的全量历史」。
Stage 8 压缩 / Stage 13 窗口生效时两者分叉：
  state.messages（全量） -> 供恢复/审计，可能已被 compaction 改写或超出窗口
  request.messages（实见）-> 策略的真实输入，训练数据的正确 State
ModelClient 边界是唯一忠实捕获点（ContextBuilder 在 loop 内、模型调用在边界外）。

这个设计的验收测试本身就是卖点：WindowContextBuilder(maxMessages=2) 下跑 3 步，
断言 step.state 重建结果 == RecordingModelClient 捕获的模型实见 != state.messages 全量
——「轨迹记的是策略输入」的可执行证明（M14.1 核心测试）。

线程模型诚实边界：v1 session 绑定 open 它的线程（ReAct 循环同步语义）；
装饰器顺序红线见 §2（recording 包最外层）。流式 chat/异步装饰器跨线程捕获 v2。
```

### D2. Trace ≠ Trajectory：同一事实源的两个投影，只有后者有兼容性承诺

```text
Trace（StepRecord/AuditEvent/VisibilityEvent）：框架私有、异构、面向调试、可随版本自由演化
Trajectory：标准化 RL 格式、版本化信封（api_version/kind）、面向训练系统
-> 训练数据是长期资产：格式漂移 = 历史数据报废。契约规则：
   1) 字段只加不删不改名（加字段可选字段，旧文件必须永远可读）
   2) api_version 升级 = 不兼容变更，必须提供迁移器（v2 的事，但承诺现在立下）
   3) golden file 测试锁 schema（M14.2）：字段名快照进版本库，无意漂移 CI 就红
-> 方向单向：Trajectory 从 trace + 边界事件派生；不承诺反向重建
```

### D3. 轨迹步 = 一次模型调用，不是一次工具调用

```text
step i = State(第 i 次请求实见) + Action(第 i 次响应) + Observation(其后全部工具结果)
与 ReAct 循环语义严格对齐（think+act 是一步，哪怕并行调 3 个工具）
-> 与 messages 的对应关系：action = assistant 消息，observations = 其后紧随的 tool 消息组
-> 双通道输出：messages（全量对话，SFT/DPO 消费方直接要的形态）
              steps（逐步结构，过程分析/process reward 的形态）
   一个文件两种消费姿势，消费方各取所需
-> 存储设计：steps 用 state_delta（增量）不用全量快照——全量每步复制是 O(n²)，
   delta 重建等价（ReplayView.stateAt(i) = concat(delta[0..i])），总量 O(n)
```

### D4. 录制与落盘分离：采样永远在事后，且是确定性的

```text
录制（运行时）：内存 append，微秒级，永远开——不录制就没有「事后想留」的选项
落盘（run 结束后）：TrajectorySampler 裁决 write 与否——IO 是贵的，裁决是免费的

采样确定性：hash(runId + seed) % 100 < sampleRate
  不用 Random：同一批 runId 两次运行选择完全一致（可复现）
  可审计：「这条为什么没导出」= hash 值可重算，不是玄学
失败轨迹是资产：默认不因 FAILED 而丢弃（负样本对 RL 同样有价值，
  DPO 的 rejected 一半来自失败 rollout）；过滤是显式配置（SamplingPolicy.statuses）
导出失败 fail loud：IO 异常上抛绝不静默吞（对照 Webhook 的 at-least-once 哲学——
  静默丢一条轨迹比让调用方看到异常糟糕得多）
```

### D5. Reward 可插拔，v1 = 规则 + 人工；步级奖励诚实为 null

```text
RewardSource 接口三个实现位：
  RuleReward（v1）—— 终态映射，默认 DONE=+1.0 / MAX_STEPS_EXCEEDED=-0.5 / ERROR=-1.0 /
    CANCELLED=0，构造器可传自定义映射（「完成任务」的语义因 Agent 而异，框架只给默认值）
  HumanFeedback（v1，M14.4）—— 标注 sidecar，导出时与轨迹 join
  LLM-as-judge（v2）—— 扩展点，接口已就位
outcome reward 落轨迹级（终步携带）；process reward（步级）v1 恒 null：
  宁可缺失不可造假——规则算不出「这一步好不好」，硬填 0 会污染训练信号
标注不回写：轨迹文件一经写出不可变（append-only，同 PromptManager），
  人工评价进独立 annotations sidecar，导出/训练时按 trajectoryId join
```

### D6. PreferencePair 引用不内嵌，DPO 导出时物化

```text
存：(trajA, trajB, preferred) —— 三个 ID，一行侧车记录
导出：DpoExporter 物化成 {prompt, chosen, rejected} 消息序列（DPO 训练器的直接输入）
为什么不内嵌：同一条轨迹可进多个偏好对（A>B、A>C、C>B）——内嵌 = 数据翻倍冗余；
  引用 + 物化 = 存储一份、投影多份（与 DagSpec「同一语义两个表示」同构）
配对来源 v1 = 同 prompt 双 rollout（rejection sampling 路线）：
  TrajectoryPairBuilder.pair(a, b) 校验 prompt 前缀一致（不一致 IAE——
  比较不同任务的轨迹没有偏好语义）
标注界面 v1 = ConsoleAnnotator + API（Web UI 不做，范围控制对齐 Stage 13「不做前端」）
```

### D7. 回放 = 走录不重演（walk, not re-run）

```text
LLM 温度采样非确定性 -> 「重新执行同一 run」不可能忠实复现（哪怕同 prompt 同工具）
Replayer 职责（诚实版）：
  1) load + 完整性校验：步 index 连续 / done 恰出现一次且在末步 /
     delta 重建 == messages（自洽性）——截断或篡改的文件 fail-fast 拒绝，绝不猜
  2) step-through 视图：stateAt(i) 逐步还原「模型当时看到什么」——
     调试「模型为什么在这步调错工具」的利器，也是人工标注的浏览工具
不承诺（v2 再说）：工具确定性重演（重放 tool call 对照两次结果）、
  重新调用模型做对照实验（A/B 策略比较）
```

### D8. 导出契约三件套：信封 + golden schema + 最小消费脚本

```text
跨仓库契约（Mini VERL 在另一条学习线）不能只有 Java 序列化能读：
  1) 信封：api_version/kind（对齐 Stage 13 AgentDefinition 的 v1/Agent 信封纪律，
     防未来 schema 版本被静默误读）
  2) 字段契约：snake_case 显式 @JsonProperty 逐字段标注（训练生态 Python 主流；
     不用全局命名策略——契约要显式到字段，不能靠推断）
  3) 可消费证明：examples/scripts/consume_trajectory.py（Python3 stdlib only）——
     读 JSONL → 校验信封 → 输出统计（轨迹数/平均 reward/工具调用分布/token 汇总）
     → 物化一条 SFT 样本形态。「能被消费」是可执行断言不是文档声明
golden file 测试：契约字段名快照进仓库，任何人无意改字段名 CI 直接红（D2 的执行机制）
```

---

## 5. 分层架构图

```text
┌────────────────────────────────────────────────────────────────────┐
│ examples: TrajectoryExample / PreferenceAnnotationExample            │
│           scripts/consume_trajectory.py（Python 消费证明）           │
└──────────────────────────────────┬─────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────┐
│ agent-trace-export（Stage 14 新增）                                  │
│                                                                    │
│  record/      TrajectoryRecorder + RunSession（会话）                │
│               RecordingModelClient / RecordingToolExecutor（边界）   │
│               RecordingAgent（糖衣）        —— D1：捕获在边界        │
│  trajectory/  Trajectory / TrajectoryStep / StepAction /             │
│               ToolObservation / TrajectoryMetadata / DoneReason     │
│  reward/      RewardSource / RuleReward     —— D5：可插拔            │
│  sample/      SamplingPolicy / TrajectorySampler —— D4：事后确定性   │
│  export/      TrajectoryExporter / JsonlTrajectoryWriter /           │
│               TrajectoryCodec / WorkflowTrajectoryAdapter —— D8 契约│
│  replay/      TrajectoryReplayer / ReplayView —— D7：走录不重演      │
│  feedback/    HumanFeedback / PreferencePair / TrajectoryPairBuilder │
│               / DpoExporter / ConsoleAnnotator —— D6：引用+物化      │
└────┬──────────────────────────────────┬─────────────────────────────┘
     │ compile                           │ compile（仅 adapter 用）
┌────▼────────────┐              ┌───────▼──────────────────┐
│ agent-core      │              │ agent-workflow            │
│ ModelClient /   │              │ StepRecord / WorkflowState│
│ ToolExecutor /  │              │ / ExecutionResult         │
│ Agent / AgentState│            │（兑现 Stage 5 javadoc 承诺）│
│ ChatMessage     │              └───────────────────────────┘
└─────────────────┘
   （agent-model MockModelClient / agent-memory 压缩场景 = test scope）
```

数据流（一次 run 的旅程）：

```text
ReActAgentLoop ──request──▶ RecordingModelClient ──捕获(State,Action)──▶ RunSession
       │                        │（最外层装饰，循环线程上）                  │
       └──ToolCall──▶ RecordingToolExecutor ──捕获(Observation)─────────▶ │
       │                                                                    │
       └─ run 结束 ──▶ RecordingAgent.close(status, lastError) ──组装──▶ Trajectory
                                                                            │
Trajectory ──▶ RewardSource.score ──▶ TrajectorySampler.shouldExport       │
                    │                            │                        │
                    ▼ (true)                     ▼ (false)                │
          JsonlTrajectoryWriter          内存丢弃（skipped 计数）           │
          trajectories.jsonl + metadata                                  │
                    │                                                   │
                    ├──▶ consume_trajectory.py（跨仓库消费）              │
                    ├──▶ TrajectoryReplayer（走录回放/标注浏览）           │
                    └──▶ TrajectoryPairBuilder ──▶ DpoExporter            │
                                   （双 rollout → preferences.jsonl）      │
```

---

## 6. 完整时序：一次「从录制到 DPO 数据」的全链

```text
T0: 装配（一次性，见 §3.1 装配形态）
    recorder + 双边界装饰器（recording 最外层）+ RecordingAgent 糖衣
    exporter = new TrajectoryExporter(dir, RuleReward.defaults(), SamplingPolicy.all())

T1: 运行（每 run 自动，业务方无感）
    agent.run("帮我查订单 8842")
    -> loop 第 1 步：buildRequest（ContextBuilder 生效）→ model 边界捕获 (State₁, Action₁)
    -> tool 边界捕获 Observation₁（含 [ERROR]/[DENIED] 文本，模型看到什么记什么）
    -> loop 第 2 步：捕获 (State₂, Action₂=终答, finish_reason=stop)
    -> close(DONE, null)：组装 Trajectory（steps + messages + metadata + token 汇总）
    -> RuleReward：DONE → +1.0
    -> Sampler：hash(run-8842, seed=42)%100=17 < 100 → 导出
    -> trajectories.jsonl 追加一行（IO 失败上抛，绝不静默）

T2: 消费（跨仓库，Mini VERL 侧同构脚本）
    python3 consume_trajectory.py trajectories.jsonl
    -> 信封校验 + 统计（轨迹数/平均 reward/order-query 调用 1 次/token 2046）
    -> 物化一条 SFT 样本（messages 序列）——「能被消费」的可执行证明

T3: 回放（调试/标注浏览）
    ReplayView view = TrajectoryReplayer.load(file)
    -> view.stateAt(1)（首步模型实见：system+user）
    -> view.actionAt(1)（调了 order-query）→ view.observationsAt(1)
    -> view.stateAt(2)（第二步实见：+assistant(toolCalls)+tool 消息）
    -> 截断/篡改文件 → 完整性校验 IAE，绝不猜

T4: 标注（人工反馈 → DPO）
    双 rollout：同 prompt 跑两次（script A 查了订单答对 / script B 直接说不知道）
    -> TrajectoryPairBuilder.pair(a, b)：prompt 前缀一致性校验通过
    -> ConsoleAnnotator：打印两条轨迹终答与关键步骤，stdin 读 "a" → preferred=A
    -> HumanFeedback 落 annotations sidecar（原轨迹文件字节不变）
    -> DpoExporter：preferences.jsonl 一行 {prompt, chosen=A 后续, rejected=B 后续}
    -> （AI Infra 主线）Mini VERL 拿 preferences.jsonl 跑 DPO → 优化后的模型换回 Agent

失败分支：
    F1 模型异常：loop 捕获置 ERROR → 轨迹仍完整产出（步骤到异常为止 + status/lastError
       进 metadata），reward=-1.0，采样默认仍导出——失败轨迹是负样本资产（D4）
    F2 采样拒绝：内存轨迹丢弃，exporter 记 skipped 计数（可观测不阻塞）
    F3 导出 IO 失败：异常上抛调用方（fail loud），不吞不重试（重试策略归装配层）
    F4 回放坏文件：完整性校验 IAE（步断裂/done 缺失或重复/delta 与 messages 不自洽）
    F5 配对前缀不一致：PairBuilder IAE——不同任务的轨迹没有偏好语义（D6）
```

---

## 7. 模块结构

```text
agent-trace-export/                            # 新增 Maven 模块（父 POM <modules> 增补）
└── src/main/java/io/github/qwzhang01/agent/trace/
    ├── trajectory/                            # 6 类（M14.1）
    │   ├── Trajectory.java / TrajectoryStep.java
    │   ├── StepAction.java / ToolObservation.java
    │   └── TrajectoryMetadata.java / DoneReason.java
    ├── record/                                # 5 类（M14.1）
    │   ├── TrajectoryRecorder.java / RunSession.java
    │   └── RecordingModelClient.java / RecordingToolExecutor.java / RecordingAgent.java
    ├── reward/                                # 3 类（M14.2）
    │   └── RewardSource.java / RewardResult.java / RuleReward.java
    ├── sample/                                # 2 类（M14.2）
    │   └── SamplingPolicy.java / TrajectorySampler.java
    ├── export/                                # 4 类（M14.2/M14.3）
    │   ├── TrajectoryExporter.java / JsonlTrajectoryWriter.java
    │   ├── TrajectoryCodec.java / WorkflowTrajectoryAdapter.java
    ├── replay/                                # 2 类（M14.3）
    │   └── TrajectoryReplayer.java / ReplayView.java
    └── feedback/                              # 5 类（M14.4）
        ├── HumanFeedback.java / PreferencePair.java
        └── TrajectoryPairBuilder.java / DpoExporter.java / ConsoleAnnotator.java
```

```text
examples/（新增 2 个 + 1 脚本）
├── TrajectoryExample.java           # 验收：装配三件套 → 3 步 run → JSONL → python 消费 → 回放 step-through
├── PreferenceAnnotationExample.java # 验收：同 prompt 双 rollout → Console 标注 → DPO JSONL
└── scripts/consume_trajectory.py    # 跨仓库消费证明（Python3 stdlib only）
```

命名说明：模块名沿用 18 周规划与 README 既定的 `agent-trace-export`（改名成本 > 认知成本）；根包取 `io.github.qwzhang01.agent.trace`；每个类的 javadoc 首段澄清 D2（本模块**导出 Trajectory**，Trace 是事实源之一）——命名是历史遗留，语义边界靠文档立住。

不改动任何存量模块代码（agent-core 零改动是 D1 的验收项）。

---

## 8. 实现里程碑（4 个，节奏对齐 Stage 12/13）

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M14.1 | 记录层 | trajectory 6 类 + record 5 类 + 单测 | **压缩保真核心测试**：WindowContextBuilder(maxMessages=2) 下 3 步 run，step.state 重建 == 模型实见 != state.messages 全量（D1 可执行证明）；多 ToolCall 同步一步（1 step 2 observations）；模型异常 → status=ERROR + lastError 入 metadata；maxSteps → MAX_STEPS_EXCEEDED + DoneReason；token/时长汇总正确；同 runId 重复 open 拒绝；**全仓存量零影响（agent-core 零改动）** |
| M14.2 | 奖励与导出 | reward 3 类 + sample 2 类 + export 前 3 类 + consume_trajectory.py + golden schema 测试 | mock agent 3 步 run → JSONL → `python3 consume_trajectory.py` 统计与 Java 侧一致（轨迹数/平均 reward/工具分布/token 汇总）；round-trip：write → load → record 相等；golden file 锁字段名（snake_case）；RuleReward 自定义映射生效（DONE→+2.0）；采样确定性：同 seed 同 runId 集合两次裁决一致，rate=0 全拒 / rate=100 全过 |
| M14.3 | 采样回放与 workflow 适配 | replay 2 类 + WorkflowTrajectoryAdapter + 单测 | ReplayView.stateAt(i) 逐步重建全量实见；完整性校验三连：截断文件 / 删 done 步 / delta 与 messages 不自洽 → 全部 IAE；WorkflowState.trace（3 个 StepRecord）→ Trajectory 导出 → 回放走查（兑现 Stage 5 javadoc 承诺）；失败轨迹默认导出（负样本），状态过滤配置生效 |
| M14.4 | 人工反馈与收口 | feedback 5 类 + 2 验收示例 + README/架构笔记/学习路线更新 | 双 rollout（scripted 好/坏）→ PairBuilder 配对 + 前缀不一致 IAE；ConsoleAnnotator 标注 → annotations sidecar 落盘且**原轨迹文件字节不变**（append-only 实证）；DpoExporter 输出 {prompt, chosen, rejected}：prompt 前缀共享、chosen/rejected 消息序列衔接完整；两个示例实跑通过；全仓存量零影响 |

依赖：M14.2 ← M14.1（记录才有轨迹）；M14.3 ← M14.2（回放依赖 codec load）；M14.4 ← M14.2（需已导出轨迹物化）。主路径串行，M14.3/M14.4 的部分单测可并行先行。

---

## 9. 验收标准（对齐 18 周规划原文 4 条）

```text
1. Agent 每次运行自动产出标准轨迹文件
   -> M14.1 + M14.2（装配三件套一次接线，之后每 run 自动一条 JSONL；
      「标准」= v1 信封 + golden schema 锁定）
   ✅ 达成：RecordingAgent.wrap 一次装配（TrajectoryExample 实跑：run 即记录，
      record 即落盘 trajectories.jsonl，api_version=v1/kind=Trajectory）

2. 轨迹文件能被 Mini VERL（或简化 RL 训练脚本）消费
   -> M14.2（consume_trajectory.py 实跑：信封校验 + 统计 + SFT 样本物化；
      Mini VERL 侧按同 schema 消费，跨仓库契约见 D8）
   ✅ 达成：两次实测数字逐项一致（M14.2 dump 3 条 avg 0.3333 / 示例 1 一条
      avg 1.0 tokens 812·45·857，python3 stdlib only）

3. 能回放一条轨迹并逐步查看状态变化
   -> M14.3（ReplayView.stateAt(i) 逐步还原模型实见；
      诚实边界：走录不重演，D7）
   ✅ 达成：ReplayView 完整性校验四连 + describeStep 走查（示例 1 实跑
      "integrity verified, walking 2 step(s)"；坏文件五形态全 IAE）

4. 人工标注偏好后，偏好数据可直接用于 DPO 训练格式
   -> M14.4（PreferencePair → {prompt, chosen, rejected} JSONL，
      rejection sampling 双 rollout 路线，D6）
   ✅ 达成：PreferenceAnnotationExample 实跑——同 prompt 双 rollout → Console
      标注 preferred=A → preferences.jsonl {prompt 2 条共享, chosen 3 条,
      rejected 1 条}，DPO 训练器直接输入形态
```

---

## 10. 测试策略

- **记录保真（D1 核心）**：压缩/窗口场景下 step.state == 模型实见 != state.messages（RecordingModelClient 捕获对照）；多轮、多工具、终答、异常各形态步划分
- **组装纪律**：agent-core 零改动断言（无源码 diff）；装饰器顺序（recording 最外层）文档化 + 错序行为 javadoc 说明
- **round-trip**：export → load → record 相等；golden file 字段名快照（snake_case 契约）
- **奖励**：默认映射 + 自定义映射 + 未知终态兜底；步级 reward 恒 null（不造假）
- **采样**：确定性（同 seed 复现）/ rate 边界（0 与 100）/ 失败轨迹默认保留 + 状态过滤
- **回放**：正常走查 / 三种坏文件（截断、done 缺失、delta 不自洽）全 fail-fast
- **DPO**：prompt 前缀共享、chosen/rejected 衔接完整、前缀不一致配对被拒、sidecar 不回写原文件（字节级断言）
- **workflow 适配**：StepRecord 列表 → Trajectory → 回放，往返语义不丢（节点级粗粒度，诚实标注非逐步模型调用）
- **向后兼容**：只新增模块，存量测试零影响

---

## 11. 文章规划（规划 8 篇 -> 优先 6 篇）

| 文章（规划原文） | 写作时机 | 素材来源 |
|---|---|---|
| 《Agent Runtime 为什么要产出 RL 轨迹》 | M14.1 | §1 四假设破裂 + 与 AI Infra 闭环（两条学习线交汇，面试最强叙事） |
| 《轨迹数据结构：State / Action / Observation / Reward / Done》 | M14.1 | D3 步定义 + messages/steps 双通道 + delta 存储 |
| 《Trace vs Trajectory：调试格式与训练格式的区别》 | M14.2 | D2（面试高频，规划原文点名） |
| 《轨迹导出格式：如何让 Mini VERL 消费》 | M14.2 | D8 契约三件套 + consume_trajectory.py 实证 |
| 《轨迹采样策略：哪些 Run 值得记录》 | M14.3 | D4 录制落盘分离 + 失败轨迹是资产 + 确定性 hash |
| 《人工反馈接入：从偏好标注到 RLHF 训练数据》 | M14.4 | D5+D6 + rejection sampling 双 rollout 路线 |
| 备选：《轨迹回放：完整复现一次 Agent Run》《Agent 执行到模型优化的完整闭环》 | 收口后按数据挑选 | D7 走录不重演 / 全链闭环总览 |

**系列衔接**：文章 1 承接 Stage 13 收尾（从「能搭出来」到「能学起来」）；文章 3 与 agent-arch 系列 Trace/观测主题呼应；文章 6 与 AI Infra 主线笔记互链（闭环叙事的两半）。

---

## 12. 本阶段不做（范围控制）

- **RL 训练本身** —— Mini VERL 归 AI Infra 主线（另一仓库）；本模块只负责产出合格数据，闭环的右半边不抢
- **LLM-as-judge 自动奖励** —— RewardSource 扩展点留位；judge 的成本/抖动/被注入三坑值得单独立项
- **重演式回放**（重放 tool call 对照结果 / 重新调模型做 A/B）—— 工具部分确定性可回放但 v1 不做，LLM 部分原理上不可忠实复现（D7）
- **Web 标注界面** —— v1 Console + API；同 Stage 13「不做前端」纪律
- **持久化轨迹仓库**（数据库 / 对象存储 / 数据目录管理）—— v1 文件系统 JSONL；数据管道是 MLOps 工程不是 Runtime 能力
- **跨进程 checkpoint-resume 的轨迹拼接** —— v1 单进程内存 session；按 runId 续写（crash 后补全轨迹）v2
- **process reward（步级奖励）自动生成** —— v1 恒 null，不造假数据（D5）
- **PII 脱敏与数据治理** —— 轨迹含原始对话（可能含个人信息），用于训练前需自行合规审查；本模块提供 custom metadata 挂脱敏标记位，治理管道本身不做
- **OTel / 指标导出** —— 归 Stage 18 可观测性（Trace 透传 MCP 一并在 18 收口）

---

## 13. M14.1 实现记录（2026-08-24，记录层）

### 交付

- 新增 `agent-trace-export` Maven 模块（父 POM `<modules>` + dependencyManagement 注册；compile 依赖 agent-core，test 依赖 agent-model）
- **trajectory 包 6 类**：`Trajectory`（messages 逻辑对话 + steps 逐步结构双通道，status 复用 `AgentState.Status`）/ `TrajectoryStep` / `StepAction` / `ToolObservation` / `TrajectoryMetadata`（含 `sha256Hex` 静态助手）/ `DoneReason`（含 `from(Status)` 非终态映射 null）
- **record 包 6 文件**（5 公开抽象 + 1 包内实现）：`TrajectoryRecorder`（ThreadLocal 会话 + usedRunIds 唯一性 + completed/last）/ `RunSession` 接口（attach 一次 / finish 恰一次 / close 安全网）+ `RecordingSession` 包内实现（pending 步组装 + 逻辑消息重建 + token 聚合）/ `RecordingModelClient`（chat 捕获 State+Action，异常 onModelError 后原样上抛；stream v1 透传不录）/ `RecordingToolExecutor`（Observation 捕获，VERBATIM；执行器异常 success=false 后上抛）/ `RecordingAgent`（糖衣：open→attach(config)→delegate→finally finish(status, lastError)；线程上已有会话则降级透传）
- 测试 19 个：`RecordingFidelityTest` 5 + `TrajectoryRecorderLifecycleTest` 10 + `RecordingAgentTest` 4；全仓 631 全绿（存量 612 零影响，agent-core 零改动达成）

### D1 压缩保真核心测试（本里程碑的灵魂）

`compressionFidelityStateIsModelSeenNotFullHistory`：测试本地 `TrimmingContextBuilder(keepLast=2)` + 独立 `CapturingModelClient`（夹在 Recording 装饰器与 Mock 之间，非循环佐证，Stage 12/13 手法）：

- 3 步 run（工具轮→工具轮→终答），断言逐条：
  - `step[i].state == capturing.requests[i]`（轨迹 State == 模型实见请求）
  - step2.state 角色序列 `[ASSISTANT, TOOL]`——窗口裁剪后模型没看到 system/user
  - `state.getMessages().size() == 7` 且 `!= step2.state`——全量历史与实见分叉，D1 论点的可执行证明
  - `trajectory.messages` 仍 7 条完整逻辑对话（SFT/DPO 消费通道不受裁剪影响）
  - 终态标记只在末步（done=true/DONE），token 聚合 250/90/340

### 与蓝图的两处偏差（诚实记录）

1. **D3 的 state_delta → 每步全量快照**：写时压缩类 builder（CompressingContextBuilder 改写历史前缀）连 delta+dropCount 都无法表达，任何"增量编码"都存在表达不了的情形；v1 精确性优先（步数 ≤ maxSteps=10，全量存储可忽略），delta 编码决策移至 M14.2 导出层（文件格式层可选择性编码）
2. **压缩保真测试的 builder 来源**：蓝图复用清单写 agent-memory test scope，实际用测试本地 TrimmingContextBuilder——真实 WindowContextBuilder 在 agent-product（拖整个 product 依赖链换一个测试助手不值），且被测契约是"任意裁剪 builder 下 State=模型实见"，测试本地 builder 更贴近契约本质

另：agent-workflow compile 依赖按"依赖随用随加"推迟到 M14.3（WorkflowTrajectoryAdapter 落地时）。

### 意外边界发现（记入笔记，留作面试素材）

- **ReActAgentLoop 对 `toolExecutor.execute` 无 try-catch**：执行器层异常（非 Tool 内部异常——那已被 DefaultToolExecutor 包成 [ERROR] 文本）会直接炸穿 loop 直到 SimpleAgent.run 调用方。这是框架既有行为，本阶段不修（超出范围），但 RecordingToolExecutor 的处理确立了正确姿势：记录 success=false 的 observation 后**原样上抛不吞**；RecordingAgent 的 finally-finish 把非终态（EXECUTING_TOOL）归一化为 ERROR 轨迹并诚实标注 "run aborted in non-terminal status EXECUTING_TOOL"
- **RecordingAgent 嵌套语义**：同线程已有会话（如 AgentNode 内跑内层已接线 Agent）时糖衣自动降级透传，避免 open 二次 IAE 炸 crash——v1 单线程单 Agent 录制边界在 javadoc 立住

---

## 14. M14.2 实现记录（2026-08-24，奖励与导出）

### 交付

- **reward 包 3 类**：`RewardSource`（接口：score → RewardResult，v1 三槽位 = 规则/人工/judge）/ `RewardResult`（reward + source + explanation；`applyTo` 不可变 wither 回填 Trajectory，原实例不动）/ `RuleReward`（默认 DONE=+1.0 / MAX_STEPS_EXCEEDED=-0.5 / ERROR=-1.0 / CANCELLED=0.0；`withReward` 定制返回新实例；空 steps → 0.0 + "no steps recorded" 诚实标注，不造假信号）
- **sample 包 2 类**：`SamplingPolicy`（rate 0-100 + seed + 状态集 + 步数区间 + reward 阈值；`all()` 默认全采含 ERROR 轨迹——负样本是资产；minReward 设置时未评分轨迹 fail-closed 拒绝）/ `TrajectorySampler`（`floorMod(runId.hashCode() ^ seed, 100) < rate`——String.hashCode 是 JLS 规范值，跨 JVM 跨次运行同一 (runId, seed) 裁决恒一致，可审计可复现）
- **export 包 3 类**：`TrajectoryCodec`（**契约唯一实现点**——手写 JSON 树而非给模型加 Jackson 注解：字段名显式到每个 snake_case，绝不靠命名策略推断；信封 api_version=v1/kind=Trajectory 对齐 Stage 13 纪律；坏信封/未知版本 IAE fail-fast）/ `JsonlTrajectoryWriter`（一行一轨迹 append-only；坏行报错带文件:行号 fail loud）/ `TrajectoryExporter`（门面：`record` = score → sample → persist，拒绝计数 `skippedCount` 可观测；`write` 手动路径绕过评分采样；IO 失败上抛绝不静默）
- **examples/scripts/consume_trajectory.py**：Python3 stdlib only 消费脚本——信封校验 + 统计（轨迹数/状态分布/平均 reward/模型调用数/工具分布/token 三项）+ SFT 样本物化（首条轨迹 messages 直接打印）
- 测试 +25（RuleReward 5 + Sampler 7 + Codec 7 + Exporter 6），模块累计 44，**全仓 656 全绿存量 631 零影响**

### 跨语言消费证明（M14.2 核心验收，实测数字逐项一致）

`TrajectoryDemoDump`（test 源内 dump 工具）跑 3 次 scripted agent（2 DONE + 1 模型异常 ERROR）→ exporter 落盘 → `python3 consume_trajectory.py` 对照：

| 指标 | Java 侧 | Python 侧 |
|---|---|---|
| trajectories | 3 | 3 |
| status 分布 | DONE×2 + ERROR×1（构造即知） | `{'DONE': 2, 'ERROR': 1}` |
| avg reward | 0.3333 | 0.3333 |
| model calls | 6 | 6 |
| echo 调用 | 3 | `{'echo': 3}` |
| prompt/completion/total tokens | 330/110/440 | 330/110/440 |

「能被消费」是可执行断言不是文档声明（D8 落地）。SFT 样本物化输出完整逻辑对话（SYSTEM→USER→ASSISTANT(tool_calls)→TOOL→…→ASSISTANT(final)），正是训练侧直接要的形态。

### golden 字段名快照（D8 契约锁的实现形态）

不用外部资源文件，用测试内联 `TreeSet` 字段清单（40 个 snake_case 字段，等价于 golden file 且无需资源加载样板）：**两种形状并集 == expected 全集**（scored 形状带 reward/reward_source，failure 形状带 last_error）+ **任何单一形状不得越界**（containsAll 双向检查）。`custom`/`arguments` 是自由数据容器不入契约快照（第一版收集器把 `tenant`/`input`/`q` 当成 schema 字段的教训）。改字段名 = api_version 升级，此测试就是 CI 绊线。

### 期间踩坑（测试自身的 bug，非实现 bug，记录防复发）

1. `Set.of(...)` 字面量不允许重复元素——"duration_ms" 在 metadata 层与 step 层重复出现直接 IAE，改 `TreeSet(List.of(...))`
2. golden 收集器初版把数据容器的 key 混进 schema 字段集——契约快照必须区分「schema 字段」与「自由数据」
3. round-trip 断言写串对象（拿 successful 原型比 failed round-trip）——assertEquals 的 expected/actual 在多构造测试里要格外小心

### 诚实边界（v1 契约）

- **多模态 parts 不进 v1 契约**（文本轨迹无损；`messageFromJson` 传 null parts 与 ChatMessage 紧凑构造器规范化一致）
- 顶层 `done_reason` 是冗余便利字段，load 时从 steps 派生、不信任盲读
- null 字段与空集合省略（tools=[]/custom={} 不落盘），加载方按缺省处理——双重 round-trip 树稳定测试锁住「省略字段回归一致」

---

## 15. M14.3 实现记录（2026-08-24，采样回放与 workflow 适配）

### 交付

- **trajectory 包 +1**：`TrajectorySteps.logicalMessages(steps)`——逻辑消息重建的**单一算法**：RecordingSession 产出时用、ReplayView 校验时重算。「怎么写」和「怎么验」必须是同一份代码，两处各写一份 = 静默腐化的 bug 农场（RecordingSession 的私有实现已删除并委托，19 个 M14.1 测试兜底等价性）
- **replay 包 2 类**：`ReplayView`（构造即完整性校验 + step-through：stateAt/actionAt/observationsAt/isDoneAt + describeStep 人读摘要供 M14.4 标注浏览）/ `TrajectoryReplayer`（loadAll/loadFirst 文件加载，坏 JSON 行带行号）
- **export 包 +1**：`WorkflowTrajectoryAdapter`——兑现 StepRecord 自 Stage 5 起的 javadoc 承诺；agent-workflow compile 依赖此时落地（依赖随用随加）
- 测试 +14（ReplayView 9 + WorkflowTrajectoryAdapter 5），模块累计 58，**全仓 670 全绿存量 656 零影响**

### D7 完整性校验四连（比蓝图多一条）

蓝图说三连，实现四条（更完整）：
1. 步 index 连续 1..n
2. done 恰一次且在末步（中间 done 拒绝；**非空轨迹无 done 也拒绝**——这条是蓝图没写的：截断文件删掉终步后 doneCount=0，必须抓住）
3. done 步必带 doneReason，非 done 步不得带
4. messages 通道 == steps 重建（双通道自洽——篡改任一通道都在这条暴露）

空 steps 轨迹合法（无 done、messages 必须空）——「没有任何模型调用的 run」是真实存在的边界。

坏文件实测：截断 / index 跳号 / done 挪中间 / done 无理由 / messages 篡改，全部 IAE 带定位信息；文件级坏 JSON 带 `文件:行号`。

### WorkflowTrajectoryAdapter 的映射裁决（诚实粗粒度）

- **节点级投影而非模型调用级**：workflow run 没有模型调用，每个 StepRecord 一步；step.state 是「黑板视图」（workflow 头 + input + 之前节点摘要累积）而非 post-ContextBuilder 模型实见——javadoc 明写这个语义差异
- action：content=summary、finishReason=节点状态名（SUCCESS/FAILED/PAUSED/CANCELLED）、无 token
- 终态映射：SUCCEEDED→DONE / FAILED→ERROR（lastError=errorMessage）/ **CANCELLED→doneReason.CANCELLED + status=ERROR**（AgentState.Status 没有 CANCELLED——status 借 loop 词表，语义放 doneReason，javadoc 记录）/ **PAUSED 直接 IAE 拒绝**（暂停的 run 不是完整轨迹，先 resume；跨 resume 拼接是 v2 范围控制）
- messages 通道 = TrajectorySteps.logicalMessages(steps) 按构造保证自洽（ReplayView.of 直接通过）
- **收益证明（免费复用管线）**：adapt → RuleReward 打分（DONE→+1.0）→ exporter.record 落盘 → load round-trip → ReplayView 走查，全链不改一行——workflow 轨迹进了和 Agent 轨迹同一条训练数据管线，这就是「映射进 Trajectory 模型」的回报

### 期间踩坑（测试 bug，记录防复发）

- helper 按替换步的 index 匹配原步——构造的坏步 index=7 根本匹配不到位置 0，替换未发生、断言未触发；改为**按位置**替换
- 坏文件行号测试期望 :2 实报 :1——第一行本身就是坏轨迹（缺 trajectory_id），测试数据与断言错位

---

## 16. M14.4 实现记录（2026-08-24，人工反馈与收口）+ 阶段总结

### 交付

- **feedback 包 5 类**：`HumanFeedback`（1-5 分单轨迹评分，构造器校验）/ `PreferencePair`（A/B 偏好**引用不内嵌**，同一轨迹可进多个对；preferred 限 A|B、两 id 必须不同）/ `TrajectoryPairBuilder`（**prompt 前缀 = 开头至首条 USER 含**；`requireSharedPrompt` 不一致 IAE 带双方摘要；`pair(a,b,preferred,annotator)` 一步校验+构造）/ `DpoExporter`（物化 {prompt, chosen, rejected}：**悬空引用 IAE** + 导出时重验前缀一致性——sidecar 与 pool 可能各自演化；空余段合法——「无响应 vs 好响应」是正当偏好）/ `ConsoleAnnotator`（可注入 BufferedReader/PrintStream：展示两侧 ReplayView.describeStep 走查+终答 → 读 a/b/skip → PreferencePair 落 sidecar；`rate()` 写评分行）
- **sidecar 纪律**：标注落独立 `annotations.jsonl`（api_version/kind=PreferencePair|HumanFeedback），**原轨迹文件字节不变**（测试用 bytes 相等实证 append-only）——同 Stage 13 PromptManager publish-only 哲学
- **examples 2 个**：`TrajectoryExample`（装配→run→record→回放走查→契约字段打印 + python 消费提示）/ `PreferenceAnnotationExample`（双 rollout→前缀校验→demo 预选 a 的 Console 标注→DPO 导出打印）；examples pom 增补 agent-trace-export
- 测试 +15（PairBuilder 4 + DpoExporter 5 + ConsoleAnnotator 6），模块累计 73，**全仓 685 全绿存量 670 零影响**

### 与蓝图的两处小差异（增强方向）

1. DPO 行加了 `api_version/kind` 信封（蓝图 §3.2 样例没有）——自家 D2 纪律统一适用，对消费方向后兼容
2. `ReplayView.describeStep`（M14.3 交付）直接复用为标注浏览的渲染——蓝图时序 T4「标注界面浏览」的伏笔兑现

### 双示例实跑结果（验收 4 条中 1/3/4 的可执行证明）

- `TrajectoryExample`：answer 正常 → steps=2/messages=5 → 导出 reward=1.0(rule) → 回放 "integrity verified, walking 2 step(s)"（step1 calls order-query/step2 [DONE: DONE]）→ 契约 11 个顶层字段；python 消费 1 条/avg 1.0/order-query×1/tokens 812·45·857
- `PreferenceAnnotationExample`：A(2 步,查了工具,答「已发货」) vs B(1 步,「我不知道」) → 标注 A → preferences.jsonl：prompt=[system,user] 2 条共享、chosen=3 条（tool_calls→tool→好终答）、rejected=1 条（烂回答）

### 期间踩坑（构建环境，非代码 bug）

- `mvn -pl examples exec:java`（无 -am）从 .m2 拿到**旧版 agent-product jar**（缺 Stage 13 评审后新增的 `DISPATCH_FAILED` 枚举），`WebhookExample` 报出误导性的「枚举 case 必须为非限定名」编译错——真相是常量不存在。教训：跨模块示例跑 exec 前先 `install` 依赖链（或 `-am` 两步走：先 `install -pl examples -am` 再单模块 exec）；javac 对「case 里不存在的枚举常量」报的是限定名错误，见此报错先查枚举是否真的有该常量

### Stage 14 阶段总结

| 维度 | 结果 |
|---|---|
| 模块 | agent-trace-export（依赖 agent-core + agent-workflow 仅 adapter + agent-model test scope，零新第三方） |
| 代码 | 29 类（trajectory 7 / record 6 / reward 3 / sample 2 / export 4 / replay 2 / feedback 5），agent-core 零改动 |
| 测试 | 73（Fidelity 5 + Lifecycle 10 + Agent 4 + Reward 5 + Sampler 7 + Codec 7 + Exporter 6 + Replay 9 + WorkflowAdapter 5 + PairBuilder 4 + Dpo 5 + Annotator 6），全仓 685 全绿 |
| 示例 | TrajectoryExample / PreferenceAnnotationExample / scripts/consume_trajectory.py |
| 验收 | 18 周规划 4 条全过（§9 已加 ✅ 对照） |
| 与蓝图的偏差 | 4 处均已诚实记录：state_delta→全量快照（§3.2 注记）、压缩测试 builder 本地化（§13）、D7 校验四连比三连多一条（§15）、DPO 行加信封（本节） |
| 留给 v2 | LLM-judge reward / 重演回放 / Web 标注界面 / 持久化轨迹仓库 / 跨 resume 拼接 / process reward / OTel 导出（§12 范围控制 9 项） |

下一步：Stage 15（规划中的下一阶段）；AI Infra 主线（Mini VERL）按 v1 契约消费本模块产物，闭环右半边启动。
