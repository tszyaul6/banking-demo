# Banking module

Java 17, Spring Boot, Spring JDBC, and MySQL. Supports account creation, deposits, withdrawals, transfers, and balance queries through `BankingService`. No HTTP server.

## Run

Requires JDK 17+, Docker, and network access for initial downloads.

```powershell
.\mvnw.cmd verify
docker compose up -d --wait
java -jar target/banking.jar --spring.profiles.active=demo
docker compose down
```

On macOS/Linux, replace `.\mvnw.cmd` with `sh mvnw`.

The demo creates new accounts and demonstrates transfers, retries, and rejections. Final balances: Alice **HKD 10.00**, Bob **HKD 30.00**. Stopping Compose preserves its data.

The local database uses port 3307 and development credentials. Override `BANKING_DB_URL`, `BANKING_DB_USER`, and `BANKING_DB_PASSWORD` as needed. All instances must use the same database writer and schema, including reads.

## Usage

Obtain `BankingService` from Spring and call it outside any existing transaction. Each async worker must call the service itself.

```java
UUID accountId = UUID.randomUUID();
CreateAccountCommand command = new CreateAccountCommand(
        UUID.randomUUID(), accountId, Money.hkd("100.00"));
OperationResult result = bankingService.createAccount(command);
```

- Use a new request ID for each intended operation; save and reuse it for retries.
- HKD amounts use exact `long` cents. Only opening deposits may be zero.
- Invalid input and self-transfers are rejected. Accounts may overdraw down to -HKD 100.00 and no further; rejected requests and overflow never change balances.
- Replays return the original result, including rejections and historical balances.
- Use `getBalance` for current balances. An empty `getOperation` result does not prove an in-flight request failed.
- Operations have no arrival-order guarantee. Wait for a deposit to succeed before a dependent withdrawal.

## Tests and coverage

```powershell
.\mvnw.cmd test     # Unit tests
.\mvnw.cmd verify   # Unit tests and isolated MySQL integration tests
```

Last verified on 2026-09-14: **53 unit tests and 17 integration tests passed**, none skipped. Tests cover validation, retries, concurrency, rollback, replay, and separate JVMs. The demo was verified before the latest review changes.

Generate coverage without editing the POM:

```powershell
.\mvnw.cmd clean org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.15:report
Start-Process .\target\site\jacoco\index.html
```

This coverage command has not been run here. Manually launched child JVMs do not inherit the coverage agent.

See [design decisions](DESIGN.md) and [AI disclosure](AI_USAGE.md).
