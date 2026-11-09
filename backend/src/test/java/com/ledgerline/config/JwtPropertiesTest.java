package com.ledgerline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.support.RandomJwtSecret;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test (no database, no Spring). */
class JwtPropertiesTest {

    private static JwtProperties withSecret(String secret) {
        return new JwtProperties(secret, "ledgerline", Duration.ofMinutes(15));
    }

    @Test
    void acceptsA48ByteKey() {
        assertThat(withSecret(RandomJwtSecret.newSecret()).signingKey().getEncoded()).hasSize(48);
    }

    @Test
    void acceptsExactly32Bytes() {
        assertThat(withSecret(Base64.getEncoder().encodeToString(new byte[32])).signingKey().getEncoded()).hasSize(32);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void rejectsAMissingSecret(String secret) {
        assertThatThrownBy(() -> withSecret(secret).signingKey()).hasMessageContaining("is not set");
    }

    @Test
    void rejectsAKeyShorterThan32BytesWithoutRevealingIt() {
        String shortSecret = Base64.getEncoder().encodeToString("only-31-bytes-long-secret-value".getBytes());
        assertThatThrownBy(() -> withSecret(shortSecret).signingKey())
                .hasMessageContaining("at least 32 bytes")
                .message().doesNotContain(shortSecret).doesNotContain("only-31");
    }

    @Test
    void rejectsNonBase64WithoutRevealingIt() {
        String bad = "this is not base64 !!! " + "x".repeat(40);
        assertThatThrownBy(() -> withSecret(bad).signingKey())
                .hasMessageContaining("not valid Base64")
                .message().doesNotContain("this is not");
    }

    @Test
    void rejectsANonPositiveLifetime() {
        assertThatThrownBy(() -> new JwtProperties(RandomJwtSecret.newSecret(), "ledgerline", Duration.ZERO).validate())
                .hasMessageContaining("access-token-ttl");
    }

    @Test
    void toStringHidesTheSecret() {
        String secret = RandomJwtSecret.newSecret();
        assertThat(withSecret(secret).toString()).doesNotContain(secret).contains("<hidden>");
    }
}
