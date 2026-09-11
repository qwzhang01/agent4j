# 实验记录：KP9 沙箱谱系——隔离等级与逃逸面（2026-09-12）

> 性质：KP9（沙箱谱系）的落地实验笔记，对应新增类 `SandboxRiskLevel` / `SandboxPolicy` / `SandboxTier` / `SandboxEscalator`，测试 `SandboxPolicyTest` / `SandboxEscapeTest` / `SandboxEscalatorTest`。  
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

## 关键认知：三个一手工程事实

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
├── blocked → isBlocked() 检测 "Blocked:" 前缀 → 升级到 ProcessSandbox
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
- `SandboxTier.DOCKER / MICROVMM / WASM` 是文档占位，无实现
- 第三方互操作（在 Docker 里运行 agent4j）未实测

---

## 新思考题

1. ~~`ProcessSandbox` 如何接 `-Xmx` 让 `memoryLimitBytes` 真正生效？~~ **已收口（2026-09-12）**：`javaCommand` 在 `java` 行加 `-Xmx`，不碰 `javac`。精确 MB 用 `m` 后缀，否则裸字节。验证：子进程 `maxMemory() <= limit` 且明显高于未设 cap 时的偶然小堆。
2. ClassLoader 的「乐观升级」是否应该有重试次数限制（防止代码反复触发 block）？
3. 多语言（Python/JS）LLM 生成代码的沙箱该走 WASM 还是 Docker？代价如何比较？
