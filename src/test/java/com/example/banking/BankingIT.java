package com.example.banking;

import com.example.banking.application.BankingException;
import com.example.banking.application.BankingService;
import com.example.banking.domain.CreateAccountCommand;
import com.example.banking.domain.DepositCommand;
import com.example.banking.domain.Money;
import com.example.banking.domain.OperationResult;
import com.example.banking.domain.TransferCommand;
import com.example.banking.domain.WithdrawalCommand;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static com.example.banking.domain.OperationResult.Outcome.ACCOUNT_ALREADY_EXISTS;
import static com.example.banking.domain.OperationResult.Outcome.ACCOUNT_NOT_FOUND;
import static com.example.banking.domain.OperationResult.Outcome.BALANCE_LIMIT_EXCEEDED;
import static com.example.banking.domain.OperationResult.Outcome.INSUFFICIENT_FUNDS;
import static com.example.banking.domain.OperationResult.Outcome.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Timeout(60)
class BankingIT {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withCommand("--log-bin-trust-function-creators=1");
    private static ConfigurableApplicationContext firstContext;
    private static ConfigurableApplicationContext secondContext;
    private static BankingService first;
    private static BankingService second;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startApplications() {
        firstContext = startApplication();
        secondContext = startApplication();
        first = firstContext.getBean(BankingService.class);
        second = secondContext.getBean(BankingService.class);
        jdbc = firstContext.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopApplications() {
        if (secondContext != null) {
            secondContext.close();
        }
        if (firstContext != null) {
            firstContext.close();
        }
    }

    @AfterEach
    void reconcileAllAccountsAndCheckNoPartialOperations() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT a.account_id
                    FROM accounts a LEFT JOIN account_movements m ON a.account_id = m.account_id
                    GROUP BY a.account_id, a.balance_minor
                    HAVING a.balance_minor <> COALESCE(SUM(m.amount_minor), 0)
                ) mismatches
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operations WHERE outcome = 'PROCESSING'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM account_movements m JOIN operations o USING (request_id)
                WHERE o.outcome <> 'SUCCEEDED'
                """, Integer.class)).isZero();
    }

    @Test
    void completeLifecycleAndReplayOriginalSnapshot() {
        UUID alice = create(10_000);
        UUID bob = create(0);
        TransferCommand transfer = new TransferCommand(UUID.randomUUID(), alice, bob, Money.cents(3_000));
        OperationResult result = first.transfer(transfer);
        assertThat(result.getOutcome()).isEqualTo(SUCCEEDED);
        assertThat(result.getAccount().orElseThrow().getMinorUnits()).isEqualTo(7_000);
        assertThat(result.getDestination().orElseThrow().getMinorUnits()).isEqualTo(3_000);
        first.deposit(new DepositCommand(UUID.randomUUID(), alice, Money.cents(500)));
        assertThat(second.transfer(transfer)).isEqualTo(result);
        assertThat(balance(alice)).isEqualTo(7_500);
        assertThat(balance(bob)).isEqualTo(3_000);
        assertThat(first.getOperation(transfer.getRequestId())).contains(result);
        assertThat(first.getOperation(UUID.randomUUID())).isEmpty();
        assertThat(first.getBalance(UUID.randomUUID())).isEmpty();
    }

    @Test
    void rejectedWithdrawalStaysRejectedAfterDepositAndNewRequestCanSucceed() {
        UUID account = create(0);
        WithdrawalCommand withdrawal = new WithdrawalCommand(UUID.randomUUID(), account, Money.cents(10_100));
        OperationResult result = first.withdraw(withdrawal);
        assertThat(result.getOutcome()).isEqualTo(INSUFFICIENT_FUNDS);
        second.deposit(new DepositCommand(UUID.randomUUID(), account, Money.cents(10_100)));
        assertThat(first.withdraw(withdrawal)).isEqualTo(result);
        assertThat(balance(account)).isEqualTo(10_100);
        assertThat(first.withdraw(new WithdrawalCommand(UUID.randomUUID(), account, Money.cents(10_100))).getOutcome())
                .isEqualTo(SUCCEEDED);
        assertThat(balance(account)).isZero();
    }

    @Test
    void duplicateAccountCreationDoesNotChangeInitialDeposit() {
        UUID account = UUID.randomUUID();
        CreateAccountCommand command = new CreateAccountCommand(UUID.randomUUID(), account, Money.cents(250));
        OperationResult result = first.createAccount(command);
        assertThat(second.createAccount(command)).isEqualTo(result);
        assertThat(second.createAccount(new CreateAccountCommand(UUID.randomUUID(), account, Money.cents(800))).getOutcome())
                .isEqualTo(ACCOUNT_ALREADY_EXISTS);
        assertThat(balance(account)).isEqualTo(250);
    }

    @Test
    void conflictingKeyCannotChangeAmountTypeOrAccount() {
        UUID account = create(100);
        UUID key = UUID.randomUUID();
        first.deposit(new DepositCommand(key, account, Money.hkd("1.0")));
        assertThat(second.deposit(new DepositCommand(key, account, Money.hkd("1.00"))).getOutcome()).isEqualTo(SUCCEEDED);
        assertThatThrownBy(() -> second.deposit(new DepositCommand(key, account, Money.cents(101))))
                .isInstanceOf(BankingException.IdempotencyConflict.class);
        assertThatThrownBy(() -> first.withdraw(new WithdrawalCommand(key, account, Money.cents(100))))
                .isInstanceOf(BankingException.IdempotencyConflict.class);
        assertThatThrownBy(() -> first.deposit(new DepositCommand(key, UUID.randomUUID(), Money.cents(100))))
                .isInstanceOf(BankingException.IdempotencyConflict.class);
        assertThat(balance(account)).isEqualTo(200);
    }

    @Test
    void missingDestinationDoesNotDebitSource() {
        UUID account = create(100);
        OperationResult result = first.transfer(new TransferCommand(UUID.randomUUID(), account, UUID.randomUUID(), Money.cents(50)));
        assertThat(result.getOutcome()).isEqualTo(ACCOUNT_NOT_FOUND);
        assertThat(balance(account)).isEqualTo(100);
        assertThat(first.deposit(new DepositCommand(UUID.randomUUID(), UUID.randomUUID(), Money.cents(1))).getOutcome())
                .isEqualTo(ACCOUNT_NOT_FOUND);
    }

    @Test
    void overflowRejectsWholeOperation() {
        UUID full = create(Long.MAX_VALUE);
        UUID source = create(1);
        assertThat(first.deposit(new DepositCommand(UUID.randomUUID(), full, Money.cents(1))).getOutcome())
                .isEqualTo(BALANCE_LIMIT_EXCEEDED);
        assertThat(first.transfer(new TransferCommand(UUID.randomUUID(), source, full, Money.cents(1))).getOutcome())
                .isEqualTo(BALANCE_LIMIT_EXCEEDED);
        assertThat(balance(full)).isEqualTo(Long.MAX_VALUE);
        assertThat(balance(source)).isEqualTo(1);
    }

    @Test
    void concurrentDepositsAcrossInstancesHaveNoLostUpdates() throws Exception {
        UUID account = create(0);
        List<OperationResult> results = concurrently(100, index -> () -> service(index).deposit(
                new DepositCommand(UUID.randomUUID(), account, Money.cents(1))));
        assertThat(results).allMatch(result -> result.getOutcome() == SUCCEEDED);
        assertThat(balance(account)).isEqualTo(100);
    }

    @Test
    void concurrentWithdrawalsCannotOverdraw() throws Exception {
        UUID account = create(100);
        List<OperationResult> results = concurrently(100, index -> () -> service(index).withdraw(
                new WithdrawalCommand(UUID.randomUUID(), account, Money.cents(200))));
        assertThat(results.stream().filter(result -> result.getOutcome() == SUCCEEDED).count()).isEqualTo(50);
        assertThat(results.stream().filter(result -> result.getOutcome() == INSUFFICIENT_FUNDS).count()).isEqualTo(50);
        assertThat(balance(account)).isEqualTo(-9_900);
    }

    @Test
    void concurrentSameKeyProducesOneEffectAcrossInstances() throws Exception {
        UUID account = create(0);
        DepositCommand command = new DepositCommand(UUID.randomUUID(), account, Money.cents(100));
        List<OperationResult> results = concurrently(50, index -> () -> service(index).deposit(command));
        assertThat(results).allMatch(result -> result.equals(results.get(0)));
        assertThat(balance(account)).isEqualTo(100);
        assertThat(movementCount(command.getRequestId())).isEqualTo(1);
    }

    @Test
    void conflictingConcurrentRequestsHaveOnlyOneWinner() throws Exception {
        UUID account = create(0);
        UUID key = UUID.randomUUID();
        List<String> results = concurrently(2, index -> () -> {
            try {
                service(index).deposit(new DepositCommand(key, account, Money.cents(index + 1)));
                return "success";
            } catch (BankingException.IdempotencyConflict exception) {
                return "conflict";
            }
        });
        assertThat(results).containsExactlyInAnyOrder("success", "conflict");
        assertThat(balance(account)).isBetween(1L, 2L);
        assertThat(movementCount(key)).isEqualTo(1);
    }

    @Test
    void oppositeDirectionTransfersConserveMoney() throws Exception {
        UUID alice = create(10_000);
        UUID bob = create(10_000);
        List<OperationResult> results = concurrently(100, index -> () -> service(index).transfer(new TransferCommand(
                UUID.randomUUID(), index % 2 == 0 ? alice : bob, index % 2 == 0 ? bob : alice, Money.cents(10))));
        assertThat(results).allMatch(result -> result.getOutcome() == SUCCEEDED);
        assertThat(balance(alice)).isEqualTo(10_000);
        assertThat(balance(bob)).isEqualTo(10_000);
    }

    @Test
    void failureAfterSourceDebitRollsBackAndSameKeyCanRecover() {
        UUID alice = create(100);
        UUID bob = create(0);
        TransferCommand command = new TransferCommand(UUID.randomUUID(), alice, bob, Money.cents(30));
        jdbc.execute("""
                CREATE TRIGGER fail_destination BEFORE UPDATE ON accounts FOR EACH ROW
                BEGIN
                    IF NEW.account_id = '%s' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Injected destination failure';
                    END IF;
                END
                """.formatted(bob));
        try {
            assertThatThrownBy(() -> first.transfer(command)).isInstanceOf(DataAccessException.class);
            assertThat(balance(alice)).isEqualTo(100);
            assertThat(balance(bob)).isZero();
            assertThat(first.getOperation(command.getRequestId())).isEmpty();
            assertThat(movementCount(command.getRequestId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_destination");
        }
        assertThat(second.transfer(command).getOutcome()).isEqualTo(SUCCEEDED);
        assertThat(balance(alice)).isEqualTo(70);
        assertThat(balance(bob)).isEqualTo(30);
    }

    @Test
    void failureSavingResultRollsBackBalancesAndJournal() {
        UUID account = create(100);
        DepositCommand command = new DepositCommand(UUID.randomUUID(), account, Money.cents(50));
        jdbc.execute("""
                CREATE TRIGGER fail_result BEFORE UPDATE ON operations FOR EACH ROW
                BEGIN
                    IF NEW.request_id = '%s' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Injected result failure';
                    END IF;
                END
                """.formatted(command.getRequestId()));
        try {
            assertThatThrownBy(() -> first.deposit(command)).isInstanceOf(DataAccessException.class);
            assertThat(balance(account)).isEqualTo(100);
            assertThat(first.getOperation(command.getRequestId())).isEmpty();
            assertThat(movementCount(command.getRequestId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_result");
        }
    }

    @Test
    void lockTimeoutRollsBackClaimAndReleasesConnectionForRecovery() throws Exception {
        UUID account = create(100);
        WithdrawalCommand command = new WithdrawalCommand(UUID.randomUUID(), account, Money.cents(10));
        try (Connection connection = MYSQL.createConnection("")) {
            connection.setAutoCommit(false);
            lock(connection, account);
            assertThatThrownBy(() -> second.withdraw(command))
                    .isInstanceOf(BankingException.TemporarilyUnavailable.class);
            assertThat(first.getOperation(command.getRequestId())).isEmpty();
            assertThat(balance(account)).isEqualTo(100);
            connection.rollback();
        }
        assertThat(first.withdraw(command).getOutcome()).isEqualTo(SUCCEEDED);
        assertThat(balance(account)).isEqualTo(90);
    }

    @Test
    void lostResponseReplaysAfterApplicationRestart() {
        UUID account = create(0);
        DepositCommand command = new DepositCommand(UUID.randomUUID(), account, Money.cents(125));
        try (ConfigurableApplicationContext original = startApplication()) {
            original.getBean(BankingService.class).deposit(command);
            // The caller deliberately discards the response, then the entire context is closed.
        }
        try (ConfigurableApplicationContext restarted = startApplication()) {
            BankingService banking = restarted.getBean(BankingService.class);
            OperationResult result = banking.deposit(command);
            assertThat(result.getOutcome()).isEqualTo(SUCCEEDED);
            assertThat(result.getAccount().orElseThrow().getMinorUnits()).isEqualTo(125);
            assertThat(balance(account)).isEqualTo(125);
            assertThat(movementCount(command.getRequestId())).isEqualTo(1);
        }
    }

    @Test
    void rejectsCallerOwnedTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(firstContext.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> assertThatIllegalStateException().isThrownBy(
                () -> first.deposit(new DepositCommand(UUID.randomUUID(), UUID.randomUUID(), Money.cents(1)))));
    }

    @Test
    void independentJvmProcessesShareLocksAndIdempotency() throws Exception {
        UUID account = create(50);
        UUID sharedKey = UUID.randomUUID();
        try (BankingWorkerProcess firstWorker = BankingWorkerProcess.start(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), account, sharedKey);
             BankingWorkerProcess secondWorker = BankingWorkerProcess.start(
                     MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), account, sharedKey)) {
            firstWorker.awaitReady();
            secondWorker.awaitReady();
            firstWorker.release();
            secondWorker.release();

            List<String> output = new ArrayList<>(firstWorker.awaitOutput());
            output.addAll(secondWorker.awaitOutput());
            assertThat(output.stream().filter("DEPOSIT SUCCEEDED"::equals).count()).isEqualTo(2);
            assertThat(output.stream().filter("WITHDRAWAL SUCCEEDED"::equals).count()).isEqualTo(2);
            assertThat(balance(account)).isEqualTo(-60);
            assertThat(movementCount(sharedKey)).isEqualTo(1);
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(BankingApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--logging.level.root=WARN");
    }

    private static UUID create(long amount) {
        UUID account = UUID.randomUUID();
        assertThat(first.createAccount(new CreateAccountCommand(UUID.randomUUID(), account, Money.cents(amount))).getOutcome())
                .isEqualTo(SUCCEEDED);
        return account;
    }

    private static long balance(UUID account) {
        return first.getBalance(account).orElseThrow().getMinorUnits();
    }

    private static int movementCount(UUID request) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_movements WHERE request_id = ?",
                Integer.class, request.toString());
    }

    private static BankingService service(int index) {
        return index % 2 == 0 ? first : second;
    }

    private static void lock(Connection connection, UUID account) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM accounts WHERE account_id = ? FOR UPDATE")) {
            statement.setString(1, account.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private static <T> List<T> concurrently(int count, IntFunction<Callable<T>> task) throws Exception {
        int threadCount = Math.min(count, 16);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<Future<T>>();
        try {
            for (int index = 0; index < count; index++) {
                Callable<T> operation = task.apply(index);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent start timed out");
                    }
                    return operation.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            for (Future<T> future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return List.copyOf(results);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
