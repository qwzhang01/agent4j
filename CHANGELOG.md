# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The current Maven version is `0.1.0`.

## [Unreleased]

## [0.1.0] - 2026-09-01

### Added

- Apache License 2.0 (`LICENSE`) and `NOTICE`
- Community files: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `RELEASING.md`
- GitHub issue and pull request templates
- User-facing documentation under `docs/`
- Product README (stage diary archived to `notes/v1-development-log.md`)
- CI workflow for Maven verify (JDK 17 / 21)
- Bill of Materials (`seven-agent-bom`) for coordinated dependency versions
- Maven Wrapper (`./mvnw`, Maven 3.9.9)
- Maven Central `release` profile: GPG signing + `central-publishing-maven-plugin` (`examples` excluded)
- Agent-level streaming: `Agent.stream(userInput, listener)` emits `AgentEvent` (content deltas, tool start/finish, Done, Error) while `ReActAgentLoop` consumes `ModelClient.stream`. `run()` is unchanged.
- Optional Spring Boot starter module (`agent-spring-boot-starter`): `agent4j.*` config, `ModelClient` bean, `AgentFactory`; core stays Spring-free
- Example: `StreamingAgentExample`
- Room conversation engine (`agent-chat`): `ChatRoom` / `ChatEngine`, `SoloSpeaker` + `MentionSpeaker`, `PersonaSource` / `HistorySource` / `ExtraTextSource` / optional `MemorySource`, `ChatListener`. Default `maxSteps` 1, no tools, no tavern dependency. Example: `ChatRoomExample`
- Optional `DirectorSpeaker` for group rooms: host-supplied director instructions plus a separate `ModelClient` pick one answering persona per turn. Default `DirectorChoiceParser.memberId()`; composable as `MentionSpeaker` fallback. Not wired in Moonlit yet.
- Character-engine eval scripts (`CharacterEvalTest`): cross-turn subject recall, group pair-scope isolation, persona text unchanged. MockModelClient only; no LLM-as-judge.
- Optional `ConsistencyGuard` on `ChatEngine` / `ChatRoom`: after Done, host checks persona + reply and may warn. Default no-op. Warnings do not rewrite history. Implementations (rules or LLM) stay with the host.
- Optional `RelationSource` for `ChatRoom`: host supplies a `RelationSnapshot` (free-form stage + slots + optional note). The engine injects and does not score. A `Supplier` is re-read every turn. Not mounted by `ContextAssembler.defaults()`. Pre-rendered blobs may still use `ExtraTextSource`.
- Optional `LoreSource` for `ChatRoom`: host supplies entries plus keyword/regex triggers. Matches this turn's user text only. Not mounted by `ContextAssembler.defaults()`. Vocabularies and card formats stay with the host.
- Optional `MemorySource` for `ChatRoom`: host supplies `MemoryRetriever` + scopes + limit. Not mounted by `ContextAssembler.defaults()`. Extract / remind stay with the host.
- `RoundRobinSpeaker`: group rooms rotate the answering persona in member order without @mention. Composable as `MentionSpeaker` fallback.
- `MemoryExtractor` is now an interface; keyword rules live in `KeywordMemoryExtractor`. Write path (`extractAndStore`) is shared. Business-specific subjects stay out of the extractor.
- `LlmMemoryExtractor`: host-supplied instructions + JSON memories; subject is stored as returned; parse/model failure yields no entries.
- `MemoryRetriever.recallForContext` ranks by importance (then recency) before applying topN. Hosts map product priority (e.g. user-edited) onto importance at write time.
- Optional `MemoryEntry.dueAt` plus `MemoryQuery` due window (`dueFrom` / `dueTo`). No scheduler and no product meaning for the timestamp.
- `LlmMemoryExtractor` parses optional JSON `dueAt` (ISO-8601 Instant or offset). Invalid values are ignored; the rest of the entry is kept.
- `ReasoningConfig` (`agent-core`): provider-neutral reasoning intent — `auto` / `enabled` / `disabled` plus an optional `effort` hint. Set per request via `ModelRequest.builder().reasoning(...)` / `disableReasoning()`, or as a client-wide default.
- `OpenAiModelClient.Flavor`: endpoint identity auto-detected from `baseUrl` (Ark, Qwen, DeepSeek, OpenRouter, OpenAI, generic), used only to pick the vendor's reasoning request switch.
- Tolerant reasoning-channel reader (`ChatDelta`): every field name seen in the wild (`reasoning_content` / `reasoning` / `thinking`, including the nested form) is read with a fallback chain, so an unknown OpenAI-compatible endpoint works without a framework change.
- `extraBody` escape hatch on `OpenAiModelClient` / `AnthropicModelClient`: vendor-specific fields merged verbatim into the request body. Standard fields win on collision, so the hatch cannot corrupt the protocol.
- `agent4j.model.reasoning.mode` / `.effort` and `agent4j.model.extra-body` configuration properties.

### Fixed

- **Streaming never terminated against Volcengine Ark.** Ark's final SSE chunk carries `content:""` and `finish_reason:"stop"` together; the parser returned early on the content branch, so `finish_reason` was never observed, `StreamEvent.Done` was never emitted, and `ReActAgentLoop` failed every turn with "Stream ended without a Done event". Every chunk is now inspected for every field, in a fixed order, with the terminal check last.
- Streamed tool-call `arguments` are merged by `index` across chunks. Previously every fragment but the last was discarded, so a tool call split across chunks arrived incomplete.
- Streams that end after `[DONE]` without `finish_reason` now emit a terminal event instead of being reported as an incomplete stream. A stream that produced nothing usable surfaces an `Error` rather than an empty answer that would be persisted as a blank reply.
- SSE `data:` lines without the conventional trailing space are now accepted.

### Changed

- T07 docs: `architecture-agent-chat.md` §9 clarifies engine vs product for memory (recall vs extract/remind); `docs/concepts.md` adds ChatRoom; `docs/modules.md` adds wiring table for Moonlit-style apps.
- `agent-memory` packages follow the write/read/compact/govern pipelines: root stays the wiring surface; implementations live in `extract/`, `store/`, `context/`, `session/` (`tools/` unchanged).
- Drop Spring Boot parent POM in favor of a standalone Maven parent
- Open-source packaging for GitHub (`qwzhang01/agent4j`) and Maven Central coordinates

[Unreleased]: https://github.com/qwzhang01/agent4j/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/qwzhang01/agent4j/releases/tag/v0.1.0
