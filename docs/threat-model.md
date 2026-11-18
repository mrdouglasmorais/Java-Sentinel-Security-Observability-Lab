# Threat model

Scope: `sentinel-api` and its immediate dependencies as deployed by `compose.yaml`. Method: data flow + STRIDE, with
OWASP ASVS 5.0 and the OWASP API Security Top 10 (2023) as checklists.

## Assets

| Asset | Why it matters |
|---|---|
| Account balances and ledger | Integrity: money must neither appear nor disappear |
| Transfer authorisation | Only the owner may move funds from an account |
| Identity tokens | Bearer tokens grant access until they expire (5 minutes) |
| Fault injection control plane | Can degrade the whole service on demand |
| Telemetry | Contains principals, client addresses and business context; must not contain secrets |

## Trust boundaries

1. Client → API (public HTTP, port 8080): untrusted input, untrusted tokens.
2. API → Keycloak (internal network): trusted for key material (JWKS).
3. API → PostgreSQL (internal network): trusted, least privilege user in production.
4. API → Collector → Tempo/Loki, Prometheus → API management port 8081 (internal network only).

## STRIDE

| Threat | Example | Mitigation in the lab | Evidence |
|---|---|---|---|
| **S**poofing | Forged JWT, `alg: none`, token from another realm or for another API | Signature against JWKS, issuer and audience validation, `exp`/`nbf`; `Basic` and malformed headers rejected | `authentication_failed` events, `security-attack.js` |
| **T**ampering | Negative or over-precise amounts, replaying a transfer | Bean Validation (`@DecimalMin`, `@Digits`), DB `CHECK` constraints, `Idempotency-Key` with payload match (409 on reuse) | `TransferFlowIT` |
| **R**epudiation | "I never made that transfer" | Transfer stores `requested_by`; audit log with principal, client address, `trace_id`; ledger is append-only | Loki audit trail |
| **I**nformation disclosure | Account enumeration, stack traces, secrets in telemetry | Foreign resources → 404; problem details without internals; `include-stacktrace: never`; SQL sanitised by the agent; Collector deletes auth/cookie headers and query strings | `ApiSecurityIT`, Collector config |
| **D**enial of service | Flooding, slow dependency exhausting threads/connections | Per-principal token bucket (429 + `Retry-After`), bounded rate-limit cache, bulkhead, time limiter, small pool with timeouts, `lock_timeout`/`statement_timeout`, 1 GB / 2 CPU container limits | `RateLimitIT`, slow-database scenario |
| **E**levation of privilege | Customer calls lab endpoints, customer acts as auditor | Deny-by-default URL rules + `@PreAuthorize`; roles from an allow-list; lab controller absent unless enabled | `access_denied` events, `ApiSecurityIT` |

## OWASP API Security Top 10 (2023)

| Risk | Status |
|---|---|
| API1 Broken Object Level Authorization | Ownership checked in services for accounts, ledgers and transfers; violations audited and alerted (`SentinelOwnershipProbing`) |
| API2 Broken Authentication | Delegated to the IdP; strict JWT validation; brute-force protection enabled in the realm |
| API3 Broken Object Property Level Authorization | Response DTOs expose only intended fields (no owner ids, versions or internal reasons beyond the decision) |
| API4 Unrestricted Resource Consumption | Rate limiting, pagination limit (`limit` ≤ 200), amount bounds, timeouts and bulkheads |
| API5 Broken Function Level Authorization | Role per operation (transfer = customer; lab = lab-operator; auditors read-only) |
| API6 Unrestricted Access to Sensitive Business Flows | Transfers rate-limited separately and stricter; risk scoring on every transfer |
| API7 SSRF | No user-controlled outbound URLs |
| API8 Security Misconfiguration | Actuator on internal port with only health/info/prometheus; security headers; non-root read-only container, no capabilities |
| API9 Improper Inventory Management | Single versioned API surface documented in the README; lab endpoints gated by a property |
| API10 Unsafe Consumption of APIs | Risk engine responses are bounded integers; failures handled by an explicit policy |

## Residual risks and lab-only shortcuts

- Password grant, passwords in the realm export, Keycloak dev mode, HTTP everywhere, Grafana anonymous editor:
  see [ADR 0004](architecture/adr/0004-identity-and-access.md#lab-only-shortcuts-do-not-copy).
- In-memory rate limiting is per instance; horizontal scaling needs a shared store or a gateway.
- Telemetry contains usernames and client addresses (needed for investigations). Production requires a retention
  policy and access control on Loki; consider pseudonymising principals.
- Supply chain: dependencies scanned by OWASP Dependency-Check and the image by Trivy in CI, Dependabot keeps them
  current; images are referenced by tag (pin by digest for production).
