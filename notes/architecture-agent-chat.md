# agent-chat 架构设计：房间对话引擎

> 状态：✅ M1–M5 已落地（2026-08-25）—— 31 测试全绿；Moonlit 接线另开任务
> 模块：`agent-chat` Maven 模块
> 依赖：compile `agent-core`；`agent-memory` 仅作可选上下文源（可后加）；`agent-model` test scope
> **不依赖** `agent-tavern` / workflow / scheduler / channel / product / enterprise / security
> 第一消费者：Moonlit（SillyTavern 一类：人对人、人对群的角色聊天）
> 对照：`agent-tavern` 是回合制游戏 Profile，保留不动。本模块不改 tavern 一行。

---

## 1. 要解决什么

SillyTavern / Moonlit 要的是：**一间屋里，下一句谁说、上下文怎么拼、字怎么往外流。**

一对一 = 屋里一个 AI。群聊 = 屋里多个 AI。同一套引擎。

`agent-tavern` 解决的是另一件事：必须 `@`、世界回合 +1、好感 0–100、事件再拉一个人说话。那是游戏包，不是聊天底板。

本模块把玩法从法律改成挂钩。角色、世界书、关系、记忆怎么变，由业务定义。

---

## 2. 引擎一次调用

```text
Room + 用户这一句
  → SpeakerPolicy 选出说话的人
  → ContextAssembler 拼上下文
  → Agent.stream 往外吐字
  → 把这句话写进房间历史
  → ChatListener 通知业务（记记忆 / 改关系 / 落库，引擎不管）
```

没有：强制 `@`、强制回合 +1、强制挂游戏工具、给人设套英文游戏规则。

---

## 3. 和现有模块怎么摆

| 留下当零件 | 不要当本模块引擎 |
|---|---|
| `Agent` / `Agent.stream` / `AgentEvent` | `TurnEngine.playTurn` |
| `ChatMessage` / `AgentState` | `CharacterAgentFactory.personaPrompt` 游戏规则文案 |
| `MemoryStore` 接口（后加） | 每次必挂的好感 / 世界 / 事件工具 |
| `agent-spring-boot-starter` 只装配模型 | `TavernGame.playerSay` |

Starter **不**自动依赖 `agent-chat`。Moonlit 要房间引擎时，自己加 `agent-chat`。

`CharacterCard` 先不复用 tavern 包（避免游戏语义漏进来）。本模块自带更薄的 `ChatPersona`。以后若抽公共「人设数据」到 core，再合并。

---

## 4. 核心类型

### 4.1 数据（业务填）

```text
ChatPersona
  personaId / displayName / systemPrompt / greeting
  systemPrompt 原文注入，引擎不改写

RoomMessage
  speakerId（"user" 或 personaId）/ role / content / at

Room
  roomId
  members: List<ChatPersona>     至少一个
  history: List<RoomMessage>     按时间
  不内置 turnCount / location / flags
```

一对一：`members` 长度为 1。  
群聊：`members` 长度 > 1。

### 4.2 挂钩（业务换）

```text
SpeakerPolicy
  pick(room, userText) → Optional<ChatPersona>
  空 = 本轮无人说（群聊里没点到人，由业务决定是否提示）

内置三种，业务也可自己写：
  SoloSpeaker          屋里只有一个 AI → 就是他；多于一个则拒绝（防误用）
  MentionSpeaker       第一个 @id 命中则他；可叠加 fallback（如 Solo）
  RoundRobinSpeaker    群聊轮流（v1 后做，未做）

ContextSource
  contribute(room, speaker, userText) → 若干 ChatMessage 或一段文本
  引擎按注册顺序拼接，不解释内容

内置：
  PersonaSource        说话人的 systemPrompt
  HistorySource        房间最近 N 条（默认 20）
  MemorySource         可选；走 MemoryStore + 业务给的 scope 白名单（v1.1，未做）
  ExtraTextSource      业务塞场景氛围 / 世界书 / 关系说明（一段字符串）

ContextAssembler
  按 Source 列表拼 Model 用的消息
  压缩不在本模块立法：HistorySource 可接 ContextCompressor，默认可关
```

### 4.3 引擎

```text
ChatEngine
  持有：Room、SpeakerPolicy、ContextAssembler、ModelClient、ChatListener[]

  stream(userText, listener: Consumer<AgentEvent>)
    1. 把 user 句写入 history
    2. policy.pick；空则回调 onNoSpeaker，结束
    3. assembler.build → 为该 persona 建一次 SimpleAgent（或缓存）
       systemPrompt = persona.systemPrompt，maxSteps 默认 1（纯聊天）
    4. agent.stream(...)，把 ContentDelta / Done / Error 转给调用方
    5. Done 后把 assistant 句写入 history
    6. 通知 ChatListener.onReplied(room, speaker, userText, reply)

  say(userText) → 完整回复字符串（测试用，内部走 stream 收齐）
```

`maxSteps` 默认 1。业务若要角色中途调工具（例如自己记一条记忆），创建引擎时把 maxSteps 调大，并自己往 Agent 上挂 Tool。引擎默认不挂任何工具。

### 4.4 通知（不是世界结算）

```text
ChatListener
  onReplied(...)
  onNoSpeaker(...)
  onError(...)
```

引擎不改亲密度、不写 MySQL、不解析动作台词。Moonlit 在 listener 里做。

---

## 5. 明确不做（v1）

| 不做 | 原因 |
|---|---|
| 回合 / WorldState / EventRule | 那是 tavern |
| 好感 0–100、档位枚举 | 关系是业务 |
| 存档文件 / 回放 | 聊天历史由业务库管 |
| 必须 `@` | 一对一不能靠点名 |
| 改写人设 | 人设是业务资产 |
| Spring | 装配走现有 starter；本模块无 Spring |
| 向量世界书 | ExtraTextSource 先够；检索留给业务或 memory |

---

## 6. 里程碑

| 里程碑 | 做什么 | 验收（必须有测试） |
|---|---|---|
| **M1 房间 + 单人流式** ✅ | `ChatPersona` / `Room` / `SoloSpeaker` / `ChatEngine.stream` | 屋里 1 人，不打 `@` 也能流式出字；`run()` 行为不变 |
| **M2 上下文** ✅ | `PersonaSource` + `HistorySource` + `ExtraTextSource` | 第二轮请求里看得到第一轮历史；Extra 原文出现在 system 或紧随其后 |
| **M3 选人** ✅ | `MentionSpeaker`；可与 Solo 组合（先 @，没有则 Solo） | 两人屋：`@b` 只有 b 回；不 @ 且无 fallback → `onNoSpeaker` |
| **M4 通知** ✅ | `ChatListener` | 回复完成后 listener 恰一次；出错不写成功历史 |
| **M5 门面 + 示例** ✅ | `ChatRoom` builder（model + personas + policy + sources）；`ChatRoomExample` | Mock 零 LLM：一对一流式 + 两人点名 |

每里程碑零改 tavern / core 行为。core 已有 `stream`，本模块只调用。

父 POM / BOM 在 M1 注册模块。`examples` 在 M5 加依赖。

---

## 7. 目录（开工时）

```text
agent-chat/
  pom.xml
  src/main/java/io/github/qwzhang01/agent/chat/
    ChatPersona.java
    Room.java
    RoomMessage.java
    ChatEngine.java
    ChatRoom.java              # M5 门面
    ChatListener.java
    speaker/
      SpeakerPolicy.java
      SoloSpeaker.java
      MentionSpeaker.java
    context/
      ContextSource.java
      ContextAssembler.java
      PersonaSource.java
      HistorySource.java
      ExtraTextSource.java
      MemorySource.java        # v1.1，未做
```

---

## 8. Moonlit 怎么坐上来（本模块做完之后，另开任务）

Moonlit 继续管：过滤、配额、会员、关系状态机、`common_ai_messages`、SSE、动作/台词解析。

换模型层时：

1. 一个「用户 × 角色」会话 = 一个 `Room`（members 1 人）
2. `systemPrompt` 仍用现在的 `renderSystemPrompt`（场景 + 记忆 + 关系），经 `ChatPersona` 或 `ExtraTextSource` 注入
3. `ChatEngine.stream` 接到现在的 `SseEmitter`
4. `onReplied` 里走现在的 `afterStreamComplete`

群聊以后：同一 `Room` 里加成员，政策换成 `MentionSpeaker` + 默认策略。不必换引擎。

记忆压缩 / 抽取：业务在 listener 里调 `agent-memory`，或挂 `MemorySource`。存储仍是 Moonlit 的表（自己写 `MemoryStore` 适配器）。

---

## 9. 开工顺序建议

1. 本文件评审，不改范围。
2. Moonlit P0 安全审核优先（控制塔本周约束）。
3. 审核过后再开 M1。不要和 P0、kidsgame G1 抢人手。
4. M1–M4 在框架内闭环（Mock + 测试）。M5 后再动 Moonlit 聊天代码。

---

## 10. 一句话

`agent-chat` 是可配置的房间对话机。  
`agent-tavern` 是写死玩法的一桌跑团。  
Moonlit 和 SillyTavern 一类，坐在 chat 上，不坐在 tavern 上。
