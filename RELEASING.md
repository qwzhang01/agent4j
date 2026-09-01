# Releasing

Maintainer notes for cutting a version of agent4j. The current Maven version is `0.1.0`.

## Prerequisites

- JDK 17+ (`JAVA_HOME` must not be 8; javadoc `--release 17` fails on JDK 8)
- `./mvnw -B verify` is green
- Changelog `[Unreleased]` notes are complete and accurate
- `~/.m2/settings.xml` has a `central` server with a Central Portal user token
- GPG key is available locally (or via `MAVEN_GPG_PASSPHRASE` + loopback pinentry)

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

Coordinates:

- Parent: `io.github.qwzhang01:seven-agent`
- BOM: `io.github.qwzhang01:seven-agent-bom`

`examples` is **not** published. The parent POM sets `excludeArtifacts=examples` on `central-publishing-maven-plugin`. Do not add `skipPublishing` on the `examples` module — it is last in the reactor and would drop the whole bundle.

The Central plugin lives in the main build (`extensions=true`) so it owns `deploy`. The stock `maven-deploy-plugin` is skipped: this project has no `distributionManagement`. `skipPublishing` defaults to `false`, so a plain `deploy` (IDE or CLI) **uploads**. Use `-Dskip.central.publish=true` only for a local-only dry run.

GPG signs in the `deploy` phase (not `verify`), so CI `./mvnw verify` does not need a key. Unsigned bundles will fail Portal validation.

Publish (signs, uploads a bundle, waits for validation, then you click Publish in the Portal):

```bash
export JAVA_HOME=/path/to/jdk-17
./mvnw -DskipTests deploy
```

IntelliJ: Command line `deploy -DskipTests`, keep `-s` pointing at the `settings.xml` that has `avinzhang-opensource`. Profiles are optional (`release` only tightens javadoc `failOnError`).

`autoPublish` is `false`. After the build prints a `deploymentId`, finish at https://central.sonatype.com/publishing/deployments.

Required local config (not in git). The plugin looks up `publishingServerId` (default `central`) in the Maven `settings.xml` **that this build actually loads**. `~/.m2/settings.xml` is the CLI/Cursor default; IntelliJ may point at another file.

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username><!-- Portal token username --></username>
      <password><!-- Portal token password --></password>
    </server>
  </servers>
</settings>
```

This repo defaults `central.serverId` to `avinzhang-opensource` (the Portal token id already in the maintainer `settings.xml`). Override with `-Dcentral.serverId=...` if needed. Pass `-s <settings-file>` when the CLI would otherwise load an empty `~/.m2/settings.xml`.

GPG: `maven-gpg-plugin` signs during `deploy` with `--pinentry-mode loopback`. Passphrase via `MAVEN_GPG_PASSPHRASE` or your agent.

## After the release

1. Bump parent POM and BOM back to the next SNAPSHOT (for example `0.1.1-SNAPSHOT`).
2. Push the SNAPSHOT bump.
3. Confirm GitHub Releases / Discussions notes point at the tag, not at SNAPSHOT artifacts.
4. Only then announce Central coordinates in the README.
