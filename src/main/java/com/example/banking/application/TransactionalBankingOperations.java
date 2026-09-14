package com.example.banking.application;

import com.example.banking.domain.AccountBalance;
import com.example.banking.domain.BankingCommand;
import com.example.banking.domain.Money;
import com.example.banking.domain.OperationResult;
import com.example.banking.persistence.BankingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.example.banking.domain.OperationResult.Outcome.ACCOUNT_ALREADY_EXISTS;
import static com.example.banking.domain.OperationResult.Outcome.ACCOUNT_NOT_FOUND;
import static com.example.banking.domain.OperationResult.Outcome.BALANCE_LIMIT_EXCEEDED;
import static com.example.banking.domain.OperationResult.Outcome.INSUFFICIENT_FUNDS;
import static com.example.banking.domain.OperationResult.Outcome.SUCCEEDED;

@Service
@RequiredArgsConstructor
public class TransactionalBankingOperations {
    private final BankingStore store;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            timeout = 5, rollbackFor = Exception.class)
    public OperationResult execute(BankingCommand command) {
        store.claim(command);

        OperationResult result = command.getType() == BankingCommand.Type.CREATE_ACCOUNT
                ? create(command) : changeBalances(command);

        store.complete(result);
        return result;
    }

    private OperationResult create(BankingCommand command) {
        if (!store.createAccount(command.getAccountId(), command.getAmount())) {
            return OperationResult.rejected(command.getRequestId(), ACCOUNT_ALREADY_EXISTS);
        }

        AccountBalance account = new AccountBalance(command.getAccountId(), command.getAmount());
        if (command.getAmount().getMinorUnits() > 0) {
            store.addMovement(command.getRequestId(), account, command.getAmount().getMinorUnits());
        }
        return OperationResult.succeeded(command.getRequestId(), account, Optional.empty());
    }

    private OperationResult changeBalances(BankingCommand command) {
        Optional<Map<UUID, AccountBalance>> lockedAccounts = lockAccounts(command);
        if (lockedAccounts.isEmpty()) {
            return OperationResult.rejected(command.getRequestId(), ACCOUNT_NOT_FOUND);
        }

        long sourceDelta = sourceDelta(command);
        OperationResult result = calculateBalances(command, lockedAccounts.orElseThrow(), sourceDelta);
        if (result.getOutcome() != SUCCEEDED) {
            return result;
        }

        updateBalances(result);
        recordMovements(result, sourceDelta, command.getAmount().getMinorUnits());
        return result;
    }

    private Optional<Map<UUID, AccountBalance>> lockAccounts(BankingCommand command) {
        Map<UUID, AccountBalance> lockedAccounts = new HashMap<>();
        for (UUID accountId : orderedAccountIds(command)) {
            Optional<AccountBalance> account = store.lockAccount(accountId);
            if (account.isEmpty()) {
                return Optional.empty();
            }
            lockedAccounts.put(accountId, account.orElseThrow());
        }
        return Optional.of(Map.copyOf(lockedAccounts));
    }

    private static List<UUID> orderedAccountIds(BankingCommand command) {
        List<UUID> accountIds = new ArrayList<>();
        accountIds.add(command.getAccountId());
        Optional<UUID> destinationId = command.getDestinationId();
        if (destinationId.isPresent()) {
            accountIds.add(destinationId.orElseThrow());
        }
        accountIds.sort(Comparator.comparing(UUID::toString));
        return List.copyOf(accountIds);
    }

    private static OperationResult calculateBalances(BankingCommand command,
                                                     Map<UUID, AccountBalance> accounts, long sourceDelta) {
        AccountBalance source = accounts.get(command.getAccountId());
        long amount = command.getAmount().getMinorUnits();
        if (sourceDelta < 0 && source.getBalance().getMinorUnits() < amount) {
            return OperationResult.rejected(command.getRequestId(), INSUFFICIENT_FUNDS);
        }

        try {
            AccountBalance updatedSource = add(source, sourceDelta);
            Optional<AccountBalance> updatedDestination = command.getDestinationId()
                    .map(accountId -> add(accounts.get(accountId), amount));
            return OperationResult.succeeded(command.getRequestId(), updatedSource, updatedDestination);
        } catch (ArithmeticException exception) {
            return OperationResult.rejected(command.getRequestId(), BALANCE_LIMIT_EXCEEDED);
        }
    }

    private static long sourceDelta(BankingCommand command) {
        long amount = command.getAmount().getMinorUnits();
        return command.getType() == BankingCommand.Type.DEPOSIT ? amount : -amount;
    }

    private void updateBalances(OperationResult result) {
        store.updateBalance(result.getAccount().orElseThrow());
        if (result.getDestination().isPresent()) {
            store.updateBalance(result.getDestination().orElseThrow());
        }
    }

    private void recordMovements(OperationResult result, long sourceDelta, long destinationDelta) {
        store.addMovement(result.getRequestId(), result.getAccount().orElseThrow(), sourceDelta);
        if (result.getDestination().isPresent()) {
            store.addMovement(result.getRequestId(), result.getDestination().orElseThrow(), destinationDelta);
        }
    }

    private static AccountBalance add(AccountBalance account, long delta) {
        long updatedAmount = Math.addExact(account.getBalance().getMinorUnits(), delta);
        return new AccountBalance(account.getAccountId(), Money.cents(updatedAmount));
    }
}
