package com.example.banking;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.example.banking.domain.OperationResult.Outcome.INSUFFICIENT_FUNDS;
import static com.example.banking.domain.OperationResult.Outcome.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

// New rule: every account may go down to -HKD 100.00, and no further.
@Testcontainers
@Timeout(60)
class OverdraftBufferIT {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withCommand("--log-bin-trust-function-creators=1");
    private static ConfigurableApplicationContext context;
    private static BankingService banking;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startApplication() {
        context = new SpringApplicationBuilder(BankingApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--logging.level.root=WARN");
        banking = context.getBean(BankingService.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) {
            context.close();
        }
    }

    @AfterEach
    void balancesMatchMovements() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts a
                WHERE a.balance_minor <> (SELECT COALESCE(SUM(m.amount_minor), 0)
                                          FROM account_movements m WHERE m.account_id = a.account_id)
                """, Integer.class)).isZero();
    }

    @Test
    void withdrawalCanUseTheBufferButNotGoPastIt() {
        UUID account = open(5_000);
        WithdrawalCommand intoBuffer = new WithdrawalCommand(UUID.randomUUID(), account, Money.cents(12_000));

        OperationResult result = banking.withdraw(intoBuffer);

        assertThat(result.getOutcome()).isEqualTo(SUCCEEDED);
        assertThat(balance(account)).isEqualTo(-7_000);
        assertThat(banking.withdraw(intoBuffer)).isEqualTo(result);
        assertThat(banking.withdraw(new WithdrawalCommand(UUID.randomUUID(), account, Money.cents(4_000)))
                .getOutcome()).isEqualTo(INSUFFICIENT_FUNDS);
        assertThat(balance(account)).isEqualTo(-7_000);
        assertThat(banking.deposit(new DepositCommand(UUID.randomUUID(), account, Money.cents(10_000)))
                .getOutcome()).isEqualTo(SUCCEEDED);
        assertThat(balance(account)).isEqualTo(3_000);
    }

    @Test
    void transferCanUseTheSourceBufferButNotGoPastIt() {
        UUID source = open(0);
        UUID destination = open(0);

        assertThat(banking.transfer(new TransferCommand(UUID.randomUUID(), source, destination, Money.cents(10_000)))
                .getOutcome()).isEqualTo(SUCCEEDED);
        assertThat(banking.transfer(new TransferCommand(UUID.randomUUID(), source, destination, Money.cents(1)))
                .getOutcome()).isEqualTo(INSUFFICIENT_FUNDS);
        assertThat(balance(source)).isEqualTo(-10_000);
        assertThat(balance(destination)).isEqualTo(10_000);
    }

    private static UUID open(long cents) {
        UUID account = UUID.randomUUID();
        assertThat(banking.createAccount(new CreateAccountCommand(UUID.randomUUID(), account, Money.cents(cents)))
                .getOutcome()).isEqualTo(SUCCEEDED);
        return account;
    }

    private static long balance(UUID account) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE account_id = ?",
                Long.class, account.toString());
    }
}
