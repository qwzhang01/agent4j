# DeepSeek Harness 沙箱架构解析

> 时间：2026-08-16
> 来源：projects/.ext/deepseek-harness 源码研读
> 相关包：`packages/sandbox/*`（sandbox / sandbox-local / sandbox-policy / sandbox-windows-acl）+ `native/landlock-run`
> 相关文档：`docs/subsystems/sandbox.md`

---

## 一、概念

dsh 的沙箱由一组精确定义的概念构成，每个概念都有明确的边界和词汇表（vocabulary）。

### 1.1 沙箱管什么：只管文件操作

dsh 沙箱的词汇表刻意收窄为**文件效果**（file effects），不含网络、进程可见性、系统调用、设备、凭据：

```typescript
type SandboxMode =
  | 'read-only'           // 只读：仅允许 /dev/null 等必要 sink
  | 'workspace-write'     // 工作区可写：workspace root + 后端承诺的临时区
  | 'danger-full-access'  // 完全访问：绕过限制（不经过沙箱）
```

关键设计点：
- 网络和进程策略**故意不在词汇表里**。要用容器/microVM 隔离网络时，是替换整个能力 seam 的 Provider（`ctx.shell`、`ctx.fs`），不是给沙箱加开关。
- `danger-full-access` 也在类型里，因为消费方需要先解析完整策略，再决定是否绕过沙箱（直接 spawn 原始 argv，不调 `ctx.sandbox`）。

### 1.2 诚实的强制执行报告

后端能限制到什么程度，如实报告，不夸大：

```typescript
type SandboxEnforcement =
  | 'full'     // 后端管辖该模式承诺的每一个文件效果
  | 'partial'  // 老内核 ABI 或平台缺口，只管辖子集
```

两个现实中的 partial 案例：
- 旧 Landlock ABI 只能限制自身公开的访问类别
- Windows ACL 受限令牌必须保留 Everyone（进程初始化需要），所以授予 Everyone 写权限的对象仍可写；NTFS 硬链接也能绕过

要求绝对边界的调用方必须检查这个值并拒绝 partial，而不是把它当 full 用。

### 1.3 策略逐调用携带

执行策略**不在 Provider 上存储，而是随每次调用传入**：

```typescript
interface SandboxPolicy {
  mode: ConfinedSandboxMode  // read-only 或 workspace-write（不含 danger）
  workspaceRoot: string      // workspace-write 模式下的可写根目录（绝对路径）
  sessionId?: SessionId      // 会话身份，后端用它做 per-session 状态
}
```

这意味着两个消费方可以同一瞬间按不同策略限制进程：bash 跑 `read-only`，同时受限子 agent 保持自己的状态目录可写。一次获批的升权重试只是"用更宽策略发起新调用"，不需要改 Provider 状态。

### 1.4 confine 范式：包装 argv，不执行

沙箱的核心 API 不是"帮我安全执行这条命令"，而是"**给我一个包装后的 argv，你自己 spawn**"：

```typescript
abstract confine(argv: readonly string[], policy: SandboxPolicy): ConfinedArgv
```

返回的 `ConfinedArgv` 包含：
- `argv`：替换用 argv（runner + profile + `--` + 原始 argv）
- `enforcement`：强制执行完整度
- `denialSignatures`：该后端"拒绝"方言（见 1.5）
- `runnerFailureRules`：runner 自身失败的结构化证据规则

这个设计让沙箱 Provider 与进程启动方式解耦 -- 消费方（bash-sandbox、终端会话）用自己习惯的方式 spawn。

### 1.5 拒绝方言与 Runner 失败：两种正交分类器

子进程失败时，stderr 是带内通道，必须区分两种完全不同的情况：

**拒绝（denial）= 沙箱正常工作，拦住了命令**

每个后端的拒绝文本不同（方言）：
- bwrap 只读绑定下写文件 -> `EROFS` 文本
- Landlock -> `EACCES`
- Seatbelt -> `EPERM`

所以 `denialSignatures` 是**该后端专用**的，不是跨后端并集 -- 并集会声称该后端从不产生的拒绝。

**Runner 失败 = 沙箱基础设施坏了，命令根本没跑**

```
RunnerFailureRule 判定条件（全部满足）：
1. 退出码非零（可选 allowedExitCodes 门限）
2. 先按整行精确匹配移除 informationalLines（良性通知行）
3. 剩余 stderr 中存在一行大小写不敏感地命中 fatalSignatures
```

单独的退出码**永远不能**证明 runner 失败（Landlock launcher 的 `LAUNCHER_FAILURE_EXIT=125`，但被包装的子进程自己也可能以 125 退出）。

消费方先查 runnerFailureRules（基础设施故障，向用户报沙箱坏了），再查 denialSignatures（限制生效，引导模型走升权流程）。

### 1.6 失败闭合（fail-closed）

没有可用后端时，`confine` 抛 `SandboxUnavailableError`（错误码 `SANDBOX_UNAVAILABLE`），**绝不静默放行**：

> "sandbox mode \"<mode>\" is requested but no sandbox backend is usable on this host; refusing to run the command unconfined."

不支持的平台、runner 缺失、内核不满足要求 -- 一律拒绝执行受限命令。要么真正受限地跑，要么不跑。

### 1.7 与会话日志的集成

运行时切换沙箱模式 = 在会话日志追加一条 `sandbox/mode` 事件（append-only，可回放）：

```
策略优先级：
explicit approved mode          // 用户批准的一次性升权
  > fold(session events)        // 会话最后一条 sandbox/mode 事件
  > deployment default          // 部署配置（默认 read-only，故障安全）
```

会话工作区根目录来自创建时记录的**不可变 `SessionHeader.cwd`**，不需要额外事件。两个会话永远看不到彼此的模式状态。

配套的 invariant 组件拒绝词汇表外的伪造 `sandbox/mode` 事件（封闭词汇校验）。

---

## 二、基于概念的架构设计

### 2.1 Capability Seam 三角色

沙箱家族严格遵循 dsh 的 Capability Seam 模式：

| 角色 | 包 | 职责 |
|------|-----|------|
| **Service Definition** | `sandbox/sandbox` | 定义 `ctx.sandbox` 服务约定：`SandboxProvider` 接口、SandboxMode/Enforcement/Policy 词汇、`SANDBOX_UNAVAILABLE` 错误。只依赖 cordis 和错误基类，不依赖任何后端 |
| **Provider** | `sandbox/sandbox-local` | 本地平台后端实现：探测并缓存 runner（Linux bwrap 优先、退 Landlock；macOS Seatbelt；Windows ACL），把模式映射成各后端的授权 profile |
| **策略归属方** | `sandbox/sandbox-policy` | `ctx.sandboxPolicy` 服务：部署默认 + 会话覆盖解析，逐调用下发完整策略 |
| **Consumer** | `shell/bash-sandbox` 等 | 调 resolve 拿策略、调 confine 拿包装 argv、自己 spawn、用两类签名做结果归因 |

另有 `sandbox/sandbox-windows-acl`：Windows 受限令牌 runner 的独立包（每个工作区一个确定性写 SID + 常驻 ACE，每个活跃会话/工作区对一个随机私有临时目录 + 独立 SID + 可撤销 ACE）。

### 2.2 为什么容器不在这个 seam 里

dsh 明确划分了边界：**这个 seam 只支持"与宿主共享文件系统和内核"的限制**（bwrap、Landlock、Seatbelt 都是同内核的进程限制）。容器、microVM、远程执行是**整个能力 seam 的替代 Provider**（替换 `ctx.shell`、`ctx.fs` 的实现），不是 `ctx.sandbox` 的后端。

```
同一内核的进程限制（本 seam）：
  bwrap / Landlock / Seatbelt / ACL
  -> workspaceRoot 是真实主机目录
  -> 沙箱是"包装"层

环境一致的隔离（兄弟实现，替换整条能力）：
  容器 / microVM / 远程执行器
  -> 替换 ctx.fs / ctx.shell 的 Provider
  -> 不是给 ctx.sandbox 加 provider
```

### 2.3 平台后端矩阵

| 平台 | 后端 | 机制 | enforcement |
|------|------|------|-------------|
| Linux | bwrap（优先） | bubblewrap 用户态命名空间，只读 bind + `/dev/null` sink | full |
| Linux | Landlock（bwrap 缺失时） | 自研 landlock-run 启动器，内核 LSM | full（新 ABI）/ partial（旧 ABI） |
| macOS | Seatbelt | `sandbox-exec`（Apple 已标 deprecated 但仍提供），默认允许 + `(deny file-write*)` + 写白名单 | full（依赖 deprecated CLI） |
| Windows | ACL 受限令牌 | 确定性工作区 SID + 随机会话临时 SID + 可撤销 ACE | **partial**（Everyone 保留 + 硬链接缺口） |

探测逻辑：多候选项按序功能探测（`probeTimeoutMs` 限时），单候选项直接选。runner 选择在 Provider 生命周期内缓存 -- 装了新 runner 要重载插件才生效。

### 2.4 landlock-run：原生启动器

`native/landlock-run` 是一个约 300 行的 C11 原生二进制（musl 静态链接），思路是"**先限制自己，再 exec**"：

```
landlock-run 自己启动
  -> 在自己身上安装 Landlock ruleset（readOnly/readWrite 授权）
  -> exec 被包装的命令
  -> 规则集跨 execve 继承
  -> 命令及其产生的所有子进程都在限制下运行
  -> 调用方（Node 进程）不受任何限制
```

发布形态：入口包（JS，解析二进制路径）+ 平台包（`linux-x64` / `linux-arm64`，npm `os`/`cpu` 字段保证只装匹配平台）。**有意不提供安装时构建回退** -- 平台包不存在的宿主上路径就是不存在，探测报 `unusable`，消费方失败闭合。

API 刻意精简：
- `launcherPath()`：启动器绝对路径
- `probe()`：功能探测，返回 `full | partial | unusable`
- `grantArgs({readOnly, readWrite})`：授权参数；**未授予的一切都被拒绝**
- `LAUNCHER_FAILURE_EXIT = 125`：约定常量（子进程也可能以 125 退出，所以必须配合致命诊断行）

### 2.5 模型体验（上下文贡献）

模型不会收到能力清单，而是在运行时上下文快照中收到一段 `sandbox:policy` 贡献（三种模式各一段固定文案），例如 workspace-write：

> Current DSH file policy: workspace-write. Any available operation enforced by the DSH file sandbox may modify files under the session workspace: "<workspace root>". Some platform temporary areas may also be writable.

Token/KV Cache 纪律：策略未变化不重复注入；模式切换时变化内容**追加**在已缓存前缀之后，不破坏 KV Cache。

---

## 三、具体流程

### 3.1 一次受限 bash 执行的完整流程

```
模型返回 toolCall: bash("npm test")
  |
  v
dsh-bash-sandbox（Consumer）
  |
  |-- 1. 解析策略
  |     ctx.sandboxPolicy.resolve({ session })
  |     -> explicit grant ?? fold(session 的 sandbox/mode 事件) ?? 部署默认
  |     -> { mode, workspaceRoot }（workspaceRoot = 会话不可变 cwd 的规范化绝对路径）
  |
  |-- 2. 判断是否受限
  |     mode == danger-full-access?
  |       -> 是：直接 spawn 原始 argv，不调沙箱
  |       -> 否：继续
  |
  |-- 3. 请求包装
  |     ctx.sandbox.confine(['bash','-c','npm test'], policy)
        |
        |   Provider（sandbox-local）
        |-- 3a. 选后端：缓存的探测结论（bwrap / Landlock / Seatbelt / ACL）
        |-- 3b. 无可用后端?
        |       -> 抛 SandboxUnavailableError（SANDBOX_UNAVAILABLE）
        |       -> 绝不返回未包装 argv
        |-- 3c. 模式 -> 授权映射
        |       read-only:      授予 /（只读）+ /dev/null（写）
        |       workspace-write: 授予 /（只读）+ workspaceRoot（读写）
        |                          + /tmp + darwin 用户临时目录（读写）
        |-- 3d. 生成包装 argv
        |       [runner, ...profileArgs, '--', 'bash', '-c', 'npm test']
        |
        v
  ConfinedArgv { argv, enforcement, denialSignatures, runnerFailureRules }
  |
  |-- 4. spawn 包装 argv（Consumer 自己的进程启动器）
  |     注意：路径解析规则 -- 先按文件系统语义解析（symlink/..），
  |     再词法规范化。保证授权的是进程真实运行所在目录。
  |
  |-- 5. 收集结果并归因
  |     退出码 == 0 -> 成功
  |     失败 ->
  |       a. 先查 runnerFailureRules（先移除 informationalLines，
  |          再在剩余 stderr 行中匹配 fatalSignatures + 退出码门限）
  |          命中 -> 沙箱基础设施故障："Runner failure: <detail>"
  |       b. 再查 denialSignatures（该后端专用方言）
  |          命中 -> 拒绝 = 限制生效，把拒绝文本 + 升权引导返回给模型
  |       c. 都不中 -> 普通任务失败，原样返回
  v
结果进入会话日志（tool/result 事件），下一轮模型基于归因决定行为
```

### 3.2 运行时模式切换流程

```
用户："允许写工作区"
  |
  v
批准流程（interaction/guard 插件负责审批 UI）
  |
  v
setSandboxMode(session, 'workspace-write')
  -> 仅在会话日志追加一条 sandbox/mode 事件
  -> 无带外状态修改
  |
  v
下一次能力调用
  -> resolve() fold 事件流，最后一次胜出
  -> 新模式生效
  |
  v
下一次请求前
  -> 归属方把新的 sandbox:policy 贡献加入运行时上下文快照
  -> 追加在已缓存前缀之后（KV Cache 友好）
  |
  v
重启后
  -> 回放日志，fold 恢复模式
  -> 跨重启保留
```

### 3.3 Windows ACL 的会话隔离细节

```
每个工作区（持久）：
  确定性写入 SID + 常驻 ACE
  -> 共享工作区的会话共享预期写权限

每个活跃 会话x工作区 对（易变）：
  随机私有临时目录 + 独立 SID + 可撤销 ACE
  -> 会话间不继承彼此的临时目录权限

恢复场景：
  新 Provider 选新临时路径 + 新 SID
  -> 崩溃残留既不能阻止恢复的会话，也不能向它授权

防冲突检查：
  工作区包含平台临时根目录?
  -> 在任何 ACL 改动前直接失败
  （否则可继承的工作区 ACE 会延伸进每个私有临时子目录）
```

---

## 四、与业界主流的对比

### 4.1 对比矩阵

| 维度 | dsh 沙箱 | Claude Code | OpenAI Code Interpreter | Docker 方案 | 我们的 Java 沙箱（Stage 4） |
|------|----------|-------------|------------------------|-------------|---------------------------|
| 隔离层级 | OS 进程限制（同内核） | OS 进程限制（同内核） | 容器/Jail（独立环境） | 容器（独立环境） | 进程内 ClassLoader / 子进程 |
| 后端 | bwrap / Landlock / Seatbelt / ACL | Seatbelt（macOS）+ bubblewrap（Linux） | gVisor/沙箱 jail | Docker runtime | 自定义 ClassLoader + ProcessBuilder |
| 策略词汇 | 3 模式，只管文件效果 | 类似 3 模式（文件为主） | 全隔离（无共享 FS） | 完全容器化 | 拦截类列表（文件/网络/进程/反射） |
| 策略携带 | 逐调用传入，Provider 无状态 | 会话级配置 | 环境级固定 | 容器级固定 | SandboxSpec 随调用传入 |
| 诚实的执行报告 | full/partial 如实报告并要求消费方检查 | 未显式暴露 | 不适用（全隔离） | 不适用 | 未实现（隐式 full） |
| 拒绝归因 | 后端专用拒绝方言 + 结构化 runner 失败规则 | 单一错误文本 | 不适用 | 容器错误 | SecurityException 文本 |
| 失败语义 | fail-closed，绝不静默放行 | fail-closed | 不适用 | 启动失败即失败 | fail-closed（抛异常） |
| 进程派生覆盖 | 覆盖（Landlock 跨 execve 继承） | 覆盖（Seatbelt/bwrap 特性） | 天然覆盖 | 天然覆盖 | 不覆盖（子进程可逃逸） |
| 模式可运行时切换 | 是（日志事件，可回放/跨重启） | 是（配置切换） | 否 | 否 | 否（spec 构建时固定） |
| 模型感知策略 | 是（sandbox:policy 上下文贡献） | 是（系统提示告知） | 否 | 否 | 是（工具描述中说明限制） |

### 4.2 设计哲学差异

**dsh：精确边界 + 诚实报告**
- 词汇刻意收窄（只管文件），把网络/进程隔离留给"替换整条能力"的兄弟实现
- full/partial 如实报告，要求消费方显式决策，不夸大安全边界
- 拒绝方言按后端区分，runner 失败与任务失败严格分离
- 大量不变量以运行时断言强制执行（封闭词汇校验、fail-closed、per-call 策略）

**Claude Code：产品化取舍**
- 同样的 Seatbelt/bubblewrap 后端，但作为产品不向用户暴露 partial 这类细微差别
- 策略粒度更粗（会话级），换取配置简单

**Code Interpreter / Docker：环境隔离**
- 不与宿主共享内核/文件系统，天然覆盖网络/进程/文件
- 代价：重（容器启动）、慢（冷启动秒级）、跨平台一致性要额外做
- dsh 的判断：这类隔离应该是能力 Provider 的替换，不是沙箱 seam 的后端

**我们的 Java 沙箱：学习型最小实现**
- ClassLoader 隔离与 OS 级隔离是**不同层**的东西：dsh 拦的是系统调用（写文件那一下），我们拦的是类加载（File 类根本加载不进来）
- ClassLoader 方案无法覆盖进程派生（LLM 代码起子进程不受限）、无法拦原生调用
- 对应关系：dsh 的 confine-argv 范式 ~= 我们 Sandbox 接口的策略化扩展方向；dsh 的 per-call policy ~= 我们 SandboxSpec 逐调用传入；dsh 的 fail-closed ~= 我们 SecurityException 直抛

### 4.3 最值得借鉴的三点

1. **把"沙箱坏了"和"被拒绝"当两个正交概念**。我们的 Java 版只有一个笼统的 SecurityException。dsh 用结构化规则区分基础设施故障与限制生效，让模型能分别走"报修"和"申请升权"两条路。

2. **诚实的 enforcement 报告**。声称 full/partial 并要求消费方检查，比统一宣称"已隔离"诚实。我们的 ClassLoader 沙箱实际上就是 partial（可反射逃逸），应该在结果里如实标注。

3. **策略是数据不是配置**。模式切换 = 日志事件 = 可回放可审计；策略随调用携带 = 无全局锁 = 并发会话互不干扰。我们的 SandboxSpec 已经是数据化的，但还缺"事件溯源"这一步。

---

## 五、附：与本框架的映射表

| dsh 概念 | dsh 类/包 | 本框架对应（现状或方向） |
|----------|-----------|------------------------|
| SandboxMode 3 模式 | `sandbox` 包类型 | SandboxSpec 隐式（拦截列表），可引入显式模式 |
| per-call policy | `SandboxPolicy` | `SandboxSpec`（已逐调用传入 ✅） |
| enforcement 报告 | `SandboxEnforcement` | 未实现（方向：ClassLoader 版报 partial） |
| confine-argv | `SandboxProvider.confine` | `Sandbox.execute`（我们直接执行，dsh 只包装） |
| 拒绝方言 | `denialSignatures` | SecurityException message（单一） |
| runner 失败规则 | `RunnerFailureRule` | 未区分（统一当执行失败） |
| fail-closed | `SANDBOX_UNAVAILABLE` | 编译/加载失败即返回 error ✅ |
| 平台后端矩阵 | sandbox-local | 两个实现（ClassLoader/Process），无 OS 级后端 |
| 模式事件溯源 | `sandbox/mode` 事件 | 未实现（spec 静态） |
| 模型可见策略 | `sandbox:policy` 贡献 | SandboxTool 描述文本（弱版本） |
