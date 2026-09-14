package com.example.banking.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MoneyTest {
    @ParameterizedTest
    @ValueSource(strings = {"10.5", "10.50", "10.500", "1.05E1"})
    void normalizesExactCentAmounts(String value) {
        assertThat(Money.hkd(value)).isEqualTo(Money.cents(1050));
        assertThat(Money.hkd(value).toString()).isEqualTo("HKD 10.50");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.001", "10.501", "92233720368547758.08", "NaN", ""})
    void rejectsInvalidAmounts(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.hkd(value));
    }

    @Test
    void acceptsLargestRepresentableAmountWithoutRounding() {
        assertThat(Money.hkd("92233720368547758.07").getMinorUnits()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(100, Currency.getInstance("USD")));
    }

    @Test
    void onlyAccountCreationAcceptsZero() {
        UUID request = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        assertThat(new CreateAccountCommand(request, account, Money.cents(0))).isNotNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DepositCommand(request, account, Money.cents(0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WithdrawalCommand(request, account, Money.cents(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> new TransferCommand(
                request, account, UUID.randomUUID(), Money.cents(0)));
    }

    @Test
    void rejectsSelfTransferAndMissingIdentifiers() {
        UUID account = UUID.randomUUID();
        assertThatIllegalArgumentException().isThrownBy(() -> new TransferCommand(
                UUID.randomUUID(), account, account, Money.cents(1)));
        assertThatNullPointerException()
                .isThrownBy(() -> new DepositCommand(null, account, Money.cents(1)));
    }
}
