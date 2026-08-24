# Workspace、Patch 和 Command 如何建模

> 配套蓝图：[architecture-stage-17.md](architecture-stage-17.md) §3（核心抽象）· 对应实现：`agent-coding/` 的 `workspace/`、`patch/`、`exec/` 三个包
> 上一篇：[stage-17-article-1-controlled-engineering-loop.md](stage-17-article-1-controlled-engineering-loop.md)
> 状态：✅ Stage 17 已完成（138 测试，全仓 1023 全绿）

---

## 1. 我今天要解决什么问题

上一篇说 Coding Agent 缺一个「变更层」。这一篇把这个抽象的层，落到三个具体、可写代码的建模问题上：

```text
状态的家在哪？   Workspace —— 文件系统是外部已有的事实，Agent 只是读它
变更的粒度是什么？ Patch —— 一次任务的变更集，全批 apply 或全批丢弃
命令的边界是什么？ Command —— 白名单里的客人，不是任意 shell
```

三个问题对应三个包（`workspace/`、`patch/`、`exec/`），加起来 14 个类。而这三个包最容易被搞混的，不是内部关系，而是**它们和 Stage 8、Stage 16 里那些「同名不同层」概念的边界**。

---

## 2. 为什么会有这三个概念

回到五个假设破裂（上一篇）。「输出即变更」这句话，一旦落地就逼出三个追问：

1. 变更发生在**哪里**？—— 文件系统。但文件系统不是 Agent 的内存，它本来就存在、有权限、有大小，Agent 必须**先读后写**，而「读」本身也要防（.git/.env 不能读）。于是有了 `Workspace`。
2. 变更的**最小交付单位**是什么？—— 一个文件？太细（一次任务改 5 个文件）。一整轮对话？太粗（无法审查）。于是有了 `Patch`：一次任务的变更集。
3. 变更的**高危动作**（跑命令）怎么管？—— 命令面近乎无限。于是有了 `Command` 的白名单建模。

所以这三个概念不是「设计出来炫技」的，是「变更」这个主语的三个必然维度：**位置、粒度、权限**。

---

## 3. 它解决了什么问题

- `Workspace` 解决「**Agent 对文件系统的访问必须有边界**」：读也是特权，写更是特权（写走 Patch 暂存，不直接落盘）。
- `Patch` 解决「**半批落盘是最坏状态**」：把「改了 5 个文件」打包成一个事务单位，要么全批 apply，要么全批丢弃。
- `Command` 解决「**命令面无限 vs 权限有限**」的矛盾：只放行白名单里的命令前缀，且**根本没有 shell**。

---

## 4. 核心抽象和架构

### 4.1 三个包、14 个类

```text
workspace/（4 类）—— 位置与边界
  Workspace          根目录锚点 + 路径解析守卫（normalize+startsWith 防 ../ 逃逸）
  WorkspacePolicy    denyGlobs（.git/.env/*.key）+ 大小/条目/深度三预算
  ReadFileTool       读单文件（超限截断标注）
  ListFilesTool      列目录树（确定性排序 + denied/symlink 隐形）

patch/（5 类）—— 粒度与事务
  FileChange         单文件变更：path + kind(CREATE/MODIFY/DELETE) + new/old 内容
  Patch              一次任务的变更集 + 状态机（DRAFT→VALIDATED→APPLIED/REJECTED/DISCARDED）
  PatchStore         暂存区：stage/replace/apply/discard/reject + 漂移检测 + materialize/revert
  WriteFileTool      写进暂存区（不落盘）
  PatchSummarizer    unified diff 渲染（人审输入）

exec/（5 类）—— 权限与裁判
  CommandWhitelist   argv 前缀白名单（fail-closed）
  CommandRunner      无 shell 执行器（锚定 + 超时 + 截断）
  RunCommandTool     通用白名单命令
  TestResult         测试判决（passed = exit 0 且未超时）
  RunTestsTool       固定裁判（测试命令装配期注入，模型不可改）
```

### 4.2 三个「状态的家」—— 最核心的边界

这是本篇最该记住的对比。项目里有三个「状态的家」，长得像，其实完全不同层：

| 概念 | 谁拥有它 | 生命周期 | 变更纪律 |
|---|---|---|---|
| `WorldState`（16） | 引擎拥有的黑板 | 一局游戏 | 变更须经 WorldEffect 指令（引擎是唯一 apply 点） |
| `MemoryStore`（8） | store 拥有的沉淀 | 跨对话 | 从对话中提取，scope 隔离 |
| `Workspace`（17） | **外部已有的事实** | 独立于 Agent | 变更须经 Patch 审批（模型不可信） |

一句话：**`Workspace` 不复制文件内容进内存——它是「视图 + 边界」，不是状态副本。** 谁拥有状态，谁就负责它的变更纪律：文件系统的变更纪律 = Patch 审批。

### 4.3 三个「变更的粒度」—— 第二个核心边界

```text
WorldEffect（16） 单条指令       立即 apply（引擎可信）
FileChange（17）  单文件差异       暂存不生效（模型不可信）
Patch（17）       一次任务的变更集  事务单位：全批 apply 或全批丢弃
```

16 的指令「立即生效因为引擎可信」；17 的变更「先暂存因为模型不可信」——**同一个「变更即一等值」哲学，两种生效时机，信任水平决定**。

---

## 5. 一次完整数据流

以「改一个文件」为例，看三个包如何协作：

```text
模型调 write_file("Calculator.java", 新内容)
  │
  ├─ Workspace.resolve("Calculator.java")   → 词法守卫：../ 逃逸、绝对路径、空白全拒
  ├─ WorkspacePolicy.isDenied(rel)          → deny 检查：.git/.env 拒
  ├─ 快照旧内容 oldContent（磁盘现场快照）
  ├─ 推导 kind：磁盘存在 → MODIFY；不存在 → CREATE
  │
  ├─ PatchStore.stage(...)                  → FileChange 进暂存区（LinkedHashMap，同路径替换）
  │                                          → 磁盘此刻零变化
  │
  └─ 返回 "staged: ... Nothing written to disk yet."

（之后 run_tests → materialize 写盘 → 裁判看得到；apply → 漂移检测 → 落盘）
```

三个包的职责在一条链上各司其职：`Workspace` 管「能不能碰这个路径」，`Patch` 管「这个变更怎么打包」，`Command` 管「这条命令能不能跑」。

---

## 6. 最小代码或实验

三个最核心的不变量，各一句测试锁定：

```java
// Workspace：路径逃逸三态全拒（WorkspaceTest.resolveEscapeRejected）
workspace.resolve("../outside.txt");   // -> IllegalArgumentException
workspace.resolve("/etc/passwd");      // -> IllegalArgumentException
workspace.resolve("a/../../..");       // -> IllegalArgumentException

// Patch：暂存不落盘（PatchStoreTest.stagingNeverWrites）
// 写 5 次（含删除与重写）后，磁盘逐字节 Arrays.equals 与写前一致

// Command：无 shell 注入免疫（RunCommandToolTest.injectionIsInert）
run_command(["echo", "x; rm marker.txt"])
// -> stdout 原样打印 "x; rm marker.txt"，marker.txt 文件健在
```

第三个实验尤其值得亲手跑一遍——它是「无 shell」设计最直观的可执行证明：注入语法没有被解释，因为它只是一串字符，没有任何东西去解释它。

---

## 7. 常见误区

1. **「Workspace 就是文件系统的封装」** —— 不是封装，是**边界**。封装关心「怎么读写」，边界关心「什么不能读写」。`Workspace` 的第一价值是 deny 列表和逃逸守卫，不是便捷 API。
2. **「Patch 就是一组文件的数组」** —— 数组只是载体，Patch 的灵魂是**事务性**：全批 apply 或全批丢弃。半批落盘是它要消灭的最坏状态。
3. **「Command 白名单用黑名单过滤危险命令就行」** —— 黑名单是军备竞赛（`rm --preserve-root`、base64 解码再执行）。白名单 + 无 shell 让整类问题**不存在**：curl 根本进不了白名单，你不需要拦它。
4. **「把文件内容读进内存缓存，就是 Workspace」** —— 恰恰相反。Workspace 不缓存内容，它是「视图 + 边界」；把内容复制进内存是 Memory 的职责，不是 Workspace 的。

---

## 8. 和相邻概念的区别

除了上面两组边界（状态的家、变更的粒度），还有一组「环」的边界，放下一篇展开，这里先点到：

```text
maxSteps（2）      防"模型喋喋不休"       步数上限，步是事故
图引擎环保护（5）  防"流程图成环死循环"    环是设计错误（DAG 拒绝成环）
修复环边界（17）   防"修不好还一直修"      环是方法，但方法要有预算
```

前两者把环当敌人，本阶段把环当方法——但给它一个 token 预算意义上的刹车。

---

## 9. 我的设计判断

最重的一条：**「状态的家」决定了「变更的纪律」，而纪律的松紧由「谁可信」决定。**

`WorldState` 变更立即生效，因为引擎是唯一 apply 点、引擎可信；`FileChange` 暂存待批，因为模型不可信。很多人把这两处「生效时机不同」当成不一致，其实是同一个原则（变更必须经过纪律）按信任水平的**参数化**。想清楚这一点，就不会在设计 Coding Agent 时犯「让模型直接写盘」的错误。

其次，`Command` 建模我选择「**没有 shell**」而不是「更好的黑名单」——这是把防御从「对抗」升级到「不存在」。凡是一类攻击手段，能通过架构让它根本不成立，就不要靠规则去拦截它。

---

## 10. 面试表达

> 「Coding Agent 的领域建模，我抓住三个维度：位置、粒度、权限。位置用 `Workspace`——它不是一个文件封装，而是一个边界，读也是特权（deny .git/.env），路径逃逸在词法层就被拦死；粒度用 `Patch`——它不是文件数组，而是一个事务单位，全批 apply 或全批丢弃，半批落盘是要消灭的最坏状态；权限用 `Command`——命令是白名单里的客人，而且我根本不经过 shell，所以注入语法在架构上就不存在。这三个概念背后是一条主线：状态的家决定变更的纪律，而纪律的松紧由谁可信决定。」

---

## 11. 下一篇连接什么

下一篇把 `Patch` 的状态机单独拎出来讲：**从需求到 Patch，Coding Agent 的状态机**——它有两层状态（对话流在 `AgentState`，变更流在 `Patch`），以及「DRAFT→VALIDATED→APPLIED」这条主路径的每一步由谁触发。

→ [stage-17-article-3-requirement-to-patch-state-machine.md](stage-17-article-3-requirement-to-patch-state-machine.md)
