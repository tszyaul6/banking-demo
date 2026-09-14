package com.example.banking;

import com.example.banking.application.BankingService;
import com.example.banking.domain.DepositCommand;
import com.example.banking.domain.Money;
import com.example.banking.domain.WithdrawalCommand;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.UUID;

public final class ProcessBankingWorker {
    private ProcessBankingWorker() {
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BankingApplication.class)
                .web(WebApplicationType.NONE).run("--spring.datasource.url=" + args[0],
                        "--spring.datasource.username=" + args[1], "--spring.datasource.password=" + args[2],
                        "--logging.level.root=ERROR")) {
            BankingService banking = context.getBean(BankingService.class);
            UUID account = UUID.fromString(args[3]);
            UUID sharedRequestId = UUID.fromString(args[4]);
            System.out.println("READY");
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            if (!"GO".equals(input.readLine())) {
                throw new IllegalStateException("Expected start signal");
            }
            System.out.println("DEPOSIT " + banking.deposit(
                    new DepositCommand(sharedRequestId, account, Money.cents(50))).getOutcome());
            System.out.println("WITHDRAWAL " + banking.withdraw(
                    new WithdrawalCommand(UUID.randomUUID(), account, Money.cents(80))).getOutcome());
        }
    }
}
