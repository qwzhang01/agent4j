# 决策 14：记忆抽象为结构化 MemoryEntry，而不是原始 ChatMessage

> 对应《agent4j 架构立场》骨架的决策 14。
> 一句话：**结构化 vs 原始，本质是"治理 vs 回放"的取舍——长期记忆要治理，所以结构化；原始消息要回放，所以另层保留。**

## 一、决策在说什么

`MemoryEntry` javadoc：

> "Unlike a raw ChatMessage, a MemoryEntry is structured and governable."

但只针对长期记忆层：

```text
Working  = AgentState（原始消息）
Session  = ChatSession（原始消息）
Long-term = MemoryEntry（结构化条目）← 只有这层结构化
```

原始消息和结构化条目是分工，不是二选一。

## 二、11 字段各是治理的答案

```java
record MemoryEntry(id, scope, type, subject, content, importance,
                   provenance, status, createdAt, expireAt, dueAt)
```

| 问题 | 字段 |
|---|---|
| 属于谁、谁能看见 | scope |
| 哪类记忆 | type |
| 冲突检测键 | subject |
| 值不值得记/召回 | importance |
| 谁说的、怎么来的 | provenance |
| 现在是否有效 | status |
| 何时失效 | expireAt |

原始消息没有元数据，治理无从下手；结构化把治理需要的信息显式化。

## 三、为什么结构化

1. 可治理：回答"谁写的、有效吗、该被取代吗"。
2. 可检索：按 type 过滤、按 subject 精确查、按 importance 排序。
3. 可冲突消解：subject 是键，同 subject 异内容 → supersede（决策 18）。

## 四、认知原型

> "人脑不存录像，存压缩后的要点 + 检索线索。"

长期记忆存提炼后的 MemoryEntry，不存原始消息。mem0 / ChatGPT memory / Claude memory 同属知识提取派。

## 五、代价（必须答）

1. 抽取有损：语境/语气/暗示丢失。缓解：原始消息在 Session 层和 compaction 归档保留。
2. 多一条抽取流水线：extract → policy → store 要维护、会失败（LLM 抽取失败=静默丢记忆）。
3. Schema 演化负担：11 字段，加字段要改 record/store/序列化/测试。
4. subject 是弱链：冲突检测质量取决于 subject 抽取质量。

## 六、诚实指出弱链

`KeywordMemoryExtractor.deriveSubject()` 用"内容前 20 字符"当 subject：

```java
return content.length() <= 20 ? content : content.substring(0, 20);
```

换一种说法就抽不到同一 subject → supersede 失效，冲突检测形同虚设。LLM 抽取器能造 subject，规则抽取器是前 20 字符——v1 已知弱点。

## 七、什么场景会改

- 完整回放 → 不用改，原始消息已在 Session/归档层。
- subject 冲突复杂 → LLM 统一抽 / 图化。
- 字段膨胀 → 拆表/子实体。
- 全文语义检索 → content 加 embedding（决策 17 反面，Stage 15）。

## 八、架构师洞察

```text
结构化 vs 原始 = 治理 vs 回放
治理要元数据（status/provenance/subject）→ 结构化
回放要完整事件流 → 原始
```

答案是两者都要，分层存：

```text
原始消息  → Session 层 + compaction 归档（回放）
结构化条目 → Long-term 层（治理/检索）
```

与 tool 审计同基因：治理 = 结构化留痕 + 可回溯。

## 九、面试表述

> 长期记忆存结构化 MemoryEntry，不存原始 ChatMessage。
> 原始消息没元数据，治理无从下手；MemoryEntry 的 scope/type/subject/importance/provenance/status 分别回答"谁的/哪类/冲突键/值不值得/谁说的/是否有效"。
> 但结构化不是替代原始——原始消息在 Session 层和归档里保留，结构化只管长期层治理与检索。
> 代价：抽取有损、多一条流水线、schema 会膨胀；已知弱点是规则抽取器用内容前 20 字符当 subject。
> 与 tool 审计同基因：治理 = 结构化留痕 + 可回溯。

## 关联

- 证据：`MemoryEntry` 11 字段 record + javadoc；`KeywordMemoryExtractor.deriveSubject()`；stage-8-memory-explained.md D2/P3。
- 决策 15（scope）、16（写入闸门）、18（supersede）都以本决策的结构化为前提。
