package com.example.banking.domain;

import com.example.banking.persistence.StoredOperation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.example.banking.domain.OperationResult.Outcome.ACCOUNT_NOT_FOUND;
import static com.example.banking.domain.OperationResult.Outcome.INSUFFICIENT_FUNDS;
import static org.assertj.core.api.Assertions.assertThat;

class ValueEqualityTest {
    private static final UUID REQUEST_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID DESTINATION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @ParameterizedTest
    @MethodSource("equivalentValues")
    void independentInstancesHaveValueEqualityAndWorkAsSetKeys(Object first, Object second, Object different) {
        assertThat(first).isNotSameAs(second).isEqualTo(second);
        assertThat(second).isEqualTo(first);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first).isNotEqualTo(different);

        Set<Object> values = new HashSet<>();
        values.add(first);
        assertThat(values).contains(second).doesNotContain(different);
    }

    static Stream<Arguments> equivalentValues() {
        return Stream.of(
                Arguments.of(Money.hkd("10.00"), Money.cents(1000), Money.cents(1001)),
                Arguments.of(snapshot(1000), snapshot(1000), snapshot(1001)),
                Arguments.of(success(1000), success(1000), success(1001)),
                Arguments.of(
                        OperationResult.rejected(REQUEST_ID, INSUFFICIENT_FUNDS),
                        OperationResult.rejected(REQUEST_ID, INSUFFICIENT_FUNDS),
                        OperationResult.rejected(REQUEST_ID, ACCOUNT_NOT_FOUND)),
                Arguments.of(
                        new CreateAccountCommand(REQUEST_ID, ACCOUNT_ID, Money.cents(0)),
                        new CreateAccountCommand(REQUEST_ID, ACCOUNT_ID, Money.hkd("0.00")),
                        new CreateAccountCommand(REQUEST_ID, DESTINATION_ID, Money.cents(0))),
                Arguments.of(
                        new DepositCommand(REQUEST_ID, ACCOUNT_ID, Money.cents(1000)),
                        new DepositCommand(REQUEST_ID, ACCOUNT_ID, Money.hkd("10.00")),
                        new DepositCommand(DESTINATION_ID, ACCOUNT_ID, Money.cents(1000))),
                Arguments.of(
                        new WithdrawalCommand(REQUEST_ID, ACCOUNT_ID, Money.cents(1000)),
                        new WithdrawalCommand(REQUEST_ID, ACCOUNT_ID, Money.hkd("10.00")),
                        new DepositCommand(REQUEST_ID, ACCOUNT_ID, Money.cents(1000))),
                Arguments.of(
                        new TransferCommand(REQUEST_ID, ACCOUNT_ID, DESTINATION_ID, Money.cents(1000)),
                        new TransferCommand(REQUEST_ID, ACCOUNT_ID, DESTINATION_ID, Money.hkd("10.00")),
                        new TransferCommand(REQUEST_ID, ACCOUNT_ID, REQUEST_ID, Money.cents(1000))),
                Arguments.of(
                        new StoredOperation("identity", success(1000)),
                        new StoredOperation("identity", success(1000)),
                        new StoredOperation("different-identity", success(1000))));
    }

    @ParameterizedTest
    @MethodSource("persistedIdentities")
    void commandIdentityRemainsCompatibleWithPreviouslyStoredRequests(BankingCommand command, String expected) {
        assertThat(command.identity()).isEqualTo(expected);
    }

    static Stream<Arguments> persistedIdentities() {
        return Stream.of(
                Arguments.of(new CreateAccountCommand(REQUEST_ID, ACCOUNT_ID, Money.cents(0)),
                        "v1|CREATE_ACCOUNT|20000000-0000-0000-0000-000000000002||HKD|0"),
                Arguments.of(new DepositCommand(REQUEST_ID, ACCOUNT_ID, Money.hkd("10.500")),
                        "v1|DEPOSIT|20000000-0000-0000-0000-000000000002||HKD|1050"),
                Arguments.of(new WithdrawalCommand(REQUEST_ID, ACCOUNT_ID, Money.cents(1050)),
                        "v1|WITHDRAWAL|20000000-0000-0000-0000-000000000002||HKD|1050"),
                Arguments.of(new TransferCommand(REQUEST_ID, ACCOUNT_ID, DESTINATION_ID, Money.cents(1050)),
                        "v1|TRANSFER|20000000-0000-0000-0000-000000000002|"
                                + "30000000-0000-0000-0000-000000000003|HKD|1050"));
    }

    private static AccountBalance snapshot(long amount) {
        return new AccountBalance(ACCOUNT_ID, amount);
    }

    private static OperationResult success(long amount) {
        return OperationResult.succeeded(REQUEST_ID, snapshot(amount), Optional.empty());
    }
}
