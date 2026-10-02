package com.example.banking.demo;

import com.example.banking.application.BankingService;
import com.example.banking.domain.CreateAccountCommand;
import com.example.banking.domain.DepositCommand;
import com.example.banking.domain.Money;
import com.example.banking.domain.OperationResult;
import com.example.banking.domain.TransferCommand;
import com.example.banking.domain.WithdrawalCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Profile("demo")
@RequiredArgsConstructor
public class BankingDemo implements ApplicationRunner {
    private final BankingService banking;

    @Override
    public void run(ApplicationArguments args) {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        System.out.println("Alice: " + alice + "; Bob: " + bob);

        CreateAccountCommand createAlice = new CreateAccountCommand(UUID.randomUUID(), alice, Money.hkd("100"));
        CreateAccountCommand createBob = new CreateAccountCommand(UUID.randomUUID(), bob, Money.hkd("0"));
        System.out.println("Create Alice: " + banking.createAccount(createAlice).getOutcome());
        System.out.println("Create Bob: " + banking.createAccount(createBob).getOutcome());

        TransferCommand transfer = new TransferCommand(UUID.randomUUID(), alice, bob, Money.hkd("30"));
        OperationResult transferResult = banking.transfer(transfer);
        OperationResult replayResult = banking.transfer(transfer);
        System.out.println("Transfer 30: " + transferResult.getOutcome());
        System.out.println("Replay identical: " + transferResult.equals(replayResult));

        WithdrawalCommand withdrawal = new WithdrawalCommand(UUID.randomUUID(), alice, Money.hkd("80"));
        System.out.println("Withdraw 80: " + banking.withdraw(withdrawal).getOutcome());

        DepositCommand deposit = new DepositCommand(UUID.randomUUID(), alice, Money.hkd("20"));
        System.out.println("Deposit 20: " + banking.deposit(deposit).getOutcome());
        System.out.println("Replay rejected withdrawal: " + banking.withdraw(withdrawal).getOutcome());

        WithdrawalCommand newWithdrawal = new WithdrawalCommand(UUID.randomUUID(), alice, Money.hkd("80"));
        System.out.println("New withdrawal 80: " + banking.withdraw(newWithdrawal).getOutcome());
        System.out.println("Alice balance: " + hkd(banking.getBalance(alice).orElseThrow().getMinorUnits()));
        System.out.println("Bob balance: " + hkd(banking.getBalance(bob).orElseThrow().getMinorUnits()));
    }

    private static String hkd(long minorUnits) {
        return "HKD " + BigDecimal.valueOf(minorUnits, 2).toPlainString();
    }
}
