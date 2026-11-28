package com.adrianrevilla.trading.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TotpTest {
    /** RFC 6238 appendix B: the SHA-1 secret is the ASCII text 12345678901234567890; codes are the last 6 digits. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({"59, 287082", "1111111109, 081804", "1111111111, 050471", "1234567890, 005924", "2000000000, 279037"})
    void matchesTheRfc6238TestVectors(long seconds, String expected) {
        assertThat(Totp.code(RFC_SECRET, seconds / Totp.STEP_SECONDS)).isEqualTo(expected);
        assertThat(Totp.verify(RFC_SECRET, expected, seconds, 0)).isEqualTo(seconds / Totp.STEP_SECONDS);
    }

    @Test
    void acceptsTheNeighbouringStepsOnlyInsideTheWindow() {
        long now = 1_111_111_109L;
        String previous = Totp.code(RFC_SECRET, now / 30 - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, 1)).isEqualTo(now / 30 - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, 0)).isEqualTo(-1);
        String far = Totp.code(RFC_SECRET, now / 30 + 5);
        assertThat(Totp.verify(RFC_SECRET, far, now, 1)).isEqualTo(-1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "1234567", "abcdef", "12 456", "-12345", "287082 "})
    void rejectsAnythingThatIsNotSixDigits(String given) {
        assertThat(Totp.verify(RFC_SECRET, given, 59, 1)).isEqualTo(-1);
    }

    @Test
    void aWrongCodeIsRejected() {
        assertThat(Totp.verify(RFC_SECRET, "000000", 59, 1)).isEqualTo(-1);
    }

    @Test
    void base32DecodesRfcVectorsAndIgnoresCaseSpacesAndPadding() {
        assertThat(Base32.decode("MZXW6YTBOI======")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("mzxw 6ytb-oi")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).isEqualTo(RFC_SECRET);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "ABC1", "ABC!", "====="})
    void base32RejectsBadText(String text) {
        assertThatThrownBy(() -> Base32.decode(text)).isInstanceOf(IllegalArgumentException.class);
    }
}
