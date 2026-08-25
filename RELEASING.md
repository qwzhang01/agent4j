# Releasing

Maintainer notes for cutting a version of agent4j. The first public release will be `0.1.0`. The current Maven version is `0.1.0-SNAPSHOT`.

## Prerequisites

- `mvn -B verify` (or `./mvnw -B verify`) is green
- Changelog `[Unreleased]` notes are complete and accurate
- No secrets or unpublished credentials in the tree

Do not push a release tag unless verify is green.

## Version bump

1. Set the version in the parent POM (`io.github.qwzhang01:seven-agent`) and the BOM to the release version (for example `0.1.0`).
2. Keep module versions aligned with the parent / BOM. Do not leave a mix of SNAPSHOT and release versions.
3. Move `[Unreleased]` entries in `CHANGELOG.md` under a dated `## [X.Y.Z]` section. Leave a fresh `[Unreleased]` heading.
4. Commit the version and changelog updates.

## Tag

Create an annotated tag matching the release version:

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
```

Push the commit, then the tag, only after verify is green:

```bash
git push origin main
git push origin vX.Y.Z
```

## Maven Central

Maven Central is **not wired yet**. GPG signing and portal credentials are pending.

Intended coordinates:

- Parent: `io.github.qwzhang01:seven-agent`
- BOM: `io.github.qwzhang01:seven-agent-bom`

Do not announce Central availability until publish credentials and the staging flow are in place.

## After the release

1. Bump parent POM and BOM back to the next SNAPSHOT (for example `0.1.1-SNAPSHOT`).
2. Push the SNAPSHOT bump.
3. Confirm GitHub Releases / Discussions notes point at the tag, not at SNAPSHOT artifacts.
