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

### Changed

- Drop Spring Boot parent POM in favor of a standalone Maven parent
- Open-source packaging for GitHub (`qwzhang01/agent4j`) and intended Maven Central coordinates

[Unreleased]: https://github.com/qwzhang01/agent4j/commits/main
