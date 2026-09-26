# 和其他 Java Agent 库比什么

本项目**不是** LangChain4j、Spring AI 或 AgentScope Java 的替代品。选型时先问：你要的是「把 LLM 接到 Java 应用」，还是「一个可停、可审、可回放的 Agent Runtime」。

一句话差异：本项目把力气花在**持久化执行、工具治理、沙箱、轨迹导出、同一 Runtime 上的多 Profile**（企业 / 酒馆 / 编码）。Java 生态在 durable execution 和 tool governance 上通常更薄。另一半目标是**通过造 Runtime 学架构**。

## LangChain4j

LangChain4j 覆盖面广：模型适配、RAG 组件、记忆、工具绑定、和 Spring 的整合都成熟，社区和文档更好。适合尽快把 chat / RAG / tool 接到现有服务。

本项目不和它对「谁的 connector 更多」。`ModelClient` 在 v1 只有 Mock、OpenAI-compatible、Anthropic。差距在执行层：Checkpoint 续跑、权限三档 + 审批 + 审计、ClassLoader/Process 沙箱、S-A-O-R-D 轨迹 / DPO 导出、以及企业 / 酒馆 / 编码三条 Profile。

已有 LangChain4j 应用不必换。需要把一次 Run 当成可治理、可恢复的工作流时，再评估本 Runtime。

## Spring AI

Spring AI 跟 Spring 生态绑在一起：自动配置、Advisor、评测与可观测的起步成本低。如果你的系统已经是 Spring Boot，它是默认选项。

本库的 **core / model / 运行时模块不依赖 Spring Framework**。`Agent` / `Tool` / Loop 是普通 Java。可选模块 `agent-spring-boot-starter` 提供 `application.yml` 绑定、`ModelClient` bean 和 `AgentFactory`（不自动创建单个 `Agent` bean，也不带 Actuator）。v1 也没有 OpenTelemetry SDK。

要「Spring 里快速接模型」选 Spring AI。要「Spring 可有可无、治理和断点恢复是一等公民」再看这里。

## AgentScope Java

AgentScope（含 Java 实现）强调多 Agent 消息传递、会话与研究型编排，和「搭一个对话系统 / 多智能体实验」更近。

本项目的编排（`agent-orchestrator`）是 Supervisor / Worker 并行派发 + 进程内 A2A，不是通用消息总线。频道层解决的是身份交集、共享会话、任务接力、Ambient 推送。MCP 在 v1 只有 stdio。

两者都「能跑多 Agent」，但本项目把多 Agent 放在可治理 Runtime 之上，而不是以对话框架为中心。

## 什么时候不该用本项目

- 只要一个 chat completion 封装，或只要 Spring 自动配置。
- 需要丰富的现成 RAG / 向量库 / 评估平台，且不想自己装。
- 需要 Docker/WASM 沙箱、MCP SSE、HTTP A2A、真 Git、OTel、训练闭环——这些是 [v1 非目标](limitations.md)。
- Portal 完成 Publish 之前，只能从源码 `mvn install`，没有 Central 稳定版。

## 什么时候值得试

- 工具必须过权限 / 审批 / 审计，失败也要留痕。
- 流程会停（人、事件、定时），停了还要原状态恢复。
- 需要轨迹 JSONL / DPO，而不是只打 access log。
- 同一套 Loop + 治理要服务企业助手、游戏回合、编码修复环。
- 想在 Java 里把 Agent Runtime 的边界走一遍（本仓库也是学习项目）。
