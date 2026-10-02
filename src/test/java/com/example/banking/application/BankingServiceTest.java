package com.example.banking.application;

import com.example.banking.domain.AccountBalance;
import com.example.banking.domain.DepositCommand;
import com.example.banking.domain.Money;
import com.example.banking.domain.OperationResult;
import com.example.banking.persistence.BankingStore;
import com.example.banking.persistence.RequestAlreadyClaimed;
import com.example.banking.persistence.StoredOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.example.banking.domain.OperationResult.Outcome.ACCOUNT_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BankingServiceTest {
    private final TransactionalBankingOperations transactions = mock(TransactionalBankingOperations.class);
    private final BankingStore store = mock(BankingStore.class);
    private final RetryDelay delay = mock(RetryDelay.class);
    private final DepositCommand command = new DepositCommand(UUID.randomUUID(), UUID.randomUUID(), Money.cents(10));
    private final OperationResult rejection = OperationResult.rejected(command.getRequestId(), ACCOUNT_NOT_FOUND);
    private BankingService banking;

    @BeforeEach
    void setUp() {
        banking = new BankingService(transactions, store, delay);
    }

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void retriesSafeFailureThenReturnsResult(RuntimeException failure) throws InterruptedException {
        OperationResult success = OperationResult.succeeded(command.getRequestId(),
                new AccountBalance(command.getAccountId(), 10), Optional.empty());
        when(transactions.execute(command)).thenThrow(failure)
                .thenReturn(success);
        assertThat(banking.deposit(command)).isEqualTo(success);
        verify(transactions, times(2)).execute(command);
        verify(delay).pause(1);
    }

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void boundsAttemptsAndDoesNotSleepAfterLastFailure(RuntimeException failure) throws InterruptedException {
        when(transactions.execute(command)).thenThrow(failure);
        assertThatThrownBy(() -> banking.deposit(command))
                .isInstanceOf(BankingException.TemporarilyUnavailable.class).hasCause(failure);
        verify(transactions, times(3)).execute(command);
        verify(delay).pause(1);
        verify(delay).pause(2);
        verifyNoMoreInteractions(delay);
    }

    @Test
    void businessRejectionDoesNotRetry() {
        when(transactions.execute(command)).thenReturn(rejection);
        assertThat(banking.deposit(command)).isEqualTo(rejection);
        verify(transactions).execute(command);
        verifyNoInteractions(delay);
    }

    @Test
    void ambiguousCommitIsUnknownAndNeverAutomaticallyRetried() {
        when(transactions.execute(command)).thenThrow(new TransactionSystemException("commit connection lost"));
        assertThatThrownBy(() -> banking.deposit(command)).isInstanceOf(BankingException.OutcomeUnknown.class)
                .hasMessageContaining("same request ID");
        verify(transactions).execute(command);
        verifyNoInteractions(delay);
    }

    @ParameterizedTest
    @MethodSource("resourceFailures")
    void resourceFailureIsUnknownAndNotRetried(DataAccessException failure) {
        when(transactions.execute(command)).thenThrow(failure);
        assertThatThrownBy(() -> banking.deposit(command))
                .isInstanceOf(BankingException.OutcomeUnknown.class).hasCause(failure);
        verify(transactions).execute(command);
        verifyNoInteractions(delay, store);
        verifyNoMoreInteractions(transactions);
    }

    @Test
    void unexpectedConstraintFailureDoesNotRetry() {
        when(transactions.execute(command)).thenThrow(new DataIntegrityViolationException("broken invariant"));
        assertThatThrownBy(() -> banking.deposit(command)).isInstanceOf(DataIntegrityViolationException.class);
        verify(transactions).execute(command);
        verifyNoInteractions(delay);
    }

    @Test
    void interruptionStopsRetriesAndPreservesInterruptFlag() throws InterruptedException {
        when(transactions.execute(command)).thenThrow(new CannotAcquireLockException("deadlock"));
        doThrow(new InterruptedException("cancelled")).when(delay).pause(1);
        try {
            assertThatThrownBy(() -> banking.deposit(command))
                    .isInstanceOf(BankingException.TemporarilyUnavailable.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(transactions).execute(command);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void duplicateReplaysOriginalResult() {
        when(transactions.execute(command)).thenThrow(new RequestAlreadyClaimed(new RuntimeException()));
        when(store.findOperation(command.getRequestId()))
                .thenReturn(Optional.of(new StoredOperation(command.identity(), rejection)));
        assertThat(banking.deposit(command)).isEqualTo(rejection);
        verifyNoInteractions(delay);
    }

    @Test
    void missingDuplicateResultIsUnknown() {
        when(transactions.execute(command)).thenThrow(new RequestAlreadyClaimed(new RuntimeException()));
        when(store.findOperation(command.getRequestId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> banking.deposit(command)).isInstanceOf(BankingException.OutcomeUnknown.class);
    }

    @ParameterizedTest
    @MethodSource("resourceFailures")
    void replayResourceFailureIsUnknownAndDoesNotExecuteAgain(DataAccessException failure) {
        when(transactions.execute(command)).thenThrow(new RequestAlreadyClaimed(new RuntimeException()));
        when(store.findOperation(command.getRequestId())).thenThrow(failure);

        assertThatThrownBy(() -> banking.deposit(command))
                .isInstanceOf(BankingException.OutcomeUnknown.class).hasCause(failure);
        verify(transactions).execute(command);
        verify(store).findOperation(command.getRequestId());
        verifyNoInteractions(delay);
        verifyNoMoreInteractions(transactions, store);
    }

    static Stream<RuntimeException> retryableFailures() {
        return Stream.of(new CannotAcquireLockException("deadlock"),
                new CannotCreateTransactionException("connection unavailable"));
    }

    static Stream<DataAccessException> resourceFailures() {
        return Stream.of(new DataAccessResourceFailureException("disconnected"),
                new TransientDataAccessResourceException("temporarily disconnected"),
                new RecoverableDataAccessException("connection must be replaced"));
    }
}
