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
public final class TransferCommand implements BankingCommand {
    private final UUID requestId;
    private final UUID accountId;
    private final UUID toAccountId;
    private final Money amount;

    public TransferCommand(UUID requestId, UUID accountId, UUID toAccountId, Money amount) {
        CommandValidation.requireFields(requestId, accountId, amount);
        CommandValidation.requirePositiveAmount(amount);
        Objects.requireNonNull(toAccountId, "toAccountId");
        if (accountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Source and destination must differ");
        }
        this.requestId = requestId;
        this.accountId = accountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
    }

    @Override
    public Type getType() {
        return Type.TRANSFER;
    }

    @Override
    public Optional<UUID> getDestinationId() {
        return Optional.of(toAccountId);
    }
}
