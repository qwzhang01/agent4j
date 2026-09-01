# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| `0.1.x` | Yes |
| `main` branch | Yes |

The current Maven version is `0.1.0`. Security fixes land on `main` and ship in the next `0.1.x` release.

## Reporting a vulnerability

This project includes a sandbox, tool-permission checks, and an injection sanitizer. Treat security reports as high priority.

**Do not file a public GitHub issue** for exploitable sandbox escapes, injection flaws, or permission bypasses.

Report privately through GitHub Security Advisories:

https://github.com/qwzhang01/agent4j/security/advisories/new

Please include:

- Affected module (`agent-sandbox`, `agent-security`, or another listed module)
- Impact (sandbox escape, permission bypass, injection, data leak)
- Reproduction steps that do not require publishing a working exploit
- JDK version and whether a real LLM is required

We will acknowledge the report, investigate, and coordinate a fix before any public disclosure.

General questions about intended security behavior can go to [GitHub Discussions](https://github.com/qwzhang01/agent4j/discussions) once it is clear they are not exploitable bypasses.
