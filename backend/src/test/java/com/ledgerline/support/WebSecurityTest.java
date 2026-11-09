package com.ledgerline.support;

import com.ledgerline.config.JwtConfig;
import com.ledgerline.config.SecurityConfig;
import com.ledgerline.security.JwtTokenService;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * For {@code @WebMvcTest} classes: loads the real security configuration and JWT handling with a random key.
 * No database is involved; tests mock {@code UserRepository} where they need users.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({SecurityConfig.class, JwtConfig.class, JwtTokenService.class})
@ContextConfiguration(initializers = RandomJwtSecret.class)
public @interface WebSecurityTest {
}
