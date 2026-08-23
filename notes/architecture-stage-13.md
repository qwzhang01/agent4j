# Stage 13 架构设计：上层产品搭建层

> 对应阶段：Stage 13 - 上层产品搭建层（声明式 Agent 定义 / 模板系统 / 配置驱动 Tool / Prompt 管理 / 事件驱动接入 / DAG 标准 / 多租户）
> 状态：✅ 已完成（2026-08-23，一天完成 M13.1-M13.5 五个里程碑）。agent-product 模块 157 测试全绿，全仓 602 存量零影响；2 个验收示例（DeclarativeAgentExample 实跑通过）
> 模块：新增 `agent-product` Maven 模块，依赖 `agent-core`（Agent/AgentConfig）+ `agent-model`（ModelClient 注册）+ `agent-memory`（ContextBuilder/scope 模板）+ `agent-workflow`（Workflow/DagSpec 转换）+ `agent-channel`（ambient 段构造 AmbientInstruction）；agent-security 经组装层可选注入（同 Stage 11/12 模块边界纪律）
> 前置：Stage 1-12 已完成（426 测试全绿；频道共享 / Identity / Ambient 已落地）

---

## 1. 核心命题：从「Java 工程师的库」到「业务方的产品」

Stage 1-12 造好的 Runtime 有一个隐含前提：**搭 Agent 的人会写 Java**。每创建一个 Agent 都是一段装配代码——new ModelClient、注册 Tool、构造 AgentConfig、写 main 方法。这个前提在产品场景下全线失效：

```text
Runtime 的五个隐含前提，在产品场景全部破裂：
1. 作者前提 -- 假设定义者 = Java 工程师，但客服 Agent 的"作者"是客服运营
2. 形态前提 -- Agent 定义活在代码里，改一个称呼要发版；产品形态是"配置即发布"
3. 工具前提 -- 每个 Tool 一个 Java 类；企业里大量工具是现成 REST API，包一层是纯胶水
4. 触发前提 -- Agent 只被调用方代码驱动；外部系统（GitHub / 监控 / 表单）也想触发
5. 迭代前提 -- Prompt 是字符串常量；产品里 Prompt 是每周都要改的"资产"
```

Stage 13 的答案：Agent 定义从 Java 代码**外置**为声明式配置（YAML），Prompt 变成可管理的**资产**（版本化 / 灰度 / 热切换），工具通过**配置**接入（HTTP API 不写代码），触发通道**多元化**（Webhook / 事件），Workflow 可被**可视化**（DAG 描述标准）。

一句话（接 Stage 6-12 的递进叙事）：

```text
Stage 6  让 Run 能暂停-恢复
Stage 7  让 Run 能自动恢复
Stage 8  让 Agent 能记住
Stage 9  让 Agent 能被信任
Stage 10 让 Agent 能连接
Stage 11 让 Agent 能协作
Stage 12 让 Agent 能入驻团队
Stage 13 让 Agent 能被"搭出来" -- 不写 Java，业务方自己定义、发布、迭代
```

### 与相邻概念的三条边界（面试高频）

```text
声明式 vs 编程式的边界：
  声明式暴露的是"配置面"（人格 / 工具引用 / 记忆策略 / 触发），不是"逻辑面"
  判断标准：能用"参数 + 引用 + 模板"表达的 -> 声明式
           需要条件分支 / 循环 / 自定义判定的 -> Workflow 或 Java 扩展点
  YAML 永远不该变成图灵完备语言

产品层 vs Runtime 的边界：
  产品层不造新能力，只把 Runtime 已有能力"翻译"成业务方可操作的形式
  （AgentDefinition -> AgentConfig；HttpApiTool -> Tool；Webhook -> run 入口）
  产品层的 bug 不该需要改 Runtime 才能修

配置驱动 Tool vs MCP（两条"不写 Java 加工具"的路线）：
  HttpApiTool = 配置层声明 -- 我知道这个 REST API 长什么样，我描述它
  MCP         = 协议层标准 -- 对方实现了协议，我直接发现它的工具
  前者适合零散企业 API，后者适合生态工具；注册后地位完全平等
```

---

## 2. 复用清单：Stage 13 是第二次「组装阶段」

Stage 12 的教训制度化：**规划时就做复用预检，不等完成后自查才发现偏差**（见 Stage 12 §13.1/§13.3 三处"复用"落空）。本清单每行标注预检结论。

| 能力需求 | 已有设施（阶段） | Stage 13 做什么 | 复用预检 |
|---|---|---|---|
| Agent 构造 | `AgentConfig` + `SimpleAgent` + `ReActAgentLoop`（1/2） | Binder 把 Definition 翻译成 AgentConfig，走现有构造路径 | ✅ 直接兑现（字段已齐） |
| 工具注册 | `ToolRegistry.register`（2） | HttpApiTool 是普通 Tool，注册路径不变 | ✅ 直接兑现 |
| 工具治理 | `GovernedToolExecutor` + `ToolPolicy`（9） | HttpApiTool 注册即被治理包装，零额外代码 | ✅ Stage 10 已证明（McpToolAdapter 同款路径） |
| 记忆配置 | `ContextBuilder` / `MemoryContextBuilder` / `MemoryScope`（8） | YAML memory 段映射为 ContextBuilder 配置 + namespace 变量模板 | ✅ 直接兑现 |
| 模型容错 | Retry / Timeout / Fallback / StructuredOutput 装饰器（1） | model 段的 provider/fallback 声明 -> 装饰器组装 | ✅ 直接兑现 |
| 工作流 | `Workflow` 不可变定义 + `WorkflowBuilder`（5） | DagSpec 双向转换（不可变定义天然可序列化） | ⚠️ 拓扑完整可表达；条件谓词是 lambda 不可序列化 -> 经注册表按名引用（D5） |
| 事件触发 | `EventBroker.fire`（7） | Webhook 触发的是**新 Run**，不是恢复旧 run | ❌ 预检不通过：EventBroker 回调绑死 `RunManager.resume(runId)`（Stage 12 D3 同款偏差），Webhook 直接走 Agent/Session 入口（D8） |
| Ambient 指令 | `AmbientInstruction` record（12） | definition.ambient 段从 YAML 构造（结构化部分） | ✅ record 已有；condition 谓词留 Java 扩展点（Stage 12 M12.4 诚实边界的部分兑现） |
| 服务身份 | `ServiceAccount` / `IdentityResolver`（12） | TenantAgentConfig 挂 ServiceAccount（每租户 Agent 一个服务身份） | ✅ 直接兑现 |

**依赖方向**：`agent-product -> agent-core + agent-model + agent-memory + agent-workflow + agent-channel`（security 可选注入）。新依赖仅新增 `jackson-dataformat-yaml`（与既有 Jackson 2.17.2 对齐）；HTTP 客户端复用 JDK `java.net.http.HttpClient`（agent-model 同款，零新依赖）。

---

## 3. 核心抽象（16 个，六组）

### 第一组：声明式定义（definition 包，M13.1）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `AgentDefinition` | 数据 | 解析后的 Agent 定义 record：metadata + spec 六段（persona / model / tools / memory / workflow / ambient） |
| `AgentDefinitionParser` | 核心 | YAML/JSON -> Definition（Jackson YAML 数据格式） |
| `DefinitionValidator` | 核心 | 结构校验 + 引用存在性校验，错误带 YAML 位置（面向业务方，不面向堆栈） |
| `ProductContext` | 核心 | 产品层注册表：models / tools / templates / prompts / workflows 按名索引（"定义存名字"的另一端） |
| `AgentDefinitionBinder` | 核心 | Definition + ProductContext -> Agent / SharedAgentSession 实例（D2 翻译器） |
| `ProductBootstrapper` | 核心 | 装配入口：注册实现 -> 扫描 agents/ 目录 -> 全部实例化（"新增 Agent = 放一个 YAML"） |

### 第二组：模板系统（template 包，M13.2）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `AgentTemplate` | 数据 | 带变量声明的 Definition 超集：variables（name/type/default/required）+ ${var} 占位 |
| `TemplateRegistry` | 核心 | 内置模板 + 租户模板的注册与查询 |

### 第三组：配置驱动工具（tools 包，M13.3）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `HttpApiTool` | 核心 | `implements Tool`：YAML 声明的 REST API 工具（参数映射 / jsonPath 提取 / 鉴权 / 超时） |
| `HttpApiToolFactory` | 核心 | tools.http 段 -> HttpApiTool 实例 |

### 第四组：Prompt 资产（prompt 包，M13.4）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `PromptManager` | 核心 | publish / resolve / rollback：版本追加不可变，resolve 是灰度分流点 |
| `PromptVersion` | 数据 | 不可变版本 record：content + semVer + channel + metadata |
| `PromptChannel` | 数据 | stable / canary 通道常量（v1 两通道；百分比灰度 v2） |

### 第五组：事件接入与 DAG（trigger + dag 包，M13.5）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `WebhookController` | 核心 | 传输无关的 Webhook 处理器：验签 -> 幂等 -> 异步触发（HTTP 壳在装配层） |
| `WebhookRoute` | 数据 | source -> agentRef + payload 模板的路由声明（v1 装配层注册） |
| `DagSpec` | 数据 | Workflow 的 JSON 描述标准：nodes / edges / 条件名 / 并行分支 |
| `WorkflowDagCodec` | 核心 | Workflow <-> DagSpec 双向转换 + ConditionRegistry（D5） |

### 第六组：多租户（tenant 包，M13.5）

| 抽象 | 层 | 一句话 |
|---|---|---|
| `TenantAgentConfig` | 数据 | 租户级覆盖：promptChannel / model / 工具开关 / ambient 开关 + 关联 ServiceAccount |

### 3.1 关键接口草图

```java
// ---- 声明式定义（第一组的管线）----
public final class ProductBootstrapper {
    public static Builder builder();
    // Builder: model(name, client) / tool(...) / templateDir(...) / promptStore(...)
    public ProductContext build();            // 注册表就绪
    public AgentRegistry startAll(Path agentsDir);   // 扫描 YAML -> 校验 -> 绑定 -> 就绪
}

public final class AgentDefinitionBinder {
    // 翻译器不是生成器（D2）：走 SimpleAgent/AgentConfig 现有构造路径
    public Agent bind(AgentDefinition def, ProductContext ctx);
    public SharedAgentSession bindChannel(AgentDefinition def, ProductContext ctx);  // 频道 Agent
}

// ---- 配置驱动工具（第三组）----
public final class HttpApiTool implements Tool {
    // name/description/schema 来自 YAML；execute = HttpClient 调用 + jsonPath 提取
    // 鉴权 token 从环境变量引用解析（${env:XXX}），密钥不落 YAML
}

// ---- Prompt 资产（第四组）----
public final class PromptManager {
    public PromptVersion publish(String name, String content, String channel);
    // Run 级 pin（D4）：会话启动时锁版本，会话内不再变
    public PromptVersion resolve(String name, String tenantId);   // 租户配置 > 默认通道
    public PromptVersion rollback(String name);                   // 指针切回，非内容回写
}

// ---- Webhook（第五组）----
public final class WebhookController {
    // 验签（HMAC）-> 幂等（eventId 去重）-> 202 语义（快速返回，异步执行）
    public WebhookResult handle(String source, Map<String,String> headers, Map<String,Object> payload);
}
```

### 3.2 定义样例（验收基准 YAML）

```yaml
apiVersion: v1
kind: Agent
metadata:
  name: support-bot
  tenant: acme                    # -> 变量 ${tenantId}
  template: support-agent@1.2     # 可选：模板派生 + 逐段覆盖
spec:
  persona:
    promptRef: { name: support-system, channel: stable }   # 指向 PromptManager
    temperature: 0.3
  model:
    provider: openai              # 引用 ProductContext 注册的 ModelClient
    fallback: deepseek
  tools:
    - ref: order-query            # 已注册本地工具（名字换实现，D1）
    - http:                       # 配置驱动注册（M13.3）
        name: weather-query
        description: 查询城市实时天气
        endpoint: https://api.weather.example/v1/now
        method: GET
        params:
          city: { in: query, type: string, required: true }
        response: { extract: "$.data.temperature" }
        auth: { type: bearer, token: "${env:WEATHER_TOKEN}" }
        timeout: 3s
  memory:
    shortTerm: { strategy: window, maxTurns: 20 }
    longTerm: { store: pgvector, namespace: "tenant-${tenantId}" }
  workflow: support-flow          # 可选：引用已注册 Workflow
  ambient:                        # 可选：Stage 12 接线（condition 留 Java 扩展点）
    - trigger: { onEvent: "ticket-updated" }
      importance: WARN
      messageTemplate: "工单 {ticketId} 状态变化，请关注"
```

---

## 4. 关键设计决策（8 个）

### D1. 「定义存名字，注册表存实现」——声明式的元模式

```text
AgentDefinition / DagSpec 里永远只有名字和参数，实现在 ProductContext 注册表里：
  tools.ref / model.provider / promptRef / workflow / DagSpec 的 when 条件
  全部是同一个模式：引用间接（reference indirection）

-> 这是"声明式不变成图灵完备语言"的机制保证：格式里放不下逻辑，只能放引用
-> 这是"定义可序列化"的机制保证：名字和参数天然可 JSON/YAML 化
-> 复杂逻辑的正确去处：注册为命名实现（Java bean / Workflow / 谓词），声明式引用它
```

### D2. Binder 是「翻译器」不是「生成器」

```text
不做：模板引擎生成 Java 代码 / 反射扫描装配
做：  AgentDefinitionBinder 把 Definition 逐段翻译成 AgentConfig + ToolRegistry 注册
      + ContextBuilder 配置，走 Stage 1-12 的现有构造路径

-> 产品层 bug 不需要改 Runtime 才能修（层间可独立演进）
-> 与 Stage 11/12 的组合哲学同源：orchestrator 包 Agent、channel 包 Agent、
   product 翻译出 Agent——三次兑现"不改存量、只加一层"
```

### D3. HttpApiTool 是普通 Tool，治理免费搭车

```text
HttpApiTool implements Tool -> 注册进 ToolRegistry -> 被 GovernedToolExecutor
自动包装（权限三档 / 审批 / 审计 / 净化）——Stage 10 D1 治理透明性第二次兑现
-> 与 MCP 双路线：HttpApiTool 连"我描述的 API"，McpToolAdapter 连"实现了协议的 Server"
-> 安全细节：鉴权 token 走 ${env:XXX} 环境变量引用，密钥不落 YAML（对齐 Stage 9 哲学）
-> 超时 / 错误映射成 ToolExecutionResult 错误（不炸 AgentLoop，复用 Stage 2 工具错误处理）
```

### D4. Prompt 热切换 = Run 级 pin

```text
进行中的对话锁定启动时的 PromptVersion；新对话自动拿最新 resolve 结果
-> 机制：会话/Run 启动时把 promptVersion 固化进 AgentState（Stage 6 快照思维的配置版）
-> publish 是不可变追加（版本历史即审计）；rollback 是指针切回而非内容回写
-> A/B 灰度 v1 = 双通道（stable/canary）+ 租户级指定（TenantAgentConfig.promptChannel）
   百分比分流需要 sticky session，v2
-> 与 Stage 3 插件热加载同一命题：热切换的单位是"新会话"，不是"进行中的会话"
```

### D5. DagSpec 是序列化标准，不是第二个 Workflow 引擎

```text
Workflow <-> DagSpec 双向转换，同一语义两个表示：
  可完整往返：图拓扑（nodes/edges）+ 节点类型 + 静态配置 + 引用名
  经注册表往返：条件谓词 / 节点行为按名引用（ConditionRegistry，D1 模式）
  诚实边界：未注册的 lambda 无法导出 -> fail-fast 报错，绝不静默丢条件

-> 前端（React Flow 等）消费 DagSpec JSON 渲染；v1 只做标准不做前端（范围控制）
-> "定义一次、执行 N 次"的再次兑现：Workflow 不可变定义天然可序列化
```

### D6. 模板实例 = fork 快照，不是指针

```text
instantiate = 变量替换 + 产出完整 Definition（快照），模板升级不影响已创建实例
-> 为什么不用指针/继承：模板 v2 改了人格，静默改变已发布租户的 Agent 行为
   是事故不是 feature；想升级 -> 显式 re-instantiate + 对比预览
-> 与 agent-arch-02 SKILL 系统呼应（写作素材）：SKILL 是"声明式能力包"，
   Template 是"声明式 Agent 骨架"——同源思想，不同粒度
```

### D7. 多租户 v1 = 配置隔离，不是运行隔离

```text
TenantAgentConfig 解决"每个租户能自己配什么"：
  promptChannel / model / 工具开关（disabledTools）/ ambient 开关
租户间运行隔离靠已有机制，不新建系统：
  记忆隔离 = MemoryScope namespace 模板 "tenant-${tenantId}"（Stage 8）
  身份隔离 = 每租户 Agent 关联 ServiceAccount（Stage 12）
  Token 配额 = Stage 18（TeamBudget/ChannelQuota 已在 18 周规划立案）
```

### D8. Webhook 是新触发面，不伪装成 EventBroker 订阅

```text
预检结论（§2）：EventBroker.fire 回调绑死 RunManager.resume(runId)，
Webhook 触发的是"新任务"不是"恢复旧 run"——直接复用必翻车（Stage 12 D3 的教训前置消化）
-> v1 路径：WebhookController -> WebhookRoute（source -> agentRef + payload 模板）
   -> 组装 ChatMessage -> agent.run() / session.speak()，复用的是 Agent 入口
-> Webhook 三件套（缺一不可）：
   1) 验签：HMAC（密钥环境变量），失败 401 + 审计
   2) 幂等：eventId 去重，重放安全
   3) 202 语义：快速返回 + 异步执行（外部系统的重试不能变成双跑）
```

---

## 5. 分层架构图

```text
┌──────────────────────────────────────────────────────────────────┐
│ examples: DeclarativeAgentExample / WebhookExample                 │
└────────────────────────────────┬─────────────────────────────────┘
                                 │
┌────────────────────────────────▼─────────────────────────────────┐
│ agent-product（Stage 13 新增）                                     │
│                                                                  │
│  ProductBootstrapper ── 注册实现 + 扫描 agents/ 目录                │
│       └── ProductContext（名字 -> 实现注册表，D1 的另一端）           │
│  definition/  AgentDefinition + Parser + Validator（位置化错误）     │
│               + AgentDefinitionBinder（D2：翻译器）                 │
│  template/    AgentTemplate + TemplateRegistry（D6：fork 快照）     │
│  tools/       HttpApiTool + Factory（D3：普通 Tool）                 │
│  prompt/      PromptManager + PromptVersion（D4：Run 级 pin）       │
│  trigger/     WebhookController + WebhookRoute（D8：三件套）         │
│  dag/         DagSpec + WorkflowDagCodec + ConditionRegistry（D5）  │
│  tenant/      TenantAgentConfig（D7：配置隔离）                      │
└────┬─────────┬──────────┬──────────┬──────────┬────────────────────┘
     │依赖       │依赖       │依赖       │依赖       │依赖
┌────▼────┐ ┌───▼─────┐ ┌──▼───────┐ ┌─▼───────┐ ┌─▼──────────────┐
│ core    │ │ model   │ │ memory   │ │workflow │ │ channel        │
│ Agent/  │ │ModelCli-│ │ContextBui│ │Workflow │ │SharedAgentSess.│
│ AgentCon│ │ent 注册表│ │lder/scope│ │/Builder │ │AmbientInstruct.│
│ fig     │ │装饰器(1) │ │模板(8)   │ │(5)      │ │(12)            │
└─────────┘ └─────────┘ └──────────┘ └─────────┘ └────────────────┘
          （agent-security 治理经 ToolExecutor 组装接线；
            EventBroker(7) 仅机制参考，Webhook 不走它——D8）
```

依赖链：`agent-product -> agent-core + agent-model + agent-memory + agent-workflow + agent-channel`（+ security 可选注入）。

---

## 6. 完整时序：一次「业务方从零搭建到事件触发」

```text
T0: 平台预置（管理员，一次性 Java）
    product = ProductBootstrapper.builder()
        .model("openai", openAi) .model("deepseek", deepseek)
        .tool(coreTools) .templateDir("templates/")
        .promptStore(prompts) .workflow("support-flow", flow)
        .build()

T1: 业务方定义（写 YAML，零 Java）
    agents/support-bot.yaml（§3.2 样例）
    -> Parser 加载 -> Validator 校验（tools.http 段 -> HttpApiToolFactory 构建）
    -> Binder 绑定（promptRef 解析 / model 装饰器组装 / memory namespace 变量替换）
    -> registry 就绪。新增一个 Agent = 放一个文件，不碰任何 Java

T2: Prompt 迭代（不重启，D4）
    promptManager.publish("support-system", v2, canary)
    -> 租户 acme 走 canary（TenantAgentConfig.promptChannel）-> 新对话用 v2
    -> 进行中对话（Run 级 pin v1）继续用 v1，行为不变
    -> v2 出问题 rollback = 指针切回 v1，秒级

T3: Webhook 触发（外部系统驱动，D8）
    监控告警 -> POST /webhooks/alerting（HMAC 签名头）
    -> 验签通过 -> eventId 幂等检查 -> 202 Accepted（快速返回）
    -> 异步：WebhookRoute("alerting" -> support-bot) payload 模板组装输入
    -> agent.run() / session.speak() —— 走 Agent 入口，不走 EventBroker

T4: 可视化（DAG 标准输出，D5）
    workflowDagCodec.toDag(registry.workflow("support-flow"))
    -> DagSpec JSON（nodes/edges/when 条件名）-> React Flow 渲染 -> 人看懂了
    -> （v2）拖拽编辑 -> 提交 DagSpec -> fromDag 反序列化 + ConditionRegistry 查回

T5: 失败分支
    - YAML 校验失败 -> 启动拒绝 + 位置报告（"spec.tools[2].ref 'order-qery'
      未注册，可用：[order-query, refund-search]"），不静默降级
    - Webhook 验签失败 -> 401 + 审计事件
    - HttpApiTool 超时 -> 工具错误结果（RetryPolicy 按配置），不炸 AgentLoop
    - DagSpec 导出时条件未注册 -> fail-fast 拒绝导出，不静默丢边
```

---

## 7. 模块结构

```text
agent-product/                                # 新增 Maven 模块
└── src/main/java/io/github/qwzhang01/agent/product/
    ├── ProductBootstrapper.java              # 装配入口（builder + startAll）
    ├── ProductContext.java                   # 名字 -> 实现注册表
    ├── AgentRegistry.java                    # 已启动 Agent 实例表
    ├── definition/                           # 6 类（M13.1）
    │   ├── AgentDefinition.java / AgentDefinitionParser.java
    │   ├── DefinitionValidator.java / ValidationError.java
    │   └── AgentDefinitionBinder.java / DefinitionException.java
    ├── template/                             # 2 类（M13.2）
    │   ├── AgentTemplate.java / TemplateRegistry.java
    ├── tools/                                # 2 类（M13.3）
    │   ├── HttpApiTool.java / HttpApiToolFactory.java
    ├── prompt/                               # 3 类（M13.4）
    │   ├── PromptManager.java / PromptVersion.java / PromptChannel.java
    ├── trigger/                              # 2 类（M13.5）
    │   ├── WebhookController.java / WebhookRoute.java
    ├── dag/                                  # 3 类（M13.5）
    │   ├── DagSpec.java / WorkflowDagCodec.java / ConditionRegistry.java
    └── tenant/                               # 1 类（M13.5）
        └── TenantAgentConfig.java
```

```text
examples/（新增 2 个）
├── DeclarativeAgentExample.java    # 验收：YAML -> 实例 -> HTTP 工具 -> Prompt 热切换 -> DAG 输出
└── WebhookExample.java             # 验收：内嵌 HttpServer -> 验签/幂等/202 -> 触发 Run
templates/（新增 2 个内置模板 YAML）
├── support-agent.yaml              # 客服模板
└── knowledge-assistant.yaml        # 知识助手模板
```

父 POM `<modules>` 增补 `agent-product`；不改动任何存量模块代码。

---

## 8. 实现里程碑（5 个，节奏对齐 Stage 11/12）

| # | 里程碑 | 交付 | 验证 |
|---|--------|------|------|
| M13.1 | 声明式定义层 | `AgentDefinition` / `Parser`（YAML+JSON）/ `Validator`（位置化错误）/ `Binder` / `ProductContext` + `ProductBootstrapper` 雏形 + 单测 | 合法定义绑定出可跑 Agent（人格+工具+记忆+model fallback 装饰器组装生效）；悬空引用 fail-fast 且错误含位置与可用列表；temperature 等参数透传正确 |
| M13.2 | 模板系统 | `AgentTemplate`（variables 声明+默认值）+ `TemplateRegistry`（内置客服/知识助手 2 模板）+ instantiate（fork 快照）+ 单测 | 模板+参数零 Java 出实例；模板升级后旧实例不受影响（D6 反证：改模板文件再 instantiate，旧实例行为不变）；必填参数缺失拒绝；${tenantId} 替换进 memory namespace |
| M13.3 | 配置驱动工具 | `HttpApiTool`（参数映射/jsonPath/鉴权 env 引用/超时）+ `HttpApiToolFactory` + 单测（JDK HttpServer mock） | YAML 声明的外部 API 被 Agent 成功调用（参数注入 query/body、响应提取）；超时映射工具错误不炸 loop；${env:} 解析（缺失拒绝加载）；治理管线自动接管（DENY 被拦+审计事件） |
| M13.4 | Prompt 管理 | `PromptManager`（publish/resolve/rollback）+ `PromptVersion`（不可变）+ Run 级 pin + 租户通道分流 + 单测 | publish 新版后进行中对话仍用旧版（pin 生效，RecordingModelClient 捕获实见 system prompt 证明）；新对话自动新版；rollback 指针切回；租户 stable/canary 分流互不影响 |
| M13.5 | 事件接入 + DAG + 多租户收口 | `WebhookController`（HMAC/幂等/202）+ `WebhookRoute` + `DagSpec` / `WorkflowDagCodec` / `ConditionRegistry` + `TenantAgentConfig` + ambient 段接线 + 2 验收示例 + README/笔记更新 | 验签失败 401；同 eventId 只跑一次；202 后异步完成 Run；Workflow->Dag->Workflow 往返图结构等价 + 未注册条件导出 fail-fast；租户 A 配置覆盖不影响租户 B；一份 YAML 定义频道 Agent+Ambient 指令并跑通；全仓存量测试零影响 |

依赖：M13.2/M13.3/M13.4 均依赖 M13.1（模板产出 Definition、tools.http/promptRef 是 spec 段）；M13.5 收口。M13.3 与 M13.4 可并行先行。

### M13.1 实现记录（2026-08-23）

新增 `agent-product` Maven 模块（父 POM 注册 + dependencyManagement；新依赖仅 `jackson-dataformat-yaml` 2.17.2，HTTP 层零新依赖），definition 包 + 根包共 11 类：

**根包 3 类**：
- `ProductContext`：名字 -> 实现注册表（models / tools / contextBuilders 三张表，LinkedHashMap 保注册序）。重名注册 IAE fail-fast——静默覆盖一个被在用定义引用的名字等于无公告变更线上 Agent 行为。`modelNames()/toolNames()/contextBuilderNames()` 供 Validator 错误消息列可用项
- `AgentRegistry`：已启动 Agent 实例表（register/get/list/size），重名 IAE（两个 Agent 一个名字会让 webhook 路由和审计变糊涂账）
- `ProductBootstrapper`：builder（model/tool/contextBuilder 注册）+ `startAll(Path)`。**all-or-nothing 语义**：phase 1 全目录 parse+validate 收集全部错误（跨文件聚合 + 跨文件重名检测），有错全仓拒启（半启动平台是调试噩梦）；phase 2 全干净才 bind+register。非 .yaml/.yml/.json 文件忽略；文件按名排序保证启动确定性

**definition 包 8 类**：
- `AgentDefinition`：record 树（apiVersion/kind 信封 + metadata + spec 四段 + 嵌套 Persona/Model/ToolRef/Memory）。信封值校验（v1/Agent，Kubernetes 风格）防未来 schema 版本静默误读；tools 列表防御性拷贝
- `AgentDefinitionParser`：YAML/JSON 双源单 mapper（YAML 是 JSON 超集，`YAMLFactory` 一个 ObjectMapper 通吃，测试实证 record 相等）。`FAIL_ON_UNKNOWN_PROPERTIES=true` 兜住拼写错误（`systemprompt` 不许静默通过）；后续里程碑字段（promptRef/http/longTerm/workflow/ambient/template）给定向提示（"planned for M13.4"而非裸 unknown-property）；语法错误带 line/column（D8 位置化）
- `DefinitionValidator`：结构校验（prompt 非空 / temperature∈[0,2] / memory 二选一互斥 / strategy 白名单）+ 引用校验（D1 落地：provider/fallback/tool ref/contextBuilder 逐个对 ProductContext 查名，错误消息列**可用清单**——"order-qery 未注册，available: [order-query, refund-search]"）。**一次全报**（业务方一轮修完，测试实证 6 错误同批返回）
- `ValidationError`：record(path, message)，path 是 YAML 内路径（如 `spec.tools[0].ref`）
- `DefinitionException`：两种形态——parse 失败（带 cause）或 validate 失败（携带全部 ValidationError，`getErrors()` 结构化供测试断言，不靠字符串匹配——Stage 12 纪律）
- `AgentDefinitionBinder`：D2 翻译器落地。model 段组装装饰链 `Temperature(Fallback(primary, fallbacks))`（temperature 包在 fallback 外层，主备调用都携带）；tools 段是**子集语义**（fresh InMemoryToolRegistry 只装声明的工具，不是全量表）；memory 段 shortTerm→WindowContextBuilder / contextBuilder→查名 / null→passthrough；防御性 orElseThrow IAE（未验证定义直接 bind 也 fail-fast）
- `TemperatureModelClient`：**存量零改动的关键发现**——`AgentConfig` 没有 temperature 槽位、`ReActAgentLoop` 从不设置 temperature（采样参数活在 ModelRequest 里）。不动 agent-core（组装阶段纪律），用装饰器在 ModelClient 边界注入默认值；显式设置的 request temperature 优先（默认值不是覆盖）
- `WindowContextBuilder`：**读时裁剪不改状态**（返回给模型的窗口裁剪，state 完整保留供 trace/审计——与 Stage 8 CompressingContextBuilder 的写时压缩契约刻意区分，测试专门断言 state 不被动）。命名诚实：YAML 字段叫 `maxMessages` 不叫 maxTurns（turn≈2 条消息，混淆烧过每个聊天产品）

测试 53 个全绿（Parser 11：YAML/JSON 等价 record 相等、信封校验、位置化语法错误、planned-field 提示、防御性拷贝 / Validator 12：全部规则 + 可用清单 + 一次全报 / Binder 10：**验收链**——RecordingModelClient 捕获模型实见消息证明 persona 注入与 temperature=0.3 透传、primary 抛异常 fallback 接管、temperature+fallback 链叠加透传、工具子集、contextBuilder 引用同实例、passthrough、防御性 bind / Window 7：system 保留/无 system/恰好窗口/读时不改 state/非法参数 / Bootstrapper 9：startAll 跑通两 Agent 并可 run、JSON 文件加载、all-or-nothing、跨文件错误聚合、跨文件重名拒启、空目录、非定义文件忽略 / Context 4）。RecordingModelClient 手法与 Stage 12 同款（MockModelClient 不暴露请求，在 ModelClient 边界截获）。

v1 诚实边界（javadoc/PLANNED_FIELDS 已写明）：schema 是 §3.2 全量蓝图的 M13.1 子集（persona.promptRef→M13.4、tools.http→M13.3、workflow/ambient/longTerm/template→M13.2/M13.5）；超出子集的字段 fail-fast 并提示所属里程碑，不静默忽略。maxSteps 未进 YAML（用 AgentConfig 默认 10，进 schema 待需要时再加，YAGNI）。

### M13.2 实现记录（2026-08-23）

template 包新增 4 类 + 2 个内置模板资源：

- `AgentTemplate`：**模板是数据不是代码**——spec 保留为 JsonNode 树，实例化是纯树变换（深拷贝 -> 递归替换 `${var}` 占位符 -> treeToValue 重水合为 AgentDefinition.Spec）。这个设计的直接收益：**定义层 schema 演进不需要改模板层**——未来新增的 spec 段（longTerm.namespace 等）从第一天起就能携带占位符。信封 kind: AgentTemplate 与 AgentDefinition 的 kind: Agent 天然隔离（模板文件误放 agents/ 目录会被 M13.1 信封校验拒绝）。**占位符声明制**：spec 树里所有 `${var}` 必须在 variables 里声明（`[tenantIID]` 这种 typo 在模板加载时就被拒绝，而不是静默留在 live prompt 里）；变量重名拒绝；未知字段拒绝（FAIL_ON_UNKNOWN 同款纪律）
- `VariableDecl`：name/required/default/description/type（type 仅文档用途，v1 无类型系统；`default` 经 @JsonProperty 映射）
- `TemplateMetadata`：name（registry key）/version（v1 仅记录，版本选择 v2）/description
- `TemplateRegistry`：register（重名 IAE——静默覆盖被在用模板等于无公告变更下一个实例）/ **replace 显式升级**（D6：只影响未来实例化，日志记录版本变化）/ loadDir（目录批量加载，装配期 fail-fast）/ builtins()（classpath 加载内置 2 模板）/ instantiate（查名 + 委托模板自身）
- 内置模板（resources/templates/）：`support-agent.yaml`（客服：订单查询 + 退款政策检索，变量 tenantId 必填 + brandName 默认"七七商城"）+ `knowledge-assistant.yaml`（知识助手：kb-search 检索问答 + "答不出就直说"，变量 tenantId + assistantName 默认"小知"）
- `ProductBootstrapper` 集成：Builder.templateDir(dir) + templates() 访问器；startAll 行为不变（agents 目录仍是定义目录，模板文件进那里会被信封校验拒绝并给出 kind 错误消息）

参数校验语义（instantiate）：required 且无 default 缺失 -> DefinitionException（path=`params.<name>`）；**多余参数键拒绝**（`params.extra`——防 typo 静默无效）；实例名必填。错误走 M13.1 的 DefinitionException/ValidationError 载体（结构化断言，不靠字符串）。

D6 fork 快照实证（测试）：instantiate 产出完整独立 record（值快照非指针）；模板 replace 升级后**旧实例内容不变**（before/after systemPrompt 对比）；同参两次实例化 spec 相等但实例独立。

测试 23 个新增全绿（AgentTemplateTest 14：解析纪律/占位符 typo 拒绝/替换+默认值/结构字段占位符（model.provider 也替换）/必填缺失/多余参数/blank 实例名/fork 反证/独立实例化/无变量模板 / TemplateRegistryTest 6：重名 IAE/replace 需先注册/**replace 升级只影响未来实例**/未知模板名列可用/builtins 加载+默认值应用/**验收链——builtins 模板 instantiate → M13.1 validate 零错误 → bind → agent.run 跑通 + 模板工具子集接线** / BootstrapperTest +3：templateDir 加载→实例化→bind→run 全链/坏模板炸构建/无 templateDir 空 registry）。

v1 诚实边界：`metadata.template`（模板派生 + 逐段覆盖的 deep-merge 语义）是 v2——M13.2 交付的是 instantiate 直接产定义路径，Parser 的 PLANNED_FIELDS 已同步指向 TemplateRegistry.instantiate 用法；版本选择（name@version 索引）v2；变量类型系统 v1 仅文档字段。

环境备忘（非本阶段代码问题）：08-22 晚间"审查 6 项修复"后未重新 `mvn install`，.m2 里 agent-workflow SNAPSHOT 是 12:45 旧 jar（缺 listPausedRunIds），单模块跑 agent-scheduler 会 NoSuchMethodError；`mvn clean install` 全仓刷新后 521 全绿。教训：改完代码全仓跑测试用 reactor（mvn test）没问题，但**单模块跑依赖 .m2 时长**——修库后记得 install。

### M13.3 实现记录（2026-08-23）

D3 落地：一个 YAML 声明的 REST API 变成普通 Tool，零 Java、治理免费搭车。tools 包新增 2 类 + schema 扩展：

- **schema 扩展**：`ToolRef` 从单 ref 字段升级为 `ref | http` 二选一（compact constructor 恰一非空校验；保留单参构造器向后兼容；`toolName()` 统一两类条目的名字空间）。新增 `HttpApiDecl`（definition 包）：name/description/endpoint/method（归一大写，默认 GET）/params（in: query|body|path，type 文档用，required）/response.extract（$.a.b 点路径）/auth（v1 仅 bearer）/timeoutSeconds（默认 10）。**schema 命名修正**：蓝图 YAML 示意的 `timeout: 3s` 实现为 `timeoutSeconds: 3`（整数秒，Jackson 直接映射免字符串解析）
- `HttpApiTool implements Tool`：**D3 的全部要义在"不特殊"**——注册进 ToolRegistry 即被 Stage 9 治理管线自动包装（同 Stage 10 McpToolAdapter 的透明性第二次兑现）。execute：必填参数校验 → 按 in 分流（query 拼 URL + URLEncoder / body 组 ObjectNode / path 替换 `{var}`）→ JDK HttpClient（connectTimeout 与 request timeout 同源，零新依赖，同 agent-model 选型）→ 2xx 提取（点路径 MissingNode 即失败——模型学到"形状不匹配"而不是读空字符串当数据）或原文。**错误契约对齐 Stage 2**：一切失败（缺参/非 2xx/超时/IO/提取失败）抛 ToolException → DefaultToolExecutor 转 `[ERROR] ...` 文本给模型自愈，绝不炸 AgentLoop。getParametersSchema 从 params 声明生成 JSON Schema（required 数组进 schema，模型看得见）
- `HttpApiToolFactory`：**密钥不落 YAML**——`auth.token: ${env:NAME}` 整串匹配解析（envLookup 函数注入，默认 System::getenv，测试可注入）；环境变量缺失 IAE fail-fast（"refusing to load the tool with an unresolved secret"，消息点名变量名）；字面量 token 放行但 javadoc 声明仅限测试/本地
- **Validator 扩展**：http 段全量规则（description 必填——模型读它决定何时调用 / endpoint http(s) 校验 / method 白名单 / in 白名单 / **body 参数不允许配 GET** / extract $. 前缀 / auth.type=bearer + token 非空 / timeoutSeconds 正数）；**ref 与 http 共享一个工具名字空间**（重名跨形态检测）
- **Binder 接线**：assembleToolRegistry 遇 http 条目走 factory.create（Binder 增构造注入工厂，测试可换 envLookup）；Parser PLANNED_FIELDS 移除 http 条目（已支持）

测试 25 个新增全绿（JDK HttpServer 真 mock）：HttpApiToolTest 11（GET query 注入+提取 / POST body JSON / path 占位替换 / Bearer 头注入 / schema 生成 / URL 编码 / **404→ToolException 含状态码 / 超时 1s 内 ToolException 且实测耗时<2.5s / 缺必参 / extract 路径不存在 / 服务器不可达**）+ HttpApiToolFactoryTest 5（env 解析 / **缺失拒绝加载点名变量** / 字面量放行 / 畸形引用按字面量 / null token 拒绝）+ HttpApiToolGovernanceTest 2（**验收：DENY 策略下 executor.execute 返回 [DENIED] 且 serverWasHit=false——拦截发生在 HTTP 请求之前**；InMemoryAuditLogger 收到 DENIED 事件含工具名；AUTO 放行+EXECUTED 审计）+ ValidatorTest +4（合法零错 / **跨形态重名** / 8 项结构错误一次全报（PATCH 下 body 合法——规则按 method 判定）/ GET+body 拒绝）+ BinderTest +1（YAML 定义带 http 工具 → bind → registry 按名持有）+ ParserTest +2（http 段全字段解析 / ref+http 同时出现拒绝）。pom 增 agent-security test scope（治理测试用）。

期间踩坑：JDK HttpClient 无 body 的 method 调用必须 `method(m, BodyPublishers.noBody())`（单参 method(String) 不存在）；测试断言 JSON 键顺序写反（mock 返回 data 在前）与 PATCH 下 body 合法导致的预期数偏差（9→8，规则本身正确）。

v1 诚实边界：仅 bearer 鉴权（API key header / OAuth v2）；${env:} 只支持整串引用（部分内插 v2）；extract 仅点路径（数组索引/过滤器 v2）；无重试策略声明（Stage 1 RetryModelClient 的思路可后续接到 tool 层）；OpenAPI 自动导入 v2（蓝图已列不做）。

### M13.4 实现记录（2026-08-23）

D4 落地：Prompt 从字符串常量变成可管理的资产。prompt 包新增 3 类 + schema 扩展：

- `PromptVersion`：不可变 record（name + **单调递增整数版本**（v1 弃用 semVer——prompt 没有兼容性契约可表达，诚实简化）+ content + channel + publishedAt）。publish 只追加不改写——**版本列表即审计轨迹**
- `PromptChannel`：stable/canary 双通道常量（百分比分流需 sticky session，v2）
- `PromptManager`：心智模型是**包管理器而非配置文件**。`publish`（追加 + 通道指针指向新版；canary 发布不动 stable）/ `resolve(name, tenantId, declaredChannel)`（路由点：**租户覆盖 > 定义声明通道 > stable**；蓝图双参签名演化为三参——定义声明的通道与租户灰度是两个独立输入）/ `rollback(name)`（stable 指针回退一步，**跳过 canary 版本**；存储内容永不改写）/ `setTenantChannel/clearTenantChannel`（单租户灰度开关，即 TenantAgentConfig.promptChannel 的 v1 形态，M13.5 收编）/ `history(name)`（不可变视图）。Clock 注入（确定性时间戳测试）
- **schema 扩展**：`Persona` 加 `promptRef` 组件（`{name, channel?}`，PromptRef record），与 systemPrompt **二选一**（Validator 校验：都有→互斥错误；都无→恰好一个错误；promptRef 引用存在性对 PromptManager 查——悬空时错误列可用 prompt 名；**平台没挂 PromptManager 但定义用了 promptRef → 错误**）。ProductContext 加 `withPromptManager`（单实例，二次挂 IAE）；Bootstrapper.Builder 加 `.promptManager(...)`；Parser PLANNED_FIELDS 移除 promptRef 条目
- `AgentDefinitionBinder.resolveSystemPrompt`：**D4 pin 的 v1 粒度 = bind 时刻（实例级）**——每次 bind resolve 一次，内容快照进 AgentConfig。蓝图"Run 级 pin"的工程化落地决策：SimpleAgent 无显式会话启动事件，把 per-run pin 塞进 AgentState 需要动 core；而产品形态"一对话一实例"（channel 层 SharedAgentSession 的映射）下**实例级 pin 等价于会话级 pin**，且与 D6 fork 快照同哲学（已 bind 实例的行为可预测性优先）。防御性：悬空 promptRef 直接 bind → IAE

测试 28 个新增全绿：PromptManagerTest 15（单调版本/append-only/canary 不动 stable/默认路由 stable/未知 prompt empty/通道无版本 empty/**租户覆盖赢过声明通道且其他租户不受影响**/清除覆盖回落/**rollback 回退一步且历史不动**/**rollback 跳过 canary 且 canary 指针不受影响**/最早版本回退拒绝/未知 prompt 回退拒绝/history 不可变/**rollback 后存储内容与 resolve 结果不同证**/promptNames）+ BinderTest +5（promptRef 内容注入实见 system 消息/**D4 验收：mid-flight publish 同实例两轮 run 实见仍 v1 + 新 bind 实见 v2**/**租户分流：acme bind 拿 canary / other bind 拿 stable 互不影响**/rollback 后下次 bind 拿回退版/悬空防御 IAE）+ ValidatorTest +5（promptRef 单独合法/互斥/悬空列可用名/无 PromptManager 拒绝/channel 非法）+ ParserTest +2（promptRef 解析/无 channel 为 null）+ BootstrapperTest +1（builder.promptManager + startAll 跑通 promptRef 定义）。存量适配 2 处（persona 校验错误 path 从 systemPrompt 收敛到 persona——二选一规则的必然变化）。

期间踩坑：`publish` 返回 PromptVersion 而非 this（值返回设计），测试里链式调用 `.publish().setTenantChannel()` 编译不过——拆开为多语句（这本身是对的设计：publish 是值语义操作，不是 builder）。

v1 诚实边界：pin 粒度是实例级（bind 时刻）——常驻 Agent 实例要拿新 prompt 需 rebind（产品层预期"一对话一实例"，且行为可预测性优先；per-run pin 需 AgentState 存版本，v2）；百分比分流/sticky session（v2）；PromptVersion 无 metadata 标签（author/变更说明，v2）；模板层（M13.2）与 promptRef 的组合未打通（模板 spec 里写 promptRef 是合法树替换，语义自然成立但未专门测试，M13.5 收口补）。

### M13.5 实现记录（2026-08-23）

Stage 13 收口：事件接入 + DAG 标准 + 多租户 + ambient 接线 + 2 验收示例。新增 dag/trigger/tenant 三包共 9 类 + schema 扩展 + Binder 增强：

**dag 包（D5）**：
- `DagSpec`：Workflow 的 JSON 投影（version/name/nodes+type/edges+条件名/errorEdges）。START/END 是哨兵只出现在边端点。toJson/fromJson 供前端消费/提交
- `ConditionRegistry`：name↔Predicate 双向注册表（IdentityHashMap——相等行为的两个 lambda 也是不同对象，注册按实例）。D1 模式第三次落地：DAG 存名字，注册表存实现
- `WorkflowDagCodec`：toDag（遍历 nodes + 全部出边/errorEdges，条件谓词反查名，**未注册谓词 fail-fast 拒绝导出**——静默丢条件分支等于无痕迹改路由）；fromDag（nodeResolver 按 id 供给节点实例 + 条件名查回 + WorkflowBuilder 重建）。**往返契约：拓扑/类型/条件名完整往返；节点行为经 resolver；RetryPolicy 不进 v1 spec**

**trigger 包（D8）**：
- `WebhookController`：传输无关（HTTP 壳在示例装配层）。**三件套**：HMAC-SHA256 验签（MessageDigest.isEqual 常量时间比较，失败 401 拒于 agent 之前）/ eventId 幂等（payload 顶层必带，重放答 DUPLICATE 不重跑；进程内 Set，持久化 v2）/ 202 语义（handle 快速返回，run 在 Executor 异步——慢 agent 不能把发送方超时变成双投递）。**不走 EventBroker**（蓝图预检结论兑现：fire 绑死 resume，Webhook 触发的是新任务，直连 Agent 入口）
- `WebhookRoute`：source→agent + payloadTemplate + **secret 必填**（v1 无匿名 webhook，fail-closed）
- `PayloadRenderer`：`{$.path}` 点路径模板（Webhook payload 与 ambient message 共用）；未知路径原样保留占位（loud miss）
- `WebhookResult`：状态 record（ACCEPTED/DUPLICATE/UNAUTHORIZED/UNKNOWN_SOURCE/NO_EVENT_ID/BAD_PAYLOAD/AGENT_NOT_FOUND），测试断言不碰 HTTP 字符串

**tenant 包（D7）**：`TenantAgentConfig`（promptChannel/model/disabledTools/serviceAccount）+ ProductContext 注册表 + **Binder bind 时覆盖**：model 换 primary（fallback 链保留）；disabledTools 收缩工具子集（**租户只能收缩不能扩张**）；promptChannel 作为 resolve 的声明通道（优先级：运营 setTenantChannel > 租户声明 > 定义声明 > stable）。运行隔离靠既有机制（MemoryScope namespace/ServiceAccount），Token 配额 Stage 18

**ambient 接线**：Spec 加 workflow + ambient 段。AmbientDecl（instructionId/description/trigger{onEvent|schedule PT10M}/importance{INFO,WARN,CRITICAL}/messageTemplate）——**condition 谓词留 Java 扩展点**（Stage 12 M12.4 遗留承诺兑现：v1 默认 condition=payload→true，蓝图"勿造 DSL"红线遵守）。`Binder.bindChannel(def, channelContext)`：复用 bind 全部组装（含租户覆盖）+ 派生 ServiceAccount（IdentityScope.capabilities("member") 与默认角色交集）+ 构造 AmbientInstruction 列表 → 返回 `ChannelBinding(session, ambient)`——**指令交给 AmbientEngine 仍是装配层的事**（Stage 12 模式：产品层生产，装配层组合）。Validator：workflow 引用存在性（悬空列可用名）+ ambient 全规则（trigger 恰一/Duration 可解析/importance 枚举/instructionId 去重）

**验收示例 2 个（examples）**：`DeclarativeAgentExample`（**实跑通过**：临时目录一份 YAML（promptRef+工具+memory+workflow 四段）→ startAll → run 返回业务回复 → toDag 输出 JSON）+ `WebhookExample`（内嵌 HttpServer + 三次投递演示：正常/重放/篡改）

测试 28 个新增全绿：WorkflowDagCodecTest 8（导出节点/边/条件名/**未注册谓词拒绝导出**/JSON 往返 record 相等/**重建工作流结构等价 + 条件边 matches 语义等价**（QUERY 走工具分支/其他走 END）/**重建条件边携带同一谓词实例**/fromDag 未知条件与未知节点拒绝/注册表重名重实例拒绝）+ WebhookControllerTest 11（有效签名 ACCEPTED+模板渲染进 agent 输入/**坏签名拒于 agent 之前 latch 实证**/缺签名 401/**同 eventId 重放 DUPLICATE 不重跑**/无 eventId 拒/未知 source/agent 未启动/坏 JSON/**真实 Executor 下 handle<150ms 且异步完成**/重复 route 拒/空 secret 拒）+ TenantOverlayBinderTest 5（model 覆盖换 primary 且其他租户不受影响/disabledTools 收缩且他人保留双工具/promptChannel 路由 canary 且他租户 stable/**运营覆盖仍赢过租户声明**/**YAML 定义频道 Agent+双 ambient 指令跑通**：mention 触发回复 + OnEvent/Scheduled 正确构造 + 模板对 payload 渲染）+ Parser/Validator 增补（workflow+ambient 解析/悬空 workflow 列可用/ambient 6 错误一次全报/合法 ambient 零错）。

期间踩坑：Agent 接口的两个 run 重载都是抽象方法（测试实现须双覆盖）；GraphRuntime 是 builder 风格构造（执行测试改为条件边 matches 语义验证，更轻更准）；SharedAgentSession 默认 IdentityScope.empty() 与默认角色空交集 → EMPTY_PERMISSION_INTERSECTION（改 capabilities("member") 与默认角色对齐）；agent 回复不进 channel history（Stage 12 契约，断言修正）。

v1 诚实边界：幂等去重是进程内 Set（重启丢失，持久化 v2）；仅 HMAC-SHA256 一种签名方案（时间戳防重放窗口 v2）；fromDag 的 nodeResolver 按名供给（v2 配合注册表完整编辑器故事）；TenantAgentConfig.serviceAccount 仅记录未接 IdentityResolver；bindChannel 的身份默认 member 交集（装配层可重建更严格 wiring）；RetryPolicy 不进 DagSpec。

---

## 9. 验收标准（对齐 18 周规划原文 4 条）

```text
1. 用一份 YAML 文件定义一个完整 Agent（含人格、工具、记忆策略）
   -> M13.1 + M13.5（DeclarativeAgentExample：§3.2 样例全段跑通）

2. 不写任何 Java 代码启动一个新 Agent 实例
   -> M13.2 + M13.5（新增 Agent = agents/ 目录放一个 YAML；
      平台 main 是一次性脚手架 ProductBootstrapper.startAll()，业务侧零 Java）

3. 通过 HTTP 配置注册一个外部 API 为 Tool
   -> M13.3（HttpApiTool + mock server 调用 + 治理接管）

4. Prompt 热切换不影响进行中的对话
   -> M13.4（Run 级 pin，进行中对话实见旧版本；新对话自动新版）
```

---

## 10. 测试策略

- **定义解析**：合法 YAML/JSON 双源 -> 同一 Definition；非法 YAML -> 位置化错误；悬空 ref（tools/model/workflow/prompt）-> fail-fast + 可用名列表
- **模板**：变量替换矩阵（必填/默认值/未声明变量拒绝）；模板升级旧实例不变（fork 反证）；同模板多实例互不干扰
- **HttpApiTool**：JDK HttpServer mock——参数注入（query/body/path）、jsonPath 提取、超时、非 2xx 映射、${env:} 缺失拒绝；治理包装生效（DENY 拦截 + 审计）
- **Prompt**：多版本共存；Run 级 pin（RecordingModelClient 捕获实见 system prompt 断言版本）；新对话拿新版；rollback；租户通道分流
- **Webhook**：验签失败 401 + 审计；同 eventId 重放只跑一次；handle 快速返回 + 异步完成；未知 source 404
- **DAG**：六节点混合图（agent/action/tool/router/approval/parallel）往返结构等价；条件经 ConditionRegistry 查回执行等价（同输入同输出）；未注册条件导出抛异常
- **多租户**：租户覆盖（channel/model/工具开关）只影响本租户；namespace 隔离（tenant-a 的记忆不进 tenant-b 检索）
- **向后兼容**：只新增模块，426 存量测试零影响

---

## 11. 文章规划（规划 8 篇 -> 优先 6 篇）

| 文章（规划原文） | 写作时机 | 素材来源 |
|---|---|---|
| 《从 Runtime 到产品：声明式 Agent 定义》 | M13.1 | §1 五个前提破裂 + D1 元模式（名字换实现） |
| 《Agent 模板系统：一键创建客服 / 知识助手 / 编码助手》 | M13.2 | D6 fork 快照 + 与 agent-arch-02 SKILL 的同源呼应 |
| 《配置驱动的 Tool 注册：HTTP API 不写 Java 就能变 Tool》 | M13.3 | D3 + 与 MCP 双路线对比（面试高频）+ 治理免费搭车 |
| 《Prompt 管理：版本化、A/B 测试、灰度与热切换》 | M13.4 | D4 Run 级 pin；与 agent-arch-06"十·补"两系列联动 |
| 《事件驱动 Agent：Webhook 和消息队列触发》 | M13.5 | D8 复用预检教训（EventBroker 绑死 resume）+ Webhook 三件套 |
| 《DAG 描述标准：让 Workflow 可视化编排成为可能》 | M13.5 | D5 序列化标准 + 条件注册表的诚实边界 |
| 备选：《多租户产品层》《前端集成 SDK 职责边界》 | 收口后按数据挑选 | D7 + 范围控制（SDK 只出职责边界文档） |

**系列衔接**：文章 1 承接 Stage 12 收尾叙事（从库到产品）；文章 4 与 agent-arch-06"十·补：Prompt 版本管理与灰度发布"形成"为什么（设计层）-> 怎么做（Java 实现）"联动；文章 6 与 Stage 5《为什么 Agent 框架需要 Graph Runtime》呼应。

---

## 12. 本阶段不做（范围控制）

- **可视化前端（React Flow 渲染器 / 拖拽编辑器）** —— 只输出 DagSpec 标准 JSON；前端消费方验证留产品化阶段
- **消息队列触发（Kafka / RabbitMQ 消费者）** —— v1 Webhook + 进程内事件；MQ 适配器是 WebhookController 后面的一层薄壳，v2
- **Prompt 百分比灰度（sticky session 分流）** —— v1 双通道 + 租户级指定；百分比需要会话粘性与统计口径，v2
- **Ambient 指令的 condition / message 函数声明化** —— v1 支持 trigger/importance/messageTemplate 的 YAML 声明，condition 谓词留 Java 扩展点（Stage 12 M12.4 诚实边界的部分兑现，勿造 DSL）
- **Webhook 路由的声明式配置（triggers 段进 YAML）** —— v1 WebhookRoute 装配层注册；进定义文件 v2
- **多租户运行隔离（独立 JVM / 资源隔离）与 Token 配额** —— v1 配置隔离 + 已有 scope/身份机制；TeamBudget/ChannelQuota 归 Stage 18
- **租户自助发布流程（审批 / 上线工作流）** —— v1 只做配置面；发布治理留产品化
- **前端集成 SDK（JS 包）** —— 只写职责边界文档（流式渲染 / 工具展示 / 审批交互分别对应 Runtime 哪些已有接口），不实现
- **OpenAPI / Swagger 自动导入** —— 手写 YAML 声明 HTTP API 已满足验收；importer v2
