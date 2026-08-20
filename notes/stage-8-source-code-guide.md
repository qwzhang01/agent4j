# Stage 8 源码导读：概念 -> 实体 -> 数据流

> 对应阶段：Stage 8 - Memory、Context 与共享记忆治理
> 定位：源码阅读笔记 -- 对着 agent-memory 模块 18 个类，讲清概念、实体设计、数据流向
> 配套：架构设计见 [architecture-stage-8.md](architecture-stage-8.md)，概念详解见 [stage-8-memory-explained.md](stage-8-memory-explained.md)，9 层教程见 [stage-8-memory-tutorial.md](stage-8-memory-tutorial.md)，存储方案见 [stage-8-memory-storage-backends.md](stage-8-memory-storage-backends.md)

---

## 全局图：18 个类分四层

```text
┌─────────────────────────────────────────────────────────────┐
│ 第一层：数据模型（6 个，纯 record/enum，零依赖）              │
│  MemoryEntry    一条记忆的本体（10 个字段）                    │
│  MemoryScope    命名空间（agent/user/session/task/channel）   │
│  MemoryProvenance  来源溯源（sourceType/actor/runId/at）      │
│  MemoryType     PREFERENCE/FACT/EPISODE/SUMMARY/EVENT        │
│  MemoryStatus   ACTIVE/PENDING_REVIEW/REJECTED/SUPERSEDED/EXPIRED │
│  MemoryQuery    查询条件（scopes+type+subject+keyword+limit） │
├─────────────────────────────────────────────────────────────┤
│ 第二层：存储（2 个）                                          │
│  MemoryStore        接口（7 个方法）                           │
│  InMemoryMemoryStore  v1 实现（ConcurrentHashMap）           │
├─────────────────────────────────────────────────────────────┤
│ 第三层：流水线（6 个，写入端+读取端+治理）                      │
│  MemoryExtractor    写入端：从对话提取候选 -> 三道闸 -> 落库   │
│  MemoryPolicy       写入闸门：阈值+频控+supersede+scope状态    │
│  MemoryRetriever    读取端：按 scope 检索 ACTIVE 条目         │
│  MemoryAdmin        治理面板：approve/reject/supersede/setTtl │
│  ChatSession        会话层：toAgentState/syncFrom 双向搬运    │
│  MemoryTools        模型自决：save_memory/search_memory 工具  │
├─────────────────────────────────────────────────────────────┤
│ 第四层：上下文层（4 个，接 AgentLoop）                         │
│  ContextBuilder     接口（在 agent-core，不在本模块）         │
│  PassthroughContextBuilder  透传（向后兼容）                  │
│  ContextBudget      token 估算（chars/4）                    │
│  ContextCompressor  pi 式压缩（旧段总结+保留最近K条+归档）     │
│  MemoryContextBuilder   完整版（压缩+检索+注入）              │
│  CompressingContextBuilder  只压缩版                          │
└─────────────────────────────────────────────────────────────┘
```

四层依赖关系：第一层零依赖（纯数据结构）；第二层依赖第一层；第三层依赖第一层+第二层；第四层依赖第一层+第二层+第三层+agent-core。**agent-memory 不依赖 agent-workflow / agent-scheduler / agent-security**，保持记忆层独立。

---

## 第一层：数据模型（6 个类）

这 6 个是纯数据结构，没有业务逻辑，是整个模块的"语言"。

### 1. MemoryEntry -- 一条记忆的本体

```text
28:39:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryEntry.java
public record MemoryEntry(
        String id,              // 唯一标识
        String scope,           // 命名空间 "user:u1" / "channel:c1"
        MemoryType type,        // PREFERENCE / FACT / EPISODE / SUMMARY / EVENT
        String subject,         // 主题键，冲突检测用（如 "饮食禁忌"）
        String content,         // 记忆文本（如 "对花生过敏"）
        double importance,      // 0.0~1.0，写入闸门用
        MemoryProvenance provenance,  // 来源溯源
        MemoryStatus status,    // 生命周期状态
        Instant createdAt,      // 创建时间
        Instant expireAt        // TTL（null=永久）
) { ... }
```

**关键设计**：这是 record（不可变）。要改状态怎么办？用 `with` 方法生成新实例：

```text
42:50:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryEntry.java
    public MemoryEntry withStatus(MemoryStatus newStatus) { ... }
    public MemoryEntry withContent(String newContent) { ... }
```

为什么不可变？因为治理操作（supersede/approve）需要保留旧状态留痕。如果可变，改了就丢了历史。record + with 方法 = 不可变 + 状态转换。

**TTL 检查**：

```text
55:57:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryEntry.java
    public boolean isExpired(Instant now) {
        return expireAt != null && !now.isBefore(expireAt);
    }
```

一行代码。expireAt 为 null = 永久；否则过了就过期。注意这里不删条目，只是"判断是否过期"--删除是调用方的事（惰性过滤）。

### 2. MemoryScope -- 命名空间

```text
23:56:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryScope.java
public record MemoryScope(String value) {
    public enum Kind { AGENT, USER, SESSION, TASK, CHANNEL }

    public static MemoryScope user(String userId) { ... }      // "user:u1"
    public static MemoryScope channel(String channelId) { ... } // "channel:c1"
    public static MemoryScope of(String value) { ... }         // 解析校验
    public Kind kind() { ... }      // "user" -> Kind.USER
    public String id() { ... }      // "u1"
}
```

**关键认知**：隔离和共享统一为这个字符串。B 的查询列表含 `user:u2` 不含 `user:u1` -> B 看不到 A 的记忆；两人都含 `channel:c1` -> 都能看到频道记忆。**隔离是 scope 不在列表，共享是 scope 在列表，实现都是 store 的一行 filter。**

### 3. MemoryProvenance -- 来源溯源

```text
18:23:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryProvenance.java
public record MemoryProvenance(
        SourceType sourceType,  // USER_SAID / TOOL_RESULT / MODEL_DERIVED / ADMIN_EDIT
        String actor,           // 谁说的（userId / toolName / modelId）
        String runId,           // 从哪次 run 提取的（admin 编辑为 null）
        Instant at              // 什么时候记录的
) { ... }
```

四个字段回答四个排查问题：
- sourceType -> "这条记忆可信度怎样"（ADMIN_EDIT > USER_SAID）
- actor -> "谁说的，去问谁确认"
- runId -> "哪次对话产生的，怎么翻完整上下文"
- at -> "什么时候记的，是否过时"

四种来源（SourceType 枚举）：
- USER_SAID：用户在对话里说的
- TOOL_RESULT：从工具执行结果提取的
- MODEL_DERIVED：模型总结/压缩/主动存的（如 compaction 归档、save_memory 工具调用）
- ADMIN_EDIT：管理员手写或修改的

### 4. MemoryType -- 记忆类型枚举

```text
8:29:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryType.java
PREFERENCE  偏好（"喜欢深色模式"）
FACT        事实（"时区 UTC+8"）
EPISODE     事件（"用户上周申请过退款"）
SUMMARY     压缩摘要（ContextCompressor 产出）
EVENT       外部事件（"PR #123 合并了"）
```

对应认知科学的 Tulving 分类（情景/语义/程序）+ 工程扩展（SUMMARY 是压缩产物、EVENT 是外部事件）。用于检索时按类型过滤，以及 policy 按类型施加不同规则。

### 5. MemoryStatus -- 生命周期状态枚举

```text
15:21:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryStatus.java
ACTIVE          活跃，可检索，注入上下文
PENDING_REVIEW  待审（channel scope 默认值），不进上下文
REJECTED        管理员拒绝，不可检索
SUPERSEDED      被同 subject 的新条目取代（保留审计）
EXPIRED         TTL 过期（惰性过滤，不主动删）
```

五态生命周期：
- 写入 -> ACTIVE（个人）或 PENDING_REVIEW（频道）
- PENDING_REVIEW -> approve -> ACTIVE，或 reject -> REJECTED
- ACTIVE -> 新条目同 subject 异内容 -> SUPERSEDED
- ACTIVE -> expireAt 过期 -> 惰性过滤（不主动改状态，查询时跳过）
- 任何状态 -> admin.delete -> 物理删除（GDPR 专用，治理别用）

**检索只回 ACTIVE**。这是 store.query 的强制过滤。

### 6. MemoryQuery -- 查询条件

```text
19:25:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryQuery.java
public record MemoryQuery(
        List<String> scopes,   // 必填！查询范围（隔离的强制化）
        MemoryType type,        // 可选类型过滤
        String subject,         // 可选主题精确匹配
        String keyword,         // 可选内容关键词
        int limit              // 最大结果数
) { ... }
```

**关键设计**：scopes 是必填的（Builder.build 里 `Objects.requireNonNull(scopes)`）。这是隔离强制化--不指定 scope 就不允许查，杜绝"全库扫描"导致越权。

Builder 模式支持两种 scope 传法：
- `scopes(List<String>)`：直接传字符串列表
- `scopes(MemoryScope...)`：传 MemoryScope 对象，自动 `.map(MemoryScope::value)` 转字符串

---

## 第二层：存储（2 个类）

### MemoryStore 接口 + InMemoryMemoryStore 实现

接口 7 个方法：

```text
write(MemoryEntry)                         写入（id 为 null 时自动分配）
query(MemoryQuery)                          查询（强制 scope 隔离）
findActiveBySubject(scope, subject)         查同 subject 的当前 ACTIVE（冲突检测用）
update(MemoryEntry)                         更新（状态转换/内容编辑/supersede）
findById(String id)                         按 id 查（任何状态）
delete(String id)                           硬删（GDPR 专用，治理走 supersede 不走这个）
listByScope(String scope)                   列出某 scope 全部（含所有状态，管理员视图）
```

v1 实现是 `InMemoryMemoryStore`，底层一个 `ConcurrentHashMap<String, MemoryEntry>`。

**最重要的方法是 query，四重过滤**：

```text
44:72:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/InMemoryMemoryStore.java
    public List<MemoryEntry> query(MemoryQuery query) {
        Stream<MemoryEntry> stream = entries.values().stream()
                // 1. scope 隔离：scope 不在查询列表 -> 过滤掉
                .filter(e -> query.scopes().contains(e.scope()))
                // 2. 只回 ACTIVE：pending/rejected/superseded/expired 全过滤
                .filter(e -> e.status() == MemoryStatus.ACTIVE)
                // 3. TTL 惰性过滤：过期的不返回
                .filter(e -> !e.isExpired(Instant.now()));

        // 4. 可选过滤：type / subject / keyword
        if (query.type() != null) { ... }
        if (query.subject() != null && !query.subject().isBlank()) { ... }
        if (query.keyword() != null && !query.keyword().isBlank()) { ... }

        // 按创建时间倒序 + limit
        ...
    }
```

**前三重过滤是强制的**（scope 隔离 + ACTIVE + TTL），后三重是可选的（type/subject/keyword）。这就是"隔离靠 store 强制不靠调用方自觉"的实现--调用方传什么 scopes，store 只返回这些 scope 的条目。

`findActiveBySubject` 是冲突检测用的：

```text
76:83:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/InMemoryMemoryStore.java
    public Optional<MemoryEntry> findActiveBySubject(String scope, String subject) {
        return entries.values().stream()
                .filter(e -> e.scope().equals(scope))
                .filter(e -> subject.equals(e.subject()))
                .filter(e -> e.status() == MemoryStatus.ACTIVE)
                .filter(e -> !e.isExpired(Instant.now()))
                .max(Comparator.comparing(MemoryEntry::createdAt));  // 多条取最新
    }
```

查"这个 scope 下这个 subject 的当前 ACTIVE 条目"。如果新记忆和旧的 subject 一样但 content 不同 -> 触发 supersede。

`listByScope` 是管理员视图：

```text
105:110:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/InMemoryMemoryStore.java
    public List<MemoryEntry> listByScope(String scope) {
        return entries.values().stream()
                .filter(e -> e.scope().equals(scope))
                .sorted(Comparator.comparing(MemoryEntry::createdAt).reversed())
                .toList();
    }
```

注意：listByScope **不做 status 过滤**--返回所有状态（含 PENDING_REVIEW/REJECTED/SUPERSEDED）。这是管理员审计需要的完整视图，和 query（只回 ACTIVE）形成对比。

---

## 第三层：流水线（6 个类）

### 写入端：MemoryExtractor + MemoryPolicy

**MemoryExtractor** 是写入流水线的入口。两步走：

**第 1 步 extract** -- 从消息列表提取候选：

```text
38:57:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryExtractor.java
    public List<MemoryEntry> extract(...) {
        for (ChatMessage msg : messages) {
            if (msg.role() != ChatRole.USER ...) continue;  // 只看 USER 消息
            String matched = matchKeyword(content);           // 命中偏好关键词
            if (matched != null) {
                candidates.add(new MemoryEntry(
                    null, scope, MemoryType.PREFERENCE, subject, content,
                    0.7, baseProvenance, MemoryStatus.ACTIVE, ...));
            }
        }
    }
```

只提取 USER 消息（assistant 回复可能含幻觉，不记）。关键词命中（"记住"/"我喜欢"/"prefer"等）才生成候选，importance 默认 0.7。

关键词表是双语硬编码的：

```text
25:28:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryExtractor.java
    private static final List<String> PREFERENCE_KEYWORDS = List.of(
            "记住", "我是", "我喜欢", "我不喜欢", "我偏好", "我习惯", "我对", "别",
            "always", "never", "prefer", "i am", "i like", "i don't like"
    );
```

subject 推导规则（v1 简化版）：

```text
108:110:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryExtractor.java
    private String deriveSubject(String content) {
        return content.length() <= 20 ? content : content.substring(0, 20);
    }
```

取内容前 20 字。同样内容 -> 同 subject（频控有效）；不同内容 -> 不同 subject（不会误 supersede）。v1 的局限是语义级冲突检测做不到（"过敏"和"不耐受"字面不同但语义相同，不会触发 supersede）。

**第 2 步 extractAndStore** -- 三道闸 + 落库：

```text
65:91:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryExtractor.java
    public int extractAndStore(...) {
        for (MemoryEntry candidate : candidates) {
            // 闸 1+2：shouldStore（阈值 + 频控）
            if (!policy.shouldStore(candidate, store)) continue;
            // 闸 3：shouldSupersede（同 subject 异内容 -> 旧的标 SUPERSEDED）
            if (policy.shouldSupersede(candidate, store)) {
                store.update(old.withStatus(MemoryStatus.SUPERSEDED));
            }
            // 状态默认值（channel -> PENDING_REVIEW）
            store.write(candidate.withStatus(defaultStatus));
        }
    }
```

三道闸全在写路径。这就是"写谨慎"--污染一条记忆之后每轮都放大它，所以闸门全卡在写入侧。

**MemoryPolicy** 是三道闸的逻辑：

```text
33:55:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryPolicy.java
    public boolean shouldStore(MemoryEntry candidate, MemoryStore store) {
        // 闸 1：importance < 阈值 -> 拒绝
        if (candidate.importance() < importanceThreshold) return false;
        // 闸 2：同 scope + 同 subject + 同 content 已存在 -> 拒绝（频控）
        if (existing.isPresent() && existing.get().content().equals(candidate.content())) return false;
        return true;
    }
    public boolean shouldSupersede(...) {
        // 同 subject 但 content 不同 -> 要取代
        return existing.isPresent() && !existing.get().content().equals(candidate.content());
    }
```

闸 1 是 importance 阈值（默认 0.5）。显式 save_memory 工具调用时 importance=1.0，自动豁免这道闸（D8 设计：模型自决存取是高置信信号）。

闸 2 是频控：同 scope + 同 subject + 完全相同的 content -> 不重复写。防止用户在同一会话里重复说同一句话。

闸 3 是 supersede：同 scope + 同 subject 但 content 不同 -> 旧的标 SUPERSEDED（不物理删），新的写 ACTIVE。保证同一主题只有一条 ACTIVE。

还有一个关键方法决定个人 vs 共享的治理差异：

```text
67:72:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryPolicy.java
    public MemoryStatus defaultStatusForScope(String scope) {
        if (scope != null && scope.startsWith("channel:")) {
            return MemoryStatus.PENDING_REVIEW;  // 频道写入默认待审
        }
        return MemoryStatus.ACTIVE;               // 个人写入立即可用
    }
```

一行 `startsWith("channel:")` 决定了个人记忆和共享记忆的治理差异。**个人记忆写完即用（影响小），共享记忆先审后用（影响大）。**

### 读取端：MemoryRetriever

```text
// MemoryRetriever.java
public List<MemoryEntry> recall(List<String> scopes) {
    return store.query(MemoryQuery.builder().scopes(scopes).build());
}
public List<MemoryEntry> recallForContext(List<String> scopes, int limit) {
    return store.query(MemoryQuery.builder().scopes(scopes).limit(limit).build());
}
```

非常薄--就是包装 `store.query`。隔离/ACTIVE/TTL 过滤全在 store 里做，retriever 不重复判。**读路径尽量少过滤，只要 scope 内、ACTIVE、未过期就给。**

还有一个 keyword 检索方法：

```text
public List<MemoryEntry> recallByKeyword(List<String> scopes, String keyword) {
    if (keyword == null || keyword.isBlank()) return recall(scopes);
    return store.query(MemoryQuery.builder().scopes(scopes).keyword(keyword).build());
}
```

keyword 为空时退化为全量 recall。这是 MemoryTools.search_memory 工具调用的后端。

### 治理：MemoryAdmin

核心方法 `supersede`（更正记忆）：

```text
1. 找到旧条目（requireEntry，不存在抛异常）
2. update 旧条目 status -> SUPERSEDED（不物理删）
3. 写新条目：同 scope/type/subject，新 content，provenance=ADMIN_EDIT，status=ACTIVE
4. 返回新条目
```

`approve`（审核通过）：

```text
1. 检查条目是 PENDING_REVIEW（否则抛 IllegalStateException）
2. 如果同 subject 有旧 ACTIVE -> 先 supersede 旧的
3. update 待审条目 status: PENDING_REVIEW -> ACTIVE
```

其他方法：
- `reject`：PENDING_REVIEW -> REJECTED（不进上下文，保留审计）
- `updateContent`：改 content，provenance 改成 ADMIN_EDIT
- `addEntry`：管理员直接写一条（importance=1.0，ACTIVE）
- `setTtl`：给条目设 expireAt
- `delete`：硬删（GDPR 专用，治理走 supersede 不走这个）
- `listPending`：列出某 scope 的待审条目
- `listByScope`：列出某 scope 全部条目（含所有状态，管理员视图）

### 会话层：ChatSession

双向搬运的代码：

```text
toAgentState(systemPrompt)：  会话历史 -> AgentState
  state = new AgentState()
  state.addMessage(ChatMessage.system(systemPrompt))    加 system
  for (msg : history) state.addMessage(msg)             灌入会话历史
  => 第 2 层降维成第 1 层

syncFrom(state)：              AgentState -> 会话历史
  history.clear()
  for (msg : state.getMessages())
    if (msg.role() != SYSTEM) history.add(msg)          剔 system 拉回
  => 第 1 层同步回第 2 层
```

这两个方法就是第 1 层和第 2 层之间的双向搬运（9 层教程第 3 层讲的三横模型中 Working 和 Session 之间的桥）。

### 模型自决：MemoryTools

两个 Tool：
- `save_memory(subject, content, type)`：importance=1.0（豁免闸 1 阈值），但仍走 supersede 和待审。对齐 Stage 3 自进化风格（Agent 自己管自己的能力/记忆）。思想源头是 MemGPT 的 self-editing。
- `search_memory(keyword)`：调 retriever.recallByKeyword，scope 隔离照常生效。

豁免的只是阈值闸，supersede 和待审不豁免--模型有存什么的自由，没有绕过治理的自由。

---

## 第四层：上下文层（4 个类，接 AgentLoop）

### ContextBuilder 接口（在 agent-core）

```text
// agent-core 的 ContextBuilder.java
public interface ContextBuilder {
    List<ChatMessage> build(AgentConfig config, AgentState state);
}
```

只有一个方法。挂接到 ReActAgentLoop：

```text
115:120:agent-core/src/main/java/io/github/qwzhang01/agent/core/agent/ReActAgentLoop.java
    private ModelRequest buildRequest(AgentConfig config, AgentState state) {
        List<ChatMessage> messages = config.getContextBuilder() != null
                ? config.getContextBuilder().build(config, state)   // Stage 8 路径
                : new ArrayList<>(state.getMessages());              // 透传，Stage 1-7 行为
```

不配 ContextBuilder = 行为不变（存量测试零影响）；配了 = 压缩+检索+注入生效。这是装饰器风格（与 RetryModelClient / TimeoutModelClient 同理）。

### PassthroughContextBuilder -- 透传

```text
public List<ChatMessage> build(AgentConfig config, AgentState state) {
    return new ArrayList<>(state.getMessages());
}
```

一行代码，原样返回。不配 ContextBuilder 时 ReActAgentLoop 自己就是这么做的，这个类是"显式的空操作"--当你想要"配了但什么都不做"时用。

### ContextBudget -- token 估算

```text
// ContextBudget.java
public static int estimate(List<ChatMessage> messages) {
    int chars = 0;
    for (ChatMessage msg : messages) {
        if (msg.content() != null) chars += msg.content().length();
        if (msg.toolCalls() != null) { /* 加 toolCall 的 name+args 长度 */ }
    }
    return chars / 4;  // 粗估：4 字符 ≈ 1 token
}
```

chars/4 的粗估。目标是"压缩先于超窗"，不追求精确。引 tokenizer 是 Stage 15 的事。

### ContextCompressor -- pi 式压缩

核心方法 `compress`，五步流程：

```text
1. 估预算：ContextBudget.estimate(messages) > budgetTokens ?
     否 -> 原样返回（didCompress=false）
     是 -> 进入压缩

2. 切分消息：
     system 消息 -> 全部保留（人设/规则不能动）
     非 system 消息：
       前 (n - keepRecent) 条 -> 待归档段（要被压缩的）
       后 keepRecent 条       -> 近期保留段（原样留窗内）

3. 调模型总结：
     待归档段拼成长文本 -> ModelClient.chat
     成功 -> "[Summary of earlier conversation]\n..."（user 角色）
     失败 -> 降级截断（前 500 字符，不抛异常）

4. 重组窗口：
     [system..., summary(作为 user 消息), 最近 K 条]

5. 返回 CompressionResult(compressed, archived, didCompress)
```

返回的 `CompressionResult` record 有三个字段：
- `compressed`：压缩后的消息列表（system+summary+最近K条）
- `archived`：被压缩掉的原文消息列表（用于归档）
- `didCompress`：是否真的压缩了（boolean，注意不能叫 compressed，因为和 List 字段重名）

### MemoryContextBuilder -- 完整版（压缩+检索+注入）

```text
59:98:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryContextBuilder.java
    public List<ChatMessage> build(AgentConfig config, AgentState state) {
        List<ChatMessage> messages = state.getMessages();

        // 1. 压缩（如果配了 compressor）
        if (compressor != null) {
            var result = compressor.compress(messages);
            if (result.didCompress()) {
                messages.clear();              // 就地改写 state！
                messages.addAll(result.compressed());
                archive(result.archived(), config);  // 原文归档
            }
        }

        // 2. 检索记忆
        List<MemoryEntry> memories = recallLimit > 0
                ? retriever.recallForContext(scopes, recallLimit)
                : retriever.recall(scopes);

        if (memories.isEmpty()) {
            return new ArrayList<>(messages);
        }

        // 3. 注入（system 之后）
        String memoryBlock = renderMemories(memories);
        List<ChatMessage> assembled = new ArrayList<>();
        boolean injected = false;
        for (ChatMessage m : messages) {
            assembled.add(m);
            if (!injected && m.role() == ChatRole.SYSTEM) {
                assembled.add(ChatMessage.user("[Known memories]\n" + memoryBlock));
                injected = true;
            }
        }
        if (!injected) {
            assembled.add(0, ChatMessage.user("[Known memories]\n" + memoryBlock));
        }

        return assembled;
    }
```

三步：压缩 -> 检索 -> 注入。

**关键设计**：注入的记忆**不存回 state**。每次 build 都重新检索、重新注入、用完即弃。否则记忆会被 extractAndStore 再提取一遍 -> 自我复制 -> 记忆库被注入产物污染。

注入位置：system 之后、历史之前。记忆是"长期背景知识"，逻辑上跟 system prompt 同层，但形式上是"提供的信息"所以放它后面。如果 messages 里没有 system（极端情况），插到最前面。

`renderMemories` 方法把 MemoryEntry 列表渲染成文本：

```text
- [PREFERENCE] 记住我对花生过敏: 记住我对花生过敏
- [FACT] tz: UTC+8
```

`archive` 方法把被压缩掉的原文存成 SUMMARY 条目（可回溯）：

```text
112:130:agent-memory/src/main/java/io/github/qwzhang01/agent/memory/MemoryContextBuilder.java
    private void archive(List<ChatMessage> archived, AgentConfig config) {
        archiveStore.write(new MemoryEntry(
                null, archiveScope, MemoryType.SUMMARY,
                "compaction-" + Instant.now().toEpochMilli(),
                sb.toString().trim(), 0.3,
                MemoryProvenance.modelDerived(...),  // 谁压的
                MemoryStatus.ACTIVE, Instant.now(), null
        ));
    }
```

压缩是有损的，归档兜底回溯。importance=0.3（低，因为是压缩产物不是用户事实）。

### CompressingContextBuilder -- 只压缩版

和 MemoryContextBuilder 的区别：只做压缩+归档，不做记忆检索注入。当你只想要上下文管理（解决装不下）但还不需要跨会话记忆时用。

---

## 五条数据流（串起来看）

### 流 1：写入（run 结束时）

```text
run 的 messages
  -> MemoryExtractor.extract          只看 USER + 偏好关键词命中 -> 候选
  -> MemoryPolicy.shouldStore         闸1 阈值 + 闸2 频控
  -> MemoryPolicy.shouldSupersede     闸3 同 subject 异内容 -> 旧的 SUPERSEDED
  -> policy.defaultStatusForScope     channel -> PENDING_REVIEW
  -> store.write                      落库
```

### 流 2：读取（每轮 buildRequest）

```text
ReActAgentLoop.buildRequest
  -> config.getContextBuilder().build(config, state)
     -> MemoryContextBuilder.build
        -> compressor.compress          超预算就压缩+归档+改写state
        -> retriever.recall(scopes)    检索 ACTIVE + scope 隔离 + TTL
        -> renderMemories              渲染成 "[Known memories]" 文本
        -> 插入 system 之后            注入（不落盘）
  -> 返回给 ModelRequest
```

### 流 3：压缩（超预算时）

```text
ContextBudget.estimate(messages) = chars/4
  > budget ?
    否 -> 原样返回
    是 -> 切分：system 全留 / 非 system 前 n-K 条归档+后 K 条留
       -> 调 ModelClient 总结归档段（失败降级截断）
       -> 重组 [system, summary, 最近K条]
       -> state.clear + addAll(压缩结果)   就地改写（保 Checkpoint 一致）
       -> 归档原文存 store 成 SUMMARY 条目
```

### 流 4：治理（channel 共享）

```text
A 在频道说"过敏" -> extractAndStore -> PENDING_REVIEW（channel 默认）
B 检索 -> 空（未审不生效）
admin.approve -> 同 subject 旧 ACTIVE 先 supersede -> 改成 ACTIVE
B 检索 -> 命中
发现错了 -> admin.supersede -> 旧 SUPERSEDED + 新 ACTIVE
B 检索 -> 只见更正版；listByScope 可见完整审计链
```

### 流 5：模型自决（工具调用）

```text
模型调 save_memory(subject, content)
  -> importance=1.0（豁免闸1，但不豁免 supersede 和待审）
  -> store.write

模型调 search_memory(keyword)
  -> retriever.recallByKeyword(scopes)  scope 隔离照常
  -> 返回匹配条目
```

---

## 一句话总结代码结构

> **6 个数据模型定义语言，1 个 Store 接口 + 1 个内存实现管存储，6 个流水线类管写入/读取/治理/会话/工具，4 个上下文层类接 AgentLoop。写入三道闸全在写路径（写谨慎），读取靠 store 一行 filter 强制隔离（读慷慨），注入不落盘防自我复制，压缩就地改写 state 保 Checkpoint 一致。**

---

## 关键设计决策回顾（对照代码位置）

| 决策 | 内容 | 代码位置 |
|------|------|---------|
| D1 | AgentState 与 MemoryStore 分层 | MemoryEntry 不存 ChatMessage；AgentState 在 agent-core，MemoryStore 在 agent-memory |
| D2 | 结构化条目而非原始消息 | MemoryEntry 10 个字段带元数据；MemoryExtractor 只提取 USER 消息 |
| D3 | 共享与隔离统一为 MemoryScope | MemoryScope 一个字符串；InMemoryMemoryStore.query 一行 `.filter(e -> query.scopes().contains(e.scope()))` |
| D4 | compaction 改写 state + 原文归档 | MemoryContextBuilder.build `messages.clear() + addAll()`；archive 存 SUMMARY 条目 |
| D5 | ContextBuilder 挂 AgentConfig | ReActAgentLoop.buildRequest 一行 `config.getContextBuilder() != null` |
| D6 | 污染防御三道闸 + channel 待审 | MemoryPolicy.shouldStore + shouldSupersede + defaultStatusForScope |
| D7 | 检索 v1 keyword 不做向量 | InMemoryMemoryStore.query `.contains(keyword)`；接口已抽象可换 |
| D8 | MemoryTools 模型自决存取 | MemoryTools.saveMemory importance=1.0 豁免闸1 |

---

## 附录：完整代码流程示例 -- "我不喜欢菠萝 -> 帮我点外卖"

> 以下例子用于帮助理解前述的抽象描述。对着代码逐步走一遍，看清写入和读取两条管道是怎么串起来的。

### 场景设定

```text
会话 A（昨天）：
  用户：我不喜欢菠萝
  助手：好的，记下了。

会话 B（今天）：
  用户：帮我点外卖
  助手：（应该避开菠萝推荐）
```

两个会话之间，AgentState 已经丢了（run 结束即焚）。但记忆库里应该有一条"用户不喜欢菠萝"。今天点外卖时，这条记忆要被取回来注入窗口，模型才能看到。

---

### 阶段 1：会话 A -- 写入"我不喜欢菠萝"

#### 步骤 1.1：用户消息进入会话历史

```java
ChatSession session = new ChatSession("s1");
session.addUser("我不喜欢菠萝");
// session.history 现在有 1 条：[user: "我不喜欢菠萝"]
```

#### 步骤 1.2：构建 AgentState，模型回复

```java
AgentState state = session.toAgentState("You are a helpful assistant.");
// state.messages 现在有 2 条：
//   [system: "You are a helpful assistant.", user: "我不喜欢菠萝"]

// 模型回复（简化，实际走 ReActAgentLoop）
state.addMessage(ChatMessage.assistant("好的，记下了。"));
// state.messages 现在有 3 条：system + user + assistant
```

#### 步骤 1.3：提取记忆 -- MemoryExtractor.extract()

会话 A 结束时，调 extractAndStore：

```java
MemoryExtractor extractor = new MemoryExtractor();
MemoryPolicy policy = new MemoryPolicy(0.5);  // 阈值 0.5
InMemoryMemoryStore store = new InMemoryMemoryStore();

int stored = extractor.extractAndStore(
    state.getMessages(),                          // 3 条消息
    "user:u1",                                     // 存到用户 u1 的 scope
    MemoryProvenance.userSaid("u1", "run-1", Instant.now()),
    policy,
    store
);
```

进入 extract() 方法（MemoryExtractor.java:38-57）：

```text
遍历 messages：
  [system] 跳过（不是 USER）
  [user: "我不喜欢菠萝"]
    -> 命中偏好关键词 "我不喜欢"  ✓
    -> subject = "我不喜欢菠萝"（前 20 字，正好全句）
    -> content = "我不喜欢菠萝"
    -> type = PREFERENCE
    -> importance = 0.7（规则提取默认值）
    -> provenance = (USER_SAID, "u1", "run-1", now)
    -> 生成候选 MemoryEntry
  [assistant: "好的，记下了。"] 跳过（不是 USER）

结果：1 个候选 MemoryEntry
```

#### 步骤 1.4：三道闸检查 -- MemoryPolicy

进入 extractAndStore 的循环（MemoryExtractor.java:65-91）：

**闸 1 + 闸 2：shouldStore**（MemoryPolicy.java:33-46）

```text
candidate.importance = 0.7
0.7 >= 阈值 0.5 ?  ✓ 闸 1 通过

store.findActiveBySubject("user:u1", "我不喜欢菠萝")
  -> 查库：这个 scope 这个 subject 有没有已存在的 ACTIVE？
  -> 没有（第一次写）
  -> 闸 2 通过（不重复）

shouldStore = true
```

**闸 3：shouldSupersede**（MemoryPolicy.java:48-52）

```text
同 subject 有旧条目但 content 不同？
  -> 没有旧条目
  -> shouldSupersede = false
  -> 不需要 supersede
```

**状态默认值：defaultStatusForScope**（MemoryPolicy.java:67-72）

```text
scope = "user:u1"
scope.startsWith("channel:") ?  否
-> 返回 ACTIVE（个人记忆写完即用，不需要待审）
```

#### 步骤 1.5：落库 -- store.write

```java
MemoryEntry toStore = candidate.withStatus(MemoryStatus.ACTIVE);
store.write(toStore);
```

现在记忆库的状态：

```text
store.entries = {
  "uuid-xxx": MemoryEntry {
    id:          "uuid-xxx",
    scope:       "user:u1",
    type:        PREFERENCE,
    subject:     "我不喜欢菠萝",
    content:     "我不喜欢菠萝",
    importance:  0.7,
    provenance:  (USER_SAID, "u1", "run-1", 2026-08-19T...),
    status:      ACTIVE,
    createdAt:   2026-08-19T...,
    expireAt:    null          // 永久
  }
}
```

#### 步骤 1.6：同步会话历史

```java
session.syncFrom(state);
// session.history 现在 = [user: "我不喜欢菠萝", assistant: "好的，记下了。"]
// （剔掉了 system）
```

会话 A 结束。AgentState 被丢弃，ChatSession 可能归档或丢弃。但**记忆库里的那条 ACTIVE 条目还在**。

---

### 阶段 2：会话 B -- "帮我点外卖"时取出记忆

#### 步骤 2.1：新会话，全新 AgentState

```java
ChatSession session2 = new ChatSession("s2");
session2.addUser("帮我点外卖");
// session2.history = [user: "帮我点外卖"]

AgentState state2 = session2.toAgentState("You are a helpful assistant.");
// state2.messages = [system, user: "帮我点外卖"]
// 注意：state2 里完全没有"菠萝"这个词
```

#### 步骤 2.2：构建上下文 -- MemoryContextBuilder.build()

这是关键步骤。ReActAgentLoop.buildRequest 会调：

```java
// ReActAgentLoop.java:115-120
List<ChatMessage> messages = config.getContextBuilder() != null
    ? config.getContextBuilder().build(config, state2)   // 走这里
    : new ArrayList<>(state2.getMessages());
```

进入 MemoryContextBuilder.build()（MemoryContextBuilder.java:59-98）：

#### 步骤 2.3：压缩检查（本次不触发）

```text
compressor != null ?
  -> 这里传的 null（没配压缩）
  -> 跳过压缩
  -> messages 还是 [system, user: "帮我点外卖"]
```

#### 步骤 2.4：检索记忆 -- retriever.recall()

```java
List<MemoryEntry> memories = retriever.recall(List.of("user:u1"));
// scopes = ["user:u1"]
```

进入 MemoryRetriever.recall -> store.query（InMemoryMemoryStore.java:44-72）：

```text
遍历 store.entries：
  找到 "uuid-xxx" 那条

四重过滤：
  1. scope 隔离：query.scopes=["user:u1"], entry.scope="user:u1"  ✓ 包含
  2. ACTIVE：entry.status=ACTIVE  ✓
  3. TTL：entry.expireAt=null  ✓ 永久未过期
  4. keyword：query.keyword=null  跳过

命中！返回 [MemoryEntry{id=uuid-xxx, content="我不喜欢菠萝", ...}]
```

**这就是"记得"发生的地方**。store 的一行 filter 把 scope 对上了，记忆就被取出来了。

#### 步骤 2.5：渲染记忆块 -- renderMemories()

```java
String memoryBlock = renderMemories(memories);
```

（MemoryContextBuilder.java:100-108）

```text
渲染结果：
"- [PREFERENCE] 我不喜欢菠萝: 我不喜欢菠萝"
```

格式是 `- [type] subject: content`。

#### 步骤 2.6：注入到 system 之后

```java
List<ChatMessage> assembled = new ArrayList<>();
boolean injected = false;
for (ChatMessage m : messages) {        // [system, user: "帮我点外卖"]
    assembled.add(m);
    if (!injected && m.role() == ChatRole.SYSTEM) {
        assembled.add(ChatMessage.user("[Known memories]\n" + memoryBlock));
        injected = true;
    }
}
```

遍历 messages：
- 第 1 条是 system -> add，然后注入记忆块，标记 injected=true
- 第 2 条是 user: "帮我点外卖" -> add

最终 assembled：

```text
[
  system: "You are a helpful assistant.",
  user: "[Known memories]\n- [PREFERENCE] 我不喜欢菠萝: 我不喜欢菠萝",
  user: "帮我点外卖"
]
```

#### 步骤 2.7：发给模型

```java
ModelRequest request = ModelRequest.builder()
    .messages(assembled)   // 上面拼好的 3 条
    .build();
ModelResponse response = model.chat(request);
```

模型实际看到的内容（拼成一个 prompt 大概是）：

```text
[system] You are a helpful assistant.
[user] [Known memories]
- [PREFERENCE] 我不喜欢菠萝: 我不喜欢菠萝
[user] 帮我点外卖
```

**模型现在知道用户不喜欢菠萝了**。它大概率会回复类似：

```text
"好的，帮你点外卖。避开菠萝相关的菜品，给你推荐这几家..."
```

---

### 关键认知：模型自己没"记忆"，是记忆系统搬进去的

模型看到的内容里，"我不喜欢菠萝"不是从模型自己的脑子里冒出来的，而是：

```text
MemoryContextBuilder.build() 在每次调模型前：
  1. 去 store 里查 user:u1 的 ACTIVE 记忆
  2. 命中"我不喜欢菠萝"
  3. 拼成 "[Known memories]" 文本块
  4. 塞到 system prompt 后面
  5. 模型读到的 prompt 里就有了这条记忆
```

模型每次调用都是"金鱼"（即焚），它不记得昨天说过什么。是记忆系统在每次调模型前，把相关记忆搬进了窗口。

---

### 一张图：两次会话的完整数据流

```text
会话 A（昨天）                          记忆库
─────────────────────                  ┌──────────────────────┐
user: "我不喜欢菠萝"                    │ MemoryEntry {         │
  ↓                                    │   scope: "user:u1"    │
  ↓ extract: 命中"我不喜欢"             │   type: PREFERENCE    │
  ↓ shouldStore: 0.7>=0.5 ✓           │   subject: "我不喜欢菠萝"│
  ↓ defaultStatus: user:u1 -> ACTIVE  │   content: "我不喜欢菠萝"│
  ↓ store.write ─────────────────────> │   status: ACTIVE      │
assistant: "好的，记下了"               │   provenance: USER_SAID│
  ↓ session.syncFrom                   │   expireAt: null      │
会话 A 结束，AgentState 丢弃             │ }                    │
                                       └──────────────────────┘
                                          ↑ 记忆还在

会话 B（今天）                          │
─────────────────────                  │
user: "帮我点外卖"                       │
  ↓                                    │
  ↓ MemoryContextBuilder.build() ──────┤
  ↓   retriever.recall(["user:u1"]) ───┤
  ↓   store.query 四重过滤 ────────────┘
  ↓     scope=user:u1 ✓ ACTIVE ✓ TTL ✓
  ↓     命中"我不喜欢菠萝"
  ↓   renderMemories: "- [PREFERENCE] 我不喜欢菠萝"
  ↓   注入到 system 之后
  ↓
最终发给模型的 prompt：
  [system] You are a helpful assistant.
  [user] [Known memories]
         - [PREFERENCE] 我不喜欢菠萝: 我不喜欢菠萝
  [user] 帮我点外卖

模型回复："好的，避开菠萝，推荐这几家..."
```

---

### 补充：如果会话 B 里用户换了个说法

假设用户今天说的是"帮我点个披萨"，而不是"帮我点外卖"。模型看到的 prompt 还是：

```text
[system] You are a helpful assistant.
[user] [Known memories]
       - [PREFERENCE] 我不喜欢菠萝: 我不喜欢菠萝
[user] 帮我点个披萨
```

模型大概率会主动说"夏威夷披萨有菠萝，要不要避开？"--因为记忆被注入了，模型能看到。

**但注意**：检索是 keyword 包含匹配。会话 B 里用户说"帮我点外卖"或"帮我点披萨"，都没有出现"菠萝"两个字。那记忆怎么被取出来的？

看回步骤 2.4：`retriever.recall(List.of("user:u1"))` -- **没有传 keyword**。它调的是无 keyword 的 recall，返回 user:u1 scope 下的**所有 ACTIVE 记忆**。

```text
// MemoryRetriever.java
public List<MemoryEntry> recall(List<String> scopes) {
    return store.query(MemoryQuery.builder().scopes(scopes).build());
    //                                   ↑ 没传 keyword
}
```

所以 v1 的策略是：**只要 scope 对上，全部 ACTIVE 记忆都注入**（靠 recallLimit 控制数量）。不做"跟当前问题相关的才注入"--那需要语义检索（v2 向量）。

这是 v1 的局限：记忆多了之后全量注入会吃窗口预算。但 v1 的核心目标是"跑通记忆闭环"，这个简化是故意的。

---

### 补充：如果是频道共享场景

如果"我不喜欢菠萝"是用户 A 在频道 c1 说的（不是个人记忆），流程会有两处不同：

**写入时**（步骤 1.4）：

```text
defaultStatusForScope("channel:c1")
  -> scope.startsWith("channel:") -> true
  -> 返回 PENDING_REVIEW  ← 不是 ACTIVE！
```

所以写进去后 status=PENDING_REVIEW，用户 B 检索不到（store.query 只回 ACTIVE）。

**管理员审核后**：

```text
admin.approve("uuid-xxx")
  -> status: PENDING_REVIEW -> ACTIVE
```

审核通过后，用户 B 才能在 recall(["user:u2", "channel:c1"]) 时命中这条记忆。

---

## 附录补充：v1 的真实局限 -- 多条记忆时如何只选相关的

> 前面的例子只有一条记忆（"我不喜欢菠萝"），全量注入没问题。但用户记忆不止一条时，v1 的局限就暴露了。这个例子帮助理解"为什么 v1 够用、什么时候不够用、升级到 v2 的原因"。

### 场景：两条记忆，一条相关一条无关

```text
会话 A（昨天）：
  用户：我不喜欢菠萝
  用户：我不喜欢穿裙子
  助手：好的，都记下了。

会话 B（今天）：
  用户：帮我选午饭外卖
  助手：（应该只参考"不喜欢菠萝"，忽略"不喜欢穿裙子"）
```

记忆库现在的状态（会话 A 后）：

```text
store.entries = {
  "uuid-1": MemoryEntry { subject: "我不喜欢菠萝",   content: "我不喜欢菠萝",   type: PREFERENCE, status: ACTIVE }
  "uuid-2": MemoryEntry { subject: "我不喜欢穿裙子", content: "我不喜欢穿裙子", type: PREFERENCE, status: ACTIVE }
}
```

### 现状：v1 会把两条记忆都注入

会话 B 用户说"帮我选午饭外卖"，MemoryContextBuilder.build 调 retriever.recall(["user:u1"])：

```text
// MemoryRetriever.java
public List<MemoryEntry> recall(List<String> scopes) {
    return store.query(MemoryQuery.builder().scopes(scopes).build());
    //                                   ↑ 没传 keyword
}
```

**没传 keyword** -> 返回 user:u1 scope 下所有 ACTIVE 记忆 -> **两条都命中**。

注入后的窗口：

```text
[system] You are a helpful assistant.
[user] [Known memories]
       - [PREFERENCE] 我不喜欢菠萝: 我不喜欢菠萝
       - [PREFERENCE] 我不喜欢穿裙子: 我不喜欢穿裙子
[user] 帮我选午饭外卖
```

模型看到"我不喜欢穿裙子"这条记忆。选午饭时它大概率会自动忽略（因为裙子跟午饭无关，模型的注意力机制会判断相关性）。**但这浪费了窗口预算**--如果用户有 100 条偏好记忆，全部注入，窗口被占满，真正相关的"不喜欢菠萝"反而可能被 lost in the middle。

### 问题根源：v1 不做"相关性筛选"

```text
v1 的检索策略：scope 对上就全量返回
  -> 优点：简单、不漏（"不喜欢菠萝"肯定在里面）
  -> 缺点：不筛（"不喜欢穿裙子"也在里面，但跟午饭无关）

需要的能力：从所有记忆中筛出"跟当前问题相关的"
  -> 这正是向量检索解决的
```

v1 用 keyword 包含匹配，但 recall() 连 keyword 都没传--因为"帮我选午饭外卖"里不含"菠萝"也不含"裙子"，传了 keyword 也匹配不到。

### 三种解法（从 v1 到 v2 的演进路径）

#### 解法 1：让模型自己搜（v1 已支持 -- 双通道读取的主动通道）

v1 有 search_memory 工具（MemoryTools.java）。模型看到窗口里有记忆后，如果觉得不够，可以自己调工具搜：

```text
模型看到：[Known memories]（两条都注入了）
模型思考：用户要选午饭，我需要确认有没有饮食相关的偏好
模型调工具：search_memory(keyword="吃")   或  search_memory(keyword="午饭")
  -> MemoryTools.searchMemory
  -> retriever.recallByKeyword(["user:u1"], "吃")
  -> store.query: scope ✓ + ACTIVE ✓ + keyword="吃" 包含匹配

结果：可能命中"我不喜欢菠萝"（如果内容里含"吃"字）
      但大概率命中不了（"我不喜欢菠萝"里没有"吃"字）
```

**问题**：keyword 包含匹配太弱。模型搜"吃"搜不到"我不喜欢菠萝"，搜"菠萝"才能搜到--但模型不知道要搜"菠萝"。

这就是 v1 的核心局限：**keyword 匹配做不到"语义相关性"判断**。

#### 解法 2：向量检索（v2 -- Stage 15 RAG）

v2 给每条记忆加 embedding 字段，查询时按语义相似度检索：

```text
写入时：
  "我不喜欢菠萝"   -> 过 embedding 模型 -> [0.12, -0.34, ...] 存进 store
  "我不喜欢穿裙子" -> 过 embedding 模型 -> [0.45, 0.67, ...] 存进 store

读取时（会话 B）：
  用户说"帮我选午饭外卖"
  -> 过 embedding 模型 -> 查询向量 [0.21, -0.28, ...]
  -> 在向量库里找余弦相似度最高的 K 条
  -> "我不喜欢菠萝" 的向量跟"午饭外卖"接近（都是食物相关）
  -> "我不喜欢穿裙子" 的向量跟"午饭外卖"很远（服装 vs 食物）
  -> 只返回"不喜欢菠萝"，不返回"不喜欢穿裙子"
```

注入后的窗口：

```text
[system] You are a helpful assistant.
[user] [Known memories]
       - [PREFERENCE] 我不喜欢菠萝: 我不喜欢菠萝
[user] 帮我选午饭外卖
```

**只有相关的记忆被注入**。这是 v2 的核心价值。

**代码改动极小**：接口 MemoryStore.query(MemoryQuery) 不变，只换实现：

```text
v1 InMemoryMemoryStore:  .filter(e -> e.content().contains(keyword))     // 字面包含
v2 VectorMemoryStore:    .sorted(by: cosineSimilarity(e.embedding, queryVec))  // 向量相似
```

retriever 和 contextBuilder 零改动。

#### 解法 3：type 过滤（v1 也能做的轻量优化）

不换向量检索，但用 MemoryType 做粗筛：

```java
// 会话 B 检索时，只注入跟食物相关的类型
List<MemoryEntry> memories = store.query(MemoryQuery.builder()
    .scopes(List.of("user:u1"))
    .type(MemoryType.PREFERENCE)  // 只查偏好
    // 但"不喜欢菠萝"和"不喜欢穿裙子"都是 PREFERENCE，还是分不开
    .build());
```

不够--两条都是 PREFERENCE。要更细的类型分类才行（如 DIET_PREFERENCE vs CLOTHING_PREFERENCE），但这又回到了"手工分类标签"的老路，不智能。

**真正能区分"菠萝跟午饭相关"和"裙子跟午饭不相关"的，只有语义检索。**

### 总结对照

| 方案 | 能不能只选"菠萝"不选"裙子" | 原因 |
|------|--------------------------|------|
| v1 全量注入 | 不能（两条都注入） | recall 不传 keyword，全量返回 |
| v1 keyword 搜"吃" | 不能（搜不到"菠萝"） | 字面不匹配 |
| v1 模型自调 search_memory | 碰运气（模型得猜对搜索词） | keyword 包含太弱 |
| v2 向量检索 | **能** | 语义相似度自动判断"菠萝"跟"午饭"相关、"裙子"不相关 |
| v1 + type 过滤 | 不能 | 两条都是 PREFERENCE，type 区分不了 |

### v1 为什么这样设计（不是 bug，是有意的简化）

```text
v1 的核心目标：跑通"存-取-注入"闭环，证明记忆机制可行
v1 不做的：语义相关性判断（需要 embedding，引依赖、不可测）

接口已留好：
  MemoryStore.query(MemoryQuery) 抽象了"怎么查"
  v1 = keyword 包含
  v2 = 向量相似
  retriever 和 contextBuilder 不用改

v1 全量注入的代价：
  记忆少时（几条）-> 全注入没问题，模型自己判断相关性
  记忆多时（几十条）-> 吃窗口预算，需要 v2 向量检索筛相关的
```

所以这个场景（两条记忆、一个跟午饭相关一个不相关）在 v1 下：

1. **两条都注入**（recall 全量返回）
2. **模型靠自己的注意力判断**："不喜欢穿裙子"跟午饭无关，忽略它
3. **结果通常是对的**（模型够聪明），但**效率不优**（窗口被无关记忆占用）

记忆从几十条涨到几百条时，这个"全量注入"就撑不住了--那时候上 v2 向量检索，只注入语义相关的。接口不用改，换个 Store 实现就行。

### 数据流对比图：v1 vs v2

```text
v1（keyword 全量注入）：
  user:u1 的所有 ACTIVE 记忆（2 条）
    -> 全部注入窗口
    -> [Known memories]
       - 我不喜欢菠萝       ← 跟午饭相关，模型用上
       - 我不喜欢穿裙子     ← 跟午饭无关，模型忽略，但占了窗口
    -> 模型选午饭，避开菠萝

v2（向量语义检索）：
  user: "帮我选午饭外卖" -> embedding -> 查询向量
    -> 在向量库找相似度最高的 K 条
    -> 只命中"我不喜欢菠萝"（语义接近"午饭"）
    -> "我不喜欢穿裙子"被过滤掉（语义远离"午饭"）
    -> [Known memories]
       - 我不喜欢菠萝       ← 只有相关的进窗口
    -> 模型选午饭，避开菠萝，窗口没浪费
```

### 一句话总结

> **v1 全量注入靠模型自己判断相关性，记忆少时够用，多了撑不住；v2 向量检索在检索阶段就筛掉无关的，只注入语义相关的。两者接口不变，只换 Store 实现。这就是 v1 的局限和 v2 的升级原因。**
