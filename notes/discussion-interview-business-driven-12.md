# Agent 架构师面试问答：业务驱动版

> 时间：2026-08-25
> 核心原则：**面试官不会逐行读你的代码，他会从自己的业务痛点出发问问题。**
> 每道题的格式：面试官的业务场景 → 他的真实问题 → 你的回答思路

---

## 场景一：做企业 AI 平台的面试官

> 我们公司给 B 端客户提供 AI Agent 平台，多租户，每个客户有自己的数据隔离要求。
> 现在的问题是：客户 A 的 Agent 不小心读到了客户 B 的记忆数据，出了安全事故。
> 我们需要你设计一套可靠的数据隔离机制。

### Q1. "我们多租户场景下，Agent 的记忆数据怎么保证不串？你之前做过类似的事吗？"

**他想听什么**：你能不能把 agent4j 的 MemoryScope 设计迁移到他的多租户问题上来。

**回答思路**：

先承认问题本质——**多租户数据隔离 = 记忆的可见性边界问题**，然后从 agent4j 的经验出发：

1. **agent4j 做了什么**：MemoryScope 用 `scope` 字符串做隐式隔离——查记忆时必须构造正确的 scopes 列表，`tenant:acme` + `channel:c1` 的记忆不会被只有 `tenant:globex` 的查询捞到。隔离不靠行级权限，靠"查询方构造的 scope 必须包含目标 scope"。

2. **这个设计能不能直接搬到多租户**：**不能**，因为隐式安全依赖调用方"不犯错"——如果某个 API 忘了传 `tenant:xxx` scope，就查到了别的租户数据。在 B 端产品里这是不可接受的。

3. **我会怎么改**：
   - **强制 scope 注入**：不从调用方传 scope，而是从请求上下文（JWT / 请求头 / 线程变量）自动注入 tenant scope。调用方想不传都不行。
   - **双层校验**：MemoryStore 层做"实际查询的 scope 必须包含当前租户 scope"的断言，不满足直接拒绝。
   - **审计日志**：每次跨 scope 查询记一条审计，不是不让你查，而是查了就有据可查。

4. **关键权衡**：agent4j 的隐式安全追求"零配置、零运行时开销"——因为它是个人开源项目，调用方都是自己人。但 B 端产品的调用方是客户的代码，不能假设他们不犯错。所以隔离策略要从"约定"升级到"强制"。

---

### Q2. "客户说他们要给 Agent 的某些操作加审批流，但我们的 Agent 框架没有这个能力。你有没有做过 Agent 权限治理？"

**他想听什么**：你能不能把 GovernedToolExecutor 的设计迁移过来，并且适配他的审批流需求。

**回答思路**：

1. **agent4j 做了什么**：`GovernedToolExecutor` 在工具执行前插了 5 道闸门——权限检查 → 审批 → 限流 → 执行 → 结果清洗 → 审计。工具的 `@ToolPermission` 注解声明权限级别（AUTO / REQUIRES_APPROVAL / DENIED），审批服务异步阻塞等结果。

2. **你能直接搬什么**：
   - **闸门模型**：5 道闸门的顺序和职责可以直接复用
   - **审批粒度**：按工具 + 参数级别审批（不是所有"发邮件"都要审，但"发邮件给外部域名"要审）

3. **你需要改什么**：
   - **审批流对接**：agent4j 的 `ApprovalService` 是内存里的简单接口，B 端要对接客户的 OA 系统（飞书审批 / 钉钉审批 / 自建审批流）
   - **审批超时策略**：agent4j v1 没做审批超时，B 端必须有——超时 = 自动拒绝 + 通知
   - **审批与工作流的冲突**：agent4j 有两套暂停机制（GraphRuntime 的 PauseException 和 GovernedToolExecutor 的阻塞等待），你需要统一

4. **加分项**：主动提出"审批不仅是工具级别，还应该是数据级别"——同一个工具，操作自己租户的数据免审，操作别的租户的数据要审。agent4j 的 MemoryScope + GovernedToolExecutor 组合天然支持这个设计。

---

## 场景二：做 AI 编码助手（Cursor / Copilot 类）的面试官

> 我们做 AI 编码助手，Agent 能在用户本地执行代码、修改文件。
> 核心风险：LLM 生成的代码可能删库、泄露密钥、植入后门。
> 我们需要有人设计安全的代码执行沙箱。

### Q3. "我们的 Agent 要执行 LLM 生成的代码，你怎么保证安全？"

**他想听什么**：你理解"不可信代码执行"的安全模型，而不是泛泛而谈"沙箱"。

**回答思路**：

1. **先定性**：LLM 生成的代码属于"不可信输入"——不是"可能有 bug"，而是"可能有恶意意图"。安全模型必须假设最坏情况。

2. **agent4j 的两种沙箱**：
   - **ClassLoaderSandbox**：类加载器黑名单 + 白名单，适合"可信代码的类隔离"。**不适合不可信代码**——`Class.forName("java.lang.reflect.Proxy")` 就能逃逸，因为 Java 的双亲委派意味着子 ClassLoader 永远能看到父 ClassLoader 的类。
   - **ProcessSandbox**：fork 子进程执行，OS 级隔离。适合不可信代码，但开销大（1-2s/次 JVM 启动）。

3. **编码助手场景我会怎么选**：
   - **代码生成（只读分析）**：ClassLoaderSandbox 够用——只读操作没有破坏性，类隔离防止依赖冲突即可
   - **代码执行（有副作用）**：ProcessSandbox + 文件系统隔离——chroot / container 级别的文件系统只读挂载
   - **代码修改（最危险）**：必须 git diff + 人工确认——这不是技术问题，是流程问题。agent4j 的 `HumanApprovalNode` + `GovernedToolExecutor.REQUIRES_APPROVAL` 就是干这个的

4. **主动加分**：提出"渐进式信任模型"——新用户首次执行必须人工审批，执行 10 次无异常后自动升级为 AUTO，但涉及危险操作（rm / sudo / 网络请求）永远需要审批。这比"一刀切审批"体验好得多。

---

### Q4. "我们的 Agent 要支持长时间运行的任务（比如重构整个代码库），中途可能挂掉，怎么恢复？"

**他想听什么**：你理解"断点恢复"不只是"存个 checkpoint"，还涉及状态一致性。

**回答思路**：

1. **agent4j 的做法**：`GraphRuntime` + `Checkpoint`。每个 workflow 节点执行完都会 checkpoint，恢复时从上次完成的节点继续。`HumanApprovalNode` 抛 `PauseException` 暂停整个 Run，外部 resume 后从断点继续。

2. **但这只解决了 workflow 层面的恢复**。编码助手的难点是**文件系统状态的一致性**：
   - Agent 改了 3 个文件中的 2 个就挂了——第 3 个文件没改，但前 2 个已经改了
   - 恢复后 Agent 以为 3 个文件都改完了，实际只改了 2 个
   - 这比"内存状态丢失"严重得多——内存状态丢了可以重建，文件状态不一致需要人工排查

3. **我的方案**：
   - **事务性文件修改**：所有文件修改先写到临时目录，全部完成后原子性 commit（类似 git 的 `git add + git commit`）
   - **修改前自动 snapshot**：每次修改前 `git stash` 或 `git commit --allow-empty`，挂了可以 `git reset --hard` 回滚
   - **Checkpoint 包含文件状态哈希**：恢复时校验当前文件状态和 checkpoint 记录是否一致，不一致就提醒

4. **关键洞察**：断点恢复不是纯技术问题——它是"Agent 的认知和现实是否一致"的问题。agent4j 的 checkpoint 只恢复了 Agent 的认知（memory + workflow state），但编码助手的现实是文件系统，两者必须同步。

---

## 场景三：做 AI 客服 / 对话机器人的面试官

> 我们做 AI 客服，同时服务 10 万+用户。
> 现在的系统是同步的，一个用户在聊，其他用户排队。
> 我们需要改成异步 + 并发，同时每个用户的上下文不能串。

### Q5. "10 万用户同时和 Agent 聊天，你的架构怎么撑住？"

**他想听什么**：你能不能从"单 Agent 循环"思维切换到"高并发服务"思维。

**回答思路**：

1. **先拆问题**：10 万并发聊天不是"10 万个 Agent 同时跑"，而是"10 万个对话状态同时活着，但同一时刻只有几千个在等 LLM 响应"。瓶颈在 LLM API 的 QPS，不在 Agent 本身。

2. **agent4j 的现状**：`SharedAgentSession.speak()` 是 `synchronized` 的——一个用户在聊，其他用户阻塞。这是 v1 的"粗但安全"选择，但绝对不能上生产。

3. **我的架构方案**：
   - **对话状态外置**：`AgentState`（消息历史 + 工具状态）存 Redis / DynamoDB，不存 JVM 堆。10 万对话 = 10 万个 Redis key，没问题。
   - **Agent 无状态化**：Agent 只是一个处理函数 `state → (action, new_state)`，不持有对话状态。每个请求从 Redis 加载 state，处理完写回。
   - **LLM 调用异步化**：LLM 调用走异步 HTTP client（Java 21 Virtual Threads 或 WebClient），不阻塞线程。
   - **工具执行队列化**：工具调用走消息队列（Kafka / SQS），工具执行结果异步回调。

4. **主动提坑**：agent4j 的 `MemoryContextBuilder` 每轮都会查记忆——在 10 万并发下，记忆查询的延迟会成为瓶颈。需要加 LRU 缓存：同一用户 5 分钟内的重复查询命中缓存，不走存储。

---

### Q6. "我们的客服 Agent 要同时服务多个业务线（售前、售后、技术支持），怎么保证它不串角色？"

**他想听什么**：你理解"身份隔离"不只是换 system prompt。

**回答思路**：

1. **agent4j 的经验**：`SharedAgentSession.handoff()` 支持在不同用户之间切换 owner——但它是通过 system message `[handoff] from A to B` 实现的，LLM 可能忽略。

2. **客服场景的核心问题**：不是"Agent 会不会串"，而是"Agent 的记忆会不会串"：
   - 售前对话里客户说了预算，这个信息能不能被售后 Agent 看到？
   - 技术支持对话里的 bug 详情，能不能被售前 Agent 拿来推销？
   - 答案取决于业务规则，不是技术能力

3. **我的方案**：
   - **业务线 = MemoryScope**：售前/售后/技术支持各有独立的 scope，Agent 切换业务线时自动切换 scope，看不到其他业务线的记忆
   - **跨业务线共享 = 显式授权**：客户同意共享的信息（如订单号）写入共享 scope，所有业务线可见
   - **角色切换 = 重新初始化上下文**：不是发一条 system message 说"你现在是售前"，而是清空当前上下文 + 加载新业务线的 system prompt + 注入新 scope 的记忆

4. **对比 agent4j 的 handoff**：agent4j 的 handoff 改了 owner 但没改 scope——这在多人协作场景是对的（大家共享频道记忆），但在客服场景是错的。**同一个机制，不同场景，不同策略。**

---

## 场景四：做 AI Agent 平台（Coze / Dify 类）的面试官

> 我们做 Agent 构建平台，用户拖拽式搭建 Agent，然后一键发布。
> 现在要加"工作流"能力——用户可以定义 Agent 执行的步骤和分支。
> 我们的图引擎做得太简单了，需要重新设计。

### Q7. "用户要定义 Agent 工作流：条件分支、人工审批、并行执行、错误重试。你的图引擎能支持吗？限制在哪？"

**他想听什么**：你能不能把 agent4j 的 GraphRuntime 经验迁移到"用户拖拽定义工作流"的场景。

**回答思路**：

1. **agent4j 的 GraphRuntime 能做什么**：
   - 7 种节点（LLM / 工具 / 条件 / 审批 / 子图 / 开始 / 结束）
   - 条件边（`Predicate<WorkflowState>`）
   - Checkpoint + PauseException 暂停恢复
   - 单 cursor 串行执行

2. **哪些能力直接能搬**：
   - 节点抽象（Node + NodeResult）
   - Checkpoint 机制
   - 暂停恢复（HumanApprovalNode → 人工审批）

3. **哪些能力需要大改**：
   - **条件边用 Java Predicate** → 用户拖拽界面不能写 Java 代码，需要 DSL 或 JSON 描述条件
   - **单 cursor** → 不支持并行分支，用户画了并行分支跑不了
   - **PauseException 暂停** → 用户平台的审批可能要对接微信/钉钉通知，不只是 API resume

4. **我会怎么设计"用户可拖拽的工作流"**：
   - **边定义改为 JSON DSL**：`{"type": "condition", "expr": "amount > 1000", "target": "highRiskPath"}`，运行时编译为 Predicate
   - **多 cursor 执行器**：并行分支 = 同时推进多个 cursor，汇合时用 Join 节点等待所有分支完成
   - **节点市场**：用户不只是拖 LLM/工具节点，还能拖"发钉钉通知""调用 Webhook""等 10 分钟"等业务节点——agent4j 的 SPI 热插拔机制天然支持

5. **主动提风险**：并行分支 + 暂停恢复的组合是最大的坑——一个分支暂停了，另一个分支要不要继续？如果继续，暂停分支恢复后的状态可能和已经完成的分支不一致。agent4j v1 选择"不做并行"就是避了这个坑，但平台产品避不了。

---

### Q8. "我们的用户想要 Agent 能'记住'上次对话的内容，但不同用户之间不能串。你做过 Agent 记忆系统吗？"

**他想听什么**：你理解"记忆"不只是存个聊天记录，还涉及隔离、生命周期、检索质量。

**回答思路**：

1. **agent4j 的记忆系统设计**：
   - **MemoryScope 做隔离**：`user:alice` + `session:s1` 的记忆只有同时包含这两个 scope 的查询才能看到
   - **三种生命周期**：工作记忆（当次对话）/ 会话记忆（跨轮次）/ 长期记忆（永久）
   - **MemoryPolicy 做治理**：channel scope 的记忆要待审（PENDING_REVIEW），防止有害内容进入共享记忆

2. **平台场景需要加什么**：
   - **记忆配额**：免费用户最多 1000 条长期记忆，付费用户 10000 条。agent4j 的 InMemoryMemoryStore 没有配额概念
   - **记忆自动压缩**：对话超过 50 轮，老记忆自动摘要。agent4j 有 ContextCompressor 但只压缩上下文，不压缩记忆存储
   - **记忆搜索质量**：用户说"上次聊的那个 bug"，Agent 要能从记忆里搜到。agent4j 的记忆查询是 scope 过滤，没有语义搜索——需要加 embedding 向量检索

3. **主动提权衡**：语义搜索 vs scope 隔离存在矛盾——向量检索天然是"跨 scope 的相似度匹配"，但隔离要求"不同 scope 不可见"。解决方案：**先 scope 过滤再向量检索**，不要在向量数据库里做全局搜索再过滤——后者会泄露隔离信息。

---

## 场景五：做内部 AI 工具的面试官（大厂 infra 团队）

> 我们内部有几十个 AI Agent，给不同业务线用。
> 现在的问题是：Agent 调 LLM 的成本失控了，每个月几十万，不知道钱花在哪了。
> 我们需要成本治理和可观测性。

### Q9. "几十个 Agent 在跑，LLM 调用成本完全不可见。你怎么做 Agent 的成本可观测？"

**他想听什么**：你能不能从"写 Agent"切换到"管 Agent"的视角。

**回答思路**：

1. **agent4j 做了什么**：`ObservingModelClient` 在 ModelClient 装饰器链的最外层，每次 LLM 调用记录 token 用量 + 延迟 + 模型名。`FiveDimensionBudget` 做五维预算控制（token / 请求 / 金额 / 时间 / 工具调用次数）。

2. **但可观测 ≠ 成本治理**：agent4j 的 Observing 记录了"花了多少"，但没有"该花多少"的预算控制。FiveDimensionBudget 是单次请求级别的预算，不是月度成本预算。

3. **我的方案**：
   - **调用链路标记**：每个 LLM 调用带 `agent_id` + `task_type` + `business_line` 标签，类似 OpenTelemetry 的 trace 属性
   - **实时成本聚合**：按标签维度实时聚合成本，写入时序数据库（Prometheus / InfluxDB）
   - **成本告警**：单 Agent 日成本 > 阈值 → 告警；单业务线月成本 > 预算 → 自动降级（换便宜模型 / 限流 / 暂停）
   - **成本归属**：每个 Agent 的成本自动归属到对应的业务线，月底出账单

4. **主动提 agent4j 的设计优势**：装饰器链让成本观测"零侵入"——只要把 `ObservingModelClient` 放在装饰器链最外层，所有内层的 Retry / Fallback / Routing 的实际调用都会被记到外层的"一次请求"里，不会重复计数。如果组装顺序反了，metrics 会膨胀 2-3 倍——这是 agent4j 文档里特别强调的坑。

---

### Q10. "我们的 Agent 有时候会用错模型——简单问题调了 GPT-4，贵且慢。你怎么做模型路由？"

**他想听什么**：你理解模型路由不只是"简单问题用便宜模型"。

**回答思路**：

1. **agent4j 的做法**：`RoutingModelClient` 根据请求特征（prompt 长度 / 工具数量 / 标签）选择模型。`FallbackModelClient` 在主模型失败时切备用模型。

2. **模型路由的难点不是"怎么路由"，而是"怎么知道路由对了"**：
   - 简单问题用便宜模型 → 省了钱，但如果便宜模型回答质量差呢？
   - 复杂问题用贵模型 → 质量好，但 80% 的请求是简单问题，成本爆炸
   - 你需要一个"路由决策的质量反馈闭环"

3. **我的方案**：
   - **三级路由**：简单（模板匹配 / FAQ）→ 中等（小模型 + RAG）→ 复杂（大模型 + 工具 + 多轮推理）
   - **自动升级**：小模型连续 2 次被用户"不满意"（显式反馈或隐式信号如重新提问），自动升级到大模型
   - **成本-质量平衡**：设置"单次对话最大模型成本"，超过就强制降级。agent4j 的 `FiveDimensionBudget` 可以做到单次请求级别的预算拦截
   - **路由日志 + A/B 测试**：同一类请求同时走两条路由，对比质量和成本，持续优化路由策略

4. **关键洞察**：模型路由本质上是"成本优化"问题，不是"技术架构"问题。agent4j 提供了路由的基建（RoutingModelClient + FallbackModelClient + FiveDimensionBudget），但路由策略（什么请求走什么模型）是业务决策，需要数据和实验来驱动。

---

## 场景 Bonus：通用架构能力（任何 Agent 团队都会问的）

### Q11. "你说你的框架是'不依赖 Spring'的，为什么？我们公司用 Spring 生态，你能不能和 Spring 整合？"

**他想听什么**：你理解"零依赖"是设计选择，不是排斥。

**回答思路**：

1. **为什么不用 Spring**：
   - agent4j 的核心模块（agent-core / agent-model / agent-workflow）定位是**库（library）**，不是**框架（framework）**
   - 库应该"尽量少依赖"，让使用者自己选框架；框架才应该"全面集成"
   - 不依赖 Spring = 不依赖 Spring Boot = 不依赖 Servlet 容器 = 可以在 GraalVM native image / Android / 任何 JVM 上跑

2. **但我们做了 Spring Boot Starter**：
   - `agent-spring-boot-starter` 是唯一依赖 Spring 的模块，读 `agent4j.*` 配置，自动创建 `ModelClient` 和 `AgentFactory` bean
   - Spring 用户加一个依赖就能用，非 Spring 用户直接 `new SimpleAgent()` 也能用
   - **两种用法共享同一套核心**——Spring 只是自动配置层，不是核心的一部分

3. **如果你们深度用 Spring**：我会在 starter 里加更多自动配置——比如把 `AgentState` 存 Spring Session、把 `MemoryStore` 存 Spring Data Redis、把审批流对接 Spring Security。但这些都在 starter 层，核心模块仍然零依赖。

---

### Q12. "你的框架和 LangChain4j / Spring AI 有什么区别？我们为什么要选你的？"

**他想听什么**：不是让你拉踩竞品，而是看你是否理解"不同框架解决不同问题"。

**回答思路**：

1. **定位不同**：
   - LangChain4j / Spring AI：**应用开发框架**——帮你快速搭建一个能跑的 Agent
   - agent4j：**运行时基础设施**——帮你管一个在生产的 Agent（断点恢复、工具治理、沙箱、轨迹导出）

2. **类比**：
   - LangChain4j / Spring AI = Spring MVC：帮你写 web 应用
   - agent4j = Kubernetes：帮你运行和治理 web 应用

3. **不是替代，是互补**：
   - 用 LangChain4j 写 Agent 的业务逻辑（prompt / tool / chain）
   - 用 agent4j 管 Agent 的运行时（权限 / 审批 / 沙箱 / 成本 / 恢复）

4. **但如果只选一个**：
   - PoC / 原型 / 个人项目 → LangChain4j / Spring AI，上手快
   - 生产 / 企业 / 多租户 → agent4j，治理能力强

---

## 速查表：12 题业务映射

| # | 面试官场景 | 业务痛点 | 你要迁移的 agent4j 能力 |
|---|-----------|---------|----------------------|
| Q1 | 企业 AI 平台 | 多租户数据串 | MemoryScope 隐式隔离 → 升级为强制 scope 注入 + 双层校验 |
| Q2 | 企业 AI 平台 | Agent 操作要审批 | GovernedToolExecutor 5 道闸门 → 对接 OA 审批流 + 数据级审批 |
| Q3 | AI 编码助手 | 不可信代码执行 | 双沙箱（ClassLoader/Process） → 渐进式信任模型 |
| Q4 | AI 编码助手 | 长任务断点恢复 | GraphRuntime Checkpoint → 加事务性文件修改 + 状态哈希校验 |
| Q5 | AI 客服 | 10 万并发 | SharedAgentSession → 对话状态外置 + Agent 无状态化 + 异步 LLM |
| Q6 | AI 客服 | 多业务线串角色 | handoff → 业务线 = MemoryScope + 切换时重新初始化上下文 |
| Q7 | Agent 平台 | 用户拖拽工作流 | GraphRuntime → JSON DSL 条件边 + 多 cursor + 节点市场 |
| Q8 | Agent 平台 | Agent 记忆管理 | MemoryScope + MemoryPolicy → 加配额 + 压缩 + 语义搜索 |
| Q9 | 大厂 infra | LLM 成本失控 | ObservingModelClient → 调用链路标记 + 实时聚合 + 成本告警 |
| Q10 | 大厂 infra | 模型路由 | RoutingModelClient + FiveDimensionBudget → 三级路由 + 自动升级 |
| Q11 | 任何公司 | 为什么不用 Spring | 库 vs 框架的定位 → Spring Boot Starter 兼顾两种用户 |
| Q12 | 任何公司 | vs LangChain4j | 运行时基础设施 vs 应用开发框架 → 互补而非替代 |
