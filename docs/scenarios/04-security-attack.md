# Scenario 04 — A hostile client

**Question it answers:** someone is attacking the API. Do we notice, can we tell what they are trying, and does the
API hold?

## What runs

`load-tests/k6/security-attack.js`, about 2 minutes, run alongside or without the baseline:

| Phase | Actor | Attack | Expected defence |
|---|---|---|---|
| 0:00–2:00 | anonymous | Garbage JWTs, `alg: none` token claiming `lab-operator`, `Basic admin:admin` | 401, `authentication_failed` |
| 0:20–2:20 | mallory (customer) | Reads Alice's account and random account ids (IDOR / BOLA) | 404 (existence not confirmed), `ownership_violation` |
| 0:40–1:40 | mallory | Calls `POST /api/lab/scenarios/dependency-outage` | 403, `access_denied` |
| 1:10–1:40 | mallory | 400 req/s flood | 429 + `Retry-After`, `rate_limited` |

```bash
docker compose --profile load run --rm k6 run /scripts/security-attack.js
```

## Measured

| Security event | Count |
|---|---|
| `authentication_failed` | ~600 |
| `ownership_violation` | ~110 |
| `access_denied` | ~30 (the rest of Mallory's lab calls were throttled first, see below) |
| `rate_limited` | ~9,650 |

- **0** forged tokens accepted, **0** foreign accounts disclosed, **0** lab changes by Mallory.
- `http_req_failed` 0%: every response was one of the expected defensive status codes.
- Alerts firing: `SentinelAuthenticationFailureSpike`, `SentinelOwnershipProbing`, `SentinelRateLimitingActive`.

During the flood, Mallory's own IDOR and lab probes also receive 429: the rate limit runs after authentication and
before authorisation, keyed by principal, so a flooding client throttles everything it does, not only the
flooding endpoint. Other customers are unaffected (separate buckets).

## Investigate

1. **Security row**: events by type show four distinct waves, which correspond to the four phases above.
2. **Audit log panel** (Loki, logger `sentinel.audit`): who, from where, what.

   ```logql
   {service_name="sentinel-api"} | scope_name="sentinel.audit" | audit_event="ownership_violation"
   ```

   Each line carries `audit_principal=mallory`, `resource_type=account`, `resource_id=...`, `client_address`,
   `url_path` and `trace_id`.
3. **Who is behind the failed logins?**

   ```logql
   topk(5, sum by (client_address) (count_over_time({service_name="sentinel-api"} | audit_event="authentication_failed" [5m])))
   ```

4. **From an alert to a person**: `SentinelOwnershipProbing` fires on the metric (no user labels, low cardinality);
   the audit log names the principal. Metrics detect, logs attribute.

## Takeaways

- Return 404 for objects the caller does not own; it removes the enumeration oracle without hiding the attempt,
  because the attempt is audited.
- Security events belong on the same dashboards as latency and errors: a flood is both a security and a
  capacity event.
- Keep security metrics low-cardinality (`type`, `endpoint_group`) and put identities in logs, otherwise an attacker
  controls your metric cardinality.
