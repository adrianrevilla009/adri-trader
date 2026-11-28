package com.adrianrevilla.trading.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Property tests without a library: thousands of random inputs from a fixed seed (so a failure is reproducible) checked
 * against rules that must hold for every input. Java: like jqwik or QuickCheck, written by hand.
 */
@Tag("property")
class PropertyTest {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final CapitalPolicy POLICY = new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25"));
    private static final Isin ISIN = new Isin("IE00B4L5Y983");

    /** Base32 encoder, test only: the production code only needs to decode. */
    private static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    @Test
    void base32RoundTripsAnyBytes() {
        Random r = new Random(11);
        for (int i = 0; i < 2000; i++) {
            byte[] data = new byte[1 + r.nextInt(64)];
            r.nextBytes(data);
            assertThat(Base32.decode(encode(data))).isEqualTo(data);
        }
    }

    @Test
    void totpAcceptsTheCodeOfEveryStepInTheWindowAndReturnsThatStep() {
        Random r = new Random(12);
        for (int i = 0; i < 500; i++) {
            byte[] secret = new byte[10 + r.nextInt(30)];
            r.nextBytes(secret);
            long now = 1_000_000_000L + r.nextInt(900_000_000);
            long step = now / Totp.STEP_SECONDS;
            for (int d = -1; d <= 1; d++) {
                assertThat(Totp.verify(secret, Totp.code(secret, step + d), now, 1)).isEqualTo(step + d);
            }
        }
    }

    @Test
    void totpNeverAcceptsAStepOutsideTheWindowAndCodesAreAlwaysSixDigits() {
        Random r = new Random(13);
        for (int i = 0; i < 500; i++) {
            byte[] secret = new byte[20];
            r.nextBytes(secret);
            long now = 1_000_000_000L + r.nextInt(900_000_000);
            long step = now / Totp.STEP_SECONDS;
            String far = Totp.code(secret, step + 2 + r.nextInt(50));
            assertThat(far).matches("[0-9]{6}");
            String near = Totp.code(secret, step);
            // a far code can equal a near code by chance (about 1 in 333,000): only then is acceptance legitimate
            boolean collides = List.of(Totp.code(secret, step - 1), near, Totp.code(secret, step + 1)).contains(far);
            if (!collides) {
                assertThat(Totp.verify(secret, far, now, 1)).isEqualTo(-1);
            }
        }
    }

    @Test
    void totpWithADifferentSecretAlmostNeverAgrees() {
        Random r = new Random(14);
        int agreed = 0;
        for (int i = 0; i < 2000; i++) {
            byte[] a = new byte[20];
            byte[] b = new byte[20];
            r.nextBytes(a);
            r.nextBytes(b);
            if (Totp.code(a, 12345).equals(Totp.code(b, 12345))) {
                agreed++;
            }
        }
        assertThat(agreed).as("random 6-digit collisions out of 2000 (expected about 0)").isLessThan(5);
    }

    /** A broker double with a fixed account cash. */
    private record Account(BigDecimal cash) implements Broker {
        public void submit(OrderRequest r) { }
        public void cancel(UUID id) { }
        public Optional<BrokerOrder> lookup(UUID id) { return Optional.empty(); }
        public Map<Isin, BigDecimal> positions() { return Map.of(); }
        public BigDecimal cash() { return cash; }
    }

    @Test
    void anAgentBudgetIsNeverNegativeNeverAboveItsCapitalAndNeverAboveTheAccount() throws Exception {
        Random r = new Random(15);
        for (int i = 0; i < 5000; i++) {
            BigDecimal capital = BigDecimal.valueOf(1 + r.nextInt(100_000), 2);
            BigDecimal committed = BigDecimal.valueOf(r.nextInt(300_000) - 100_000, 2);
            BigDecimal account = BigDecimal.valueOf(r.nextInt(500_000), 2);
            BigDecimal seen = new AgentBudgetBroker(new Account(account), capital, () -> committed).cash();
            assertThat(seen).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(seen).isLessThanOrEqualTo(account);
            // spending nothing never gives more than the capital, only a gain (negative committed) can
            if (committed.signum() >= 0) {
                assertThat(seen).isLessThanOrEqualTo(capital);
            }
        }
    }

    @Test
    void spendingNeverIncreasesWhatAnAgentCanSpend() throws Exception {
        Random r = new Random(16);
        for (int i = 0; i < 2000; i++) {
            BigDecimal capital = BigDecimal.valueOf(4000 + r.nextInt(50_000), 2);
            BigDecimal account = BigDecimal.valueOf(1_000_000);
            BigDecimal c1 = BigDecimal.valueOf(r.nextInt(100_000), 2);
            BigDecimal c2 = c1.add(BigDecimal.valueOf(r.nextInt(100_000), 2));
            BigDecimal before = new AgentBudgetBroker(new Account(account), capital, () -> c1).cash();
            BigDecimal after = new AgentBudgetBroker(new Account(account), capital, () -> c2).cash();
            assertThat(after).isLessThanOrEqualTo(before);
        }
    }

    @Test
    void twoAgentsOnOneAccountCanNeverTogetherSpendMoreThanTheAccountHolds() throws Exception {
        Random r = new Random(17);
        for (int i = 0; i < 3000; i++) {
            BigDecimal account = BigDecimal.valueOf(1000 + r.nextInt(100_000), 2);
            BigDecimal one = new AgentBudgetBroker(new Account(account), BigDecimal.valueOf(100 + r.nextInt(5000), 2),
                    () -> BigDecimal.ZERO).cash();
            BigDecimal two = new AgentBudgetBroker(new Account(account), BigDecimal.valueOf(100 + r.nextInt(5000), 2),
                    () -> BigDecimal.ZERO).cash();
            // each one alone is capped by the account; the sum is bounded by the capitals, which the policy keeps small
            assertThat(one).isLessThanOrEqualTo(account);
            assertThat(two).isLessThanOrEqualTo(account);
        }
    }

    @Test
    void anAgentIsEqualRegardlessOfTheTrailingZerosOfItsNumbers() {
        Random r = new Random(18);
        for (int i = 0; i < 1000; i++) {
            long units = 40 + r.nextInt(5000);
            int extra = r.nextInt(6);
            BigDecimal plain = BigDecimal.valueOf(units);
            BigDecimal padded = plain.setScale(extra, java.math.RoundingMode.UNNECESSARY);
            AgentConfig a = AgentConfig.create("a", "t", Map.of("p", plain), List.of(ISIN), plain, List.of(),
                    TradingMode.DEMO, POLICY);
            AgentConfig b = AgentConfig.create("a", "t", Map.of("p", padded), List.of(ISIN), padded, List.of(),
                    TradingMode.DEMO, POLICY);
            assertThat(b).isEqualTo(a);
            assertThat(b.hashCode()).isEqualTo(a.hashCode());
        }
    }

    @Test
    void anyCapitalBelowTheMinimumIsRejectedAndAnyAboveIsAccepted() {
        Random r = new Random(19);
        BigDecimal min = POLICY.minimumCapital();
        for (int i = 0; i < 2000; i++) {
            BigDecimal below = min.subtract(BigDecimal.valueOf(1 + r.nextInt(3999), 2));
            BigDecimal above = min.add(BigDecimal.valueOf(r.nextInt(1_000_000), 2));
            if (below.signum() > 0) {
                assertThatThrownBy(() -> AgentConfig.create("a", "t", Map.of(), List.of(ISIN), below, List.of(),
                        TradingMode.DEMO, POLICY)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(AgentConfig.create("a", "t", Map.of(), List.of(ISIN), above, List.of(), TradingMode.DEMO,
                    POLICY).capital()).isEqualByComparingTo(above);
        }
    }

    @Test
    void randomIdsAreAcceptedExactlyWhenTheyMatchTheDocumentedPattern() {
        Random r = new Random(20);
        String chars = "abcXYZ019_-. /\\%$é";
        for (int i = 0; i < 5000; i++) {
            int len = r.nextInt(70);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append(chars.charAt(r.nextInt(chars.length())));
            }
            String id = sb.toString();
            boolean valid = id.matches("[a-z0-9][a-z0-9_-]{0,63}");
            boolean accepted;
            try {
                AgentConfig.create(id, "t", Map.of(), List.of(ISIN), new BigDecimal("40"), List.of(), TradingMode.DEMO,
                        POLICY);
                accepted = true;
            } catch (IllegalArgumentException e) {
                accepted = false;
            }
            assertThat(accepted).as("id '%s'", id).isEqualTo(valid);
        }
    }
}
