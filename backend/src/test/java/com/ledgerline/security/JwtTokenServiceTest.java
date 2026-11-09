package com.ledgerline.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.config.JwtProperties;
import com.ledgerline.config.JwtTestSupport;
import com.ledgerline.support.RandomJwtSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Unit test (no database, no Spring context): issuing and verifying tokens with the real encoder/decoder. */
class JwtTokenServiceTest {

    private final JwtProperties properties = new JwtProperties(RandomJwtSecret.newSecret(), "ledgerline", Duration.ofMinutes(15));
    private final JwtDecoder decoder = JwtTestSupport.decoder(properties);
    private final UUID userId = UUID.randomUUID();

    private JwtTokenService serviceAt(Instant now) {
        return new JwtTokenService(JwtTestSupport.encoder(properties), properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private String issueNow() {
        return serviceAt(Instant.now()).issue(userId).value();
    }

    @Test
    void issuesATokenWithOnlyIdentityAndValidityClaims() {
        JwtTokenService.IssuedToken issued = serviceAt(Instant.now()).issue(userId);
        Jwt jwt = decoder.decode(issued.value());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ledgerline");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "iat", "exp", "jti");
    }

    @Test
    void eachTokenHasItsOwnId() {
        assertThat(decoder.decode(issueNow()).getId()).isNotEqualTo(decoder.decode(issueNow()).getId());
    }

    @Test
    void rejectsATamperedPayload() {
        String[] parts = issueNow().split("\\.");
        String forgedPayload = base64Url("{\"iss\":\"ledgerline\",\"sub\":\"" + UUID.randomUUID()
                + "\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}");
        assertThatThrownBy(() -> decoder.decode(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        JwtProperties otherKey = new JwtProperties(RandomJwtSecret.newSecret(), "ledgerline", Duration.ofMinutes(15));
        String token = new JwtTokenService(JwtTestSupport.encoder(otherKey), otherKey, Clock.systemUTC()).issue(userId).value();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnUnsignedAlgNoneToken() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"ledgerline\",\"sub\":\"" + userId
                + "\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}");
        assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnotherIssuer() {
        JwtProperties otherIssuer = new JwtProperties(properties.secret(), "someone-else", Duration.ofMinutes(15));
        String token = new JwtTokenService(JwtTestSupport.encoder(otherIssuer), otherIssuer, Clock.systemUTC()).issue(userId).value();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenThatExpiredMoreThanTheAllowedSkewAgo() {
        // Issued 17 minutes ago, so it expired 2 minutes ago: beyond Spring's 60-second clock-skew allowance.
        String token = serviceAt(Instant.now().minus(Duration.ofMinutes(17))).issue(userId).value();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class).hasMessageContaining("expired");
    }

    @Test
    void acceptsATokenThatExpiredWithinTheSkewAllowance() {
        // Expired 30 seconds ago: still accepted, because of the 60-second clock-skew allowance.
        String token = serviceAt(Instant.now().minus(Duration.ofMinutes(15).plusSeconds(30))).issue(userId).value();
        assertThatCode(() -> decoder.decode(token)).doesNotThrowAnyException();
    }

    @Test
    void issuedTokenToStringHidesTheValue() {
        JwtTokenService.IssuedToken issued = serviceAt(Instant.now()).issue(userId);
        assertThat(issued.toString()).doesNotContain(issued.value());
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
