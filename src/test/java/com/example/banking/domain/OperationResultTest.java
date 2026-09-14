package com.example.banking.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.UUID;

import static com.example.banking.domain.OperationResult.Outcome.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OperationResultTest {
    private final UUID requestId = UUID.randomUUID();
    private final AccountBalance account = new AccountBalance(UUID.randomUUID(), Money.cents(100));
    private final AccountBalance destination = new AccountBalance(UUID.randomUUID(), Money.cents(20));

    @Test
    void successRequiresAnAccountEvenWhenDestinationIsPresent() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OperationResult(requestId, SUCCEEDED, Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OperationResult(requestId, SUCCEEDED, Optional.empty(), Optional.of(destination)));
    }

    @ParameterizedTest
    @EnumSource(value = OperationResult.Outcome.class, names = "SUCCEEDED", mode = EnumSource.Mode.EXCLUDE)
    void rejectionCannotContainEitherSnapshot(OperationResult.Outcome outcome) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OperationResult(requestId, outcome, Optional.of(account), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OperationResult(requestId, outcome, Optional.empty(), Optional.of(destination)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OperationResult(requestId, outcome, Optional.of(account), Optional.of(destination)));
        OperationResult result = OperationResult.rejected(requestId, outcome);
        assertThat(result.getOutcome()).isEqualTo(outcome);
        assertThat(result.getAccount()).isEmpty();
        assertThat(result.getDestination()).isEmpty();
    }

    @Test
    void successFactoryPreservesSingleAccountAndTransferSnapshots() {
        OperationResult deposit = OperationResult.succeeded(requestId, account, Optional.empty());
        assertThat(deposit.getRequestId()).isEqualTo(requestId);
        assertThat(deposit.getOutcome()).isEqualTo(SUCCEEDED);
        assertThat(deposit.getAccount()).contains(account);
        assertThat(deposit.getDestination()).isEmpty();

        OperationResult transfer = OperationResult.succeeded(requestId, account, Optional.of(destination));
        assertThat(transfer).isEqualTo(new OperationResult(
                requestId, SUCCEEDED, Optional.of(account), Optional.of(destination)));
    }

    @Test
    void rejectionFactoryDoesNotAcceptSuccess() {
        assertThatIllegalArgumentException().isThrownBy(() -> OperationResult.rejected(requestId, SUCCEEDED));
    }
}
