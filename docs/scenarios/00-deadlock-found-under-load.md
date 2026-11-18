# Scenario 00 — The deadlock we found under load (a real one)

This was not planned. It happened the first time the baseline load test ran against the stack, and the telemetry the
lab was built to demonstrate is what found it. It is kept here as a worked example of an investigation.

## Symptom

`k6 run baseline.js` at 15 req/s, no faults injected:

| Metric | Value |
|---|---|
| Failed requests | **73%** (500s) |
| Transfer P95 | **5.6s** |
| Hikari pending connections | climbing, pool at 10/10 |

With no fault injected and modest load, the system should have been idle.

## Investigation

1. **Dashboard, USE row.** Pool saturated, CPU low: the service was waiting, not working.
2. **Slow and failed traces.** They end in the account locking statement of the transfer transaction, held for
   seconds, then failing. The JDBC span records the exception: PostgreSQL's `deadlock detected`.
3. **Logs for the same trace.** The error log line (same `trace_id`) carries the full PostgreSQL message, which names
   the two blocked processes and the relation: `account`.
4. **Code reading with that hint.** The original transaction:

   ```text
   INSERT INTO transfer (source_account_id, target_account_id, ...)   -- FK check takes KEY SHARE on both accounts
   SELECT ... FROM account WHERE id IN (...) FOR UPDATE              -- upgrade to exclusive row lock
   UPDATE account SET balance = ...
   ```

   Two concurrent transfers touching the same account both hold `FOR KEY SHARE` (compatible with each other) from the
   foreign key checks, then both try to upgrade to `FOR UPDATE` (conflicts with `KEY SHARE`). Each waits for the other.
   Ordering the `SELECT ... FOR UPDATE` by id does not help, because the conflicting locks were taken *before* it, by
   the `INSERT`.

## Fix

`TransferService.persist` and `JdbcAccountRepository.lockAll`:

1. Lock the accounts **first**, in a deterministic order (`ORDER BY id`).
2. Use `FOR NO KEY UPDATE` instead of `FOR UPDATE`. Balance updates do not change the key, and `NO KEY UPDATE` does not
   conflict with the `KEY SHARE` locks taken by foreign key checks.
3. Only then insert the transfer and ledger entries.

Defence in depth added at the same time:

- Regression test `TransferFlowIT.concurrentTransfersInOppositeDirectionsDoNotDeadlock` (opposite-direction transfers
  between the same pair of accounts, in parallel, against a real PostgreSQL via Testcontainers).
- Transient database errors (`TransientDataAccessException`, connection acquisition failures) now map to
  **503 + `Retry-After`** instead of 500, so clients and SLOs can tell "try again" apart from "bug".
- `lock_timeout=2s` and `statement_timeout=5s` on every connection, so lock queues cannot grow without bound.

## Result

| Metric | Before | After |
|---|---|---|
| Failed requests | 73% | 0% |
| Transfer P95 | 5.6s | **52ms** |
| Transfer P99 | — | 56ms |

## Lessons

- Foreign keys take locks. Lock ordering has to account for *every* statement in the transaction, not just the
  explicit `SELECT ... FOR UPDATE`.
- Unit tests and single-request integration tests passed. It took concurrency (load test + a parallel test) to see it.
- The path from symptom to root cause was metric → exemplar → trace → logs, without SSH or extra logging. That
  is the point of the lab.
