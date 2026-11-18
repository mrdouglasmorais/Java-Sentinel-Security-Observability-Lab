# Security policy

Java Sentinel is a **lab**. Its defaults (password grant, passwords in the Keycloak realm export, Keycloak dev mode,
Grafana anonymous access, HTTP) are deliberate and bound to `127.0.0.1`. They are documented in
[docs/threat-model.md](docs/threat-model.md) and [ADR 0004](docs/architecture/adr/0004-identity-and-access.md);
reports about them are not vulnerabilities.

## Reporting a vulnerability

Please report anything else (for example an authorisation bypass in the API, a way to trigger lab faults without the
`lab-operator` role, or secrets leaking into telemetry) privately through
[GitHub private vulnerability reporting](../../security/advisories/new). Do not open a public issue.

Include the steps to reproduce and, if possible, the `trace_id` from the problem response.

## Supported versions

Only the `main` branch is maintained.

## Automated checks

Every push runs Trivy (dependencies, IaC, secrets, container image) and CodeQL; OWASP Dependency-Check runs on
`main` and weekly; Dependabot keeps Maven, Docker, Compose and GitHub Actions dependencies current.
