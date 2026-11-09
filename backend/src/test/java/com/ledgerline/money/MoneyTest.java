package com.ledgerline.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({"0.01,1", "1,100", "1.5,150", "1.50,150", "100,10000", "10.05,1005", "1000000,100000000",
            "1000000.00,100000000", "999999999.99,99999999999"})
    void convertsExactlyToPaise(String amount, long paise) {
        assertThat(Money.toMinor(amount)).isEqualTo(paise);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "00", "-1", "+1", "1.001", "1e3", "1E3", " 1", "1 ", "1,000", ".5",
            "1.", "01", "1000000000", "NaN", "Infinity", "0x10", "", "１"})
    void rejectsAnythingElse(String amount) {
        assertThat(amount.matches(Money.AMOUNT_REGEX)).isFalse();
        assertThatThrownBy(() -> Money.toMinor(amount)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsWithTwoDecimals() {
        assertThat(Money.format(0)).isEqualTo("0.00");
        assertThat(Money.format(5)).isEqualTo("0.05");
        assertThat(Money.format(50000)).isEqualTo("500.00");
        assertThat(Money.format(-30000)).isEqualTo("-300.00");
        assertThat(Money.format(1_000_000_000)).isEqualTo("10000000.00");
    }
}
