package com.example.banking.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

@Getter
@EqualsAndHashCode
@ToString
public final class WithdrawalCommand implements BankingCommand {
    private final UUID requestId;
    private final UUID accountId;
    private final Money amount;

    public WithdrawalCommand(UUID requestId, UUID accountId, Money amount) {
        CommandValidation.requireFields(requestId, accountId, amount);
        CommandValidation.requirePositiveAmount(amount);
        this.requestId = requestId;
        this.accountId = accountId;
        this.amount = amount;
    }

    @Override
    public Type getType() {
        return Type.WITHDRAWAL;
    }
}
