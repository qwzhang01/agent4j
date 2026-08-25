# ToDo：AI 角色引擎（agent-chat + memory × Moonlit）

> 状态：📋 逐项推进（2026-08-25）  
> 下一项：**T08**  
> 组织蓝图：[`architecture-character-engine.md`](architecture-character-engine.md)  
> 原则：**框架 = 角色怎么活（通用挂钩）；产品 = 怎么卖、抽什么、何时提醒。不写 birthday / 外卖进框架。不拆 20 个 Maven 模块。**

说「做 T0x」就只改那一项，做完再勾。

---

## 边界（先读再动手）

### 框架做（通用，无业务词）

- 房间：选人、拼上下文、流式、`ChatListener`
- 记忆：`MemoryStore` 读写、按 **scope / type / subject / keyword** 查询、抽取接口、压缩、召回注入
- `subject` 是**自由字符串**，框架不认识 `birthday`、`takeout`、`11:30`
- 调度模块若用，只提供「到点跑一段业务代码」，不内置任何提醒规则

### Moonlit 做（业务）

- 实现 `MemoryStore`（表在哪）
- **抽什么**：规则表、或 LLM prompt（生日、外卖、称呼、私密…由产品/模型决定）
- **何时主动说话**：自己的 Job 查 `MemoryQuery`（例如 `subject` 由业务自己约定），11:30 点外卖、生日问候都写在这里
- 过滤、配额、关系、SSE、推送、群聊主角文案

### 禁止

- 框架 Extractor / Job / 测试里写死 birthday、外卖、亲密度
- `agent-tavern` 当聊天引擎或「统一关系引擎」
- 为 7 层 × 20 模块各建一个 artifact
- Moonlit 继续自研一套抽取/压缩算法（应调用框架）
- `AiMemoryService`（背单词）当 Moonlit 记忆引擎
- 记忆未接通前做向量世界书、OOC 检测、角色 LLM-judge

---

## A. 框架 · agent-memory

### T01 · Extractor 改成可替换接口

- [x] 把现有 `MemoryExtractor` 抽成接口（`extract` / `extractAndStore`）
- [x] 原实现改名为 `KeywordMemoryExtractor`（通用关键词，不写业务 subject）
- [x] 旧测试改名后仍绿
- **模块：** `agent-memory`  
- **类：** 新建接口；改/改名 `MemoryExtractor` → `KeywordMemoryExtractor`；改 `MemoryPipelineTest` 等引用

### T02 · 通用 LLM 抽取器（subject 由模型填，框架不解释）

- [x] 新建 `LlmMemoryExtractor`：输入对话 + **业务传入的 system 指令**（或默认「抽结构化记忆，subject 自拟」）
- [x] 输出 `MemoryEntry`（type + subject + content）；框架不校验 subject 词表
- [x] Mock 测试：任意 subject 能落库、能 query
- **模块：** `agent-memory`  
- **类：** 新建 `LlmMemoryExtractor` + 测试

### T03 · 召回按 importance + limit（通用）

- [x] `MemoryRetriever.recallForContext` 支持按 `importance` 排序 + topN
- [x] 不写「用户编辑优先」——那是 Moonlit 适配器把 `isUserEdited` 映射成更高 importance
- **模块：** `agent-memory`  
- **类：** 改 `MemoryRetriever` + 测试

### T04 · （可选）通用到期字段，仍无业务语义

- [x] 若主动提醒需要「几点触发」：给 `MemoryEntry` 增加可选 `dueAt`（或约定业务写在 content/JSON）
- [x] `MemoryQuery` 增加可选时间窗过滤（due 在某区间）
- [x] **不**实现任何 cron、不写 birthday
- **模块：** `agent-memory`  
- **类：** 改 `MemoryEntry` / `MemoryQuery` / `InMemoryMemoryStore` + 测试  
- 不做也能靠 Moonlit 自己扫 content；本项可跳过

---

## B. 框架 · agent-chat

### T05 · `MemorySource`（通用召回注入）

- [x] 新建 `MemorySource`：用 `MemoryRetriever` + 业务传入的 **scope 列表** + limit
- [x] `ChatRoom.Builder` 可 `.source(new MemorySource(...))`，**不默认挂上**
- [x] 测试：第二轮请求里看得到召回文本；无 birthday 用例
- **模块：** `agent-chat`  
- **类：** 新建 `MemorySource`（依赖 `agent-memory`：本项才给 `agent-chat/pom.xml` 加 compile 依赖）+ `MemorySourceTest`

### T06 · `RoundRobinSpeaker`（通用轮流，无主角叙事）

- [x] 群聊轮流选人；叙事「你是世界主角」留给 Moonlit 的 ExtraText
- **模块：** `agent-chat`  
- **类：** 新建 `RoundRobinSpeaker` + 测试  
- 可在群聊接线前做

---

## C. 框架 · 文档（不改引擎行为）

### T07 · 蓝图与模块说明

- [x] `architecture-agent-chat.md`：MemorySource 标已规划/已做；写明抽取与提醒属业务
- [x] `docs/modules.md` / `CHANGELOG.md`：T05 落地后更新
- **模块：** `java-agent-framework` 文档

---

## D. Moonlit · 存储（业务只实现「存哪」）

### T08 · 表结构对齐 `MemoryEntry`（无业务枚举写死在框架）

- [ ] `moonlit_memories` 增加：`scope`、`subject`、`status`、`expire_at`、provenance；可选 `due_at`
- [ ] 旧数据回填 `scope = agent:{characterId}:{userId}`
- **模块：** `seven-ai-learn/server`  
- **文件：** `MoonlitMemory`、`database-schema-moonlit.sql`、迁移脚本

### T09 · `MoonlitMemoryStore` + Scope 工厂

- [ ] `implements MemoryStore`，7 方法对齐框架语义
- [ ] `MoonlitMemoryScope`：`user` / `pair` / `session` / `group`（字符串格式合法，见下）
- [ ] 单测：与 `InMemoryMemoryStore` 行为对照（可用 Testcontainers 或 H2）
- **模块：** `seven-ai-learn/server`  
- **类：** 新建 `MoonlitMemoryStore`、`MoonlitMemoryScope`；改 `MoonlitMemoryMapper`

Scope 约定（`MemoryScope.kind()` 只认已有 Kind）：

```text
user:{appId}:{userId}
agent:{characterId}:{userId}
session:{conversationFk}
channel:{groupRoomId}
```

### T10 · MemoryService 变薄

- [ ] `buildMemoryContext` → `MemoryRetriever`
- [ ] `addMemory` / `saveSummary` / 编辑删除 → Store
- [ ] 用户编辑：提高 importance + 业务标记 `isUserEdited`，Policy 侧由 Moonlit 决定是否禁止 AI 覆盖
- **模块：** Moonlit  
- **类：** 改 `MoonlitMemoryService` / `MoonlitMemoryServiceImpl` / Controller / VO（按需）

### T11 · 取消关系删记忆（已有方法未接线）

- [ ] `MoonlitUserCharacterRelationServiceImpl.cancel` 调用 `deleteAllByRelation`
- **类：** 改 Relation 实现

---

## E. Moonlit · 抽什么（业务，不进框架）

### T12 · 抽取策略（规则 和/或 LLM）

- [ ] 新建 `MoonlitMemoryExtractPolicy`（或 prompt 配置）：**Moonlit 决定**抽哪些类型、subject 约定（可动态、可交给 LLM 自拟）
- [ ] 组装 `KeywordMemoryExtractor` 或 `LlmMemoryExtractor`，**指令从这里传入**
- [ ] 例：外卖 11:30、生日——只作为 **Moonlit 配置/prompt 示例**，不写进 agent-memory
- **模块：** Moonlit  
- **类：** 新建 Policy/Prompt + `MoonlitMemoryConfig`（Bean：Store / Retriever / Extractor / Policy）

---

## F. Moonlit · 聊天接线

### T13 · 依赖与装配

- [ ] `server/pom.xml` 加 `agent-chat`、`agent-memory`
- [ ] `MoonlitChatRoomFactory`：1:1 默认 `SoloSpeaker` + Persona/History/Extra/MemorySource
- **类：** 新建 Factory；改 `pom.xml` / `application.yml`

### T14 · `MoonlitChatListener`

- [ ] `onReplied`：落库、parser、关系/亲密度、**调用业务 Extractor**、超窗则 `ContextCompressor`
- [ ] `onError` / `onNoSpeaker`：不写成功助手句 / 群聊提示
- **类：** 新建 `MoonlitChatListener`

### T15 · `MoonlitChatBizServiceImpl` 换 `ChatRoom.stream`

- [ ] 保留：过滤、配额、getOrCreate 会话
- [ ] 人设/场景/关系 → `ChatPersona` + `ExtraTextSource`；记忆 → `MemorySource`
- [ ] SSE：消费 `AgentEvent`；停用本路径的 `AiChatService`
- [ ] **不**调用 `AiMemoryService`
- **类：** 大改 `MoonlitChatBizServiceImpl`；改 `MoonlitCharacterServiceImpl.renderSystemPrompt`；小改 `SseUtil`

### T16 · SUMMARY 回写会话

- [ ] Compressor 产出写入 Store + `moonlit_chat_sessions.summary`
- **类：** 改 `MoonlitChatSessionServiceImpl`、Listener

---

## G. Moonlit · 主动说话（业务 Job，框架零规则）

### T17 · 通用「到期扫描」Job（规则在 Moonlit）

- [ ] `MoonlitProactiveScanJob`：定时跑；**查的条件、过滤、文案全在 Moonlit**
- [ ] 用 `MemoryStore.query(MemoryQuery)`（type/subject/时间窗由本 Job 拼）
- [ ] 生日、11:30 外卖、三日未登录 = **本 Job 或配置表里的多条规则**，可增删，不改框架
- [ ] `MoonlitProactiveChatService`：选角色 → Factory → 生成一句 → 落库 + 推送
- **模块：** Moonlit only  
- **类：** 新建 Job + Service；推送用现有或新建通知适配

---

## H. Moonlit · 群聊与主角感（业务规则）

### T18 · 群房间模型 + scope 政策

- [ ] 群房间 / 成员表（若无）
- [ ] `MoonlitGroupScopePolicy`：说话人召回 `user` + `channel` + **自己的** `agent:`；默认不读别人私密 scope
- [ ] ExtraText：主角叙事（框架不写）
- **类：** 新建实体/服务/Policy；改 Factory

### T19 · 群聊选人接线

- [ ] Factory：`MentionSpeaker` ± `RoundRobinSpeaker`（T06）
- [ ] Controller/Biz 支持群 `roomId`
- **类：** 改 Factory、ChatBiz、前端会话（若已有群 UI）

---

## I. 客户端（API 变了再动）

### T20 · Flutter 记忆 / 主动消息 / 群

- [ ] `memory.dart` / `memory_repository.dart`（VO 增 subject 才改）
- [ ] 主动消息、群聊入口
- **模块：** `c-end/moonlit`

---

## J. 引擎成形（Wave 2，T15 通了再做）

挂钩在 `agent-chat` / `agent-memory`，**先不要新建 `agent-character` 模块**。

### T21 · `PersonaRenderer` 挂钩（① 资产）

- [ ] 接口：结构化输入 → system 文本；引擎不写死占位符词表
- [ ] Moonlit 把现有 `renderSystemPrompt` 改成实现
- [ ] `ChatPersona.systemPrompt` 仍可直接用（无渲染器时）
- **模块：** `agent-chat` + Moonlit  
- **类：** 新建 `PersonaRenderer`；改 `MoonlitCharacterServiceImpl`

### T22 · Room / Factory 带身份 scope（②）

- [ ] `ChatRoom` 或 Factory 能带上 user / session / pair 的 scope 列表，交给 `MemorySource`
- [ ] 不引入 channel 模块依赖；字符串即可
- **模块：** `agent-chat`  
- **类：** 改 `ChatRoom.Builder` 或新建小 record `RoomIdentity`

### T23 · （可选）`agent-character` 门面模块

- [ ] 仅当 T15+T21+T22 用起来散了：组装 Renderer + Room + Memory，零新玩法
- [ ] Starter 仍不自动依赖
- **默认跳过**

---

## K. 完整引擎挂钩（Wave 4，群聊和主动说之后）

### T24 · `LoreSource`（① 世界书，通用触发）

- [ ] `ContextSource`：业务传入条目 + 触发器（关键词/正则）；命中则注入
- [ ] 词库、SillyTavern 卡格式在产品或导入工具
- **模块：** `agent-chat`  
- **类：** 新建 `LoreSource`

### T25 · `RelationSnapshot` 源（⑤ 解耦关系，不是 tavern）

- [ ] 业务每轮给一段/一个结构（阶段、数字槽）；引擎只注入，不算分
- [ ] Moonlit 状态机仍在产品
- **模块：** `agent-chat`  
- **类：** 新建 `RelationSource` 或约定走 `ExtraTextSource`（若够用则本项取消）

### T26 · `ConsistencyGuard` 挂钩（② 防漂）

- [ ] 接口：人设锚点 + 本轮回复 → OK / 告警；默认 no-op
- [ ] 实现（规则或 LLM）可后做，**不**内置 Moonlit 人设
- **模块：** `agent-chat`  
- **类：** 新建接口；`ChatEngine` 在 Done 后可选调用

### T27 · 角色向 eval 用例（⑦）

- [ ] 固定剧本：跨轮是否召回 subject、群聊是否串 scope、无人设改写
- [ ] 用 Mock，不绑真 LLM-as-judge
- **模块：** `agent-chat` 或 `examples`  
- **类：** 新建测试 / `CharacterEvalExample`

### T28 · LLM 导演选人（④ 后置）

- [ ] `SpeakerPolicy` 实现：模型选谁说话；prompt 由业务给
- [ ] 一轮一人仍是默认
- **默认后置**

---

## 推进顺序（一次只做一项）

```text
Wave 1  记得住、聊得上（角色引擎 MVP）
  T01 → T02 → T03 → T05 → T07
  T08 → T09 → T10 → T11 → T12
  T13 → T14 → T15 → T16

Wave 2  引擎成形
  T21 → T22
  T23 仅当组装太散

Wave 3  产品能力
  T17
  T06 → T18 → T19 → T20

Wave 4  完整引擎挂钩
  T24 → T25 → T26 → T27
  T04、T28 可选
```

`T06` 可在 Wave 1 中段穿插，但 T19 前必须完成。

---

## 对照：以前错在哪

| 错 | 对 |
|----|----|
| 框架 Job 写死 birthday | 框架只提供 query；Job 在 Moonlit，规则可配置 |
| Extractor 认识「生日」 | Extractor 只产出 type/subject/content；词表在 Moonlit prompt |
| 11:30 外卖进框架 | 同一条 Moonlit 规则引擎，多加一条规则即可 |
