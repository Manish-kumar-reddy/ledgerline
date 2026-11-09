package com.ledgerline.config;

import com.ledgerline.security.ProblemAccessDeniedHandler;
import com.ledgerline.security.ProblemAuthenticationEntryPoint;
import com.ledgerline.security.UserJwtAuthenticationConverter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless security: every request is authenticated by its own bearer token, using Spring Security's
 * built-in OAuth2 resource server support. No sessions, cookies, login form or HTTP Basic.
 *
 * CSRF protection is off because no cookie or session authenticates a request: a browser cannot attach the
 * bearer token to a forged cross-site request on its own.
 *
 * Authorization has two layers (README.md, "Authorization"):
 * <ol>
 *   <li>URL rules here, default deny: every endpoint must be listed with its method and who may call it; anything
 *       not listed is refused (403, or 401 without a token).</li>
 *   <li>{@code @PreAuthorize} on every service method ({@link EnableMethodSecurity}), so a service stays protected
 *       even if a future endpoint reaches it without a matching URL rule.</li>
 * </ol>
 * Roles come from PostgreSQL on every request (UserJwtAuthenticationConverter), never from the token.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties({LoginRateLimitProperties.class, RegistrationRateLimitProperties.class})
public class SecurityConfig {

    /**
     * Only in a web application. The same jar can run as a one-off migration job with
     * spring.main.web-application-type=none (see README.md, "Production"); there is no HTTP server to secure then,
     * and HttpSecurity does not exist, so this bean must not be created.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserJwtAuthenticationConverter userConverter,
            JsonMapper json) throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(json);
        ProblemAccessDeniedHandler accessDenied = new ProblemAccessDeniedHandler(json);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        // Account holders: their own accounts only (ownership enforced in AccountService).
                        .requestMatchers(HttpMethod.POST, "/api/accounts").hasRole("ACCOUNT_HOLDER")
                        .requestMatchers(HttpMethod.GET, "/api/accounts", "/api/accounts/*").hasRole("ACCOUNT_HOLDER")
                        // Money movement and statements on the holder's own accounts (ownership in the services
                        // and again in the PostgreSQL posting functions). ADMIN and AUDITOR never move money.
                        .requestMatchers(HttpMethod.POST, "/api/accounts/*/deposits", "/api/accounts/*/withdrawals",
                                "/api/accounts/*/transfers").hasRole("ACCOUNT_HOLDER")
                        .requestMatchers(HttpMethod.GET, "/api/accounts/*/statement").hasRole("ACCOUNT_HOLDER")
                        // Read-only oversight of every account.
                        .requestMatchers(HttpMethod.GET, "/api/oversight/accounts", "/api/oversight/accounts/*",
                                "/api/oversight/accounts/*/statement", "/api/oversight/reconciliation")
                                .hasAnyRole("ADMIN", "AUDITOR")
                        // Default deny: unlisted paths and methods (e.g. PUT/DELETE on an account) are refused.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(userConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied));
        return http.build();
    }

    /** BCrypt with a configurable cost factor (default 12, i.e. 2^12 rounds). */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${ledgerline.security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${ledgerline.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        // Only what the current API uses.
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("Location", "Retry-After", "Idempotent-Replayed"));
        cors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
