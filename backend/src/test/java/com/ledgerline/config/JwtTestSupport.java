package com.ledgerline.config;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/** Gives tests outside this package the exact encoder and decoder the application builds. */
public final class JwtTestSupport {

    private JwtTestSupport() {
    }

    public static JwtEncoder encoder(JwtProperties properties) {
        return JwtConfig.encoder(properties.signingKey());
    }

    public static JwtDecoder decoder(JwtProperties properties) {
        return JwtConfig.decoder(properties.signingKey(), properties.issuer());
    }
}
