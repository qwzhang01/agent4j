# Documentation

These guides are the user-facing contract, in English. Chinese translations sit beside them as `*.zh-CN.md`.

Behavior you can rely on is defined here, in the public API, and in the tests. The [`notes/`](../notes/) directory is a design journal. [`harness-contract.md`](harness-contract.md) is an internal engineering note in Chinese.

| Guide | Read it when |
|-------|----------------|
| [Getting started](getting-started.md) | Adding the library, running the mock example, or wiring Spring Boot |
| [Core concepts](concepts.md) | Learning the runtime: agent, tool, loop, workflow, memory, governance, checkpoint |
| [Modules](modules.md) | Choosing Maven artifacts |
| [Comparison](comparison.md) | Deciding between this runtime and LangChain4j, Spring AI, or AgentScope |
| [Limitations](limitations.md) | Checking the security boundary and what `0.1.5` deliberately does not include |

Runnable programs: [examples/README.md](../examples/README.md). Start with `MockAgentExample`.
