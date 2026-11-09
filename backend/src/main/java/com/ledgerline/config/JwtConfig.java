package com.ledgerline.config;

import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.UserJwtAuthenticationConverter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Signing and verification of access tokens (HS256, Nimbus via Spring Security). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        properties.validate();
        return encoder(properties.signingKey());
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        properties.validate();
        return decoder(properties.signingKey(), properties.issuer());
    }

    @Bean
    UserJwtAuthenticationConverter userJwtAuthenticationConverter(UserRepository users) {
        return new UserJwtAuthenticationConverter(users);
    }

    static JwtEncoder encoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * Accepts only HS256 tokens signed with our key (so "alg": "none" and other algorithms are rejected), from
     * our issuer, that have a subject and an expiry and are within their validity period. Spring's timestamp
     * check allows 60 seconds of clock skew.
     */
    static JwtDecoder decoder(SecretKey key, String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<Object>(JwtClaimNames.SUB, Objects::nonNull)));
        return decoder;
    }
}
