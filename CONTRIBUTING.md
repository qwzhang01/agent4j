# Contributing to agent4j

Thank you for contributing to [agent4j](https://github.com/qwzhang01/agent4j), a Java 17 agent runtime.

Read this file before you open a pull request. The community channels are [GitHub Issues](https://github.com/qwzhang01/agent4j/issues) and [GitHub Discussions](https://github.com/qwzhang01/agent4j/discussions). Vulnerability reports go through [SECURITY.md](SECURITY.md), not a public issue. The [code of conduct](CODE_OF_CONDUCT.md) applies to every channel.

## What counts as the contract

User-facing behavior lives in javadoc and in [`docs/`](docs/). [`notes/`](notes/) is a design journal, mostly in Chinese. Do not treat it as a specification, and do not move a contract into it.

## Prerequisites

- JDK 17 or later. A JDK 8 `JAVA_HOME` fails the enforcer and the javadoc `--release 17` flag.
- Maven 3.9+, or the bundled wrapper (`./mvnw`). Prefer the wrapper.

## Build

```bash
./mvnw -B verify
```

The suite covers all 22 reactor modules and should stay green. `mvn -B verify` is fine when the wrapper is not available.

## Run one example

From a fresh clone, build the example and the modules it needs:

```bash
./mvnw -pl examples -am compile exec:java \
  -Dexec.mainClass=io.github.qwzhang01.agent.examples.MockAgentExample
```

Most examples use `MockModelClient` and do not call a live model. The index is [examples/README.md](examples/README.md).

## Code style

- Java 17. New code stays in `io.github.qwzhang01.agent.*`.
- Do not add a Spring dependency outside `agent-spring-boot-starter`.
- Follow the existing decorator and module boundaries. Do not invent module names.

Library modules:

`agent-core`, `agent-model`, `agent-plugin`, `agent-sandbox`, `agent-workflow`, `agent-scheduler`, `agent-memory`, `agent-security`, `agent-mcp`, `agent-orchestrator`, `agent-channel`, `agent-product`, `agent-trace-export`, `agent-enterprise`, `agent-tavern`, `agent-chat`, `agent-coding`, `agent-observability`, `agent-otel-export`, `agent-spring-boot-starter`.

`seven-agent-bom` aligns their versions. `examples` is not published.

## Tests

- A behavior change needs a test.
- Do not weaken fail-closed tests: sandbox isolation, permissions, injection sanitizer.
- Prefer a mock client unless the change truly needs a live model.

## Pull requests

- One concern per pull request.
- Open an issue before a large refactor.
- If the change is user-visible, add a note under `[Unreleased]` in `CHANGELOG.md`, and update `docs/` or javadoc when the contract changed.
- Never commit secrets, API keys, or credentials.

By contributing, you agree that your contributions are licensed under the Apache License 2.0.

## 中文摘要

欢迎贡献。请先用 JDK 17+ 和 `./mvnw -B verify` 跑通测试。行为变更必须带测试，不要削弱沙箱 / 权限 / 注入防御的 fail-closed 用例。一次 PR 只做一件事；大重构请先开 Issue。`notes/` 是学习笔记，不是 API 契约。社区渠道是 GitHub Issues 与 Discussions。安全漏洞走 [SECURITY.md](SECURITY.md)，不要开公开 Issue。
