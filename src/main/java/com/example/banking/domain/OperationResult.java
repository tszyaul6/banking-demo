package com.example.banking.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Getter
@EqualsAndHashCode
@ToString
public final class OperationResult {
    private final UUID requestId;
    private final Outcome outcome;
    private final Optional<AccountBalance> account;
    private final Optional<AccountBalance> destination;

    public OperationResult(UUID requestId, Outcome outcome, Optional<AccountBalance> account,
                           Optional<AccountBalance> destination) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.account = Objects.requireNonNull(account, "account");
        this.destination = Objects.requireNonNull(destination, "destination");
        if (outcome == Outcome.SUCCEEDED && account.isEmpty()) {
            throw new IllegalArgumentException("A successful result must include an account snapshot");
        }
        if (outcome != Outcome.SUCCEEDED && (account.isPresent() || destination.isPresent())) {
            throw new IllegalArgumentException("A rejected result cannot include account snapshots");
        }
    }

    public enum Outcome {
        SUCCEEDED,
        INSUFFICIENT_FUNDS,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_ALREADY_EXISTS,
        BALANCE_LIMIT_EXCEEDED
    }

    public static OperationResult succeeded(UUID requestId, AccountBalance account,
                                            Optional<AccountBalance> destination) {
        return new OperationResult(requestId, Outcome.SUCCEEDED, Optional.of(account), destination);
    }

    public static OperationResult rejected(UUID requestId, Outcome outcome) {
        if (outcome == Outcome.SUCCEEDED) {
            throw new IllegalArgumentException("A rejection cannot succeed");
        }
        return new OperationResult(requestId, outcome, Optional.empty(), Optional.empty());
    }
}
