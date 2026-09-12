# 实验记录：KP9 沙箱谱系——隔离等级与逃逸面（2026-09-12）

> 性质：KP9（沙箱谱系）的落地实验笔记，对应新增类 `SandboxRiskLevel` / `SandboxPolicy` / `SandboxTier` / `SandboxEscalator` / `SandboxResult.FailureKind` / `SandboxReport`，测试 `SandboxPolicyTest` / `SandboxEscapeTest` / `SandboxEscalatorTest` / `SandboxFailureKindTest` / `SandboxReportAndBudgetTest`。  
> 关联：`learning-path-2026-09-knowledge-points.md` §KP9、`stage-4-sandbox.md`（Stage 4 原始实现）、Decision 21。

---

## 一图收口：五档谱系

```
风险等级          → 推荐沙箱                   逃逸面（已知）
──────────────────────────────────────────────────────────────────
TRUSTED           → ClassLoaderSandbox          理论上 0（同信任域）
SEMI_TRUSTED      → ClassLoaderSandbox (opt.)   反射、Unsafe、JNI
  单租户 + 非对抗    + 自动升级至 ProcessSandbox
SEMI_TRUSTED      → ProcessSandbox              共享 FS（OS 用户层）
  多租户（Decision 21 边界）
UNTRUSTED         → ProcessSandbox              共享 FS、OS 用户层
ADVERSARIAL       → ProcessSandbox (最低)        同上；Docker/microVM 是正解
                    生产推荐: Docker + cgroup / Firecracker
```

---

## 心智模型（KP9 自测判据答案）

**判据：说清 Decision 21 被推翻的具体触发条件。**

Decision 21 成立的两个前提：
1. **单租户**——同一个 agent 实例只运行一个用户的代码。
2. **非对抗**——模型没有被指示去逃逸沙箱；它只是在解一道编程题。

触发翻转的组合：
- 多租户 coding agent（用户 A 的 prompt 影响到用户 B 的代码执行路径） → 升 UNTRUSTED / PROCESS
- 用户直接粘贴任意 Java 代码（不经过模型过滤） → 升 UNTRUSTED / PROCESS
- 已知对手正在测试沙箱边界（红队、公开服务） → 升 ADVERSARIAL / PROCESS；生产加 Docker

---

## 关键认知：四个一手工程事实

### 事实 1：ClassLoader 不是安全边界——它是防抢跑屏障

`SandboxClassLoader` 在 `loadClass` 里逐名拦截。它挡住的是「不小心调用危险类」的 LLM 生成代码，不是「刻意绕过的攻击者」。

实测拦截能力（`SandboxEscapeTest`）：
- `java.lang.Runtime` → 编译失败（`RuntimeException` 依赖链被截断）
- `java.io.File` → 编译失败（同上）
- `java.lang.ProcessBuilder` → 编译失败
- `java.lang.reflect.*` → 被整包拦截；`Class.forName("java.lang.Runtime")` 实测不通

理论逃逸路径（v1 已知，有意不堵——堵不完整）：
- `sun.misc.Unsafe` / `jdk.internal.misc.Unsafe`：直接操作内存，绕过类型系统
- JNI（`System.loadLibrary`）：加载 native 库
- `MethodHandles.lookup().findClass()`：部分 JDK 版本可绕过 ClassLoader 检查
- 攻击者故意让模型输出使用上述 API 的代码

这就是 Decision 21 的适用边界：**非对抗 + 单租户 = ClassLoader 够用**。一旦攻击者知道沙箱存在并主动测试，ClassLoader 的逃逸面就暴露了。

### 事实 2：ProcessSandbox 提供进程边界，不提供 FS 白名单

`ProcessSandbox` 通过 OS 进程隔离获得三条保证：
1. 子进程无法访问父 JVM 的堆（进程地址空间隔离）
2. `destroyForcibly()` 能可靠杀死卡住的无限循环（vs ClassLoader 只能「中断」线程）
3. 子进程 PID 不同于父进程（`SandboxEscapeTest.processSandbox_cannotReachParentJvmHeap` 验证）

**没有提供**：
- 文件系统白名单——子进程以宿主 OS 用户身份运行，有同样的文件权限
- 网络隔离——子进程可以访问网络（除非 OS 级限制）
- 内存上限——`SandboxSpec.memoryLimitBytes` 只传给 ProcessSandbox 但未接 `-Xmx` JVM 参数（v1 TODO）

生产补丁：把子进程包进 `systemd` resource group，或用 Docker `--memory` / `--network=none`。

### 事实 3：SandboxEscalator 的「乐观升级」模式是成本-安全权衡

`SandboxEscalator.forRisk(SEMI_TRUSTED)` 的执行路径：

```
第一次尝试：ClassLoaderSandbox
├── success → 返回（快，无 JVM 启动成本）
├── blocked → isBlocked()（结构化 kind 优先，fallback "Blocked:" 前缀）
│     ├── 预算内 → 升级到 ProcessSandbox
│     └── 预算烧完 → BLOCKED 原样返回（不再启动第二个 JVM）
└── timeout / error → 原样返回（超时重试无意义；编译错不用 Process 重试）
```

为什么 blocked → escalate 是合理的：
- ClassLoader 挡住的代码说明它「企图做危险操作」
- 乐观假设：这是模型的意外（不是对抗），在 Process 隔离下运行更安全
- 若你认为「能触发 ClassLoader 拦截的代码就不该被运行」，直接用 `UNTRUSTED` 级别（跳 ClassLoader）

成本模型：
- 安全代码（大部分 LLM 输出）：走 ClassLoader，~0 ms
- 危险代码（少数）：两次编译成本（ClassLoader 编译一次 + Process 编译一次）+子进程 JVM 启动 1~2 s
- 直接用 UNTRUSTED：每次都是 Process，统一 1~2 s

熔断补丁（2026-09-12）：这条成本曲线有尾部风险——「反复触发 block 的源」（对抗 prompt，或模型系统性偏爱危险 API）会让每次请求都烧 1~2 s JVM 启动，升级被反向利用成 denial-of-wallet。`escalationBudget`（默认 3，Escalator 生命周期计）烧完后 BLOCKED 原样返回，strong tier 不再启动。

### 事实 4（2026-09-12 增补）：失败要分桶——「谁的锅」决定「下一步」

`SandboxResult.FailureKind` 把失败正交分四桶，桶与 tier 无关：

| 桶 | 谁的锅 | 下一步 |
|----|--------|--------|
| SANDBOX_FAILURE | 沙箱机器自身（编译基建崩、JVM 启动失败） | 查基建，别怪代码 |
| BLOCKED_BY_POLICY | 策略拒绝（代码想摸危险类） | 这是**设计路径**不是事故——升级 tier 重跑 |
| TIMEOUT | 时间预算烧完 | 任何 tier 重跑都一样超时，不升级 |
| CODE_FAILURE | 代码自身（编译错/运行时异常/非零退出） | 把 stderr 喂回模型让它改 |

正交的含义：同一 tier 可以产生四种死法，同一种死法可以来自所有 tier。`deriveKind` 按 timedOut + error 前缀自动推导，显式传入可 pin 覆盖。

`SandboxReport` 是这四桶的纯下游翻译器：tier × outcome → 「保证什么 / 不保证什么 / 升级说明」。它与 HealthPipeline（KP7）同构——enforcement 负责做事，report 负责把「做成了什么」翻译成人类可审计的承诺。blocked 时它会告诉读者「代码从未运行，拒绝本身就是保证」；timeout 时承认「ClassLoader tier 无硬杀语义（中断是协作的）」。占位 tier（DOCKER/MICROVMM/WASM）一律「零保证大声声明」。

---

## 谱系升级决策树

```
LLM 生成代码（模型选的工具调用）
  → 单租户、无用户提供代码       → SEMI_TRUSTED (ClassLoader, 乐观升级)
  → 单租户、用户直接提供代码     → UNTRUSTED (Process)
  → 多租户                        → SEMI_TRUSTED + multiTenant=true (Process)
  → 已知红队/对抗环境             → ADVERSARIAL (Process + 生产加 Docker/microVM)

Docker/microVM 升级触发：
  → 需要网络隔离          → Docker --network=none
  → 需要 FS 白名单        → Docker --read-only + volume mount
  → 多租户公开服务         → Firecracker microVM / gVisor
  → 语言无关、有生态约束    → WASM (Wasmtime/WasmEdge)
```

---

## Decision 21 完整口径（更新后）

| 条件                | v1 决策 21 成立？ | 推荐升级路径 |
|-------------------|--------------|-----------|
| 单租户 + 非对抗 LLM 代码 | ✅ ClassLoader 够 | — |
| 多租户（用户共享 agent） | ❌ 翻转          | → PROCESS |
| 用户粘贴任意代码          | ❌ 翻转          | → PROCESS |
| 红队/已知对抗             | ❌ 翻转          | → PROCESS + Docker |
| 多租户公开服务             | ❌ 翻转          | → Firecracker / gVisor |
| 语言无关计算               | N/A            | → WASM |

---

## 诚实边界（v1）

- `ProcessSandbox` 已透传 `-Xmx`（2026-09-12）：`memoryLimitBytes` 写入子进程 `java` 启动参数（精确 MB 用 `-XmxNm`，否则裸字节）；`javac` 编译进程不加 cap。`ProcessSandboxTest.memoryLimitBytesBecomesXmxOnChildJvm` 用 `Runtime.maxMemory()` 钉死。`memoryLimitBytes <= 0` 表示不加 cap（走宿主 JVM 默认堆）
- `ProcessSandbox` 无网络隔离（子进程可以访问外网）
- `SandboxEscalator` 超时不升级（超时代码重跑于 Process 也会超时，浪费时间）
- `SandboxResult.FailureKind`（2026-09-12）：四桶分类默认按 timedOut / error 前缀推导，**推导规则与字符串前缀耦合**（"Blocked:" / "Sandbox error:"）——tier 若换错误文案，桶跟着变；结构化 error 类型是后续债
- `SandboxReport` 是纯翻译器：tier × outcome → 保证/不保证/升级说明；表中 DOCKER/MICROVMM/WASM 行是「零保证」占位声明，不代表实现存在
- `SandboxEscalator` 升级预算默认 3 次（2026-09-12）：防 denial-of-wallet 的生命周期上限；按 Escalator 实例计而非按 runId 计——多 run 共享同一 Escalator 时预算被摊薄，生产应按 run 维度重建实例
- `SandboxTier.DOCKER / MICROVMM / WASM` 是文档占位，无实现
- 第三方互操作（在 Docker 里运行 agent4j）未实测

---

## 新思考题

1. ~~`ProcessSandbox` 如何接 `-Xmx` 让 `memoryLimitBytes` 真正生效？~~ **已收口（2026-09-12）**：`javaCommand` 在 `java` 行加 `-Xmx`，不碰 `javac`。精确 MB 用 `m` 后缀，否则裸字节。验证：子进程 `maxMemory() <= limit` 且明显高于未设 cap 时的偶然小堆。
2. ~~ClassLoader 的「乐观升级」是否应该有重试次数限制（防止代码反复触发 block）？~~ **已收口（2026-09-12，代码化）**：应该，且已落。升级无上限的风险不是安全而是成本——对抗性 prompt 每轮生成危险代码，每次 block 都烧一次 JVM 启动（1~2 s），升级机制被反向利用成 denial-of-wallet。落法：`SandboxEscalator` 加生命周期预算 `DEFAULT_ESCALATION_BUDGET = 3`，`AtomicInteger` 计数；烧完后 fast tier 的 BLOCKED 结果原样返回（block 本身就是安全答案——「代码从未运行」），strong tier 不再启动。`escalationBudget = 0` 是显式禁用升级的策略开关；`getEscalationsUsed()` 暴露给监控。验证：`SandboxReportAndBudgetTest` 的 budgetExhaustionReturnsBlockAsIs / zeroBudgetDisablesEscalation / escalationsUsedIsObservable。
3. ~~多语言（Python/JS）LLM 生成代码的沙箱该走 WASM 还是 Docker？代价如何比较？~~ **已收口（2026-09-12）**：判据不是「哪个更强」，而是「代码进沙箱前的形态是否可控」：

   | 维度 | WASM（Wasmtime/WasmEdge） | Docker（+cgroup/netns） |
   |------|--------------------------|------------------------|
   | 隔离机制 | 线性内存 + 类型系统，**安全由构造保证** | namespace + cgroup，安全由配置保证（配错即漏） |
   | 系统调用面 | 宿主显式注入能力（WASI capabilities），默认零 | 容器内全量 syscall，靠 seccomp profile 收窄 |
   | 冷启动 | 毫秒级 | 百毫秒~秒级 + 镜像分发 |
   | 语言改造 | 必须先编译到 wasm：Python 走 Pyodide 系生态别扭、C 扩展需专门交叉编译；JS 需 toolchain | 零改造：任何解释器塞进镜像即跑 |
   | 生态覆盖 | 长尾缺（常见科学计算包有，长尾 C 扩展基本无） | 完整 |
   | 逃逸面 | 内存安全消灭整类内存逃逸；剩余风险在宿主注入的 API 本身 | 共享内核，container escape 历史上真实发生（runc CVE 系） |

   结论：**LLM 生成的受限纯计算代码（无重依赖）→ WASM（快、密度高、安全由构造保证）；需要任意生态（pip/npm install）→ Docker；多租户公开服务 → Firecracker microVM（连共享内核都不接受）**。agent4j v1 不选边：`SandboxTier.DOCKER / MICROVMM / WASM` 是占位 tier，`SandboxReport` 对它们「零保证大声声明」——占位不冒充实现，正是谱系文档的诚实纪律。
