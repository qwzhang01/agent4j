# Contributing to agent4j

## 中文摘要

欢迎贡献。请先用 JDK 17+ 和 `./mvnw -B verify` 跑通测试。行为变更必须带测试，不要削弱沙箱 / 权限 / 注入防御的 fail-closed 用例。一次 PR 只做一件事；大重构请先开 Issue。`notes/` 是学习笔记，不是 API 契约。社区渠道是 GitHub Issues 与 Discussions。

---

Thank you for contributing to [agent4j](https://github.com/qwzhang01/agent4j), a Java 17 Maven multi-module Agent runtime.

Community channels: [GitHub Issues](https://github.com/qwzhang01/agent4j/issues) and [GitHub Discussions](https://github.com/qwzhang01/agent4j/discussions).

## Prerequisites

- JDK 17 or later
- Maven 3.9+ or the bundled wrapper (`./mvnw`)

## Build

Prefer the wrapper:

```bash
./mvnw -B verify
```

Fallback if the wrapper is unavailable:

```bash
mvn -B verify
```

The suite includes 1155 tests. `./mvnw verify` and `mvn test` should stay green.

## Run one example

Install modules once (skip tests if you already verified):

```bash
mvn install -DskipTests
```

Then run:

```bash
mvn -pl examples compile exec:java -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

Most examples use mock clients and do not need a real LLM.

## Code style

- Java 17; keep new code in `io.github.qwzhang01.agent.*`
- Do not add a new Spring dependency
- Follow existing decorator and module boundaries
- Library modules (do not invent names): `agent-core`, `agent-model`, `agent-plugin`, `agent-sandbox`, `agent-workflow`, `agent-scheduler`, `agent-memory`, `agent-security`, `agent-mcp`, `agent-orchestrator`, `agent-channel`, `agent-product`, `agent-trace-export`, `agent-enterprise`, `agent-tavern`, `agent-coding`, `agent-observability`, plus `examples`

## Tests

- Behavior changes require tests
- Do not weaken fail-closed security or sandbox tests (permissions, injection sanitizer, sandbox isolation)
- Prefer mock clients unless the change truly needs a live model

## Pull requests

- One concern per PR
- Open an issue before large refactors
- Keep user-facing contract in javadoc and `docs/` — not in `notes/`
- `notes/` is learning material, not a specification
- If the change is user-visible, add a note under `[Unreleased]` in `CHANGELOG.md`
- Never commit secrets, API keys, or credentials

## License

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
