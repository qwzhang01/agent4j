# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The current Maven version is `0.1.0-SNAPSHOT`. The first public release will be `0.1.0`.

## [Unreleased]

### Added

- Apache License 2.0 (`LICENSE`) and `NOTICE`
- Community files: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `RELEASING.md`
- GitHub issue and pull request templates
- User-facing documentation under `docs/`
- Product README (stage diary archived to `notes/v1-development-log.md`)
- CI workflow for Maven verify (JDK 17 / 21)
- Bill of Materials (`seven-agent-bom`) for coordinated dependency versions
- Maven Wrapper (`./mvnw`, Maven 3.9.9)
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

### Changed

- T07 docs: `architecture-agent-chat.md` §9 clarifies engine vs product for memory (recall vs extract/remind); `docs/concepts.md` adds ChatRoom; `docs/modules.md` adds wiring table for Moonlit-style apps.
- `agent-memory` packages follow the write/read/compact/govern pipelines: root stays the wiring surface; implementations live in `extract/`, `store/`, `context/`, `session/` (`tools/` unchanged).
- Drop Spring Boot parent POM in favor of a standalone Maven parent
- Open-source packaging for GitHub (`qwzhang01/agent4j`) and intended Maven Central coordinates

[Unreleased]: https://github.com/qwzhang01/agent4j/commits/main
