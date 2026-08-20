# Stage 9 源码导读：概念 -> 实体 -> 流程 -> 设计模式 -> 架构

> 对应阶段：Stage 9 - Tool Governance、安全与审计
> 定位：源码理解笔记 -- 用"公司前台安检"比喻把 agent-security 模块 15 个文件讲清楚
> 配套：架构设计见 [architecture-stage-9.md](architecture-stage-9.md)，8 层教程见 [stage-9-tutorial.md](stage-9-tutorial.md)，可扩展性评估方法参照 [stage-8-extensibility-review.md](stage-8-extensibility-review.md)

---

## 全局：15 个文件 = 一套"公司前台安检"

把 **Agent 调工具** 想象成 **访客要进公司**：

```text
模型说"我要调 delete_file"  =  一个访客走到前台说"我要见你们老板"
DefaultToolExecutor（旧版）  =  没有前台，推门就进
GovernedToolExecutor（新版） =  装了完整前台流程
```

### 比喻映射表

| 公司前台的组件 | 我们的文件 | 干什么 |
|--------------|-----------|--------|
| **访客等级制度**（VIP 直接进/需登记/黑名单） | `ToolPermission` | 三档枚举：AUTO / REQUIRES_APPROVAL / DENY |
| **访客名单册**（谁是 VIP、谁在黑名单） | `ToolPolicy` | 记录"每个工具是哪一档" |
| **前台查名单的接待员** | `PermissionChecker` | 拿着名单册查"这位访客是哪档" |
| **打电话确认流程**（"王总，有访客要见你"） | `ToolApprovalService` | 接口：要审批时怎么问人 |
| 电话的几种接法 | `ConsoleApprovalService` | 实现：自动同意/自动拒绝/真人接/转接自定义 |
| **每小时接待上限** | `RateLimiter` | 接口：限流 |
| 挂在墙上的计数器 | `SimpleRateLimiter` | 实现：简单计数窗口 |
| **行李扫描仪**（检查带进来的东西） | `ResultSanitizer` | 接口：扫工具结果有没有注入 |
| 违禁品特征库（刀/枪长什么样） | `InjectionPattern` | 静态规则：三类注入的 regex |
| 扫描仪的处理方式（没收/截留/整箱扣） | `DefaultResultSanitizer` | 实现：SANITIZE/TRUNCATE/BLOCK |
| 扫描报告 | `SanitizeResult` | record：改没改、为什么 |
| **登记簿的一行**（谁/几点/进还是拒） | `AuditEvent` | record：九个字段 |
| 登记簿本身 | `AuditLogger` | 接口：记录和查询 |
| 放在前台的登记本（搬家就丢） | `InMemoryAuditLogger` | 实现：内存版 |
| **整个前台流程本身**（把上面全串起来） | `GovernedToolExecutor` | 核心：六步串联 |

---

## 一、代码分组：15 个文件只有 4 种角色

```text
┌──────────────────────────────────────────────────┐
│ 第 1 种：数据类（3 个）-- 安检中流动的"单据"        │
│   ToolPermission   一档权限（一个枚举值）           │
│   AuditEvent       一条审计记录（一次调用的结局）    │
│   SanitizeResult   一份扫描报告（结果改没改）       │
│                                                  │
│ 第 2 种：规则类（2 个）-- 安检的"法律条文"          │
│   ToolPolicy       名单册（工具 -> 档位）           │
│   InjectionPattern 违禁品特征库（regex 集合）       │
│                                                  │
│ 第 3 种：接口 + 实现（4 对）-- 安检的"设备"         │
│   ToolApprovalService  <- ConsoleApprovalService  │
│   ResultSanitizer      <- DefaultResultSanitizer  │
│   AuditLogger          <- InMemoryAuditLogger     │
│   RateLimiter          <- SimpleRateLimiter       │
│                                                  │
│ 第 4 种：组装者（2 个）-- 把设备串成流程            │
│   PermissionChecker   查名单（规则类的薄封装）       │
│   GovernedToolExecutor 整个前台（唯一的主角）★     │
└──────────────────────────────────────────────────┘
```

**记忆口诀：3 张单据、2 本规则、4 台设备、1 个前台。**

### 为什么这么多接口（常见困惑）

"审批就是问一句 yes/no，净化就是替换几个词，为什么都要接口 + 实现两个文件？"

因为**每台设备都会换**：

```text
审批设备会换：
  教学演示   -> ConsoleApprovalService.console()（控制台问）
  接 Slack   -> ConsoleApprovalService.callback(tc -> 发Slack消息)
  企业内部   -> 自己实现 ToolApprovalService 接 Web 审批系统

审计设备会换：
  v1 教学    -> InMemoryAuditLogger（内存，重启丢）
  v2 生产    -> 实现 AuditLogger 接口，写数据库 / 哈希链防篡改

净化设备会换：
  v1 教学    -> DefaultResultSanitizer（regex 模式匹配）
  v2 增强    -> 实现 ResultSanitizer，加 LLM 语义判官
```

**接口是"插槽"，实现是"插进去的卡"。** 换卡不动插槽 = 换实现不改调用方 = 同 Stage 8 的哲学（MemoryStore -> InMemoryMemoryStore，将来 VectorMemoryStore）。

---

## 二、一次调用的完整旅程（流程）

以 delete_file（REQUIRES_APPROVAL 档）为例：

```text
模型：我要调 delete_file("/tmp/x")
          │
          ▼
┌─ 前台接待员查名单（PermissionChecker + ToolPolicy）─┐
│    名单册：delete_file -> REQUIRES_APPROVAL          │
│    （不是 DENY，不是 AUTO，走审批）                    │
└──────────────────────┬───────────────────────────┘
          ▼
┌─ 打电话确认（ToolApprovalService）────────────────┐
│    "有 Agent 想执行 delete_file(/tmp/x)，允许？"      │
│    人点了允许                                          │
│    -> 登记簿记一笔：[APPROVED]                        │
└──────────────────────┬───────────────────────────┘
          ▼
┌─ 计数器（RateLimiter）───────────────────────────┐
│    delete_file 这个月第 3 次调用，没超限 -> 放行       │
└──────────────────────┬───────────────────────────┘
          ▼
┌─ 真正执行（delegate -> DefaultToolExecutor）──────┐
│    执行工具，拿到结果字符串                            │
│    计时：耗时 3ms                                     │
└──────────────────────┬───────────────────────────┘
          ▼
┌─ 行李扫描（ResultSanitizer + InjectionPattern）───┐
│    扫结果里有没有 [SYSTEM]/ignore instructions/外发URL │
│    干净 -> 原样放行                                   │
│    （有毒 -> 净化 + 登记簿记 [SANITIZED]）             │
└──────────────────────┬───────────────────────────┘
          ▼
┌─ 登记收尾（AuditLogger）─────────────────────────┐
│    登记簿记最后一笔：[EXECUTED] delete_file 3ms       │
└──────────────────────┬───────────────────────────┘
          ▼
   结果返回模型："deleted /tmp/x"
```

**六步 = 查名单 -> 问人 -> 看计数 -> 执行 -> 扫行李 -> 记登记。**

任何一步被拦（DENY/人拒绝/超频），旅程就地终止，登记簿记一笔 [DENIED] + 原因，模型收到 [DENIED] 文本自己想办法。

---

## 三、架构：谁依赖谁

```text
                 调用方（AgentLoop / 测试 / 示例）
                            │
                            ▼
              ┌─────────────────────────┐
              │   GovernedToolExecutor   │ ★ 唯一入口
              │  （前台流程，组装者）       │
              └──┬────┬────┬────┬────┬──┘
                 │    │    │    │    │
     ┌───────────┘    │    │    │    └───────────┐
     ▼                ▼    ▼    ▼                ▼
PermissionChecker  Tool  Result Audit          RateLimiter
     │             Approval Sanitizer Logger      │
     ▼             Service    │      │            ▼
  ToolPolicy          │       │      │      SimpleRateLimiter
  （名单册）           ▼       ▼      ▼
                 Console  Default InMemory
                 Approval ResultSan. AuditLogger
                 Service      │
                             ▼
                      InjectionPattern
                      （违禁品特征库）

数据类在中间流动：ToolPermission / AuditEvent / SanitizeResult
                 （不依赖任何人，被所有人引用）
```

三条架构原则：

```text
1. GovernedToolExecutor 是唯一主角
   外部只需要认识它，其余 14 个文件都是它的"零部件"

2. 全部零部件可替换（nullable / 接口）
   拆掉任何一台设备，前台照常运转（少一道工序而已）
   全拆 = 透传 = 行为回到 Stage 1-8

3. 数据类（record）零依赖
   ToolPermission / AuditEvent / SanitizeResult
   被所有层引用，自己不引用任何人 -- 单据谁都能看，单据不认识人
```

---

## 四、设计模式清单

| 模式 | 用在哪 | 解决什么问题 |
|------|--------|-------------|
| **装饰器** | `GovernedToolExecutor` 实现 `ToolExecutor` 接口，包装 delegate | 不改 DefaultToolExecutor 一行代码，加上全部治理；调用方类型不变 |
| **策略模式** | `ToolPolicy`（权限策略）、`DefaultResultSanitizer.Strategy`（净化策略） | 换策略不改流程；"名单怎么定"和"前台怎么运转"解耦 |
| **Builder** | `GovernedToolExecutor.builder()` | 6 个可选参数（2^6 组合），链式组装表达"我要哪几件" |
| **工厂方法** | `ConsoleApprovalService.autoApprove()/autoReject()/console()/callback()` | 四种审批行为一个类搞定，命名即文档 |
| **接口分离** | 审批/净化/审计/限流四个独立接口，而非一个"SecurityManager"大接口 | 每台设备独立演进、独立替换；不想用的直接传 null |
| **静态工具类** | `InjectionPattern`（纯 static） | 特征库是无状态的规则集合，不需要实例 |

**最核心的是装饰器**--它回答了整个模块存在形式的根本问题："怎么在不碰旧代码的前提下给执行链加五道闸？" 答案：包一层，对外还是同一个接口。

---

## 五、三个最容易混的对比

### 混淆 1：ToolPolicy vs PermissionChecker，不都是"查权限"吗？

```text
ToolPolicy          = 名单册（数据：工具名 -> 档位的映射表）
PermissionChecker   = 查名单的动作（行为：拿名字去查，返回档位）

类比：通讯录 vs 查通讯录的手
     名单册可以换（换个 Policy 对象），查法以后也可能变
     （比如将来加"管理员角色例外"-- 改 Checker 不动 Policy）
```

现状下 Checker 确实是薄封装，看起来多余。它存在的原因：**给未来的复杂判断留位置**，让 GovernedToolExecutor 只跟 Checker 对话，不直接翻名单册。

### 混淆 2：接口和实现为什么要分开两个文件？

```text
ToolApprovalService.java      = 插槽（定义"审批长什么样"：给调用，返回批不批）
ConsoleApprovalService.java   = 插进插槽的卡（具体怎么问人：控制台/自动/回调）

分开的价值：
  测试时插"永远同意"的卡（autoApprove）
  演示时插"控制台问"的卡（console）
  生产时插"发 Slack"的卡（callback 或自己实现接口）
  换卡时，前台流程（GovernedToolExecutor）一个字不用改
```

### 混淆 3：这个审批和 Stage 5/6 的审批为什么是两套？

```text
Stage 5/6 ApprovalService    = 审批"流程节点"
  语境：退款流程走到"人工审批"节点
  请求体：nodeId + summary + payload（业务语义）

Stage 9 ToolApprovalService   = 审批"工具调用"
  语境：Agent 要执行 delete_file("/tmp/x")
  请求体：toolCall + runId（风险语义）

为什么不合用：审批人回答的问题不同
  节点审批："这笔退款该不该批？"（业务判断）
  工具审批："删这个文件安全吗？"（风险判断）
```

---

## 六、收束

> **agent-security 的 15 个文件 = 一套公司前台安检系统：3 张单据（权限档位、审计事件、扫描报告）+ 2 本规则（名单册、违禁品特征库）+ 4 台可替换设备（审批、净化、审计、限流，每台都是接口+实现）+ 1 个前台（GovernedToolExecutor 用装饰器模式把设备串成六步流程：查名单->问人->看计数->执行->扫行李->记登记）。任何一步拦下就地终止并记 DENIED；全部设备可拆（nullable），全拆就退化回 Stage 1-8 的直通执行；所有设备将来可换新卡不动机器。**

---

## 对照 Stage 8 源码导读的方法论

| 维度 | Stage 8（memory） | Stage 9（security） |
|------|-----------------|---------------------|
| 分组方式 | 四层（数据模型/存储/流水线/上下文） | 四种角色（单据/规则/设备/前台） |
| 核心比喻 | 金鱼 + 搬运工 | 公司前台安检 |
| 唯一主角 | MemoryContextBuilder（读取流水线） | GovernedToolExecutor（治理执行器） |
| 可替换哲学 | Store 换实现不动调用方 | 设备换实现不动前台 |
| 数据类零依赖 | MemoryEntry / Scope / Provenance | ToolPermission / AuditEvent / SanitizeResult |

两个模块共享同一个设计基因：**接口插槽 + 可替换实现 + 组装者串联 + record 单据零依赖**。这是这个框架的通用架构语言，Stage 10 接 MCP 时还会再现。
