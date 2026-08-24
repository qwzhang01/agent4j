# Coding Agent 与沙箱：阶段 4 的实战检验

> 配套蓝图：[architecture-stage-17.md](architecture-stage-17.md) D5（沙箱复用裁决）· 对应实现：`agent-coding/exec/CommandRunner.java` + Stage 4 的 `SandboxSpec`/`SandboxResult`
> 上一篇：[stage-17-article-6-file-and-command-permission.md](stage-17-article-6-file-and-command-permission.md) · 本文是 Stage 17 系列收官篇
> 状态：✅ Stage 17 已完成

---

## 1. 我今天要解决什么问题

这是收官篇，回答一个「回望」的问题：

> Stage 4 造的沙箱（`ProcessSandbox`），在 Coding Agent 这里，到底能不能直接用？

当初做沙箱（Stage 4）时的全部意义，就是为了今天这个场景。现在 Coding Agent 真的来了，检验结果如何？

结论先给：**哲学通用，隔离模型换壳**——沙箱三要素（时间限制、空间锚定、资源限制）完全通用，但「临时目录用完即焚」这个隔离模型，和「命令必须看见真实工作区」的需求冲突，必须换壳。

---

## 2. 为什么会有这个检验

Stage 4 的 `ProcessSandbox` 是为「执行一段**不可信代码**」设计的：

```java
// ProcessSandbox.execute(className, code)
// 流程：写临时文件 -> javac 编译 -> java 运行 -> 用完即焚（清理临时目录）
```

它的隔离哲学是「**临时目录 + 自动清理**」——不可信代码在隔离的临时目录里跑，跑完删掉，别碰真实文件系统。

但 Coding Agent 的需求完全不同：

```text
mvn test 必须看见真实工作区（pom.xml、src、依赖）
命令的语义就是"在真实文件系统上工作"
```

如果直接把 `mvn test` 塞进「临时目录用完即焚」的壳，它什么都做不了——临时目录里没有你的项目。

于是必须做一次诚实的裁决：**哪些能复用，哪些不能。**

---

## 3. 它解决了什么问题

- **复用三样**（避免重复造轮子）：数据契约（`SandboxSpec`/`SandboxResult`）、进程模式（双流读取/超时 kill/输出捕获）、沙箱哲学（时间/空间/资源三限制）。
- **不复用一样**（避免削足适履）：`ProcessSandbox.execute` 的临时目录隔离模型。

这个「复用 / 不复用」的边界，比「全部复用」或「全部重写」都更诚实——它是「阶段 4 的实战检验」的真正价值。

---

## 4. 核心抽象和架构

### 4.1 复用三样

```text
数据契约（直接 import，零重造）：
  SandboxSpec     timeout / env / workingDirectory —— 命令执行的入参语义
  SandboxResult   success / stdout / stderr / exitCode / timedOut / error

进程模式（同款复现）：
  ProcessSandbox.runProcess 的三件套 ——
    双流读取（防管道死锁）  超时 destroyForcibly  输出捕获

沙箱哲学（继承）：
  资源限制（超时）+ 时间限制 + 空间锚定 —— 三要素
```

### 4.2 不复用一样：临时目录壳 vs 锚定白名单壳

```text
ProcessSandbox.execute(className, code)    —— 临时目录壳
  威胁模型：代码本身不可信
  隔离方式：临时目录 + 用完即焚
  适用于：执行一段用户提供的 Java 代码

CommandRunner.run(argv)                    —— 锚定白名单壳
  威胁模型：命令的副作用面
  隔离方式：cwd 锚定 workspace 根 + 白名单 + 无 shell
  适用于：在可信工作区上执行白名单命令
```

**两种威胁模型，两种正确答案**：

- 前者防「**代码本身**」——代码是攻击者，隔离它的执行环境；
- 后者防「**命令的副作用面**」——命令本身是白名单里的，但它的副作用（改文件、外发数据）要受控。

这不是「阶段 4 做错了」，恰恰相反——阶段 4 的沙箱哲学（限制时间/空间/资源）**完整通用**，只是隔离模型要按场景分壳。

---

## 5. 一次完整数据流

`CommandRunner` 复现 `runProcess` 模式的完整链路：

```text
run_tests -> RunTestsTool -> CommandRunner.run(["mvn", "test"])

1. ProcessBuilder(argv)      -> 无 shell，cwd 锚定 workspace 根
                               （spec.workingDirectory 被有意忽略 —— 锚定是闸不是提示）
2. 双流读取线程              -> 防管道死锁（同 ProcessSandbox.runProcess）
3. waitFor(timeout)          -> 超时 destroyForcibly（同款）
4. 输出截断                  -> 头尾各半 + 真实总字节标注 + hard cap 4×预算防 OOM
5. 返回 SandboxResult        -> 数据契约复用（success/exitCode/timedOut）
```

实测（`CommandRunnerTest`，真子进程）：

```text
pwd                       -> 输出 workspace 真实路径（cwd 锚定实证）
spec.workingDirectory=/tmp -> 仍锚定 workspace（被有意忽略）
sleep 30 + 300ms timeout   -> timedOut=true（超时 kill，非异常）
seq 1 100000               -> 588895 字节截断，标注报真总数（hard cap 诚实降级）
```

---

## 6. 最小代码或实验

两个「复用 vs 不复用」的关键验证：

```java
// 复用：SandboxSpec 进、SandboxResult 出（CommandRunnerTest.environmentPassed）
SandboxSpec spec = SandboxSpec.builder().timeout(5s).environment(Map.of("VAR","v")).build();
SandboxResult result = runner.run(List.of("printenv", "VAR"), spec);
assertEquals("v\n", result.stdout());   // 数据契约复用，语义零重造

// 不复用：spec.workingDirectory 被忽略（CommandRunnerTest.specWorkingDirectoryIgnored）
SandboxSpec spec = SandboxSpec.builder().workingDirectory("/tmp").build();
assertEquals(root.toRealPath() + "\n", runner.run(List.of("pwd"), spec).stdout());
// 锚定是闸，不是提示
```

第二个实验最能说明问题：即使 spec 里写了 `/tmp`，命令的 cwd 依然锚定在 workspace 根——因为「工作目录锚定」是安全闸门，不是可配置的偏好。

---

## 7. 常见误区

1. **「Coding Agent 应该直接复用 ProcessSandbox」** —— 会削足适履。临时目录隔离会让 `mvn test` 找不到项目文件。隔离模型必须按场景分壳。
2. **「沙箱 = 隔离执行不可信代码」** —— 这是沙箱的一个用途，不是全部。Coding Agent 的沙箱（锚定 + 白名单 + 无 shell）防的是「命令的副作用面」，不是「代码本身」。两种威胁模型。
3. **「复用就要全部复用，否则就全部重写」** —— 二分的思维害人。真正的复用是「数据契约复用 + 进程模式复用 + 哲学继承」，但「隔离模型换壳」。这才是诚实的复用裁决。
4. **「spec.workingDirectory 是个可以随便设置的参数」** —— 恰恰相反，它被有意忽略。锚定不是可配置项，是安全闸门。

---

## 8. 和相邻概念的区别

**两种威胁模型**（本篇核心，也是收官总结）：

```text
不可信代码执行（Stage 4 原始场景）
  威胁：代码是攻击者
  隔离：临时目录 + 用完即焚（防代码碰真实文件系统）
  答案：ProcessSandbox

可信工作区操作（Stage 17 Coding Agent）
  威胁：命令的副作用面
  隔离：cwd 锚定 + 白名单 + 无 shell（防命令副作用失控）
  答案：CommandRunner（契约复用 + 模式复现 + 隔离换壳）
```

同源的沙箱哲学（限制时间/空间/资源），在不同威胁模型下长出两种隔离壳——这就是「阶段 4 的实战检验」的完整答案。

---

## 9. 我的设计判断

最重的一条：**复用裁决的黄金标准是「复用什么」，不是「复不复用」。**

很多人面对「已有沙箱能不能用」会陷入两个极端：要么硬塞（临时目录里跑 mvn，跑不起来就说沙箱不好用），要么推倒重来（忽视 Stage 4 已经造好的契约和模式）。正确的裁决是拆开看：**数据契约能复用（SandboxSpec/SandboxResult 语义零重造）、进程模式能复用（双流/超时/捕获同款）、沙箱哲学能继承（时/空/资源三限制），但隔离模型要换壳（临时目录 → 锚定白名单）**。这个拆解本身就是「阶段 4 的实战检验」的答案——它证明 Stage 4 没白做，也证明 Stage 17 没乱做。

其次，把「`spec.workingDirectory` 被有意忽略」做成显式设计而非静默覆盖，是诚实工程的一个细节：**锚定是闸，不是提示**，写进 javadoc 让人知道这是刻意的，不是漏了。

---

## 10. 面试表达

> 「Coding Agent 对沙箱的复用，我做了一次诚实裁决：复用了三样——数据契约（SandboxSpec/SandboxResult 语义零重造）、进程模式（双流读取防死锁、超时 destroyForcibly、输出捕获）、沙箱哲学（时间/空间/资源三限制）；但没复用 ProcessSandbox 的临时目录隔离模型，因为它防的是『代码本身不可信』，而 Coding Agent 要防的是『命令的副作用面』——mvn test 必须看见真实工作区，临时目录壳会让它什么都做不了。所以 CommandRunner 换了壳：cwd 锚定 workspace 根（spec.workingDirectory 被有意忽略）+ 白名单 + 无 shell。结论是：两种威胁模型，两种正确答案，而沙箱哲学是通用的。」

---

## 11. 系列收官：七篇的连接

这七篇从「本质」走到「落地」，最后回到「复用」：

```text
1. 受控的软件工程循环   —— 为什么 Coding Agent ≠ 聊天机器人（输出即变更）
2. Workspace/Patch/Command 建模 —— 位置、粒度、权限三个维度
3. 从需求到 Patch 的状态机 —— 双层状态 + 冻结态
4. 修复循环             —— 测试即裁判，边界在引擎节奏在模型
5. 可观测性和审批       —— 档位跟着真副作用走
6. 文件权限和命令权限   —— 无 shell 是第一道防御
7. 沙箱实战检验         —— 复用三样、换壳一样（哲学通用）
```

整个 Stage 17 的落点，回到三 Profile 收官的那句话：**同一套 Runtime，企业、游戏、编码三种场景，靠「领域语义」而非「改机制」支撑，存量模块近乎零改动。**

下一步（可选）：转 Stage 18（可观测性/成本治理/评估回归/发布，收官阶段），或把本系列 7 篇挑选 2-3 篇扩展为公众号文章（学习规划：每周从产出中挑 2-3 篇）。
