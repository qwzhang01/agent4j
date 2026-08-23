# Stage 13 概念（产品层：声明式 Agent 定义与翻译器架构）

> 对应阶段：Stage 13 - 上层产品搭建层（学习规划第 8 篇：《从 Runtime 到产品：声明式 Agent 定义》的底稿）
> 定位：总纲概念 -> 灵魂概念（D1）-> 三道流水线 -> 五个能力域 -> 心智模型（与 Stage 12 笔记同体系）
> 配套：架构设计见 [architecture-stage-13.md](architecture-stage-13.md)，源码可读 `agent-product` 模块（30 类 + 2 内置模板 + 2 验收示例）
> 状态：已实现 + 外部评审修复（2026-08-23）。product 167 测试全绿
> 本笔记聚焦「概念」，代码细节以源码为准

---

## 一、定位：产品层是翻译器，不是第二套 Runtime

**一句话：agent-product 消费一份 YAML 配置，产出 agent-core 里现成的 `Agent`。**

它不跑推理、不执行工具、不做调度——那些全是 `agent-core` / `agent-workflow` / `agent-channel` 的事。产品层只做一件事：**把「业务方想定义一个 Agent」翻译成「代码世界里的 Agent 对象」**。整个模块的红线由此而来：**别造第二套引擎**（架构决策 D2）。

递进叙事（接 Stage 1-12 笔记体系）：

```
Stage 6  让 Run 能暂停-恢复
Stage 7  让 Run 能自动恢复
Stage 8  让 Agent 能记住
Stage 9  让 Agent 能被信任
Stage 10 让 Agent 能连接
Stage 11 让 Agent 能协作
Stage 12 让 Agent 能入驻团队
Stage 13 让 Agent 能被"搭"出来 -- 不写 Java，放一份 YAML 就上线
```

类比：agent-core 是发动机，agent-product 是仪表台。仪表台不产生动力，它把驾驶员的意图翻译给发动机。仪表台坏了发动机还能跑，但「不懂车的人也能开车」这件事就没了。

---

## 二、核心问题：Agent 的产品化难在哪

底层框架里定义一个 Agent 是这样的：

```java
new SimpleAgent(new AgentConfig(
    "support-bot", "你是客服...", modelClient, toolRegistry, 10, contextBuilder));
```

对业务方（运营 / 产品 / 另一个团队）这是灾难：

| 痛点 | 后果 |
|---|---|
| 要会 Java，要编译，要部署 | 改一版客服话术 = 一次发版 |
| 人格 / 工具 / 模型散落在代码各处 | 不可审计、不可版本化、换模型要改代码 |
| 每个 Agent 都是手工 new 出来的 | 加一个 Agent 的人力成本没有下限 |

**Stage 13 的回答：把「定义」从 Java 代码里拿出来，变成一份 YAML 文件。**

```yaml
spec:
  persona: { systemPrompt: "你是客服..." }
  model:   { provider: openai }        # 名字，指向注册表
  tools:   [ { ref: order-query } ]    # 名字，指向注册表
  memory:  { shortTerm: { strategy: window, maxMessages: 10 } }
```

加一个 Agent = 放一个文件。这就是「从 Runtime 升级为产品」的字面意思。

---

## 三、灵魂概念：定义存名字，注册表存实现（D1）

agent-product 最重要的概念，整个模块围绕它转了四次。**YAML 里只有名字，没有实现；实现（Java 对象）住在注册表里，由装配方（写 Java 的平台方）提供。**

### 四次落地

| YAML 里的名字 | 注册表里的实现 | 注册表位置 |
|---|---|---|
| `model.provider: "openai"` | `ModelClient` | `ProductContext` |
| `tools.ref: "order-query"` | `Tool` | `ProductContext` |
| `persona.promptRef.name: "support-system"` | `PromptVersion`（版本化 prompt） | `PromptManager` |
| `tenant.serviceAccount: "ops-identity"` | `ServiceAccount`（admin 预置） | `ProductContext` |

### 为什么这么设计（三个理由）

1. **YAML 保持非图灵完备**——配置里没有 Java 类、没有函数、没有逻辑，只有数据。配置不可能写坏，只能写错（名字不存在）。这是「声明式」和「脚本化」的本质区别。
2. **运行时可换实现**——「同名换实现」：把注册表里 `openai` 对应的 `ModelClient` 换掉，所有引用它的 Agent 立刻换模型，配置文件一个字不用改。
3. **可以提前校验**——名字不存在 = 配置错，启动时 fail-fast 报出来，而不是运行到一半才发现。

**心智锚点：看到 YAML 里的任何字符串，先问「这个名字对应注册表里的什么？」**

---

## 四、三道流水线：parse → validate → bind

每个 YAML 文件过三道闸（`definition` 包），这是「翻译器」的完整工作流程：

```
YAML ──parse──▶ AgentDefinition（对象）──validate──▶（检查通过）──bind──▶ Agent
```

| 闸 | 做什么 | 怎么拦坏人 |
|---|---|---|
| **parse** | YAML → `AgentDefinition` 对象（纯数据） | 不认识的字段 → 直接拒绝（`FAIL_ON_UNKNOWN_PROPERTIES`）；v2 字段（如 `longTerm` / `template`）→ 定向提示「这是 v2，请用 XX」 |
| **validate** | 逐个检查 YAML 里的名字在注册表里存在 | `tools.ref` 未注册 → 报错并列出可用名字；`promptRef` 声明通道无版本 → 报错（与 Binder 同路由，2026-08-23 修复） |
| **bind** | 把 Definition 翻译成 `SimpleAgent`（**翻译，不生成代码**） | 翻译途中发现悬空引用 → 抛异常并列出可用名字 |

`ProductBootstrapper.startAll()` 是流水线编排者：扫目录里所有 YAML，全部走完三道闸。**任何一份失败，全部不起**（all-or-nothing）——半套系统上线比不上下线。

设计要点：
- `validate` 返回**聚合的错误列表**（一份文件 5 个错一次性全报），面向「写配置的人」的 UX 设计
- `bind` 走 `AgentConfig → SimpleAgent` 现有构造路径，零代码生成（D2「翻译器不是生成器」）
- startAll 对声明 `spec.ambient` 的定义打 WARN：ambient 需要频道会话（`bindChannel`），startAll 没有——**不静默忽略**（2026-08-23 修复）

---

## 五、五个能力域（包结构）

产品层不止「翻译」，还顺带解决了五个产品化问题：

| 包 | 解决的产品问题 | 核心概念 |
|---|---|---|
| `definition` | 怎么定义 Agent | parse / validate / bind 流水线 |
| `template` | 同一种 Agent 反复建，太麻烦 | **模板 = 骨架 + 变量**：`instantiate(客服模板, 品牌名)` → 产出一份完整定义（fork 快照，改模板不影响已建实例） |
| `tools` | 接一个外部 HTTP API 也要写 Java Tool？ | **配置驱动注册**：YAML 声明 `endpoint + 参数 + 鉴权`，`HttpApiTool` 自动变成一个 Tool（密钥走 `${env:XXX}` 不落盘） |
| `prompt` | 客服话术想版本化、A/B、热切换 | **Prompt 资产化**：`publish(版本) → 指向 stable/canary 通道 → resolve(按通道取版本)`；历史只追加不覆盖，rollback 只动指针 |
| `trigger` + `dag` + `tenant` | 怎么接入外部系统 / 可视化 / 多租户 | Webhook 触发（验签 + 幂等 + 202）；Workflow → JSON DAG 供前端渲染；租户覆盖（每租户换模型 / 工具开关 / prompt 通道） |

---

## 六、三个心智模型

1. **翻译器不造引擎**——在这个模块里看不到推理循环、工具执行器，它们属于别的层。看到 agent-product 在 `new SimpleAgent(...)` 就对了。
2. **配置里只有名字，实现在注册表**——看到 YAML 字符串，去找它对应的注册表。四个注册域：model / tool / prompt / serviceAccount。
3. **诚实边界优先**——v1 做不了的明确说 v2（`longTerm`、治理自动接线、Run 级 pin、图执行）；配置被**静默忽略**是这个模块最不能接受的事（ambient 段已修成启动即警告）。

---

## 七、自检（面试向）

1. **为什么 YAML 里不允许出现 Java 逻辑？**
   答：非图灵完备 + 名字指向注册表——配置只描述不执行。允许配置里带逻辑就会重蹈「配置文件长成编程语言」的覆辙（YAML 陷阱）。
2. **`startAll` 起的 Agent 和手写 `new SimpleAgent()` 有什么区别？**
   答：差异在流程纪律，不在 Agent 本身——startAll 多了「校验 + 翻译」流水线且 all-or-nothing；产出物同样是 `SimpleAgent`（翻译器不是生成器）。
3. **运营发布新 prompt 版本，进行中的对话会怎样？**
   答：不受影响——prompt 在 bind 时刻快照进实例（实例级 pin）。但常驻实例的新对话也拿不到新版，要 rebind。蓝图承诺的「新对话自动新版」只在「每会话 bind 新实例」的形态下成立，startAll 主路径是部署级 pin（评审修正，Run 级 pin 为 v2）。
4. **怎么给 Agent 接一个外部 HTTP API 当工具？**
   答：不用写 Java——YAML 里声明 `endpoint / method / 参数 / 鉴权(env) / 超时`，`HttpApiToolFactory` 生成 Tool 注册进注册表。治理由装配层显式配置生效（评审修正：非「注册即自动」）。

---

## 八、代码阅读路线图

建议顺序（从「数据」到「流程」，每步只引入一个新概念）：

```
AgentDefinition            YAML 长什么样（纯数据，无逻辑）
  → AgentDefinitionParser  怎么读进对象（未知字段拒绝 / v2 提示）
  → DefinitionValidator    怎么查名字（聚合错误 / 路由与 Binder 一致）
  → ProductContext         注册表里有什么（model/tool/prompt/serviceAccount）
  → AgentDefinitionBinder  怎么翻译成 Agent（D1 四次落地的消费方）
  → ProductBootstrapper    怎么编排三道闸（startAll / all-or-nothing / ambient WARN）
```

按能力域补读：`template/TemplateRegistry`（模板实例化）、`tools/HttpApiToolFactory`（配置驱动 Tool）、`prompt/PromptManager`（版本/通道/热切换）、`trigger/WebhookController`（D8 三件套）、`dag/WorkflowDagCodec`（DAG 导出）、`tenant/TenantAgentConfig`（租户覆盖）。

---

## 九、能力域速览（怎么用，一句话 + 最小声明）

| 包 | 解决什么 | 关键机制 |
|---|---|---|
| `tools`（HttpApiTool） | 不写 Java 把 REST API 变 Tool | YAML 声明 `endpoint + params(in: query/body/path) + response.extract + auth + timeout`；`getParametersSchema()` 从声明生成模型可见的 Schema；密钥走 `${env:NAME}`，build 时解析、缺失拒加载（fail-fast） |
| `trigger`（WebhookController） | 外部系统触发 Agent | D8 三件套：HMAC 验签（拒于 Agent 之前）/ eventId 幂等（重放答 DUPLICATE）/ 202 语义（快返回 + 异步 run）。幂等键「受理才占用」：Agent 缺失 / 入队拒绝时释放，执行失败保留（at-least-once） |
| `dag`（WorkflowDagCodec） | Workflow ⇄ JSON 给前端画图 | 条件谓词（lambda）不可序列化，经 ConditionRegistry 按名引用；未注册谓词拒绝导出（D5，防静默丢条件分支） |
| `tenant`（TenantAgentConfig） | 多租户覆盖 | 优先级 运营 > 租户 > 定义 > 默认；disabledTools 只收缩不扩张；v1 是「配置隔离」非「运行隔离」（记忆隔离 v2） |

## 十、方法论与边界（这次学习最重要的两条）

### 1. 消费方方法论：判断一个字段有没有影响

```
① grep 字段名 → 出现在哪些文件
② 排除噪音：record 里「存它的那行」（数据结构）、javadoc（注释）
③ 看剩下的调用是不是「消费方」= 读它的值去做决策
④ 有消费方 → 有影响；没有 → 死字段（对行为无影响）
   最硬验证：删掉它跑一遍，看行为变不变
```

- 经典对照：`workflow` 字段 → Binder 里 0 次出现 → **死字段**（对 run 无影响）；`temperature` 字段 → Binder 的 assembleModelClient 读了它 → **有消费方**（影响采样温度）。
- 关键心智：**靠查消费方判断，不靠字段名像不像。**「workflow 听起来会执行」是陷阱。

### 2. 模块边界标尺：两个模块是不是重复

问三句：**时间**（启动时 / 运行时 / 入口）？**单数还是复数**（一个 Agent 的定义 / 多个 Agent 的协作）？**要不要聚合**（分完就完事 / 收回来合并）？

- `agent-product`（Stage 13）= 定义层：YAML → Agent，启动时，管「一个 Agent 怎么来」
- `agent-orchestrator`（Stage 11）= 编排层：supervisor 并行分发任务给 workers 再聚合，运行时，管「多个 Agent 怎么协作」
- 入口路由（agent-product 不负责）= 请求级单发：1 请求 → 1 Agent，无聚合

### 3. 四层框架（agent-product 覆盖 2、3 层）

```
第 0 层 入口/路由    用户消息从哪来、发给哪个 Agent   ← 不在 agent-product 范围
第 1 层 运行时       ReAct 循环                        ← agent-core
第 2 层 翻译         YAML → Agent                      ← agent-product 核心
第 3 层 周边         模板 / prompt / DAG / Webhook      ← agent-product 附加
```

产品层生命周期 = 启动那一刻：翻译完成、Agent 进 AgentRegistry，之后 run 由 agent-core 接管，产品层退场。
