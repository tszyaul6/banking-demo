# Design decisions

## Responsibilities and style

| Class | Responsibility |
| --- | --- |
| `BankingService` | Public entry point, bounded retries, and result replay |
| `TransactionalBankingOperations` | One complete database transaction per attempt |
| `BankingStore` | SQL, row locking, and result mapping |
| Domain classes | Immutable values and constructor validation |

Use explicit Java types, final fields, standard getters, and constructor injection. Lombok generates value methods and required constructors; enable annotation processing in the IDE. Keep the existing package structure and ordinary command interface.

Each method has one purpose. Workflow methods coordinate named helpers; calculations stay separate from database writes. Introduce abstractions only when they simplify an actual requirement.

`OperationResult.succeeded(...)` requires an account snapshot. `rejected(...)` permits no snapshots. The public constructor enforces the same rules. Optional query results use `Optional`; SQL parameters may use `null`.

## Transactions and locking

MySQL coordinates independent application instances and preserves results across restarts. JVM locks or an in-memory map cannot provide those guarantees across processes.

`BankingService` calls a separate Spring bean using `REQUIRES_NEW`, `READ_COMMITTED`, and rollback for all exceptions. Each attempt follows:

```text
claim request -> lock accounts -> validate and calculate
-> update balances -> record movements -> save result -> commit
```

Existing accounts are locked with `SELECT ... FOR UPDATE`. Transfers lock both accounts in UUID-string order to reduce deadlocks. Both balances are calculated before either is written. `Math.addExact` detects overflow.

Business rejections commit only their result. Exceptions trigger rollback; a failure during commit can leave the caller uncertain. `PROCESSING` is never committed separately.

## Idempotency and failures

The unique `request_id` permits at most one committed effect. `command_identity` stores the version, operation type, accounts, currency, and amount.

Matching duplicates replay the saved result after leaving the failed claim transaction. Different contents raise `IdempotencyConflict`. Preserve the v1 identity format; deleting saved requests would allow them to execute again.

| Failure | Handling |
| --- | --- |
| Invalid command | Reject before database work |
| Business rejection | Save result; no retry |
| Lock failure or transaction-start failure | Retry the same command in a fresh transaction |
| Connection/resource or transaction-completion failure | Report `OutcomeUnknown`; recover using the same request ID |
| Unexpected database or programming error | Propagate; no automatic retry |

Allow three attempts, with randomized waits of 0-25ms and 0-50ms outside transactions. Exhaustion or interruption reports `TemporarilyUnavailable`; interruption preserves the thread flag.

Configured timeouts: connection acquisition 3s, row-lock wait 2s, transaction 5s, socket 7s. These do not form a strict overall deadline.

## Storage and limits

`accounts` holds balances; `operations` holds requests and results; `account_movements` holds signed changes and resulting balances. All commit together. Positive opening deposits create movements; zero openings do not. Database constraints protect nonnegative balances, HKD currency, and movement uniqueness.

Each instance has up to 16 connections. Independent accounts can progress concurrently; busy accounts serialize. Throughput and database failover are untested. Authentication, external settlement, multiple currencies, and a full double-entry ledger are outside scope.

Integration tests use real MySQL, reconcile balances with movements, and check rollback and replay across instances and JVMs. A test-only `BankingWorkerProcess` owns child-process startup, output, and cleanup. These checks do not simulate every network or infrastructure failure.

## References

- [Spring transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [MySQL locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
- [Testcontainers MySQL](https://java.testcontainers.org/modules/databases/mysql/)
- [JaCoCo Maven plugin](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
