# AI 角色引擎：组织与缺口

> 状态：📐 规划修订（2026-08-25）  
> 施工清单：[`todo-moonlit-memory-chat.md`](todo-moonlit-memory-chat.md)（已并入本蓝图分层）  
> **不**把 20 个概念做成 20 个 Maven 模块。引擎是一层组装，底下仍是现有 Runtime。

---

## 1. 三层，不是七套仓库

```text
产品     Moonlit：卖、管、呈现、提醒规则、动作解析、配额审核
角色引擎  「角色怎么活」——资产渲染 + 身份边界 + 记忆 + 对话编排
          （状态关系 / 评测按需挂挂钩）
Runtime  agent-core / model / security / mcp / observability
```

7 层是**能力分层**，用来查缺。落地时压成 **现有模块 + 少量挂钩 + 一个门面（后做）**。

| 能力层 | Maven 落点 | 不落成独立模块的原因 |
|--------|------------|----------------------|
| ① 角色资产 | `agent-chat` 的 `ChatPersona`；渲染器接口后加 | 人设是数据 + 一段 prompt，先别建卡库工程 |
| ② 身份一致性 | 复用 `MemoryScope` + `Room`；channel 身份后接线 | 防漂移是挂钩，不是新存储引擎 |
| ③ 记忆 | `agent-memory` | 已有 |
| ④ 对话编排 | `agent-chat` | 已有，心脏 |
| ⑤ 状态关系 | **挂钩**（ContextSource / Listener）；tavern 只管游戏 | 0–100 好感是玩法，不是引擎法律 |
| ⑥ 模型工具 | 现有 core/model/mcp；角色路由后做 | Runtime 已够 |
| ⑦ 评测观测 | 现有 observability/trace；角色用例后加 | 先有对话再评 |

`agent-tavern` = 游戏 Profile（回合 / 好感工具 / 事件）。**不是**角色引擎，也不并进 chat。

以后若门面太散，再加 `agent-character`：**只组装，不复制逻辑**。现在不要开。

---

## 2. 引擎 vs 产品（再钉一次）

| 引擎（通用，无业务词） | 产品（Moonlit） |
|------------------------|-----------------|
| 房间、选人策略接口、拼上下文、流式 | 会员、配额、审核、SSE 帧、小程序 |
| MemoryStore / Retriever / Extractor **接口** | 表、抽什么、prompt、用户「请记住」 |
| subject / dueAt 是字段，不解释含义 | 生日、11:30 外卖、三日未登录 |
| 关系/情绪 = 可选快照文本或数字槽 | 状态机 BROWSING→ESTABLISHED、亲密度公式 |
| Safety = hook | 过滤实现 |
| Eval = 可跑的角色用例接口 | 线上点踩、运营归因 |

---

## 3. 对照你列的 20 个模块：我们缺什么

### 已有（骨架够用）

| 标准模块 | 现在在哪 | 成熟度 |
|----------|----------|--------|
| Character Schema | `ChatPersona`（薄）、tavern `CharacterCard`（游戏向，勿混用） | 中 |
| Session / Room / Speaker / Assembler / Stream / Listener | `agent-chat` | 高 |
| Memory Store / Scope / Writer / Retriever / Compressor | `agent-memory`（Writer 仍是关键词类） | 中 |
| Identity / Scope 字符串 | `MemoryScope`、channel `IdentityResolver`（**未接到 ChatRoom**） | 中、未接线 |
| Model / Tool / MCP / 治理 / 预算 | core + model + security + mcp + observability | 高 |
| Trace / 通用 Eval | trace-export / observability | 中（非角色向） |
| 游戏向关系/世界/事件/存档 | `agent-tavern` | 高，但**不能当 Moonlit 引擎** |

### 真缺口（角色引擎要「像一个人」）

按「不做会不会让角色不像人」排序：

| 缺口 | 层 | 为何缺 | 组织方式 |
|------|----|--------|----------|
| **记忆写回 + 注入未接通** | ③④ | chat 已有可选 `MemorySource`；Moonlit 尚未挂上、几乎不写回 | ToDo T08–T16 |
| **生产持久化** | ③ | 只有 `InMemoryMemoryStore` | Moonlit `MemoryStore` 适配（T08–T09） |
| **LLM 抽取（指令业务给）** | ③ | 只有关键词 | T01–T02 + T12 |
| **人设渲染接口** | ① | `systemPrompt` 整段塞进 Persona；Moonlit `renderSystemPrompt` 仍是产品私货 | 引擎：`PersonaRenderer` 挂钩；实现在 Moonlit |
| **Room 上的身份键** | ② | Room 只有 roomId + members，没有 user/session scope 约定 | 给 Room/ChatRoom 可选 `scopes` 或业务 Factory 传入 MemorySource |
| **一致性挂钩** | ② | 无人设锚点校验、无 OOC | 接口 `ConsistencyGuard`，默认 no-op；实现可后做或产品做 |
| **Worldbook** | ① | 只有 `ExtraTextSource` 整段塞 | 通用 `LoreSource`（按关键词/触发器抽条目）；**格式/词库在产品** |
| **解耦的关系槽** | ⑤ | 关系在 Moonlit 业务或 tavern 游戏里 | 引擎只要 `RelationSnapshot` → Extra/System；公式在产品 |
| **群聊轮流 / 导演** | ④ | 只有 Solo / Mention | T06；LLM 导演更后 |
| **角色评测集** | ⑦ | Eval 不测「还记不记得 / 会不会串戏」 | 框架给 runner；用例在 `examples` 或 Moonlit 测 |
| **按角色/场景选模型** | ⑥ | 有 Router，无角色策略 | 产品配；引擎不立法 |
| **语义检索** | ③ | 无向量 | 后置；先 keyword + importance |
| **SillyTavern 卡导入** | ① | 无 | 产品或独立小工具，不进引擎核心 |
| **群聊并行发言** | ④ | 一轮一人 | 后置 |
| **TTS/ASR** | — | 无 | 产品层 |

**不是缺口、不要做的：**

- 把 tavern 关系矩阵搬进 chat  
- 引擎内置生日 / 外卖 Job  
- 为 20 个名字各建一个 artifact  
- 在接上记忆之前做漂移检测、向量世界书、角色 LLM-judge  

---

## 4. 怎么组织（Maven + 包）

```text
agent-core / agent-model / …     Runtime（不动角色语义）

agent-memory                     ③ 记忆引擎（一个模块；包按流水线）
                                 根包：Store / Entry / Query / Scope / Extractor / Retriever / Policy / Admin
                                 extract/ 写  store/ 存  context/ 读+压缩  session/ 会话  tools/ 模型自管
agent-chat                       ④ 对话心脏 + ① 薄 Persona + ContextSource
                                 ② scopes 由调用方传入
                                 ⑤ 只收 Snapshot 文本（Extra / 新 Source）

agent-tavern                     游戏 Profile，正交

（后置）agent-character          门面：PersonaRenderer + ChatRoom + Memory 组装
                                 依赖 chat + memory，零新玩法

Moonlit server                   产品：Store 适配、Renderer 实现、关系状态机、
                                 抽取 prompt、主动 Job、审核配额、SSE
```

`agent-chat` **不要** compile 依赖 channel / tavern / product。  
`MemorySource` 落地时 chat → memory，单向。

---

## 5. 和旧 ToDo 的关系

旧 T01–T20 = **角色引擎 MVP 的施工单**（记忆接通 + Moonlit 坐上对话心脏）。

缺的是「这张单在 7 层里的位置」和「MVP 之后引擎还缺哪几块挂钩」。

```text
Wave 1  像一个人：记得住、聊得上     = T01–T16（原清单）
Wave 2  引擎成形：渲染挂钩 + 身份键   = T21–T23
Wave 3  产品能力：主动说、群聊         = T17–T20、T06
Wave 4  完整引擎：lore / 关系槽 / 一致性 / 角色评测 = T24–T27
可选    dueAt、导演、向量、卡导入     = T04、T28+
```

一次仍只做一项。下一项代码仍是 **T01**。

---

## 6. 一句话

你要的是 **角色引擎**，不是再造一套 Runtime。  
agent4j 的 Runtime 和对话心脏已经够；**缺的是记忆闭环、人设渲染挂钩、身份/关系/一致性用挂钩而不是游戏法律、以及 Moonlit 只做产品和存储。**  
20 个模块名用来查表，落地按 4 波挂钩往现有 `agent-chat` + `agent-memory` 上挂。
