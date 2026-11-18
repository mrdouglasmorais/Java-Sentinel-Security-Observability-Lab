# ADR 0004 — Identity and access

**Status:** accepted

## Decision

- The API is a **stateless OAuth 2.0 resource server**. It never sees passwords. Keycloak (realm `sentinel`) is the
  identity provider.
- JWT validation: signature against the realm's JWKS (fetched over the internal network), `iss` must equal
  `http://localhost:8180/realms/sentinel` (Keycloak is pinned to that frontend URL with `KC_HOSTNAME`), `aud` must
  contain `sentinel-api` (audience mapper on the client), `exp`/`nbf` enforced.
- **Roles from an allow-list**: `realm_access.roles` is mapped to `CUSTOMER`, `AUDITOR`, `LAB_OPERATOR`; anything else
  is ignored, so a misconfigured IdP cannot grant unknown privileges.
- **Authorization in two layers**: URL rules in the filter chain (coarse, deny by default) and ownership checks in the
  application services (fine-grained). Resources owned by someone else answer **404**, not 403, to avoid confirming
  they exist; the attempt is recorded as an `ownership_violation`.
- **Lab control plane** (fault injection): bean exists only with `sentinel.lab.enabled=true`, URL rule and
  `@PreAuthorize` both require `LAB_OPERATOR`, every change is an audit event.
- **Rate limiting** per authenticated principal (token bucket, Bucket4j, bounded Caffeine cache), applied after
  authentication so the key cannot be spoofed with headers.
- No sessions, no cookies → CSRF protection disabled deliberately; strict security headers (`CSP default-src 'none'`,
  `frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, HSTS on HTTPS).

## Lab-only shortcuts (do not copy)

| Shortcut | Production alternative |
|---|---|
| Public client with the password grant (`sentinel-cli`) so scripts get tokens | Authorization code + PKCE for users, client credentials for services |
| Passwords in the realm export | Users managed in the IdP, secrets in a vault |
| Keycloak `start-dev`, HTTP | `start` with TLS, a real database, hostname and proxy settings |
| Grafana anonymous Editor | SSO, viewer by default |
| Default passwords in `compose.yaml` (`.env` overrides them) | Secrets injected by the platform |
