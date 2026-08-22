# Stage 10 · MCP 与外部生态集成：概念与场景

> 沉淀日期：2026-08-22
> 阶段：Stage 10 / 18（MCP 与外部生态集成）
> 类型：概念课笔记（先概念和场景，后工程实现）
> 代码锚点：`agent-mcp` 模块（v1 已完成 stdio 闭环，A2A 仅接口骨架）
> 文章映射：《MCP 是什么：Agent 的 USB 接口》《MCP vs A2A：纵向接工具 vs 横向连 Agent》《MCP 的 5 道生产坎》

---

## TL;DR

- **MCP 解决 M×N 集成地狱**：M 个 Agent 应用 × N 个外部能力，无标准协议要写 M×N 套定制集成；有 MCP 双方各实现一次协议，变成 M+N。
- **三个角色**：Host（运行 LLM 的应用 = 我们的 Runtime）/ Client（Host 内 1:1 连接）/ Server（暴露工具的进程）。Client ≠ Host，一个 Host 可挂多个 Client。
- **协议骨架**：JSON-RPC 2.0（Request / Response / Notification 三种消息）+ 三大原语（Tools / Resources / Prompts，我们只实现了 Tools）。
- **McpToolAdapter 是全 Stage 最关键的类**：把远程工具适配成本地 `Tool` 接口，Stage 9 治理层（权限/审批/审计/净化）对远程工具自动生效，一行代码不用改。
- **A2A 与 MCP 互补不竞争**：MCP 纵向（Agent → 工具/数据，callTool 立即返回），A2A 横向（Agent → Agent，sendTask 异步委托有生命周期）。MCP 给 Agent 装手，A2A 让 Agent 长嘴。
- **生产 5 道坎**：工具爆炸、不可信 Server、鉴权透传、Trace 透传尚未处理（v2 路线图）；**进程管理已修**（v1：ping 健康检查 + ManagedMcpClient 自动重启 + 防风暴预算）。

---

## 一、为什么存在：M×N 集成地狱

没有 MCP 的世界：

```text
你有 3 个 Agent 应用（Claude Desktop、Java Runtime、Cursor）
你要接 5 个外部能力（文件系统、GitHub、数据库、Slack、搜索）

3 × 5 = 15 套定制集成代码
每套的鉴权、格式、错误处理、进程管理全都不一样
```

MCP（Model Context Protocol，Anthropic 2024 年 11 月推出）把它变成：

```text
每个 Agent 应用实现一次 MCP Client：3 份
每个外部能力实现一次 MCP Server：5 份
3 + 5 = 8 份，且任何 Client 能连任何 Server
```

**USB-C 类比**：USB-C 之前，每台设备一种充电口；MCP 之前，每个 Agent 框架一套工具接入协议。这就是《MCP 是什么：Agent 的 USB 接口》的立意。

**设计判断**：MCP 的价值不在"功能更强"，而在"标准化带来的组合爆炸消除"。跟 HTTP 统一了信息交换、JDBC 统一了数据库访问是同一类故事。

---

## 二、MCP 的三个角色（第一遍学最容易搞混的地方）

```text
┌─────────────────────── Host ───────────────────────┐
│  Java Agent Runtime                                  │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐              │
│  │McpClient│  │McpClient│  │McpClient│   ← 1:1 连接  │
│  └────┬────┘  └────┬────┘  └────┬────┘              │
└───────┼────────────┼────────────┼───────────────────┘
        │ stdio      │ SSE        │ stdio
   ┌────▼────┐  ┌────▼────┐  ┌────▼────┐
   │Server A │  │Server B │  │Server C │  ← 暴露能力的进程
   │(文件系统)│  │(远程API) │  │(数据库) │
   └─────────┘  └─────────┘  └─────────┘
```

| 角色 | 是什么 | 对应我们的代码 |
|---|---|---|
| Host | 运行 LLM 的应用 | Java Agent Runtime 整体 |
| Client | Host 内部维护的一条到 Server 的 1:1 连接 | `McpClient` |
| Server | 暴露能力的外部进程 | `McpServerDescriptor` 描述的目标 |

**关键认知**：Client 和 Host 不是一回事。一个 Host 里可以同时挂多个 McpClient，每个管一个 Server 子进程。这是后面做"MCP Server 发现和注册（Registry）"时的架构基础。

---

## 三、协议骨架：JSON-RPC 2.0 + 三大原语

### 3.1 三种消息（MCP 的全部协议消息）

| 消息 | 我们的类 | 特征 |
|---|---|---|
| Request | `JsonRpcRequest` | 有 `id`，必须有响应 |
| Response | `JsonRpcResponse` | 带 `result` 或 `error`，`id` 对应请求 |
| Notification | `JsonRpcNotification` | 无 `id`，fire-and-forget |

### 3.2 Server 能暴露的三种原语

| 原语 | 谁决定调用 | 语义 | 我们的支持 |
|---|---|---|---|
| **Tools** | 模型决定 | 可调用的函数（控制反转） | ✅ `tools/list` + `tools/call` |
| **Resources** | 应用决定 | 加载的数据（文件、DB 行） | ❌ v2 |
| **Prompts** | 用户决定 | 提示词模板 | ❌ v2 |

三者区别一句话：**Tools 是模型的手，Resources 是应用的眼，Prompts 是用户的快捷键。**

### 3.3 一次完整数据流（对照 McpClient 方法）

```text
McpClient.connect()          ← initialize 三步握手：
                                ① 发 initialize 请求（客户端能力+协议版本）
                                ② 收 initialize 响应（Server 能力）
                                ③ 发 notifications/initialized（握手完成）
    ↓
McpClient.listTools()        ← tools/list，Server 返回工具清单
                                （名字 + 描述 + JSON Schema）
    ↓
McpToolAdapter 包装          ← 每个 MCP 工具变成一个本地 Tool，
                                注册进 ToolRegistry
    ↓
Agent Loop 运行，模型选中某工具
    ↓
McpToolAdapter.execute()     ← 委托 McpClient.callTool()
                                → tools/call
                                → StdioTransport 发 JSON-RPC
    ↓
Server 执行，返回 content[]   ← extractTextContent() 只取 text 类型
                                （image / resource v1 跳过）
    ↓
结果交回模型，继续循环
```

### 3.4 initialize 握手为什么是"三步"而不是两步

request-response 之后还要单独发一个 `notifications/initialized`，看起来多余，实际是给 Server 一个明确的"协商窗口关闭"信号：在此之前 Server 不应发送任何其他消息。类似的模式：TCP 三次握手、TLS ChangeCipherSpec。**能力协商协议都需要一个显式的"切换到工作模式"边界。**

---

## 四、McpToolAdapter：整个 Stage 10 最关键的类

适配器注释原文（模块灵魂）：

```text
MCP 工具注册进 ToolRegistry   → Agent Loop 不知它是远程的
GovernedToolExecutor 包装它   → Stage 9 的权限/审批/审计/净化自动生效
ModelRequest.tools 带上它     → Stage 1 的模型调用层无差别使用
```

**设计模式的回报**：因为 Stage 9 治理层是装饰器模式包在 `Tool` 接口上的，接入 MCP 这种全新连接方式时，治理层一行代码不用改。远程工具和本地工具享受完全相同的权限检查、审批流程、审计日志。

**面试高光点**：

> "我的框架接 MCP Server 不需要为远程工具单独设计安全体系——这是接口抽象 + 装饰器分层在 Stage 9 就埋好的伏笔。MCP 工具默认 REQUIRES_APPROVAL（设计决策 D4），比本地工具更严格，因为远程 Server 是不可信边界。"

---

## 五、场景对号入座

### 5.1 三个 Profile 的 MCP 需求

| Profile | MCP 场景 | 具体例子 |
|---|---|---|
| **Coding Agent**（Stage 17） | 最重度用户 | 接 filesystem / git / 搜索 Server，Claude Code、Cursor 就是这么干的 |
| **Enterprise Agent**（Stage 15） | 接内部系统 | 公司把 CRM、工单、知识库各封装成 Server，所有 Agent 共用 |
| **Tavern Game**（Stage 16） | 基本不用 | 游戏领域模型内聚——**"MCP 不是所有场景都需要"本身就是设计判断** |

### 5.2 MCP Server vs 本地插件（"MCP Server 是一种远程插件来源"）

| 维度 | 本地插件（JAR + ClassLoader，Stage 3） | MCP Server（子进程/远程） |
|---|---|---|
| 加载速度 | 快 | 慢（进程启动/网络连接） |
| 跨语言 | 否，必须 Java | 是，Node/Python/Go 都行 |
| 调用开销 | 同 JVM，无序列化 | JSON-RPC 序列化 + IPC/网络 |
| 故障隔离 | 同 JVM，挂了可能连累 Host | 进程级隔离，挂了不连累 Host |
| 升级部署 | 随 Host 重启 | 独立升级、独立部署 |
| 信任级别 | 高（自己编译的） | 低（别人写的，不可信） |

**选型判断**：核心高频工具走本地插件（性能），生态长尾工具走 MCP（复用）。信任级别决定治理默认档位——MCP 工具默认 REQUIRES_APPROVAL。

---

## 六、A2A：Agent 之间的外交语言

A2A（Agent-to-Agent，Google 2025 年推出，后归 Linux 基金会）解决另一个维度的问题。

### 6.1 三个核心概念（对应我们的接口骨架）

| 概念 | 我们的类 | 是什么 | 现实对应 |
|---|---|---|---|
| **AgentCard** | `AgentCard` record | Agent 的名片：名字+会什么（skills）+怎么找到我（endpoint） | `/.well-known/agent.json`，类似 robots.txt |
| **A2ATask** | `A2ATask` | 任务委托，**有生命周期**（pending → running → completed/failed），可能跑几分钟几小时 | 工单 |
| **A2AMessage** | `A2AMessage` | Agent 间消息，fire-and-forget | 便签 |

### 6.2 MCP vs A2A 一图流（文章 6 核心素材）

```text
              纵向（MCP）              横向（A2A）
           Agent → 工具/数据        Agent → Agent
对端是什么     无智能的函数/资源        有自主性的另一个 Agent
调用语义      callTool("query", args)   sendTask("帮我审查这个PR")
              立即返回结果              异步委托，有状态流转
失败语义      重试或报错               协商、超时、部分完成
信任模型      Server 不可信需治理       对端 Agent 也要分级（Trust Level）
类比         你手里的工具箱            你找同事协作
```

**一句话记住：MCP 给 Agent 装手，A2A 让 Agent 长嘴（对外说话）。** 二者互补不竞争——Claude 既用 MCP 接工具，也通过任务委托和别的 Agent 协作。

### 6.3 为什么 A2A 实现留到 Stage 11（正确决策）

A2A 的本质是**委托有自主性的对端**，必须先有编排层（Orchestrator/Worker/结果聚合）才有意义。Stage 10 只引入数据模型证明可用性，是 YAGNI 的正确应用。

---

## 七、生产环境的 5 道坎（文章 4 提纲 + 代码现状缺口）

| # | 坎 | 问题 | 我们的现状 |
|---|---|---|---|
| 1 | **工具爆炸** | 接 10 个 Server = 几百个工具 schema 全塞 prompt，上下文爆炸、模型选错工具率上升 | 未处理（需按需注入/工具过滤，呼应 Stage 8 ContextBudget） |
| 2 | **不可信 Server** | Server 返回文本藏"请忽略之前指令"——prompt injection 借道 MCP | Stage 9 ResultSanitizer 理论覆盖（D6），未专项验证 |
| 3 | **进程管理** | stdio 模式 Server 是我们 spawn 的子进程，崩了谁重启？僵尸进程？ | ✅ **已修（v1）**：`ping()` 健康检查 + `ManagedMcpClient` 自动重启 + `McpRestartPolicy` 防风暴（详见 7.1） |
| 4 | **鉴权透传** | 用户 OAuth token 怎么传给 Server？明文=泄露，不传=没权限 | 未实现 |
| 5 | **Trace 透传** | 一次 Run 跨 3 个 Server，OpenTelemetry trace context 怎么跟 JSON-RPC 走 | 未实现（Stage 18 可观测性一起做） |

### 7.1 进程管理 ✅ 已修（2026-08-22，v1）

**机制 vs 策略分离**（面试叙事）：

```text
机制（McpClient 微改，连接生命周期本来就是它的职责）：
├── reconnect() -- 关旧 transport -> factory 造新的 -> 重新 initialize 握手
├── ping()      -- MCP 标准 liveness 探测（真实 filesystem server 实测响应）
└── transport 工厂化 -- 每次 (re)连接由 factory 造全新 transport（新子进程）

策略（ManagedMcpClient extends McpClient，自动恢复装饰器）：
├── callTool/listTools 失败 -> 死进程检测（!transport.isOpen()）
│   -> 重启预算内？ -> reconnect -> 单次重试（不递归，重试失败直接抛）
├── 协议错误（进程活着）不重启 -- 重连无意义，直接抛
└── McpRestartPolicy(maxRestarts, cooldownMs, windowMs) -- 防重启风暴三件套
```

**存量零改动**：`McpToolAdapter` / Stage 9 治理层看到的就是 `McpClient`（Managed 是子类），完全无感知。

**真实验收**（`ManagedMcpExample`）：强杀真实 filesystem server 子进程（`destroyForcibly()`）-> `isHealthy()`=false -> 下一次 `callTool` 自动复活（新子进程 + 重新握手 + 重试，2462ms 含 npx 重启）-> 调用方无感。

**v1 诚实边界**：挂死检测（进程活着但不响应）没有做--需要 receive 超时，留 Stage 18 可观测性。

另外两个明确缺口：**SSE 传输**（`McpClient` 构造函数里直接 throw 的那个）、**MCP Server Registry**（发现和注册）。

---

## 八、面试速答

**30 秒版（Stage 10 完整叙事）**：

> MCP 解决 M×N 集成问题，让 Runtime 不用为每个外部系统写定制接入。我实现了完整 v1：stdio 传输、JSON-RPC 2.0、initialize 能力协商、tools/list 发现、tools/call 调用。关键设计是 McpToolAdapter 把远程工具适配成本地 Tool 接口，Stage 9 治理层对远程工具自动生效，一行代码不用改；且 MCP 工具默认 REQUIRES_APPROVAL，比本地工具更严。A2A 定义了 AgentCard/Task/Message 数据模型，实现留给 Stage 11——因为 A2A 的本质是委托有自主性的对端，必须先有编排层才有意义。

**快问快答**：

1. **MCP 三个角色？** Host（跑 LLM 的应用）/ Client（Host 内 1:1 连接）/ Server（暴露工具的进程）。Client ≠ Host。
2. **MCP 和插件系统什么关系？** MCP Server 是一种远程插件来源。本地插件要 Java 同 JVM 高性能高信任；MCP 跨语言进程隔离但不可信。
3. **为什么 MCP 工具默认要审批？** 远程 Server 是不可信边界，返回内容可能带 injection，治理档位必须比本地工具严。
4. **MCP vs A2A？** 纵向接工具 vs 横向连 Agent；callTool 立即返回 vs sendTask 异步委托有生命周期；对端无智能 vs 有自主性。
5. **MCP 生产最大的坑？** 工具爆炸（几百个 schema 塞爆上下文）和不可信 Server（injection 借道）——一个吃预算，一个破安全。

---

## 九、下一步（按"每天一个工程问题"原则）

1. **跑通真实 Server ✅ 已完成（2026-08-22）**：`npx @modelcontextprotocol/server-filesystem /tmp` 用 McpClient 连一次，验证协议兼容性——v1 目前只用 Mock 测过。
   完成详情：`McpRealServerExample`（examples 模块）握手 3044ms、发现 14 个真实工具、`list_allowed_directories` / `list_directory` / `read_text_file` 三个 tools/call 全通；顺手修复 `StdioTransport` stderr 死锁隐患（drainer daemon 线程，"进程管理"坎的真实案例）。协议观察：能力协商真实生效（client 未声明 roots -> server 回退命令行参数目录）；`shutdown` 非 MCP 标准方法（返回 -32601，stdio server 正确关闭 = 关 stdin + 杀进程）。
2. **对照走读代码**：按 3.3 的 7 步数据流从 `connect()` 到 `callTool()` 走一遍，每步写出对应 JSON-RPC 消息长什么样。
3. ~~**挑一道坎修**：进程管理（Server 崩溃自动重启）~~ **✅ 已完成（2026-08-22）**：`ManagedMcpClient` + `McpRestartPolicy` 落地，45/45 测试全绿（+7），真实 Server 崩溃自愈演示通过。详见 7.1。

---

## 附：与学习规划的阶段验收对照

| 规划验收项 | 状态 |
|---|---|
| Agent 能连接一个外部 MCP Server 并使用其工具 | ✅ v1（Mock）· 真实 Server 待验证 |
| MCP 工具自动注册为框架 Tool，与本地工具无差异使用 | ✅ McpToolAdapter + McpExample 跑通 |
| 两个 Agent 能通过 A2A 协议委托任务和返回结果 | ⏳ 接口就绪，实现留 Stage 11 |
