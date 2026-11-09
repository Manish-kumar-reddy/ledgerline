package com.ledgerline.support;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Gives database-free web tests a fresh random signing key, so no test key is ever committed and these tests
 * do not depend on a developer's JWT_SECRET. Use with {@code @ContextConfiguration(initializers = ...)}.
 */
public class RandomJwtSecret implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static String newSecret() {
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("randomJwtSecret", Map.of("ledgerline.jwt.secret", newSecret())));
    }
}
