package com.example.banking.application;

import com.example.banking.domain.AccountBalance;
import com.example.banking.domain.BankingCommand;
import com.example.banking.domain.CreateAccountCommand;
import com.example.banking.domain.DepositCommand;
import com.example.banking.domain.OperationResult;
import com.example.banking.domain.TransferCommand;
import com.example.banking.domain.WithdrawalCommand;
import com.example.banking.persistence.BankingStore;
import com.example.banking.persistence.RequestAlreadyClaimed;
import com.example.banking.persistence.StoredOperation;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankingService {
    private static final Logger LOG = LoggerFactory.getLogger(BankingService.class);
    private static final int MAX_ATTEMPTS = 3;
    private final TransactionalBankingOperations transactions;
    private final BankingStore store;
    private final RetryDelay retryDelay;

    public OperationResult createAccount(CreateAccountCommand command) {
        return execute(command);
    }

    public OperationResult deposit(DepositCommand command) {
        return execute(command);
    }

    public OperationResult withdraw(WithdrawalCommand command) {
        return execute(command);
    }

    public OperationResult transfer(TransferCommand command) {
        return execute(command);
    }

    public Optional<AccountBalance> getBalance(UUID accountId) {
        requireNoCallerTransaction();
        return store.findAccount(Objects.requireNonNull(accountId, "accountId"));
    }

    public Optional<OperationResult> getOperation(UUID requestId) {
        requireNoCallerTransaction();
        return store.findOperation(Objects.requireNonNull(requestId, "requestId"))
                .map(StoredOperation::getResult);
    }

    private OperationResult execute(BankingCommand command) {
        Objects.requireNonNull(command, "command");
        requireNoCallerTransaction();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(command);
            } catch (RequestAlreadyClaimed exception) {
                return replay(command);
            } catch (PessimisticLockingFailureException | CannotCreateTransactionException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new BankingException.TemporarilyUnavailable(command.getRequestId(), exception);
                }
                LOG.info("Retrying request {} after transient failure; attempt {}", command.getRequestId(), attempt);
                pause(command.getRequestId(), attempt);
            } catch (DataAccessResourceFailureException | TransientDataAccessResourceException
                     | RecoverableDataAccessException | TransactionException exception) {
                throw new BankingException.OutcomeUnknown(command.getRequestId(), exception);
            }
        }
        throw new IllegalStateException("Retry loop exhausted unexpectedly");
    }

    private OperationResult replay(BankingCommand command) {
        StoredOperation stored = findCommittedOperation(command.getRequestId());
        if (!stored.getCommandIdentity().equals(command.identity())) {
            throw new BankingException.IdempotencyConflict(command.getRequestId());
        }
        return stored.getResult();
    }

    private StoredOperation findCommittedOperation(UUID requestId) {
        try {
            return store.findOperation(requestId)
                    .orElseThrow(() -> new BankingException.OutcomeUnknown(requestId,
                            new IllegalStateException("Committed operation is not available")));
        } catch (DataAccessResourceFailureException | TransientDataAccessResourceException
                 | RecoverableDataAccessException exception) {
            throw new BankingException.OutcomeUnknown(requestId, exception);
        }
    }

    private void pause(UUID requestId, int attempt) {
        try {
            retryDelay.pause(attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BankingException.TemporarilyUnavailable(requestId, exception);
        }
    }

    private static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("BankingService must be called outside a caller-owned transaction");
        }
    }
}
