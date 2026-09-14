package com.example.banking.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

@Getter
@EqualsAndHashCode
@ToString
public final class DepositCommand implements BankingCommand {
    private final UUID requestId;
    private final UUID accountId;
    private final Money amount;

    public DepositCommand(UUID requestId, UUID accountId, Money amount) {
        CommandValidation.requireFields(requestId, accountId, amount);
        CommandValidation.requirePositiveAmount(amount);
        this.requestId = requestId;
        this.accountId = accountId;
        this.amount = amount;
    }

    @Override
    public Type getType() {
        return Type.DEPOSIT;
    }
}
