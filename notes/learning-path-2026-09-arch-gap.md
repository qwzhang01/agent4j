# 学习路径：Agent 架构知识缺口补齐（2026-09）

> 来源：2026-09-08 业界对照分析（agent4j vs 2026 业界主流框架）。
> 性质：个人学习计划，不是设计蓝图。与 `architecture-stance-*` 立场卡体系配合使用：每个实验完成后写一张五字段立场卡。
> 前置结论：agent4j 核心设计与业界生产共识高度一致（治理收口、流式合一、状态执行分离），真正的差距在「框架之外的那一半」。

## 一、为什么有这份路径

2026-09-08 与 AI 做了一次业界对照，结论是：不缺「怎么写 Agent 框架」，缺的是三块：

1. **协作范式视野**：handoff / A2A 这类「Agent 之间怎么协作」的范式，agent4j 目前只有中央 orchestrator 图编排一种世界观。
2. **成本工程**：model routing / prompt caching / token budgeting 三个杠杆，是把框架从「正确」带到「便宜且可经营」的关键。
3. **运维思维**：在线评估、分布式执行，是从「能跑」到「能经营」的分水岭。

业界坐标系（2026 格局）速查：

| 流派 | 代表 | 核心隐喻 | 生产定位 |
|------|------|----------|----------|
| 图状态机 | LangGraph、Mastra | 流程图 | 复杂状态工作流的事实标准，checkpoint + time-travel |
| 角色协作 | CrewAI、AutoGen/AG2、MS Agent Framework | 任务板 / 群聊 | 快速原型、内容与研究流水线 |
| SDK 原语 | OpenAI Agents SDK、Claude Agent SDK | 接力赛（Handoff） | 单 Agent 增强与轻量多 Agent 串联 |

业界五条生产共识（行业宪法）：Workflow 优先 Agent 兜底、Context Engineering 是新学科、记忆三层 + 失效机制、治理是独立平面、协议层收敛（MCP + A2A）。

## 二、知识缺口清单（按优先级）

### 第一优先级：范式盲区（决定框架表达能力上限）

| # | 缺口 | 核心问题 | 关键概念 |
|---|------|----------|----------|
| 1 | Handoff 范式 | 接力棒 vs 中央指挥的边界在哪 | OpenAI Agents SDK 的控制权转移语义，8-10 个 Agent 以内的分诊路由场景 |
| 2 | A2A 协议 | agent-to-agent 标准化 | Agent Card 能力发现、task 生命周期、跨厂商委托；MCP 是手，A2A 是握手 |
| 3 | Guardrails 三层论 | 治理只做了中间层 | input guardrail（进 Agent 前）/ tool governance（已有）/ output guardrail（出给用户前） |

### 第二优先级：成本与上下文工程（生产竞争力分水岭）

| # | 缺口 | 核心问题 | 关键概念 |
|---|------|----------|----------|
| 4 | Model Routing / Cascading | 按步骤难度动态选模型 | 路由用小模型、规划用大模型，业界实测省 60-80%；agent4j 装饰器体系已留好插槽（RoutingModelClient 可以是下一个装饰器） |
| 5 | Prompt Caching | 缓存反过来约束上下文设计 | 稳定前缀（system prompt、工具定义）放前面，易变内容放后面；compaction 决策要和缓存命中联动 |
| 6 | Token Budgeting | 上下文窗口当预算管理 | 系统提示预留、工具定义预留、历史动态分配、输出留 headroom；业界已做成运行时一等指标 |

### 第三优先级：评估与运维（从能跑到能经营）

| # | 缺口 | 核心问题 | 关键概念 |
|---|------|----------|----------|
| 7 | 在线评估体系 | CI 思维 → 持续监控 | task completion rate、cost per task、latency P50/P95、safety violation 计数、drift detection |
| 8 | 分布式执行 | 单 JVM checkpoint 的天花板 | durable execution as a service、Actor 模型、跨进程 checkpoint 恢复；state 序列化已做对，缺 runtime 分布式化 |

### 第四优先级：安全深水区

| # | 缺口 | 核心问题 | 关键概念 |
|---|------|----------|----------|
| 9 | 沙箱谱系 | 什么时候必须升级到哪一级 | Docker → microVM（Firecracker）→ WASM 的取舍；目前停在 ClassLoader + Process 并已写进 limitations |
| 10 | Prompt Injection 纵深防御 | 工具输出净化之外的层 | 间接注入（检索内容里的攻击）、指令与数据分离标记、身份最小化 |

## 三、四周学习计划

### 第 1 周：一手文献（不看二手博客）

- [ ] Anthropic《Building Effective Agents》
- [ ] OpenAI《A Practical Guide to Building Agents》
- [ ] MCP 规范全文，重点：OAuth / sampling / elicitation
- [ ] A2A 规范全文
- [ ] Anthropic context engineering 系列博客

读完标准：第一优先级的空白填掉一半；能不看资料说出 handoff 与 orchestrator 的适用边界。

### 第 2 周：读竞品源码（带着问题读）

LangGraph 读三样：

- [ ] checkpointer 接口（对照 agent4j 的 Checkpoint 决策 8）
- [ ] interrupt / resume 实现（对照决策 9：compaction 改写 state）
- [ ] time-travel 调试怎么做（agent4j 暂无对应物，评估是否值得补）

OpenAI Agents SDK 读两样：

- [ ] handoff 的上下文转移语义（接力时历史怎么搬）
- [ ] guardrail 的执行时机（input/output 两层怎么插桩）

Claude Agent SDK 读两样：

- [ ] hooks 生命周期（对照 agent4j 的 ContextBuilder hook 决策 6）
- [ ] subagent 的上下文隔离（对照 agent-memory 的 MemoryScope）

### 第 3-4 周：在 agent4j 上做三个实验（知识内化的唯一方式）

| 实验 | 内容 | 验证的问题 | 产出 |
|------|------|-----------|------|
| E1 HandoffLoop profile | 在 Loop 抽象上表达接力范式 | 接力范式在 agent4j 能否自然表达，还是需要新抽象 | 立场卡：handoff vs orchestrator 边界 |
| E2 RoutingModelClient | 按 step 类型路由大小模型的装饰器 | 装饰器体系是否足以承载成本工程；顺手测真实成本差 | 立场卡：路由策略放哪一层 |
| E3 Prompt caching 支持 | 稳定前缀约定写进 ContextBuilder 契约 | 上下文设计与缓存命中的耦合怎么处理 | 立场卡：前缀稳定性契约 |

每个实验写一张五字段立场卡（我的选择 / 对比替代 / 代价 / 什么场景会改 / 证据），格式见 `architecture-stance-skeleton.md`，编号从 23 开始接续现有 22 条决策。

## 四、完成判据

1. 能脱稿回答：「handoff 和 orchestrator 各自的死穴是什么」。
2. E2 实验给出真实成本数据（大小模型混合 vs 全大模型，至少一组对照）。
3. 三张立场卡全部通过自检（代价具体到谁付什么量级、反例场景真实存在）。
4. Guardrails 三层论能在 agent4j 里指出 input / output 两层各自的插入点位置。

## 五、文献与链接清单

- [Anthropic: Building Effective Agents](https://www.anthropic.com/research/building-effective-agents)
- [OpenAI: A Practical Guide to Building Agents](https://cdn.openai.com/business-guides-and-resources/a-practical-guide-to-building-agents.pdf)
- [MCP 规范](https://modelcontextprotocol.io)
- [A2A 协议（Linux Foundation）](https://a2a-protocol.org)
- [Anthropic: Effective Context Engineering for AI Agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Agentic AI Frameworks: A 2026 Comparison Guide](https://agentswarms.fyi/blog/agentic-ai-frameworks-comparison-guide)
- [Shipping AI Agents to Production: A 2026 Context Engineering Recipe Book](https://www.pento.ai/blog/shipping-ai-agents-to-production-recipe-book)
- [Building Production AI Agents: What Actually Works in 2026](https://www.devpick.io/blog/building-production-ai-agents-2026)

## 六、与既有笔记的关系

- 知识缺口第 4/6 项与 `stage-18-article-4-model-routing.md`、`stage-18-article-5-token-budget.md` 已有文章重叠：那两篇是「写了什么」，本路径关注「还缺什么、去哪补」。
- 实验产出回到 `architecture-stance-*` 立场卡体系，不新开格式。
- 沙箱谱系（缺口 9）与 `dsh-sandbox-analysis.md`、决策 21 相关，学习时对照读。
