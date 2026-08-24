# Stage 17 架构设计：Coding Agent Profile

> 对应阶段：Stage 17 - Coding Agent Profile（第三个领域 Profile：工作区 / 变更与补丁 / 命令与沙箱 / 测试裁判 / 修复循环 / 审批）
> 状态：✅ 全部完成（2026-08-24，M17.1~M17.5 五里程碑全过，agent-coding 138 测试全仓 **1023 全绿**，**零存量改动第三次兑现**——18 周规划 M8「三类场景同 Runtime」完成）；实现记录见 §13 起
> 模块：新增 `agent-coding` Maven 模块，依赖 `agent-core`（Agent/AgentConfig/AgentState/Tool/ToolRegistry/ToolExecutor）+ `agent-security`（GovernedToolExecutor/ToolPolicy/ToolApprovalService/AuditLogger）+ `agent-sandbox`（SandboxSpec/SandboxResult 数据契约）；`agent-model`（MockModelClient）test scope。**不依赖 workflow / scheduler / memory / channel / trace-export / product / enterprise / tavern**（见 D5/D7/D9 四处「有意不复用」裁决）
> 前置：Stage 1-16 已完成（全仓 885 测试全绿；沙箱 / 治理链 / 审批 / 装配底座齐备）
> 定位：18 周规划「同一 Runtime 支撑三类场景」宣言的**第三次实证**。Stage 15 验证企业场景缺的是**归属层**（谁在问 / 哪个租户 / 花谁的钱）；Stage 16 验证游戏场景缺的是**世界层**（角色有灵魂 / 说话有后果 / 一局有历史）；Stage 17 验证编码场景缺的是**变更层**（输出即变更 / 变更即补丁 / 验证即测试 / 循环即收敛）——目标第三次零存量改动

---

## 1. 核心命题：Chat Loop 的输出是文本，Coding Agent 的输出是变更

Stage 1-16 造好的 Runtime 有一个从未被挑战的隐含共识：**Agent 的产出是给人看的文本**。`agent.run("...")` 出来的是答案（企业）、台词（游戏）、摘要（频道）——文本没有副作用，给出去就结束了。这个共识在编码场景全线破裂：

```text
Chat Loop 的五个隐含假设，在 Coding 场景全部破裂：
1. 输出假设 -- 假设输出是"给人看的文本"（答案说完即止）
   Coding Agent 的输出是"要落盘的变更"：文件改了什么必须可审查（diff）、
   可拒绝（审批）、可撤销（整体丢弃）——一句"我把除法方法加好了"不是交付，
   一个可审查的 Patch 才是交付
2. 副作用假设 -- 假设工具调用要么无副作用（查询/检索），要么副作用受控
   （游戏数值限幅 / 企业审计留痕）
   Coding Agent 要写文件、跑构建命令——副作用直接作用在真实文件系统上，
   且命令面近乎无限（构建工具能做的事 = 一切）：rm -rf、curl 外发、
   覆盖 .git、读 .env——每一个都是真实事故
3. 验证假设 -- 假设产出对不对由人判断（用户看答案 / 管理员审记忆 / 主管批退款）
   Coding Agent 的产出可以机器验证：编译过没过、测试绿没绿——裁判从人
   换成了工具，奖励信号内生（测试结果就是 reward），这是三个场景中
   唯一自带客观裁判的
4. 循环假设 -- 假设一次 run 线性走到答案（ReAct 有 maxSteps 兜底但目标是一次收敛）
   Coding Agent 的常态是迭代收敛环：改 → 测 → 败 → 修 → 再测——
   "失败后修复"不是异常分支，是主流程；而环必须有界（修不好还一直修
   = 无限烧 token 的真实事故）
5. 上下文假设 -- 假设上下文 = 对话消息（Stage 8 后 + 记忆检索）
   Coding Agent 的上下文还有工作区：文件树、文件内容、命令输出——
   单文件可能几千行、一次构建输出可能几 MB，读什么/读多少/输出截断到
   哪里，都是上下文预算的真实战场
```

Stage 17 的答案：**工作区即边界**（Workspace 路径白名单，读也是特权）、**变更即补丁**（写进暂存区不落盘，审批后 apply——写盘是特权，Patch 是申请单）、**命令即白名单客人**（无 shell 执行 + argv 白名单，注入语法无处生根）、**测试即裁判**（TestResult 机器验证，裁判命令不由被裁判者指定）、**修复环即有界收敛**（边界在引擎、节奏在模型）。

一句话（接 Stage 15/16 的递进叙事）：

```text
Stage 15 让 Agent 能进企业 -- 第一个领域 Profile：归属与隔离
Stage 16 让 Agent 能演戏   -- 第二个领域 Profile：灵魂与后果
Stage 17 让 Agent 能写代码 -- 第三个领域 Profile（变更层）：
         每次修改是可审查的 Patch（不是直接写盘），
         每条命令是白名单里的客人（没有 shell），
         每轮验证交给测试（裁判不归被裁判者指定），
         每个修复环有边界（引擎计界、模型驱动）
```

### 与相邻概念的四条边界（面试高频）

```text
Workspace（17）vs WorldState（16）vs MemoryStore（8）—— 三个"状态的家"：
  WorldState 是引擎拥有的黑板（变更须经 Effect 指令，Stage 16 D3）
  MemoryStore 是 store 拥有的沉淀（对话中提取，scope 隔离，Stage 8）
  Workspace 是外部已有的事实（文件系统本来就在那里，Agent 只是读它）
  Workspace 不复制文件内容进内存——它是"视图 + 边界"，不是状态副本；
  谁拥有状态，谁就负责它的变更纪律：文件系统的变更纪律 = Patch 审批

Patch（17）vs FileChange（17）vs WorldEffect（16）—— 变更的三个粒度：
  WorldEffect 是单条指令（SetFlag 一类，立即 apply）
  FileChange 是单文件差异（path + 新内容，暂存不生效）
  Patch 是一次任务的变更集（N 个 FileChange 的事务单位：全批 apply
  或全批丢弃——半批落盘是最坏状态）
  16 的指令"立即生效因为引擎可信"；17 的变更"先暂存因为模型不可信"——
  同一个"变更即一等值"哲学，两种生效时机，信任水平决定

命令白名单（17）vs 工具权限三档（9）—— 两道闸、两个粒度：
  权限三档管"工具能不能调"（工具名粒度：run_command 设 REQUIRES_APPROVAL）
  白名单管"参数里那条命令合不合法"（argv[0] 粒度：mvn 可以、curl 不行）
  审批过后命令还要过白名单；白名单放行还要受超时/截断——纵深防御，
  任何单道闸失守都不是事故

修复环（17）vs maxSteps（2）vs Workflow 环保护（5）—— 三种"环"三种语义：
  maxSteps 防"模型喋喋不休"（步数上限，步是事故）
  图引擎环检测防"流程图成环死循环"（环是设计错误，DAG 拒绝成环）
  修复环边界防"修不好还一直修"（环是方法，但方法要有预算）
  前两者把环当敌人，本阶段把环当方法——但给它一个 token 预算意义上的刹车
```

---

## 2. 复用清单：Stage 17 是第五次「组装阶段」（预检先行）

延续 Stage 12 教训、13-16 制度化的做法：**规划时就做复用预检**。本清单每行标注预检结论，含四处「有意不复用」与一处「模式复现」。

| 能力需求 | 已有设施（阶段） | Stage 17 做什么 | 复用预检 |
|---|---|---|---|
| 模型/循环/工具注册 | `ModelClient` / `ReActAgentLoop` / `ToolRegistry` / `DefaultToolExecutor`（1/2） | CodingAgentFactory 装配五个编码工具，ReAct 循环承载"读→改→测→修" | ✅ 直接兑现 |
| 工具治理四件套 | `GovernedToolExecutor` + `ToolPolicy` + `ToolApprovalService` + `AuditLogger`（9） | 读/列 AUTO、写暂存 AUTO（无真副作用）、run_command 与 apply REQUIRES_APPROVAL（真副作用）、全量审计——审批档位跟着真副作用走（D8） | ✅ 直接兑现，零改动挂车 |
| 审批服务 | `ConsoleApprovalService`（9：auto/autoReject/console/callback 四模式） | 示例用 auto 演示流 + console 演示人审 diff；审批拒绝 → Patch REJECTED | ✅ 直接兑现 |
| 沙箱数据契约 | `SandboxSpec`（timeout/env/workingDirectory）+ `SandboxResult`（success/stdout/stderr/exitCode/timedOut/error）（4） | CommandRunner 的入参出参直接 import 这两个类型——超时/环境/结果的语义不另造 | ✅ 契约复用 |
| 沙箱进程模式 | `ProcessSandbox.runProcess`（4：双流读取防死锁 / 超时 destroyForcibly / 输出捕获） | **模式复现非代码复用**：WorkspaceCommandRunner 在 coding 模块内同款实现（该方法 private 且语义锚定临时目录，见 D5） | ✅ 模式复用，蓝图显式记录 |
| 沙箱隔离模型 | `ProcessSandbox.execute(className, code)`（4：临时目录 → javac → java → 自动清理） | **不依赖**：它的隔离哲学是"临时目录用完即焚"，而 mvn test 必须看见真实工作区——形似神异（D5，文章 7 核心素材） | ⚠️ 有意不复用，蓝图显式记录 |
| 修复循环编排 | `Workflow` / `GraphRuntime`（5/6） | **不依赖**：图引擎是无环 DAG（环保护把环当设计错误），修复环把环当方法（迭代收敛）——语义相反，硬套是机制税（D7，对齐 Stage 16 D8 回合不是图） | ⚠️ 有意不复用，蓝图显式记录 |
| 变更摘要结构化 | `StructuredOutputModelClient`（1） | 可选：终态摘要走结构化输出；v1 文本摘要够用 | ✅ 可选，不承诺 |
| 项目知识记忆 | `MemoryStore` + `MemoryContextBuilder`（8） | **v1 不用**："这个项目用 Maven、测试命令是 mvn test"的项目级记忆是 v2 主题（依赖注入减少一档，对照 15/16 都用 memory——本阶段证明不依赖 memory 也能成 Profile） | ⚠️ 有意不复用，v2 回补 |
| 编码轨迹 | `RecordingAgent`（14） | 彩蛋不承诺：包 Coding Agent → 测试通过即 reward 1.0 → 编码行为变 RL 数据（D3 接口预留，v2 验证） | ⚠️ v2 彩蛋 |
| Mock 验收 | `MockModelClient` scripted：`respondText` / `respondToolCalls`（1） | 按序编排"读文件→写补丁→跑测试→失败→修复→再测→摘要"全流程，零 LLM 依赖 | ✅ 同 Stage 8-16 手法 |

### 存量改动清单（预检裁决：零）

**目标：第三次零存量改动**（15 两处枚举加法 / 16 完全零）。路径白名单、命令白名单、补丁暂存、修复计数全部是 coding 模块内部概念；治理三档（AUTO/REQUIRES_APPROVAL/DENY）与审批服务、审计链在 Stage 9 已备齐——**"文件写入和命令执行经过 Policy 检查"的规划红线，用既有治理链拼装即可兑现，一个枚举值都不用加**。

若实现中发现需要动存量：先停下来核对是否真有必要，能靠组合解决的绝不改存量（组合优于修改的纪律第 N 次兑现）；确需加法的，在本节回填并说明向后兼容性。

**依赖方向**：`agent-coding -> agent-core + agent-security + agent-sandbox`（compile）；`agent-model`（test scope）。零新第三方依赖（Jackson 已由 core 传递）。

---

## 3. 核心抽象（17 个，四组）

### 第一组：工作区（workspace 包，M17.1）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `Workspace` | 核心 | 文件系统视图 + 边界：根目录锚点，`resolve(path)` 规范化后必须仍在根内（防 `../` 逃逸与绝对路径），读 API 带大小/深度上限——读也是特权 |
| `WorkspacePolicy` | 数据 | 路径边界 SSOT：denyGlobs（默认 `.git/**`、`.env*`、`*.key`、`target/**` 可配）+ maxFileBytes（单文件读取上限）+ maxTreeEntries（目录树条目上限） |
| `ReadFileTool` | 核心 | `implements Tool`：read_file {path}——读单文件（超限截断并标注）；治理 AUTO（无副作用） |
| `ListFilesTool` | 核心 | `implements Tool`：list_files {path?, maxDepth}——目录树文本渲染（条目上限）；治理 AUTO |

### 第二组：变更与补丁（patch 包，M17.2）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `FileChange` | 数据 | 单文件变更 record：path + ChangeKind（CREATE/MODIFY/DELETE）+ newContent + oldContent（写暂存时快照——供 diff 渲染与 apply 时漂移检测） |
| `Patch` | 数据 | 一次任务的变更集：patchId + List&lt;FileChange&gt; + PatchStatus（DRAFT → VALIDATED → APPLIED / REJECTED / DISCARDED）+ summary——**事务单位：全批 apply 或全批丢弃** |
| `PatchStore` | 核心 | 会话级暂存区：stage（同文件再写 = 替换该条目非叠加）/ latest / apply / discard；apply 前漂移检测（磁盘内容 ≠ 暂存时 oldContent → 拒绝，防审批期间被并发修改的 TOCTOU） |
| `WriteFileTool` | 核心 | `implements Tool`：write_file {path, content}——产出 FileChange 进暂存区，**不落盘**；路径过 WorkspacePolicy；治理 AUTO（暂存无真副作用，D8） |
| `PatchSummarizer` | 核心 | unified diff 渲染（oldContent vs newContent 逐行对比 +/-）+ 变更摘要（N 文件 / 增删行数 / 类型分布）——人审 apply 的输入 |

### 第三组：命令与沙箱（exec 包，M17.3）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `CommandWhitelist` | 核心 | argv 白名单：前缀匹配（`["mvn","test"]` 放行 `mvn test -q`、拒绝 `mvn clean` 若只授了 test 前缀）；**fail-closed：不在表 = 拒**；不含 shell——见 D2 |
| `CommandRunner` | 核心 | **无 shell 执行器**：ProcessBuilder 直传 argv（不经 `/bin/sh`，注入语法无处生根）；工作目录锚定 workspace 根；超时 destroyForcibly；双流读取；输出截断 maxOutputBytes（保留头尾）——入参 SandboxSpec、出参 SandboxResult（契约复用 Stage 4） |
| `RunCommandTool` | 核心 | `implements Tool`：run_command {command: [...]}——治理 REQUIRES_APPROVAL → 白名单 → runner；白名单外返回 [REJECTED] 文本（模型可读可改道） |
| `TestResult` | 数据 | 测试判决 record：passed（exitCode==0）+ exitCode + outputExcerpt（优先 "Tests run:" 行 + 失败尾部摘录）+ timedOut + durationMs |
| `RunTestsTool` | 核心 | `implements Tool`：run_tests——**测试命令装配期注入**（模型不可改：裁判不能由被裁判者指定，D3）→ runner → 解析 TestResult；FAILED 时向 session 报修复计数 |

### 第四组：会话与装配（session 包 + root，M17.4/M17.5）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `CodingSession` | 核心 | 治理壳：持 PatchStore + whitelist + runner + 治理链装配；`apply()/reject()` 人工闸口（apply 真落盘 = 唯一写盘点）；修复计数入口；变更摘要出口——验收流程 8 步的领域宿主 |
| `FixLoopPolicy` | 数据 | 修复环边界：maxFixIterations（默认 3）+ 超限行为（run_tests 返回 [LIMIT] 拒绝再修——模型读到诚实失败，报告而非死磕） |
| `CodingAgentFactory` | 核心 | 装配器：五工具（read/list/write/run_command/run_tests）+ 治理链（ToolPolicy 分档 + ConsoleApprovalService + AuditLogger）+ 编码 systemPrompt（行为契约：先读后改 / 小步补丁 / 测试验证 / 失败自修）→ Agent |

### 3.1 关键接口草图

```java
// ---- 工作区（第一组）----
public final class Workspace {
    public static Workspace open(Path root);          // root 必须已存在且是目录
    public Path resolve(String relative);             // normalize 后必须 startsWith(root)
                                                      // —— 逃逸/绝对路径/空路径全 IAE（fail-fast）
    public String readFile(String path);              // 过 WorkspacePolicy：deny 拒、超限截断标注
    public String listTree(String path, int depth);   // 目录树文本，条目上限
}

public final class WorkspacePolicy {
    // denyGlobs 默认：.git/** .env* *.key ——deny 优先于一切（读也不行）
    public boolean isReadable(Path normalized);       // fail-closed：glob 匹配异常视为不可读
    public static Builder builder();                  // denyGlobs / maxFileBytes / maxTreeEntries
}

// ---- 变更与补丁（第二组）----
public record FileChange(String path, ChangeKind kind,
                         String newContent, String oldContent) {
    public enum ChangeKind { CREATE, MODIFY, DELETE }
}

public final class PatchStore {
    public FileChange stage(String path, String newContent);   // 同文件重写=替换；oldContent 现场快照
    public Patch snapshot();                                   // 当前全部变更（DRAFT）
    public ApplyOutcome apply();        // 唯一落盘点：漂移检测 → 逐文件写（CREATE/MODIFY/DELETE）
    public void discard();              // 整体丢弃，磁盘零变化
    public enum ApplyOutcome { APPLIED, REJECTED_DRIFT }        // drift：磁盘内容 ≠ 暂存时快照
}

public final class WriteFileTool implements Tool {
    // name="write_file"; schema: {path, content}
    // 成功返回："staged: <path> (kind=MODIFY). Nothing written to disk yet."
    // —— 模型被明确告知"还没落盘"，apply 是另一个动作
}

// ---- 命令与沙箱（第三组）----
public final class CommandWhitelist {
    public CheckResult check(List<String> argv);   // 前缀匹配；fail-closed（空 argv/不在表=拒）
    // CheckResult: ALLOWED(reason=null) / DENIED(reason="not in whitelist: curl")
}

public final class CommandRunner {
    public SandboxResult run(List<String> argv);   // ProcessBuilder 直执行（无 shell）；
                                                   // cwd=workspace 根；超时 kill；输出截断保留头尾
}

public final class RunTestsTool implements Tool {
    // name="run_tests"; 无参数——测试命令在装配期注入（模型不可指定，D3）
    // 返回 TestResult JSON；FAILED → session.recordTestFailure() 计数
    // 超过 FixLoopPolicy.maxFixIterations → 返回 [LIMIT] fix budget exhausted
}

// ---- 会话与装配（第四组）----
public final class CodingSession {
    public static Builder builder();               // workspace/whitelist/testCommand/policy/governance
    public Agent buildAgent(ModelClient model);    // 五工具 + 治理链 + systemPrompt
    public PatchSummary reviewPatch();             // 人审输入：unified diff + 统计
    public ApplyOutcome approveAndApply();         // 人闸：审批通过才落盘
    public void rejectPatch();                     // 人闸：拒绝 → REJECTED，磁盘零变化
}

public record FixLoopPolicy(int maxFixIterations) {
    public static FixLoopPolicy DEFAULT;           // 3
}
```

---

## 4. 关键设计决策（9 个）

### D1. 变更即补丁：写盘是特权，Patch 是申请单

```text
WriteFileTool 不碰真实文件系统：变更进 PatchStore 暂存区，apply 才落盘。
这是规划验收流程"生成 Patch → 申请修改权限"的机制化落点——
  "申请修改权限"不是一个工具调用，是 Patch 的生命周期状态迁移

为什么读可以直读、写必须暂存：
  读的副作用为零（AUTO 即可），写的副作用不可逆（文件被覆盖后旧内容消失）
  暂存区把"不可逆动作"改造成"可审查提案"：人看 diff → approve → 落盘
  或 reject → 磁盘零变化——错误修改的最坏结果从"事故"降级为"提案被拒"

对照 Stage 16 D3「变更即指令」：同一哲学的两种生效时机——
  WorldEffect 立即生效（引擎可信：TurnEngine 是唯一 apply 点）
  FileChange 暂存待批（模型不可信：apply 前有人闸 + 漂移检测）
  谁拥有状态、谁可信，决定变更什么时候生效——这不是不一致，是同一原则
  （变更必须经过纪律）按信任水平的参数化

事务性：Patch 是全批单位——apply 要么全部落盘要么整体不落，
  半批落盘（写了 2 个文件第 3 个漂移失败）是最坏状态，v1 用"先全量
  预检漂移、后逐文件写"的两段式把窗口压到最小（诚实边界：进程在写的
  中途被杀的理论窗口 v1 不处理，Stage 6 checkpoint 语义才管这个）
```

### D2. 无 shell 执行：白名单的第一道防御是"不存在 shell"

```text
RunCommandTool / CommandRunner 不经 /bin/sh：ProcessBuilder 直传 argv 数组。
"mvn test; rm -rf /" 作为 argv[1] 只会被 maven 当成一个奇怪的 goal 名报错——
  shell 注入语法（; | && $( ) ` >）在无 shell 世界里只是普通字符串

纵深三道闸（任何单道失守都不是事故）：
  闸 1 治理链（Stage 9）：run_command 整体 REQUIRES_APPROVAL——人先批"要跑命令了"
  闸 2 白名单（本阶段）：argv[0]（+可选 argv[1]）前缀匹配，fail-closed——
     curl/wget/rm 根本进不了白名单，不需要"拦 rm -rf"的黑名单对抗
  闸 3 执行器（本阶段）：工作目录锚定 + 超时 kill + 输出截断——
     白名单内的命令也跑不出工作区、跑不过超时、刷不爆上下文

为什么不用黑名单拦危险命令：黑名单是军备竞赛（rm --preserve-root 的绕法、
  base64 解码再执行、脚本套脚本）；白名单 + 无 shell 让整类问题不存在——
  规划红线"第一版禁止直接执行高风险命令"的兑现方式不是"拦"，是"默认不存在"
```

### D3. 测试即裁判：裁判不能由被裁判者指定

```text
TestResult 是 Coding Agent 独有的内生奖励：编译过没过、测试绿没绿——
  不需要人判断（企业）、不需要数值限幅（游戏），跑一遍就知道。
  与 Stage 14 RewardSource 的天然接口：testPassed → reward +1.0（v2 彩蛋）

关键裁决：测试命令装配期注入，RunTestsTool 无参数——模型不能自选测试命令。
  反例：模型可以 run_command("mvn test -DskipTests") → "全绿" → 交付——
  被裁判者指定了裁判，验证完整性归零。测试命令是 Constitution 不是 choice

修复环的驱动机制（观察引导）：TestResult.outputExcerpt 摘录失败断言与
  预期/实际值 → 模型读到失败观察（Stage 2 工具错误处理哲学）→ 自然进入
  "读失败 → 改补丁 → 再测"的收敛流——修复不是硬编码流程，是 ReAct 的
  自然行为 + 引擎的硬边界（D4）
```

### D4. 修复环有界：边界在引擎，节奏在模型

```text
修复环的两层分工（同构 Stage 16 D4 限幅的两层分工）：
  模型层（自然节奏）：改 → 测 → 读失败 → 修 → 再测——ReAct 循环内模型自主驱动，
    "先读后改、小步补丁"靠 systemPrompt 行为契约引导
  引擎层（硬边界）：RunTestsTool 每次 FAILED → session 修复计数 +1；
    超过 FixLoopPolicy.maxFixIterations（默认 3）→ run_tests 返回
    [LIMIT] fix budget exhausted, report honestly
    —— 模型读到预算耗尽，诚实报告失败（Patch DISCARDED），不再死磕

为什么不把边界交给 maxSteps（Stage 2 已有）：maxSteps 是"总步数"闸——
  它分不清"正在收敛"（读文件 5 步 + 改 2 步 + 测 2 步，健康）和
  "死循环修复"（改→测→败 × 10，失血）——修复计数是领域语义的闸：
  只数"测试失败后的再修复"轮数，健康探索不消耗预算
```

### D5. 沙箱复用裁决：契约与模式复用，隔离模型换壳

```text
复用三样：
  数据契约：SandboxSpec（timeout/env/workingDirectory）作 CommandRunner 入参、
    SandboxResult（success/stdout/stderr/exitCode/timedOut/error）作出参——
    超时/结果的语义不另造第二套
  进程模式：ProcessSandbox.runProcess 的三件套——双流读取（防管道死锁）、
    超时 destroyForcibly、输出捕获——WorkspaceCommandRunner 同款实现
  哲学：资源限制 + 时间限制 + 空间锚定的沙箱三要素

不复用一样：ProcessSandbox.execute(className, code) 本身——
  它的隔离模型是"临时目录 + 用完即焚"（写临时文件 → 编译 → 运行 → 清理），
  这对"执行一段不可信 Java 代码"是对的；
  但 mvn test 必须看见真实工作区（pom、src、依赖）——命令的语义就是
  "在真实文件系统上工作"，临时目录隔离会让命令什么都做不了

结论（文章 7 的检验答案）：阶段 4 的沙箱哲学直接通用（限制时间/空间/资源），
  但隔离模型按场景分壳——不可信代码执行用"临时目录壳"，可信工作区操作用
  "锚定 + 白名单壳"。这不是阶段 4 做错了，是两种威胁模型的两种正确答案：
  前者防"代码本身"，后者防"命令的副作用面"
```

### D6. Git 工作区 v1 的诚实边界：Workspace + Patch 是分支语义的最小模拟

```text
规划"Git 工作区管理"在 v1 的兑现形态：
  Workspace = checkout 的工作目录（只读视图 + 边界）
  PatchStore = 暂存区/staged changes（变更可审查、可整体丢弃、可 apply）
  Patch 状态机 = 变更的审查流（DRAFT → VALIDATED → APPLIED/REJECTED）

不进 v1 的：真实 git 命令（branch/commit/diff/checkout）不进白名单——
  git 的命令面本身就是任意代码执行面（git config core.pagers=...、
  hooks、submodule 的 URL 处理都有已知逃逸），git 治理值得 v2 专题；
  变更回滚用"discard Patch"而非"git checkout --"兑现（暂存区丢弃 =
  工作区从未被碰过，语义更干净）

诚实记录：这不是"完整的 Git 工作区管理"，是"无 git 依赖的分支语义最小集"。
  v2 再决定是真集成（JGit / git 命令白名单子集）还是继续语义模拟
```

### D7. 修复环不是 Workflow 图：环是方法不是错误

```text
GraphRuntime（Stage 5）是无环 DAG 引擎：环保护（maxSteps + 死端检测）把
  环当设计错误防御——这对手工审批流是对的（流程不该绕圈）；
  修复环是迭代收敛方法：改 → 测 → 败 → 修 是领域常态，环就是流程本身

硬套图引擎的机制税：把环表达成"TEST_FAILED 条件边回指 PATCH 节点"——
  与环保护语义打架、无 checkpoint 需求却背 runId/黑板、迭代计数变成
  图深度——每一个都是为绕开引擎假设而付的税（对齐 Stage 16 D8
  「回合是顺序代码不是图」的同款裁决：哪里需要哪里接，不是一切都过图）

顺序代码 + 显式计数器的修复环：3 行 while 语义、计数即领域概念
  （fixIteration）、测试可直断——机制税为零
```

### D8. 审批档位跟着真副作用走，不是跟着工具名走

```text
五个工具的四档分布：
  read_file / list_files  AUTO               —— 零副作用
  write_file              AUTO               —— 暂存区动作，磁盘零变化（真副作用在 apply）
  run_command             REQUIRES_APPROVAL  —— 进程执行，副作用即时
  run_tests               REQUIRES_APPROVAL  —— 同上（v1 从严；白名单+固定命令后可降 AUTO，留运维裁量）
  apply（Session 人闸）    人工 review diff   —— 唯一真写盘点，diff 人审后落盘

原则：无盘上副作用的动作不扰民（读/暂存全自动），有副作用的动作必过闸
  （命令过审批 + 白名单双闸，落盘过人审 + 漂移检测双闸）——
  审批疲劳是真实敌人：什么都批 = 什么都不批（人机械点同意），档位设计
  就是把人有限的注意力预算花在真正不可逆的动作上

对照 Stage 15「工具审批 vs 任务审批」双层：本阶段工具审批（run_command）
  + Patch 人闸（review diff 后 apply）是同一双层的编码域形态——
  前者保"这个动作能做"，后者保"这批变更能落"
```

### D9. 与 Stage 15/16 正交：第三个 Profile 的零存量目标

```text
agent-coding 不依赖 agent-enterprise / agent-tavern（反之亦然）：
  三 Profile 共同底座是 core + security（15/16 另点用 memory，17 不用——
  第三个证明：不依赖 memory 也能成 Profile，Runtime 的最小公约数比想象更小）

三个 Profile 三种领域缺失，同一 Runtime 兜底：
  企业缺归属层（Tenant/User/CostLedger）——谁在问、花谁的钱
  游戏缺世界层（WorldState/Relationship/GameEvent）——灵魂、后果、历史
  编码缺变更层（Workspace/Patch/CommandWhitelist）——可审查的变更、
    受控的命令、内生的裁判
  三次零/近零存量改动 = "同一 Runtime 三类场景"从宣言变成可复核的证据链
  （15 两处枚举加法 / 16 零 / 17 目标零——若兑现，M8 里程碑收官）
```

---

## 5. 分层架构图

```text
┌───────────────────────────────────────────────────────────────────────┐
│ examples: CodingAgentExample（全链路验收剧本 T0-T7）                     │
└───────────────────────────────────────────┬───────────────────────────┘
                                            │
┌───────────────────────────────────────────▼───────────────────────────┐
│ agent-coding（Stage 17 新增）                                          │
│                                                                       │
│  workspace/  Workspace / WorkspacePolicy / ReadFileTool / ListFilesTool│
│              —— 读也是特权：路径白名单 + deny globs + 大小上限          │
│  patch/      FileChange / Patch / PatchStore / WriteFileTool           │
│              / PatchSummarizer                                         │
│              —— D1：变更即补丁，暂存不落盘，apply 是唯一写盘点          │
│  exec/       CommandWhitelist / CommandRunner / RunCommandTool         │
│              / TestResult / RunTestsTool                               │
│              —— D2/D3：无 shell + 白名单 fail-closed + 固定测试命令     │
│  session/    CodingSession / FixLoopPolicy                             │
│              —— D4：修复计数在引擎、节奏在模型；D8：人闸在 apply         │
│  (root)      CodingAgentFactory                                        │
└────┬──────────────────┬──────────────────────────┬────────────────────┘
     │ compile          │ compile                  │ compile（契约复用）
┌────▼───────────┐ ┌────▼─────────────────┐ ┌──────▼─────────────────────┐
│ agent-core     │ │ agent-security       │ │ agent-sandbox              │
│ Agent/Config   │ │ GovernedToolExecutor │ │ SandboxSpec / SandboxResult│
│ /AgentState    │ │ /ToolPolicy（四档）  │ │ （数据契约；ProcessSandbox │
│ /ReActAgentLoop│ │ /ConsoleApproval     │ │  本身不复用——D5 形似神异） │
│ /Tool/Registry │ │ /AuditLogger         │ │                            │
└────────────────┘ └──────────────────────┘ └────────────────────────────┘
  agent-model（MockModelClient）= test scope；
  workflow/scheduler/memory/channel/trace-export/product/enterprise/tavern
  不依赖（D5/D7/D9）
```

数据流（一次编码任务的旅程）：

```text
用户 ──"给 Calculator 加除法方法并补测试"──▶ CodingSession.buildAgent(...)
                                                            │
                                                        ReAct Loop
                                                            │
              ┌─────────────────────────────────────────────┤
              ▼                                             ▼
        list_files / read_file                     write_file(path, content)
        （AUTO：零副作用）                          （AUTO：进暂存区，磁盘零变化）
              │                                             │
              │                                             ▼
              │                                    PatchStore.stage(...)
              │                                    "staged: MODIFY src/.../Calculator.java"
              ▼                                             │
        run_tests（REQUIRES_APPROVAL → approve）            │
              │                                             │
              ▼                                             ▼
        CommandWhitelist.check(["mvn","test"]) ✓   模型读失败摘录 → write_file（修复）
              │                                             │
              ▼                                    （fixIteration < 3 内可再修）
        CommandRunner：无 shell / cwd=workspace / 超时 / 截断
              │
              ▼
        TestResult(passed=false, "Tests run: 1, Failures: 1 …")
              │                                   ┌── 修复环收敛（D4）──┐
              └───────────────────────────────────┴────────────────────┘
                                                            │
                                              run_tests → passed=true
                                                            │
              ┌── reviewPatch()：unified diff + 统计 ──────┤
              ▼                                             ▼
        人审 diff ✓ → session.approveAndApply()      人审 diff ✗ → rejectPatch()
        （漂移检测 → 逐文件落盘 → APPLIED）          （REJECTED，磁盘零变化）
                                                            │
                                                            ▼
                                                  变更摘要：2 文件 / +18 -2 / 测试绿
        审计流水（治理链）：read×2 / write×3 / run_command×0 / run_tests×2
                          / apply×1 全留痕 + DENIED 事件（白名单拒绝也留痕）
```

---

## 6. 完整时序：一个编码任务的剧本

```text
T0: 装配（一次性）
    Workspace.open(~/calc-demo)（一个真实的小 Maven 项目：Calculator + 测试）
    WorkspacePolicy：denyGlobs 默认（.git/** .env* *.key）+ maxFileBytes 64KB
    CommandWhitelist：[["mvn","test"], ["java"], ["javac"]]——curl/rm 不在表
    测试命令（装配注入）：["mvn", "test"]
    FixLoopPolicy：maxFixIterations=3
    治理链：read/list/write AUTO + run_command/run_tests REQUIRES_APPROVAL
           + ConsoleApprovalService(auto) + InMemoryAuditLogger

T1: 理解需求 + 读取代码
    "给 Calculator 加一个 divide 方法并补测试"
    → 模型 list_files("") → read_file("src/main/java/demo/Calculator.java")
    → 只读白名单路径；读 .git/config 会在 T7 演示被 deny

T2: 生成修改计划 + 写补丁（暂存，不落盘）
    模型 write_file(".../Calculator.java", 含 divide 的新全文)
        → FileChange{MODIFY, oldContent=快照} 进 PatchStore
    模型 write_file(".../CalculatorTest.java", 新测试全文)
        → FileChange{CREATE} 进 PatchStore
    → 磁盘字节零变化（验收断言点）；工具返回明示 "Nothing written to disk yet"

T3: 沙箱跑测试（审批 + 白名单 + 失败）
    模型 run_tests → REQUIRES_APPROVAL → approve（auto 模式）
    → 白名单 check(["mvn","test"]) ✓ → CommandRunner（无 shell，cwd=workspace）
    → mvn test 失败（scripted：初版 divide 忘了除零守卫，测试红）
    → TestResult{passed=false, excerpt="expected:<Infinity> but was:<ArithmeticException>"}

T4: 修复环（D4 核心演示）
    模型读失败摘录 → write_file(修正版) （同文件 = 替换暂存条目，非叠加）
    → run_tests → passed=true（fixIteration=1，未超 3）
    → Patch → VALIDATED

T5: 人审 + 落盘（唯一写盘点）
    session.reviewPatch() → PatchSummarizer：
        Calculator.java        MODIFY  +6 -1
        CalculatorTest.java    CREATE  +12 -0
    → 人审 diff ✓ → approveAndApply()：漂移检测（磁盘 == 暂存快照）→ 落盘
    → 磁盘此刻才变化；Patch → APPLIED

T6: 变更摘要
    "2 files changed (1 modify, 1 create), +18 -1 lines, tests green,
     fix iterations: 1"——交付物 = 摘要 + diff，不是一句"改好了"

T7: 治理回看（红线演示）
    audit 全流水：read×N(AUTO+EXECUTED) / write×N / run_tests×2(APPROVED+EXECUTED)
                / apply×1
    三个拒绝演示：
      a) read_file(".git/config")        → WorkspacePolicy deny（读也是特权）
      b) run_command(["curl","evil.com"])→ 白名单 fail-closed → [REJECTED] + DENIED 审计
      c) run_command(["mvn","test; rm -rf /"])
                                         → argv[1] 只是 maven 的怪参数 → mvn 自报错
                                           （无 shell 的天然免疫，D2 实证）

失败分支：
    F1 路径逃逸：write_file("../evil.sh")      → resolve IAE（normalize 出根）
    F2 审批拒绝：apply 人审 ✗                  → REJECTED，磁盘零变化
    F3 修复超界：maxFix=1 场景二次修复 run_tests → [LIMIT] budget exhausted
                                             → 模型诚实报告失败，Patch DISCARDED
    F4 超时：测试命令死循环（scripted 命令）   → runner 超时 kill → timedOut=true
                                             → passed=false（诚实失败，非崩溃）
    F5 巨输出：命令输出 1MB                    → 截断保留头尾（上下文预算保护）
    F6 漂移：审批等待期间文件被人手改           → apply 时 REJECTED_DRIFT（TOCTOU 防御）
    F7 同文件反复写：write 3 次同一路径         → 暂存区 1 条（替换非叠加），diff 干净
```

---

## 7. 模块结构

```text
agent-coding/                                      # 新增 Maven 模块（父 POM <modules> 增补）
└── src/main/java/io/github/qwzhang01/agent/coding/
    ├── workspace/                                 # 4 类（M17.1）
    │   ├── Workspace.java
    │   ├── WorkspacePolicy.java
    │   ├── ReadFileTool.java
    │   └── ListFilesTool.java
    ├── patch/                                     # 5 类（M17.2）
    │   ├── FileChange.java                        # record（含 ChangeKind）
    │   ├── Patch.java                             # record + PatchStatus 状态机
    │   ├── PatchStore.java                        # 暂存区 + apply 漂移检测
    │   ├── WriteFileTool.java
    │   └── PatchSummarizer.java                   # unified diff + 摘要
    ├── exec/                                      # 5 类（M17.3）
    │   ├── CommandWhitelist.java
    │   ├── CommandRunner.java                     # 无 shell / 超时 / 截断
    │   ├── RunCommandTool.java
    │   ├── TestResult.java                        # record
    │   └── RunTestsTool.java                      # 固定测试命令 + 解析 + 计数上报
    ├── session/                                   # 2 类（M17.4）
    │   ├── CodingSession.java                     # 治理壳 + 人闸 + 摘要出口
    │   └── FixLoopPolicy.java                     # record
    └── CodingAgentFactory.java                    # 装配器（M17.5，root）
```

```text
存量改动：零（§2 预检裁决——目标第三次零存量，对照 15 两处加法 / 16 零）
```

```text
examples/（新增 1 个）
└── CodingAgentExample.java     # 验收剧本：T0-T7 全景（读代码 → 暂存补丁 →
                                #   沙箱测试 → 失败修复 → 人审 diff → 落盘摘要
                                #   + F1/F3/F7 拒绝与边界演示）
```

不改动其他任何存量模块（agent-core / agent-security / agent-sandbox 零 diff；examples pom 增补 agent-coding 依赖，同 Stage 16 tavern 先例）。

---

## 8. 实现里程碑（5 个，节奏对齐 Stage 13-16）

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M17.1 | 工作区与读取 | workspace 4 类 + 单测 | 路径逃逸三态全拒（`../` / 绝对路径 / 空白）；denyGlobs 生效（.git/.env 读拒）；单文件超限截断标注；目录树条目上限；resolve 正常路径 round-trip |
| M17.2 | 变更与补丁 | patch 5 类 + 单测 | **暂存不落盘**（写 N 次后磁盘字节零变化断言）；同文件重写=替换非叠加；diff 渲染 +/- 行正确（MODIFY/CREATE/DELETE 三态）；apply 落盘 round-trip；**漂移检测**（apply 前手改文件 → REJECTED_DRIFT）；discard 后磁盘零变化 |
| M17.3 | 命令与沙箱 | exec 5 类 + 单测 | 白名单三态（命中前缀放 / argv[1] 越界拒 / 不在表拒 fail-closed）；**无 shell 注入免疫**（`test; rm -rf /` 作为参数不执行——D2 实证）；超时 kill（timedOut=true 非崩溃）；巨输出截断保留头尾；TestResult 解析（exitCode + "Tests run:" 摘录）；run_command 全链（审批→白名单→执行→审计） |
| M17.4 | 修复循环 | session 2 类 + 单测 | 计数正确（FAILED 才计，passed 不计）；**超界 [LIMIT]**（第 N+1 次 run_tests 拒绝 + 文本可读）；通过即出环（VALIDATED）；Patch 状态机全迁移（DRAFT→VALIDATED→APPLIED / REJECTED / DISCARDED）；rejectPatch 后磁盘零变化 |
| M17.5 | 装配与收口 | CodingAgentFactory + CodingAgentExample + README/笔记收口 | 示例实跑 T0-T7 全剧本（Mock 十响应编排：读→暂存→测试败→修复→通过→人审→落盘→摘要）；三个拒绝演示（deny 读/白名单拒/注入免疫）；全仓存量零影响（零 diff 即证明） |

依赖：M17.2 ← M17.1（路径边界）；M17.3 ← M17.1（cwd 锚定）；M17.4 ← M17.2/M17.3（Patch + 测试计数）；M17.5 ← 全部。主路径串行；M17.2 与 M17.3 可并行。

---

## 9. 验收标准（对齐 18 周规划原文）

```text
规划原文：完成一个受控 Coding Agent 流程：
1. 理解需求        -> CodingSession 收需求，systemPrompt 行为契约（先读后改）
2. 读取代码        -> M17.1 read_file / list_files（读也是特权：白名单 + deny + 上限）
3. 生成修改计划    -> 模型产出（ReAct 自然行为）+ Patch DRAFT 状态就位
4. 申请修改权限    -> M17.2 PatchStore 暂存（写盘是特权，Patch 是申请单）
5. 生成 Patch      -> M17.2 FileChange/Patch/PatchSummarizer（unified diff 可审查）
6. 在沙箱中运行测试 -> M17.3 run_tests（固定命令 + 白名单 + 无 shell + 超时 + 截断）
7. 失败后修复      -> M17.4 修复环（边界在引擎：计数 [LIMIT]；节奏在模型：观察引导）
8. 输出变更摘要    -> M17.2/M17.5 PatchSummarizer（N 文件/增删行/测试绿/修复轮数）

规划红线：第一版禁止直接执行高风险命令，
所有文件写入和命令执行都经过 Policy 检查并在沙箱中执行
  -> 文件写入：write=暂存（AUTO 无真副作用）+ apply=人闸 + 漂移检测（D1/D8）
  -> 命令执行：REQUIRES_APPROVAL + 白名单 fail-closed + 无 shell + 超时（D2）
  -> 高风险命令：不在白名单即不存在（默认拒绝而非拦截对抗，D2）

「需要支持」九项对照：代码读取=M17.1 / 文件修改=M17.2 / Patch=M17.2 /
命令执行（沙箱内）=M17.3 / 测试=M17.3 / 自动修复=M17.4 / 审批=M17.2-4
（治理链+人闸）/ 沙箱（复用阶段 4）=M17.3（D5 契约与模式复用，隔离模型换壳）/
Git 工作区管理=M17.1+M17.2（D6 诚实边界：Workspace+PatchStore=分支语义最小集）
```

---

## 10. 测试策略

- **路径安全（最高优先级）**：逃逸三态（`../` 链 / 绝对路径 / 编码空白）全拒；denyGlobs 默认集逐条；deny 优先于白名单（同路径两表冲突时拒）；glob 匹配异常 fail-closed 视为不可读
- **暂存纪律**：写 N 次（含同文件反复写）后磁盘字节零变化（Files.mismatch 全量断言）；暂存条目数 = 去重路径数；discard/REJECTED 后磁盘依旧零变化——"没 apply 就没写盘"的不变量贯穿全部 patch 测试
- **漂移检测（TOCTOU）**：stage 后手改磁盘文件 → apply 拒 + 磁盘保持手改后内容（不覆盖人的修改——被拒 apply 也不许有半点副作用）
- **注入免疫**：`["mvn", "test; rm -rf /"]` 不执行注入（mvn 自报错或白名单按 argv 拒）——无 shell 的可执行证明；shell 元字符集（`;` `|` `&&` `$(` `` ` `` `>`）出现在参数里仅是普通字符串
- **白名单语义**：前缀命中放行 / 前缀越界拒（授权 `["mvn","test"]` 时 `mvn clean` 拒）/ 不在表 fail-closed / 空 argv 拒
- **执行器边界**：超时 destroyForcibly（timedOut=true + 非零退出诚实报告）；输出截断保留头尾且总字节 ≤ maxOutputBytes；cwd 锚定 workspace 根（命令产生的工作区文件落在根内）
- **修复环**：FAILED 计 / passed 不计 / [LIMIT] 文本进对话模型可读（捕获 ModelRequest 断言） / 超界后 Patch DISCARDED
- **审批链**：run_command/run_tests 未批拒（[DENIED] + DENIED 审计）；apply 人审拒绝 → REJECTED；四档分布与 ToolPolicy 一致（装配断言）
- **审计完备**：read/write/run_tests/apply 全留痕；白名单拒绝也留 DENIED 事件（denied is intelligence，对齐 Stage 9 D6）
- **向后兼容**：零存量改动——全仓存量测试零 diff 即证明
- **Mock 验收**：全链零 LLM（MockModelClient scripted 编排 T0-T7，同 Stage 8-16 手法；真实 mvn 场景在 Example 中真跑一次）

---

## 11. 文章规划（规划原文 7 篇全收）

| 文章（规划原文） | 写作时机 | 素材来源 |
|---|---|---|
| 《Coding Agent 的本质是一个受控的软件工程循环》 | M17.1/M17.2 | §1 五假设破裂总纲 + "输出即变更"命题（三 Profile 递进收官篇） |
| 《Workspace、Patch 和 Command 如何建模》 | M17.2/M17.3 | §3 四组 17 抽象 + 三个"状态的家"对比（Workspace/WorldState/MemoryStore） |
| 《从需求到 Patch：Coding Agent 的状态机》 | M17.2 | D1 + Patch 状态机（DRAFT→VALIDATED→APPLIED/REJECTED/DISCARDED）+ 两层状态（AgentState 对话流 / Patch 变更流） |
| 《测试失败后 Agent 如何进入修复循环》 | M17.4 | D3/D4：测试即裁判（裁判不可被指定）+ 边界在引擎节奏在模型 + maxSteps 为什么不够 |
| 《Coding Agent 为什么必须具备可观测性和审批》 | M17.4/M17.5 | D8 审批四档（档位跟着真副作用走）+ 审计流水 + 审批疲劳敌人 |
| 《Coding Agent 的文件权限和命令权限设计》 | M17.3 | D1/D2 + 两道闸两粒度（工具权限 vs argv 白名单）+ 无 shell 第一防御 |
| 《Coding Agent 与沙箱：阶段 4 的实战检验》 | M17.3/M17.5 | D5 检验结论：哲学通用（时/空/资源限制），隔离模型分壳（临时目录壳 vs 锚定白名单壳）——两种威胁模型两种正确答案 |

**系列衔接**：文章 1 是三 Profile 总纲收官（M8 里程碑"三类场景同 Runtime"的完成叙事）；文章 7 回扣 Stage 4（沙箱阶段的实战检验是当初设计它的全部意义）；文章 4 与 Stage 14 RewardSource 预留接口（测试=内生奖励，编码轨迹=RL 数据的 v2 叙事钩子）。

---

## 12. 本阶段不做（范围控制）

- **真实 git 集成（branch/commit/diff/checkout 命令）** —— D6：v1 用 Workspace + PatchStore 模拟分支语义最小集；git 命令面本身是任意执行面，治理留 v2 专题
- **行级 diff 数据结构** —— FileChange 存全量内容，渲染时生成 diff；行级最小编辑距离算法（Myers）留 v2（摘要场景全量对比够用）
- **多 Patch 并行 / 变更依赖图** —— v1 单 Patch 整批事务；文件间依赖（改接口+改实现+改测试的拓扑序）v2
- **记忆驱动的项目知识** —— "此项目用 Maven、测试命令是 mvn test"的跨会话记忆（MemoryStore agent scope）是 v2 顺手活（对照 15/16 都用 memory，17 证明不用也能成 Profile）
- **编码轨迹 → RL（RecordingAgent 包 Coding Agent）** —— D3 接口预留（testPassed→reward），v2 彩蛋不承诺
- **LSP / AST 级代码理解** —— v1 纯文本读（read_file + 截断）；符号级导航是编辑器域
- **增量测试 / 测试选择** —— 全量跑测试命令；按变更选测试子集 v2
- **Docker / WASM 沙箱升级** —— 阶段 4 范围（README 已声明无 Docker/WASM/资源池）；本阶段在进程档内深化
- **并行修复（多假设分支）** —— v1 串行修复环；"生成 3 个修复假设并行测试选优"是 v2 与 Stage 11 编排的交叉点
- **IDE / LSP 前端集成** —— 库形态，无 UI

---

## 13. M17.1 实现记录（2026-08-24，工作区与读取）

### 交付

- 新增 `agent-coding` Maven 模块（父 POM `<modules>` + dependencyManagement 两处注册；compile 依赖**仅 `agent-core`**——「依赖随用随加」纪律：agent-security 留 M17.2 治理链、agent-sandbox 留 M17.3 CommandRunner 契约复用时再加）
- **零存量改动兑现**：除父 POM 两处注册外，agent-core / agent-security / agent-sandbox 等存量模块零 diff——**全仓 921 测试全绿**（Stage 16 收官 885 + coding 36），存量零影响，第三次零存量目标首个里程碑兑现
- **workspace 包 4 类**：
  - `WorkspacePolicy`（路径边界 SSOT：denyGlobs 默认六条 `[.git, .git/**, .env*, **/.env*, *.key, **/*.key]` + maxFileBytes 64KB + maxTreeEntries 500 + **maxDepth 4**——第 4 字段超蓝图补充（蓝图 §3.1 草图 `listTree(path, depth)` 的深度上限需要家，同 M15.1/M16.2 先例）；**isDenied 祖先传播**：deny 目录名即 deny 整棵子树（`.git` 命中则 `.git/hooks/pre-commit` 免逐条匹配）；glob **构造期 fail-fast**——Builder.build() 即编译 PathMatcher，语法错（如 `"["`）当场抛 IAE，不留到运行期）
  - `Workspace`（视图 + 边界，非状态副本：**两层路径安全**——词法层 `resolve()` normalize+startsWith 拦逃逸三态（空白/绝对路径/`../` 链），真实层 `readFile()` toRealPath 拦 **symlink 逃逸**（根内 symlink 指根外文件 → 拒）；`readFile` 超限**流式读前 N 字节**（readNBytes，不整载大文件）+ `[TRUNCATED: showing X of Y bytes]` 标注；`listTree` 确定性输出——每目录按名排序（LinkedHashMap 教训的落地：凡渲染内容必须显式选择顺序语义）、denied 条目与 symlink 全隐形、目录尾 `/`、**共享游标单一截断点**（跨递归一个计数器、至多一条 TRUNCATED 标记、恰好耗尽且无剩余时零标记——"没有隐藏任何东西就不说截断"））
  - `ReadFileTool`（薄翻译器：workspace 的 IAE → ToolException 模型可读可自愈，内容 verbatim 流过；治理层天然 AUTO 候选——javadoc 注明 D8）
  - `ListFilesTool`（默认根 + 默认 depth 2；depth 超上限返回**带范围的拒绝**而非静默钳制——fail-fast 诚实；description 动态拼 policy.maxDepth 让模型知道边界）
- 测试 36 个：`WorkspacePolicyTest` 7（默认集逐条代表性匹配含"名字含 .git 的正常文件不误伤" / **祖先传播四向** / 自定义 glob 整替默认 / 语法错 fail-fast / builder 校验 / null 拒）+ `WorkspaceTest` 17（**逃逸三态全拒**（null/空白/`../` 链×3/绝对路径×2）/ resolve round-trip 含 `./` 与 `src/../` 冗余归一 / deny 默认集 5 路径全拒 / 正常读 verbatim + 空文件 `(empty file)` 诚实标注 / **超限截断**（200 字节限 50 → 首行恰 50 字节 + 标注）/ **symlink 逃逸**（根外 secret 经根内 shortcut 读 → IAE "symbolic link escapes"）/ **目录树渲染**（排序 + 目录尾 `/` + denied 双隐形 + 精确 6 行）/ depth 0 只列直接子项 / depth 超上限拒不钳制 / **条目预算截断恰一条标记** / **预算恰好耗尽零标记**（静默隐藏是最坏状态的反向证明）/ symlink 不列出 / 子树/空目录/非目录基座/denied 基座四态）+ `ReadFileToolTest` 6（读/缺参/逃逸/deny/缺文件五类错误全 ToolException 可读 + metadata）+ `ListFilesToolTest` 6（默认根+默认深度 / 显式路径深度输出保持 workspace 相对路径可直喂 read_file / depth 0 / 超上限含范围 / 非目录 / metadata 含上限数字）

### 实现期坑 3 条（记入防复发）

1. **listTree 截断边界 bug（实现侧，设计审视抓住）**：首版用递归返回值累计 + 递归后 `listed >= max` 提前 return——两种坏态：恰好达上限时静默跳过剩余条目（无标记隐藏）、子层触发标记后外层再列下一条会二次标记。重构为共享游标 `int[] listed / boolean[] truncated` 单一截断点：计数一处、标记至多一条、"恰好耗尽且无剩余"自然零标记。教训：**递归 + 预算 = 返回值累计是 bug 农场，共享游标才是单一事实源**（与 M14.3 logicalMessages「两处各写一份=静默腐化」同族）
2. **测试侧 5 处**（对齐 M16.1 教训「测试失败先核对自己算式和语义」）：depth 语义数错（depth=2 从根列到 `src/main/java/` 目录行——目录自身列出、内容才需 depth 3，测试期望少算一层）/ `"src/../pom.xml"` 期望值笔误写成 `src/pom.xml`（normalize 后是 `pom.xml`）/ 两处 NPE-vs-IAE 断言错（requireNonNull 抛 NPE，参数校验才是 IAE）/ `config/server.key` 未先建目录 NoSuchFile。**实现侧零 bug**，36 测试一次修复后全绿
3. **环境**：`mvn -version` 显示 Maven 走的 JAVA_HOME 是 Tencent JDK 8（`java -version` 是 17 的——两者不同源），必须显式 `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`（README 备忘重演；opt 符号链接路径曾有 permission denied，直接用 Cellar 真实路径最稳）

### 与蓝图的一致性

- 交付对齐：workspace 4 类，签名与 §3.1 草图一致（open/resolve/readFile/listTree + isDenied）；M17.1 五条验证全过（逃逸三态 / denyGlobs 默认集逐条 / 超限截断标注 / 目录树条目上限 / resolve round-trip）
- **超蓝图防御增强（诚实记录）**：symlink 两道防御（readFile toRealPath 逃逸检查 + listTree 不列 symlink）——蓝图未承诺，实现时补上（symlink 是词法检查拦不住的真实逃逸向量：`resolve("shortcut.txt")` 词法上在根内，指向却可在根外）；v1 边界：symlink 目录不跟随、不列出（javadoc 注明 no-follow / no-spoofing）
- **默认 deny 集口径扩展**：蓝图写三条（`.git/**` `.env*` `*.key`），实现为六条（补 `.git` 目录自身 + `**/` 嵌套变体）——`.git` 目录自身靠祖先传播生效、嵌套的 `.env.local`/`server.key` 靠 `**/` 变体覆盖（测试 `config/.env.local`、`config/server.key` 实证）
- **glob fail-closed 口径升级**：蓝图测试策略写"glob 匹配异常 fail-closed 视为不可读"，实现为**构造期 fail-fast**（build() 即编译，语法错当场抛）——比运行期 fail-closed 更早更诚实：坏模式不该等到第一次匹配才暴露，更不该静默放行
- 依赖随用随加第四次执行：pom 仅 agent-core（对照蓝图 §2 的 agent-security/agent-sandbox 推迟落点）

---

## 14. M17.2 实现记录（2026-08-24，变更与补丁）

### 交付

- **patch 包 6 文件**（蓝图 5 类 + `ApplyResult` sealed 第 6 文件——蓝图草图的 enum 升级为 sealed 两 record：drift 拒绝需要 path + reason 细节，enum 装不下；同 M16.2 TurnResult 先例），+36 测试全仓 **957 全绿**（存量 921 零影响，pom 零变化——workspace 是模块内依赖无需新外部依赖，零存量改动继续兑现）
- **`FileChange`**（record：path + ChangeKind 三态 + newContent + oldContent；紧凑构造器按 kind 校验内容不变量——CREATE 不得带基线、DELETE 不得带内容；**全量内容存储**（行级 delta/Myers 留 v2 诚实边界）；oldContent = stage 时刻磁盘快照 = 漂移基线）
- **`Patch`**（record + wither 迁移生成新实例旧例留档——RewardResult.applyTo 纪律；**summary 非字段**：由 Summarizer 派生，避免双份事实源（蓝图字段清单的诚实偏离）；patchId "P-N" 会话内递增确定性可测；状态机 DRAFT → VALIDATED → APPLIED / REJECTED / DISCARDED，**DRAFT → APPLIED 直达允许**——人闸可批未测试的 patch，状态机不立法流程，流程归 M17.4 Session）
- **`PatchStore`**（暂存区核心）：
  - **暂存不落盘不变量**：stage/stageDeletion 只记 FileChange + 快照基线，apply 是唯一写盘点
  - **同路径重写 = 替换非叠加**（修复环形态）；基线 = **最新一次 stage 时刻**的磁盘快照（诚实边界：中途手改后重 stage 会刷新基线——"此条变更的基线是 stage 时刻的磁盘"，javadoc 明写）
  - **VALIDATED 后冻结 staging**（IAA 提示 discard first）——测试通过的 patch 进入人审冻结态，修复环只在 DRAFT 内转
  - **两段式 apply**：先全量漂移预检（任一不过整批拒）后逐文件写；**四类漂移**全覆盖（MODIFY 内容变 / CREATE 撞车同名文件出现 / 文件消失 / 路径变 symlink——后两者也是词法检查之外的真实 TOCTOU 向量）
  - **被拒 apply 零副作用**：磁盘保持现状（含手改内容），CREATE 不半批落盘
  - discard/reject 返回**终态 Patch**（审计事实可断言）；apply/discard/reject 三路关闭后下次 stage 开新 patchId
  - stage 路径安全：继承 workspace 逃逸/deny 两层 + 自有规则**拒绝 symlink 路径**（写穿 symlink 可能写出根外——写比读严）
- **`WriteFileTool`**（确认文本明示 **"Nothing written to disk yet"** + 暂存计数——模型被显式告知落盘是另一个被审批的动作，不是悄悄发生的）
- **`PatchSummarizer`**（人审渲染：**公共前缀/后缀 diff**——中段一删块一增块，非最小编辑脚本（Myers v2），hunk 头 `@@ -s,c +s,c @@` 无上下文行；CREATE/DELETE 用 /dev/null 惯例；行数只计中段——重 stage 出相同内容显示 `(no textual change)` + `+0 -0` 诚实标注；**尾部换行无 phantom 空行**（"a\nb\n" 是 2 行不是 3 行，中间空行保留））

### 测试 36 个

- `PatchStoreTest` 17：**暂存不落盘**（5 次 stage 含删除与重写后磁盘逐字节 Arrays.equals 断言 + 文件集合不变）/ **重写=替换**（条目数不变 + 最新内容胜出 + 基线仍是磁盘快照）/ kind 推导四态（缺文件 DELETE 拒）/ **staging 路径安全五向**（逃逸/绝对/deny/.env/symlink/目录）/ **apply round-trip**（MODIFY 覆盖 + CREATE 建深层父目录 + DELETE 删除，全批一次生效）/ apply 后关店重开 P-2 / **漂移四态**（手改后整批拒 + 磁盘保留手改内容 + CREATE 不半落 / CREATE 撞车 / 文件消失 / 换 symlink）/ discard/reject 磁盘零变化 + 终态断言 / VALIDATED 冻结 staging / DRAFT 直达 apply 允许 / 空店四守卫 / 双重关闭拒
- `FileChangeTest` 5：kind×内容不变量全矩阵（CREATE/MODIFY/DELETE 各自的正反例）+ Patch 守卫与 wither
- `WriteFileToolTest` 7：MODIFY 确认文本四要素（staged:/kind=/Nothing written/staged count）/ CREATE 计数增长 / 重写计数稳定 / 缺参五态 / 逃逸与 deny 可读 / **空串 content 合法**（空文件是正当写入，null 不是）/ metadata 含 staging 语义
- `PatchSummarizerTest` 7：MODIFY diff（头尾裁剪 + hunk 行号 `-2,2 +2,1` + 上下文行不误标）/ CREATE 全 + / DELETE 全 - / identical `(no textual change)` + 0/0 / 行数只计中段 / summarize 头行（kind 分布 + 增删行总数）/ 空内容零行

### 实现期坑 3 条（记入防复发）

1. **lines() phantom 空行（实现侧真 bug，6 个 diff 测试集体失败暴露）**：`split("\n", -1)` 对尾部换行产生空串尾元素——"a\nb\n" 算出 3 行，javadoc 承诺与实现相反。修复 = 去尾部空元素保中间空行（"a\n\nb\n" 仍是 [a,"",b]）。教训：**按行处理字符串的 trailing-newline 语义要在写第一个 diff 断言前想清楚**——它决定所有行号和计数
2. **测试侧 5 处**（对齐 M16.1/M17.1 教训）：FileChange 构造参数顺序颠倒×2（record 第 3/4 参是 newContent/oldContent，手滑写反——diff 的 -/+ 行正好对调，失败信息一目了然）/ `Map<Path,byte[]>` 的 equals 是引用比较（byte[] 不重写 equals——必须逐 key `Arrays.equals`）/ stagingPathSafety 假设 setUp 建了 src 目录（本测试类没建——断言"目录不可 stage"前目录得先存在）/ NPE-vs-IAE 断言（requireNonNull 是 NPE）/ summarize 行数手算错（+3 -5 写成 +5 -5）。**实现侧仅 phantom 一处真 bug**
3. **agent-scheduler flaky 重现**：全仓跑偶发 1 Error 单独重跑全绿——M16.1 已记录的 TaskSchedulerTest 异步时序竞态，与 coding 无关（coding 不依赖 scheduler），维持"留观察不修"

### 与蓝图的一致性

- 交付对齐：patch 5 类全落地 + 蓝图五条验证全过（暂存不落盘字节断言 / 同文件重写=替换 / diff 三态 / apply round-trip / 漂移检测含"磁盘保持手改内容"）
- **蓝图偏差四处（诚实记录）**：① `ApplyOutcome` enum → `ApplyResult` sealed interface + Applied/DriftRejected 两 record（drift 要 path + reason，enum 装不下细节）；② `Patch.summary` 字段删除 → Summarizer 派生（单一事实源，不变更不腐化）；③ `stageDeletion` 独立 API（蓝图草图只有 stage(path, newContent)，"null=删除"的隐式约定易误触——显式 API 不可能传错；工具层触发者 M17.5 决定是否暴露 delete_file）；④ `markValidated`/`reject` 机制 API 提前落位（状态机一处家，触发者 M17.4 Session 接线——同 M16.2 relationshipDescriber 注入点先立后接手法）
- v1 诚实边界三处：apply 中途 IO 失败不回滚（两段式最小化窗口，Stage 6 checkpoint 语义才管恢复）；diff 非最小编辑（前缀后缀算法）；hunk 无上下文行

---

## 15. M17.3 实现记录（2026-08-24，命令与沙箱）

### 交付

- **exec 包 5 类** + pom 增补 `agent-sandbox` 依赖（蓝图 D5 预告的落点，「依赖随用随加」第五次执行），+37 测试全仓 **994 全绿**（存量 957 零影响，除父 POM/pom 外存量模块零 diff，零存量改动继续兑现）
- **`CommandWhitelist`**（闸 2：**argv 前缀匹配**——规则是 argv 的前缀才放行（`[mvn,test]` 放 `mvn test -q`、拒 `mvn clean`）；规则比 argv 长不匹配（授权的是完整命令，短 argv 是另一个命令）；fail-closed 四态（null/空/空白元素/不在表）全拒；`CheckResult` 嵌套 record + `summary()` 供 [REJECTED] 反馈列出可用命令——模型可读可改道）
- **`CommandRunner`**（闸 3：**无 shell**——ProcessBuilder 直传 argv，注入语法无处生根；**cwd 锚定 workspace 根**（`spec.workingDirectory` 被有意忽略——锚定是闸不是提示，javadoc 诚实注明）；超时 destroyForcibly；双流读取防管道死锁（ProcessSandbox.runProcess 同款**模式复现**）；**头尾截断**：每流预算内全量放行、超预算头半+尾半+**真实总字节**标注、**hard cap 4×预算**防 OOM（超 cap 时尾诚实降级但标注报真总数——`seq 1 100000` 的 588895 字节实测）。入参 SandboxSpec、出参 SandboxResult——**D5 契约复用完整兑现**：ProcessSandbox 本身不复用（临时目录模型 ≠ 工作区锚定模型，形似神异））
- **`TestResult`**（判决 record：`passed = exitCode==0 && !timedOut`——超时是诚实失败非崩溃；excerpt 证据链：surefire `"Tests run:"` 摘要行优先 + 失败尾部 15 行 + `[TIMED OUT]` 标注 + **无模式时退化尾部**（通过运行不显示 "(no output)"——测试驱动的设计修正）；与 Stage 14 RewardSource 的接口在 javadoc 注明（passed→+1.0，v2 彩蛋））
- **`RunCommandTool`**（argv 数组参数解析四态校验；白名单拒 → `[REJECTED]` + 可用命令列表；成功 → 结构化 JSON（success/exit_code/timed_out/stdout/stderr）——治理链 REQUIRES_APPROVAL 在 M17.5 装配层接线，工具内职责=参数粒度白名单）
- **`RunTestsTool`**（**固定裁判**：测试命令装配期注入、schema 无参数、arguments 显式忽略——D3「裁判不能由被裁判者指定」的机制兑现，测试实证模型走私 `mvn test -DskipTests` 无效；**固定命令也过白名单**——白名单是命令面 SSOT，装配必须显式授权测试命令（蓝图 T0 `[mvn,test]` 正是此意）；FAILED → `onTestFailure` listener（M17.4 修复预算的接线点，null 容忍））

### 测试 37 个

- `CommandWhitelistTest` 7：前缀命中（含 `-Dtest=` 长尾参数）/ argv[1] 越界拒（reason 含 "mvn clean"）/ 不在表 fail-closed（curl/rm -rf/sh -c 全拒）/ 规则长于 argv 拒 / 畸形 argv 四态 / 规则校验 fail-fast / summary
- `CommandRunnerTest` 11（真子进程 POSIX 实测）：echo 基本执行 / 非零退出诚实失败 / **cwd 锚定**（pwd == workspace 真实路径）/ **spec.workingDirectory 被忽略**（塞 /tmp 仍锚定）/ **超时 kill**（sleep 30 + 300ms → timedOut=true 非异常）/ **预算内截断**（seq 1 1000 ≈3.9KB 超 2KB 预算：头 "1\n2\n" + 尾 "999\n1000\n" + 真实总数标注）/ **超 hard cap 诚实降级**（seq 1 100000 588895 字节：头保留 + 标注报真总数）/ 预算内全量 / spec env 透传（printenv 实测）/ 未知命令启动失败 / 畸形 argv fail-fast
- `RunCommandToolTest` 7：白名单内 JSON 结果 / **[REJECTED] 带可用命令列表** / 前缀作用域 / **D2 核心实证——注入免疫**（白名单 echo + 参数 `"x; rm marker.txt"`：payload 被 echo 原样打印 + marker 文件健在——没有 shell 解释它，可执行的安全证明）/ 畸形参数四态 / 非零退出诚实 / metadata
- `TestResultTest` 5：passed 双条件语义（超时=失败）/ excerpt 摘要行优先+尾部 / 通过无尾部 / [TIMED OUT] 标注 / 空输出诚实
- `RunTestsToolTest` 7：通过 JSON / 失败通知 listener 恰一次 / 通过不通知 / **超时通知**（timedOut 进 verdict + listener）/ **arguments 被忽略**（走私命令无效，固定裁判照跑）/ 测试命令未授权白名单 [REJECTED]（SSOT 语义）/ 构造守卫 + metadata（description 明示固定命令与不可改性）

### 实现期坑 4 条（记入防复发）

1. **CheckResult 静态工厂与 record 访问器签名冲突**（编译错）：组件 `allowed` 的访问器是 `allowed()`，同名无参静态工厂签名冲突——工厂改名 `granted()`/`denied()`（动词命名避开组件名）。教训：**record 里定义工厂时先看组件访问器占用**
2. **pwd/@TempDir symlink 陷阱**：macOS `@TempDir` 在 `/var/folders`（→`/private/var` symlink），`pwd` 输出**真实路径** `/private/var/...`，而 `toAbsolutePath().normalize()` 不解析 symlink——断言必须用 `toRealPath()`（M17.1 Workspace realRoot 缓存解决的同源问题在测试侧重现：词法路径 ≠ 真实路径）
3. **hardCap 语义的测试数据设计**：首版截断测试用 `seq 1 100000`（588KB）超 hardCap（4×2048=8KB）→ buffer 截停在 cap、**尾部不是真尾**——测试数据要分两档：`>预算且<hardCap`（seq 1 1000 ≈3.9KB，头尾都真可断言 endsWith）与 `>hardCap`（断言标注里的真实总字节数 588895）
4. **截断标注字节数手算错**：断言 "showing 4096" 实际 2048——标注是 `half×2 = maxOutputBytes`（头+尾=预算），不是 2×预算。断言前先手算（M17.2 summarize 行数同款教训）

### 与蓝图的一致性

- 交付对齐：exec 5 类 + 六条验证全过（白名单三态 / **无 shell 注入免疫**（D2 的可执行证明：payload 原样打印 + marker 健在）/ 超时 kill / 巨输出头尾截断 / TestResult 解析 / run_command 全链）
- **D5 完整兑现**：SandboxSpec/SandboxResult 数据契约复用（timeout/env/结果语义零重造）+ runProcess 进程模式复现（双流/超时/捕获同款）+ ProcessSandbox.execute 不复用（临时目录隔离 ≠ 工作区锚定，文章 7 素材落地）；pom 此刻才加 agent-sandbox 依赖
- **D3 机制兑现**：RunTestsTool 无参数 + 固定命令过白名单（装配必须显式授权）+ argumentsIgnored 实测（模型走私无效）；`onTestFailure` listener 是 D4 修复预算的 M17.4 接线点（先立机制后接线，同 M16.2 注入点手法）
- 蓝图偏差：无重大偏差；两处超蓝图小改进（通过场景 excerpt 退化尾部、beyondHardCap 降级测试）诚实记录

---

## 16. M17.4 实现记录（2026-08-24，修复循环）

### 交付

- **session 包 2 类** + exec 包两处演进，+15 测试（FixLoopPolicyTest 2 + CodingSessionTest 13）全仓 **1009 全绿**（存量 994 零影响——M17.4 的演进全在 agent-coding 模块内部，零存量改动继续兑现）
- **`FixLoopPolicy`**（record：maxFixIterations 默认 3；校验 **≥1**——0 与"至少跑一次初始测试"矛盾：不跑测试的编码会话没有裁判，语义在测试驱动下澄清并收紧）
- **`CodingSession`**（治理壳，验收流程 8 步的领域宿主）：
  - **工具工厂五件套**（M17.5 CodingAgentFactory 的装配素材）：readFileTool / listFilesTool / writeFileTool / runCommandTool / **runTestsTool()**（返回 LimitedTestsTool 装饰器——非裸 RunTestsTool）
  - **人闸三口**：`reviewPatch()`（Summarizer 渲染 unified diff——人审输入）/ `approveAndApply()`（唯一真落盘）/ `rejectPatch()`（REJECTED 磁盘零变化）+ `discardPatch()`（显式丢弃）+ `activePatch()` 观察
  - **`LimitedTestsTool` 嵌套装饰器（D4 的机制载体）**：execute 前检查 `failedRuns >= max` → 返回 **[LIMIT] fix budget exhausted**（文本含"report honestly"引导诚实失败 + "staged patch is kept for review"证据提示）；委托执行 → FAILED 计数、passed 则 DRAFT 补丁自动 `markValidated()`（**通过即出环**）；白名单拒绝不计预算（命令根本没跑）；description 动态标注 fix budget 让模型预知边界
- **exec 包两处演进（模块内，诚实记录）**：
  - `RunTestsTool`：**M17.3 的 onTestFailure listener 被 `run()` 结构化返回取代**——Session 直接消费 TestResult verdict，无需回调管道（回调 vs 直返的取舍在接线时才看清：装饰器需要 verdict 本体而非通知）；4 参构造删，增 `run(JsonNode) → TestResult` + `whitelistRejection() → Optional<String>`（结构化的白名单拒路径，供装饰器前移检查——拒绝不计预算）
  - `TestResult.toJson()`：record 自带 JSON 投影——工具层渲染与 Session 装饰器共用一处（消灭 M17.3 版 render 的复制风险）
- **蓝图 F3 语义细化**：[LIMIT] 时补丁**不自动 DISCARDED**——暂存内容是"改了什么而失败"的复盘证据，自动销毁证据是反模式；`discardPatch()` 显式 API 留给人/装配层决定（诚实偏离：蓝图 F3 写"Patch DISCARDED"，实现细化为"证据保留 + 显式丢弃"）

### 测试 15 个

- `FixLoopPolicyTest` 2：默认 3；<1 拒（0 与负数——初始测试必须可跑的语义锁定）
- `CodingSessionTest` 13（真子进程扮演裁判：`echo ok` = 通过套件、`ls /不存在` = 失败套件）：
  - **FAILED 才计数**（通过 0 / 失败×2 → 2）
  - **[LIMIT] veto**（max=1：一次失败后第二次返回 [LIMIT] 文本，预算不再消耗）
  - **[LIMIT] 保留补丁证据**（max=1 失败后 veto，暂存 patch 仍 DRAFT 可复盘）
  - **通过即 VALIDATED**（stage → 测试过 → DRAFT→VALIDATED）
  - **修复环端到端**（失败 1 次 → 重 stage → 通过 → VALIDATED + 预算停 1）
  - reviewPatch 渲染（文件数 + +++ 头 + diff 行）/ 空店 fail-fast
  - **approveAndApply 全批落盘**（2 文件 APPLIED + 磁盘断言 + 店清空）
  - **rejectPatch REJECTED 磁盘零变化** / discardPatch DISCARDED 磁盘零变化
  - 工具工厂五件套 metadata（run_tests description 含 Fix budget 与数值）
  - builder 校验三态 / **白名单拒不计预算**（命令没跑）
- `RunTestsToolTest` 同步更新（7 个）：listener 三测试改为 `run()` 结构化断言（failed verdict 直读 / timedOut 进 verdict / whitelistRejection Optional）

### 实现期坑 2 条（记入防复发）

1. **maxFix=0 语义矛盾（测试驱动裁决）**：首版 policy 允许 0，但 veto 检查 `failedRuns >= max` 在 max=0 时连第一次测试都拒——"测试即裁判"的流程前提是初始测试必须可跑。裁决：校验收紧 ≥1（javadoc 写明理由）。教训：**边界参数的极端值要跑一遍流程语义再定合法性**，"0 看起来合法"和"0 在流程里成立"是两回事
2. **description 断言大小写**（第二次犯，M17.3 同款）：实现写 "Fix budget"、测试断言 "fix budget"——contains 大小写敏感。教训固化：**断言自然语言片段前先核对大小写或统一用小写源**

### 与蓝图的一致性

- 交付对齐：session 2 类 + 五条验证全过（FAILED 才计 / 超界 [LIMIT] 文本可读 / 通过即出环 VALIDATED / Patch 状态机全迁移 DRAFT→VALIDATED→APPLIED\/REJECTED\/DISCARDED / rejectPatch 磁盘零变化）
- **蓝图偏差三处（诚实记录）**：① M17.3 的 onTestFailure listener → run() 直返 verdict（接线时看清：装饰器要 verdict 本体不要通知——机制演进记录进 RunTestsTool javadoc）；② F3 的"[LIMIT] → Patch DISCARDED"细化为"证据保留 + discardPatch() 显式丢弃"（自动销毁证据是反模式）；③ FixLoopPolicy 收紧 ≥1（初始裁判必须可跑）
- 边界在引擎（LimitedTestsTool veto + 计数）、节奏在模型（失败 excerpt 自然引导修复流）——D4 双层分工完整落地
- M17.5 剩余：CodingAgentFactory（五工具 + 治理链四档 REQUIRES_APPROVAL + 编码 systemPrompt 装配）+ CodingAgentExample（T0-T7 全剧本 + F1/F3/F7 拒绝演示）+ 收口

---

## 17. M17.5 实现记录（2026-08-24，装配与收口）· Stage 17 收官

### 装配时显形的架构发现：蓝图 T3 的隐含前提（本里程碑最重要的一课）

蓝图 T3 要求 `run_tests` 看到暂存的变更——但 M17.2 的暂存纪律是"**不落盘**"，而测试命令跑在真实磁盘上：**裁判永远看不到暂存区的修改，修复环形同虚设**。这个矛盾在蓝图里被时序图掩盖，在装配（M17.5 写第一个端到端剧本）时才显形。

兑现方案（PatchStore 增强，模块内）：

- **三态磁盘判定 `OnDiskState`**：每个 staged change 相对磁盘有三态——`BASELINE`（磁盘==stage 快照，未物化）/ `MATERIALIZED`（磁盘==newContent，已物化）/ `DRIFT`（都不是=有人插手）。apply 的漂移预检从"必须==oldContent"升级为三态：BASELINE 正常写、MATERIALIZED 幂等跳过、DRIFT 整批拒
- **`materialize()`**：写盘让裁判可见（幂等）；**`revert()`**：恢复到 patch 开始前
- **`firstBaselines`（修复环 revert 的关键设计）**：`oldContent` 身兼两职冲突——漂移基线要随 re-stage **刷新**（物化判定需要"磁盘==上次物化结果"），恢复目标要**冻结**（revert 必须回到 patch 最初）。分离：re-stage 照旧刷新 `oldContent`，PatchStore 另存 patch 级 `firstBaselines`（首次 stage 时 computeIfAbsent 冻结），`revert` 按 firstBaseline 恢复——修复环 `stage v1 → materialize → stage v2 → materialize → revert` 全链回到原始（测试锁定）
- **修复环闭环的数学**：stage v2 时快照的磁盘=v1（上次物化结果）→ 下次 materialize 的 BASELINE 判定自然成立 → M17.2 的"现场快照"设计与物化循环无缝衔接（当时未预见，此处结果）
- **人闸关闭即回滚**：`rejectPatch()/discardPatch()` 内置 best-effort revert（物化过则恢复原始；DRIFT 时跳过恢复只关闭补丁——人的手改优先于机器账本，诚实边界 javadoc 注明）
- `run_tests` 自动物化：LimitedTestsTool 在执行前 materialize（幂等，修复环中只写变化部分）——蓝图 T3 前提在机制层兑现

### 交付

- **`CodingAgentFactory`**（root 包装配器，第 18 个抽象）：**D8 四档**（read/list/write AUTO——零副作用与暂存无真副作用；run_command/run_tests REQUIRES_APPROVAL——进程执行即时副作用；DENY 不用——未知工具根本不在注册表，fail-closed by construction）；**编码 systemPrompt 行为契约六条**（READ BEFORE YOU CHANGE / STAGE SMALL PATCHES / THE TEST IS THE JUDGE / RESPECT THE FIX BUDGET / COMMANDS ARE GUESTS / DELIVER A SUMMARY）；GovernedToolExecutor 治理链挂车（PermissionChecker + approvalService + auditLogger）+ SimpleAgent + ReActAgentLoop——**零存量改动下的第五次组装**；`createDemoAgent` 便捷工厂
- **`CodingAgentExample`**（T0-T7 全剧本 + 拒绝演示，**真实子进程裁判**：check.sh 真脚本 grep 工作区）：T1 读代码 → T2 暂存 v1（无守卫）→ T3 物化+裁判**真实走红** → T4 修复 v2 + 重 stage（替换）→ 再测**绿** → T5 人审 diff（unified diff 打印）+ approveAndApply → T6 变更摘要 → T7 审计流水 7 条（EXECUTED/APPROVED 状态可见）+ **三拒绝演示**（deny-read ".git/config" / 白名单 fail-closed curl / 注入免疫 payload 原样打印 + marker.txt 健在）+ **[LIMIT] 演示**（budget 1 耗尽后拒绝执行 + 证据保留 true）
- pom：agent-coding 增 agent-security（compile，蓝图预告落点）+ agent-model（test）；examples pom 增 agent-coding
- 测试 **+14**（FactoryTest 6 含 fullLoopThroughGovernance 全链治理剧本 / PatchStoreTest +6 物化族 / SessionTest +2 物化断言）→ 模块 **138**，全仓 **1023 全绿**（存量 885 零影响）

### 实现期坑 3 条（记入防复发）

1. **v1 注释 "guard" 字样骗过 grep 裁判**（剧本 bug）：初版代码注释写 "no zero guard"，check.sh 的判定恰是 `grep 'guard'`——**注释词汇撞了裁判的判定依据**，第一次跑修复环根本没红（budget 0 暴露）。教训：**模拟裁判的判定依据不能与被测内容的自然语言共享词汇**——裁判要 grep 什么，剧本就避开什么
2. **修复环 revert 基线丢失**（测试驱动发现，本阶段最有价值的设计发现）：首版 revert 恢复 `oldContent`——但修复环 re-stage 已把它刷新为 v1，revert 回不到原始 "line1..."。根因是 `oldContent` 双职责（漂移基线 vs 恢复目标），`firstBaselines` 分离是正解。教训：**一个字段承担两个生命周期语义时，问一句"这两个语义的更新时机一致吗"**——不一致必拆
3. **examples 单模块跑 exec 找不到未 install 的 SNAPSHOT**：`-pl examples` 的依赖解析走本地仓库——新模块要先 `mvn install -pl agent-coding -am`（README 备忘的老问题在新模块上的重现）

### 与蓝图的一致性（Stage 17 总验收）

- **五里程碑全过**：M17.1 工作区（36 测试）→ M17.2 补丁（+36）→ M17.3 命令沙箱（+37）→ M17.4 修复循环（+15）→ M17.5 装配（+14）= **138 测试**，全仓 **1023**（885 存量零影响）
- **零存量改动第三次兑现**：除父 POM / examples pom 注册外，agent-core / agent-security / agent-sandbox 等全部存量模块零 diff——**18 周规划 M8 里程碑完成：三类场景（企业/游戏/编码）同 Runtime，三种领域缺失（归属层/世界层/变更层）全部以 Profile 形式落地**（15 两处枚举加法 / 16 零 / 17 零）
- **规划验收逐条兑现**：8 步流程（理解需求→读取代码→生成修改计划→申请修改权限→生成 Patch→沙箱测试→失败修复→输出变更摘要——Example 实跑全过）；九项支持（代码读取/文件修改/Patch/命令执行沙箱内/测试/自动修复/审批/沙箱复用阶段4/Git 工作区最小语义）；红线（第一版禁止直接执行高风险命令——白名单 fail-closed + 无 shell + REQUIRES_APPROVAL 三层兑现，curl 演示实证）
- **蓝图偏差汇总**（全程诚实记录）：ApplyOutcome→ApplyResult sealed / Patch.summary 派生 / stageDeletion 显式 API / markValidated+reject 先立后接 / listener→run() 演进 / F3 细化证据保留 / FixLoopPolicy ≥1 / **materialize+revert+firstBaselines（T3 隐含前提的兑现，最大偏差=最大收获）**
- 文章 7 篇素材全部就绪（§11 映射表 + 本文全部实现记录）
