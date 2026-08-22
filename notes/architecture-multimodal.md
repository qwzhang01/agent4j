# 多模态能力如何接到已有治理与长任务

> 状态：✅ 已实现（2026-08-22）
> 范围：读图（Vision）/ 生图 / 生视频。不新开 Stage，复用 Stage 1 装饰器、Stage 7 调度、Stage 9 治理。

---

## 1. 边界

```text
读图（理解）  → ModelClient + ChatMessage.parts     用户入口：SimpleAgent.run(ChatMessage)
              → VisionTool (describe_image)         模型中途要看图：走 Tool，才能被治理

生图（同步）  → ImageGenerationClient + ImageGenerationTool
              → Retry / Timeout 装饰器（与 ModelClient 同一套）
              → GovernedToolExecutor（默认 REQUIRES_APPROVAL）

生视频（异步）→ VideoGenerationClient + VideoGenerationTool（默认不阻塞）
              → GenerationTaskListener → GenerationTaskCoordinator
              → AsyncTaskQueue + 轮询 + EventBroker.fire("video-done:{id}")
              → WaitEventNode.fromState 自动 resume
```

不把生图/生视频塞进 `ModelClient.chat()`。对话是理解，生成是副作用，走 Tool。

---

## 2. 治理（Stage 9）

`ToolPolicy.applyGenerationDefaults()` 把三个工具设为 `REQUIRES_APPROVAL`：

| 工具 | 常量 | 默认权限 |
|---|---|---|
| `describe_image` | `GenerationTools.DESCRIBE_IMAGE` | REQUIRES_APPROVAL |
| `generate_image` | `GenerationTools.GENERATE_IMAGE` | REQUIRES_APPROVAL |
| `generate_video` | `GenerationTools.GENERATE_VIDEO` | REQUIRES_APPROVAL |

注册进 `GovernedToolExecutor` 后，审批 / 限流 / 净化 / 审计自动生效，与 MCP 工具同一条链路。

---

## 3. 长任务（Stage 6/7）

视频默认 `wait=false`：ReAct 循环只拿到 task id，不占 10 分钟。

```text
VideoGenerationTool.submit
    → GenerationTaskListener.onSubmitted
    → GenerationTaskCoordinator.trackVideo
        → enqueue AsyncTask
        → TaskScheduler.schedule(poll)
        → status == done → fire("video-done:{taskId}", VideoTask)
    → WaitEventNode.fromState("wait-video", "eventKey") 恢复 Workflow
```

`wait=true` 仍可用，但是显式选择，不是默认。

---

## 4. 入口

- 用户带图提问：`agent.run(ChatMessage.userWithImage(text, url))`
- Agent 自己看图 / 生图 / 生视频：注册三个 Tool + `GovernedToolExecutor`
- Checkpoint：`ContentPart` 带 Jackson `@JsonTypeInfo`，多模态消息可序列化

验收示例：`examples/MultimodalExample`。
