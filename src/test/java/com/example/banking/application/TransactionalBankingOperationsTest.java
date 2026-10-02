package com.example.banking.application;

import com.example.banking.domain.AccountBalance;
import com.example.banking.domain.Money;
import com.example.banking.domain.OperationResult;
import com.example.banking.domain.TransferCommand;
import com.example.banking.persistence.BankingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static com.example.banking.domain.OperationResult.Outcome.BALANCE_LIMIT_EXCEEDED;
import static com.example.banking.domain.OperationResult.Outcome.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TransactionalBankingOperationsTest {
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private final BankingStore store = mock(BankingStore.class);
    private final TransactionalBankingOperations operations = new TransactionalBankingOperations(store);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transfersLockInStringOrderRegardlessOfDirection(boolean reverse) {
        UUID sourceId = reverse ? SECOND_ID : FIRST_ID;
        UUID destinationId = reverse ? FIRST_ID : SECOND_ID;
        TransferCommand command = new TransferCommand(UUID.randomUUID(), sourceId, destinationId, Money.cents(30));
        when(store.lockAccount(FIRST_ID)).thenReturn(Optional.of(new AccountBalance(FIRST_ID, 100)));
        when(store.lockAccount(SECOND_ID)).thenReturn(Optional.of(new AccountBalance(SECOND_ID, 100)));

        OperationResult result = operations.execute(command);

        assertThat(result.getOutcome()).isEqualTo(SUCCEEDED);
        assertThat(result.getAccount()).contains(new AccountBalance(sourceId, 70));
        assertThat(result.getDestination()).contains(new AccountBalance(destinationId, 130));
        InOrder order = inOrder(store);
        order.verify(store).claim(command);
        order.verify(store).lockAccount(FIRST_ID);
        order.verify(store).lockAccount(SECOND_ID);
        order.verify(store, times(2)).updateBalance(any(AccountBalance.class));
        order.verify(store, times(2)).addMovement(eq(command.getRequestId()), any(AccountBalance.class), anyLong());
        order.verify(store).complete(result);
        verify(store).updateBalance(result.getAccount().orElseThrow());
        verify(store).updateBalance(result.getDestination().orElseThrow());
        verify(store).addMovement(command.getRequestId(), result.getAccount().orElseThrow(), -30);
        verify(store).addMovement(command.getRequestId(), result.getDestination().orElseThrow(), 30);
        verifyNoMoreInteractions(store);
    }

    @Test
    void destinationOverflowIsRejectedBeforeAnyBalanceOrMovementWrite() {
        TransferCommand command = new TransferCommand(UUID.randomUUID(), FIRST_ID, SECOND_ID, Money.cents(1));
        when(store.lockAccount(FIRST_ID)).thenReturn(Optional.of(new AccountBalance(FIRST_ID, 100)));
        when(store.lockAccount(SECOND_ID))
                .thenReturn(Optional.of(new AccountBalance(SECOND_ID, Long.MAX_VALUE)));

        OperationResult result = operations.execute(command);

        assertThat(result).isEqualTo(OperationResult.rejected(command.getRequestId(), BALANCE_LIMIT_EXCEEDED));
        verify(store).claim(command);
        verify(store).lockAccount(FIRST_ID);
        verify(store).lockAccount(SECOND_ID);
        verify(store).complete(result);
        verifyNoMoreInteractions(store);
    }
}
